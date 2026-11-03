//! Centinela Autónomo de Memoria y Rendimiento (`JettraPolice`) para JettraRDB.
//! Monitorea el uso de memoria del proceso/sistema, previene saturaciones OOM,
//! activa paginación perezosa (`LazyPagedCursor`) y emite alertas predictivas.

use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionType {
    Permitted,
    AutoPaginateLazy,
    AdaptiveThrottle,
    RejectMemoryOverflow,
}

#[derive(Debug, Clone)]
pub struct PoliceDecision {
    pub action: ActionType,
    pub enforced_limit: usize,
    pub estimated_memory_bytes: u64,
    pub current_memory_ratio: f64,
    pub rationale: String,
    pub intervention_required: bool,
}

#[derive(Debug, Clone)]
pub struct PoliceAlert {
    pub timestamp: u64,
    pub level: String,
    pub category: String,
    pub operation: String,
    pub message: String,
    pub memory_usage_pct: f64,
}

pub struct LazyPagedCursor<T: Clone> {
    items: Vec<T>,
    page_size: usize,
    offset: usize,
}

impl<T: Clone> LazyPagedCursor<T> {
    pub fn new(items: Vec<T>, page_size: usize) -> Self {
        Self {
            items,
            page_size: page_size.clamp(1, 2000),
            offset: 0,
        }
    }

    pub fn has_next_page(&self) -> bool {
        self.offset < self.items.len()
    }

    pub fn next_page(&mut self) -> Vec<T> {
        if !self.has_next_page() {
            return Vec::new();
        }
        let end = (self.offset + self.page_size).min(self.items.len());
        let page = self.items[self.offset..end].to_vec();
        self.offset = end;
        page
    }

    pub fn current_offset(&self) -> usize {
        self.offset
    }

    pub fn page_size(&self) -> usize {
        self.page_size
    }

    pub fn total_items(&self) -> usize {
        self.items.len()
    }
}

#[derive(Debug, Clone)]
pub struct JettraPolice {
    autonomous_mode: Arc<AtomicBool>,
    interventions_count: Arc<AtomicU64>,
    prevented_oom_count: Arc<AtomicU64>,
    max_memory_budget_bytes: Arc<AtomicU64>,
    alert_log: Arc<RwLock<Vec<PoliceAlert>>>,
}

impl Default for JettraPolice {
    fn default() -> Self {
        Self::new(4 * 1024 * 1024 * 1024)
    }
}

impl JettraPolice {
    pub const CRITICAL_MEMORY_THRESHOLD: f64 = 0.85;
    pub const WARNING_MEMORY_THRESHOLD: f64 = 0.70;
    pub const SAFE_BATCH_PAGE_SIZE: usize = 250;
    pub const EMERGENCY_PAGE_SIZE: usize = 50;

    pub fn new(max_memory_budget_bytes: u64) -> Self {
        Self {
            autonomous_mode: Arc::new(AtomicBool::new(true)),
            interventions_count: Arc::new(AtomicU64::new(0)),
            prevented_oom_count: Arc::new(AtomicU64::new(0)),
            max_memory_budget_bytes: Arc::new(AtomicU64::new(max_memory_budget_bytes.max(64 * 1024 * 1024))),
            alert_log: Arc::new(RwLock::new(Vec::new())),
        }
    }

    /// Obtiene la memoria RSS actual del proceso en Linux (`/proc/self/statm`), o un estimado seguro.
    pub fn process_rss_bytes(&self) -> u64 {
        if let Ok(statm) = fs::read_to_string("/proc/self/statm") {
            if let Some(rss_pages) = statm.split_whitespace().nth(1).and_then(|s| s.parse::<u64>().ok()) {
                return rss_pages * 4096;
            }
        }
        16 * 1024 * 1024
    }

    pub fn current_memory_ratio(&self) -> f64 {
        let used = self.process_rss_bytes() as f64;
        let max = self.max_memory_budget_bytes.load(Ordering::Relaxed) as f64;
        (used / max).clamp(0.0, 1.0)
    }

    pub fn evaluate_memory_safety(
        &self,
        operation: &str,
        target: &str,
        total_records: usize,
        requested_limit: usize,
        avg_record_bytes: u64,
    ) -> PoliceDecision {
        let ratio = self.current_memory_ratio();
        let max_budget = self.max_memory_budget_bytes.load(Ordering::Relaxed);
        let used = self.process_rss_bytes();
        let free_bytes = max_budget.saturating_sub(used);

        let effective_rows = if requested_limit > 0 {
            requested_limit.min(total_records)
        } else {
            total_records
        };
        let estimated_bytes = (effective_rows as u64).saturating_mul(avg_record_bytes.max(256)) * 2;

        if !self.autonomous_mode.load(Ordering::Relaxed) {
            return PoliceDecision {
                action: ActionType::Permitted,
                enforced_limit: effective_rows.max(1),
                estimated_memory_bytes: estimated_bytes,
                current_memory_ratio: ratio,
                rationale: "Autonomous mode disabled".to_string(),
                intervention_required: false,
            };
        }

        // Caso 1: Presión crítica de memoria o proyección de desborde
        if ratio >= Self::CRITICAL_MEMORY_THRESHOLD || estimated_bytes > (free_bytes * 75 / 100) {
            self.interventions_count.fetch_add(1, Ordering::Relaxed);
            self.prevented_oom_count.fetch_add(1, Ordering::Relaxed);

            let safe_limit = if ratio >= 0.92 {
                Self::EMERGENCY_PAGE_SIZE
            } else {
                Self::SAFE_BATCH_PAGE_SIZE
            };
            let rationale = format!(
                "JettraPolice evitó desborde de RAM en {} sobre '{}' (Registros: {}, Proyección: {} MB, RAM Libre: {} MB). Activada paginación lazy a {} filas.",
                operation,
                target,
                total_records,
                estimated_bytes / (1024 * 1024),
                free_bytes / (1024 * 1024),
                safe_limit
            );
            self.record_alert("CRITICAL", "MEMORY_EXHAUSTION_PREVENTED", operation, &rationale, ratio);
            return PoliceDecision {
                action: ActionType::AutoPaginateLazy,
                enforced_limit: safe_limit,
                estimated_memory_bytes: estimated_bytes,
                current_memory_ratio: ratio,
                rationale,
                intervention_required: true,
            };
        }

        // Caso 2: Consulta masiva sin límite explícito (> 10,000 registros)
        if total_records > 10_000 && (requested_limit == 0 || requested_limit > 2_500) {
            self.interventions_count.fetch_add(1, Ordering::Relaxed);
            let enforced = if ratio >= Self::WARNING_MEMORY_THRESHOLD {
                Self::SAFE_BATCH_PAGE_SIZE
            } else {
                1000
            };
            let rationale = format!(
                "JettraPolice detectó escaneo masivo en '{}' ({} registros). Aplicado streaming paginado seguro de {} registros.",
                target, total_records, enforced
            );
            self.record_alert("WARNING", "MASSIVE_SCAN_PAGINATED", operation, &rationale, ratio);
            return PoliceDecision {
                action: ActionType::AutoPaginateLazy,
                enforced_limit: enforced,
                estimated_memory_bytes: estimated_bytes,
                current_memory_ratio: ratio,
                rationale,
                intervention_required: true,
            };
        }

        let default_limit = if requested_limit > 0 { requested_limit } else { 500 };
        PoliceDecision {
            action: ActionType::Permitted,
            enforced_limit: default_limit,
            estimated_memory_bytes: estimated_bytes,
            current_memory_ratio: ratio,
            rationale: "Memory safety verified".to_string(),
            intervention_required: false,
        }
    }

    pub fn record_alert(
        &self,
        level: &str,
        category: &str,
        operation: &str,
        message: &str,
        ratio: f64,
    ) {
        let mut guard = self.alert_log.write().unwrap();
        if guard.len() >= 200 {
            guard.remove(0);
        }
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        guard.push(PoliceAlert {
            timestamp: ts,
            level: level.to_string(),
            category: category.to_string(),
            operation: operation.to_string(),
            message: message.to_string(),
            memory_usage_pct: (ratio * 10000.0).round() / 100.0,
        });
    }

    pub fn recent_alerts(&self) -> Vec<PoliceAlert> {
        self.alert_log.read().unwrap().clone()
    }

    pub fn interventions_count(&self) -> u64 {
        self.interventions_count.load(Ordering::Relaxed)
    }

    pub fn prevented_oom_count(&self) -> u64 {
        self.prevented_oom_count.load(Ordering::Relaxed)
    }
}


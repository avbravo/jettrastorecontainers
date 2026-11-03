//! Motor de Clúster Distribuido, Protocolo Binario Raft (`JettraClusterProtocol` `0x4A54` `'JT'`)
//! y Anillo Dinámico (`DynamicRingEngine`) de JettraRDB.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const JETTRA_CLUSTER_MAGIC: u16 = 0x4A54; // 'JT'

// Tipos de trama compatibles 100% a nivel de byte con JettraStore
pub const TYPE_HEARTBEAT: u8 = 0x01;
pub const TYPE_HEARTBEAT_ACK: u8 = 0x02;
pub const TYPE_CREATE_DATABASE: u8 = 0x03;
pub const TYPE_DROP_DATABASE: u8 = 0x04;
pub const TYPE_PUT_RECORD: u8 = 0x05;
pub const TYPE_DELETE_RECORD: u8 = 0x06;
pub const TYPE_SYNC_CATALOG_REQ: u8 = 0x07;
pub const TYPE_SYNC_CATALOG_RESP: u8 = 0x08;
pub const TYPE_ACK: u8 = 0x09;
pub const TYPE_NACK: u8 = 0x0A;
pub const TYPE_PUT_DOCUMENT: u8 = 0x0B;
pub const TYPE_DELETE_DOCUMENT: u8 = 0x0C;
pub const TYPE_CREATE_INDEX: u8 = 0x0D;
pub const TYPE_DROP_INDEX: u8 = 0x0E;
pub const TYPE_SYNC_DATA_REQ: u8 = 0x0F;
pub const TYPE_SYNC_DATA_RESP: u8 = 0x10;
pub const TYPE_DISTRIBUTE_DATABASE: u8 = 0x11;
pub const TYPE_NODE_STOPPING: u8 = 0x12;
pub const TYPE_LEADER_ELECTION: u8 = 0x13;
pub const TYPE_NEW_LEADER_PROMOTED: u8 = 0x14;
pub const TYPE_CLUSTER_LIVE_EVENT: u8 = 0x15;
pub const TYPE_CREATE_ENGINE: u8 = 0x16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JettraRaftFrame {
    pub frame_type: u8,
    pub term: i64,
    pub log_index: i64,
    pub sender_node_id: String,
    pub database_name: String,
    pub collection_name: String,
    pub key: String,
    pub payload: Vec<u8>,
    pub timestamp: i64,
}

impl JettraRaftFrame {
    pub fn now_millis() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    pub fn heartbeat(term: i64, sender_node_id: &str) -> Self {
        Self {
            frame_type: TYPE_HEARTBEAT,
            term,
            log_index: 0,
            sender_node_id: sender_node_id.to_string(),
            database_name: String::new(),
            collection_name: String::new(),
            key: String::new(),
            payload: Vec::new(),
            timestamp: Self::now_millis(),
        }
    }

    pub fn heartbeat_ack(term: i64, sender_node_id: &str) -> Self {
        Self {
            frame_type: TYPE_HEARTBEAT_ACK,
            term,
            log_index: 0,
            sender_node_id: sender_node_id.to_string(),
            database_name: String::new(),
            collection_name: String::new(),
            key: String::new(),
            payload: Vec::new(),
            timestamp: Self::now_millis(),
        }
    }

    pub fn ack(term: i64, log_index: i64, sender_node_id: &str, message: &str) -> Self {
        Self {
            frame_type: TYPE_ACK,
            term,
            log_index,
            sender_node_id: sender_node_id.to_string(),
            database_name: String::new(),
            collection_name: String::new(),
            key: String::new(),
            payload: message.as_bytes().to_vec(),
            timestamp: Self::now_millis(),
        }
    }

    pub fn put_document(
        term: i64,
        log_index: i64,
        sender_node_id: &str,
        database_name: &str,
        collection_name: &str,
        id: &str,
        json_payload: Vec<u8>,
    ) -> Self {
        Self {
            frame_type: TYPE_PUT_DOCUMENT,
            term,
            log_index,
            sender_node_id: sender_node_id.to_string(),
            database_name: database_name.to_string(),
            collection_name: collection_name.to_string(),
            key: id.to_string(),
            payload: json_payload,
            timestamp: Self::now_millis(),
        }
    }

    pub fn payload_as_string(&self) -> String {
        String::from_utf8_lossy(&self.payload).to_string()
    }
}

pub struct JettraClusterProtocol;

impl JettraClusterProtocol {
    fn write_utf<W: Write>(out: &mut W, s: &str) -> std::io::Result<()> {
        let bytes = s.as_bytes();
        let len = bytes.len().min(u16::MAX as usize) as u16;
        out.write_all(&len.to_be_bytes())?;
        out.write_all(&bytes[..len as usize])
    }

    fn read_utf<R: Read>(inp: &mut R) -> std::io::Result<String> {
        let mut len_buf = [0u8; 2];
        inp.read_exact(&mut len_buf)?;
        let len = u16::from_be_bytes(len_buf) as usize;
        let mut buf = vec![0u8; len];
        if len > 0 {
            inp.read_exact(&mut buf)?;
        }
        Ok(String::from_utf8_lossy(&buf).to_string())
    }

    pub fn write_frame<W: Write>(out: &mut W, frame: &JettraRaftFrame) -> std::io::Result<()> {
        out.write_all(&JETTRA_CLUSTER_MAGIC.to_be_bytes())?;
        out.write_all(&[frame.frame_type])?;
        out.write_all(&frame.term.to_be_bytes())?;
        out.write_all(&frame.log_index.to_be_bytes())?;
        out.write_all(&frame.timestamp.to_be_bytes())?;
        Self::write_utf(out, &frame.sender_node_id)?;
        Self::write_utf(out, &frame.database_name)?;
        Self::write_utf(out, &frame.collection_name)?;
        Self::write_utf(out, &frame.key)?;
        let len = frame.payload.len() as i32;
        out.write_all(&len.to_be_bytes())?;
        if !frame.payload.is_empty() {
            out.write_all(&frame.payload)?;
        }
        out.flush()
    }

    pub fn read_frame<R: Read>(inp: &mut R) -> std::io::Result<JettraRaftFrame> {
        let mut magic_buf = [0u8; 2];
        inp.read_exact(&mut magic_buf)?;
        let magic = u16::from_be_bytes(magic_buf);
        if magic != JETTRA_CLUSTER_MAGIC {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("Invalid JettraClusterProtocol magic: 0x{:04x}", magic),
            ));
        }
        let mut ft = [0u8; 1];
        inp.read_exact(&mut ft)?;
        let mut i64_buf = [0u8; 8];
        inp.read_exact(&mut i64_buf)?;
        let term = i64::from_be_bytes(i64_buf);
        inp.read_exact(&mut i64_buf)?;
        let log_index = i64::from_be_bytes(i64_buf);
        inp.read_exact(&mut i64_buf)?;
        let timestamp = i64::from_be_bytes(i64_buf);

        let sender_node_id = Self::read_utf(inp)?;
        let database_name = Self::read_utf(inp)?;
        let collection_name = Self::read_utf(inp)?;
        let key = Self::read_utf(inp)?;

        let mut len_buf = [0u8; 4];
        inp.read_exact(&mut len_buf)?;
        let payload_len = i32::from_be_bytes(len_buf).max(0) as usize;
        let mut payload = vec![0u8; payload_len];
        if payload_len > 0 {
            inp.read_exact(&mut payload)?;
        }

        Ok(JettraRaftFrame {
            frame_type: ft[0],
            term,
            log_index,
            sender_node_id,
            database_name,
            collection_name,
            key,
            payload,
            timestamp,
        })
    }

    pub fn send_frame_tcp(host: &str, raft_port: u16, frame: &JettraRaftFrame) -> Result<JettraRaftFrame, String> {
        let addr = format!("{}:{}", host, raft_port);
        let sock_addr: std::net::SocketAddr = addr.parse().map_err(|e: std::net::AddrParseError| e.to_string())?;
        let mut stream = TcpStream::connect_timeout(&sock_addr, Duration::from_millis(1500))
            .map_err(|e| e.to_string())?;
        let _ = stream.set_read_timeout(Some(Duration::from_millis(1500)));
        let _ = stream.set_write_timeout(Some(Duration::from_millis(1500)));
        Self::write_frame(&mut stream, frame).map_err(|e| e.to_string())?;
        Self::read_frame(&mut stream).map_err(|e| e.to_string())
    }
}

// ============================================================================
// DynamicRingEngine: Anillo Dinámico, Balanceo por Saturación y Failover Raft
// ============================================================================

#[derive(Debug, Clone)]
pub struct ClusterNode {
    pub node_id: String,
    pub host: String,
    pub port: u16,
    pub role: String, // "LEADER" o "FOLLOWER"
    pub memory_usage_percent: f64,
    pub active: bool,
}

#[derive(Debug, Clone)]
pub struct ClusterLiveEvent {
    pub timestamp: i64,
    pub event_type: String,
    pub source_node: String,
    pub role: String,
    pub message: String,
}

#[derive(Debug, Clone)]
pub struct DynamicRingEngine {
    local_node_id: String,
    local_role: Arc<RwLock<String>>,
    saturation_threshold: f64,
    offload_target_percentage: f64,
    ring_active: Arc<AtomicBool>,
    round_robin_index: Arc<AtomicUsize>,
    current_term: Arc<AtomicI64>,
    log_index: Arc<AtomicI64>,
    ring_nodes: Arc<RwLock<HashMap<String, ClusterNode>>>,
    live_events: Arc<RwLock<Vec<ClusterLiveEvent>>>,
}

impl DynamicRingEngine {
    pub fn new(
        local_node_id: impl Into<String>,
        role: &str,
        saturation_threshold: f64,
        offload_target_percentage: f64,
    ) -> Self {
        let raft_role = if role.eq_ignore_ascii_case("SECONDARY") || role.eq_ignore_ascii_case("FOLLOWER") {
            "FOLLOWER".to_string()
        } else {
            "LEADER".to_string()
        };
        Self {
            local_node_id: local_node_id.into(),
            local_role: Arc::new(RwLock::new(raft_role)),
            saturation_threshold,
            offload_target_percentage,
            ring_active: Arc::new(AtomicBool::new(false)),
            round_robin_index: Arc::new(AtomicUsize::new(0)),
            current_term: Arc::new(AtomicI64::new(1)),
            log_index: Arc::new(AtomicI64::new(0)),
            ring_nodes: Arc::new(RwLock::new(HashMap::new())),
            live_events: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub fn local_node_id(&self) -> &str {
        &self.local_node_id
    }

    pub fn local_role(&self) -> String {
        self.local_role.read().unwrap().clone()
    }

    pub fn current_term(&self) -> i64 {
        self.current_term.load(Ordering::Relaxed)
    }

    pub fn next_log_index(&self) -> i64 {
        self.log_index.fetch_add(1, Ordering::Relaxed) + 1
    }

    pub fn register_peer(&self, node: ClusterNode) {
        self.ring_nodes
            .write()
            .unwrap()
            .insert(node.node_id.clone(), node);
    }

    pub fn check_memory_saturation(&self, current_memory_ratio: f64) -> bool {
        if current_memory_ratio >= self.saturation_threshold {
            if !self.ring_active.swap(true, Ordering::Relaxed) {
                self.record_event(
                    "RING_SATURATION_ACTIVATED",
                    &format!(
                        "Saturación de RAM ({:.1}% >= {:.1}%). Activando transición al Anillo Dinámico.",
                        current_memory_ratio * 100.0,
                        self.saturation_threshold * 100.0
                    ),
                );
            }
            return true;
        }
        false
    }

    pub fn select_next_available_peer(&self) -> Option<ClusterNode> {
        let guard = self.ring_nodes.read().unwrap();
        let available: Vec<ClusterNode> = guard
            .values()
            .filter(|n| {
                n.active
                    && n.node_id != self.local_node_id
                    && n.memory_usage_percent < (self.saturation_threshold * 100.0)
            })
            .cloned()
            .collect();
        if available.is_empty() {
            return None;
        }
        let idx = self.round_robin_index.fetch_add(1, Ordering::Relaxed) % available.len();
        Some(available[idx].clone())
    }

    /// Promoción automática de nodo secundario a LEADER ante caída del nodo primario.
    pub fn trigger_failover_election(&self, failed_leader_id: &str) -> String {
        let new_term = self.current_term.fetch_add(1, Ordering::Relaxed) + 1;
        {
            let mut guard = self.ring_nodes.write().unwrap();
            if let Some(failed) = guard.get_mut(failed_leader_id) {
                failed.active = false;
                failed.role = "OFFLINE".to_string();
            }
            if let Some(local) = guard.get_mut(&self.local_node_id) {
                local.role = "LEADER".to_string();
            }
        }
        *self.local_role.write().unwrap() = "LEADER".to_string();
        self.record_event(
            "NEW_LEADER_PROMOTED",
            &format!(
                "Failover completado en Term {}: '{}' promovido a LEADER tras caída de '{}'",
                new_term, self.local_node_id, failed_leader_id
            ),
        );
        self.local_node_id.clone()
    }

    pub fn record_event(&self, event_type: &str, message: &str) {
        let mut guard = self.live_events.write().unwrap();
        if guard.len() >= 150 {
            guard.remove(0);
        }
        guard.push(ClusterLiveEvent {
            timestamp: JettraRaftFrame::now_millis(),
            event_type: event_type.to_string(),
            source_node: self.local_node_id.clone(),
            role: self.local_role(),
            message: message.to_string(),
        });
    }

    pub fn get_ring_nodes(&self) -> Vec<ClusterNode> {
        self.ring_nodes.read().unwrap().values().cloned().collect()
    }

    pub fn get_live_events(&self) -> Vec<ClusterLiveEvent> {
        self.live_events.read().unwrap().clone()
    }

    pub fn is_ring_active(&self) -> bool {
        self.ring_active.load(Ordering::Relaxed)
    }

    pub fn offload_target_percentage(&self) -> f64 {
        self.offload_target_percentage
    }
}


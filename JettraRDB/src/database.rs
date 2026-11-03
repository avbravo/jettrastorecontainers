//! Coordinador Central de Base de Datos Multimodelo (`JettraDatabase`).
//! Integra los 8 motores multimodelo, índices secundarios, `NativeMemTable`,
//! `DynamicRingEngine`, `JettraPolice` y persistencia híbrida `.jettra` + `_meta.json`.

use crate::cluster::DynamicRingEngine;
use crate::config::{JettraConfig, StorageMode};
use crate::engines::{
    ColumnarEngine, Document, DocumentEngine, FacturaAuditRecord, GeospatialEngine, GraphEngine,
    KeyValueEngine, RecordsEngine, TimeSeriesEngine, TypedRecord, VectorEngine,
};
use crate::index::{IndexMetadata, IndexType, JettraIndexManager};
use crate::json::JsonValue;
use crate::police::JettraPolice;
use crate::storage::{BackupManager, BackupMetadata, NativeMemTable};
use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone)]
pub struct JettraDatabase {
    database_name: String,
    config: JettraConfig,
    document_engines: Arc<RwLock<HashMap<String, DocumentEngine>>>,
    kv_engines: Arc<RwLock<HashMap<String, KeyValueEngine>>>,
    vector_engines: Arc<RwLock<HashMap<String, VectorEngine>>>,
    graph_engines: Arc<RwLock<HashMap<String, GraphEngine>>>,
    time_series_engines: Arc<RwLock<HashMap<String, TimeSeriesEngine>>>,
    geospatial_engines: Arc<RwLock<HashMap<String, GeospatialEngine>>>,
    columnar_engines: Arc<RwLock<HashMap<String, ColumnarEngine>>>,
    records_engines: Arc<RwLock<HashMap<String, RecordsEngine>>>,
    index_manager: JettraIndexManager,
    mem_table: NativeMemTable,
    ring_engine: DynamicRingEngine,
    police: JettraPolice,
}

impl JettraDatabase {
    pub fn new(database_name: impl Into<String>, config: JettraConfig) -> Self {
        let name = database_name.into();
        let ring = DynamicRingEngine::new(
            &config.node_id,
            &config.node_role,
            config.ring_saturation_threshold,
            config.ring_offload_target_pct,
        );
        let police = JettraPolice::new(config.max_memory_bytes);
        let db = Self {
            database_name: name,
            config,
            document_engines: Arc::new(RwLock::new(HashMap::new())),
            kv_engines: Arc::new(RwLock::new(HashMap::new())),
            vector_engines: Arc::new(RwLock::new(HashMap::new())),
            graph_engines: Arc::new(RwLock::new(HashMap::new())),
            time_series_engines: Arc::new(RwLock::new(HashMap::new())),
            geospatial_engines: Arc::new(RwLock::new(HashMap::new())),
            columnar_engines: Arc::new(RwLock::new(HashMap::new())),
            records_engines: Arc::new(RwLock::new(HashMap::new())),
            index_manager: JettraIndexManager::new(),
            mem_table: NativeMemTable::new(16 * 1024 * 1024),
            ring_engine: ring,
            police,
        };
        if db.config.storage_mode == StorageMode::DiskMemory {
            let _ = db.load_from_disk();
        }
        db
    }

    pub fn database_name(&self) -> &str {
        &self.database_name
    }

    pub fn config(&self) -> &JettraConfig {
        &self.config
    }

    pub fn index_manager(&self) -> &JettraIndexManager {
        &self.index_manager
    }

    pub fn mem_table(&self) -> &NativeMemTable {
        &self.mem_table
    }

    pub fn ring_engine(&self) -> &DynamicRingEngine {
        &self.ring_engine
    }

    pub fn police(&self) -> &JettraPolice {
        &self.police
    }

    // ========================================================================
    // Acceso a los 8 Motores Multimodelo
    // ========================================================================

    pub fn document_engine(&self, collection: &str) -> DocumentEngine {
        let mut guard = self.document_engines.write().unwrap();
        guard
            .entry(collection.to_string())
            .or_insert_with(|| DocumentEngine::new(collection))
            .clone()
    }

    pub fn kv_engine(&self, namespace: &str) -> KeyValueEngine {
        let mut guard = self.kv_engines.write().unwrap();
        guard
            .entry(namespace.to_string())
            .or_insert_with(|| KeyValueEngine::new(namespace))
            .clone()
    }

    pub fn vector_engine(&self, index_name: &str, dimensions: usize) -> VectorEngine {
        let mut guard = self.vector_engines.write().unwrap();
        guard
            .entry(index_name.to_string())
            .or_insert_with(|| VectorEngine::new(index_name, dimensions))
            .clone()
    }

    pub fn graph_engine(&self, graph_name: &str) -> GraphEngine {
        let mut guard = self.graph_engines.write().unwrap();
        guard
            .entry(graph_name.to_string())
            .or_insert_with(|| GraphEngine::new(graph_name))
            .clone()
    }

    pub fn time_series_engine(&self, metric_name: &str) -> TimeSeriesEngine {
        let mut guard = self.time_series_engines.write().unwrap();
        guard
            .entry(metric_name.to_string())
            .or_insert_with(|| TimeSeriesEngine::new(metric_name))
            .clone()
    }

    pub fn geospatial_engine(&self, layer_name: &str) -> GeospatialEngine {
        let mut guard = self.geospatial_engines.write().unwrap();
        guard
            .entry(layer_name.to_string())
            .or_insert_with(|| GeospatialEngine::new(layer_name))
            .clone()
    }

    pub fn columnar_engine(&self, table_name: &str) -> ColumnarEngine {
        let mut guard = self.columnar_engines.write().unwrap();
        guard
            .entry(table_name.to_string())
            .or_insert_with(|| ColumnarEngine::new(table_name))
            .clone()
    }

    pub fn records_engine(&self, store_name: &str) -> RecordsEngine {
        let mut guard = self.records_engines.write().unwrap();
        guard
            .entry(store_name.to_string())
            .or_insert_with(|| RecordsEngine::new(store_name))
            .clone()
    }

    // ========================================================================
    // Operaciones Transaccionales e Indexadas
    // ========================================================================

    pub fn save_document(
        &self,
        collection: &str,
        id: &str,
        mut doc: Document,
    ) -> Result<(), String> {
        doc.insert("_id".to_string(), JsonValue::Str(id.to_string()));
        self.index_manager.on_document_saved(collection, id, &doc)?;
        let engine = self.document_engine(collection);
        engine.save(id, doc.clone());

        if self.config.storage_mode == StorageMode::DiskMemory {
            let payload = JsonValue::Object(doc).to_json();
            let composite_key = format!("{}#{}", collection, id);
            if self
                .mem_table
                .write_entry(1, &composite_key, payload.as_bytes())
                .is_err()
            {
                let _ = self.flush_mem_table();
                let _ = self
                    .mem_table
                    .write_entry(1, &composite_key, payload.as_bytes());
            }
        }
        Ok(())
    }

    pub fn delete_document(&self, collection: &str, id: &str) -> Option<Document> {
        let engine = self.document_engine(collection);
        let removed = engine.delete(id);
        if let Some(ref doc) = removed {
            self.index_manager.on_document_removed(collection, id, doc);
        }
        removed
    }

    pub fn create_index(
        &self,
        index_name: &str,
        collection_name: &str,
        field_name: &str,
        index_type: IndexType,
        unique: bool,
    ) -> Result<IndexMetadata, String> {
        let meta = self
            .index_manager
            .create_index(index_name, collection_name, field_name, index_type, unique);
        let engine = self.document_engine(collection_name);
        self.index_manager.rebuild_index(index_name, &engine)?;
        Ok(meta)
    }

    // ========================================================================
    // Persistencia Híbrida: SSTable Binaria (.jettra) + Metadata (_meta.json)
    // ========================================================================

    pub fn sstable_path(&self) -> PathBuf {
        Path::new(&self.config.storage_path).join(format!(
            "{}_sstable{}",
            self.database_name, self.config.file_extension
        ))
    }

    pub fn meta_json_path(&self) -> PathBuf {
        Path::new(&self.config.storage_path).join(format!("{}_meta.json", self.database_name))
    }

    pub fn flush_mem_table(&self) -> Result<usize, String> {
        let bytes = self.mem_table.flush_to_disk(self.sstable_path())?;
        self.save_to_disk()?;
        Ok(bytes)
    }

    pub fn save_to_disk(&self) -> Result<(), String> {
        let path = self.meta_json_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }

        let mut root = BTreeMap::new();
        root.insert(
            "databaseName".to_string(),
            JsonValue::Str(self.database_name.clone()),
        );

        let mut doc_map = BTreeMap::new();
        for (name, eng) in self.document_engines.read().unwrap().iter() {
            let mut col_obj = BTreeMap::new();
            for (id, doc) in eng.snapshot() {
                col_obj.insert(id, JsonValue::Object(doc));
            }
            doc_map.insert(name.clone(), JsonValue::Object(col_obj));
        }
        root.insert("documentEngines".to_string(), JsonValue::Object(doc_map));

        let mut kv_map = BTreeMap::new();
        for (name, eng) in self.kv_engines.read().unwrap().iter() {
            let mut ns_obj = BTreeMap::new();
            for (k, v) in eng.snapshot() {
                ns_obj.insert(
                    k,
                    JsonValue::Str(String::from_utf8_lossy(&v).to_string()),
                );
            }
            kv_map.insert(name.clone(), JsonValue::Object(ns_obj));
        }
        root.insert("kvEngines".to_string(), JsonValue::Object(kv_map));

        let json_str = JsonValue::Object(root).to_json();
        fs::write(path, json_str).map_err(|e| e.to_string())
    }

    pub fn load_from_disk(&self) -> Result<(), String> {
        let path = self.meta_json_path();
        if !path.exists() {
            return Ok(());
        }
        let content = fs::read_to_string(path).map_err(|e| e.to_string())?;
        let parsed = JsonValue::parse(&content)?;
        if let Some(root) = parsed.as_object() {
            if let Some(doc_engines) = root.get("documentEngines").and_then(|v| v.as_object()) {
                for (col_name, col_val) in doc_engines {
                    if let Some(col_map) = col_val.as_object() {
                        let eng = self.document_engine(col_name);
                        let mut batch = HashMap::with_capacity(col_map.len());
                        for (id, doc_val) in col_map {
                            if let Some(doc_obj) = doc_val.as_object() {
                                batch.insert(id.clone(), doc_obj.clone());
                            }
                        }
                        eng.save_batch(batch);
                    }
                }
            }
            if let Some(kv_engines) = root.get("kvEngines").and_then(|v| v.as_object()) {
                for (ns_name, ns_val) in kv_engines {
                    if let Some(ns_map) = ns_val.as_object() {
                        let eng = self.kv_engine(ns_name);
                        for (k, v) in ns_map {
                            eng.put_str(k.clone(), &v.to_string_value());
                        }
                    }
                }
            }
        }
        Ok(())
    }

    pub fn backup_to<P: AsRef<Path>>(&self, destination: P) -> Result<BackupMetadata, String> {
        let _ = self.flush_mem_table();
        BackupManager::backup_file(&self.database_name, self.sstable_path(), destination)
    }

    // ========================================================================
    // Catálogo e Inventario de Motores
    // ========================================================================

    pub fn engine_summary(&self) -> BTreeMap<String, usize> {
        let mut map = BTreeMap::new();
        map.insert(
            "DOCUMENT".to_string(),
            self.document_engines.read().unwrap().len(),
        );
        map.insert("KV".to_string(), self.kv_engines.read().unwrap().len());
        map.insert(
            "VECTOR".to_string(),
            self.vector_engines.read().unwrap().len(),
        );
        map.insert(
            "GRAPH".to_string(),
            self.graph_engines.read().unwrap().len(),
        );
        map.insert(
            "TIMESERIES".to_string(),
            self.time_series_engines.read().unwrap().len(),
        );
        map.insert(
            "GEOSPATIAL".to_string(),
            self.geospatial_engines.read().unwrap().len(),
        );
        map.insert(
            "COLUMNAR".to_string(),
            self.columnar_engines.read().unwrap().len(),
        );
        map.insert(
            "RECORDS".to_string(),
            self.records_engines.read().unwrap().len(),
        );
        map
    }

    pub fn list_document_collections(&self) -> Vec<(String, usize)> {
        self.document_engines
            .read()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.count()))
            .collect()
    }

    // ========================================================================
    // Instalación de Muestras Multimodelo (Factura, Hospital, Ambiental)
    // ========================================================================

    pub fn install_sample_dataset(&self, sample_type: &str, scale_records: usize) -> usize {
        let count = scale_records.max(10);
        let upper = sample_type.to_uppercase();
        if upper.contains("HOSPITAL") {
            let docs = self.document_engine("pacientes");
            let kv = self.kv_engine("camas_uci");
            let ts = self.time_series_engine("signos_vitales");
            let vec_eng = self.vector_engine("diagnosticos_vec", 3);
            let graph = self.graph_engine("red_medica");
            let gis = self.geospatial_engine("ambulancias_gis");
            let col = self.columnar_engine("costos_clinicos");

            for i in 1..=count {
                let id = format!("PAC-{}", i);
                let mut d = BTreeMap::new();
                d.insert("nombre".to_string(), JsonValue::Str(format!("Paciente {}", i)));
                d.insert(
                    "area".to_string(),
                    JsonValue::Str(if i % 2 == 0 { "CARDIOLOGIA" } else { "URGENCIAS" }.to_string()),
                );
                d.insert("costo".to_string(), JsonValue::Number((i as f64) * 125.5));
                docs.save(id.clone(), d.clone());
                col.append_row(&d);
                kv.put_str(format!("CAMA-{}", i), &id);
                ts.record(1_700_000_000 + (i as i64), 70.0 + ((i % 30) as f64));
                let _ = vec_eng.index(id.clone(), vec![0.2 * (i as f32), 0.5, 0.8]);
                graph.add_edge(&id, format!("MED-{}", (i % 5) + 1), "ATENDIDO_POR", BTreeMap::new());
                gis.add_location(format!("AMB-{}", i), 8.9824 + (i as f64) * 0.001, -79.5199);
            }
            count * 7
        } else {
            // Muestra Factura Multimodelo (los 8 motores activos)
            let facturas = self.document_engine("facturas");
            let kv = self.kv_engine("cache_folios");
            let vec_eng = self.vector_engine("factura_embeddings", 3);
            let graph = self.graph_engine("red_comercial");
            let ts = self.time_series_engine("volumen_facturacion");
            let gis = self.geospatial_engine("sucursales_fiscales");
            let col = self.columnar_engine("analitica_fiscal");
            let rec = self.records_engine("auditoria_records");

            for i in 1..=count {
                let id = format!("FAC-{}", i);
                let monto = (i as f64) * 150.0;
                let estado = if i % 3 == 0 { "PENDIENTE" } else { "PAGADA" };
                let mut d = BTreeMap::new();
                d.insert("folio".to_string(), JsonValue::Str(format!("F-2026-{:05}", i)));
                d.insert("cliente".to_string(), JsonValue::Str(format!("CLI-{}", (i % 10) + 1)));
                d.insert("estado".to_string(), JsonValue::Str(estado.to_string()));
                d.insert("total".to_string(), JsonValue::Number(monto));
                facturas.save(id.clone(), d.clone());
                col.append_row(&d);
                kv.put_str(format!("FOLIO-{}", i), &id);
                let _ = vec_eng.index(id.clone(), vec![(i as f32) * 0.1, 0.5, 0.9]);
                graph.add_edge(
                    format!("EMP-{}", (i % 5) + 1),
                    format!("CLI-{}", (i % 10) + 1),
                    "FACTURO_A",
                    BTreeMap::new(),
                );
                ts.record(1_700_000_000 + (i as i64) * 60, monto);
                gis.add_location(format!("SUC-{}", i), 19.4326 + (i as f64) * 0.001, -99.1332);
                rec.save_record(
                    format!("AUD-{}", i),
                    TypedRecord::FacturaAudit(FacturaAuditRecord {
                        audit_id: format!("AUD-{}", i),
                        factura_id: id,
                        rfc_emisor: "JET260101AAA".to_string(),
                        monto_auditado: monto,
                        resultado: "APROBADO".to_string(),
                        timestamp: 1_700_000_000 + (i as i64),
                    }),
                );
            }
            count * 8
        }
    }
}

//! Los 8 Motores Multimodelo Especializados de JettraRDB:
//! 1. `DocumentEngine` (JSON/Map Documentos + `JettraRef` + `StreamResponse`)
//! 2. `KeyValueEngine` (Clave-Valor Binario)
//! 3. `VectorEngine` (Embeddings Multidimensionales + Similitud Coseno/Euclidiana)
//! 4. `GraphEngine` (Grafo Dirigido de Propiedades: Vértices y Aristas)
//! 5. `TimeSeriesEngine` (Series de Tiempo Ordenadas por Timestamp)
//! 6. `GeospatialEngine` (Coordenadas GIS + Búsqueda Haversine por Radio)
//! 7. `ColumnarEngine` (Almacenamiento Columnar OLAP Numérico y Texto)
//! 8. `RecordsEngine` (Registros Tipados y Auditoría Fiscal)

use crate::calc::JettraVectorMath;
use crate::json::JsonValue;
use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, RwLock};

pub type Document = BTreeMap<String, JsonValue>;

// ============================================================================
// 0. JettraRef: Referencia Cruzada entre Motores (Lazy / Eager)
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FetchMode {
    Lazy,
    Eager,
}

#[derive(Debug, Clone)]
pub struct JettraRef {
    pub target_engine: String,
    pub target_id: String,
    pub fetch_mode: FetchMode,
    pub resolved_value: Option<JsonValue>,
}

impl JettraRef {
    pub fn new(target_engine: impl Into<String>, target_id: impl Into<String>, fetch_mode: FetchMode) -> Self {
        Self {
            target_engine: target_engine.into(),
            target_id: target_id.into(),
            fetch_mode,
            resolved_value: None,
        }
    }

    pub fn descriptor(&self) -> String {
        format!("{}::{}", self.target_engine, self.target_id)
    }

    pub fn is_resolved(&self) -> bool {
        self.resolved_value.is_some()
    }

    pub fn resolve_with<F>(&mut self, resolver: F) -> Option<&JsonValue>
    where
        F: FnOnce(&str, &str) -> Option<JsonValue>,
    {
        if self.resolved_value.is_none() {
            self.resolved_value = resolver(&self.target_engine, &self.target_id);
        }
        self.resolved_value.as_ref()
    }
}

// ============================================================================
// 1. DocumentEngine: Motor Documental
// ============================================================================

#[derive(Debug, Clone)]
pub struct StreamResponse {
    pub items: Vec<Document>,
    pub next_offset: usize,
    pub total_items: usize,
    pub has_more: bool,
}

#[derive(Debug, Clone)]
pub struct DocumentEngine {
    collection_name: String,
    documents: Arc<RwLock<HashMap<String, Document>>>,
}

impl DocumentEngine {
    pub fn new(collection_name: impl Into<String>) -> Self {
        Self {
            collection_name: collection_name.into(),
            documents: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn collection_name(&self) -> &str {
        &self.collection_name
    }

    pub fn save(&self, id: impl Into<String>, mut doc: Document) {
        let id_str = id.into();
        doc.insert("_id".to_string(), JsonValue::Str(id_str.clone()));
        let mut guard = self.documents.write().unwrap();
        guard.insert(id_str, doc);
    }

    pub fn save_batch(&self, batch: HashMap<String, Document>) {
        let mut guard = self.documents.write().unwrap();
        guard.reserve(batch.len());
        for (id, mut doc) in batch {
            doc.insert("_id".to_string(), JsonValue::Str(id.clone()));
            guard.insert(id, doc);
        }
    }

    pub fn find_by_id(&self, id: &str) -> Option<Document> {
        let guard = self.documents.read().unwrap();
        guard.get(id).cloned()
    }

    pub fn delete(&self, id: &str) -> Option<Document> {
        let mut guard = self.documents.write().unwrap();
        guard.remove(id)
    }

    pub fn count(&self) -> usize {
        self.documents.read().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.documents.read().unwrap().is_empty()
    }

    pub fn find_all(&self) -> Vec<Document> {
        self.documents.read().unwrap().values().cloned().collect()
    }

    pub fn snapshot(&self) -> HashMap<String, Document> {
        self.documents.read().unwrap().clone()
    }

    pub fn stream(&self, offset: usize, limit: usize) -> StreamResponse {
        let guard = self.documents.read().unwrap();
        let total = guard.len();
        let safe_limit = limit.clamp(1, 5000);
        let items: Vec<Document> = guard
            .values()
            .skip(offset)
            .take(safe_limit)
            .cloned()
            .collect();
        let next_offset = offset + items.len();
        StreamResponse {
            items,
            next_offset,
            total_items: total,
            has_more: next_offset < total,
        }
    }

    pub fn evict_percentage(&self, percentage: f64) -> HashMap<String, Document> {
        let mut guard = self.documents.write().unwrap();
        let target = ((guard.len() as f64) * percentage).ceil() as usize;
        let keys_to_remove: Vec<String> = guard.keys().take(target).cloned().collect();
        let mut evicted = HashMap::with_capacity(keys_to_remove.len());
        for k in keys_to_remove {
            if let Some(doc) = guard.remove(&k) {
                evicted.insert(k, doc);
            }
        }
        evicted
    }

    pub fn clear(&self) {
        self.documents.write().unwrap().clear();
    }
}

// ============================================================================
// 2. KeyValueEngine: Motor Clave-Valor
// ============================================================================

#[derive(Debug, Clone)]
pub struct KeyValueEngine {
    namespace: String,
    store: Arc<RwLock<HashMap<String, Vec<u8>>>>,
}

impl KeyValueEngine {
    pub fn new(namespace: impl Into<String>) -> Self {
        Self {
            namespace: namespace.into(),
            store: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn namespace(&self) -> &str {
        &self.namespace
    }

    pub fn put(&self, key: impl Into<String>, value: Vec<u8>) {
        self.store.write().unwrap().insert(key.into(), value);
    }

    pub fn put_str(&self, key: impl Into<String>, value: &str) {
        self.put(key, value.as_bytes().to_vec());
    }

    pub fn put_batch(&self, batch: HashMap<String, Vec<u8>>) {
        let mut guard = self.store.write().unwrap();
        guard.extend(batch);
    }

    pub fn get(&self, key: &str) -> Option<Vec<u8>> {
        self.store.read().unwrap().get(key).cloned()
    }

    pub fn get_str(&self, key: &str) -> Option<String> {
        self.get(key).and_then(|b| String::from_utf8(b).ok())
    }

    pub fn remove(&self, key: &str) -> Option<Vec<u8>> {
        self.store.write().unwrap().remove(key)
    }

    pub fn size(&self) -> usize {
        self.store.read().unwrap().len()
    }

    pub fn snapshot(&self) -> HashMap<String, Vec<u8>> {
        self.store.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.store.write().unwrap().clear();
    }
}

// ============================================================================
// 3. VectorEngine: Motor Vectorial (Embeddings & IA)
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
pub struct VectorMatch {
    pub id: String,
    pub score: f32,
}

#[derive(Debug, Clone)]
pub struct VectorEngine {
    index_name: String,
    dimensions: usize,
    vectors: Arc<RwLock<HashMap<String, Vec<f32>>>>,
}

impl VectorEngine {
    pub fn new(index_name: impl Into<String>, dimensions: usize) -> Self {
        Self {
            index_name: index_name.into(),
            dimensions: dimensions.max(1),
            vectors: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn index_name(&self) -> &str {
        &self.index_name
    }

    pub fn dimensions(&self) -> usize {
        self.dimensions
    }

    pub fn index(&self, id: impl Into<String>, embedding: Vec<f32>) -> Result<(), String> {
        if embedding.len() != self.dimensions {
            return Err(format!(
                "Invalid vector dimensions: expected {}, got {}",
                self.dimensions,
                embedding.len()
            ));
        }
        self.vectors.write().unwrap().insert(id.into(), embedding);
        Ok(())
    }

    pub fn index_batch(&self, batch: HashMap<String, Vec<f32>>) {
        let mut guard = self.vectors.write().unwrap();
        for (id, vec) in batch {
            if vec.len() == self.dimensions {
                guard.insert(id, vec);
            }
        }
    }

    pub fn search_cosine(&self, query_vector: &[f32], top_k: usize) -> Vec<VectorMatch> {
        let guard = self.vectors.read().unwrap();
        let mut matches: Vec<VectorMatch> = guard
            .iter()
            .filter_map(|(id, vec)| {
                JettraVectorMath::cosine_similarity(query_vector, vec)
                    .ok()
                    .map(|score| VectorMatch {
                        id: id.clone(),
                        score,
                    })
            })
            .collect();
        matches.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        matches.truncate(top_k);
        matches
    }

    pub fn search_euclidean(&self, query_vector: &[f32], top_k: usize) -> Vec<VectorMatch> {
        let guard = self.vectors.read().unwrap();
        let mut matches: Vec<VectorMatch> = guard
            .iter()
            .filter_map(|(id, vec)| {
                JettraVectorMath::euclidean_distance(query_vector, vec)
                    .ok()
                    .map(|dist| VectorMatch {
                        id: id.clone(),
                        score: dist,
                    })
            })
            .collect();
        matches.sort_by(|a, b| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal));
        matches.truncate(top_k);
        matches
    }

    pub fn centroid(&self) -> Vec<f32> {
        let guard = self.vectors.read().unwrap();
        let all: Vec<Vec<f32>> = guard.values().cloned().collect();
        JettraVectorMath::centroid(&all).unwrap_or_default()
    }

    pub fn size(&self) -> usize {
        self.vectors.read().unwrap().len()
    }

    pub fn snapshot(&self) -> HashMap<String, Vec<f32>> {
        self.vectors.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.vectors.write().unwrap().clear();
    }
}

// ============================================================================
// 4. GraphEngine: Motor de Grafos Dirigidos de Propiedades
// ============================================================================

#[derive(Debug, Clone)]
pub struct Edge {
    pub target_vertex: String,
    pub label: String,
    pub properties: BTreeMap<String, JsonValue>,
}

#[derive(Debug, Clone)]
pub struct GraphEngine {
    graph_name: String,
    vertices: Arc<RwLock<HashMap<String, BTreeMap<String, JsonValue>>>>,
    adjacency_list: Arc<RwLock<HashMap<String, Vec<Edge>>>>,
}

impl GraphEngine {
    pub fn new(graph_name: impl Into<String>) -> Self {
        Self {
            graph_name: graph_name.into(),
            vertices: Arc::new(RwLock::new(HashMap::new())),
            adjacency_list: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn graph_name(&self) -> &str {
        &self.graph_name
    }

    pub fn add_vertex(&self, vertex_id: impl Into<String>, properties: BTreeMap<String, JsonValue>) {
        self.vertices.write().unwrap().insert(vertex_id.into(), properties);
    }

    pub fn add_edge(
        &self,
        from_vertex: impl Into<String>,
        to_vertex: impl Into<String>,
        label: impl Into<String>,
        properties: BTreeMap<String, JsonValue>,
    ) {
        let from_id = from_vertex.into();
        let to_id = to_vertex.into();
        {
            let mut v_guard = self.vertices.write().unwrap();
            v_guard.entry(from_id.clone()).or_default();
            v_guard.entry(to_id.clone()).or_default();
        }
        let mut e_guard = self.adjacency_list.write().unwrap();
        e_guard.entry(from_id).or_default().push(Edge {
            target_vertex: to_id,
            label: label.into(),
            properties,
        });
    }

    pub fn get_vertex(&self, vertex_id: &str) -> Option<BTreeMap<String, JsonValue>> {
        self.vertices.read().unwrap().get(vertex_id).cloned()
    }

    pub fn get_outbound_edges(&self, vertex_id: &str) -> Vec<Edge> {
        self.adjacency_list
            .read()
            .unwrap()
            .get(vertex_id)
            .cloned()
            .unwrap_or_default()
    }

    pub fn vertex_count(&self) -> usize {
        self.vertices.read().unwrap().len()
    }

    pub fn edge_count(&self) -> usize {
        self.adjacency_list
            .read()
            .unwrap()
            .values()
            .map(|v| v.len())
            .sum()
    }

    pub fn snapshot_vertices(&self) -> HashMap<String, BTreeMap<String, JsonValue>> {
        self.vertices.read().unwrap().clone()
    }

    pub fn snapshot_edges(&self) -> HashMap<String, Vec<Edge>> {
        self.adjacency_list.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.vertices.write().unwrap().clear();
        self.adjacency_list.write().unwrap().clear();
    }
}

// ============================================================================
// 5. TimeSeriesEngine: Motor de Series de Tiempo
// ============================================================================

#[derive(Debug, Clone)]
pub struct TimeSeriesEngine {
    metric_name: String,
    points: Arc<RwLock<BTreeMap<i64, f64>>>,
}

impl TimeSeriesEngine {
    pub fn new(metric_name: impl Into<String>) -> Self {
        Self {
            metric_name: metric_name.into(),
            points: Arc::new(RwLock::new(BTreeMap::new())),
        }
    }

    pub fn metric_name(&self) -> &str {
        &self.metric_name
    }

    pub fn record(&self, timestamp: i64, value: f64) {
        self.points.write().unwrap().insert(timestamp, value);
    }

    pub fn record_batch(&self, batch: BTreeMap<i64, f64>) {
        self.points.write().unwrap().extend(batch);
    }

    pub fn range(&self, from_timestamp: i64, to_timestamp: i64) -> BTreeMap<i64, f64> {
        let guard = self.points.read().unwrap();
        guard
            .range(from_timestamp..=to_timestamp)
            .map(|(k, v)| (*k, *v))
            .collect()
    }

    pub fn average(&self, from_timestamp: i64, to_timestamp: i64) -> f64 {
        let sub = self.range(from_timestamp, to_timestamp);
        if sub.is_empty() {
            return 0.0;
        }
        let sum: f64 = sub.values().sum();
        sum / (sub.len() as f64)
    }

    pub fn size(&self) -> usize {
        self.points.read().unwrap().len()
    }

    pub fn snapshot(&self) -> BTreeMap<i64, f64> {
        self.points.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.points.write().unwrap().clear();
    }
}

// ============================================================================
// 6. GeospatialEngine: Motor Geoespacial (GIS / Haversine)
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
pub struct GeoPoint {
    pub id: String,
    pub latitude: f64,
    pub longitude: f64,
}

#[derive(Debug, Clone)]
pub struct GeospatialEngine {
    layer_name: String,
    points: Arc<RwLock<HashMap<String, GeoPoint>>>,
}

impl GeospatialEngine {
    pub fn new(layer_name: impl Into<String>) -> Self {
        Self {
            layer_name: layer_name.into(),
            points: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn layer_name(&self) -> &str {
        &self.layer_name
    }

    pub fn add_location(&self, id: impl Into<String>, latitude: f64, longitude: f64) {
        let id_str = id.into();
        self.points.write().unwrap().insert(
            id_str.clone(),
            GeoPoint {
                id: id_str,
                latitude,
                longitude,
            },
        );
    }

    pub fn find_within_radius(&self, center_lat: f64, center_lon: f64, radius_km: f64) -> Vec<GeoPoint> {
        let guard = self.points.read().unwrap();
        guard
            .values()
            .filter(|pt| Self::haversine_km(center_lat, center_lon, pt.latitude, pt.longitude) <= radius_km)
            .cloned()
            .collect()
    }

    pub fn haversine_km(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
        const EARTH_RADIUS_KM: f64 = 6371.0;
        let d_lat = (lat2 - lat1).to_radians();
        let d_lon = (lon2 - lon1).to_radians();
        let a = (d_lat / 2.0).sin().powi(2)
            + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon / 2.0).sin().powi(2);
        let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
        EARTH_RADIUS_KM * c
    }

    pub fn size(&self) -> usize {
        self.points.read().unwrap().len()
    }

    pub fn snapshot(&self) -> HashMap<String, GeoPoint> {
        self.points.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.points.write().unwrap().clear();
    }
}

// ============================================================================
// 7. ColumnarEngine: Motor Columnar Analítico (OLAP)
// ============================================================================

#[derive(Debug, Clone)]
pub struct ColumnarEngine {
    table_name: String,
    numeric_columns: Arc<RwLock<HashMap<String, Vec<f64>>>>,
    text_columns: Arc<RwLock<HashMap<String, Vec<String>>>>,
    row_count: Arc<RwLock<usize>>,
}

impl ColumnarEngine {
    pub fn new(table_name: impl Into<String>) -> Self {
        Self {
            table_name: table_name.into(),
            numeric_columns: Arc::new(RwLock::new(HashMap::new())),
            text_columns: Arc::new(RwLock::new(HashMap::new())),
            row_count: Arc::new(RwLock::new(0)),
        }
    }

    pub fn table_name(&self) -> &str {
        &self.table_name
    }

    pub fn append_row(&self, values: &BTreeMap<String, JsonValue>) {
        let mut num_guard = self.numeric_columns.write().unwrap();
        let mut txt_guard = self.text_columns.write().unwrap();
        for (k, v) in values {
            match v {
                JsonValue::Number(n) => {
                    num_guard.entry(k.clone()).or_default().push(*n);
                }
                other => {
                    txt_guard
                        .entry(k.clone())
                        .or_default()
                        .push(other.to_string_value());
                }
            }
        }
        *self.row_count.write().unwrap() += 1;
    }

    pub fn append_batch(
        &self,
        num_cols: HashMap<String, Vec<f64>>,
        txt_cols: HashMap<String, Vec<String>>,
        count: usize,
    ) {
        let mut num_guard = self.numeric_columns.write().unwrap();
        for (k, vals) in num_cols {
            num_guard.entry(k).or_default().extend(vals);
        }
        let mut txt_guard = self.text_columns.write().unwrap();
        for (k, vals) in txt_cols {
            txt_guard.entry(k).or_default().extend(vals);
        }
        *self.row_count.write().unwrap() += count;
    }

    pub fn get_numeric_column(&self, col_name: &str) -> Vec<f64> {
        self.numeric_columns
            .read()
            .unwrap()
            .get(col_name)
            .cloned()
            .unwrap_or_default()
    }

    pub fn sum_column(&self, col_name: &str) -> f64 {
        self.numeric_columns
            .read()
            .unwrap()
            .get(col_name)
            .map(|c| c.iter().sum())
            .unwrap_or(0.0)
    }

    pub fn row_count(&self) -> usize {
        *self.row_count.read().unwrap()
    }

    pub fn snapshot_numeric(&self) -> HashMap<String, Vec<f64>> {
        self.numeric_columns.read().unwrap().clone()
    }

    pub fn snapshot_text(&self) -> HashMap<String, Vec<String>> {
        self.text_columns.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.numeric_columns.write().unwrap().clear();
        self.text_columns.write().unwrap().clear();
        *self.row_count.write().unwrap() = 0;
    }
}

// ============================================================================
// 8. RecordsEngine: Motor de Registros Tipados (Records / Auditoría)
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
pub struct FacturaAuditRecord {
    pub audit_id: String,
    pub factura_id: String,
    pub rfc_emisor: String,
    pub monto_auditado: f64,
    pub resultado: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum TypedRecord {
    FacturaAudit(FacturaAuditRecord),
    Generic {
        type_name: String,
        fields: BTreeMap<String, JsonValue>,
    },
}

#[derive(Debug, Clone)]
pub struct RecordsEngine {
    store_name: String,
    records: Arc<RwLock<HashMap<String, TypedRecord>>>,
}

impl RecordsEngine {
    pub fn new(store_name: impl Into<String>) -> Self {
        Self {
            store_name: store_name.into(),
            records: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn store_name(&self) -> &str {
        &self.store_name
    }

    pub fn save_record(&self, id: impl Into<String>, record: TypedRecord) {
        self.records.write().unwrap().insert(id.into(), record);
    }

    pub fn get_record(&self, id: &str) -> Option<TypedRecord> {
        self.records.read().unwrap().get(id).cloned()
    }

    pub fn delete_record(&self, id: &str) -> Option<TypedRecord> {
        self.records.write().unwrap().remove(id)
    }

    pub fn size(&self) -> usize {
        self.records.read().unwrap().len()
    }

    pub fn snapshot(&self) -> HashMap<String, TypedRecord> {
        self.records.read().unwrap().clone()
    }

    pub fn clear(&self) {
        self.records.write().unwrap().clear();
    }
}


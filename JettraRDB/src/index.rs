//! Gestor de Índices Secundarios (`HASH` y `BTREE`) de JettraRDB.

use crate::engines::{Document, DocumentEngine};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IndexType {
    Hash,
    BTree,
}

impl IndexType {
    pub fn from_str(s: &str) -> Self {
        if s.eq_ignore_ascii_case("BTREE") {
            Self::BTree
        } else {
            Self::Hash
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Hash => "HASH",
            Self::BTree => "BTREE",
        }
    }
}

#[derive(Debug, Clone)]
pub struct IndexMetadata {
    pub index_name: String,
    pub collection_name: String,
    pub field_name: String,
    pub index_type: IndexType,
    pub unique: bool,
    pub created_at: u64,
}

#[derive(Debug, Clone)]
enum InvertedStorage {
    Hash(HashMap<String, HashSet<String>>),
    BTree(BTreeMap<String, HashSet<String>>),
}

impl InvertedStorage {
    fn new(index_type: IndexType) -> Self {
        match index_type {
            IndexType::Hash => Self::Hash(HashMap::new()),
            IndexType::BTree => Self::BTree(BTreeMap::new()),
        }
    }

    fn get(&self, key: &str) -> Vec<String> {
        match self {
            Self::Hash(m) => m
                .get(key)
                .map(|s| s.iter().cloned().collect())
                .unwrap_or_default(),
            Self::BTree(m) => m
                .get(key)
                .map(|s| s.iter().cloned().collect())
                .unwrap_or_default(),
        }
    }

    fn insert(&mut self, key: String, doc_id: String, unique: bool) -> Result<(), String> {
        let set = match self {
            Self::Hash(m) => m.entry(key.clone()).or_default(),
            Self::BTree(m) => m.entry(key.clone()).or_default(),
        };
        if unique && !set.is_empty() && !set.contains(&doc_id) {
            return Err(format!("Unique constraint violation for key '{}'", key));
        }
        set.insert(doc_id);
        Ok(())
    }

    fn remove(&mut self, key: &str, doc_id: &str) {
        match self {
            Self::Hash(m) => {
                if let Some(set) = m.get_mut(key) {
                    set.remove(doc_id);
                    if set.is_empty() {
                        m.remove(key);
                    }
                }
            }
            Self::BTree(m) => {
                if let Some(set) = m.get_mut(key) {
                    set.remove(doc_id);
                    if set.is_empty() {
                        m.remove(key);
                    }
                }
            }
        }
    }

    fn clear(&mut self) {
        match self {
            Self::Hash(m) => m.clear(),
            Self::BTree(m) => m.clear(),
        }
    }

    fn range(&self, from_inclusive: &str, to_inclusive: &str) -> Vec<String> {
        match self {
            Self::BTree(m) => m
                .range(from_inclusive.to_string()..=to_inclusive.to_string())
                .flat_map(|(_, set)| set.iter().cloned())
                .collect(),
            Self::Hash(m) => m
                .iter()
                .filter(|(k, _)| k.as_str() >= from_inclusive && k.as_str() <= to_inclusive)
                .flat_map(|(_, set)| set.iter().cloned())
                .collect(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct JettraIndexManager {
    indexes: Arc<RwLock<HashMap<String, IndexMetadata>>>,
    inverted_data: Arc<RwLock<HashMap<String, InvertedStorage>>>,
}

impl Default for JettraIndexManager {
    fn default() -> Self {
        Self::new()
    }
}

impl JettraIndexManager {
    pub fn new() -> Self {
        Self {
            indexes: Arc::new(RwLock::new(HashMap::new())),
            inverted_data: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn create_index(
        &self,
        index_name: impl Into<String>,
        collection_name: impl Into<String>,
        field_name: impl Into<String>,
        index_type: IndexType,
        unique: bool,
    ) -> IndexMetadata {
        let name = index_name.into();
        let meta = IndexMetadata {
            index_name: name.clone(),
            collection_name: collection_name.into(),
            field_name: field_name.into(),
            index_type,
            unique,
            created_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
        };
        self.indexes.write().unwrap().insert(name.clone(), meta.clone());
        self.inverted_data
            .write()
            .unwrap()
            .insert(name, InvertedStorage::new(index_type));
        meta
    }

    pub fn drop_index(&self, index_name: &str) -> bool {
        self.inverted_data.write().unwrap().remove(index_name);
        self.indexes.write().unwrap().remove(index_name).is_some()
    }

    pub fn list_indexes(&self) -> Vec<IndexMetadata> {
        self.indexes.read().unwrap().values().cloned().collect()
    }

    pub fn on_document_saved(
        &self,
        collection_name: &str,
        doc_id: &str,
        document: &Document,
    ) -> Result<(), String> {
        let indexes_guard = self.indexes.read().unwrap();
        let mut data_guard = self.inverted_data.write().unwrap();

        for meta in indexes_guard.values() {
            if meta.collection_name.eq_ignore_ascii_case(collection_name) {
                if let Some(val) = document.get(&meta.field_name) {
                    let key_str = val.to_string_value();
                    if let Some(storage) = data_guard.get_mut(&meta.index_name) {
                        storage.insert(key_str, doc_id.to_string(), meta.unique)?;
                    }
                }
            }
        }
        Ok(())
    }

    pub fn on_document_removed(&self, collection_name: &str, doc_id: &str, document: &Document) {
        let indexes_guard = self.indexes.read().unwrap();
        let mut data_guard = self.inverted_data.write().unwrap();

        for meta in indexes_guard.values() {
            if meta.collection_name.eq_ignore_ascii_case(collection_name) {
                if let Some(val) = document.get(&meta.field_name) {
                    let key_str = val.to_string_value();
                    if let Some(storage) = data_guard.get_mut(&meta.index_name) {
                        storage.remove(&key_str, doc_id);
                    }
                }
            }
        }
    }

    pub fn find_document_ids(
        &self,
        collection_name: &str,
        field_name: &str,
        value: &str,
    ) -> Option<Vec<String>> {
        let indexes_guard = self.indexes.read().unwrap();
        let data_guard = self.inverted_data.read().unwrap();

        for meta in indexes_guard.values() {
            if meta.collection_name.eq_ignore_ascii_case(collection_name)
                && meta.field_name.eq_ignore_ascii_case(field_name)
            {
                if let Some(storage) = data_guard.get(&meta.index_name) {
                    return Some(storage.get(value));
                }
            }
        }
        None
    }

    pub fn find_range(
        &self,
        index_name: &str,
        from_inclusive: &str,
        to_inclusive: &str,
    ) -> Vec<String> {
        let data_guard = self.inverted_data.read().unwrap();
        data_guard
            .get(index_name)
            .map(|s| s.range(from_inclusive, to_inclusive))
            .unwrap_or_default()
    }

    pub fn rebuild_index(&self, index_name: &str, doc_engine: &DocumentEngine) -> Result<usize, String> {
        let meta = self
            .indexes
            .read()
            .unwrap()
            .get(index_name)
            .cloned()
            .ok_or_else(|| format!("Index not found: {}", index_name))?;

        let docs = doc_engine.snapshot();
        let mut data_guard = self.inverted_data.write().unwrap();
        let storage = data_guard
            .entry(index_name.to_string())
            .or_insert_with(|| InvertedStorage::new(meta.index_type));
        storage.clear();

        let mut indexed = 0;
        for (doc_id, doc) in docs {
            if let Some(val) = doc.get(&meta.field_name) {
                storage.insert(val.to_string_value(), doc_id, meta.unique)?;
                indexed += 1;
            }
        }
        Ok(indexed)
    }
}


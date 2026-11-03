//! Configuración unificada de JettraRDB compatible con `database.properties` y `jettra.config`.

use std::collections::HashMap;
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StorageMode {
    /// Almacenamiento 100% en memoria nativa de alta velocidad.
    Ram,
    /// Almacenamiento híbrido LSM + SSTable en disco.
    DiskMemory,
}

#[derive(Debug, Clone)]
pub struct JettraConfig {
    pub storage_mode: StorageMode,
    pub storage_path: String,
    pub file_extension: String,
    pub host: String,
    pub port: u16,
    pub raft_port: u16,
    pub node_id: String,
    pub node_role: String,
    pub multinode_active: bool,
    pub peers: Vec<String>,
    pub ring_saturation_threshold: f64,
    pub ring_offload_target_pct: f64,
    pub query_default_limit: usize,
    pub query_max_limit: usize,
    pub max_memory_bytes: u64,
}

impl Default for JettraConfig {
    fn default() -> Self {
        Self {
            storage_mode: StorageMode::DiskMemory,
            storage_path: "/tmp/jettrardb/data".to_string(),
            file_extension: ".jettra".to_string(),
            host: "0.0.0.0".to_string(),
            port: 9080,
            raft_port: 9081,
            node_id: "jettrardb-node-1".to_string(),
            node_role: "PRIMARY".to_string(),
            multinode_active: false,
            peers: Vec::new(),
            ring_saturation_threshold: 0.85,
            ring_offload_target_pct: 0.45,
            query_default_limit: 50,
            query_max_limit: 5000,
            max_memory_bytes: 4 * 1024 * 1024 * 1024, // 4 GiB por defecto
        }
    }
}

impl JettraConfig {
    pub fn in_memory(storage_path: &str) -> Self {
        Self {
            storage_mode: StorageMode::Ram,
            storage_path: storage_path.to_string(),
            ..Default::default()
        }
    }

    pub fn from_properties_file<P: AsRef<Path>>(path: P) -> Result<Self, String> {
        let content = fs::read_to_string(path).map_err(|e| e.to_string())?;
        Ok(Self::from_properties_str(&content))
    }

    pub fn from_properties_str(content: &str) -> Self {
        let mut props = HashMap::new();
        for line in content.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with("//") {
                continue;
            }
            if let Some((k, v)) = trimmed.split_once('=') {
                props.insert(k.trim().to_string(), v.trim().to_string());
            }
        }

        let mut cfg = Self::default();
        if let Some(mode) = props.get("storage.mode") {
            if mode.eq_ignore_ascii_case("RAM") || mode.eq_ignore_ascii_case("JVM_RAM") {
                cfg.storage_mode = StorageMode::Ram;
            } else {
                cfg.storage_mode = StorageMode::DiskMemory;
            }
        }
        if let Some(p) = props.get("storage.path") {
            cfg.storage_path = p.clone();
        }
        if let Some(ext) = props.get("storage.extension") {
            cfg.file_extension = ext.clone();
        }
        if let Some(h) = props.get("server.host") {
            cfg.host = h.clone();
        }
        if let Some(port) = props.get("server.port").and_then(|s| s.parse().ok()) {
            cfg.port = port;
        }
        if let Some(rport) = props.get("cluster.raft.port").and_then(|s| s.parse().ok()) {
            cfg.raft_port = rport;
        } else {
            cfg.raft_port = cfg.port + 1;
        }
        if let Some(id) = props.get("cluster.node.id") {
            cfg.node_id = id.clone();
        }
        if let Some(role) = props.get("cluster.node.role") {
            cfg.node_role = role.to_uppercase();
        }
        if let Some(active) = props.get("cluster.multinode.active") {
            let a = active.to_lowercase();
            cfg.multinode_active = a == "on" || a == "true" || a == "1";
        }
        if let Some(peers_str) = props.get("cluster.peers") {
            cfg.peers = peers_str
                .split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
        }
        if let Some(th) = props
            .get("ring.saturation.threshold")
            .and_then(|s| s.parse().ok())
        {
            cfg.ring_saturation_threshold = th;
        }
        if let Some(off) = props
            .get("ring.offload.target")
            .and_then(|s| s.parse().ok())
        {
            cfg.ring_offload_target_pct = off;
        }
        if let Some(dl) = props
            .get("query.limit.default")
            .and_then(|s| s.parse().ok())
        {
            cfg.query_default_limit = dl;
        }
        if let Some(ml) = props.get("query.limit.max").and_then(|s| s.parse().ok()) {
            cfg.query_max_limit = ml;
        }
        cfg
    }
}


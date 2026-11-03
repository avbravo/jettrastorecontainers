//! Servidor Distribuido de JettraRDB (`JettraRdbServer`):
//! - Transporte HTTP/1.1 REST API (`/api/v1/health`, `/api/v1/auth/login`, `/api/v1/cluster/status`,
//!   `/api/v1/cluster/databases`, `/api/v1/cluster/live`, `/api/v1/police/alerts`, `/api/v1/query`).
//! - Transporte Binario Raft TCP (`JettraClusterProtocol` `0x4A54` `'JT'`).

use crate::cluster::{
    ClusterNode, DynamicRingEngine, JettraClusterProtocol, JettraRaftFrame, TYPE_CREATE_DATABASE,
    TYPE_DELETE_DOCUMENT, TYPE_DROP_DATABASE, TYPE_HEARTBEAT, TYPE_PUT_DOCUMENT,
};
use crate::config::JettraConfig;
use crate::database::JettraDatabase;
use crate::json::JsonValue;
use crate::query::JettraSQLProcessor;
use crate::security::JettraSecurityManager;
use std::collections::{BTreeMap, HashMap};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use std::thread;

#[derive(Clone)]
pub struct JettraRdbServer {
    config: JettraConfig,
    databases: Arc<RwLock<HashMap<String, JettraDatabase>>>,
    security_manager: JettraSecurityManager,
    ring_engine: DynamicRingEngine,
    running: Arc<AtomicBool>,
}

impl JettraRdbServer {
    pub fn new(config: JettraConfig) -> Self {
        let ring = DynamicRingEngine::new(
            &config.node_id,
            &config.node_role,
            config.ring_saturation_threshold,
            config.ring_offload_target_pct,
        );
        ring.register_peer(ClusterNode {
            node_id: config.node_id.clone(),
            host: config.host.clone(),
            port: config.port,
            role: if config.node_role.eq_ignore_ascii_case("SECONDARY") {
                "FOLLOWER".to_string()
            } else {
                "LEADER".to_string()
            },
            memory_usage_percent: 12.5,
            active: true,
        });
        Self {
            config,
            databases: Arc::new(RwLock::new(HashMap::new())),
            security_manager: JettraSecurityManager::new(),
            ring_engine: ring,
            running: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn get_or_create_database(&self, name: &str) -> JettraDatabase {
        let mut guard = self.databases.write().unwrap();
        guard
            .entry(name.to_string())
            .or_insert_with(|| JettraDatabase::new(name, self.config.clone()))
            .clone()
    }

    pub fn drop_database(&self, name: &str) -> bool {
        self.databases.write().unwrap().remove(name).is_some()
    }

    pub fn list_databases(&self) -> Vec<String> {
        let mut names: Vec<String> = self.databases.read().unwrap().keys().cloned().collect();
        names.sort();
        names
    }

    pub fn security_manager(&self) -> &JettraSecurityManager {
        &self.security_manager
    }

    pub fn ring_engine(&self) -> &DynamicRingEngine {
        &self.ring_engine
    }

    pub fn apply_raft_frame(&self, frame: &JettraRaftFrame) -> JettraRaftFrame {
        match frame.frame_type {
            TYPE_HEARTBEAT => {
                JettraRaftFrame::heartbeat_ack(self.ring_engine.current_term(), &self.config.node_id)
            }
            TYPE_CREATE_DATABASE => {
                let _ = self.get_or_create_database(&frame.database_name);
                self.ring_engine.record_event(
                    "CREATE_DATABASE",
                    &format!("Base de datos '{}' replicada vía Raft", frame.database_name),
                );
                JettraRaftFrame::ack(
                    self.ring_engine.current_term(),
                    frame.log_index,
                    &self.config.node_id,
                    "DB_CREATED",
                )
            }
            TYPE_DROP_DATABASE => {
                self.drop_database(&frame.database_name);
                JettraRaftFrame::ack(
                    self.ring_engine.current_term(),
                    frame.log_index,
                    &self.config.node_id,
                    "DB_DROPPED",
                )
            }
            TYPE_PUT_DOCUMENT => {
                let db = self.get_or_create_database(&frame.database_name);
                if let Ok(JsonValue::Object(doc)) = JsonValue::parse(&frame.payload_as_string()) {
                    let _ = db.save_document(&frame.collection_name, &frame.key, doc);
                }
                JettraRaftFrame::ack(
                    self.ring_engine.current_term(),
                    frame.log_index,
                    &self.config.node_id,
                    "DOC_SAVED",
                )
            }
            TYPE_DELETE_DOCUMENT => {
                let db = self.get_or_create_database(&frame.database_name);
                db.delete_document(&frame.collection_name, &frame.key);
                JettraRaftFrame::ack(
                    self.ring_engine.current_term(),
                    frame.log_index,
                    &self.config.node_id,
                    "DOC_DELETED",
                )
            }
            _ => JettraRaftFrame::ack(
                self.ring_engine.current_term(),
                frame.log_index,
                &self.config.node_id,
                "OK",
            ),
        }
    }

    pub fn start_blocking(&self) -> Result<(), String> {
        self.running.store(true, Ordering::Relaxed);

        // 1. Iniciar servidor TCP binario Raft en hilo dedicado
        let raft_addr = format!("{}:{}", self.config.host, self.config.raft_port);
        let raft_listener = TcpListener::bind(&raft_addr).map_err(|e| e.to_string())?;
        let srv_raft = self.clone();
        thread::spawn(move || {
            for stream in raft_listener.incoming().flatten() {
                let s = srv_raft.clone();
                thread::spawn(move || {
                    let mut conn = stream;
                    if let Ok(frame) = JettraClusterProtocol::read_frame(&mut conn) {
                        let resp = s.apply_raft_frame(&frame);
                        let _ = JettraClusterProtocol::write_frame(&mut conn, &resp);
                    }
                });
            }
        });

        // 2. Iniciar servidor HTTP/1.1 REST API
        let http_addr = format!("{}:{}", self.config.host, self.config.port);
        let http_listener = TcpListener::bind(&http_addr).map_err(|e| e.to_string())?;
        println!(
            "========================================================================\n\
             JettraRDB Distributed Multi-Model Engine v1.0.0 (Rust Native Zero-GC)\n\
             - Node ID   : {} ({})\n\
             - HTTP REST : http://{}\n\
             - Raft TCP  : tcp://{}\n\
             ========================================================================",
            self.config.node_id, self.config.node_role, http_addr, raft_addr
        );

        for stream in http_listener.incoming().flatten() {
            if !self.running.load(Ordering::Relaxed) {
                break;
            }
            let srv = self.clone();
            thread::spawn(move || {
                let _ = srv.handle_http_client(stream);
            });
        }
        Ok(())
    }

    fn handle_http_client(&self, mut stream: TcpStream) -> std::io::Result<()> {
        let mut reader = BufReader::new(stream.try_clone()?);
        let mut request_line = String::new();
        reader.read_line(&mut request_line)?;
        let parts: Vec<&str> = request_line.split_whitespace().collect();
        if parts.len() < 2 {
            return Ok(());
        }
        let method = parts[0];
        let path = parts[1];

        let mut content_length = 0usize;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line)?;
            if line == "\r\n" || line.is_empty() {
                break;
            }
            if let Some((k, v)) = line.split_once(':') {
                if k.trim().eq_ignore_ascii_case("content-length") {
                    content_length = v.trim().parse().unwrap_or(0);
                }
            }
        }

        let mut body_bytes = vec![0u8; content_length];
        if content_length > 0 {
            reader.read_exact(&mut body_bytes)?;
        }
        let body = String::from_utf8_lossy(&body_bytes).to_string();

        let (status, response_json) = self.route_http(method, path, &body);
        let status_text = match status {
            200 => "200 OK",
            401 => "401 Unauthorized",
            404 => "404 Not Found",
            _ => "400 Bad Request",
        };
        let http_resp = format!(
            "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            status_text,
            response_json.len(),
            response_json
        );
        stream.write_all(http_resp.as_bytes())?;
        stream.flush()
    }

    pub fn route_http(&self, method: &str, path: &str, body: &str) -> (u16, String) {
        match (method, path) {
            ("GET", "/api/v1/health") => {
                let mut m = BTreeMap::new();
                m.insert("status".to_string(), JsonValue::Str("UP".to_string()));
                m.insert("engine".to_string(), JsonValue::Str("JettraRDB-Rust".to_string()));
                m.insert("nodeId".to_string(), JsonValue::Str(self.config.node_id.clone()));
                m.insert("role".to_string(), JsonValue::Str(self.ring_engine.local_role()));
                (200, JsonValue::Object(m).to_json())
            }
            ("POST", "/api/v1/auth/login") => {
                if let Ok(JsonValue::Object(obj)) = JsonValue::parse(body) {
                    let u = obj.get("username").and_then(|v| v.as_str()).unwrap_or("");
                    let p = obj.get("password").and_then(|v| v.as_str()).unwrap_or("");
                    match self.security_manager.authenticate(u, p) {
                        Ok(token) => {
                            let mut m = BTreeMap::new();
                            m.insert("status".to_string(), JsonValue::Str("AUTHENTICATED".to_string()));
                            m.insert("token".to_string(), JsonValue::Str(token));
                            (200, JsonValue::Object(m).to_json())
                        }
                        Err(e) => {
                            let mut m = BTreeMap::new();
                            m.insert("error".to_string(), JsonValue::Str(e));
                            (401, JsonValue::Object(m).to_json())
                        }
                    }
                } else {
                    (400, "{\"error\":\"Invalid JSON\"}".to_string())
                }
            }
            ("GET", "/api/v1/cluster/status") => {
                let mut m = BTreeMap::new();
                m.insert("nodeId".to_string(), JsonValue::Str(self.config.node_id.clone()));
                m.insert("role".to_string(), JsonValue::Str(self.ring_engine.local_role()));
                m.insert("term".to_string(), JsonValue::Number(self.ring_engine.current_term() as f64));
                m.insert(
                    "ringActive".to_string(),
                    JsonValue::Bool(self.ring_engine.is_ring_active()),
                );
                let dbs: Vec<JsonValue> = self
                    .list_databases()
                    .into_iter()
                    .map(JsonValue::Str)
                    .collect();
                m.insert("databases".to_string(), JsonValue::Array(dbs));
                (200, JsonValue::Object(m).to_json())
            }
            ("GET", "/api/v1/cluster/databases") => {
                let dbs: Vec<JsonValue> = self
                    .list_databases()
                    .into_iter()
                    .map(JsonValue::Str)
                    .collect();
                (200, JsonValue::Array(dbs).to_json())
            }
            ("POST", "/api/v1/query") => {
                if let Ok(JsonValue::Object(obj)) = JsonValue::parse(body) {
                    let db_name = obj
                        .get("database")
                        .and_then(|v| v.as_str())
                        .unwrap_or("default");
                    let query = obj.get("query").and_then(|v| v.as_str()).unwrap_or("");
                    let db = self.get_or_create_database(db_name);
                    let res = JettraSQLProcessor::new(&db).execute(query);
                    let mut m = BTreeMap::new();
                    m.insert("message".to_string(), JsonValue::Str(res.message));
                    m.insert(
                        "affectedRows".to_string(),
                        JsonValue::Number(res.affected_rows as f64),
                    );
                    let cols: Vec<JsonValue> = res.columns.into_iter().map(JsonValue::Str).collect();
                    m.insert("columns".to_string(), JsonValue::Array(cols));
                    let rows: Vec<JsonValue> = res
                        .rows
                        .into_iter()
                        .map(|r| JsonValue::Array(r.into_iter().map(JsonValue::Str).collect()))
                        .collect();
                    m.insert("rows".to_string(), JsonValue::Array(rows));
                    (200, JsonValue::Object(m).to_json())
                } else {
                    (400, "{\"error\":\"Expected JSON with database and query\"}".to_string())
                }
            }
            _ => (404, "{\"error\":\"Endpoint not found\"}".to_string()),
        }
    }
}


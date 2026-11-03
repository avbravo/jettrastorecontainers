use jettrardb::calc::{JettraFinance, JettraMath, JettraStatistics, JettraVectorMath};
use jettrardb::cluster::{ClusterNode, JettraClusterProtocol, JettraRaftFrame};
use jettrardb::config::JettraConfig;
use jettrardb::database::JettraDatabase;
use jettrardb::engines::{FacturaAuditRecord, FetchMode, JettraRef, TypedRecord};
use jettrardb::index::IndexType;
use jettrardb::json::JsonValue;
use jettrardb::query::{JettraQLProcessor, JettraSQLProcessor};
use jettrardb::security::{JettraSecurityManager, DEFAULT_ADMIN_PASS, DEFAULT_ADMIN_USER};
use jettrardb::server::JettraRdbServer;
use jettrardb::storage::NativeMemTable;
use std::collections::BTreeMap;

#[test]
fn test_all_eight_multimodel_engines() {
    let cfg = JettraConfig::in_memory("/tmp/jettrardb_test_engines");
    let db = JettraDatabase::new("multimodel_db", cfg);

    // 1. DocumentEngine + JettraRef
    let mut doc = BTreeMap::new();
    doc.insert("cliente".to_string(), JsonValue::Str("ACME Corp".to_string()));
    doc.insert("monto".to_string(), JsonValue::Number(2500.0));
    db.save_document("facturas", "FAC-1", doc).unwrap();
    assert_eq!(db.document_engine("facturas").count(), 1);

    let mut jref = JettraRef::new("DOCUMENT::facturas", "FAC-1", FetchMode::Lazy);
    assert!(!jref.is_resolved());
    jref.resolve_with(|_eng, id| {
        db.document_engine("facturas")
            .find_by_id(id)
            .map(JsonValue::Object)
    });
    assert!(jref.is_resolved());

    // 2. KeyValueEngine
    let kv = db.kv_engine("sessions");
    kv.put_str("tok-1", "user-admin");
    assert_eq!(kv.get_str("tok-1").as_deref(), Some("user-admin"));

    // 3. VectorEngine
    let vec_eng = db.vector_engine("embeddings", 3);
    vec_eng.index("v1", vec![1.0, 0.0, 0.0]).unwrap();
    vec_eng.index("v2", vec![0.9, 0.1, 0.0]).unwrap();
    vec_eng.index("v3", vec![0.0, 0.0, 1.0]).unwrap();
    let top = vec_eng.search_cosine(&[1.0, 0.0, 0.0], 2);
    assert_eq!(top.len(), 2);
    assert_eq!(top[0].id, "v1");

    // 4. GraphEngine
    let graph = db.graph_engine("social");
    graph.add_edge("Alice", "Bob", "KNOWS", BTreeMap::new());
    assert_eq!(graph.vertex_count(), 2);
    assert_eq!(graph.edge_count(), 1);
    assert_eq!(graph.get_outbound_edges("Alice")[0].target_vertex, "Bob");

    // 5. TimeSeriesEngine
    let ts = db.time_series_engine("cpu_load");
    ts.record(1000, 40.0);
    ts.record(1010, 60.0);
    assert!((ts.average(1000, 1010) - 50.0).abs() < 1e-6);

    // 6. GeospatialEngine
    let gis = db.geospatial_engine("branches");
    gis.add_location("PanamaCity", 8.9824, -79.5199);
    gis.add_location("Colon", 9.3547, -79.9001);
    let nearby = gis.find_within_radius(8.9824, -79.5199, 10.0);
    assert_eq!(nearby.len(), 1);
    assert_eq!(nearby[0].id, "PanamaCity");

    // 7. ColumnarEngine
    let col = db.columnar_engine("ventas_olap");
    let mut r1 = BTreeMap::new();
    r1.insert("total".to_string(), JsonValue::Number(100.0));
    let mut r2 = BTreeMap::new();
    r2.insert("total".to_string(), JsonValue::Number(250.0));
    col.append_row(&r1);
    col.append_row(&r2);
    assert_eq!(col.row_count(), 2);
    assert!((col.sum_column("total") - 350.0).abs() < 1e-6);

    // 8. RecordsEngine
    let rec = db.records_engine("auditoria");
    rec.save_record(
        "AUD-1",
        TypedRecord::FacturaAudit(FacturaAuditRecord {
            audit_id: "AUD-1".to_string(),
            factura_id: "FAC-1".to_string(),
            rfc_emisor: "JET260101AAA".to_string(),
            monto_auditado: 2500.0,
            resultado: "OK".to_string(),
            timestamp: 1_700_000_000,
        }),
    );
    assert_eq!(rec.size(), 1);
}

#[test]
fn test_sql_jql_and_secondary_indexes() {
    let cfg = JettraConfig::in_memory("/tmp/jettrardb_test_sql");
    let db = JettraDatabase::new("sql_db", cfg);
    db.create_index("idx_estado", "facturas", "estado", IndexType::Hash, false)
        .unwrap();

    let sql = JettraSQLProcessor::new(&db);
    sql.execute("INSERT INTO facturas (_id, cliente, estado, total) VALUES ('F1', 'C1', 'PAGADA', 100)");
    sql.execute("INSERT INTO facturas (_id, cliente, estado, total) VALUES ('F2', 'C2', 'PAGADA', 300)");
    sql.execute("INSERT INTO facturas (_id, cliente, estado, total) VALUES ('F3', 'C1', 'PENDIENTE', 200)");

    let sel = sql.execute("SELECT _id, total FROM facturas WHERE estado = 'PAGADA'");
    assert_eq!(sel.affected_rows, 2);

    let agg = sql.execute("SELECT estado, COUNT(*), SUM(total) FROM facturas GROUP BY estado");
    assert_eq!(agg.affected_rows, 2);

    let jql = JettraQLProcessor::new(&db);
    let find_res = jql.execute("FIND facturas 'F1'");
    assert_eq!(find_res.affected_rows, 1);
}

#[test]
fn test_calc_math_finance_statistics_vectors() {
    assert!((JettraMath::eval("2 + 3 * 4").unwrap() - 14.0).abs() < 1e-6);
    assert!((JettraMath::eval("sqrt(16) + pow(2, 3)").unwrap() - 12.0).abs() < 1e-6);

    let pmt = JettraFinance::pmt(0.01, 12, 1000.0);
    assert!(pmt > 88.0 && pmt < 89.0);
    assert!((JettraFinance::roi(1500.0, 1000.0) - 50.0).abs() < 1e-6);

    let data = vec![10.0, 20.0, 30.0, 40.0, 50.0];
    assert!((JettraStatistics::mean(&data) - 30.0).abs() < 1e-6);
    assert!((JettraStatistics::median(&data) - 30.0).abs() < 1e-6);

    let dot = JettraVectorMath::dot_product(&[1.0, 2.0, 3.0], &[4.0, 5.0, 6.0]).unwrap();
    assert!((dot - 32.0).abs() < 1e-5);
}

#[test]
fn test_sstable_backup_security_and_raft_protocol() {
    // 1. SSTable & Backup
    let dir = "/tmp/jettrardb_test_sstable";
    let sstable_path = format!("{}/test.jettra", dir);
    let backup_path = format!("{}/backup.jettra_bak", dir);
    let mem = NativeMemTable::new(1024 * 1024);
    mem.write_entry(1, "facturas#F1", b"{\"_id\":\"F1\"}").unwrap();
    mem.flush_to_disk(&sstable_path).unwrap();

    let records = NativeMemTable::read_sstable(&sstable_path).unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].key, "facturas#F1");

    let meta = jettrardb::storage::BackupManager::backup_file("test_db", &sstable_path, &backup_path).unwrap();
    assert!(meta.size_bytes > 0);
    assert!(meta.checksum != 0);

    // 2. Security & JWT
    let sec = JettraSecurityManager::new();
    let token = sec.authenticate(DEFAULT_ADMIN_USER, DEFAULT_ADMIN_PASS).unwrap();
    let claims = sec.validate_token(&token).unwrap();
    assert_eq!(claims.username, "admin");
    assert!(sec.has_database_access("admin", "any_db", "WRITE"));

    // 3. Raft binary wire protocol & Ring failover
    let frame = JettraRaftFrame::put_document(
        3,
        42,
        "node-1",
        "ventas_db",
        "facturas",
        "F100",
        b"{\"total\":500}".to_vec(),
    );
    let mut wire = Vec::new();
    JettraClusterProtocol::write_frame(&mut wire, &frame).unwrap();
    let mut cursor = std::io::Cursor::new(wire);
    let decoded = JettraClusterProtocol::read_frame(&mut cursor).unwrap();
    assert_eq!(decoded, frame);

    let srv = JettraRdbServer::new(JettraConfig::in_memory(dir));
    srv.ring_engine().register_peer(ClusterNode {
        node_id: "node-leader-old".to_string(),
        host: "127.0.0.1".to_string(),
        port: 9080,
        role: "LEADER".to_string(),
        memory_usage_percent: 20.0,
        active: true,
    });
    let new_leader = srv.ring_engine().trigger_failover_election("node-leader-old");
    assert_eq!(new_leader, srv.ring_engine().local_node_id());
    assert_eq!(srv.ring_engine().local_role(), "LEADER");
}


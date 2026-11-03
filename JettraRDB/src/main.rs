//! Binario Principal de JettraRDB:
//! Soporta modo Servidor (`--server`), modo ejecución directa (`--exec "<query>"`)
//! y modo Consola Interactiva Multimodelo (`JettraRDB Shell`).

use jettrardb::config::JettraConfig;
use jettrardb::index::IndexType;
use jettrardb::query::{JettraSQLProcessor, QueryResult};
use jettrardb::server::JettraRdbServer;
use std::env;
use std::io::{self, BufRead, Write};

fn print_table(res: &QueryResult) {
    if res.columns.is_empty() {
        println!("{}", res.message);
        return;
    }
    let mut widths: Vec<usize> = res.columns.iter().map(|c| c.len()).collect();
    for row in &res.rows {
        for (i, cell) in row.iter().enumerate() {
            if i < widths.len() {
                widths[i] = widths[i].max(cell.len());
            }
        }
    }

    let sep: String = widths
        .iter()
        .map(|w| "-".repeat(*w + 2))
        .collect::<Vec<_>>()
        .join("+");
    println!("+{}+", sep);
    let header: String = res
        .columns
        .iter()
        .enumerate()
        .map(|(i, c)| format!(" {:width$} ", c, width = widths[i]))
        .collect::<Vec<_>>()
        .join("|");
    println!("|{}|", header);
    println!("+{}+", sep);
    for row in &res.rows {
        let line: String = row
            .iter()
            .enumerate()
            .map(|(i, cell)| {
                let w = widths.get(i).copied().unwrap_or(cell.len());
                format!(" {:width$} ", cell, width = w)
            })
            .collect::<Vec<_>>()
            .join("|");
        println!("|{}|", line);
    }
    println!("+{}+", sep);
    println!("{} ({} fila(s))", res.message, res.affected_rows);
}

fn execute_shell_command(server: &JettraRdbServer, current_db: &mut String, line: &str) -> bool {
    let trimmed = line.trim().trim_end_matches(';').trim();
    if trimmed.is_empty() {
        return true;
    }
    let upper = trimmed.to_uppercase();

    if upper == "EXIT" || upper == "QUIT" {
        return false;
    } else if upper == "HELP" {
        println!(
            "Comandos de JettraRDB Shell (Rust Multi-Model Distributed Engine):\n\
             - CREATE DATABASE <db> | USE <db> | DROP DATABASE <db> | SHOW DATABASES\n\
             - SHOW ENGINES | SHOW COLLECTIONS | SHOW INDEXES\n\
             - CREATE INDEX <name> ON <col>(<field>) [HASH|BTREE] [UNIQUE]\n\
             - INSTALL SAMPLE [FACTURA | HOSPITAL]\n\
             - SELECT ... FROM <col> [WHERE k=v] [GROUP BY f] [LIMIT n]\n\
             - INSERT INTO <col> (k1, k2) VALUES (v1, v2) | UPDATE <col> SET k=v WHERE _id=id | DELETE FROM <col> WHERE _id=id\n\
             - FROM <col> | FIND <col> <id> | FETCH <col> <id> [RESOLVE REFS]\n\
             - MATCH (<vertex>) IN <graph> | VECTOR SIMILARITY <col> TO [f1,f2,f3] LIMIT <k>\n\
             - MATH <expr> | FINANCE PMT/ROI/NPV/IRR ... | STATS MEAN/MEDIAN/STDDEV/SUMMARY ... | VECTOR DOT/COSINE/CENTROID ...\n\
             - CLUSTER STATUS | POLICE STATUS | EXIT"
        );
        return true;
    } else if upper.starts_with("USE ") {
        let db_name = trimmed[4..].trim();
        let _ = server.get_or_create_database(db_name);
        *current_db = db_name.to_string();
        println!("Base de datos activa cambiada a '{}'", current_db);
        return true;
    } else if upper.starts_with("CREATE DATABASE ") {
        let db_name = trimmed[16..].trim();
        let _ = server.get_or_create_database(db_name);
        println!("Base de datos '{}' creada exitosamente.", db_name);
        return true;
    } else if upper.starts_with("DROP DATABASE ") {
        let db_name = trimmed[14..].trim();
        server.drop_database(db_name);
        println!("Base de datos '{}' eliminada.", db_name);
        return true;
    } else if upper == "SHOW DATABASES" {
        let dbs = server.list_databases();
        let rows = dbs.into_iter().map(|d| vec![d]).collect();
        print_table(&QueryResult {
            columns: vec!["database_name".to_string()],
            affected_rows: 1,
            rows,
            message: "Catálogo de bases de datos".to_string(),
        });
        return true;
    } else if upper == "SHOW ENGINES" {
        let db = server.get_or_create_database(current_db);
        let summary = db.engine_summary();
        let rows: Vec<Vec<String>> = summary
            .into_iter()
            .map(|(k, v)| vec![k, v.to_string()])
            .collect();
        print_table(&QueryResult {
            columns: vec!["engine_type".to_string(), "active_instances".to_string()],
            affected_rows: rows.len(),
            rows,
            message: format!("Motores multimodelo en '{}'", current_db),
        });
        return true;
    } else if upper == "SHOW COLLECTIONS" {
        let db = server.get_or_create_database(current_db);
        let cols = db.list_document_collections();
        let rows: Vec<Vec<String>> = cols
            .into_iter()
            .map(|(name, c)| vec![name, c.to_string()])
            .collect();
        print_table(&QueryResult {
            columns: vec!["collection".to_string(), "documents".to_string()],
            affected_rows: rows.len(),
            rows,
            message: format!("Colecciones documentales en '{}'", current_db),
        });
        return true;
    } else if upper.starts_with("CREATE INDEX ") {
        // CREATE INDEX idx_name ON col(field) [BTREE|HASH] [UNIQUE]
        let rest = &trimmed[13..];
        if let Some(on_idx) = rest.to_uppercase().find(" ON ") {
            let idx_name = rest[..on_idx].trim();
            let after_on = rest[on_idx + 4..].trim();
            if let (Some(open), Some(close)) = (after_on.find('('), after_on.find(')')) {
                let col = after_on[..open].trim();
                let field = after_on[open + 1..close].trim();
                let flags = after_on[close + 1..].to_uppercase();
                let idx_type = if flags.contains("BTREE") {
                    IndexType::BTree
                } else {
                    IndexType::Hash
                };
                let unique = flags.contains("UNIQUE");
                let db = server.get_or_create_database(current_db);
                match db.create_index(idx_name, col, field, idx_type, unique) {
                    Ok(meta) => println!(
                        "Índice '{}' ({}) creado sobre {}.{} (unique={})",
                        meta.index_name,
                        meta.index_type.as_str(),
                        meta.collection_name,
                        meta.field_name,
                        meta.unique
                    ),
                    Err(e) => println!("Error creando índice: {}", e),
                }
                return true;
            }
        }
    } else if upper == "CLUSTER STATUS" {
        let nodes = server.ring_engine().get_ring_nodes();
        let rows: Vec<Vec<String>> = nodes
            .into_iter()
            .map(|n| {
                vec![
                    n.node_id,
                    n.role,
                    format!("{}:{}", n.host, n.port),
                    format!("{:.1}%", n.memory_usage_percent),
                    n.active.to_string(),
                ]
            })
            .collect();
        print_table(&QueryResult {
            columns: vec![
                "node_id".to_string(),
                "raft_role".to_string(),
                "endpoint".to_string(),
                "ram_pct".to_string(),
                "active".to_string(),
            ],
            affected_rows: rows.len(),
            rows,
            message: format!("Estado del Anillo Dinámico Raft (Term {})", server.ring_engine().current_term()),
        });
        return true;
    } else if upper == "POLICE STATUS" {
        let db = server.get_or_create_database(current_db);
        let p = db.police();
        print_table(&QueryResult {
            columns: vec![
                "rss_mb".to_string(),
                "ratio_pct".to_string(),
                "interventions".to_string(),
                "prevented_oom".to_string(),
            ],
            rows: vec![vec![
                (p.process_rss_bytes() / (1024 * 1024)).to_string(),
                format!("{:.2}%", p.current_memory_ratio() * 100.0),
                p.interventions_count().to_string(),
                p.prevented_oom_count().to_string(),
            ]],
            affected_rows: 1,
            message: "JettraPolice Autonomous Memory Sentinel".to_string(),
        });
        return true;
    }

    let db = server.get_or_create_database(current_db);
    let res = JettraSQLProcessor::new(&db).execute(trimmed);
    print_table(&res);
    true
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let mut config = JettraConfig::default();

    if let Some(pos) = args.iter().position(|a| a == "--config") {
        if let Some(cfg_path) = args.get(pos + 1) {
            if let Ok(loaded) = JettraConfig::from_properties_file(cfg_path) {
                config = loaded;
            }
        }
    }

    let server = JettraRdbServer::new(config);
    let mut current_db = "default".to_string();
    let _ = server.get_or_create_database(&current_db);

    if args.iter().any(|a| a == "--server") {
        if let Err(e) = server.start_blocking() {
            eprintln!("Error iniciando JettraRDB Server: {}", e);
            std::process::exit(1);
        }
        return;
    }

    if let Some(pos) = args.iter().position(|a| a == "--exec") {
        if let Some(cmd_batch) = args.get(pos + 1) {
            for cmd in cmd_batch.split(';') {
                if !execute_shell_command(&server, &mut current_db, cmd) {
                    break;
                }
            }
        }
        return;
    }

    println!(
        "====================================================================\n\
         JettraRDB Interactive Shell v1.0.0 (Rust Multi-Model Distributed DB)\n\
         Escribe 'HELP' para ver los comandos o 'EXIT' para salir.\n\
         ===================================================================="
    );
    let stdin = io::stdin();
    loop {
        print!("JettraRDB [{}]> ", current_db);
        let _ = io::stdout().flush();
        let mut line = String::new();
        match stdin.lock().read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {
                if !execute_shell_command(&server, &mut current_db, &line) {
                    break;
                }
            }
            Err(_) => break,
        }
    }
}


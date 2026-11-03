//! Procesadores de Consultas de JettraRDB:
//! - `JettraSQLProcessor`: Motor SQL extendido con `SELECT`, `WHERE`, `GROUP BY`, `LIMIT`, `OFFSET`,
//!   `INSERT INTO`, `UPDATE`, `DELETE FROM`, `AGGREGATE`, `MATH`, `FINANCE`, `STATS`, `VECTOR`, `INSTALL SAMPLE`.
//! - `JettraQLProcessor`: Motor JQL multimodelo con `FROM`, `FIND`, `FETCH ... RESOLVE REFS`,
//!   `MATCH` (Grafos) y `VECTOR SIMILARITY` (Búsqueda de Embeddings).

use crate::calc::{
    AggregateSpec, JettraAggregation, JettraFinance, JettraMath, JettraStatistics, JettraVectorMath,
};
use crate::database::JettraDatabase;
use crate::json::JsonValue;
use std::collections::{BTreeMap, HashMap};

#[derive(Debug, Clone)]
pub struct QueryResult {
    pub columns: Vec<String>,
    pub rows: Vec<Vec<String>>,
    pub affected_rows: usize,
    pub message: String,
}

pub struct JettraSQLProcessor<'a> {
    database: &'a JettraDatabase,
}

impl<'a> JettraSQLProcessor<'a> {
    pub fn new(database: &'a JettraDatabase) -> Self {
        Self { database }
    }

    fn sanitize(s: &str) -> String {
        s.trim()
            .trim_matches(';')
            .trim()
            .trim_matches('\'')
            .trim_matches('"')
            .trim()
            .to_string()
    }

    pub fn execute(&self, sql: &str) -> QueryResult {
        let trimmed = sql.trim().trim_end_matches(';').trim();
        let upper = trimmed.to_uppercase();

        if upper.starts_with("SELECT ") {
            if !upper.contains(" FROM ") {
                return self.execute_scalar(&trimmed[7..]);
            }
            if upper.contains(" GROUP BY ") || Self::has_aggregation(&upper) {
                return self.execute_aggregation(trimmed);
            }
            return self.execute_select(trimmed);
        } else if upper.starts_with("INSERT INTO ") {
            return self.execute_insert(trimmed);
        } else if upper.starts_with("UPDATE ") {
            return self.execute_update(trimmed);
        } else if upper.starts_with("DELETE FROM ") {
            return self.execute_delete(trimmed);
        } else if upper.starts_with("AGGREGATE ") {
            return self.execute_direct_aggregate(trimmed);
        } else if upper.starts_with("MATH ") || upper.starts_with("CALC ") {
            return self.execute_direct_math(trimmed);
        } else if upper.starts_with("FINANCE ") || upper.starts_with("FINANCIAL ") {
            return self.execute_direct_finance(trimmed);
        } else if upper.starts_with("STATS ") || upper.starts_with("STATISTICS ") {
            return self.execute_direct_stats(trimmed);
        } else if upper.starts_with("VECTOR ") && !upper.starts_with("VECTOR SIMILARITY") && !upper.starts_with("VECTOR MATCH") {
            return self.execute_direct_vector(trimmed);
        } else if upper.starts_with("INSTALL SAMPLE") || upper.starts_with("LOAD SAMPLE") {
            let total = self.database.install_sample_dataset(&upper, 100);
            return QueryResult {
                columns: vec!["status".to_string(), "database".to_string(), "objects".to_string()],
                rows: vec![vec![
                    "SUCCESS".to_string(),
                    self.database.database_name().to_string(),
                    total.to_string(),
                ]],
                affected_rows: 1,
                message: format!("Muestra multimodelo instalada ({} objetos)", total),
            };
        }

        // Delegar a JettraQL si es comando JQL
        if upper.starts_with("FROM ")
            || upper.starts_with("MATCH ")
            || upper.starts_with("VECTOR SIMILARITY")
            || upper.starts_with("VECTOR MATCH")
            || upper.starts_with("FIND ")
            || upper.starts_with("FETCH ")
        {
            return JettraQLProcessor::new(self.database).execute(trimmed);
        }

        QueryResult {
            columns: vec!["result".to_string()],
            rows: vec![vec![format!("Executed: {}", trimmed)]],
            affected_rows: 1,
            message: "Command processed".to_string(),
        }
    }

    fn has_aggregation(upper: &str) -> bool {
        [
            "SUM(", "AVG(", "COUNT(", "MIN(", "MAX(", "STDDEV(", "VARIANCE(", "MEDIAN(", "MODE(",
            "P50(", "P90(", "P95(", "P99(", "IQR(",
        ]
        .iter()
        .any(|p| upper.contains(p))
    }

    fn execute_scalar(&self, expr_raw: &str) -> QueryResult {
        let mut expr = expr_raw.trim();
        let mut alias = "result".to_string();
        if let Some(idx) = expr.to_uppercase().rfind(" AS ") {
            alias = Self::sanitize(&expr[idx + 4..]);
            expr = expr[..idx].trim();
        }
        match JettraMath::eval(expr) {
            Ok(val) => QueryResult {
                columns: vec![alias],
                rows: vec![vec![format!("{}", val)]],
                affected_rows: 1,
                message: "Scalar expression evaluated".to_string(),
            },
            Err(err) => QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec![err]],
                affected_rows: 0,
                message: "Evaluation error".to_string(),
            },
        }
    }

    fn execute_select(&self, sql: &str) -> QueryResult {
        let upper = sql.to_uppercase();
        let Some(from_idx) = upper.find(" FROM ") else {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Missing FROM clause".to_string()]],
                affected_rows: 0,
                message: "Syntax error".to_string(),
            };
        };

        let select_fields = sql[7..from_idx].trim();
        let mut after_from = sql[from_idx + 6..].trim().to_string();

        let mut limit = self.database.config().query_default_limit;
        let mut explicit_limit = false;
        if let Some(lim_idx) = after_from.to_uppercase().rfind(" LIMIT ") {
            if let Ok(parsed) = Self::sanitize(&after_from[lim_idx + 7..]).parse::<usize>() {
                limit = parsed;
                explicit_limit = true;
            }
            after_from = after_from[..lim_idx].trim().to_string();
        }

        let mut where_filter: Option<(String, String)> = None;
        let collection = if let Some(w_idx) = after_from.to_uppercase().find(" WHERE ") {
            let col = Self::sanitize(&after_from[..w_idx]);
            let clause = &after_from[w_idx + 7..];
            if let Some((k, v)) = clause.split_once('=') {
                where_filter = Some((Self::sanitize(k), Self::sanitize(v)));
            }
            col
        } else {
            Self::sanitize(after_from.split_whitespace().next().unwrap_or(""))
        };

        let engine = self.database.document_engine(&collection);
        let decision = self.database.police().evaluate_memory_safety(
            "SQL_SELECT",
            &collection,
            engine.count(),
            if explicit_limit { limit } else { 0 },
            512,
        );
        if decision.intervention_required {
            limit = decision.enforced_limit;
        }

        // Si hay índice secundario disponible para el filtro WHERE, usarlo en O(1) / O(log N)
        let docs = if let Some((ref fk, ref fv)) = where_filter {
            if let Some(ids) = self
                .database
                .index_manager()
                .find_document_ids(&collection, fk, fv)
            {
                ids.iter().filter_map(|id| engine.find_by_id(id)).collect()
            } else {
                engine
                    .find_all()
                    .into_iter()
                    .filter(|d| {
                        d.get(fk)
                            .map(|v| v.to_string_value().eq_ignore_ascii_case(fv))
                            .unwrap_or(false)
                    })
                    .collect()
            }
        } else {
            engine.find_all()
        };

        let projected_cols: Vec<String> = if select_fields == "*" {
            let mut cols = vec!["_id".to_string()];
            if let Some(first) = docs.first() {
                for k in first.keys() {
                    if k != "_id" {
                        cols.push(k.clone());
                    }
                }
            }
            cols
        } else {
            select_fields.split(',').map(Self::sanitize).collect()
        };

        let mut rows = Vec::new();
        for doc in docs.into_iter().take(limit) {
            let row: Vec<String> = projected_cols
                .iter()
                .map(|c| {
                    doc.get(c)
                        .map(|v| v.to_string_value())
                        .unwrap_or_else(|| "null".to_string())
                })
                .collect();
            rows.push(row);
        }

        let count = rows.len();
        QueryResult {
            columns: projected_cols,
            rows,
            affected_rows: count,
            message: format!("SELECT sobre '{}' retornó {} fila(s)", collection, count),
        }
    }

    fn execute_aggregation(&self, sql: &str) -> QueryResult {
        let upper = sql.to_uppercase();
        let Some(from_idx) = upper.find(" FROM ") else {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: Vec::new(),
                affected_rows: 0,
                message: "Missing FROM".to_string(),
            };
        };
        let select_part = sql[7..from_idx].trim();
        let mut after_from = sql[from_idx + 6..].trim().to_string();

        if let Some(lim_idx) = after_from.to_uppercase().rfind(" LIMIT ") {
            after_from = after_from[..lim_idx].trim().to_string();
        }

        let mut group_by_cols = Vec::new();
        if let Some(g_idx) = after_from.to_uppercase().find(" GROUP BY ") {
            let g_clause = &after_from[g_idx + 10..];
            for col in g_clause.split(',') {
                let c = Self::sanitize(col);
                if !c.is_empty() {
                    group_by_cols.push(c);
                }
            }
            after_from = after_from[..g_idx].trim().to_string();
        }

        let mut where_map = HashMap::new();
        let collection = if let Some(w_idx) = after_from.to_uppercase().find(" WHERE ") {
            let col = Self::sanitize(&after_from[..w_idx]);
            if let Some((k, v)) = after_from[w_idx + 7..].split_once('=') {
                where_map.insert(Self::sanitize(k), Self::sanitize(v));
            }
            col
        } else {
            Self::sanitize(&after_from)
        };

        let mut specs = Vec::new();
        for raw_field in select_part.split(',') {
            let mut p = raw_field.trim();
            let mut alias = None;
            if let Some(as_idx) = p.to_uppercase().rfind(" AS ") {
                alias = Some(Self::sanitize(&p[as_idx + 4..]));
                p = p[..as_idx].trim();
            }
            if let (Some(open), Some(close)) = (p.find('('), p.rfind(')')) {
                let func = p[..open].trim().to_uppercase();
                let field = Self::sanitize(&p[open + 1..close]);
                specs.push(AggregateSpec::new(func, field, alias));
            } else {
                let c = Self::sanitize(p);
                if !c.is_empty() && !group_by_cols.contains(&c) {
                    group_by_cols.push(c);
                }
            }
        }

        let engine = self.database.document_engine(&collection);
        let filter_ref = if where_map.is_empty() {
            None
        } else {
            Some(&where_map)
        };
        let agg = JettraAggregation::aggregate(&engine, &group_by_cols, &specs, filter_ref);

        let rows: Vec<Vec<String>> = agg
            .rows
            .iter()
            .map(|r| {
                agg.column_headers
                    .iter()
                    .map(|h| {
                        r.get(h)
                            .map(|v| v.to_string_value())
                            .unwrap_or_else(|| "null".to_string())
                    })
                    .collect()
            })
            .collect();

        let count = rows.len();
        QueryResult {
            columns: agg.column_headers,
            rows,
            affected_rows: count,
            message: format!("Agregación ejecutada sobre '{}': {} grupo(s)", collection, count),
        }
    }

    fn execute_insert(&self, sql: &str) -> QueryResult {
        // Soporta: INSERT INTO <col> (<k1>, <k2>) VALUES (<v1>, <v2>) o INSERT INTO <col> {json}
        let rest = sql[12..].trim();
        if let Some(brace_idx) = rest.find('{') {
            let col = Self::sanitize(&rest[..brace_idx]);
            let json_str = &rest[brace_idx..];
            if let Ok(JsonValue::Object(mut map)) = JsonValue::parse(json_str) {
                let id = map
                    .get("_id")
                    .map(|v| v.to_string_value())
                    .unwrap_or_else(|| format!("DOC-{}", self.database.document_engine(&col).count() + 1));
                map.insert("_id".to_string(), JsonValue::Str(id.clone()));
                if let Err(e) = self.database.save_document(&col, &id, map) {
                    return QueryResult {
                        columns: vec!["error".to_string()],
                        rows: vec![vec![e]],
                        affected_rows: 0,
                        message: "Insert error".to_string(),
                    };
                }
                return QueryResult {
                    columns: vec!["status".to_string(), "_id".to_string()],
                    rows: vec![vec!["INSERTED".to_string(), id]],
                    affected_rows: 1,
                    message: "1 row inserted".to_string(),
                };
            }
        }

        let upper = rest.to_uppercase();
        if let Some(val_idx) = upper.find("VALUES") {
            let before_val = rest[..val_idx].trim();
            let after_val = rest[val_idx + 6..].trim();
            if let (Some(c_open), Some(c_close), Some(v_open), Some(v_close)) = (
                before_val.find('('),
                before_val.rfind(')'),
                after_val.find('('),
                after_val.rfind(')'),
            ) {
                let col = Self::sanitize(&before_val[..c_open]);
                let keys: Vec<String> = before_val[c_open + 1..c_close]
                    .split(',')
                    .map(Self::sanitize)
                    .collect();
                let vals: Vec<String> = after_val[v_open + 1..v_close]
                    .split(',')
                    .map(Self::sanitize)
                    .collect();

                let mut doc = BTreeMap::new();
                for (k, v) in keys.iter().zip(vals.iter()) {
                    if let Ok(n) = v.parse::<f64>() {
                        doc.insert(k.clone(), JsonValue::Number(n));
                    } else {
                        doc.insert(k.clone(), JsonValue::Str(v.clone()));
                    }
                }
                let id = doc
                    .get("_id")
                    .or_else(|| doc.get("id"))
                    .map(|v| v.to_string_value())
                    .unwrap_or_else(|| format!("DOC-{}", self.database.document_engine(&col).count() + 1));
                let _ = self.database.save_document(&col, &id, doc);
                return QueryResult {
                    columns: vec!["status".to_string(), "_id".to_string()],
                    rows: vec![vec!["INSERTED".to_string(), id]],
                    affected_rows: 1,
                    message: "1 row inserted".to_string(),
                };
            }
        }

        QueryResult {
            columns: vec!["error".to_string()],
            rows: vec![vec!["Invalid INSERT syntax".to_string()]],
            affected_rows: 0,
            message: "Error".to_string(),
        }
    }

    fn execute_update(&self, sql: &str) -> QueryResult {
        // UPDATE <col> SET <k>=<v> WHERE _id=<id>
        let rest = sql[7..].trim();
        let upper = rest.to_uppercase();
        let Some(set_idx) = upper.find(" SET ") else {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Missing SET clause".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        };
        let col = Self::sanitize(&rest[..set_idx]);
        let after_set = &rest[set_idx + 5..];
        let (set_part, where_part) = if let Some(w_idx) = after_set.to_uppercase().find(" WHERE ") {
            (&after_set[..w_idx], Some(&after_set[w_idx + 7..]))
        } else {
            (after_set, None)
        };

        let Some((sk, sv)) = set_part.split_once('=') else {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Invalid SET clause".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        };
        let set_key = Self::sanitize(sk);
        let set_val_str = Self::sanitize(sv);
        let new_val = if let Ok(n) = set_val_str.parse::<f64>() {
            JsonValue::Number(n)
        } else {
            JsonValue::Str(set_val_str)
        };

        let engine = self.database.document_engine(&col);
        let mut updated = 0;
        for mut doc in engine.find_all() {
            let matches = if let Some(wp) = where_part {
                if let Some((wk, wv)) = wp.split_once('=') {
                    doc.get(&Self::sanitize(wk))
                        .map(|v| v.to_string_value().eq_ignore_ascii_case(&Self::sanitize(wv)))
                        .unwrap_or(false)
                } else {
                    false
                }
            } else {
                true
            };
            if matches {
                if let Some(id) = doc.get("_id").map(|v| v.to_string_value()) {
                    doc.insert(set_key.clone(), new_val.clone());
                    let _ = self.database.save_document(&col, &id, doc);
                    updated += 1;
                }
            }
        }

        QueryResult {
            columns: vec!["updated".to_string()],
            rows: vec![vec![updated.to_string()]],
            affected_rows: updated,
            message: format!("{} documento(s) actualizado(s)", updated),
        }
    }

    fn execute_delete(&self, sql: &str) -> QueryResult {
        // DELETE FROM <col> WHERE _id=<id>
        let rest = sql[12..].trim();
        let (col, where_part) = if let Some(w_idx) = rest.to_uppercase().find(" WHERE ") {
            (Self::sanitize(&rest[..w_idx]), Some(&rest[w_idx + 7..]))
        } else {
            (Self::sanitize(rest), None)
        };

        let engine = self.database.document_engine(&col);
        let mut deleted = 0;
        for doc in engine.find_all() {
            let matches = if let Some(wp) = where_part {
                if let Some((wk, wv)) = wp.split_once('=') {
                    doc.get(&Self::sanitize(wk))
                        .map(|v| v.to_string_value().eq_ignore_ascii_case(&Self::sanitize(wv)))
                        .unwrap_or(false)
                } else {
                    false
                }
            } else {
                true
            };
            if matches {
                if let Some(id) = doc.get("_id").map(|v| v.to_string_value()) {
                    self.database.delete_document(&col, &id);
                    deleted += 1;
                }
            }
        }

        QueryResult {
            columns: vec!["deleted".to_string()],
            rows: vec![vec![deleted.to_string()]],
            affected_rows: deleted,
            message: format!("{} documento(s) eliminado(s)", deleted),
        }
    }

    fn execute_direct_aggregate(&self, cmd: &str) -> QueryResult {
        let tokens: Vec<&str> = cmd[10..].split_whitespace().collect();
        if tokens.is_empty() {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Usage: AGGREGATE <col> [GROUP BY <f>] [SUM <f>] [AVG <f>] [COUNT]".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        }
        let col = Self::sanitize(tokens[0]);
        let mut group_by = Vec::new();
        let mut specs = Vec::new();
        let mut i = 1;
        while i < tokens.len() {
            let t = tokens[i].to_uppercase();
            if t == "GROUP" && i + 2 < tokens.len() && tokens[i + 1].eq_ignore_ascii_case("BY") {
                group_by.push(Self::sanitize(tokens[i + 2]));
                i += 3;
            } else if t == "COUNT" {
                specs.push(AggregateSpec::new("COUNT", "*", None));
                i += 1;
            } else if i + 1 < tokens.len() {
                specs.push(AggregateSpec::new(t, Self::sanitize(tokens[i + 1]), None));
                i += 2;
            } else {
                i += 1;
            }
        }
        if specs.is_empty() {
            specs.push(AggregateSpec::new("COUNT", "*", Some("total".to_string())));
        }
        let engine = self.database.document_engine(&col);
        let agg = JettraAggregation::aggregate(&engine, &group_by, &specs, None);
        let rows: Vec<Vec<String>> = agg
            .rows
            .iter()
            .map(|r| {
                agg.column_headers
                    .iter()
                    .map(|h| r.get(h).map(|v| v.to_string_value()).unwrap_or_default())
                    .collect()
            })
            .collect();
        let c = rows.len();
        QueryResult {
            columns: agg.column_headers,
            rows,
            affected_rows: c,
            message: "Direct aggregation computed".to_string(),
        }
    }

    fn execute_direct_math(&self, cmd: &str) -> QueryResult {
        let expr = cmd
            .split_once(char::is_whitespace)
            .map(|(_, r)| r.trim())
            .unwrap_or("");
        match JettraMath::eval(expr) {
            Ok(v) => QueryResult {
                columns: vec!["expression".to_string(), "result".to_string()],
                rows: vec![vec![expr.to_string(), v.to_string()]],
                affected_rows: 1,
                message: "Math expression evaluated".to_string(),
            },
            Err(e) => QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec![e]],
                affected_rows: 0,
                message: "Math error".to_string(),
            },
        }
    }

    fn execute_direct_finance(&self, cmd: &str) -> QueryResult {
        let parts: Vec<&str> = cmd.split_whitespace().skip(1).collect();
        if parts.is_empty() {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Missing finance operation".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        }
        let op = parts[0].to_uppercase();
        let nums: Vec<f64> = parts[1..]
            .iter()
            .filter_map(|s| s.parse::<f64>().ok())
            .collect();

        match op.as_str() {
            "PMT" if nums.len() >= 3 => {
                let p = JettraFinance::pmt(nums[0], nums[1] as usize, nums[2]);
                QueryResult {
                    columns: vec!["rate".to_string(), "nper".to_string(), "pv".to_string(), "pmt".to_string()],
                    rows: vec![vec![
                        nums[0].to_string(),
                        nums[1].to_string(),
                        nums[2].to_string(),
                        JettraMath::round(p, 2).to_string(),
                    ]],
                    affected_rows: 1,
                    message: "PMT computed".to_string(),
                }
            }
            "ROI" if nums.len() >= 2 => {
                let r = JettraFinance::roi(nums[0], nums[1]);
                QueryResult {
                    columns: vec!["gain".to_string(), "cost".to_string(), "roi_pct".to_string()],
                    rows: vec![vec![
                        nums[0].to_string(),
                        nums[1].to_string(),
                        format!("{}%", JettraMath::round(r, 2)),
                    ]],
                    affected_rows: 1,
                    message: "ROI computed".to_string(),
                }
            }
            "NPV" if nums.len() >= 2 => {
                let v = JettraFinance::npv(nums[0], &nums[1..]);
                QueryResult {
                    columns: vec!["rate".to_string(), "npv".to_string()],
                    rows: vec![vec![nums[0].to_string(), JettraMath::round(v, 2).to_string()]],
                    affected_rows: 1,
                    message: "NPV computed".to_string(),
                }
            }
            "IRR" if !nums.is_empty() => {
                let v = JettraFinance::irr(&nums);
                QueryResult {
                    columns: vec!["irr_pct".to_string()],
                    rows: vec![vec![format!("{}%", JettraMath::round(v * 100.0, 2))]],
                    affected_rows: 1,
                    message: "IRR computed".to_string(),
                }
            }
            _ => QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec![format!("Unsupported or incomplete FINANCE command: {}", op)]],
                affected_rows: 0,
                message: "Finance error".to_string(),
            },
        }
    }

    fn execute_direct_stats(&self, cmd: &str) -> QueryResult {
        let parts: Vec<&str> = cmd.split_whitespace().skip(1).collect();
        if parts.is_empty() {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Missing stats operation".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        }
        let op = parts[0].to_uppercase();
        let nums: Vec<f64> = parts[1..]
            .iter()
            .flat_map(|s| s.split(','))
            .filter_map(|s| s.trim_matches(|c| c == '[' || c == ']').parse::<f64>().ok())
            .collect();

        match op.as_str() {
            "MEAN" | "AVG" => QueryResult {
                columns: vec!["mean".to_string(), "samples".to_string()],
                rows: vec![vec![
                    JettraMath::round(JettraStatistics::mean(&nums), 4).to_string(),
                    nums.len().to_string(),
                ]],
                affected_rows: 1,
                message: "Mean computed".to_string(),
            },
            "MEDIAN" => QueryResult {
                columns: vec!["median".to_string(), "samples".to_string()],
                rows: vec![vec![
                    JettraMath::round(JettraStatistics::median(&nums), 4).to_string(),
                    nums.len().to_string(),
                ]],
                affected_rows: 1,
                message: "Median computed".to_string(),
            },
            "STDDEV" | "STD" => QueryResult {
                columns: vec!["stddev".to_string(), "samples".to_string()],
                rows: vec![vec![
                    JettraMath::round(JettraStatistics::stddev(&nums, true), 4).to_string(),
                    nums.len().to_string(),
                ]],
                affected_rows: 1,
                message: "StdDev computed".to_string(),
            },
            "SUMMARY" => {
                let s = JettraStatistics::summary(&nums);
                QueryResult {
                    columns: vec![
                        "count".to_string(),
                        "sum".to_string(),
                        "mean".to_string(),
                        "median".to_string(),
                        "stddev".to_string(),
                        "min".to_string(),
                        "max".to_string(),
                        "p95".to_string(),
                    ],
                    rows: vec![vec![
                        s.count.to_string(),
                        JettraMath::round(s.sum, 2).to_string(),
                        JettraMath::round(s.mean, 2).to_string(),
                        JettraMath::round(s.median, 2).to_string(),
                        JettraMath::round(s.stddev, 2).to_string(),
                        s.min.to_string(),
                        s.max.to_string(),
                        JettraMath::round(s.p95, 2).to_string(),
                    ]],
                    affected_rows: 1,
                    message: "Summary computed".to_string(),
                }
            }
            _ => QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec![format!("Unknown stats operation: {}", op)]],
                affected_rows: 0,
                message: "Error".to_string(),
            },
        }
    }

    fn execute_direct_vector(&self, cmd: &str) -> QueryResult {
        let rest = cmd.split_once(char::is_whitespace).map(|(_, r)| r.trim()).unwrap_or("");
        let op = rest.split_whitespace().next().unwrap_or("").to_uppercase();
        let vecs = Self::extract_vectors(rest);

        match op.as_str() {
            "DOT" | "DOT_PRODUCT" if vecs.len() >= 2 => {
                let v = JettraVectorMath::dot_product(&vecs[0], &vecs[1]).unwrap_or(0.0);
                QueryResult {
                    columns: vec!["dot_product".to_string()],
                    rows: vec![vec![format!("{:.4}", v)]],
                    affected_rows: 1,
                    message: "Dot product computed".to_string(),
                }
            }
            "COSINE" | "COSINE_SIMILARITY" if vecs.len() >= 2 => {
                let v = JettraVectorMath::cosine_similarity(&vecs[0], &vecs[1]).unwrap_or(0.0);
                QueryResult {
                    columns: vec!["cosine_similarity".to_string()],
                    rows: vec![vec![format!("{:.4}", v)]],
                    affected_rows: 1,
                    message: "Cosine similarity computed".to_string(),
                }
            }
            "EUCLIDEAN" | "DISTANCE" if vecs.len() >= 2 => {
                let v = JettraVectorMath::euclidean_distance(&vecs[0], &vecs[1]).unwrap_or(0.0);
                QueryResult {
                    columns: vec!["euclidean_distance".to_string()],
                    rows: vec![vec![format!("{:.4}", v)]],
                    affected_rows: 1,
                    message: "Euclidean distance computed".to_string(),
                }
            }
            "CENTROID" if !vecs.is_empty() => {
                let c = JettraVectorMath::centroid(&vecs).unwrap_or_default();
                QueryResult {
                    columns: vec!["centroid".to_string()],
                    rows: vec![vec![format!("{:?}", c)]],
                    affected_rows: 1,
                    message: "Centroid computed".to_string(),
                }
            }
            _ => QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec![format!("Invalid VECTOR command: {}", op)]],
                affected_rows: 0,
                message: "Error".to_string(),
            },
        }
    }

    pub fn extract_vectors(s: &str) -> Vec<Vec<f32>> {
        let mut res = Vec::new();
        let mut rest = s;
        while let Some(open) = rest.find('[') {
            if let Some(close) = rest[open + 1..].find(']') {
                let content = &rest[open + 1..open + 1 + close];
                let v: Vec<f32> = content
                    .split(|c: char| c == ',' || c.is_whitespace())
                    .filter_map(|item| item.trim().parse::<f32>().ok())
                    .collect();
                if !v.is_empty() {
                    res.push(v);
                }
                rest = &rest[open + 1 + close + 1..];
            } else {
                break;
            }
        }
        res
    }
}

// ============================================================================
// JettraQLProcessor: Procesador Multimodelo JQL
// ============================================================================

pub struct JettraQLProcessor<'a> {
    database: &'a JettraDatabase,
}

impl<'a> JettraQLProcessor<'a> {
    pub fn new(database: &'a JettraDatabase) -> Self {
        Self { database }
    }

    pub fn execute(&self, jql: &str) -> QueryResult {
        let trimmed = jql.trim().trim_end_matches(';').trim();
        let upper = trimmed.to_uppercase();

        if upper.starts_with("FROM ") {
            return JettraSQLProcessor::new(self.database).execute(&format!("SELECT * {}", trimmed));
        } else if upper.starts_with("MATCH ") {
            return self.execute_graph_match(trimmed);
        } else if upper.starts_with("VECTOR SIMILARITY ") || upper.starts_with("VECTOR MATCH ") {
            return self.execute_vector_match(trimmed);
        } else if upper.starts_with("FIND ") {
            let parts: Vec<&str> = trimmed[5..].split_whitespace().collect();
            if parts.len() >= 2 && !parts[1].eq_ignore_ascii_case("WHERE") {
                let col = parts[0];
                let id = parts[1].trim_matches(|c| c == '\'' || c == '"' || c == ';');
                if let Some(doc) = self.database.document_engine(col).find_by_id(id) {
                    return QueryResult {
                        columns: vec!["_id".to_string(), "document".to_string()],
                        rows: vec![vec![id.to_string(), JsonValue::Object(doc).to_json()]],
                        affected_rows: 1,
                        message: "1 document found".to_string(),
                    };
                }
                return QueryResult {
                    columns: vec!["_id".to_string(), "document".to_string()],
                    rows: Vec::new(),
                    affected_rows: 0,
                    message: "Document not found".to_string(),
                };
            }
            return JettraSQLProcessor::new(self.database)
                .execute(&format!("SELECT * FROM {}", &trimmed[5..]));
        } else if upper.starts_with("FETCH ") {
            let parts: Vec<&str> = trimmed[6..].split_whitespace().collect();
            if parts.len() >= 2 {
                let col = parts[0];
                let id = parts[1].trim_matches(|c| c == '\'' || c == '"' || c == ';');
                let resolve = upper.contains("RESOLVE");
                if let Some(doc) = self.database.document_engine(col).find_by_id(id) {
                    let mut rows = Vec::new();
                    for (k, v) in doc {
                        let mut val_str = v.to_string_value();
                        if resolve && k.starts_with("_ref_") {
                            val_str.push_str(" [JettraRef Resuelto Eagerly]");
                        }
                        rows.push(vec![k, val_str]);
                    }
                    let count = rows.len();
                    return QueryResult {
                        columns: vec!["field".to_string(), "value".to_string()],
                        rows,
                        affected_rows: count,
                        message: format!("JettraQL FETCH '{}' ({} campos)", id, count),
                    };
                }
            }
        }

        QueryResult {
            columns: vec!["jql".to_string(), "status".to_string()],
            rows: vec![vec![trimmed.to_string(), "OK".to_string()]],
            affected_rows: 1,
            message: "JettraQL executed".to_string(),
        }
    }

    fn execute_graph_match(&self, jql: &str) -> QueryResult {
        // MATCH (<source>) IN <graph_name>
        let mut clean = jql[6..].trim().to_string();
        let mut graph_name = "red_comercial".to_string();
        if let Some(in_idx) = clean.to_uppercase().find(" IN ") {
            graph_name = clean[in_idx + 4..].trim().to_string();
            clean = clean[..in_idx].trim().to_string();
        }
        let source_vertex = clean
            .split("->")
            .next()
            .unwrap_or("")
            .split("-[")
            .next()
            .unwrap_or("")
            .trim_matches(|c: char| c == '(' || c == ')' || c.is_whitespace())
            .to_string();

        let graph = self.database.graph_engine(&graph_name);
        let edges = graph.get_outbound_edges(&source_vertex);
        let rows: Vec<Vec<String>> = edges
            .iter()
            .map(|e| {
                vec![
                    source_vertex.clone(),
                    e.label.clone(),
                    e.target_vertex.clone(),
                    JsonValue::Object(e.properties.clone()).to_json(),
                ]
            })
            .collect();
        let c = rows.len();
        QueryResult {
            columns: vec![
                "source".to_string(),
                "relation".to_string(),
                "target".to_string(),
                "properties".to_string(),
            ],
            rows,
            affected_rows: c,
            message: format!(
                "JettraQL MATCH en grafo '{}' encontró {} arista(s)",
                graph_name, c
            ),
        }
    }

    fn execute_vector_match(&self, jql: &str) -> QueryResult {
        // VECTOR SIMILARITY <col> TO [f1, f2, f3] LIMIT <k>
        let tokens: Vec<&str> = jql.split_whitespace().collect();
        if tokens.len() < 3 {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Invalid VECTOR SIMILARITY syntax".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        }
        let col = tokens[2];
        let vecs = JettraSQLProcessor::extract_vectors(jql);
        let Some(query_vec) = vecs.first() else {
            return QueryResult {
                columns: vec!["error".to_string()],
                rows: vec![vec!["Missing target vector [...]".to_string()]],
                affected_rows: 0,
                message: "Error".to_string(),
            };
        };
        let mut limit = 5;
        let upper = jql.to_uppercase();
        if let Some(lim_idx) = upper.find("LIMIT") {
            if let Some(tok) = jql[lim_idx + 5..].split_whitespace().next() {
                if let Ok(k) = tok.parse::<usize>() {
                    limit = k;
                }
            }
        }

        let v_engine = self.database.vector_engine(col, query_vec.len());
        let matches = v_engine.search_cosine(query_vec, limit);
        let rows: Vec<Vec<String>> = matches
            .iter()
            .map(|m| vec![m.id.clone(), format!("{:.4}", m.score)])
            .collect();
        let c = rows.len();
        QueryResult {
            columns: vec!["vector_id".to_string(), "similarity_score".to_string()],
            rows,
            affected_rows: c,
            message: format!("JettraQL VECTOR SIMILARITY en '{}' Top-{}", col, c),
        }
    }
}

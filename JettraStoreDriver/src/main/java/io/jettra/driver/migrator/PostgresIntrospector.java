package io.jettra.driver.migrator;

import java.sql.*;
import java.util.*;

/**
 * Servicio de introspección y análisis de esquemas, tablas, columnas e índices de PostgreSQL.
 */
public class PostgresIntrospector {

    public List<String> listDatabases(PostgresConnectionConfig config) throws SQLException {
        List<String> databases = new ArrayList<>();
        String targetDb = (config.getDatabase() != null && !config.getDatabase().isBlank()) ? config.getDatabase() : "postgres";
        String url = config.toJdbcUrl(targetDb);
        Properties props = buildConnectionProperties(config);

        try (Connection conn = DriverManager.getConnection(url, props)) {
            // 1. Consultar pg_database (más completo en PostgreSQL)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT datname FROM pg_database WHERE datistemplate = false AND datallowconn = true ORDER BY datname")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank() && !databases.contains(name)) {
                        databases.add(name);
                    }
                }
            } catch (Exception ex) {
                // Fallback a DatabaseMetaData.getCatalogs()
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_CAT");
                        if (name != null && !name.isBlank() && !databases.contains(name)) {
                            databases.add(name);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (databases.isEmpty() && config.getDatabase() != null && !config.getDatabase().isBlank()) {
            databases.add(config.getDatabase());
        }
        return databases;
    }

    public List<String> listSchemas(PostgresConnectionConfig config) throws SQLException {
        List<String> schemas = new ArrayList<>();
        String targetDb = (config.getDatabase() != null && !config.getDatabase().isBlank()) ? config.getDatabase() : "postgres";
        String url = config.toJdbcUrl(targetDb);
        Properties props = buildConnectionProperties(config);

        try (Connection conn = DriverManager.getConnection(url, props)) {
            // 1. Consultar information_schema.schemata
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT schema_name FROM information_schema.schemata " +
                         "WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast') " +
                         "  AND schema_name NOT LIKE 'pg_temp_%' AND schema_name NOT LIKE 'pg_toast_%' " +
                         "ORDER BY schema_name")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank() && !schemas.contains(name)) {
                        schemas.add(name);
                    }
                }
            } catch (Exception ex) {
                // Fallback a DatabaseMetaData.getSchemas()
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_SCHEM");
                        if (name != null && !name.startsWith("pg_") && !name.equals("information_schema") && !schemas.contains(name)) {
                            schemas.add(name);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!schemas.contains("public")) {
            schemas.add(0, "public");
        }
        return schemas;
    }

    private Properties buildConnectionProperties(PostgresConnectionConfig config) {
        Properties props = new Properties();
        if (config.getUser() != null) props.setProperty("user", config.getUser());
        if (config.getPassword() != null) props.setProperty("password", config.getPassword());
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        return props;
    }

    public PostgresIntrospectionResult introspect(PostgresConnectionConfig config) {
        PostgresIntrospectionResult result = new PostgresIntrospectionResult();
        result.setHost(config.getHost());
        result.setPort(config.getPort());
        result.setDatabase(config.getDatabase());
        result.setSchema(config.getSchema());

        long start = System.currentTimeMillis();
        String url = config.toJdbcUrl();

        Properties props = buildConnectionProperties(config);

        try {
            try {
                result.setAvailableDatabases(listDatabases(config));
            } catch (Exception ignored) {}
            try {
                result.setAvailableSchemas(listSchemas(config));
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}

        try (Connection conn = DriverManager.getConnection(url, props)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schema = config.getSchema();

            // 1. Obtener tablas del esquema
            List<String> tableNames = new ArrayList<>();
            try (ResultSet rsTables = meta.getTables(config.getDatabase(), schema, "%", new String[]{"TABLE", "PARTITIONED TABLE"})) {
                while (rsTables.next()) {
                    String tName = rsTables.getString("TABLE_NAME");
                    if (tName != null && !tName.isBlank()) {
                        tableNames.add(tName);
                    }
                }
            }

            // Fallback si meta.getTables no filtró por catálogo/esquema
            if (tableNames.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                    ps.setString(1, schema);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tableNames.add(rs.getString("table_name"));
                        }
                    }
                }
            }

            long totalDatabaseRows = 0;

            for (String tableName : tableNames) {
                PostgresTableMeta tableMeta = new PostgresTableMeta(tableName);

                // 2. Primary Keys
                Set<String> pkColumns = new HashSet<>();
                try (ResultSet rsPk = meta.getPrimaryKeys(config.getDatabase(), schema, tableName)) {
                    while (rsPk.next()) {
                        String col = rsPk.getString("COLUMN_NAME");
                        if (col != null) pkColumns.add(col.toLowerCase());
                    }
                } catch (Exception ignored) {}

                // 3. Columnas
                Map<String, PostgresColumnMeta> colsMap = new LinkedHashMap<>();
                try (ResultSet rsCols = meta.getColumns(config.getDatabase(), schema, tableName, "%")) {
                    while (rsCols.next()) {
                        String colName = rsCols.getString("COLUMN_NAME");
                        String typeName = rsCols.getString("TYPE_NAME");
                        int colSize = rsCols.getInt("COLUMN_SIZE");
                        int decDigits = rsCols.getInt("DECIMAL_DIGITS");
                        int nullable = rsCols.getInt("NULLABLE");
                        String colDef = rsCols.getString("COLUMN_DEF");
                        String isAuto = "";
                        try {
                            isAuto = rsCols.getString("IS_AUTOINCREMENT");
                        } catch (Exception ignored) {}

                        boolean isPk = pkColumns.contains(colName.toLowerCase());
                        boolean autoInc = "YES".equalsIgnoreCase(isAuto)
                                || (colDef != null && (colDef.toLowerCase().contains("nextval") || colDef.toLowerCase().contains("identity")));

                        PostgresColumnMeta colMeta = new PostgresColumnMeta(
                                colName, typeName, colSize, decDigits,
                                nullable == DatabaseMetaData.columnNullable,
                                isPk, autoInc, colDef
                        );
                        colsMap.put(colName.toLowerCase(), colMeta);
                    }
                }

                tableMeta.setColumns(new ArrayList<>(colsMap.values()));

                // 4. Índices
                Map<String, PostgresIndexMeta> idxMap = new LinkedHashMap<>();
                try (ResultSet rsIdx = meta.getIndexInfo(config.getDatabase(), schema, tableName, false, true)) {
                    while (rsIdx.next()) {
                        String idxName = rsIdx.getString("INDEX_NAME");
                        String colName = rsIdx.getString("COLUMN_NAME");
                        boolean nonUnique = rsIdx.getBoolean("NON_UNIQUE");
                        if (idxName == null || colName == null) continue;

                        // Determinar tipo de índice en JettraStore
                        String indexType = "BTREE";
                        PostgresColumnMeta colMeta = colsMap.get(colName.toLowerCase());
                        if (colMeta != null) {
                            if (colMeta.isAutoIncrement()) {
                                indexType = "AUTO_INCREMENT";
                            } else if (colMeta.getJavaType().equals("java.math.BigDecimal")
                                    || colMeta.getJavaType().equals("Integer")
                                    || colMeta.getJavaType().equals("Long")
                                    || colMeta.getJavaType().equals("Double")) {
                                indexType = "NUMERIC";
                            }
                        }

                        PostgresIndexMeta idxMeta = new PostgresIndexMeta(idxName, tableName, colName, !nonUnique, indexType);
                        idxMap.put(idxName + "_" + colName, idxMeta);
                    }
                } catch (Exception ignored) {}

                tableMeta.setIndexes(new ArrayList<>(idxMap.values()));

                // 5. Conteo aproximado o exacto de registros
                long count = 0;
                try (Statement stCount = conn.createStatement();
                     ResultSet rsCount = stCount.executeQuery("SELECT count(*) FROM \"" + schema + "\".\"" + tableName + "\"")) {
                    if (rsCount.next()) {
                        count = rsCount.getLong(1);
                    }
                } catch (Exception ignored) {}

                tableMeta.setRowCount(count);
                totalDatabaseRows += count;
                result.getTables().add(tableMeta);
            }

            result.setTotalRows(totalDatabaseRows);
            result.setSuccess(true);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage("Error conectando a PostgreSQL (" + url + "): " + e.getMessage());
        } finally {
            result.setAnalysisTimeMs(System.currentTimeMillis() - start);
        }

        return result;
    }
}

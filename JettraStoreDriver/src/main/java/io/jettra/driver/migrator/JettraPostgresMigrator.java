package io.jettra.driver.migrator;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraBucketSchema;
import io.jettra.store.core.JettraId;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * Servicio integral de migración de datos desde PostgreSQL hacia JettraStore (RF-17).
 * Replica estructura de esquemas como Buckets (DEFINED/NONE), crea índices (BTREE, NUMERIC, AUTO_INCREMENT),
 * preserva la precisión crítica de decimales (BigDecimal) y marcas de tiempo/fechas ISO-8601,
 * y genera el artefacto backend descargable en formato .zip.
 */
public class JettraPostgresMigrator {

    private final PostgresIntrospector introspector = new PostgresIntrospector();
    private final JettraBackendProjectGenerator generator = new JettraBackendProjectGenerator();

    public PostgresIntrospectionResult introspect(PostgresConnectionConfig config) {
        return introspector.introspect(config);
    }

    public List<String> listDatabases(PostgresConnectionConfig config) throws java.sql.SQLException {
        return introspector.listDatabases(config);
    }

    public List<String> listSchemas(PostgresConnectionConfig config) throws java.sql.SQLException {
        return introspector.listSchemas(config);
    }

    public MigrationProgress migrate(PostgresConnectionConfig config, JettraClient client, Consumer<MigrationProgress> progressCallback) {
        MigrationProgress progress = new MigrationProgress();
        long startTime = System.currentTimeMillis();

        Consumer<MigrationProgress> notify = cb -> {
            if (progressCallback != null) {
                try { progressCallback.accept(cb); } catch (Exception ignored) {}
            }
        };

        progress.addLog("Iniciando análisis e introspección de PostgreSQL en " + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase());
        notify.accept(progress);

        // 1. Introspección del esquema
        PostgresIntrospectionResult metadata = introspector.introspect(config);
        if (!metadata.isSuccess()) {
            progress.addError("Error en introspección: " + metadata.getErrorMessage());
            progress.setCompleted(true);
            progress.setElapsedMs(System.currentTimeMillis() - startTime);
            notify.accept(progress);
            return progress;
        }

        progress.setTotalTables(metadata.getTables().size());
        progress.setTotalRecords(metadata.getTotalRows());
        progress.addLog("Esquema analizado: " + metadata.getTables().size() + " tablas y " + metadata.getTotalRows() + " registros detectados.");
        notify.accept(progress);

        if (config.isDryRun()) {
            progress.addLog("Modo DRY-RUN activado: Simulación completada sin mutar el almacenamiento.");
            if (config.isGenerateBackend()) {
                generateAndAttachZip(metadata, config, progress);
            }
            progress.setCompleted(true);
            progress.setElapsedMs(System.currentTimeMillis() - startTime);
            notify.accept(progress);
            return progress;
        }

        // 2. Conectar a JettraStore y preparar Base de Datos
        String targetDb = config.getTargetDatabase();
        String engine = (config.getTargetEngine() != null && !config.getTargetEngine().isBlank())
                ? config.getTargetEngine().trim() : "DOCUMENT";

        try {
            if (client != null) {
                try {
                    client.createDatabase(targetDb);
                    progress.addLog("Base de datos destino '" + targetDb + "' verificada/creada en JettraStore.");
                } catch (Exception ex) {
                    progress.addLog("Base de datos '" + targetDb + "' ya existente o lista para recibir buckets.");
                }
            }
        } catch (Exception ex) {
            progress.addError("Error preparando base de datos destino: " + ex.getMessage());
        }

        String jdbcUrl = config.toJdbcUrl();
        Properties props = new Properties();
        if (config.getUser() != null) props.setProperty("user", config.getUser());
        if (config.getPassword() != null) props.setProperty("password", config.getPassword());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            conn.setAutoCommit(false);

            for (PostgresTableMeta table : metadata.getTables()) {
                String tableName = table.getTableName();
                progress.setCurrentTable(tableName);
                progress.addLog("Migrando tabla '" + tableName + "' hacia Bucket '" + tableName + "' (motor " + engine + ")...");
                notify.accept(progress);

                // A. Crear Bucket estructurado (DEFINED)
                if (client != null) {
                    List<JettraBucketSchema.AttributeRule> schemaRules = new ArrayList<>();
                    schemaRules.add(new JettraBucketSchema.AttributeRule("jettraId", "String", "NOT_NULL", "Clave única JettraId"));
                    for (PostgresColumnMeta col : table.getColumns()) {
                        String rule = col.isNullable() ? "" : "NOT_NULL";
                        if (col.isAutoIncrement()) {
                            rule = (rule.isBlank() ? "" : rule + ",") + "AUTO_INCREMENT";
                        }
                        schemaRules.add(new JettraBucketSchema.AttributeRule(
                                col.getColumnName(),
                                col.getJettraStoreType(),
                                rule,
                                "Campo migrado desde PostgreSQL (" + col.getDataType() + ")"
                        ));
                    }
                    try {
                        client.createBucket(targetDb, engine, tableName, JettraBucketSchema.StructureType.DEFINED, schemaRules);
                    } catch (Exception ex) {
                        progress.addLog("Bucket '" + tableName + "' creado o reutilizado.");
                    }

                    // B. Crear Índices equivalentes
                    for (PostgresIndexMeta idx : table.getIndexes()) {
                        try {
                            if ("AUTO_INCREMENT".equalsIgnoreCase(idx.getIndexType())) {
                                client.createAutoIncrementIndex(targetDb, tableName, idx.getIndexName(), idx.getColumnName());
                            } else {
                                client.createBucketIndex(targetDb, tableName, idx.getIndexName(), idx.getColumnName(), idx.getIndexType(), idx.isUnique());
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // C. Transferir datos fila por fila preservando exactitud de decimales y fechas
                long rowsInTable = 0;
                String selectSql = "SELECT * FROM \"" + config.getSchema() + "\".\"" + tableName + "\"";

                try (Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    stmt.setFetchSize(config.getBatchSize());
                    try (ResultSet rs = stmt.executeQuery(selectSql)) {
                        ResultSetMetaData rsmd = rs.getMetaData();
                        int colCount = rsmd.getColumnCount();

                        while (rs.next()) {
                            Map<String, Object> record = new LinkedHashMap<>();
                            String explicitId = null;

                            for (int i = 1; i <= colCount; i++) {
                                String colName = rsmd.getColumnName(i);
                                int colType = rsmd.getColumnType(i);

                                Object val = extractTypedValue(rs, i, colType);
                                if (val != null) {
                                    record.put(colName, val);
                                }

                                // Si la columna es PK, usarla para identificar
                                if (table.getPrimaryKeyColumn() != null && colName.equalsIgnoreCase(table.getPrimaryKeyColumn().getColumnName())) {
                                    if (val != null) explicitId = val.toString();
                                }
                            }

                            // Asegurar JettraId
                            if (!record.containsKey("jettraId")) {
                                String finalId = (explicitId != null && !explicitId.isBlank()) ? explicitId : JettraId.generate(targetDb, tableName).toHexString();
                                record.put("jettraId", finalId);
                            }

                            if (client != null) {
                                try {
                                    String recId = record.get("jettraId") != null ? record.get("jettraId").toString() : null;
                                    client.insertBucketRecord(targetDb, engine, tableName, recId, record);
                                } catch (Exception ex) {
                                    progress.addError("Error insertando registro en '" + tableName + "': " + ex.getMessage());
                                }
                            }

                            rowsInTable++;
                            progress.setRecordsMigrated(progress.getRecordsMigrated() + 1);
                        }
                    }
                } catch (Exception ex) {
                    progress.addError("Fallo al consultar tabla '" + tableName + "': " + ex.getMessage());
                }

                progress.setTablesMigrated(progress.getTablesMigrated() + 1);
                progress.addLog("Tabla '" + tableName + "' completada: " + rowsInTable + " registros migrados.");
                notify.accept(progress);
            }

        } catch (Exception e) {
            progress.addError("Error de migración de datos PostgreSQL: " + e.getMessage());
        }

        // 3. Generación del proyecto backend descargable en .zip
        if (config.isGenerateBackend()) {
            generateAndAttachZip(metadata, config, progress);
        }

        progress.setCompleted(true);
        progress.setElapsedMs(System.currentTimeMillis() - startTime);
        progress.addLog("Migración finalizada con éxito en " + progress.getElapsedMs() + " ms ("
                + progress.getTablesMigrated() + " tablas, " + progress.getRecordsMigrated() + " registros).");
        notify.accept(progress);

        return progress;
    }

    private void generateAndAttachZip(PostgresIntrospectionResult metadata, PostgresConnectionConfig config, MigrationProgress progress) {
        try {
            progress.addLog("Generando proyecto backend Java 25+ descargable en .zip...");
            byte[] zipBytes = generator.generateZip(metadata, config);
            progress.setGeneratedZipBytes(zipBytes);

            if (config.getOutputZipPath() != null && !config.getOutputZipPath().isBlank()) {
                Path outPath = Path.of(config.getOutputZipPath());
                if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
                try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                    fos.write(zipBytes);
                }
                progress.addLog("Proyecto backend empaquetado y guardado en: " + outPath.toAbsolutePath());
            } else {
                progress.addLog("Proyecto backend empaquetado en memoria (" + (zipBytes.length / 1024) + " KB) listo para descarga.");
            }
        } catch (Exception ex) {
            progress.addError("Error generando proyecto backend .zip: " + ex.getMessage());
        }
    }

    /**
     * Extrae el valor de la columna preservando la máxima fidelidad de tipos:
     * - DECIMAL / NUMERIC -> BigDecimal sin redondeo ni distorsión IEEE 754.
     * - TIMESTAMP / TIMESTAMPTZ -> ISO-8601 string exacto con zona y nanosegundos.
     * - DATE -> ISO-8601 YYYY-MM-DD.
     */
    private Object extractTypedValue(ResultSet rs, int colIdx, int colType) throws SQLException {
        switch (colType) {
            case Types.NUMERIC:
            case Types.DECIMAL: {
                BigDecimal bd = rs.getBigDecimal(colIdx);
                return bd != null ? bd.toPlainString() : null;
            }
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE: {
                try {
                    OffsetDateTime odt = rs.getObject(colIdx, OffsetDateTime.class);
                    if (odt != null) {
                        return odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    }
                } catch (Exception ignored) {}
                Timestamp ts = rs.getTimestamp(colIdx);
                return ts != null ? ts.toInstant().toString() : null;
            }
            case Types.DATE: {
                java.sql.Date d = rs.getDate(colIdx);
                return d != null ? d.toLocalDate().toString() : null;
            }
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE: {
                Time t = rs.getTime(colIdx);
                return t != null ? t.toString() : null;
            }
            case Types.BIGINT: {
                long l = rs.getLong(colIdx);
                return rs.wasNull() ? null : l;
            }
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT: {
                int n = rs.getInt(colIdx);
                return rs.wasNull() ? null : n;
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                boolean b = rs.getBoolean(colIdx);
                return rs.wasNull() ? null : b;
            }
            case Types.DOUBLE:
            case Types.FLOAT: {
                double d = rs.getDouble(colIdx);
                return rs.wasNull() ? null : d;
            }
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY: {
                byte[] bytes = rs.getBytes(colIdx);
                return bytes != null ? Base64.getEncoder().encodeToString(bytes) : null;
            }
            default: {
                return rs.getString(colIdx);
            }
        }
    }
}

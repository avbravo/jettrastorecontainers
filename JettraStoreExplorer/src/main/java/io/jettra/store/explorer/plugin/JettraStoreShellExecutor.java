package io.jettra.store.explorer.plugin;

import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineIndexInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin.QueryResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de ejecución de comandos interactivos para la consola /query de JettraStoreExplorer.
 * Emula el parsing, manejo de errores, historial y salida formateada de {@code JettraStoreShell}.
 */
public final class JettraStoreShellExecutor {

    public record ShellExecutionResult(
        boolean success,
        String executedCommand,
        String resolvedDb,
        String resolvedBucket,
        String statusMessage,
        String terminalOutput,
        List<EngineRecordInfo> records,
        long elapsedMicros
    ) {}

    public record HelpCategory(String id, String title, String icon, List<HelpCommand> commands) {}
    public record HelpCommand(String syntax, String description, String example) {}

    private static final JettraStoreShellExecutor INSTANCE = new JettraStoreShellExecutor();

    public static JettraStoreShellExecutor getInstance() {
        return INSTANCE;
    }

    private final List<String> commandHistory = new CopyOnWriteArrayList<>();
    private final Map<String, String> kvStore = new ConcurrentHashMap<>();
    private final List<String> graphEdges = new CopyOnWriteArrayList<>();
    private final Map<String, List<Double>> timeSeries = new ConcurrentHashMap<>();
    private final Map<String, double[]> vectors = new ConcurrentHashMap<>();
    private String storageMode = "OFF_HEAP";

    private JettraStoreShellExecutor() {
        kvStore.put("cluster:version", "JettraStore 2.0 (Java 25 Loom/FFM)");
        kvStore.put("raft:leader", "node-01");
        graphEdges.add("EmpresaA -[FACTURA]-> DistribuidoraNorte");
        graphEdges.add("Paciente01 -[ATENDIDO_EN]-> HospitalCentral");
        timeSeries.put("cpu_usage", new CopyOnWriteArrayList<>(List.of(22.4, 24.1, 23.8, 25.0)));
        vectors.put("vec-01", new double[]{0.85, 0.12, 0.44});
        vectors.put("vec-02", new double[]{0.14, 0.91, 0.35});
    }

    public List<String> getCommandHistory() {
        return Collections.unmodifiableList(commandHistory);
    }

    public ShellExecutionResult execute(JettraStorePlugin plugin, String db, String bucket, String rawInput) {
        long t0 = System.nanoTime();
        String activeDb = (db != null && !db.isBlank()) ? db.trim() : "example_factura_db";
        String activeBucket = (bucket != null && !bucket.isBlank()) ? bucket.trim() : "Facturas";

        if (rawInput == null || rawInput.isBlank()) {
            QueryResult all = plugin.executeQuery(activeDb, activeBucket, "", false);
            return new ShellExecutionResult(
                true, "", activeDb, activeBucket,
                all.message(),
                formatRecordsAscii(activeDb, activeBucket, all.records(), all.message()),
                all.records(),
                elapsedUs(t0)
            );
        }

        String trimmed = rawInput.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        // Resolución de alias de historial: !! y !<n>
        if ("!!".equals(trimmed)) {
            if (commandHistory.isEmpty()) {
                return errorResult(trimmed, activeDb, activeBucket, "[ERROR] El historial de comandos está vacío.", t0);
            }
            trimmed = commandHistory.get(commandHistory.size() - 1);
        } else if (trimmed.matches("^!\\d+$")) {
            int idx = Integer.parseInt(trimmed.substring(1)) - 1;
            if (idx < 0 || idx >= commandHistory.size()) {
                return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Índice de historial fuera de rango: " + trimmed, t0);
            }
            trimmed = commandHistory.get(idx);
        }

        recordHistory(trimmed);

        String upper = trimmed.toUpperCase(Locale.ROOT);
        String[] parts = tokenize(trimmed);
        String cmd = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "";

        try {
            if ("HELP".equals(cmd) || "?".equals(cmd)) {
                String out = getHelpReferenceText();
                return okResult(trimmed, activeDb, activeBucket, "Referencia de comandos JettraStoreShell desplegada", out, Collections.emptyList(), t0);
            }

            if ("CLEAR".equals(cmd) || "CLS".equals(cmd)) {
                commandHistory.clear();
                return okResult(trimmed, activeDb, activeBucket, "Historial de consola limpiado", "[OK] Historial de consola restablecido.", Collections.emptyList(), t0);
            }

            if ("HISTORY".equals(cmd)) {
                StringBuilder sb = new StringBuilder("=== Historial de Comandos ===\n");
                for (int i = 0; i < commandHistory.size(); i++) {
                    sb.append(String.format("  %3d  %s%n", i + 1, commandHistory.get(i)));
                }
                return okResult(trimmed, activeDb, activeBucket, "Historial (" + commandHistory.size() + " entradas)", sb.toString(), Collections.emptyList(), t0);
            }

            if ("WHOAMI".equals(cmd)) {
                String out = "Usuario actual: admin | Rol: ADMIN | Contexto: " + activeDb + "." + activeBucket;
                return okResult(trimmed, activeDb, activeBucket, out, "[AUTH] " + out, Collections.emptyList(), t0);
            }

            if ("MODE".equals(cmd)) {
                String out = "Modo de operación activo: EMBEDDED_ADMIN_SHELL | Topología: " + plugin.getMultinodeDisplay() + " | Storage: " + storageMode;
                return okResult(trimmed, activeDb, activeBucket, out, out, Collections.emptyList(), t0);
            }

            if ("STATUS".equals(cmd) || "INFO".equals(cmd)) {
                return handleStatus(plugin, trimmed, activeDb, activeBucket, t0);
            }

            if ("STORAGE_MODE".equals(cmd)) {
                if (parts.length > 1) {
                    String newMode = parts[1].toUpperCase(Locale.ROOT);
                    if (!List.of("OFF_HEAP", "HEAP", "DISK", "MEMORY", "HYBRID").contains(newMode)) {
                        return errorResult(trimmed, activeDb, activeBucket, "[ERROR] STORAGE_MODE inválido '" + newMode + "'. Valores: OFF_HEAP, HEAP, DISK, MEMORY, HYBRID.", t0);
                    }
                    this.storageMode = newMode;
                }
                String out = "[OK] STORAGE_MODE activo: " + this.storageMode;
                return okResult(trimmed, activeDb, activeBucket, out, out, Collections.emptyList(), t0);
            }

            if ("MULTINODE".equals(cmd)) {
                if (parts.length > 1) {
                    String arg = parts[1].toUpperCase(Locale.ROOT);
                    if ("ON".equals(arg) || "TRUE".equals(arg)) plugin.setMultinodeActive(true);
                    else if ("OFF".equals(arg) || "FALSE".equals(arg)) plugin.setMultinodeActive(false);
                    else return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Uso: MULTINODE [ON|OFF]", t0);
                }
                String out = "[OK] Estado de clúster: " + plugin.getMultinodeDisplay();
                return okResult(trimmed, activeDb, activeBucket, out, out, Collections.emptyList(), t0);
            }

            if ("USE".equals(cmd)) {
                if (parts.length < 2) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Uso: USE <database> o USE <database>.<bucket>", t0);
                }
                String target = parts[1];
                String nextDb = target.contains(".") ? target.substring(0, target.indexOf('.')) : target;
                if (!plugin.getDatabaseNames().contains(nextDb)) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Base de datos no encontrada: " + nextDb, t0);
                }
                List<EngineBucketInfo> dbBuckets = plugin.getBuckets(nextDb);
                String nextBucket = activeBucket;
                if (target.contains(".")) {
                    String reqBucket = target.substring(target.indexOf('.') + 1);
                    EngineBucketInfo found = findExactBucket(plugin, nextDb, reqBucket);
                    if (found == null) {
                        return errorResult(trimmed, nextDb, activeBucket, "[ERROR] Bucket no encontrado en '" + nextDb + "': " + reqBucket, t0);
                    }
                    nextBucket = found.getBucketName();
                } else if (!dbBuckets.isEmpty()) {
                    nextBucket = dbBuckets.get(0).getBucketName();
                }
                String out = "[OK] Contexto cambiado a -> Base de Datos: '" + nextDb + "', Bucket: '" + nextBucket + "'";
                return okResult(trimmed, nextDb, nextBucket, out, out, Collections.emptyList(), t0);
            }

            if ("SHOW".equals(cmd)) {
                return handleShow(plugin, trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("CREATE".equals(cmd)) {
                return handleCreate(plugin, trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("DROP".equals(cmd)) {
                return handleDrop(plugin, trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("COUNT".equals(cmd)) {
                String targetBucket = parts.length > 1 ? parts[1] : activeBucket;
                EngineBucketInfo b = findExactBucket(plugin, activeDb, targetBucket);
                if (b == null) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Bucket no encontrado: " + targetBucket, t0);
                }
                String out = String.format("Bucket: %s.%s [%s] | Registros en muestra: %d | Total almacenado: %,d",
                    activeDb, b.getBucketName(), b.getEngineType(), b.getSampleRecords().size(), b.getTotalObjects());
                return okResult(trimmed, activeDb, b.getBucketName(), out, out, b.getSampleRecords(), t0);
            }

            if ("GET".equals(cmd)) {
                if (parts.length < 2) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Uso: GET <recordId>", t0);
                }
                String id = parts[1];
                EngineRecordInfo rec = plugin.getRecord(activeDb, activeBucket, id);
                if (rec == null) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Registro no encontrado: '" + id + "' en " + activeDb + "." + activeBucket, t0);
                }
                String out = "=== Registro " + rec.getId() + " (v" + rec.getVersion() + ") ===\n"
                    + "Resumen: " + rec.getSummary() + "\n"
                    + "Motor:   " + rec.getEngineType() + " | Actualizado: " + rec.getTimestamp() + "\n"
                    + rec.getDetails();
                return okResult(trimmed, activeDb, activeBucket, "Registro '" + id + "' recuperado", out, List.of(rec), t0);
            }

            if ("INSERT".equals(cmd)) {
                return handleInsert(plugin, trimmed, activeDb, activeBucket, t0);
            }

            if ("UPDATE".equals(cmd)) {
                return handleUpdate(plugin, trimmed, activeDb, activeBucket, t0);
            }

            if ("DELETE".equals(cmd)) {
                return handleDelete(plugin, trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("SELECT".equals(cmd)) {
                return handleSelect(plugin, trimmed, activeDb, activeBucket, t0);
            }

            if ("JQL".equals(cmd) || "FIND".equals(cmd)) {
                return handleJqlOrFind(plugin, trimmed, activeDb, activeBucket, t0);
            }

            if ("ADD".equals(cmd) && parts.length >= 2 && "NODE".equalsIgnoreCase(parts[1])) {
                return handleAddNode(plugin, trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("REMOVE".equals(cmd) && parts.length >= 3 && "NODE".equalsIgnoreCase(parts[1])) {
                String nodeId = parts[2];
                boolean ok = plugin.removeNode(nodeId);
                if (!ok) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] No se pudo expulsar el nodo '" + nodeId + "' (puede estar protegido o no existir).", t0);
                }
                String out = "[OK] Nodo '" + nodeId + "' expulsado del clúster.";
                return okResult(trimmed, activeDb, activeBucket, out, out, Collections.emptyList(), t0);
            }

            if (("STOP".equals(cmd) || "SHUTDOWN".equals(cmd)) && parts.length >= 3 && "NODE".equalsIgnoreCase(parts[1])) {
                String nodeId = parts[2];
                NodeLifecycleManager.ShutdownOutcome outcome = plugin.shutdownNode(nodeId);
                if (!outcome.success()) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] " + outcome.message(), t0);
                }
                String out = "[OFFLINE] " + outcome.message() + " (" + outcome.elapsedMs() + " ms)";
                return okResult(trimmed, activeDb, activeBucket, outcome.message(), out, Collections.emptyList(), t0);
            }

            if ("START".equals(cmd) && parts.length >= 3 && "NODE".equalsIgnoreCase(parts[1])) {
                String nodeId = parts[2];
                NodeLifecycleManager.StartOutcome outcome = plugin.startNode(nodeId);
                if (!outcome.success()) {
                    return errorResult(trimmed, activeDb, activeBucket, "[ERROR] " + outcome.message(), t0);
                }
                String out = (outcome.onlineVerified() ? "[ONLINE] " : "[WARN] ") + outcome.message();
                return okResult(trimmed, activeDb, activeBucket, outcome.message(), out, Collections.emptyList(), t0);
            }

            if ("KV".equals(cmd)) {
                return handleKv(trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("GRAPH".equals(cmd)) {
                return handleGraph(trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("TS".equals(cmd)) {
                return handleTimeSeries(trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("VECTOR".equals(cmd)) {
                return handleVector(trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("MATH".equals(cmd)) {
                return handleMath(trimmed, activeDb, activeBucket, t0);
            }

            if ("STATS".equals(cmd)) {
                return handleStats(trimmed, parts, activeDb, activeBucket, t0);
            }

            if ("FINANCE".equals(cmd)) {
                return handleFinance(trimmed, parts, activeDb, activeBucket, t0);
            }

            // Soporte retrocompatible para expresiones de filtro directas (e.g. "total > 500", "emisor LIKE Corp")
            if (isFilterPredicateExpression(trimmed)) {
                QueryResult qr = plugin.executeQuery(activeDb, activeBucket, trimmed, false);
                return new ShellExecutionResult(
                    qr.success(), trimmed, activeDb, activeBucket,
                    qr.message(),
                    formatRecordsAscii(activeDb, activeBucket, qr.records(), qr.message()),
                    qr.records(),
                    qr.elapsedMicros()
                );
            }

            // Cualquier otro comando desconocido produce error estructurado idéntico a JettraStoreShell
            return errorResult(trimmed, activeDb, activeBucket,
                "[ERROR] Comando desconocido: '" + parts[0] + "'. Escriba 'HELP' o presione el botón 'Ayuda' para ver los comandos disponibles.", t0);

        } catch (Exception ex) {
            return errorResult(trimmed, activeDb, activeBucket, "[ERROR] Excepción ejecutando comando: " + ex.getMessage(), t0);
        }
    }

    private ShellExecutionResult handleStatus(JettraStorePlugin plugin, String cmd, String db, String bucket, long t0) {
        plugin.refreshAllNodeStatusesIfStale(500L);
        List<ServerNodeInfo> nodes = plugin.getServerNodes();
        long online = nodes.stream().filter(ServerNodeInfo::isOnline).count();
        StringBuilder sb = new StringBuilder();
        sb.append("+----------------------------------------------------------+\n");
        sb.append("| JETTRASTORE CLUSTER & RUNTIME TELEMETRY STATUS           |\n");
        sb.append("+----------------------------------------------------------+\n");
        sb.append(String.format("| Active Context   : %-37s |\n", db + "." + bucket));
        sb.append(String.format("| Storage Mode     : %-37s |\n", storageMode));
        sb.append(String.format("| Cluster Topology : %-37s |\n", plugin.getMultinodeDisplay()));
        sb.append(String.format("| Cluster Nodes    : %-37s |\n", online + " ONLINE / " + nodes.size() + " total"));
        sb.append(String.format("| Databases        : %-37s |\n", plugin.getDatabaseNames().size() + " catálogos registrados"));
        sb.append(String.format("| Police Events    : %-37s |\n", plugin.getPoliceIncidents().size() + " incidentes recientes"));
        sb.append("+----------------------------------------------------------+\n");
        return okResult(cmd, db, bucket, "Estado del sistema recuperado (" + online + "/" + nodes.size() + " nodos ONLINE)", sb.toString(), Collections.emptyList(), t0);
    }

    private ShellExecutionResult handleShow(JettraStorePlugin plugin, String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 2) {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: SHOW [DATABASES|BUCKETS|RECORDS|USERS|INDEXES|NODES|SAMPLES]", t0);
        }
        String target = parts[1].toUpperCase(Locale.ROOT);
        switch (target) {
            case "DATABASES", "DBS" -> {
                List<String> dbs = plugin.getDatabaseNames();
                StringBuilder sb = new StringBuilder();
                sb.append("+--------------------------------+----------+------------------+\n");
                sb.append("| Database Name                  | Buckets  | Primary Engines  |\n");
                sb.append("+--------------------------------+----------+------------------+\n");
                for (String d : dbs) {
                    List<EngineBucketInfo> bs = plugin.getBuckets(d);
                    String engines = bs.stream().map(EngineBucketInfo::getEngineType).distinct().reduce((a, b) -> a + "," + b).orElse("-");
                    sb.append(String.format("| %-30s | %-8d | %-16s |\n", truncate(d, 30), bs.size(), truncate(engines, 16)));
                }
                sb.append("+--------------------------------+----------+------------------+");
                return okResult(cmd, db, bucket, dbs.size() + " bases de datos encontradas", sb.toString(), Collections.emptyList(), t0);
            }
            case "BUCKETS", "ENGINES" -> {
                String targetDb = parts.length > 2 ? parts[2] : db;
                List<EngineBucketInfo> buckets = plugin.getBuckets(targetDb);
                if (buckets.isEmpty()) {
                    return errorResult(cmd, db, bucket, "[ERROR] No existen buckets o la base de datos '" + targetDb + "' no existe.", t0);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("+------------------------+--------------+------------+--------------+\n");
                sb.append("| Bucket Name            | Engine Type  | Sample Cnt | Total Obj    |\n");
                sb.append("+------------------------+--------------+------------+--------------+\n");
                for (EngineBucketInfo b : buckets) {
                    sb.append(String.format("| %-22s | %-12s | %-10d | %-12d |\n",
                        truncate(b.getBucketName(), 22), truncate(b.getEngineType(), 12), b.getSampleRecords().size(), b.getTotalObjects()));
                }
                sb.append("+------------------------+--------------+------------+--------------+");
                return okResult(cmd, targetDb, bucket, buckets.size() + " buckets en '" + targetDb + "'", sb.toString(), Collections.emptyList(), t0);
            }
            case "NODES" -> {
                plugin.refreshAllNodeStatusesIfStale(500L);
                List<ServerNodeInfo> nodes = plugin.getServerNodes();
                StringBuilder sb = new StringBuilder();
                sb.append("+--------------+------------+---------------------+--------+------------+\n");
                sb.append("| Node ID      | Role       | Endpoint            | Status | Latency    |\n");
                sb.append("+--------------+------------+---------------------+--------+------------+\n");
                for (ServerNodeInfo n : nodes) {
                    String st = n.isOnline() ? "ONLINE" : "OFFLINE";
                    String lat = n.isOnline() ? String.format(Locale.US, "%.2f ms", n.getLatencyMs()) : "-";
                    sb.append(String.format("| %-12s | %-10s | %-19s | %-6s | %-10s |\n",
                        truncate(n.getId(), 12), truncate(n.getRole(), 10), truncate(n.getHost() + ":" + n.getPort(), 19), st, lat));
                }
                sb.append("+--------------+------------+---------------------+--------+------------+");
                return okResult(cmd, db, bucket, nodes.size() + " nodos del clúster", sb.toString(), Collections.emptyList(), t0);
            }
            case "RECORDS", "SAMPLES" -> {
                String targetBucket = parts.length > 2 ? parts[2] : bucket;
                QueryResult qr = plugin.executeQuery(db, targetBucket, "", false);
                return new ShellExecutionResult(qr.success(), cmd, db, targetBucket, qr.message(),
                    formatRecordsAscii(db, targetBucket, qr.records(), qr.message()), qr.records(), elapsedUs(t0));
            }
            case "USERS" -> {
                List<JettraUser> users = plugin.getUsers();
                StringBuilder sb = new StringBuilder();
                sb.append("+----------------------+------------------+--------------------------------+\n");
                sb.append("| Username             | Global Role      | Description                    |\n");
                sb.append("+----------------------+------------------+--------------------------------+\n");
                for (JettraUser u : users) {
                    sb.append(String.format("| %-20s | %-16s | %-30s |\n",
                        truncate(u.getUsername(), 20), truncate(String.valueOf(u.getGlobalRole()), 16), truncate(u.getDescription(), 30)));
                }
                sb.append("+----------------------+------------------+--------------------------------+");
                return okResult(cmd, db, bucket, users.size() + " usuarios registrados", sb.toString(), Collections.emptyList(), t0);
            }
            case "INDEXES" -> {
                String targetBucket = parts.length > 2 ? parts[2] : bucket;
                EngineBucketInfo b = findExactBucket(plugin, db, targetBucket);
                if (b == null) {
                    return errorResult(cmd, db, bucket, "[ERROR] Bucket no encontrado: " + targetBucket, t0);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("+------------------------+----------------------+--------+--------+\n");
                sb.append("| Index Name             | Fields               | Type   | Unique |\n");
                sb.append("+------------------------+----------------------+--------+--------+\n");
                for (EngineIndexInfo idx : b.getIndexes()) {
                    sb.append(String.format("| %-22s | %-20s | %-6s | %-6s |\n",
                        truncate(idx.getName(), 22), truncate(idx.getFields(), 20), truncate(idx.getType(), 6), idx.isUnique() ? "YES" : "NO"));
                }
                sb.append("+------------------------+----------------------+--------+--------+");
                return okResult(cmd, db, b.getBucketName(), b.getIndexes().size() + " índices en " + b.getBucketName(), sb.toString(), Collections.emptyList(), t0);
            }
            default -> {
                return errorResult(cmd, db, bucket, "[ERROR] Subcomando SHOW desconocido: '" + target + "'", t0);
            }
        }
    }

    private ShellExecutionResult handleCreate(JettraStorePlugin plugin, String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 3) {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: CREATE DATABASE <nombre> | CREATE BUCKET <nombre> [ENGINE] | CREATE INDEX <nombre> ON <bucket>(<campo>)", t0);
        }
        String objType = parts[1].toUpperCase(Locale.ROOT);
        if ("DATABASE".equals(objType) || "DB".equals(objType)) {
            String name = parts[2];
            String engine = parts.length > 3 ? parts[3].toUpperCase(Locale.ROOT) : "DOCUMENT";
            boolean created = plugin.createDatabase(name, "DefaultBucket", engine, "Creada vía JettraStoreShell Console");
            if (!created) {
                return errorResult(cmd, db, bucket, "[ERROR] No se pudo crear la base de datos '" + name + "' (¿ya existe?).", t0);
            }
            String out = "[OK] Base de datos '" + name + "' creada exitosamente.";
            return okResult(cmd, name, "DefaultBucket", out, out, Collections.emptyList(), t0);
        }
        if ("BUCKET".equals(objType) || "ENGINE".equals(objType)) {
            String bName = parts[2];
            String engine = parts.length > 3 ? parts[3].toUpperCase(Locale.ROOT) : "DOCUMENT";
            boolean created = plugin.addBucket(db, bName, engine, "Creado vía JettraStoreShell Console");
            if (!created) {
                return errorResult(cmd, db, bucket, "[ERROR] No se pudo crear el bucket '" + bName + "' en '" + db + "'.", t0);
            }
            String out = "[OK] Bucket '" + bName + "' [" + engine + "] creado en '" + db + "'.";
            return okResult(cmd, db, bName, out, out, Collections.emptyList(), t0);
        }
        if ("INDEX".equals(objType)) {
            Matcher m = Pattern.compile("CREATE\\s+INDEX\\s+(\\S+)\\s+ON\\s+([^(]+)\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE).matcher(cmd);
            if (!m.find()) {
                return errorResult(cmd, db, bucket, "[ERROR] Uso: CREATE INDEX <idx_name> ON <bucket>(<field>)", t0);
            }
            String idxName = m.group(1).trim();
            String targetBucket = m.group(2).trim();
            String field = m.group(3).trim();
            EngineBucketInfo b = findExactBucket(plugin, db, targetBucket);
            if (b == null) {
                return errorResult(cmd, db, bucket, "[ERROR] Bucket no encontrado: " + targetBucket, t0);
            }
            b.getIndexes().add(new EngineIndexInfo(idxName, field, "BTREE", false, "READY"));
            String out = "[OK] Índice '" + idxName + "' creado sobre " + b.getBucketName() + "(" + field + ").";
            return okResult(cmd, db, b.getBucketName(), out, out, Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Tipo no soportado en CREATE: '" + objType + "'", t0);
    }

    private ShellExecutionResult handleDrop(JettraStorePlugin plugin, String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 3) {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: DROP DATABASE <nombre> | DROP BUCKET <nombre>", t0);
        }
        String objType = parts[1].toUpperCase(Locale.ROOT);
        String name = parts[2];
        if ("DATABASE".equals(objType) || "DB".equals(objType)) {
            boolean dropped = plugin.deleteDatabase(name);
            if (!dropped) {
                return errorResult(cmd, db, bucket, "[ERROR] No se pudo eliminar la base de datos '" + name + "'.", t0);
            }
            String out = "[OK] Base de datos '" + name + "' eliminada.";
            return okResult(cmd, db.equalsIgnoreCase(name) ? "example_factura_db" : db, bucket, out, out, Collections.emptyList(), t0);
        }
        if ("BUCKET".equals(objType)) {
            boolean dropped = plugin.deleteBucket(db, name);
            if (!dropped) {
                return errorResult(cmd, db, bucket, "[ERROR] No se pudo eliminar el bucket '" + name + "' (puede ser el último bucket o no existir).", t0);
            }
            String out = "[OK] Bucket '" + name + "' eliminado de '" + db + "'.";
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Tipo no soportado en DROP: '" + objType + "'", t0);
    }

    private ShellExecutionResult handleSelect(JettraStorePlugin plugin, String cmd, String db, String bucket, long t0) {
        Matcher m = Pattern.compile("^SELECT\\s+(.+?)\\s+FROM\\s+(\\S+)(?:\\s+WHERE\\s+(.+))?$", Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (!m.find()) {
            return errorResult(cmd, db, bucket, "[ERROR] Sintaxis SELECT inválida. Uso: SELECT * FROM <bucket> [WHERE <condición>]", t0);
        }
        String targetBucket = m.group(2).trim();
        String where = m.group(3) != null ? m.group(3).trim() : "";
        EngineBucketInfo b = findExactBucket(plugin, db, targetBucket);
        if (b == null) {
            return errorResult(cmd, db, bucket, "[ERROR] Bucket no encontrado en '" + db + "': " + targetBucket, t0);
        }
        QueryResult qr = plugin.executeQuery(db, b.getBucketName(), where, true);
        return new ShellExecutionResult(
            qr.success(), cmd, db, b.getBucketName(),
            qr.message(),
            formatRecordsAscii(db, b.getBucketName(), qr.records(), qr.message()),
            qr.records(),
            elapsedUs(t0)
        );
    }

    private ShellExecutionResult handleJqlOrFind(JettraStorePlugin plugin, String cmd, String db, String bucket, long t0) {
        String targetBucket = bucket;
        String filter = "";
        Matcher mJql = Pattern.compile("^JQL\\s+FROM\\s+(\\S+)(?:\\s+WHERE\\s+(.+))?$", Pattern.CASE_INSENSITIVE).matcher(cmd);
        Matcher mFind = Pattern.compile("^FIND(?:\\s+IN\\s+(\\S+))?(?:\\s+WHERE\\s+(.+))?$", Pattern.CASE_INSENSITIVE).matcher(cmd);

        if (mJql.find()) {
            targetBucket = mJql.group(1).trim();
            filter = mJql.group(2) != null ? mJql.group(2).trim() : "";
        } else if (mFind.find()) {
            if (mFind.group(1) != null && !mFind.group(1).isBlank()) targetBucket = mFind.group(1).trim();
            filter = mFind.group(2) != null ? mFind.group(2).trim() : "";
        } else {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: JQL FROM <bucket> [WHERE <condición>] o FIND WHERE <condición>", t0);
        }

        EngineBucketInfo b = findExactBucket(plugin, db, targetBucket);
        if (b == null) {
            return errorResult(cmd, db, bucket, "[ERROR] Bucket no encontrado en '" + db + "': " + targetBucket, t0);
        }
        QueryResult qr = plugin.executeQuery(db, b.getBucketName(), filter, false);
        return new ShellExecutionResult(
            qr.success(), cmd, db, b.getBucketName(),
            qr.message(),
            formatRecordsAscii(db, b.getBucketName(), qr.records(), qr.message()),
            qr.records(),
            elapsedUs(t0)
        );
    }

    private ShellExecutionResult handleInsert(JettraStorePlugin plugin, String cmd, String db, String bucket, long t0) {
        // Formato 1: INSERT INTO <bucket> VALUES (<id>, '<summary>', '<json>')
        Matcher sqlM = Pattern.compile("^INSERT\\s+INTO\\s+(\\S+)\\s+VALUES\\s*\\((.+)\\)$", Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (sqlM.find()) {
            String targetBucket = sqlM.group(1).trim();
            EngineBucketInfo b = findExactBucket(plugin, db, targetBucket);
            if (b == null) return errorResult(cmd, db, bucket, "[ERROR] Bucket no encontrado: " + targetBucket, t0);
            String payload = sqlM.group(2).trim();
            String id = "REC-" + System.currentTimeMillis();
            String summary = "Registro insertado vía Shell";
            String details = payload;
            if (payload.startsWith("{")) {
                Matcher idM = Pattern.compile("\"(?:id|folio|uuid)\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
                if (idM.find()) id = idM.group(1);
                summary = "Documento JSON " + id;
            } else {
                String[] vals = payload.split(",", 3);
                if (vals.length >= 1) id = stripQuotes(vals[0].trim());
                if (vals.length >= 2) summary = stripQuotes(vals[1].trim());
                details = vals.length >= 3 ? stripQuotes(vals[2].trim()) : "{\"id\":\"" + id + "\",\"summary\":\"" + summary + "\"}";
            }
            plugin.addRecord(db, b.getBucketName(), id, summary, details);
            EngineRecordInfo inserted = plugin.getRecord(db, b.getBucketName(), id);
            String out = "[OK] Registro '" + id + "' insertado en " + db + "." + b.getBucketName() + ".";
            return okResult(cmd, db, b.getBucketName(), out, out, inserted != null ? List.of(inserted) : Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Uso: INSERT INTO <bucket> VALUES ('id', 'resumen', '{\"campo\":\"valor\"}')", t0);
    }

    private ShellExecutionResult handleUpdate(JettraStorePlugin plugin, String cmd, String db, String bucket, long t0) {
        Matcher m = Pattern.compile("^UPDATE\\s+(\\S+)\\s+SET\\s+summary\\s*=\\s*['\"]([^'\"]+)['\"](?:,\\s*details\\s*=\\s*['\"](.+)['\"])?\\s+WHERE\\s+id\\s*=\\s*['\"]([^'\"]+)['\"]$", Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (!m.find()) {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: UPDATE <bucket> SET summary='nuevo' WHERE id='<recordId>'", t0);
        }
        String targetBucket = m.group(1).trim();
        String newSummary = m.group(2).trim();
        String newDetails = m.group(3);
        String id = m.group(4).trim();
        EngineRecordInfo existing = plugin.getRecord(db, targetBucket, id);
        if (existing == null) {
            return errorResult(cmd, db, bucket, "[ERROR] Registro '" + id + "' no encontrado en " + targetBucket, t0);
        }
        plugin.updateRecord(db, targetBucket, id, newSummary, newDetails != null ? newDetails : existing.getDetails());
        String out = "[OK] Registro '" + id + "' actualizado a v" + existing.getVersion() + ".";
        return okResult(cmd, db, targetBucket, out, out, List.of(existing), t0);
    }

    private ShellExecutionResult handleDelete(JettraStorePlugin plugin, String cmd, String[] parts, String db, String bucket, long t0) {
        Matcher m = Pattern.compile("^DELETE\\s+FROM\\s+(\\S+)\\s+WHERE\\s+id\\s*=\\s*['\"]([^'\"]+)['\"]$", Pattern.CASE_INSENSITIVE).matcher(cmd);
        String targetBucket = bucket;
        String id = null;
        if (m.find()) {
            targetBucket = m.group(1).trim();
            id = m.group(2).trim();
        } else if (parts.length == 2) {
            id = stripQuotes(parts[1]);
        } else {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: DELETE FROM <bucket> WHERE id='<recordId>' | DELETE <recordId>", t0);
        }
        EngineRecordInfo existing = plugin.getRecord(db, targetBucket, id);
        if (existing == null) {
            return errorResult(cmd, db, bucket, "[ERROR] Registro no encontrado para eliminar: " + id, t0);
        }
        plugin.deleteRecord(db, targetBucket, id);
        String out = "[OK] Registro '" + id + "' eliminado de " + db + "." + targetBucket + ".";
        return okResult(cmd, db, targetBucket, out, out, Collections.emptyList(), t0);
    }

    private ShellExecutionResult handleAddNode(JettraStorePlugin plugin, String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 5) {
            return errorResult(cmd, db, bucket, "[ERROR] Uso: ADD NODE <nodeId> <host> <port> [ROLE]", t0);
        }
        String id = parts[2];
        String host = parts[3];
        int port;
        try {
            port = Integer.parseInt(parts[4]);
        } catch (NumberFormatException nfe) {
            return errorResult(cmd, db, bucket, "[ERROR] Puerto inválido: " + parts[4], t0);
        }
        String role = parts.length > 5 ? parts[5].toUpperCase(Locale.ROOT) : "FOLLOWER";
        boolean ok = plugin.addNode(id, id, role, host, port, 2048);
        if (!ok) {
            return errorResult(cmd, db, bucket, "[ERROR] Ya existe un nodo con ID '" + id + "'.", t0);
        }
        String out = "[OK] Nodo '" + id + "' [" + role + "] registrado en " + host + ":" + port + ".";
        return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
    }

    private ShellExecutionResult handleKv(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 2) return errorResult(cmd, db, bucket, "[ERROR] Uso: KV [PUT <k> <v> | GET <k> | DEL <k> | LIST]", t0);
        String sub = parts[1].toUpperCase(Locale.ROOT);
        switch (sub) {
            case "LIST" -> {
                StringBuilder sb = new StringBuilder("=== JettraKV In-Memory Store (" + kvStore.size() + " keys) ===\n");
                kvStore.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
                return okResult(cmd, db, bucket, kvStore.size() + " llaves KV", sb.toString(), Collections.emptyList(), t0);
            }
            case "GET" -> {
                if (parts.length < 3) return errorResult(cmd, db, bucket, "[ERROR] Uso: KV GET <key>", t0);
                String val = kvStore.get(parts[2]);
                if (val == null) return errorResult(cmd, db, bucket, "[ERROR] Llave KV no encontrada: " + parts[2], t0);
                String out = parts[2] + " = " + val;
                return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
            }
            case "PUT" -> {
                if (parts.length < 4) return errorResult(cmd, db, bucket, "[ERROR] Uso: KV PUT <key> <value>", t0);
                String val = joinFrom(parts, 3);
                kvStore.put(parts[2], val);
                String out = "[OK] KV PUT " + parts[2] + " = " + val;
                return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
            }
            case "DEL" -> {
                if (parts.length < 3) return errorResult(cmd, db, bucket, "[ERROR] Uso: KV DEL <key>", t0);
                String removed = kvStore.remove(parts[2]);
                if (removed == null) return errorResult(cmd, db, bucket, "[ERROR] Llave KV no encontrada: " + parts[2], t0);
                String out = "[OK] Llave KV eliminada: " + parts[2];
                return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
            }
            default -> {
                return errorResult(cmd, db, bucket, "[ERROR] Subcomando KV inválido: " + sub, t0);
            }
        }
    }

    private ShellExecutionResult handleGraph(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 2) return errorResult(cmd, db, bucket, "[ERROR] Uso: GRAPH [LIST | CREATE <origen> <relacion> <destino> | QUERY <nodo>]", t0);
        String sub = parts[1].toUpperCase(Locale.ROOT);
        switch (sub) {
            case "LIST" -> {
                String out = "=== Aristas del Grafo ===\n" + String.join("\n", graphEdges);
                return okResult(cmd, db, bucket, graphEdges.size() + " aristas", out, Collections.emptyList(), t0);
            }
            case "CREATE" -> {
                if (parts.length < 5) return errorResult(cmd, db, bucket, "[ERROR] Uso: GRAPH CREATE <origen> <relacion> <destino>", t0);
                String edge = parts[2] + " -[" + parts[3].toUpperCase(Locale.ROOT) + "]-> " + parts[4];
                graphEdges.add(edge);
                String out = "[OK] Arista creada: " + edge;
                return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
            }
            case "QUERY" -> {
                if (parts.length < 3) return errorResult(cmd, db, bucket, "[ERROR] Uso: GRAPH QUERY <nodo>", t0);
                String q = parts[2].toLowerCase(Locale.ROOT);
                List<String> matches = graphEdges.stream().filter(e -> e.toLowerCase(Locale.ROOT).contains(q)).toList();
                String out = matches.isEmpty() ? "(Sin coincidencias para '" + parts[2] + "')" : String.join("\n", matches);
                return okResult(cmd, db, bucket, matches.size() + " relaciones encontradas", out, Collections.emptyList(), t0);
            }
            default -> {
                return errorResult(cmd, db, bucket, "[ERROR] Subcomando GRAPH inválido: " + sub, t0);
            }
        }
    }

    private ShellExecutionResult handleTimeSeries(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 3) return errorResult(cmd, db, bucket, "[ERROR] Uso: TS [PUT <metric> <val> | QUERY <metric>]", t0);
        String sub = parts[1].toUpperCase(Locale.ROOT);
        String metric = parts[2];
        if ("PUT".equals(sub) && parts.length >= 4) {
            double v = Double.parseDouble(parts[3]);
            timeSeries.computeIfAbsent(metric, k -> new CopyOnWriteArrayList<>()).add(v);
            String out = "[OK] Punto agregado a serie '" + metric + "': " + v;
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        }
        if ("QUERY".equals(sub)) {
            List<Double> vals = timeSeries.get(metric);
            if (vals == null || vals.isEmpty()) return errorResult(cmd, db, bucket, "[ERROR] Serie temporal no encontrada: " + metric, t0);
            double avg = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            String out = String.format(Locale.US, "Serie '%s' | Puntos: %d | Promedio: %.2f | Valores: %s", metric, vals.size(), avg, vals);
            return okResult(cmd, db, bucket, "Serie '" + metric + "' consultada", out, Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Uso: TS [PUT <metric> <val> | QUERY <metric>]", t0);
    }

    private ShellExecutionResult handleVector(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 3) return errorResult(cmd, db, bucket, "[ERROR] Uso: VECTOR [UPSERT <id> <v1,v2,v3> | SEARCH <v1,v2,v3>]", t0);
        String sub = parts[1].toUpperCase(Locale.ROOT);
        if ("UPSERT".equals(sub) && parts.length >= 4) {
            double[] vec = parseVector(parts[3]);
            vectors.put(parts[2], vec);
            String out = "[OK] Vector '" + parts[2] + "' indexado (" + vec.length + " dims) en HNSW.";
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        }
        if ("SEARCH".equals(sub)) {
            double[] q = parseVector(parts[2]);
            StringBuilder sb = new StringBuilder("=== Similitud Coseno HNSW ===\n");
            vectors.entrySet().stream()
                .sorted((a, b) -> Double.compare(cosine(q, b.getValue()), cosine(q, a.getValue())))
                .forEach(e -> sb.append(String.format(Locale.US, "  %-12s  score=%.4f%n", e.getKey(), cosine(q, e.getValue()))));
            return okResult(cmd, db, bucket, "Búsqueda vectorial HNSW completada", sb.toString(), Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Uso: VECTOR [UPSERT <id> <v1,v2,v3> | SEARCH <v1,v2,v3>]", t0);
    }

    private ShellExecutionResult handleMath(String cmd, String db, String bucket, long t0) {
        String expr = cmd.substring(4).trim();
        if (expr.isEmpty()) return errorResult(cmd, db, bucket, "[ERROR] Uso: MATH <expresión> (ej. MATH sqrt(144) + 8 * 2)", t0);
        try {
            double result = evalMath(expr);
            String formatted = (Math.abs(result - Math.rint(result)) < 1e-9)
                ? String.valueOf((long) Math.rint(result))
                : String.format(Locale.US, "%.6f", result).replaceAll("0+$", "").replaceAll("\\.$", "");
            String out = "[MATH] " + expr + " = " + formatted;
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        } catch (Exception ex) {
            return errorResult(cmd, db, bucket, "[ERROR] Expresión matemática inválida '" + expr + "': " + ex.getMessage(), t0);
        }
    }

    private ShellExecutionResult handleStats(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 2) return errorResult(cmd, db, bucket, "[ERROR] Uso: STATS <v1,v2,v3,...>", t0);
        double[] nums = parseVector(joinFrom(parts, 1).replace(" ", ","));
        if (nums.length == 0) return errorResult(cmd, db, bucket, "[ERROR] Debe proveer al menos un valor numérico.", t0);
        double sum = 0, min = nums[0], max = nums[0];
        for (double n : nums) {
            sum += n;
            if (n < min) min = n;
            if (n > max) max = n;
        }
        double mean = sum / nums.length;
        double var = 0;
        for (double n : nums) var += (n - mean) * (n - mean);
        var /= nums.length;
        double std = Math.sqrt(var);
        String out = String.format(Locale.US,
            "[STATS] count=%d | sum=%.2f | min=%.2f | max=%.2f | mean=%.4f | stddev=%.4f",
            nums.length, sum, min, max, mean, std);
        return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
    }

    private ShellExecutionResult handleFinance(String cmd, String[] parts, String db, String bucket, long t0) {
        if (parts.length < 2) return errorResult(cmd, db, bucket, "[ERROR] Uso: FINANCE compound <principal> <rate> <periods> <years> | FINANCE npv <rate> <cf0,cf1,...>", t0);
        String sub = parts[1].toUpperCase(Locale.ROOT);
        if ("COMPOUND".equals(sub) && parts.length >= 6) {
            double p = Double.parseDouble(parts[2]);
            double r = Double.parseDouble(parts[3]);
            double n = Double.parseDouble(parts[4]);
            double t = Double.parseDouble(parts[5]);
            double amount = p * Math.pow(1.0 + r / n, n * t);
            String out = String.format(Locale.US, "[FINANCE] Interés Compuesto -> Monto Final: %.2f (Ganancia: %.2f)", amount, amount - p);
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        }
        if ("NPV".equals(sub) && parts.length >= 4) {
            double rate = Double.parseDouble(parts[2]);
            double[] cfs = parseVector(parts[3]);
            double npv = 0;
            for (int i = 0; i < cfs.length; i++) npv += cfs[i] / Math.pow(1.0 + rate, i);
            String out = String.format(Locale.US, "[FINANCE] NPV (rate=%.4f): %.2f", rate, npv);
            return okResult(cmd, db, bucket, out, out, Collections.emptyList(), t0);
        }
        return errorResult(cmd, db, bucket, "[ERROR] Sintaxis FINANCE inválida.", t0);
    }

    public List<HelpCategory> getHelpCategories() {
        List<HelpCategory> cats = new ArrayList<>();
        cats.add(new HelpCategory(
            "system", "1. Navegación, Estado y Shell", "🧭",
            List.of(
                new HelpCommand("STATUS", "Muestra telemetría general del clúster, modo Off-Heap y salud Raft.", "STATUS"),
                new HelpCommand("USE <database>[.<bucket>]", "Cambia la base de datos o el bucket activo en la consola.", "USE example_factura_db.Facturas"),
                new HelpCommand("SHOW DATABASES", "Lista todas las bases de datos y sus motores registrados.", "SHOW DATABASES"),
                new HelpCommand("SHOW BUCKETS [db]", "Lista los buckets y tipos de motor de la base de datos actual.", "SHOW BUCKETS"),
                new HelpCommand("HISTORY / !n / !!", "Lista comandos ejecutados o re-ejecuta el último (!n o !!).", "HISTORY")
            )
        ));
        cats.add(new HelpCategory(
            "cluster", "2. Administración de Nodos y Clúster Raft", "🖥️",
            List.of(
                new HelpCommand("SHOW NODES", "Muestra todos los nodos (PRIMARY, FOLLOWER, EDGE), puerto y estado real.", "SHOW NODES"),
                new HelpCommand("ADD NODE <id> <host> <port> [role]", "Registra un nuevo nodo en la topología del clúster.", "ADD NODE node-05 127.0.0.1 8775 FOLLOWER"),
                new HelpCommand("STOP NODE <id>", "Envía señal de apagado controlado vía NodeLifecycleManager a un nodo.", "STOP NODE node-04"),
                new HelpCommand("START NODE <id>", "Rehabilita el nodo y sondea su puerto TCP para marcarlo ONLINE.", "START NODE node-04"),
                new HelpCommand("MULTINODE [ON|OFF]", "Conmuta o consulta el modo distribuido Raft del clúster.", "MULTINODE ON")
            )
        ));
        cats.add(new HelpCategory(
            "crud", "3. DDL, Consultas JettraSQL / JQL y CRUD", "⚡",
            List.of(
                new HelpCommand("SELECT * FROM <bucket> [WHERE ...]", "Ejecuta consultas SQL multimodelo con predicados.", "SELECT * FROM Facturas WHERE total > 500"),
                new HelpCommand("JQL FROM <bucket> [WHERE ...]", "Ejecuta consultas nativas JettraQL sobre documentos u objetos.", "JQL FROM Facturas WHERE emisor LIKE Corp"),
                new HelpCommand("GET <recordId>", "Recupera el documento completo y versión vigente de un registro.", "GET FAC-2026-001"),
                new HelpCommand("INSERT INTO <bucket> VALUES (...)", "Inserta un nuevo registro en el bucket seleccionado.", "INSERT INTO Facturas VALUES ('FAC-900', 'Factura Demo', '{\"total\":950}')"),
                new HelpCommand("CREATE DATABASE <db> [ENGINE]", "Crea una nueva base de datos y su bucket inicial.", "CREATE DATABASE billing_demo_db DOCUMENT")
            )
        ));
        cats.add(new HelpCategory(
            "multimodel", "4. Motores Multimodelo y Analítica (KV / Graph / Math / Vector)", "🧬",
            List.of(
                new HelpCommand("KV LIST / KV PUT <k> <v> / KV GET <k>", "Almacén ultrarrápido llave-valor en memoria.", "KV LIST"),
                new HelpCommand("GRAPH LIST / GRAPH CREATE <a> <rel> <b>", "Gestiona relaciones y consulta aristas de grafos.", "GRAPH LIST"),
                new HelpCommand("VECTOR SEARCH <v1,v2,v3>", "Búsqueda de similitud coseno en índices HNSW.", "VECTOR SEARCH 0.80,0.15,0.40"),
                new HelpCommand("MATH <expresión>", "Evalúa expresiones matemáticas (sqrt, pow, sin, cos, log).", "MATH sqrt(144) + 8 * 2"),
                new HelpCommand("STATS <v1,v2,...> / FINANCE ...", "Calcula estadística descriptiva o métricas financieras.", "STATS 100,250,400,320")
            )
        ));
        return cats;
    }

    public String getHelpReferenceText() {
        StringBuilder sb = new StringBuilder();
        sb.append("===================================================================\n");
        sb.append("        JETTRASTORE SHELL - GUÍA COMPLETA DE COMANDOS\n");
        sb.append("===================================================================\n");
        for (HelpCategory cat : getHelpCategories()) {
            sb.append("\n").append(cat.icon()).append(" ").append(cat.title()).append("\n");
            for (HelpCommand hc : cat.commands()) {
                sb.append(String.format("  %-36s -> %s%n     Ejemplo: %s%n", hc.syntax(), hc.description(), hc.example()));
            }
        }
        return sb.toString();
    }

    private void recordHistory(String cmd) {
        if (commandHistory.size() >= 100) {
            commandHistory.remove(0);
        }
        commandHistory.add(cmd);
    }

    private boolean isFilterPredicateExpression(String expr) {
        if (expr == null || expr.isBlank()) return true;
        // Si contiene un operador de comparación (ej. total > 500, emisor LIKE Corp)
        return expr.contains(">") || expr.contains("<") || expr.contains("=") || expr.toUpperCase(Locale.ROOT).contains(" LIKE ");
    }

    private EngineBucketInfo findExactBucket(JettraStorePlugin plugin, String db, String bucketName) {
        for (EngineBucketInfo b : plugin.getBuckets(db)) {
            if (b.getBucketName().equalsIgnoreCase(bucketName)) {
                return b;
            }
        }
        return null;
    }

    private ShellExecutionResult okResult(String cmd, String db, String bucket, String msg, String out, List<EngineRecordInfo> recs, long t0) {
        return new ShellExecutionResult(true, cmd, db, bucket, msg, out, recs, elapsedUs(t0));
    }

    private ShellExecutionResult errorResult(String cmd, String db, String bucket, String errorMsg, long t0) {
        return new ShellExecutionResult(false, cmd, db, bucket, errorMsg, errorMsg, Collections.emptyList(), elapsedUs(t0));
    }

    private long elapsedUs(long t0) {
        return Math.max(1L, (System.nanoTime() - t0) / 1000L);
    }

    private String formatRecordsAscii(String db, String bucket, List<EngineRecordInfo> records, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Contexto: ").append(db).append(".").append(bucket).append("] ").append(summary).append("\n");
        if (records == null || records.isEmpty()) {
            sb.append("(0 registros coincidentes)");
            return sb.toString();
        }
        sb.append("+------------------+--------------+----------------------------------+-----+\n");
        sb.append("| ID               | Engine       | Summary                          | Ver |\n");
        sb.append("+------------------+--------------+----------------------------------+-----+\n");
        for (EngineRecordInfo r : records) {
            sb.append(String.format("| %-16s | %-12s | %-32s | v%-2d |\n",
                truncate(r.getId(), 16), truncate(r.getEngineType(), 12), truncate(r.getSummary(), 32), r.getVersion()));
        }
        sb.append("+------------------+--------------+----------------------------------+-----+");
        return sb.toString();
    }

    private String[] tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) inQuotes = false;
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                inQuotes = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        return tokens.toArray(new String[0]);
    }

    private String stripQuotes(String s) {
        if (s == null) return "";
        return s.replaceAll("^['\"]|['\"]$", "");
    }

    private String joinFrom(String[] parts, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private double[] parseVector(String csv) {
        String clean = csv.replace("[", "").replace("]", "").trim();
        if (clean.isEmpty()) return new double[0];
        String[] p = clean.split(",");
        double[] res = new double[p.length];
        for (int i = 0; i < p.length; i++) res[i] = Double.parseDouble(p[i].trim());
        return res;
    }

    private double cosine(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 2)) + "..";
    }

    // Evaluador descendente recursivo seguro para MATH <expr>
    private double evalMath(String raw) {
        String expr = raw.replaceAll("(?i)\\bPI\\b", String.valueOf(Math.PI))
                         .replaceAll("(?i)\\bE\\b", String.valueOf(Math.E));
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) throw new RuntimeException("Carácter inesperado: '" + (char) ch + "'");
                return x;
            }
            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else if (eat('%')) x %= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Falta paréntesis ')'");
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos, this.pos));
                } else if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') {
                    while (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') nextChar();
                    String func = expr.substring(startPos, this.pos).toLowerCase(Locale.ROOT);
                    if (eat('(')) {
                        double arg1 = parseExpression();
                        if (eat(',')) {
                            double arg2 = parseExpression();
                            if (!eat(')')) throw new RuntimeException("Falta ')' en " + func);
                            x = switch (func) {
                                case "pow" -> Math.pow(arg1, arg2);
                                case "max" -> Math.max(arg1, arg2);
                                case "min" -> Math.min(arg1, arg2);
                                default -> throw new RuntimeException("Función desconocida: " + func);
                            };
                        } else {
                            if (!eat(')')) throw new RuntimeException("Falta ')' en " + func);
                            x = switch (func) {
                                case "sqrt" -> Math.sqrt(arg1);
                                case "sin" -> Math.sin(Math.toRadians(arg1));
                                case "cos" -> Math.cos(Math.toRadians(arg1));
                                case "tan" -> Math.tan(Math.toRadians(arg1));
                                case "abs" -> Math.abs(arg1);
                                case "log" -> Math.log(arg1);
                                case "log10" -> Math.log10(arg1);
                                case "ceil" -> Math.ceil(arg1);
                                case "floor" -> Math.floor(arg1);
                                default -> throw new RuntimeException("Función desconocida: " + func);
                            };
                        }
                    } else {
                        throw new RuntimeException("Función sin paréntesis: " + func);
                    }
                } else {
                    throw new RuntimeException("Token inesperado en posición " + this.pos);
                }
                if (eat('^')) x = Math.pow(x, parseFactor());
                return x;
            }
        }.parse();
    }
}

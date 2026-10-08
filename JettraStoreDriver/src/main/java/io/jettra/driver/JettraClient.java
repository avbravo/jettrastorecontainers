package io.jettra.driver;

import io.jettra.driver.admin.JettraAdminClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.ClusterNodeDistributionInfo;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.StorageMode;
import com.jettra.memory.api.JettraMemoryEngine;
import com.jettra.memory.engine.StorageMetrics;
import io.jettra.store.police.JettraPolice;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.query.JettraSQLProcessor;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.police.JettraPoliceNotification;
import io.jettra.store.security.JettraSecurityManager;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JettraClient implements AutoCloseable {
    private final JettraClientConfig config;
    private final ConcurrentHashMap<String, JettraDatabase> databases = new ConcurrentHashMap<>();
    private final JettraAdminClient adminClient;
    private final String sessionToken;
    private final JettraSecurityManager securityManager = new JettraSecurityManager();
    private final DynamicRingEngine ringEngine;
    private final List<JettraPoliceEventListener> policeListeners = new CopyOnWriteArrayList<>();
    private final Consumer<JettraPoliceNotification> policeNotificationListener;

    public JettraClient(JettraClientConfig config) {
        this.config = config;
        this.sessionToken = securityManager.authenticate(config.getUsername(), config.getPassword());
        this.adminClient = new JettraAdminClient(sessionToken);
        JettraStoreConfig storeCfg = null;
        try {
            storeCfg = JettraStoreConfig.load();
        } catch (Exception ignored) {}

        String localNodeId = (storeCfg != null) ? storeCfg.getNodeId() : "node-01";
        double satThreshold = (storeCfg != null) ? (storeCfg.getRingSaturationThresholdPercent() / 100.0) : 0.85;
        double relThreshold = (storeCfg != null) ? (storeCfg.getRingReleaseTargetPercent() / 100.0) : 0.45;
        this.ringEngine = new DynamicRingEngine(localNodeId, satThreshold, relThreshold, config.isClusterMultinodeActive());

        // Registrar nodos pares desde jettra.config si cluster.multinode.active está habilitado (on)
        if (config.isClusterMultinodeActive()) {
            if (storeCfg != null && !storeCfg.getParsedPeers().isEmpty()) {
                for (ClusterNode peer : storeCfg.getParsedPeers()) {
                    this.ringEngine.registerPeer(peer);
                }
            } else {
                this.ringEngine.registerPeer(new ClusterNode("node-02", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
                this.ringEngine.registerPeer(new ClusterNode("node-03", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
            }
        }

        this.policeNotificationListener = this::dispatchPoliceEvent;
        JettraPolice.getInstance().addNotificationListener(this.policeNotificationListener);
        registerAutoFailoverHandler();
    }

    public static JettraClient connect(String host, int port, String user, String pass) {
        boolean multinode = true;
        try {
            multinode = JettraStoreConfig.load().isClusterMultinodeActive();
        } catch (Exception ignored) {}
        JettraClientConfig cfg = JettraClientConfig.builder()
            .addClusterNode(host, port)
            .credentials(user, pass)
            .clusterMultinodeActive(multinode)
            .build();
        return new JettraClient(cfg);
    }

    public static JettraClient connect(JettraClientConfig config) {
        return new JettraClient(config);
    }

    public java.util.List<String> listDatabases() {
        Set<String> result = new TreeSet<>(databases.keySet());

        // 1. Escanear rutas físicas configuradas en database.properties y locales
        JettraStoreConfig cfg = JettraStoreConfig.load();
        scanDatabasesFromPath(cfg.getStoragePath(), result);
        scanDatabasesFromPath(cfg.getConfiguredStoragePath(), result);
        scanDatabasesFromPath("./data/jettra", result);
        scanDatabasesFromPath("data/jettra", result);
        scanDatabasesFromPath("../data/jettra", result);
        scanDatabasesFromPath("/jettra/data", result);

        return new ArrayList<>(result);
    }

    private void scanDatabasesFromPath(String pathStr, Set<String> target) {
        if (pathStr == null || pathStr.isBlank()) return;
        try {
            Path p = Path.of(pathStr);
            if (Files.exists(p) && Files.isDirectory(p)) {
                try (var stream = Files.list(p)) {
                    stream.forEach(entry -> {
                        String name = entry.getFileName().toString();
                        if (Files.isDirectory(entry)) {
                            if (!name.startsWith(".")) {
                                target.add(name);
                            }
                        } else if (name.endsWith("_sstable.jettra")) {
                            target.add(name.substring(0, name.indexOf("_sstable.jettra")));
                        } else if (name.endsWith(".jettra") && !name.contains("_wal")) {
                            target.add(name.substring(0, name.indexOf(".jettra")));
                        } else if (name.endsWith("_meta.json")) {
                            target.add(name.substring(0, name.indexOf("_meta.json")));
                        }
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean dropDatabase(String name) {
        if (name == null || name.isBlank()) return false;
        JettraStoreConfig scfg = JettraStoreConfig.load();
        if (scfg.isClusterMultinodeActive() && scfg.getNodeRole() == io.jettra.store.cluster.ClusterNode.Role.SECONDARY) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite eliminar bases de datos.",
                scfg.getNodeId()
            ));
        }
        boolean inMemory = false;
        JettraDatabase db = databases.remove(name);
        if (db != null) {
            inMemory = true;
            try {
                db.drop();
            } catch (Exception ignored) {}
        }

        boolean onDisk = deletePhysicalDatabase(name.trim());
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            io.jettra.store.JettraStoreServer.getActiveInstance().dropDatabase(name);
        }
        return inMemory || onDisk;
    }

    private boolean deletePhysicalDatabase(String name) {
        JettraStoreConfig cfg = JettraStoreConfig.load();
        Set<String> searchPaths = new LinkedHashSet<>();
        if (cfg.getStoragePath() != null) searchPaths.add(cfg.getStoragePath());
        if (cfg.getConfiguredStoragePath() != null) searchPaths.add(cfg.getConfiguredStoragePath());
        searchPaths.add("./data/jettra");
        searchPaths.add("data/jettra");
        searchPaths.add("../data/jettra");
        searchPaths.add("../../data/jettra");
        searchPaths.add("/jettra/data");
        searchPaths.add(System.getProperty("user.home") + "/jettra/data");

        boolean deleted = false;
        for (String pathStr : searchPaths) {
            try {
                Path p = Path.of(pathStr);
                if (!Files.exists(p)) continue;

                // 1. Si es directorio con el nombre de la BD
                Path dbDir = p.resolve(name);
                if (Files.exists(dbDir)) {
                    if (Files.isDirectory(dbDir)) {
                        try (var stream = Files.walk(dbDir)) {
                            stream.sorted(Comparator.reverseOrder())
                                  .forEach(f -> {
                                      try { Files.deleteIfExists(f); } catch (Exception ignored) {}
                                  });
                        }
                        deleted = true;
                    } else {
                        deleted |= Files.deleteIfExists(dbDir);
                    }
                }

                // 2. Archivos asociados
                deleted |= Files.deleteIfExists(p.resolve(name + "_sstable.jettra"));
                deleted |= Files.deleteIfExists(p.resolve(name + ".jettra"));
                deleted |= Files.deleteIfExists(p.resolve(name + "_wal.jettra"));
                deleted |= Files.deleteIfExists(p.resolve(name + "_meta.json"));
                deleted |= Files.deleteIfExists(p.resolve(name + "_sstable" + cfg.getFileExtension()));
                deleted |= Files.deleteIfExists(p.resolve(name + cfg.getFileExtension()));
                deleted |= Files.deleteIfExists(p.resolve(name + ".snap"));
                deleted |= Files.deleteIfExists(p.resolve(name + "_backup.snap"));

            } catch (Exception ignored) {}
        }
        return deleted;
    }

    public boolean isDatabaseLoaded(String name) {
        return name != null && databases.containsKey(name);
    }

    public int getLightweightCollectionCount(String dbName) {
        if (dbName == null) return 0;
        if (databases.containsKey(dbName)) {
            return databases.get(dbName).getAllCollectionNames().size();
        }
        return io.jettra.store.core.JettraDatabase.getLightweightCollectionCount(dbName, JettraStoreConfig.load());
    }

    public boolean databaseExists(String name) {
        return databases.containsKey(name) || listDatabases().contains(name);
    }

    public JettraDatabase getDatabase(String name) {
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            return io.jettra.store.JettraStoreServer.getActiveInstance().getOrCreateDatabase(name);
        }
        boolean isNew = !databases.containsKey(name);
        JettraDatabase db = databases.computeIfAbsent(name, k -> new JettraDatabase(k, JettraStoreConfig.load(), ringEngine));
        if (isNew && config.isClusterMultinodeActive()) {
            if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
                io.jettra.store.JettraStoreServer.getActiveInstance().getOrCreateDatabase(name);
            }
        }
        return db;
    }

    private boolean triggerServerDatabaseDistribution(String databaseName) {
        try {
            String host = "127.0.0.1";
            int restPort = 8080;
            JettraStoreConfig scfg = JettraStoreConfig.load();
            if (scfg != null) {
                if (scfg.getNodeIp() != null && !scfg.getNodeIp().isBlank()) {
                    host = scfg.getNodeIp();
                }
                if (scfg.getRestPort() > 0) {
                    restPort = scfg.getRestPort();
                }
            }
            if (!config.getClusterEndpoints().isEmpty()) {
                String ep = config.getClusterEndpoints().get(0);
                if (ep.contains(":")) {
                    host = ep.substring(0, ep.indexOf(':'));
                } else {
                    host = ep;
                }
            }

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(800))
                .build();
            String jsonPayload = String.format("{\"op\":\"DISTRIBUTE_DATABASE\",\"database\":\"%s\"}", databaseName);
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(String.format("http://%s:%d/api/v1/cluster/replicate", host, restPort)))
                .timeout(java.time.Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload));
            if (sessionToken != null && !sessionToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + sessionToken);
            }
            java.net.http.HttpResponse<String> resp = httpClient.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Distribuye la base de datos especificada y todos sus registros internos,
     * colecciones e índices a todos los nodos del clúster Raft.
     */
    public boolean clusterDistributed(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) return false;
        JettraStoreConfig scfg = JettraStoreConfig.load();
        if (scfg.isClusterMultinodeActive() && scfg.getNodeRole() == ClusterNode.Role.SECONDARY) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY NODE] El nodo actual '%s' tiene rol SECUNDARIO. Las distribuciones deben iniciarse desde el nodo PRIMARIO.",
                scfg.getNodeId()
            ));
        }

        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            return io.jettra.store.JettraStoreServer.getActiveInstance().distributeDatabase(databaseName);
        }

        // Si el cliente corre en un proceso independiente, delegar vía REST al servidor primario activo
        if (triggerServerDatabaseDistribution(databaseName)) {
            return true;
        }

        JettraDatabase db = getDatabase(databaseName);
        if (db != null) {
            try { db.flushMemTable(); } catch (Exception ignored) {}
            db.saveToDisk();
        }
        byte[] payload = new byte[0];
        Path meta = JettraDatabase.resolveMetaFile(databaseName, scfg);
        if (meta != null && Files.exists(meta)) {
            try {
                payload = Files.readAllBytes(meta);
            } catch (Exception ignored) {}
        }

        var peers = ringEngine.getPeers();
        if (!peers.isEmpty()) {
            try (var replClient = new io.jettra.store.cluster.JettraClusterReplicationClient(ringEngine.getNodeId(), peers)) {
                return replClient.broadcastDistributeDatabase(databaseName, payload);
            } catch (Exception ignored) {}
        }
        return true;
    }

    /**
     * Distribuye todas las bases de datos registradas y sus registros a todos los nodos del clúster.
     */
    public Map<String, Boolean> clusterDistributedAll() {
        JettraStoreConfig scfg = JettraStoreConfig.load();
        if (scfg.isClusterMultinodeActive() && scfg.getNodeRole() == ClusterNode.Role.SECONDARY) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY NODE] El nodo actual '%s' tiene rol SECUNDARIO. Las distribuciones deben iniciarse desde el nodo PRIMARIO.",
                scfg.getNodeId()
            ));
        }

        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            return io.jettra.store.JettraStoreServer.getActiveInstance().distributeAllDatabases();
        }

        if (triggerServerDatabaseDistribution("all")) {
            Map<String, Boolean> res = new LinkedHashMap<>();
            for (String db : listDatabases()) {
                res.put(db, true);
            }
            return res;
        }

        Map<String, Boolean> results = new LinkedHashMap<>();
        List<String> dbs = listDatabases();
        for (String db : dbs) {
            results.put(db, clusterDistributed(db));
        }
        return results;
    }

    /**
     * Obtiene una lista detallada con los nodos del clúster y las bases de datos presentes en cada uno.
     */
    public List<ClusterNodeDistributionInfo> getClusterDistributedInfo() {
        JettraStoreConfig scfg = JettraStoreConfig.load();
        String localId = (scfg != null) ? scfg.getNodeId() : ringEngine.getNodeId();
        String localIp = "127.0.0.1";
        if (scfg != null && scfg.getNodeIp() != null && !scfg.getNodeIp().isBlank()) {
            localIp = scfg.getNodeIp();
        } else if (!config.getClusterEndpoints().isEmpty()) {
            String ep = config.getClusterEndpoints().get(0);
            localIp = ep.contains(":") ? ep.substring(0, ep.indexOf(':')) : ep;
        }
        int localPort = (scfg != null && scfg.getGrpcPort() > 0) ? scfg.getGrpcPort() : 9091;
        String localRole = (scfg != null && scfg.getNodeRole() != null) ? scfg.getNodeRole().name() : "PRIMARY";
        List<String> localDbs = listDatabases();

        var peers = ringEngine.getPeers();
        try (var replClient = new io.jettra.store.cluster.JettraClusterReplicationClient(localId, peers)) {
            return replClient.getClusterDistributionInfo(localIp, localPort, localRole, localDbs);
        } catch (Exception e) {
            List<ClusterNodeDistributionInfo> fallback = new ArrayList<>();
            fallback.add(new ClusterNodeDistributionInfo(localId, localIp, localPort, localRole, "RUNNING", localDbs.size(), localDbs));
            for (var p : peers) {
                fallback.add(new ClusterNodeDistributionInfo(p.getId(), p.getIp(), p.getPort(), p.getRole().name(), "UNKNOWN", 0, List.of()));
            }
            return fallback;
        }
    }

    public void replicatePutDocument(String dbName, String colName, String id, Map<String, Object> doc) {
        if (!config.isClusterMultinodeActive()) return;
        byte[] jsonBytes = new io.jettra.json.JettraJson().toJson(doc).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            io.jettra.store.JettraStoreServer.getActiveInstance().replicatePutDocument(dbName, colName, id, jsonBytes);
        }
    }

    public void replicateDeleteDocument(String dbName, String colName, String id) {
        if (!config.isClusterMultinodeActive()) return;
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            io.jettra.store.JettraStoreServer.getActiveInstance().replicateDeleteDocument(dbName, colName, id);
        }
    }

    public void replicateCreateIndex(String dbName, String colName, String indexName, String field, String type, boolean unique) {
        if (!config.isClusterMultinodeActive()) return;
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            io.jettra.store.JettraStoreServer.getActiveInstance().replicateCreateIndex(dbName, colName, indexName, field, type, unique);
        }
    }

    public void replicateDropIndex(String dbName, String indexName) {
        if (!config.isClusterMultinodeActive()) return;
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            io.jettra.store.JettraStoreServer.getActiveInstance().replicateDropIndex(dbName, indexName);
        }
    }

    public List<io.jettra.store.cluster.ClusterLiveEvent> getClusterLiveEvents() {
        return getRecentClusterLiveEvents(100);
    }

    private List<io.jettra.store.cluster.ClusterLiveEvent> fetchRemoteClusterLiveEvents(int limit) {
        try {
            String host = "127.0.0.1";
            int restPort = 8080;
            JettraStoreConfig scfg = JettraStoreConfig.load();
            if (scfg != null) {
                if (scfg.getNodeIp() != null && !scfg.getNodeIp().isBlank()) {
                    host = scfg.getNodeIp();
                }
                if (scfg.getRestPort() > 0) {
                    restPort = scfg.getRestPort();
                }
            }
            if (!config.getClusterEndpoints().isEmpty()) {
                String ep = config.getClusterEndpoints().get(0);
                if (ep.contains(":")) {
                    host = ep.substring(0, ep.indexOf(':'));
                } else {
                    host = ep;
                }
            }

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(800))
                .build();
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(String.format("http://%s:%d/api/v1/cluster/events?limit=%d", host, restPort, limit)))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET();
            if (sessionToken != null && !sessionToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + sessionToken);
            }
            var resp = httpClient.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                List<Map<String, Object>> parsedList = new io.jettra.json.JettraJson().fromJson(resp.body(), List.class);
                if (parsedList != null) {
                    List<io.jettra.store.cluster.ClusterLiveEvent> result = new ArrayList<>();
                    for (Map<String, Object> map : parsedList) {
                        long ts = (map.get("timestamp") instanceof Number n) ? n.longValue() : System.currentTimeMillis();
                        String type = (String) map.get("type");
                        String src = (String) map.get("source");
                        String tgt = (String) map.get("target");
                        String msg = (String) map.get("message");
                        String details = (String) map.get("details");
                        result.add(new io.jettra.store.cluster.ClusterLiveEvent(ts, type, src, tgt, msg, details));
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    public List<io.jettra.store.cluster.ClusterLiveEvent> getRecentClusterLiveEvents(int limit) {
        if (io.jettra.store.JettraStoreServer.getActiveInstance() != null) {
            return io.jettra.store.cluster.JettraClusterEventBus.getInstance().getRecentEvents(limit);
        }
        List<io.jettra.store.cluster.ClusterLiveEvent> remoteEvents = fetchRemoteClusterLiveEvents(limit);
        if (!remoteEvents.isEmpty()) {
            return remoteEvents;
        }
        return io.jettra.store.cluster.JettraClusterEventBus.getInstance().getRecentEvents(limit);
    }

    public String clusterLive() {
        return clusterLive(25);
    }

    public String clusterLive(int limit) {
        List<io.jettra.store.cluster.ClusterLiveEvent> events = getRecentClusterLiveEvents(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("========================================================================================================================\n");
        sb.append("                                        JETTRASTORE CLUSTER LIVE EVENT STREAM                                           \n");
        sb.append("========================================================================================================================\n");
        sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
        sb.append("| Marca Temporal          | Tipo de Evento       | Origen  | Destino | Mensaje / Trazabilidad Operativa                |\n");
        sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
        if (events == null || events.isEmpty()) {
            sb.append("| (Sin eventos recientes de clúster registrados en el búfer)                                                           |\n");
        } else {
            for (var ev : events) {
                String msg = ev.message() != null ? ev.message() : "";
                if (msg.length() > 47) {
                    msg = msg.substring(0, 44) + "...";
                }
                String typeStr = ev.type() != null ? ev.type() : "";
                if (typeStr.length() > 20) {
                    typeStr = typeStr.substring(0, 17) + "...";
                }
                String src = ev.sourceNodeId() != null ? ev.sourceNodeId() : "-";
                String tgt = ev.targetNodeId() != null ? ev.targetNodeId() : "-";
                sb.append(String.format("| %-23s | %-20s | %-7s | %-7s | %-47s |\n",
                    ev.formattedTimestamp(), typeStr, src, tgt, msg));
            }
        }
        sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
        sb.append(String.format("Eventos en búfer mostrados: %d | Canal de Eventos en Tiempo Real: ACTIVO\n", events != null ? events.size() : 0));
        return sb.toString();
    }

    public void subscribeClusterLive(Consumer<io.jettra.store.cluster.ClusterLiveEvent> listener) {
        io.jettra.store.cluster.JettraClusterEventBus.getInstance().subscribe(listener);
    }

    public void unsubscribeClusterLive(Consumer<io.jettra.store.cluster.ClusterLiveEvent> listener) {
        io.jettra.store.cluster.JettraClusterEventBus.getInstance().unsubscribe(listener);
    }

    public void registerAutoFailoverHandler() {
        subscribeClusterLive(event -> {
            if (io.jettra.store.cluster.ClusterLiveEvent.TYPE_LEADER_PROMOTED.equals(event.type())) {
                String newLeader = event.sourceNodeId();
                if (newLeader != null && ringEngine != null) {
                    for (ClusterNode p : ringEngine.getPeers()) {
                        if (p.getId().equalsIgnoreCase(newLeader)) {
                            p.setRole(ClusterNode.Role.PRIMARY);
                            p.start();
                        } else if (p.getRole() == ClusterNode.Role.PRIMARY) {
                            p.setRole(ClusterNode.Role.SECONDARY);
                        }
                    }
                }
            }
        });
    }

    public io.jettra.store.engine.query.JettraQLProcessor.JQLResult jql(String databaseName, String query) {
        JettraDatabase db = getDatabase(databaseName);
        io.jettra.store.engine.query.JettraQLProcessor processor = new io.jettra.store.engine.query.JettraQLProcessor(db);
        return processor.execute(query);
    }

    /**
     * Ejecuta una consulta SQL paginada de forma segura garantizando control de memoria Heap.
     *
     * @param databaseName Nombre de la base de datos
     * @param query Sentencia SQL (ej. SELECT * FROM clientes)
     * @param page Número de página (1-based)
     * @param pageSize Tamaño del lote por página
     * @return Resultado de la consulta con filas del lote actual y resumen
     */
    public JettraSQLProcessor.QueryResult sqlPaged(String databaseName, String query, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 5000));
        int offset = (safePage - 1) * safeSize;
        String clean = query.replaceAll("(?i)\\s+LIMIT\\s+\\d+(\\s+OFFSET\\s+\\d+)?", "").trim();
        String pagedQuery = clean + " LIMIT " + safeSize + " OFFSET " + offset;
        return sql(databaseName, pagedQuery);
    }

    /**
     * Crea un cursor de carga perezosa distribuida (Lazy Paged Cursor) para iterar colecciones
     * masivas página por página sin sobrecargar el Heap y permitiendo recolección de basura O(1).
     *
     * @param databaseName Nombre de la base de datos
     * @param collection Nombre de la colección o bucket
     * @param pageSize Tamaño de página
     * @return Cursor perezoso autónomo
     */
    public io.jettra.store.police.JettraPolice.LazyPagedCursor<Map<String, Object>> cursor(String databaseName, String collection, int pageSize) {
        JettraDatabase db = getDatabase(databaseName);
        var engine = db.getDocumentEngine(collection);
        int safeSize = Math.max(1, Math.min(pageSize, 5000));
        return new io.jettra.store.police.JettraPolice.LazyPagedCursor<>(safeSize, (offset, limit) -> {
            List<Map<String, Object>> batch = new ArrayList<>(limit);
            if (engine == null || engine.isEmpty()) return batch;
            int current = 0;
            for (Map<String, Object> doc : engine) {
                if (current >= offset && batch.size() < limit) {
                    batch.add(doc);
                }
                current++;
                if (batch.size() >= limit) break;
            }
            return batch;
        });
    }

    /**
     * Acceso al centinela supervisor de estabilidad y telemetría de memoria.
     */
    public void addPoliceEventListener(JettraPoliceEventListener listener) {
        if (listener != null) {
            policeListeners.add(listener);
        }
    }

    public void removePoliceEventListener(JettraPoliceEventListener listener) {
        policeListeners.remove(listener);
    }

    public void dispatchPoliceEvent(JettraPoliceNotification notification) {
        if (notification != null) {
            for (JettraPoliceEventListener listener : policeListeners) {
                try {
                    listener.onSentinelActivated(notification);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Retorna un flujo continuo de chunks de documentos procesados de forma segura
     * particionados en lotes liberables para Garbage Collection, con metadatos de Sentinel.
     */
    public StreamResponse<Map<String, Object>> streamFindAll(String databaseName, String collectionName) {
        return streamFindAll(databaseName, collectionName, 0);
    }

    public StreamResponse<Map<String, Object>> streamFindAll(String databaseName, String collectionName, int limit) {
        JettraDatabase db = getDatabase(databaseName);
        StreamResponse<Map<String, Object>> stream = db.streamCollection(collectionName, limit);
        if (stream.isSentinelActivated()) {
            dispatchPoliceEvent(stream.getNotification());
        }
        return stream;
    }

    /**
     * Recupera todos los documentos de una colección consumiendo de forma transparente
     * el flujo de chunks del servidor y ensamblándolos progresivamente para proteger el Heap.
     */
    public List<Map<String, Object>> findAll(String databaseName, String collectionName) {
        StreamResponse<Map<String, Object>> stream = streamFindAll(databaseName, collectionName);
        return stream.collectAll();
    }

    public io.jettra.store.police.JettraPolice getPolice() {
        return io.jettra.store.police.JettraPolice.getInstance();
    }

    /**
     * Evalúa de forma predictiva si una consulta sobre una colección causaría riesgo de OOM en el Heap.
     */
    public io.jettra.store.police.JettraPolice.PoliceDecision evaluateQuerySafety(String databaseName, String collection, int requestedLimit) {
        JettraDatabase db = getDatabase(databaseName);
        long count = db.getDocumentEngine(collection) != null ? db.getDocumentEngine(collection).count() : 0;
        return io.jettra.store.police.JettraPolice.getInstance().evaluateHeapSafety("DRIVER_EVALUATE", collection, count, requestedLimit, 512L);
    }

    public JettraSQLProcessor.QueryResult sql(String databaseName, String query) {
        JettraDatabase db = getDatabase(databaseName);
        JettraSQLProcessor processor = new JettraSQLProcessor(db);
        return processor.execute(query);
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregate(
            String databaseName, 
            String collection, 
            List<String> groupByFields, 
            List<io.jettra.store.calc.JettraAggregation.AggregateSpec> specs) {
        return getDatabase(databaseName).aggregate(collection, groupByFields, specs);
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregate(
            String databaseName, 
            String collection, 
            String groupByField, 
            io.jettra.store.calc.JettraAggregation.AggregateSpec... specs) {
        return getDatabase(databaseName).aggregate(collection, groupByField, specs);
    }

    public double evalMath(String expression) {
        return io.jettra.store.calc.JettraMath.eval(expression);
    }

    public double evalMath(String expression, Map<String, Double> variables) {
        return io.jettra.store.calc.JettraMath.eval(expression, variables);
    }

    public io.jettra.store.calc.JettraStatistics.StatsSummary statsSummary(List<? extends Number> data) {
        return io.jettra.store.calc.JettraStatistics.summary(data);
    }

    public double pmt(double rate, int nper, double pv) {
        return io.jettra.store.calc.JettraFinance.pmt(rate, nper, pv);
    }

    public double fv(double rate, int nper, double pmt, double pv) {
        return io.jettra.store.calc.JettraFinance.fv(rate, nper, pmt, pv);
    }

    public double roi(double gain, double cost) {
        return io.jettra.store.calc.JettraFinance.roi(gain, cost);
    }

    public List<io.jettra.store.calc.JettraFinance.AmortizationRow> amortizationSchedule(double principal, double annualRate, int periods) {
        return io.jettra.store.calc.JettraFinance.amortizationSchedule(principal, annualRate, periods);
    }

    public float dotProduct(float[] v1, float[] v2) {
        return io.jettra.store.calc.JettraVectorMath.dotProduct(v1, v2);
    }

    public float cosineSimilarity(float[] v1, float[] v2) {
        return io.jettra.store.calc.JettraVectorMath.cosineSimilarity(v1, v2);
    }

    public float euclideanDistance(float[] v1, float[] v2) {
        return io.jettra.store.calc.JettraVectorMath.euclideanDistance(v1, v2);
    }

    // --- Métodos de Agregación de Alto Nivel ---
    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateSum(String db, String col, String field, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("SUM", field, "total_" + field));
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateAvg(String db, String col, String field, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("AVG", field, "avg_" + field));
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateMin(String db, String col, String field, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("MIN", field, "min_" + field));
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateMax(String db, String col, String field, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("MAX", field, "max_" + field));
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateCount(String db, String col, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("COUNT", "*", "total_count"));
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregateMedian(String db, String col, String field, String groupBy) {
        return aggregate(db, col, groupBy, new io.jettra.store.calc.JettraAggregation.AggregateSpec("MEDIAN", field, "median_" + field));
    }

    // --- Métodos Matemáticos ---
    public double sqrt(double x) { return io.jettra.store.calc.JettraMath.sqrt(x); }
    public double cbrt(double x) { return io.jettra.store.calc.JettraMath.cbrt(x); }
    public double pow(double b, double e) { return io.jettra.store.calc.JettraMath.pow(b, e); }
    public double round(double x, int d) { return io.jettra.store.calc.JettraMath.round(x, d); }
    public long factorial(int n) { return io.jettra.store.calc.JettraMath.factorial(n); }
    public long gcd(long a, long b) { return io.jettra.store.calc.JettraMath.gcd(a, b); }
    public long lcm(long a, long b) { return io.jettra.store.calc.JettraMath.lcm(a, b); }
    public double hypot(double x, double y) { return io.jettra.store.calc.JettraMath.hypot(x, y); }

    // --- Métodos Financieros ---
    public double pv(double rate, int nper, double pmt, double fv) {
        return io.jettra.store.calc.JettraFinance.pv(rate, nper, pmt, fv);
    }
    public double npv(double rate, double... cashFlows) {
        return io.jettra.store.calc.JettraFinance.npv(rate, cashFlows);
    }
    public double irr(double... cashFlows) {
        return io.jettra.store.calc.JettraFinance.irr(cashFlows);
    }
    public double cagr(double beginningValue, double endingValue, double periods) {
        return io.jettra.store.calc.JettraFinance.cagr(beginningValue, endingValue, periods);
    }
    public double compoundInterest(double principal, double annualRate, int compoundsPerYear, double years) {
        return io.jettra.store.calc.JettraFinance.compoundInterest(principal, annualRate, compoundsPerYear, years);
    }
    public double simpleInterest(double principal, double annualRate, double years) {
        return io.jettra.store.calc.JettraFinance.simpleInterest(principal, annualRate, years);
    }
    public double depreciationStraightLine(double cost, double salvageValue, int lifeYears) {
        return io.jettra.store.calc.JettraFinance.depreciationStraightLine(cost, salvageValue, lifeYears);
    }
    public double paybackPeriod(double initialInvestment, double... annualInflows) {
        return io.jettra.store.calc.JettraFinance.paybackPeriod(initialInvestment, annualInflows);
    }
    public double mirr(double financeRate, double reinvestRate, double... cashFlows) {
        return io.jettra.store.calc.JettraFinance.mirr(financeRate, reinvestRate, cashFlows);
    }

    // --- Métodos Estadísticos ---
    public double statsMean(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.mean(data); }
    public double statsMedian(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.median(data); }
    public double statsMode(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.mode(data); }
    public double statsStdDev(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.stddev(data, true); }
    public double statsVariance(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.variance(data, true); }
    public double statsPercentile(List<? extends Number> data, double p) { return io.jettra.store.calc.JettraStatistics.percentile(data, p); }
    public double statsSkewness(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.skewness(data); }
    public double statsKurtosis(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.kurtosis(data); }
    public double statsIqr(List<? extends Number> data) { return io.jettra.store.calc.JettraStatistics.iqr(data); }
    public double statsCorrelation(List<? extends Number> x, List<? extends Number> y) { return io.jettra.store.calc.JettraStatistics.correlation(x, y); }
    public double statsCovariance(List<? extends Number> x, List<? extends Number> y) { return io.jettra.store.calc.JettraStatistics.covariance(x, y, true); }
    public io.jettra.store.calc.JettraStatistics.RegressionResult statsLinearRegression(List<? extends Number> x, List<? extends Number> y) {
        return io.jettra.store.calc.JettraStatistics.linearRegression(x, y);
    }

    // --- Métodos Vectoriales ---
    public float manhattanDistance(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.manhattanDistance(v1, v2); }
    public float chebyshevDistance(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.chebyshevDistance(v1, v2); }
    public float norm(float[] v) { return io.jettra.store.calc.JettraVectorMath.norm(v); }
    public float[] normalize(float[] v) { return io.jettra.store.calc.JettraVectorMath.normalize(v); }
    public float[] vectorAdd(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.add(v1, v2); }
    public float[] vectorSubtract(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.subtract(v1, v2); }
    public float[] vectorMultiply(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.multiply(v1, v2); }
    public double vectorAngle(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.angle(v1, v2); }
    public double vectorAngleDegrees(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.angleDegrees(v1, v2); }
    public float[] crossProduct(float[] v1, float[] v2) { return io.jettra.store.calc.JettraVectorMath.crossProduct(v1, v2); }
    public float[] projection(float[] v, float[] onto) { return io.jettra.store.calc.JettraVectorMath.projection(v, onto); }
    public float[] centroid(List<float[]> vectors) { return io.jettra.store.calc.JettraVectorMath.centroid(vectors); }

    public JettraAdminClient admin() {
        return adminClient;
    }

    public JettraSecurityManager getSecurityManager() {
        return securityManager;
    }

    public boolean isClusterMultinodeActive() {
        return config.isClusterMultinodeActive();
    }

    public String getClusterMultinodeActive() {
        return config.getClusterMultinodeActive();
    }

    public DynamicRingEngine getRingEngine() {
        return ringEngine;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public JettraClientConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        JettraPolice.getInstance().removeNotificationListener(this.policeNotificationListener);
        for (JettraDatabase db : databases.values()) {
            try {
                db.close();
            } catch (Exception ignored) {}
        }
        databases.clear();
    }

    public JettraMemoryEngine getMemoryEngine(String databaseName) {
        return getDatabase(databaseName).getMemoryEngine();
    }

    public void putBinary(String databaseName, String key, byte[] data) throws java.io.IOException {
        getDatabase(databaseName).putOffHeapBinary(key, data);
    }

    public byte[] getBinary(String databaseName, String key) throws java.io.IOException {
        return getDatabase(databaseName).getOffHeapBinary(key);
    }

    public StorageMetrics getMemoryMetrics(String databaseName) {
        return getDatabase(databaseName).getMemoryMetrics();
    }

    public boolean compactMemory(String databaseName) throws Exception {
        var engine = getDatabase(databaseName).getMemoryEngine();
        if (engine != null) {
            engine.compact();
            return true;
        }
        return false;
    }

    public List<JettraPolice.PoliceAlert> getPoliceAlerts() {
        return JettraPolice.getInstance().getAlerts();
    }

    public boolean isPoliceActive() {
        return JettraPolice.getInstance().isActive();
    }


    public StorageMode getStorageMode(String databaseName) {
        return getDatabase(databaseName).getStorageMode();
    }

    public void setStorageMode(String databaseName, StorageMode mode) {
        getDatabase(databaseName).setStorageMode(mode);
    }

    public void setGlobalStorageMode(StorageMode mode) {
        for (JettraDatabase db : databases.values()) {
            db.setStorageMode(mode);
        }
    }

    public Set<String> getCollectionNames(String databaseName) {
        return getDatabase(databaseName).getAllCollectionNames();
    }

    public Set<String> getDocumentEngineNames(String databaseName) {
        return getDatabase(databaseName).getDocumentEngineNames();
    }

    public Set<String> getKeyValueEngineNames(String databaseName) {
        return getDatabase(databaseName).getKeyValueEngineNames();
    }

    public Set<String> getVectorEngineNames(String databaseName) {
        return getDatabase(databaseName).getVectorEngineNames();
    }

    public Set<String> getGraphEngineNames(String databaseName) {
        return getDatabase(databaseName).getGraphEngineNames();
    }

    public Set<String> getTimeSeriesEngineNames(String databaseName) {
        return getDatabase(databaseName).getTimeSeriesEngineNames();
    }

    public Set<String> getGeospatialEngineNames(String databaseName) {
        return getDatabase(databaseName).getGeospatialEngineNames();
    }

    public Set<String> getColumnarEngineNames(String databaseName) {
        return getDatabase(databaseName).getColumnarEngineNames();
    }

    public Set<String> getRecordsEngineNames(String databaseName) {
        return getDatabase(databaseName).getRecordsEngineNames();
    }

    public <T extends Record> io.jettra.store.engine.models.RecordsEngine<T> getRecordsEngine(String databaseName, String entityName, Class<T> recordClass) {
        return getDatabase(databaseName).getRecordsEngine(entityName, recordClass);
    }

    public long count(String databaseName, String bucketName) {
        var db = getDatabase(databaseName);
        if (db.getDocumentEngineNames().contains(bucketName)) {
            return db.getDocumentEngine(bucketName).count();
        }
        return 0;
    }

    public List<Map<String, Object>> getDocuments(String databaseName, String bucketName, int offset, int limit) {
        var db = getDatabase(databaseName);
        List<Map<String, Object>> result = new ArrayList<>();
        if (db.getDocumentEngineNames().contains(bucketName)) {
            var engine = db.getDocumentEngine(bucketName);
            int current = 0;
            for (Map<String, Object> doc : engine) {
                if (current >= offset && result.size() < limit) {
                    result.add(doc);
                }
                current++;
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    public void insertDocument(String databaseName, String bucketName, String id, Map<String, Object> data) {
        getDatabase(databaseName).getDocumentEngine(bucketName).insert(id, data);
    }

    public void createBucket(String databaseName, String bucketName, String engineType) {
        var db = getDatabase(databaseName);
        switch (engineType != null ? engineType.toUpperCase() : "DOCUMENT") {
            case "VECTOR" -> db.getVectorEngine(bucketName, 3);
            case "GRAPH" -> db.getGraphEngine(bucketName);
            case "TIMESERIES" -> db.getTimeSeriesEngine(bucketName);
            case "KEYVALUE" -> db.getKeyValueEngine(bucketName);
            default -> db.getDocumentEngine(bucketName);
        }
    }

    public boolean dropBucket(String databaseName, String bucketName) {
        return getDatabase(databaseName).dropCollection(bucketName);
    }

    public boolean deleteDocument(String databaseName, String bucketName, String id) {
        var db = getDatabase(databaseName);
        if (db.getDocumentEngineNames().contains(bucketName)) {
            return db.getDocumentEngine(bucketName).delete(id);
        } else if (db.getKeyValueEngineNames().contains(bucketName)) {
            return db.getKeyValueEngine(bucketName).remove(id);
        }
        return false;
    }

    public boolean createDatabase(String name) {
        if (name == null || name.isBlank()) return false;
        getDatabase(name.trim());
        return true;
    }

    public record BucketRecord(String id, String summary, String references) {}

    public long getBucketCount(String databaseName, String bucketName) {
        var db = getDatabase(databaseName);
        if (db.getDocumentEngineNames().contains(bucketName)) {
            return db.getDocumentEngine(bucketName).count();
        } else if (db.getVectorEngineNames().contains(bucketName)) {
            return db.getVectorEngine(bucketName, 3).size();
        } else if (db.getGraphEngineNames().contains(bucketName)) {
            return db.getGraphEngine(bucketName).size();
        } else if (db.getKeyValueEngineNames().contains(bucketName)) {
            return db.getKeyValueEngine(bucketName).size();
        } else if (db.getTimeSeriesEngineNames().contains(bucketName)) {
            return db.getTimeSeriesEngine(bucketName).size();
        } else if (db.getGeospatialEngineNames().contains(bucketName)) {
            return db.getGeospatialEngine(bucketName).size();
        } else if (db.getColumnarEngineNames().contains(bucketName)) {
            return db.getColumnarEngine(bucketName).size();
        } else if (db.getRecordsEngineNames().contains(bucketName)) {
            var r = db.getRecordsEngine(bucketName);
            return r != null ? r.size() : 0;
        }
        return 0;
    }

    public List<BucketRecord> getBucketRecords(String databaseName, String bucketName, int offset, int limit) {
        var db = getDatabase(databaseName);
        List<BucketRecord> items = new ArrayList<>();
        if (db.getDocumentEngineNames().contains(bucketName)) {
            var engine = db.getDocumentEngine(bucketName);
            int current = 0;
            for (Map<String, Object> doc : engine) {
                if (current >= offset && items.size() < limit) {
                    String id = String.valueOf(doc.getOrDefault("_id", ""));
                    String refs = doc.keySet().stream().filter(k -> k.startsWith("_ref")).map(k -> k + "->" + doc.get(k)).reduce("", (a, b) -> a + " " + b);
                    items.add(new BucketRecord(id, doc.toString(), refs.isBlank() ? "(Sin Ref)" : refs.trim()));
                }
                current++;
                if (items.size() >= limit) break;
            }
        } else if (db.getVectorEngineNames().contains(bucketName)) {
            var vecEngine = db.getVectorEngine(bucketName, 3);
            for (var entry : vecEngine.getAllVectors().entrySet()) {
                items.add(new BucketRecord(entry.getKey(), Arrays.toString(entry.getValue()), "Vector [" + vecEngine.getDimensions() + "D]"));
            }
        } else if (db.getGraphEngineNames().contains(bucketName)) {
            var graphEngine = db.getGraphEngine(bucketName);
            var edgesMap = graphEngine.getAllEdges();
            for (String v : graphEngine.getVertices()) {
                var out = edgesMap.getOrDefault(v, List.of());
                String edgeDesc = out.isEmpty() ? "(Vértice aislado)" : out.stream().map(e -> e.label() + " -> " + e.targetVertex()).reduce("", (a, b) -> a + "; " + b);
                items.add(new BucketRecord(v, edgeDesc.startsWith("; ") ? edgeDesc.substring(2) : edgeDesc, "Graph (" + out.size() + " aristas)"));
            }
        } else if (db.getKeyValueEngineNames().contains(bucketName)) {
            var kvEngine = db.getKeyValueEngine(bucketName);
            for (var entry : kvEngine.getAll().entrySet()) {
                String valStr = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                items.add(new BucketRecord(entry.getKey(), valStr, "KeyValue"));
            }
        } else if (db.getTimeSeriesEngineNames().contains(bucketName)) {
            var tsEngine = db.getTimeSeriesEngine(bucketName);
            for (var entry : tsEngine.getAll().entrySet()) {
                String timeStr = java.time.Instant.ofEpochMilli(entry.getKey()).toString();
                items.add(new BucketRecord(String.valueOf(entry.getKey()), "Valor: " + entry.getValue() + " (" + timeStr + ")", "TimeSeries"));
            }
        } else if (db.getGeospatialEngineNames().contains(bucketName)) {
            var geoEngine = db.getGeospatialEngine(bucketName);
            for (var entry : geoEngine.getAllPoints().entrySet()) {
                items.add(new BucketRecord(entry.getKey(), "Lat: " + entry.getValue().latitude() + ", Lon: " + entry.getValue().longitude(), "Geospatial"));
            }
        } else if (db.getColumnarEngineNames().contains(bucketName)) {
            var colEngine = db.getColumnarEngine(bucketName);
            int count = colEngine.size();
            var numCols = colEngine.getNumericColumns();
            var txtCols = colEngine.getTextColumns();
            for (int i = 0; i < count && items.size() < limit; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (var e : numCols.entrySet()) {
                    if (i < e.getValue().size()) row.put(e.getKey(), e.getValue().get(i));
                }
                for (var e : txtCols.entrySet()) {
                    if (i < e.getValue().size()) row.put(e.getKey(), e.getValue().get(i));
                }
                items.add(new BucketRecord("row_" + (i + 1), row.toString(), "Columnar"));
            }
        } else if (db.getRecordsEngineNames().contains(bucketName)) {
            var recEngine = db.getRecordsEngine(bucketName);
            if (recEngine != null) {
                int count = 0;
                for (var rec : recEngine.listAll()) {
                    if (count >= offset && items.size() < limit) {
                        items.add(new BucketRecord("rec_" + (count + 1), rec.toString(), "JavaRecord (" + recEngine.getRecordClass().getSimpleName() + ")"));
                    }
                    count++;
                    if (items.size() >= limit) break;
                }
            }
        }
        return items;
    }

    public void insertRecord(String databaseName, String bucketName, String id, String rawData) {
        var db = getDatabase(databaseName);
        if (db.getDocumentEngineNames().contains(bucketName)) {
            Map<String, Object> map = new HashMap<>();
            map.put("_id", id);
            map.put("raw_data", rawData);
            db.getDocumentEngine(bucketName).insert(id, map);
        } else if (db.getKeyValueEngineNames().contains(bucketName)) {
            db.getKeyValueEngine(bucketName).put(id, rawData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else if (db.getVectorEngineNames().contains(bucketName)) {
            String[] parts = rawData.replace("[", "").replace("]", "").split(",");
            float[] floats = new float[parts.length];
            for (int i = 0; i < parts.length; i++) floats[i] = Float.parseFloat(parts[i].trim());
            db.getVectorEngine(bucketName, floats.length).index(id, floats);
        } else if (db.getGraphEngineNames().contains(bucketName)) {
            db.getGraphEngine(bucketName).addVertex(id);
        } else if (db.getTimeSeriesEngineNames().contains(bucketName)) {
            db.getTimeSeriesEngine(bucketName).record(System.currentTimeMillis(), Double.parseDouble(rawData.trim()));
        } else if (db.getGeospatialEngineNames().contains(bucketName)) {
            String[] parts = rawData.split(",");
            db.getGeospatialEngine(bucketName).insertPoint(id, Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        } else {
            Map<String, Object> map = new HashMap<>();
            map.put("_id", id);
            map.put("raw_data", rawData);
            db.getDocumentEngine(bucketName).insert(id, map);
        }
    }

}

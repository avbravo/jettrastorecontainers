package io.jettra.store.core;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.json.JsonArray;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.engine.index.JettraIndexManager;
import io.jettra.store.engine.models.*;
import io.jettra.store.engine.panama.NativeMemTable;
import io.jettra.store.police.JettraPolice;
import io.jettra.store.sample.JettraStoreSamples;
import com.jettra.memory.api.JettraMemoryConfig;
import com.jettra.memory.api.JettraMemoryEngine;
import com.jettra.memory.engine.StorageMetrics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.jettra.store.offheap.JettraTransactionContext;
import io.jettra.store.offheap.OffHeapPageBuffer;
import io.jettra.store.offheap.OffHeapPageManager;
import io.jettra.store.offheap.OffHeapSlottedMap;

public final class JettraDatabase implements AutoCloseable {
    private final String databaseName;
    private final JettraStoreConfig config;
    private final NativeMemTable memTable;
    private final JettraIndexManager indexManager;
    private final DynamicRingEngine ringEngine;
    private final AtomicBoolean distributedRingActive = new AtomicBoolean(false);
    private final JettraMemoryEngine memoryEngine;
    private final OffHeapSlottedMap offHeapDatabaseStore = new OffHeapSlottedMap();
    private final OffHeapPageManager offHeapPageManager;
    private volatile StorageMode storageMode;

    private final Map<String, DocumentEngine> documentEngines = new ConcurrentHashMap<>();
    private final Map<String, VectorEngine> vectorEngines = new ConcurrentHashMap<>();
    private final Map<String, GraphEngine> graphEngines = new ConcurrentHashMap<>();
    private final Map<String, TimeSeriesEngine> timeSeriesEngines = new ConcurrentHashMap<>();
    private final Map<String, KeyValueEngine> keyValueEngines = new ConcurrentHashMap<>();
    private final Map<String, GeospatialEngine> geospatialEngines = new ConcurrentHashMap<>();
    private final Map<String, ColumnarEngine> columnarEngines = new ConcurrentHashMap<>();
    private final Map<String, io.jettra.store.engine.models.RecordsEngine<?>> recordsEngines = new ConcurrentHashMap<>();

    public JettraDatabase(String databaseName, JettraStoreConfig config) {
        this(databaseName, config, null);
    }

    public JettraDatabase(String databaseName, JettraStoreConfig config, DynamicRingEngine ringEngine) {
        this.databaseName = databaseName;
        this.config = config != null ? config : JettraStoreConfig.load();
        this.indexManager = new JettraIndexManager(databaseName, this.config);
        long memTableBytes = this.config.getMemTableSizeMb() * 1024L * 1024L;
        this.memTable = new NativeMemTable(memTableBytes);
        this.ringEngine = (ringEngine != null) ? ringEngine : new DynamicRingEngine(this.config.getNodeId(), 
            this.config.getRingSaturationThresholdPercent() / 100.0, 
            this.config.getRingReleaseTargetPercent() / 100.0,
            this.config.isClusterMultinodeActive());

        // Inicialización de JettraMemory (Almacenamiento Off-Heap de ultra-alta velocidad)
        JettraMemoryEngine memEngine = null;
        try {
            Path memDir = Path.of(this.config.getStoragePath(), databaseName, "jettra_memory");
            JettraMemoryConfig memConfig = JettraMemoryConfig.builder()
                .storageDirectory(memDir)
                .storeName(databaseName)
                .autoGcEnabled(true)
                .compactionThreshold(0.25)
                .build();
            memEngine = new JettraMemoryEngine(memConfig);
        } catch (Exception ex) {
            System.err.printf("[JettraDatabase] Aviso: JettraMemory off-heap fallback: %s%n", ex.getMessage());
        }
        this.memoryEngine = memEngine;

        // Inicialización de JettraStore-OffHeap (Gestor de páginas ranuradas Slotted-Page)
        OffHeapPageManager pageMgr = null;
        try {
            Path offHeapPath = Path.of(this.config.getStoragePath(), databaseName, databaseName + "_offheap.pages");
            if (offHeapPath.getParent() != null) {
                Files.createDirectories(offHeapPath.getParent());
            }
            pageMgr = new OffHeapPageManager(offHeapPath);
        } catch (Exception ex) {
            System.err.printf("[JettraDatabase] Aviso: JettraStore-OffHeap page manager fallback: %s%n", ex.getMessage());
        }
        this.offHeapPageManager = pageMgr;
        this.storageMode = (this.config != null && this.config.getStorageMode() != null) ? this.config.getStorageMode() : StorageMode.JVM_RAM;

        if (this.config.isClusterMultinodeActive() && this.ringEngine.getPeers().isEmpty()) {
            for (ClusterNode peer : this.config.getParsedPeers()) {
                this.ringEngine.registerPeer(peer);
            }
        }

        // 1. Cargar estado previo de disco si existe
        boolean loaded = loadFromDisk();

        // 2. Si es una base de datos de muestra y no tiene datos, cargar muestra automáticamente (sólo en modo writable)
        if (!loaded && getAllCollectionNames().isEmpty() && JettraStoreSamples.isSampleDatabase(databaseName)) {
            if (!isReadOnlyNode()) {
                JettraStoreSamples.installSample(databaseName, this);
                try {
                    flushMemTable();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Determina si el nodo actual opera en modo de solo lectura (SECONDARY en modo multinodo).
     */
    public boolean isReadOnlyNode() {
        return config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY;
    }

    /**
     * Valida que el nodo actual permita mutaciones (escrituras, actualizaciones, eliminaciones).
     * En nodos SECUNDARIOS se lanza una excepción explícita que bloquea la mutación.
     */
    public void assertWritable() {
        if (isReadOnlyNode()) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO (SECONDARY). " +
                "Las operaciones de inserción, actualización y eliminación sólo están permitidas en el nodo PRIMARIO (PRIMARY).",
                config != null ? config.getNodeId() : "secondary"
            ));
        }
    }

    public double getRamSaturationPercentage() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return ((double) used / (double) rt.maxMemory()) * 100.0;
    }

    public boolean isDistributedRingActive() {
        return distributedRingActive.get();
    }

    public void setDistributedRingActive(boolean active) {
        distributedRingActive.set(active);
    }

    public DynamicRingEngine getRingEngine() {
        return ringEngine;
    }

    public void activateDistributedRingTransition(String reason) {
        if (!this.config.isClusterMultinodeActive()) {
            // Distribución desactivada (cluster.multinode.active=off): operaciones solo locales
            return;
        }
        this.distributedRingActive.set(true);
        double ramPct = getRamSaturationPercentage();
        if (ringEngine != null) {
            ringEngine.evaluateMemorySaturation(ramPct / 100.0, memTable);
        }
        JettraPolice.getInstance().recordAlert("RING_SATURATION_ACTIVATED", 
            String.format("[PREVENTIVO] Base de datos '%s': RAM al %.1f%%. Activada Transición Dinámica a Anillo Distribuido (%s).", 
                databaseName, ramPct, reason));

        if (ringEngine != null) {
            List<ClusterNode> peers = ringEngine.getPeers();
            long offloadBytes = (memTable != null ? memTable.getUsedBytes() : 128L * 1024L * 1024L) / Math.max(1, peers.size());
            for (ClusterNode peer : peers) {
                peer.receiveOffloadedRingPayload("node-01", offloadBytes);
            }
        }

        JettraPolice.getInstance().recordAlert("RING_LOAD_BALANCED", 
            String.format("[BALANCEADO] Particiones de '%s' descargadas a nodos secundarios (node-02, node-03). RAM local normalizada a %.1f%%.", 
                databaseName, (double) config.getRingReleaseTargetPercent()));
    }

    private void onEngineCreated(String engineType, String engineName, byte[] payload) {
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicateCreateEngine(databaseName, engineName, engineType, payload);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_ENGINE_CREATED,
                config.getNodeId(), "cluster",
                String.format("Motor especializado '%s' [%s] en base '%s' creado y sincronizado en el clúster", engineName, engineType, databaseName),
                "db=" + databaseName + ",engine=" + engineName + ",type=" + engineType
            );
        }
    }

    public DocumentEngine getDocumentEngine(String name) {
        boolean isNew = !documentEngines.containsKey(name);
        DocumentEngine eng = documentEngines.computeIfAbsent(name, n -> new DocumentEngine(n, this));
        if (isNew) {
            onEngineCreated("DOCUMENT", name, null);
        }
        return eng;
    }

    public StreamResponse<Map<String, Object>> streamCollection(String collectionName) {
        return getDocumentEngine(collectionName).streamAll();
    }

    public StreamResponse<Map<String, Object>> streamCollection(String collectionName, int limit) {
        return getDocumentEngine(collectionName).streamAll(limit);
    }

    public VectorEngine getVectorEngine(String name, int dimensions) {
        boolean isNew = !vectorEngines.containsKey(name);
        VectorEngine eng = vectorEngines.computeIfAbsent(name, k -> new VectorEngine(k, dimensions));
        if (isNew) {
            byte[] payload = String.valueOf(dimensions).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            onEngineCreated("VECTOR", name, payload);
        }
        return eng;
    }

    public GraphEngine getGraphEngine(String name) {
        boolean isNew = !graphEngines.containsKey(name);
        GraphEngine eng = graphEngines.computeIfAbsent(name, GraphEngine::new);
        if (isNew) {
            onEngineCreated("GRAPH", name, null);
        }
        return eng;
    }

    public TimeSeriesEngine getTimeSeriesEngine(String name) {
        boolean isNew = !timeSeriesEngines.containsKey(name);
        TimeSeriesEngine eng = timeSeriesEngines.computeIfAbsent(name, TimeSeriesEngine::new);
        if (isNew) {
            onEngineCreated("TIMESERIES", name, null);
        }
        return eng;
    }

    public KeyValueEngine getKeyValueEngine(String name) {
        boolean isNew = !keyValueEngines.containsKey(name);
        KeyValueEngine eng = keyValueEngines.computeIfAbsent(name, k -> new KeyValueEngine(k, this));
        if (isNew) {
            onEngineCreated("KEY_VALUE", name, null);
        }
        return eng;
    }

    public void onDocumentInsert(String collectionName, String id, Map<String, Object> doc) {
        if (indexManager != null) {
            indexManager.onDocumentInsert(collectionName, id, doc);
        }
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                byte[] jsonBytes = new io.jettra.json.JettraJson().toJson(doc).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                server.replicatePutDocument(databaseName, collectionName, id, jsonBytes);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_DOCUMENT_REPLICATED,
                config.getNodeId(), "cluster",
                String.format("Documento '%s' en colección '%s' de base '%s' replicado a nodos secundarios", id, collectionName, databaseName),
                "db=" + databaseName + ",col=" + collectionName + ",id=" + id
            );
        }
    }

    public void onDocumentDelete(String collectionName, String id) {
        if (indexManager != null) {
            indexManager.onDocumentDelete(collectionName, id, null);
        }
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicateDeleteDocument(databaseName, collectionName, id);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_DOCUMENT_DELETED,
                config.getNodeId(), "cluster",
                String.format("Documento '%s' en colección '%s' de base '%s' eliminado y propagado al clúster", id, collectionName, databaseName),
                "db=" + databaseName + ",col=" + collectionName + ",id=" + id
            );
        }
    }

    public void onKeyValuePut(String namespace, String key, byte[] value) {
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicatePutRecord(databaseName, namespace, key, value);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_RECORD_REPLICATED,
                config.getNodeId(), "cluster",
                String.format("Registro KV '%s' en '%s' de base '%s' replicado a nodos secundarios", key, namespace, databaseName),
                "db=" + databaseName + ",ns=" + namespace + ",key=" + key
            );
        }
    }

    public void onKeyValueDelete(String namespace, String key) {
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicateDeleteRecord(databaseName, namespace, key);
            }
        }
    }

    public GeospatialEngine getGeospatialEngine(String name) {
        boolean isNew = !geospatialEngines.containsKey(name);
        GeospatialEngine eng = geospatialEngines.computeIfAbsent(name, GeospatialEngine::new);
        if (isNew) {
            onEngineCreated("GEOSPATIAL", name, null);
        }
        return eng;
    }

    public ColumnarEngine getColumnarEngine(String name) {
        boolean isNew = !columnarEngines.containsKey(name);
        ColumnarEngine eng = columnarEngines.computeIfAbsent(name, ColumnarEngine::new);
        if (isNew) {
            onEngineCreated("COLUMNAR", name, null);
        }
        return eng;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> io.jettra.store.engine.models.RecordsEngine<T> getRecordsEngine(String name, Class<T> recordClass) {
        boolean isNew = !recordsEngines.containsKey(name);
        var eng = (io.jettra.store.engine.models.RecordsEngine<T>) recordsEngines.computeIfAbsent(name, k -> new io.jettra.store.engine.models.RecordsEngine<>(name, recordClass));
        if (isNew) {
            byte[] payload = recordClass != null ? recordClass.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
            onEngineCreated("RECORDS", name, payload);
        }
        return eng;
    }

    public io.jettra.store.engine.models.RecordsEngine<?> getRecordsEngine(String name) {
        return recordsEngines.get(name);
    }

    public java.util.Set<String> getRecordsEngineNames() {
        return java.util.Collections.unmodifiableSet(recordsEngines.keySet());
    }

    public void ensureEngineInternal(String engineType, String engineName, byte[] payload) {
        if (engineType == null || engineName == null) return;
        switch (engineType.toUpperCase()) {
            case "DOCUMENT" -> documentEngines.computeIfAbsent(engineName, n -> new DocumentEngine(n, this));
            case "KEY_VALUE" -> keyValueEngines.computeIfAbsent(engineName, k -> new KeyValueEngine(k, this));
            case "VECTOR" -> {
                int dims = 128;
                if (payload != null && payload.length > 0) {
                    try {
                        dims = Integer.parseInt(new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim());
                    } catch (Exception ignored) {}
                }
                final int finalDims = dims;
                vectorEngines.computeIfAbsent(engineName, k -> new VectorEngine(k, finalDims));
            }
            case "GRAPH" -> graphEngines.computeIfAbsent(engineName, GraphEngine::new);
            case "TIMESERIES" -> timeSeriesEngines.computeIfAbsent(engineName, TimeSeriesEngine::new);
            case "GEOSPATIAL" -> geospatialEngines.computeIfAbsent(engineName, GeospatialEngine::new);
            case "COLUMNAR" -> columnarEngines.computeIfAbsent(engineName, ColumnarEngine::new);
            case "RECORDS" -> {
                Class<?> rClass = Record.class;
                if (payload != null && payload.length > 0) {
                    try {
                        String clsName = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
                        rClass = Class.forName(clsName);
                    } catch (Exception ignored) {}
                }
                final Class<?> finalClass = rClass;
                recordsEngines.computeIfAbsent(engineName, k -> (io.jettra.store.engine.models.RecordsEngine) new io.jettra.store.engine.models.RecordsEngine(engineName, finalClass));
            }
            default -> documentEngines.computeIfAbsent(engineName, n -> new DocumentEngine(n, this));
        }
    }

    public void flushMemTable() throws IOException {
        Path target = Path.of(config.getStoragePath(), databaseName + "_sstable" + config.getFileExtension());
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        memTable.flushToJettraFile(target);
        saveToDisk();
    }

    public static Path resolveMetaFile(String dbName, JettraStoreConfig config) {
        if (dbName == null) return null;
        if (config != null && config.getStoragePath() != null) {
            Path p = Path.of(config.getStoragePath(), dbName + "_meta.json");
            if (Files.exists(p)) return p;
        }
        if (config != null && config.getConfiguredStoragePath() != null) {
            Path p = Path.of(config.getConfiguredStoragePath(), dbName + "_meta.json");
            if (Files.exists(p)) return p;
        }
        Path p = Path.of("./data/jettra", dbName + "_meta.json");
        if (Files.exists(p)) return p;
        p = Path.of("data/jettra", dbName + "_meta.json");
        if (Files.exists(p)) return p;
        p = Path.of("../data/jettra", dbName + "_meta.json");
        if (Files.exists(p)) return p;
        p = Path.of(System.getProperty("user.home") + "/jettra/data", dbName + "_meta.json");
        if (Files.exists(p)) return p;
        return null;
    }

    public static int getLightweightCollectionCount(String databaseName, JettraStoreConfig config) {
        if (databaseName == null) return 0;
        if (JettraStoreSamples.isSampleDatabase(databaseName)) {
            return JettraStoreSamples.getSampleCollectionCount(databaseName);
        }
        Path metaFile = resolveMetaFile(databaseName, config);
        if (metaFile == null || !Files.exists(metaFile)) {
            return 0;
        }

        try (FileChannel channel = FileChannel.open(metaFile, StandardOpenOption.READ);
             Arena arena = Arena.ofShared()) {
            long size = channel.size();
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            PanamaOffHeapScanner s = new PanamaOffHeapScanner(segment, size);
            s.skipWhitespace();
            if (s.peek() == '{') s.read();
            Set<String> collections = new HashSet<>();
            while (s.hasMore() && s.peek() != '}') {
                s.skipWhitespaceAndSeparators();
                String key = s.readQuotedString();
                if (key == null) break;
                s.skipWhitespaceAndSeparators();
                if ("collections".equals(key)) {
                    s.skipWhitespace();
                    if (s.peek() == '[') {
                        s.read();
                        while (s.hasMore() && s.peek() != ']') {
                            s.skipWhitespaceAndSeparators();
                            String colName = s.readQuotedString();
                            if (colName != null) collections.add(colName);
                        }
                        return collections.size();
                    }
                } else if ("documentEngines".equals(key) || "keyValueEngines".equals(key) ||
                           "timeSeriesEngines".equals(key) || "geospatialEngines".equals(key) ||
                           "vectorEngines".equals(key) || "graphEngines".equals(key) ||
                           "columnarEngines".equals(key) || "recordsEngines".equals(key)) {
                    s.skipWhitespace();
                    if (s.peek() == '{') {
                        s.read();
                        while (s.hasMore() && s.peek() != '}') {
                            s.skipWhitespaceAndSeparators();
                            String itemKey = s.readQuotedString();
                            if (itemKey == null) break;
                            collections.add(itemKey);
                            s.skipValue();
                        }
                        s.skipWhitespace();
                        if (s.peek() == '}') s.read();
                    } else {
                        s.skipValue();
                    }
                } else {
                    s.skipValue();
                }
            }
            return collections.size();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean saveToDisk() {
        Path metaFile = Path.of(config.getStoragePath(), databaseName + "_meta.json");
        if (metaFile.getParent() != null) {
            try {
                Files.createDirectories(metaFile.getParent());
            } catch (IOException e) {
                return false;
            }
        }

        JettraJson json = new JettraJson();
        try (BufferedWriter writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write("{\n");
            writer.write("  \"databaseName\": \"" + databaseName + "\",\n");
            writer.write("  \"savedAt\": " + System.currentTimeMillis() + ",\n");

            // Metadatos de colecciones ligeras al inicio
            List<String> allCols = new ArrayList<>(getAllCollectionNames());
            writer.write("  \"collections\": [");
            for (int i = 0; i < allCols.size(); i++) {
                writer.write("\"" + JettraJson.escapeString(allCols.get(i)) + "\"" + (i < allCols.size() - 1 ? ", " : ""));
            }
            writer.write("],\n");

            // 1. Documentos
            writer.write("  \"documentEngines\": {\n");
            boolean firstCol = true;
            for (var entry : documentEngines.entrySet()) {
                if (!firstCol) writer.write(",\n");
                firstCol = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": [\n");
                boolean firstDoc = true;
                for (var doc : entry.getValue().findAll()) {
                    if (!firstDoc) writer.write(",\n");
                    firstDoc = false;
                    writer.write("      " + json.toJson(doc));
                }
                writer.write("\n    ]");
            }
            writer.write("\n  },\n");

            // 2. Key-Value
            writer.write("  \"keyValueEngines\": {\n");
            boolean firstKV = true;
            for (var entry : keyValueEngines.entrySet()) {
                if (!firstKV) writer.write(",\n");
                firstKV = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                boolean firstPair = true;
                for (var pair : entry.getValue().getAll().entrySet()) {
                    if (!firstPair) writer.write(",\n");
                    firstPair = false;
                    writer.write("      \"" + JettraJson.escapeString(pair.getKey()) + "\": \"" +
                            JettraJson.escapeString(new String(pair.getValue(), StandardCharsets.UTF_8)) + "\"");
                }
                writer.write("\n    }");
            }
            writer.write("\n  },\n");

            // 3. TimeSeries
            writer.write("  \"timeSeriesEngines\": {\n");
            boolean firstTS = true;
            for (var entry : timeSeriesEngines.entrySet()) {
                if (!firstTS) writer.write(",\n");
                firstTS = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                boolean firstP = true;
                for (var p : entry.getValue().getAll().entrySet()) {
                    if (!firstP) writer.write(",\n");
                    firstP = false;
                    writer.write("      \"" + p.getKey() + "\": " + p.getValue());
                }
                writer.write("\n    }");
            }
            writer.write("\n  },\n");

            // 4. Geospatial
            writer.write("  \"geospatialEngines\": {\n");
            boolean firstGeo = true;
            for (var entry : geospatialEngines.entrySet()) {
                if (!firstGeo) writer.write(",\n");
                firstGeo = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": [\n");
                boolean firstPt = true;
                for (var p : entry.getValue().getAllPoints().values()) {
                    if (!firstPt) writer.write(",\n");
                    firstPt = false;
                    writer.write("      {\"id\": \"" + JettraJson.escapeString(p.id()) +
                            "\", \"lat\": " + p.latitude() + ", \"lon\": " + p.longitude() + "}");
                }
                writer.write("\n    ]");
            }
            writer.write("\n  },\n");

            // 5. Vectors
            writer.write("  \"vectorEngines\": {\n");
            boolean firstVec = true;
            for (var entry : vectorEngines.entrySet()) {
                if (!firstVec) writer.write(",\n");
                firstVec = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                writer.write("      \"dimensions\": " + entry.getValue().getDimensions() + ",\n");
                writer.write("      \"vectors\": {\n");
                boolean firstVData = true;
                for (var pair : entry.getValue().getAllVectors().entrySet()) {
                    if (!firstVData) writer.write(",\n");
                    firstVData = false;
                    writer.write("        \"" + JettraJson.escapeString(pair.getKey()) + "\": [");
                    float[] fa = pair.getValue();
                    for (int idx = 0; idx < fa.length; idx++) {
                        writer.write(String.valueOf(fa[idx]) + (idx < fa.length - 1 ? ", " : ""));
                    }
                    writer.write("]");
                }
                writer.write("\n      }\n    }");
            }
            writer.write("\n  },\n");

            // 6. Graphs
            writer.write("  \"graphEngines\": {\n");
            boolean firstGr = true;
            for (var entry : graphEngines.entrySet()) {
                if (!firstGr) writer.write(",\n");
                firstGr = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                writer.write("      \"vertices\": [");
                var verts = new ArrayList<>(entry.getValue().getVertices());
                for (int i = 0; i < verts.size(); i++) {
                    writer.write("\"" + JettraJson.escapeString(verts.get(i)) + "\"" + (i < verts.size() - 1 ? ", " : ""));
                }
                writer.write("],\n      \"edges\": {\n");
                boolean firstEdge = true;
                for (var edgeEntry : entry.getValue().getAllEdges().entrySet()) {
                    if (edgeEntry.getValue() == null || edgeEntry.getValue().isEmpty()) continue;
                    if (!firstEdge) writer.write(",\n");
                    firstEdge = false;
                    writer.write("        \"" + JettraJson.escapeString(edgeEntry.getKey()) + "\": [\n");
                    boolean firstEl = true;
                    for (var edge : edgeEntry.getValue()) {
                        if (!firstEl) writer.write(",\n");
                        firstEl = false;
                        writer.write("          {\"target\": \"" + JettraJson.escapeString(edge.targetVertex()) +
                                "\", \"label\": \"" + JettraJson.escapeString(edge.label()) + "\"}");
                    }
                    writer.write("\n        ]");
                }
                writer.write("\n      }\n    }");
            }
            writer.write("\n  },\n");

            // 7. Columnar
            writer.write("  \"columnarEngines\": {\n");
            boolean firstColm = true;
            for (var entry : columnarEngines.entrySet()) {
                if (!firstColm) writer.write(",\n");
                firstColm = false;
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                writer.write("      \"rowCount\": " + entry.getValue().getRowCount() + ",\n");
                writer.write("      \"numericColumns\": {\n");
                boolean firstNum = true;
                for (var nc : entry.getValue().getNumericColumns().entrySet()) {
                    if (!firstNum) writer.write(",\n");
                    firstNum = false;
                    writer.write("        \"" + JettraJson.escapeString(nc.getKey()) + "\": [");
                    var list = nc.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        writer.write(list.get(i).toString() + (i < list.size() - 1 ? ", " : ""));
                    }
                    writer.write("]");
                }
                writer.write("\n      },\n");
                writer.write("      \"textColumns\": {\n");
                boolean firstTxt = true;
                for (var tc : entry.getValue().getTextColumns().entrySet()) {
                    if (!firstTxt) writer.write(",\n");
                    firstTxt = false;
                    writer.write("        \"" + JettraJson.escapeString(tc.getKey()) + "\": [");
                    var list = tc.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        writer.write("\"" + JettraJson.escapeString(list.get(i)) + "\"" + (i < list.size() - 1 ? ", " : ""));
                    }
                    writer.write("]");
                }
                writer.write("\n      }\n    }");
            }
            writer.write("\n  },\n");

            // 8. Java Records
            writer.write("  \"recordsEngines\": {\n");
            boolean firstRecEng = true;
            for (var entry : recordsEngines.entrySet()) {
                if (!firstRecEng) writer.write(",\n");
                firstRecEng = false;
                var recEng = entry.getValue();
                writer.write("    \"" + JettraJson.escapeString(entry.getKey()) + "\": {\n");
                String clsName = recEng.getRecordClass() != null ? recEng.getRecordClass().getName() : "";
                writer.write("      \"recordClass\": \"" + JettraJson.escapeString(clsName) + "\",\n");
                writer.write("      \"records\": {\n");
                boolean firstRec = true;
                for (var recEntry : recEng.getAll().entrySet()) {
                    if (!firstRec) writer.write(",\n");
                    firstRec = false;
                    writer.write("        \"" + JettraJson.escapeString(recEntry.getKey()) + "\": " + json.toJson(recEntry.getValue()));
                }
                writer.write("\n      }\n    }");
            }
            writer.write("\n  },\n");

            // 9. Secondary Indexes
            writer.write("  \"indexes\": [\n");
            boolean firstIdx = true;
            for (var idx : indexManager.listIndexes(null)) {
                if (!firstIdx) writer.write(",\n");
                firstIdx = false;
                writer.write("    {\"name\": \"" + JettraJson.escapeString(idx.name()) + "\", " +
                        "\"collection\": \"" + JettraJson.escapeString(idx.collection()) + "\", " +
                        "\"field\": \"" + JettraJson.escapeString(idx.field()) + "\", " +
                        "\"type\": \"" + JettraJson.escapeString(idx.type()) + "\", " +
                        "\"unique\": " + idx.unique() + "}");
            }
            writer.write("\n  ]\n");

            writer.write("}\n");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean loadFromDisk() {
        Path metaFile = resolveMetaFile(databaseName, config);
        return loadFromDisk(metaFile);
    }

    public boolean loadFromDisk(Path metaFile) {
        if (metaFile == null || !Files.exists(metaFile)) {
            return false;
        }

        try {
            long fileSize = Files.size(metaFile);
            double currentRam = getRamSaturationPercentage();

            // Supervisión predictiva JettraPolice: si el archivo es masivo (> 50MB) o RAM > 70%
            boolean shouldRing = (fileSize > 50L * 1024L * 1024L) || (currentRam >= 70.0);
            if (shouldRing) {
                activateDistributedRingTransition("DENSIDAD_OFF_HEAP_MASIVA");
            }

            // Usar Scanner Off-Heap de Project Panama si el archivo existe
            return loadUsingPanamaOffHeap(metaFile, shouldRing);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean isHeapCriticallyLow() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        long free = max - used;
        if (free < Math.max(64L * 1024L * 1024L, (long) (max * 0.12))) {
            System.gc();
            used = rt.totalMemory() - rt.freeMemory();
            free = max - used;
            return free < Math.max(48L * 1024L * 1024L, (long) (max * 0.10));
        }
        return false;
    }

    private void triggerLowMemoryRingGuard() {
        this.distributedRingActive.set(true);
        activateDistributedRingTransition("DENSIDAD_OFF_HEAP_MASIVA");
        JettraPolice.getInstance().recordAlert("HEAP_EXHAUSTION_PREVENTED",
            String.format("[CENTINELA OFF-HEAP] Base de datos '%s': Prevención activa de OutOfMemoryError activada durante carga masiva.", databaseName));
    }

    private boolean loadUsingPanamaOffHeap(Path metaFile, boolean ringModeActive) {
        JettraJson json = new JettraJson();
        try (FileChannel channel = FileChannel.open(metaFile, StandardOpenOption.READ);
             Arena arena = Arena.ofShared()) {

            long size = channel.size();
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            PanamaOffHeapScanner scanner = new PanamaOffHeapScanner(segment, size);

            scanner.skipWhitespace();
            if (scanner.peek() == '{') scanner.read();

            while (scanner.hasMore() && scanner.peek() != '}') {
                scanner.skipWhitespaceAndSeparators();
                if (scanner.peek() == '}') break;
                String sectionKey = scanner.readKeyString();
                if (sectionKey == null) break;
                scanner.skipWhitespaceAndSeparators();

                switch (sectionKey) {
                    case "documentEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String colName = scanner.readKeyString();
                                if (colName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '[') {
                                    scanner.read();
                                    var docEng = getDocumentEngine(colName);
                                    docEng.applyReplicatedClear();
                                    Map<String, Map<String, Object>> batch = new HashMap<>(5000);
                                    boolean hasColIndexes = !indexManager.listIndexes(colName).isEmpty();
                                    long docCount = 0;

                                    while (scanner.hasMore() && scanner.peek() != ']') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == ']') break;
                                        if ((++docCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                            triggerLowMemoryRingGuard();
                                            scanner.skipToArrayEnd();
                                            break;
                                        }
                                        io.jettra.collections.map.UnifiedMap<String, Object> map = scanner.readDocumentObject(json);
                                        if (map != null) {
                                            Object rawId = map.get("_id");
                                            String id = (rawId != null) ? rawId.toString() : UUID.randomUUID().toString();
                                            map.put("_id", id);
                                            batch.put(id, map);
                                            if (batch.size() >= 5000) {
                                                docEng.applyReplicatedBatch(batch);
                                                if (hasColIndexes) {
                                                    for (var entry : batch.entrySet()) {
                                                        indexManager.onDocumentInsert(colName, entry.getKey(), entry.getValue());
                                                    }
                                                }
                                                batch.clear();
                                            }
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    if (!batch.isEmpty()) {
                                        docEng.applyReplicatedBatch(batch);
                                        if (hasColIndexes) {
                                            for (var entry : batch.entrySet()) {
                                                indexManager.onDocumentInsert(colName, entry.getKey(), entry.getValue());
                                            }
                                        }
                                        batch.clear();
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == ']') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "keyValueEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String ns = scanner.readKeyString();
                                if (ns == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    var kvEng = getKeyValueEngine(ns);
                                    kvEng.applyReplicatedClear();
                                    String lastValStr = null;
                                    byte[] lastValBytes = null;
                                    long kvCount = 0;
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == '}') break;
                                        if ((++kvCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                            triggerLowMemoryRingGuard();
                                            scanner.skipToObjectEnd();
                                            break;
                                        }
                                        String k = scanner.readQuotedString();
                                        if (k == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        String v = scanner.readCachedValueString();
                                        if (v != null) {
                                            byte[] bytes;
                                            if (v.equals(lastValStr) && lastValBytes != null) {
                                                bytes = lastValBytes;
                                            } else {
                                                bytes = v.getBytes(StandardCharsets.UTF_8);
                                                lastValStr = v;
                                                lastValBytes = bytes;
                                            }
                                            kvEng.applyReplicatedPut(k, bytes);
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == '}') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "timeSeriesEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String metric = scanner.readKeyString();
                                if (metric == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    var tsEng = getTimeSeriesEngine(metric);
                                    tsEng.clear();
                                    long tsCount = 0;
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == '}') break;
                                        if ((++tsCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                            triggerLowMemoryRingGuard();
                                            scanner.skipToObjectEnd();
                                            break;
                                        }
                                        String tsStr = scanner.readQuotedString();
                                        if (tsStr == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        Number valNum = scanner.readNumberValue();
                                        if (valNum != null) {
                                            try {
                                                long ts = Long.parseLong(tsStr);
                                                tsEng.record(ts, valNum.doubleValue());
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == '}') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "geospatialEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String layer = scanner.readKeyString();
                                if (layer == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '[') {
                                    scanner.read();
                                    var geoEng = getGeospatialEngine(layer);
                                    geoEng.clear();
                                    long geoCount = 0;
                                    while (scanner.hasMore() && scanner.peek() != ']') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == ']') break;
                                        if ((++geoCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                            triggerLowMemoryRingGuard();
                                            scanner.skipToArrayEnd();
                                            break;
                                        }
                                        io.jettra.collections.map.UnifiedMap<String, Object> gp = scanner.readDocumentObject(json);
                                        if (gp != null) {
                                            Object idObj = gp.get("id");
                                            Object latObj = gp.get("lat");
                                            Object lonObj = gp.get("lon");
                                            if (idObj != null && latObj != null && lonObj != null) {
                                                double lat = (latObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(latObj.toString());
                                                double lon = (lonObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(lonObj.toString());
                                                geoEng.insertPoint(idObj.toString(), lat, lon);
                                            }
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == ']') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "vectorEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String vName = scanner.readKeyString();
                                if (vName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    int dims = 3;
                                    VectorEngine vecEng = null;
                                    float[] lastVec = null;
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == '}') break;
                                        String prop = scanner.readKeyString();
                                        if (prop == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        if ("dimensions".equals(prop)) {
                                            Number n = scanner.readNumberValue();
                                            dims = (n != null) ? n.intValue() : 3;
                                            vecEng = getVectorEngine(vName, dims);
                                            vecEng.clear();
                                        } else if ("vectors".equals(prop)) {
                                            if (vecEng == null) {
                                                vecEng = getVectorEngine(vName, dims);
                                                vecEng.clear();
                                            }
                                            scanner.skipWhitespace();
                                            if (scanner.peek() == '{') {
                                                scanner.read();
                                                long vecCount = 0;
                                                while (scanner.hasMore() && scanner.peek() != '}') {
                                                    scanner.skipWhitespaceAndSeparators();
                                                    if (scanner.peek() == '}') break;
                                                    if ((++vecCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                                        triggerLowMemoryRingGuard();
                                                        scanner.skipToObjectEnd();
                                                        break;
                                                    }
                                                    String vid = scanner.readQuotedString();
                                                    if (vid == null) break;
                                                    scanner.skipWhitespaceAndSeparators();
                                                    float[] fa = scanner.readFloatArray(dims);
                                                    if (fa != null && fa.length == dims) {
                                                        if (lastVec != null && Arrays.equals(fa, lastVec)) {
                                                            fa = lastVec;
                                                        } else {
                                                            lastVec = fa;
                                                        }
                                                        vecEng.index(vid, fa);
                                                    }
                                                }
                                                scanner.skipWhitespace();
                                                if (scanner.peek() == '}') scanner.read();
                                            } else {
                                                scanner.skipValue();
                                            }
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == '}') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "graphEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String gName = scanner.readKeyString();
                                if (gName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    var gEng = getGraphEngine(gName);
                                    gEng.clear();
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == '}') break;
                                        String prop = scanner.readKeyString();
                                        if (prop == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        if ("vertices".equals(prop)) {
                                            scanner.skipWhitespace();
                                            if (scanner.peek() == '[') {
                                                scanner.read();
                                                long vCount = 0;
                                                while (scanner.hasMore() && scanner.peek() != ']') {
                                                    scanner.skipWhitespaceAndSeparators();
                                                    if (scanner.peek() == ']') break;
                                                    if ((++vCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                                        triggerLowMemoryRingGuard();
                                                        scanner.skipToArrayEnd();
                                                        break;
                                                    }
                                                    String vId = scanner.readCachedValueString();
                                                    if (vId != null) {
                                                        gEng.addVertex(vId);
                                                    } else {
                                                        scanner.skipValue();
                                                    }
                                                }
                                                scanner.skipWhitespace();
                                                if (scanner.peek() == ']') scanner.read();
                                            } else {
                                                scanner.skipValue();
                                            }
                                        } else if ("edges".equals(prop)) {
                                            scanner.skipWhitespace();
                                            if (scanner.peek() == '{') {
                                                scanner.read();
                                                long eCount = 0;
                                                while (scanner.hasMore() && scanner.peek() != '}') {
                                                    scanner.skipWhitespaceAndSeparators();
                                                    if (scanner.peek() == '}') break;
                                                    if ((++eCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                                        triggerLowMemoryRingGuard();
                                                        scanner.skipToObjectEnd();
                                                        break;
                                                    }
                                                    String src = scanner.readCachedValueString();
                                                    if (src == null) break;
                                                    scanner.skipWhitespaceAndSeparators();
                                                    if (scanner.peek() == '[') {
                                                        scanner.read();
                                                        while (scanner.hasMore() && scanner.peek() != ']') {
                                                            scanner.skipWhitespaceAndSeparators();
                                                            if (scanner.peek() == ']') break;
                                                            io.jettra.collections.map.UnifiedMap<String, Object> eo = scanner.readDocumentObject(json);
                                                            if (eo != null) {
                                                                Object tgtObj = eo.get("target");
                                                                Object lblObj = eo.get("label");
                                                                if (tgtObj != null && lblObj != null) {
                                                                    gEng.addEdge(src, tgtObj.toString(), lblObj.toString(), Map.of());
                                                                }
                                                            } else {
                                                                scanner.skipValue();
                                                            }
                                                        }
                                                        scanner.skipWhitespace();
                                                        if (scanner.peek() == ']') scanner.read();
                                                    } else {
                                                        scanner.skipValue();
                                                    }
                                                }
                                                scanner.skipWhitespace();
                                                if (scanner.peek() == '}') scanner.read();
                                            } else {
                                                scanner.skipValue();
                                            }
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == '}') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "columnarEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String tableName = scanner.readKeyString();
                                if (tableName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                String tableObjStr = scanner.readBalancedObject();
                                if (tableObjStr != null) {
                                    JsonObject to = json.fromJson(tableObjStr, JsonObject.class);
                                    if (to != null) {
                                        var colEng = getColumnarEngine(tableName);
                                        colEng.clear();
                                        int rowCount = to.has("rowCount") ? to.getAsInt("rowCount") : 0;
                                        Map<String, List<Double>> numCols = new HashMap<>();
                                        if (to.has("numericColumns")) {
                                            JsonObject no = to.getAsJsonObject("numericColumns");
                                            if (no != null) {
                                                for (String k : no.keySet()) {
                                                    JsonArray arr = no.getAsJsonArray(k);
                                                    if (arr != null) {
                                                        List<Double> list = new ArrayList<>(arr.size());
                                                        for (int i = 0; i < arr.size(); i++) {
                                                            try {
                                                                list.add(Double.parseDouble(arr.get(i).toString()));
                                                            } catch (Exception ignored) {}
                                                        }
                                                        numCols.put(k, list);
                                                    }
                                                }
                                            }
                                        }
                                        Map<String, List<String>> txtCols = new HashMap<>();
                                        if (to.has("textColumns")) {
                                            JsonObject txo = to.getAsJsonObject("textColumns");
                                            if (txo != null) {
                                                for (String k : txo.keySet()) {
                                                    JsonArray arr = txo.getAsJsonArray(k);
                                                    if (arr != null) {
                                                        List<String> list = new ArrayList<>(arr.size());
                                                        for (int i = 0; i < arr.size(); i++) {
                                                            list.add(arr.get(i) != null ? arr.get(i).toString() : "");
                                                        }
                                                        txtCols.put(k, list);
                                                    }
                                                }
                                            }
                                        }
                                        colEng.appendBatch(numCols, txtCols, rowCount);
                                    }
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "recordsEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '}') break;
                                String entityName = scanner.readKeyString();
                                if (entityName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    Class<?> recClass = null;
                                    java.lang.reflect.RecordComponent[] recComps = null;
                                    Class<?>[] recCompTypes = null;
                                    java.lang.reflect.Constructor<?> recCtor = null;
                                    io.jettra.store.engine.models.RecordsEngine rawEng = null;

                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (scanner.peek() == '}') break;
                                        String prop = scanner.readKeyString();
                                        if (prop == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        if ("recordClass".equals(prop)) {
                                            String clsName = scanner.readQuotedString();
                                            if (clsName != null && !clsName.isBlank()) {
                                                try {
                                                    recClass = Class.forName(clsName);
                                                    if (Record.class.isAssignableFrom(recClass)) {
                                                        @SuppressWarnings("unchecked")
                                                        Class<? extends Record> checkedClass = (Class<? extends Record>) recClass;
                                                        rawEng = (io.jettra.store.engine.models.RecordsEngine) getRecordsEngine(entityName, checkedClass);
                                                        rawEng.clear();
                                                        recComps = recClass.getRecordComponents();
                                                        recCompTypes = new Class<?>[recComps.length];
                                                        for (int i = 0; i < recComps.length; i++) {
                                                            recCompTypes[i] = recComps[i].getType();
                                                        }
                                                        recCtor = recClass.getDeclaredConstructor(recCompTypes);
                                                        recCtor.setAccessible(true);
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        } else if ("records".equals(prop)) {
                                            scanner.skipWhitespace();
                                            if (scanner.peek() == '{' && rawEng != null && recCtor != null && recComps != null) {
                                                scanner.read();
                                                long recCount = 0;
                                                while (scanner.hasMore() && scanner.peek() != '}') {
                                                    scanner.skipWhitespaceAndSeparators();
                                                    if (scanner.peek() == '}') break;
                                                    if ((++recCount & 0x3FFF) == 0 && isHeapCriticallyLow()) {
                                                        triggerLowMemoryRingGuard();
                                                        scanner.skipToObjectEnd();
                                                        break;
                                                    }
                                                    String rid = scanner.readQuotedString();
                                                    if (rid == null) break;
                                                    scanner.skipWhitespaceAndSeparators();
                                                    Record inst = scanner.readRecordInstance(rid, recComps, recCompTypes, recCtor, json);
                                                    if (inst != null) {
                                                        rawEng.persist(rid, inst);
                                                    }
                                                }
                                                scanner.skipWhitespace();
                                                if (scanner.peek() == '}') scanner.read();
                                            } else {
                                                scanner.skipValue();
                                            }
                                        } else {
                                            scanner.skipValue();
                                        }
                                    }
                                    scanner.skipWhitespace();
                                    if (scanner.peek() == '}') scanner.read();
                                } else {
                                    scanner.skipValue();
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    case "indexes" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '[') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != ']') {
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == ']') break;
                                String idxStr = scanner.readBalancedObject();
                                if (idxStr != null) {
                                    JsonObject io = json.fromJson(idxStr, JsonObject.class);
                                    if (io != null && io.has("name") && io.has("collection") && io.has("field")) {
                                        String name = io.getAsString("name");
                                        String collection = io.getAsString("collection");
                                        String field = io.getAsString("field");
                                        String type = io.has("type") ? io.getAsString("type") : "HASH";
                                        boolean unique = io.has("unique") && io.getAsBoolean("unique");
                                        var docEng = getDocumentEngine(collection);
                                        indexManager.applyReplicatedIndex(collection, name, field, type, unique, docEng);
                                    }
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == ']') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    default -> scanner.skipValue();
                }
            }
            return true;
        } catch (OutOfMemoryError oom) {
            triggerLowMemoryRingGuard();
            System.err.printf("[JettraDatabase:%s] Aviso: Límite de Heap alcanzado cargando '%s'. Activado modo Anillo/Off-Heap.%n",
                databaseName, metaFile);
            return true;
        } catch (Exception e) {
            System.err.printf("[JettraDatabase:%s] Error cargando metadatos y registros desde '%s': %s%n",
                databaseName, metaFile, e.getMessage());
            return false;
        }
    }

    /**
     * Escáner JSON Zero-Copy Off-Heap implementado con Project Panama FFM (Foreign Function & Memory API).
     * Mapea archivos masivos directamente a memoria nativa sin tocar el Heap de la JVM,
     * eliminando cualquier riesgo de OutOfMemoryError.
     */
    public static final class PanamaOffHeapScanner {
        private static final int KEY_CACHE_SIZE = 512;
        private static final int KEY_CACHE_MASK = KEY_CACHE_SIZE - 1;
        private static final int VAL_CACHE_SIZE = 65536;
        private static final int VAL_CACHE_MASK = VAL_CACHE_SIZE - 1;
        private static final int DOUBLE_CACHE_SIZE = 8192;
        private static final int DOUBLE_CACHE_MASK = DOUBLE_CACHE_SIZE - 1;

        private final MemorySegment segment;
        private final long size;
        private long offset = 0;

        private final String[] keyCacheValues = new String[KEY_CACHE_SIZE];
        private final long[] keyCacheSlices = new long[KEY_CACHE_SIZE];
        private final String[] valCacheValues = new String[VAL_CACHE_SIZE];
        private final long[] valCacheSlices = new long[VAL_CACHE_SIZE];
        private final Double[] doubleCache = new Double[DOUBLE_CACHE_SIZE];

        public PanamaOffHeapScanner(MemorySegment segment, long size) {
            this.segment = segment;
            this.size = size;
        }

        public boolean hasMore() {
            return offset < size;
        }

        public int peek() {
            if (offset >= size) return -1;
            return segment.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
        }

        public int read() {
            if (offset >= size) return -1;
            return segment.get(ValueLayout.JAVA_BYTE, offset++) & 0xFF;
        }

        public void skipWhitespaceAndSeparators() {
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                if (Character.isWhitespace((char) b) || b == ',' || b == ':') {
                    offset++;
                } else {
                    break;
                }
            }
        }

        public void skipWhitespace() {
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                if (Character.isWhitespace((char) b)) {
                    offset++;
                } else {
                    break;
                }
            }
        }

        private boolean sliceEquals(long posA, long posB, int len) {
            int i = 0;
            while (i + 8 <= len) {
                if (segment.get(ValueLayout.JAVA_LONG_UNALIGNED, posA + i) != segment.get(ValueLayout.JAVA_LONG_UNALIGNED, posB + i)) {
                    return false;
                }
                i += 8;
            }
            while (i < len) {
                if (segment.get(ValueLayout.JAVA_BYTE, posA + i) != segment.get(ValueLayout.JAVA_BYTE, posB + i)) {
                    return false;
                }
                i++;
            }
            return true;
        }

        private String unescapeSlice(long startPos, int len) {
            byte[] raw = segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE);
            String s = new String(raw, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char esc = s.charAt(++i);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 < s.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (Exception ignored) {}
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private String readStringInternal(String[] cacheVals, long[] cacheSlices, int cacheMask) {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '"') return null;
            offset++; // consume "
            long startPos = offset;
            boolean escaped = false;
            boolean hasEscape = false;
            int hash = 0x811c9dc5;
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                    hasEscape = true;
                } else if (b == '"') {
                    long len = (offset - 1) - startPos;
                    if (len <= 0) return "";
                    if (hasEscape) {
                        return unescapeSlice(startPos, (int) len);
                    }
                    if (cacheVals != null && len <= 96 && startPos < (1L << 47)) {
                        int slot = (hash ^ (hash >>> 16)) & cacheMask;
                        String cached = cacheVals[slot];
                        if (cached != null) {
                            long prevSlice = cacheSlices[slot];
                            int prevLen = (int) (prevSlice & 0xFFFFL);
                            long prevStart = prevSlice >>> 16;
                            if (prevLen == len && sliceEquals(prevStart, startPos, (int) len)) {
                                return cached;
                            }
                        }
                        String created = new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                        cacheVals[slot] = created;
                        cacheSlices[slot] = (startPos << 16) | (len & 0xFFFFL);
                        return created;
                    }
                    return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                } else if (cacheVals != null) {
                    hash = (hash ^ (b & 0xFF)) * 0x01000193;
                }
            }
            return null;
        }

        public String readQuotedString() {
            return readStringInternal(null, null, 0);
        }

        public String readKeyString() {
            return readStringInternal(keyCacheValues, keyCacheSlices, KEY_CACHE_MASK);
        }

        public String readCachedValueString() {
            return readStringInternal(valCacheValues, valCacheSlices, VAL_CACHE_MASK);
        }

        private Double cacheDouble(double d) {
            long bits = Double.doubleToRawLongBits(d);
            int slot = (int) ((bits ^ (bits >>> 32)) ^ (bits >>> 16)) & DOUBLE_CACHE_MASK;
            Double cached = doubleCache[slot];
            if (cached != null && Double.doubleToRawLongBits(cached) == bits) {
                return cached;
            }
            Double boxed = Double.valueOf(d);
            doubleCache[slot] = boxed;
            return boxed;
        }

        public Number readNumberValue() {
            skipWhitespace();
            if (offset >= size) return 0;
            long startPos = offset;
            boolean negative = false;
            byte first = segment.get(ValueLayout.JAVA_BYTE, offset);
            if (first == '-') {
                negative = true;
                offset++;
            } else if (first == '+') {
                offset++;
            }
            long intPart = 0;
            int intDigits = 0;
            boolean isFloating = false;
            boolean hasExponent = false;
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                if (b >= '0' && b <= '9') {
                    intPart = intPart * 10 + (b - '0');
                    intDigits++;
                    offset++;
                } else {
                    break;
                }
            }
            long fracPart = 0;
            long fracDiv = 1;
            int fracDigits = 0;
            if (offset < size && segment.get(ValueLayout.JAVA_BYTE, offset) == '.') {
                isFloating = true;
                offset++;
                while (offset < size) {
                    byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                    if (b >= '0' && b <= '9') {
                        if (fracDigits < 15) {
                            fracPart = fracPart * 10 + (b - '0');
                            fracDiv *= 10;
                        }
                        fracDigits++;
                        offset++;
                    } else {
                        break;
                    }
                }
            }
            if (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                if (b == 'e' || b == 'E') {
                    isFloating = true;
                    hasExponent = true;
                    offset++;
                    while (offset < size) {
                        byte eb = segment.get(ValueLayout.JAVA_BYTE, offset);
                        if ((eb >= '0' && eb <= '9') || eb == '-' || eb == '+') {
                            offset++;
                        } else {
                            break;
                        }
                    }
                }
            }
            if (intDigits == 0 && fracDigits == 0) {
                return 0;
            }
            if (hasExponent || intDigits > 18 || fracDigits > 9) {
                long len = offset - startPos;
                String numStr = new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                try {
                    if (isFloating) return cacheDouble(Double.parseDouble(numStr));
                    long l = Long.parseLong(numStr);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                    return l;
                } catch (Exception e) {
                    return 0;
                }
            }
            if (!isFloating) {
                long val = negative ? -intPart : intPart;
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) val);
                }
                return Long.valueOf(val);
            }
            double d = intPart + ((double) fracPart / (double) fracDiv);
            if (negative) d = -d;
            return cacheDouble(d);
        }

        public float[] readFloatArray(int expectedDims) {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '[') {
                skipValue();
                return null;
            }
            offset++; // consume '['
            float[] arr = new float[Math.max(1, expectedDims)];
            int idx = 0;
            while (offset < size) {
                skipWhitespaceAndSeparators();
                if (offset >= size) break;
                if (segment.get(ValueLayout.JAVA_BYTE, offset) == ']') {
                    offset++;
                    break;
                }
                Number n = readNumberValue();
                if (idx == arr.length) {
                    arr = Arrays.copyOf(arr, arr.length * 2);
                }
                arr[idx++] = (n != null) ? n.floatValue() : 0f;
            }
            return (idx == arr.length) ? arr : Arrays.copyOf(arr, idx);
        }

        public io.jettra.collections.map.UnifiedMap<String, Object> readDocumentObject(JettraJson fallbackJson) {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '{') return null;
            offset++; // consume '{'
            io.jettra.collections.map.UnifiedMap<String, Object> map = io.jettra.collections.map.UnifiedMap.newMap(8);
            while (offset < size) {
                skipWhitespaceAndSeparators();
                if (offset >= size) break;
                if (segment.get(ValueLayout.JAVA_BYTE, offset) == '}') {
                    offset++;
                    break;
                }
                String key = readKeyString();
                if (key == null) {
                    skipObjectBody();
                    break;
                }
                skipWhitespaceAndSeparators();
                Object val = readJsonValue(key, fallbackJson);
                map.put(key, val);
            }
            return map;
        }

        public Record readRecordInstance(
                String rid,
                java.lang.reflect.RecordComponent[] comps,
                Class<?>[] compTypes,
                java.lang.reflect.Constructor<?> ctor,
                JettraJson fallbackJson) {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '{') {
                skipValue();
                return null;
            }
            offset++; // consume '{'
            Object[] args = new Object[comps.length];
            while (offset < size) {
                skipWhitespaceAndSeparators();
                if (offset >= size) break;
                if (segment.get(ValueLayout.JAVA_BYTE, offset) == '}') {
                    offset++;
                    break;
                }
                String key = readKeyString();
                if (key == null) {
                    skipObjectBody();
                    break;
                }
                skipWhitespaceAndSeparators();
                int compIdx = -1;
                for (int i = 0; i < comps.length; i++) {
                    if (comps[i].getName().equals(key)) {
                        compIdx = i;
                        break;
                    }
                }
                if (compIdx >= 0) {
                    Object rawVal = readJsonValue(key, fallbackJson);
                    if (rawVal instanceof String s && s.equals(rid)) {
                        rawVal = rid;
                    }
                    args[compIdx] = coerceRecordValue(rawVal, compTypes[compIdx]);
                } else {
                    skipValue();
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null && compTypes[i].isPrimitive()) {
                    args[i] = coerceRecordValue(0, compTypes[i]);
                }
            }
            try {
                return (Record) ctor.newInstance(args);
            } catch (Exception e) {
                return null;
            }
        }

        private Object coerceRecordValue(Object val, Class<?> targetType) {
            if (val == null) return null;
            if (targetType.isInstance(val)) return val;
            if (targetType == String.class) return val.toString();
            if (targetType == double.class || targetType == Double.class) {
                return (val instanceof Number n) ? n.doubleValue() : Double.parseDouble(val.toString());
            }
            if (targetType == long.class || targetType == Long.class) {
                return (val instanceof Number n) ? n.longValue() : Long.parseLong(val.toString());
            }
            if (targetType == int.class || targetType == Integer.class) {
                return (val instanceof Number n) ? n.intValue() : Integer.parseInt(val.toString());
            }
            if (targetType == float.class || targetType == Float.class) {
                return (val instanceof Number n) ? n.floatValue() : Float.parseFloat(val.toString());
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return (val instanceof Boolean b) ? b : Boolean.parseBoolean(val.toString());
            }
            return val;
        }

        private Object readJsonValue(String key, JettraJson fallbackJson) {
            skipWhitespace();
            if (offset >= size) return null;
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
            if (b == '"') {
                boolean isUniqueKey = "_id".equals(key) || "_ref_detalle".equals(key) || "_ref_factura".equals(key)
                    || "razon_social".equals(key) || "rfc_tax_id".equals(key) || "auditId".equals(key)
                    || "folioFiscal".equals(key) || "hashSha256".equals(key) || "rfcReceptor".equals(key);
                return isUniqueKey ? readQuotedString() : readCachedValueString();
            }
            if (b == '-' || b == '+' || (b >= '0' && b <= '9')) {
                return readNumberValue();
            }
            if (b == 't' || b == 'T' || b == 'f' || b == 'F') {
                String tok = readPrimitiveToken();
                return "true".equalsIgnoreCase(tok) ? Boolean.TRUE : Boolean.FALSE;
            }
            if (b == 'n' || b == 'N') {
                readPrimitiveToken();
                return null;
            }
            if (b == '{') {
                String nested = readBalancedObject();
                return (nested != null && fallbackJson != null) ? fallbackJson.parse(nested) : null;
            }
            if (b == '[') {
                String nested = readBalancedArray();
                return (nested != null && fallbackJson != null) ? fallbackJson.parse(nested) : null;
            }
            return readPrimitiveToken();
        }

        public String readPrimitiveToken() {
            skipWhitespace();
            long startPos = offset;
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
                if (Character.isWhitespace((char) b) || b == ',' || b == '}' || b == ']' || b == ':') {
                    break;
                }
                offset++;
            }
            long len = offset - startPos;
            if (len <= 0) return null;
            return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        public String readBalancedObject() {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '{') return null;
            long startPos = offset++;
            int depth = 1;
            boolean inString = false;
            boolean escaped = false;
            while (offset < size && depth > 0) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') inString = false;
                } else {
                    if (b == '"') inString = true;
                    else if (b == '{') depth++;
                    else if (b == '}') depth--;
                }
            }
            if (depth != 0) return null;
            long len = offset - startPos;
            if (len > 67_108_864L) { // 64 MB por objeto/bloque de motor
                return null;
            }
            return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        public String readBalancedArray() {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '[') return null;
            long startPos = offset++;
            int depth = 1;
            boolean inString = false;
            boolean escaped = false;
            while (offset < size && depth > 0) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') inString = false;
                } else {
                    if (b == '"') inString = true;
                    else if (b == '[') depth++;
                    else if (b == ']') depth--;
                }
            }
            if (depth != 0) return null;
            long len = offset - startPos;
            if (len > 67_108_864L) {
                return null;
            }
            return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        public void skipToArrayEnd() {
            skipArrayBody();
            if (offset > 0 && segment.get(ValueLayout.JAVA_BYTE, offset - 1) == ']') {
                offset--;
            }
        }

        public void skipToObjectEnd() {
            skipObjectBody();
            if (offset > 0 && segment.get(ValueLayout.JAVA_BYTE, offset - 1) == '}') {
                offset--;
            }
        }

        public void skipArrayBody() {
            int depth = 1;
            boolean inString = false;
            boolean escaped = false;
            while (offset < size && depth > 0) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') inString = false;
                } else {
                    if (b == '"') inString = true;
                    else if (b == '[') depth++;
                    else if (b == ']') depth--;
                }
            }
        }

        public void skipObjectBody() {
            int depth = 1;
            boolean inString = false;
            boolean escaped = false;
            while (offset < size && depth > 0) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') inString = false;
                } else {
                    if (b == '"') inString = true;
                    else if (b == '{') depth++;
                    else if (b == '}') depth--;
                }
            }
        }

        public void skipValue() {
            skipWhitespaceAndSeparators();
            if (offset >= size) return;
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset);
            if (b == '{') {
                offset++;
                skipObjectBody();
            } else if (b == '[') {
                offset++;
                skipArrayBody();
            } else if (b == '"') {
                readQuotedString();
            } else {
                while (offset < size) {
                    byte ob = segment.get(ValueLayout.JAVA_BYTE, offset);
                    if (Character.isWhitespace((char) ob) || ob == ',' || ob == '}' || ob == ']') {
                        break;
                    }
                    offset++;
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            if (offHeapPageManager != null) {
                offHeapPageManager.close();
            }
        } catch (Exception ignored) {}
        try {
            offHeapDatabaseStore.close();
        } catch (Exception ignored) {}
        try {
            if (memoryEngine != null) {
                memoryEngine.close();
            }
        } catch (Exception ignored) {}
        try {
            memTable.close();
        } catch (Exception ignored) {}
    }

    public void drop() {
        close();
        documentEngines.clear();
        vectorEngines.clear();
        graphEngines.clear();
        timeSeriesEngines.clear();
        keyValueEngines.clear();
        geospatialEngines.clear();
        columnarEngines.clear();
        recordsEngines.clear();
        deleteStorageFiles();
    }

    private void deleteStorageFiles() {
        String[] paths = {
            config.getStoragePath(),
            config.getConfiguredStoragePath(),
            "./data/jettra",
            "data/jettra",
            "../data/jettra",
            "/jettra/data"
        };
        for (String pStr : paths) {
            if (pStr == null || pStr.isBlank()) continue;
            try {
                Path dir = Path.of(pStr);
                Files.deleteIfExists(dir.resolve(databaseName + "_meta.json"));
                Files.deleteIfExists(dir.resolve(databaseName + "_sstable" + config.getFileExtension()));
                Files.deleteIfExists(dir.resolve(databaseName + config.getFileExtension()));
                Files.deleteIfExists(dir.resolve(databaseName + "_sstable.jettra"));
                Files.deleteIfExists(dir.resolve(databaseName + ".jettra"));
                Files.deleteIfExists(dir.resolve(databaseName + "_wal.jettra"));
            } catch (Exception ignored) {}
        }
    }

    public String getEngineType(String collectionName) {
        if (documentEngines.containsKey(collectionName)) return "DOCUMENT";
        if (vectorEngines.containsKey(collectionName)) return "VECTOR";
        if (graphEngines.containsKey(collectionName)) return "GRAPH";
        if (timeSeriesEngines.containsKey(collectionName)) return "TIMESERIES";
        if (keyValueEngines.containsKey(collectionName)) return "KEYVALUE";
        if (geospatialEngines.containsKey(collectionName)) return "GEOSPATIAL";
        if (columnarEngines.containsKey(collectionName)) return "COLUMNAR";
        if (recordsEngines.containsKey(collectionName)) return "RECORDS";
        return "DOCUMENT";
    }

    public long getRecordCount(String collectionName) {
        if (documentEngines.containsKey(collectionName)) return documentEngines.get(collectionName).count();
        if (vectorEngines.containsKey(collectionName)) return vectorEngines.get(collectionName).size();
        if (graphEngines.containsKey(collectionName)) return graphEngines.get(collectionName).size();
        if (timeSeriesEngines.containsKey(collectionName)) return timeSeriesEngines.get(collectionName).size();
        if (keyValueEngines.containsKey(collectionName)) return keyValueEngines.get(collectionName).size();
        if (geospatialEngines.containsKey(collectionName)) return geospatialEngines.get(collectionName).size();
        if (columnarEngines.containsKey(collectionName)) return columnarEngines.get(collectionName).size();
        if (recordsEngines.containsKey(collectionName)) return recordsEngines.get(collectionName).size();
        return 0;
    }

    public java.util.Set<String> getDocumentEngineNames() { return java.util.Collections.unmodifiableSet(documentEngines.keySet()); }
    public java.util.Set<String> getVectorEngineNames() { return java.util.Collections.unmodifiableSet(vectorEngines.keySet()); }
    public java.util.Set<String> getGraphEngineNames() { return java.util.Collections.unmodifiableSet(graphEngines.keySet()); }
    public java.util.Set<String> getTimeSeriesEngineNames() { return java.util.Collections.unmodifiableSet(timeSeriesEngines.keySet()); }
    public java.util.Set<String> getKeyValueEngineNames() { return java.util.Collections.unmodifiableSet(keyValueEngines.keySet()); }
    public java.util.Set<String> getGeospatialEngineNames() { return java.util.Collections.unmodifiableSet(geospatialEngines.keySet()); }
    public java.util.Set<String> getColumnarEngineNames() { return java.util.Collections.unmodifiableSet(columnarEngines.keySet()); }

    public java.util.Set<String> getAllCollectionNames() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.addAll(documentEngines.keySet());
        all.addAll(vectorEngines.keySet());
        all.addAll(graphEngines.keySet());
        all.addAll(timeSeriesEngines.keySet());
        all.addAll(keyValueEngines.keySet());
        all.addAll(geospatialEngines.keySet());
        all.addAll(columnarEngines.keySet());
        all.addAll(recordsEngines.keySet());
        return all;
    }

    public long getAllRecordCount() {
        long count = 0;
        for (var eng : documentEngines.values()) {
            count += eng.count();
        }
        for (var eng : keyValueEngines.values()) {
            count += eng.size();
        }
        for (var eng : timeSeriesEngines.values()) {
            count += eng.size();
        }
        for (var eng : geospatialEngines.values()) {
            count += eng.size();
        }
        for (var eng : vectorEngines.values()) {
            count += eng.getAllVectors().size();
        }
        for (var eng : graphEngines.values()) {
            count += eng.size();
        }
        for (var eng : columnarEngines.values()) {
            count += eng.getRowCount();
        }
        for (var eng : recordsEngines.values()) {
            count += eng.count();
        }
        return count;
    }

    public boolean dropCollection(String name) {
        boolean removed = false;
        if (documentEngines.remove(name) != null) removed = true;
        if (vectorEngines.remove(name) != null) removed = true;
        if (graphEngines.remove(name) != null) removed = true;
        if (timeSeriesEngines.remove(name) != null) removed = true;
        if (keyValueEngines.remove(name) != null) removed = true;
        if (geospatialEngines.remove(name) != null) removed = true;
        if (columnarEngines.remove(name) != null) removed = true;
        if (recordsEngines.remove(name) != null) removed = true;
        if (removed) {
            saveToDisk();
        }
        return removed;
    }

    public String getDatabaseName() { return databaseName; }
    public NativeMemTable getMemTable() { return memTable; }
    public JettraStoreConfig getConfig() { return config; }
    public JettraIndexManager getIndexManager() { return indexManager; }

    public StorageMode getStorageMode() {
        return storageMode != null ? storageMode : StorageMode.JVM_RAM;
    }

    public void setStorageMode(StorageMode storageMode) {
        this.storageMode = (storageMode != null) ? storageMode : StorageMode.JVM_RAM;
        System.out.printf("[JettraDatabase:%s] Modo de almacenamiento conmutado a: %s (%s)%n",
            databaseName, this.storageMode.getCode(), this.storageMode.getDescription());
        if (this.storageMode.isDiskMemory() && memoryEngine != null) {
            // Sincronizar documentos existentes hacia JettraMemory (LSM Direct Off-Heap)
            io.jettra.json.JettraJson jsonHelper = new io.jettra.json.JettraJson();
            for (var entry : documentEngines.entrySet()) {
                String col = entry.getKey();
                for (var doc : entry.getValue()) {
                    String id = String.valueOf(doc.getOrDefault("_id", java.util.UUID.randomUUID().toString()));
                    try {
                        putRecordDiskMemory((byte) 1, col + ":" + id, jsonHelper.toJson(doc).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public JettraMemoryEngine getMemoryEngine() {
        return memoryEngine;
    }

    public void putRecordDiskMemory(byte engineId, String key, byte[] payload) throws IOException {
        if (memoryEngine != null && memoryEngine.getStoreConnector() != null) {
            memoryEngine.getStoreConnector().putRecord(engineId, key, payload);
        }
    }

    public byte[] getRecordDiskMemory(byte engineId, String key) throws IOException {
        if (memoryEngine != null && memoryEngine.getStoreConnector() != null) {
            var rec = memoryEngine.getStoreConnector().getRecord(engineId, key);
            return rec != null ? rec.payload() : null;
        }
        return null;
    }

    public boolean deleteRecordDiskMemory(byte engineId, String key) throws IOException {
        if (memoryEngine != null && memoryEngine.getStoreConnector() != null) {
            return memoryEngine.getStoreConnector().deleteRecord(engineId, key);
        }
        return false;
    }

    public StorageMetrics getMemoryMetrics() {
        return memoryEngine != null ? memoryEngine.getMetrics() : null;
    }

    public void putOffHeapBinary(String key, byte[] data) throws IOException {
        if (memoryEngine != null) {
            memoryEngine.put(key, data);
        }
    }

    public byte[] getOffHeapBinary(String key) throws IOException {
        return memoryEngine != null ? memoryEngine.get(key) : null;
    }

    public boolean deleteOffHeapBinary(String key) throws IOException {
        return memoryEngine != null && memoryEngine.delete(key);
    }

    public void putRecordOffHeap(String key, byte[] payload) {
        offHeapDatabaseStore.put(key, payload);
    }

    public byte[] getRecordOffHeap(String key) {
        return offHeapDatabaseStore.get(key);
    }

    public MemorySegment getRecordOffHeapSlice(String key) {
        return offHeapDatabaseStore.getSlice(key);
    }

    public boolean deleteRecordOffHeap(String key) {
        return offHeapDatabaseStore.remove(key);
    }

    public OffHeapSlottedMap getOffHeapDatabaseStore() {
        return offHeapDatabaseStore;
    }

    public OffHeapPageManager getOffHeapPageManager() {
        return offHeapPageManager;
    }

    public JettraTransactionContext newTransactionContext(long txId) {
        return new JettraTransactionContext(txId);
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregate(
            String collection, 
            List<String> groupByFields, 
            List<io.jettra.store.calc.JettraAggregation.AggregateSpec> specs) {
        DocumentEngine engine = getDocumentEngine(collection);
        return io.jettra.store.calc.JettraAggregation.aggregate(engine, groupByFields, specs);
    }

    public io.jettra.store.calc.JettraAggregation.AggregationResult aggregate(
            String collection, 
            String groupByField, 
            io.jettra.store.calc.JettraAggregation.AggregateSpec... specs) {
        List<String> groups = (groupByField != null && !groupByField.isBlank()) ? List.of(groupByField) : Collections.emptyList();
        return aggregate(collection, groups, List.of(specs));
    }

}

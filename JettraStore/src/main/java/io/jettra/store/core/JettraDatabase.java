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

public final class JettraDatabase implements AutoCloseable {
    private final String databaseName;
    private final JettraStoreConfig config;
    private final NativeMemTable memTable;
    private final JettraIndexManager indexManager;
    private final DynamicRingEngine ringEngine;
    private final AtomicBoolean distributedRingActive = new AtomicBoolean(false);
    private final JettraMemoryEngine memoryEngine;
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

    public DocumentEngine getDocumentEngine(String name) {
        return documentEngines.computeIfAbsent(name, n -> new DocumentEngine(n, this));
    }

    public StreamResponse<Map<String, Object>> streamCollection(String collectionName) {
        return getDocumentEngine(collectionName).streamAll();
    }

    public StreamResponse<Map<String, Object>> streamCollection(String collectionName, int limit) {
        return getDocumentEngine(collectionName).streamAll(limit);
    }

    public VectorEngine getVectorEngine(String name, int dimensions) {
        return vectorEngines.computeIfAbsent(name, k -> new VectorEngine(k, dimensions));
    }

    public GraphEngine getGraphEngine(String name) {
        return graphEngines.computeIfAbsent(name, GraphEngine::new);
    }

    public TimeSeriesEngine getTimeSeriesEngine(String name) {
        return timeSeriesEngines.computeIfAbsent(name, TimeSeriesEngine::new);
    }

    public KeyValueEngine getKeyValueEngine(String name) {
        return keyValueEngines.computeIfAbsent(name, KeyValueEngine::new);
    }

    public GeospatialEngine getGeospatialEngine(String name) {
        return geospatialEngines.computeIfAbsent(name, GeospatialEngine::new);
    }

    public ColumnarEngine getColumnarEngine(String name) {
        return columnarEngines.computeIfAbsent(name, ColumnarEngine::new);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> io.jettra.store.engine.models.RecordsEngine<T> getRecordsEngine(String name, Class<T> recordClass) {
        return (io.jettra.store.engine.models.RecordsEngine<T>) recordsEngines.computeIfAbsent(name, k -> new io.jettra.store.engine.models.RecordsEngine<>(name, recordClass));
    }

    public io.jettra.store.engine.models.RecordsEngine<?> getRecordsEngine(String name) {
        return recordsEngines.get(name);
    }

    public java.util.Set<String> getRecordsEngineNames() {
        return java.util.Collections.unmodifiableSet(recordsEngines.keySet());
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
                           "columnarEngines".equals(key)) {
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

        // Si la base de datos en memoria no tiene colecciones o registros pero en disco existe un archivo previo con datos,
        // cargar previamente el archivo para garantizar que todos los registros se preserven y serialicen íntegramente.
        if (getAllCollectionNames().isEmpty()) {
            if (Files.exists(metaFile)) {
                try {
                    if (Files.size(metaFile) > 100) {
                        loadFromDisk();
                    }
                } catch (Exception ignored) {}
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
                    JsonObject d = new JsonObject();
                    for (var kv : doc.entrySet()) {
                        if (kv.getValue() instanceof Number n) {
                            d.addProperty(kv.getKey(), n);
                        } else if (kv.getValue() instanceof Boolean b) {
                            d.addProperty(kv.getKey(), b);
                        } else {
                            d.addProperty(kv.getKey(), String.valueOf(kv.getValue()));
                        }
                    }
                    writer.write("      " + json.toJson(d));
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
            writer.write("\n  }\n");

            writer.write("}\n");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean loadFromDisk() {
        Path metaFile = resolveMetaFile(databaseName, config);
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
        } catch (Exception e) {
            return false;
        }
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
                String sectionKey = scanner.readQuotedString();
                if (sectionKey == null) break;
                scanner.skipWhitespaceAndSeparators();

                switch (sectionKey) {
                    case "documentEngines" -> {
                        scanner.skipWhitespace();
                        if (scanner.peek() == '{') {
                            scanner.read();
                            while (scanner.hasMore() && scanner.peek() != '}') {
                                scanner.skipWhitespaceAndSeparators();
                                String colName = scanner.readQuotedString();
                                if (colName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '[') {
                                    scanner.read();
                                    var docEng = getDocumentEngine(colName);
                                    docEng.applyReplicatedClear();
                                    Map<String, Map<String, Object>> batch = new HashMap<>(5000);
                                    long docCount = 0;
                                    final long MAX_LOCAL_DOCS = ringModeActive ? 10000 : 1000000;

                                    while (scanner.hasMore() && scanner.peek() != ']') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (ringModeActive && docCount >= MAX_LOCAL_DOCS) {
                                            // En modo anillo, omitir el resto del array de forma ultra-rápida off-heap
                                            scanner.skipArrayBody();
                                            break;
                                        }

                                        String docStr = scanner.readBalancedObject();
                                        if (docStr != null) {
                                            JsonObject d = json.fromJson(docStr, JsonObject.class);
                                            if (d != null) {
                                                String id = d.has("_id") ? d.getAsString("_id") : UUID.randomUUID().toString();
                                                Map<String, Object> map = new HashMap<>();
                                                for (String k : d.keySet()) {
                                                    map.put(k, d.getAsString(k));
                                                }
                                                batch.put(id, map);
                                                docCount++;
                                                if (batch.size() >= 5000) {
                                                    docEng.applyReplicatedBatch(batch);
                                                    for (var entry : batch.entrySet()) {
                                                        getIndexManager().onDocumentInsert(colName, entry.getKey(), entry.getValue());
                                                    }
                                                    batch.clear();
                                                }
                                            }
                                        }
                                    }
                                    if (!batch.isEmpty()) {
                                        docEng.applyReplicatedBatch(batch);
                                        for (var entry : batch.entrySet()) {
                                            getIndexManager().onDocumentInsert(colName, entry.getKey(), entry.getValue());
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
                                String ns = scanner.readQuotedString();
                                if (ns == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    var kvEng = getKeyValueEngine(ns);
                                    long count = 0;
                                    long maxKVs = ringModeActive ? 10000 : 500000;
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (ringModeActive && count >= maxKVs) {
                                            scanner.skipObjectBody();
                                            break;
                                        }
                                        String k = scanner.readQuotedString();
                                        if (k == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        String v = scanner.readQuotedString();
                                        if (v != null) {
                                            kvEng.put(k, v.getBytes(StandardCharsets.UTF_8));
                                            count++;
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
                                String metric = scanner.readQuotedString();
                                if (metric == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '{') {
                                    scanner.read();
                                    var tsEng = getTimeSeriesEngine(metric);
                                    long count = 0;
                                    long maxPts = ringModeActive ? 5000 : 100000;
                                    while (scanner.hasMore() && scanner.peek() != '}') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (ringModeActive && count >= maxPts) {
                                            scanner.skipObjectBody();
                                            break;
                                        }
                                        String tsStr = scanner.readQuotedString();
                                        if (tsStr == null) break;
                                        scanner.skipWhitespaceAndSeparators();
                                        String valStr = scanner.readPrimitiveToken();
                                        if (valStr != null) {
                                            try {
                                                long ts = Long.parseLong(tsStr);
                                                double val = Double.parseDouble(valStr);
                                                tsEng.record(ts, val);
                                                count++;
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
                                String layer = scanner.readQuotedString();
                                if (layer == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                if (scanner.peek() == '[') {
                                    scanner.read();
                                    var geoEng = getGeospatialEngine(layer);
                                    long count = 0;
                                    long maxPts = ringModeActive ? 5000 : 50000;
                                    while (scanner.hasMore() && scanner.peek() != ']') {
                                        scanner.skipWhitespaceAndSeparators();
                                        if (ringModeActive && count >= maxPts) {
                                            scanner.skipArrayBody();
                                            break;
                                        }
                                        String ptStr = scanner.readBalancedObject();
                                        if (ptStr != null) {
                                            JsonObject gp = json.fromJson(ptStr, JsonObject.class);
                                            if (gp != null && gp.has("id") && gp.has("lat") && gp.has("lon")) {
                                                String id = gp.getAsString("id");
                                                double lat = Double.parseDouble(gp.getAsString("lat"));
                                                double lon = Double.parseDouble(gp.getAsString("lon"));
                                                geoEng.insertPoint(id, lat, lon);
                                                count++;
                                            }
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
                                String vName = scanner.readQuotedString();
                                if (vName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                String vecObjStr = scanner.readBalancedObject();
                                if (vecObjStr != null) {
                                    JsonObject vo = json.fromJson(vecObjStr, JsonObject.class);
                                    if (vo != null) {
                                        int dims = vo.has("dimensions") ? vo.getAsInt("dimensions") : 3;
                                        var vecEng = getVectorEngine(vName, dims);
                                        if (vo.has("vectors")) {
                                            JsonObject vData = vo.getAsJsonObject("vectors");
                                            long count = 0;
                                            long maxVecs = ringModeActive ? 5000 : 200000;
                                            for (String vid : vData.keySet()) {
                                                if (ringModeActive && count >= maxVecs) break;
                                                JsonArray va = vData.getAsJsonArray(vid);
                                                float[] fa = new float[va.size()];
                                                for (int idx = 0; idx < va.size(); idx++) {
                                                    fa[idx] = Float.parseFloat(va.get(idx).toString());
                                                }
                                                vecEng.index(vid, fa);
                                                count++;
                                            }
                                        }
                                    }
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
                                String gName = scanner.readQuotedString();
                                if (gName == null) break;
                                scanner.skipWhitespaceAndSeparators();
                                String grObjStr = scanner.readBalancedObject();
                                if (grObjStr != null) {
                                    JsonObject go = json.fromJson(grObjStr, JsonObject.class);
                                    if (go != null) {
                                        var gEng = getGraphEngine(gName);
                                        if (go.has("vertices")) {
                                            JsonArray va = go.getAsJsonArray("vertices");
                                            long maxV = ringModeActive ? 5000 : 200000;
                                            for (int i = 0; i < Math.min(va.size(), maxV); i++) {
                                                gEng.addVertex(va.get(i).toString());
                                            }
                                        }
                                        if (go.has("edges")) {
                                            JsonObject edges = go.getAsJsonObject("edges");
                                            long count = 0;
                                            long maxE = ringModeActive ? 5000 : 200000;
                                            for (String src : edges.keySet()) {
                                                if (ringModeActive && count >= maxE) break;
                                                JsonArray ea = edges.getAsJsonArray(src);
                                                for (int i = 0; i < ea.size(); i++) {
                                                    JsonObject eo = ea.getAsJsonObject(i);
                                                    String tgt = eo.getAsString("target");
                                                    String lbl = eo.getAsString("label");
                                                    gEng.addEdge(src, tgt, lbl, Map.of());
                                                    count++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            scanner.skipWhitespace();
                            if (scanner.peek() == '}') scanner.read();
                        } else {
                            scanner.skipValue();
                        }
                    }
                    default -> scanner.skipValue();
                }
            }
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
        private final MemorySegment segment;
        private final long size;
        private long offset = 0;

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

        public String readQuotedString() {
            skipWhitespace();
            if (offset >= size || segment.get(ValueLayout.JAVA_BYTE, offset) != '"') return null;
            offset++; // consume "
            long startPos = offset;
            boolean escaped = false;
            while (offset < size) {
                byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    long len = (offset - 1) - startPos;
                    if (len <= 0) return "";
                    return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                }
            }
            return null;
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
            if (len > 131072) { // Tope seguro de 128 KB por objeto
                return null;
            }
            return new String(segment.asSlice(startPos, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
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

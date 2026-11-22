package io.jettra.store.engine.index;

import io.jettra.collections.map.UnifiedMap;
import io.jettra.collections.set.UnifiedSet;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.models.DocumentEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestor de índices secundarios de JettraStore optimizado con JettraCollections.
 * Aplica almacenamiento compacto (Singleton / Zero-Set), pre-dimensionado configurado
 * en microprofile-config.properties (jettra.index.initial.capacity) y streaming perezoso sin toArray()
 * para eliminar de raíz el error OutOfMemoryError en rehash() y colecciones masivas.
 */
public final class JettraIndexManager {
    public record IndexInfo(
        String name, 
        String collection, 
        String field, 
        String type, 
        boolean unique, 
        long entriesCount, 
        long createdAt
    ) {}

    private final String databaseName;
    private final JettraStoreConfig config;
    private final Map<String, IndexInfo> indexMetadata = new ConcurrentHashMap<>();
    // indexName -> (fieldValue -> Object (String or UnifiedSet<String>))
    private final Map<String, UnifiedMap<Object, Object>> indexData = new ConcurrentHashMap<>();
    // indexName -> TreeMap ordenado por valor numérico (Double) para consultas exactas y por rango O(log N)
    private final Map<String, TreeMap<Double, UnifiedSet<String>>> numericIndexData = new ConcurrentHashMap<>();
    // "collection:field" -> secuencia atómica persistente para índices AUTO_INCREMENT
    private final Map<String, AtomicLong> autoIncrementSequences = new ConcurrentHashMap<>();

    public JettraIndexManager(String databaseName) {
        this(databaseName, JettraStoreConfig.load());
    }

    public JettraIndexManager(String databaseName, JettraStoreConfig config) {
        this.databaseName = databaseName;
        this.config = (config != null) ? config : JettraStoreConfig.load();
        loadIndexesAndSequencesFromDisk();
    }

    private static String sequenceKey(String collection, String field) {
        return (collection != null ? collection.trim().toLowerCase(Locale.ROOT) : "")
                + ":" + (field != null ? field.trim().toLowerCase(Locale.ROOT) : "");
    }

    public static boolean isNumericType(String type) {
        if (type == null) return false;
        String t = type.trim().toUpperCase(Locale.ROOT);
        return t.equals("NUMERIC") || t.equals("AUTO_INCREMENT") || t.equals("AUTOINCREMENT")
                || t.equals("INT") || t.equals("INTEGER") || t.equals("LONG") || t.equals("DOUBLE") || t.equals("DECIMAL");
    }

    public static boolean isAutoIncrementType(String type) {
        if (type == null) return false;
        String t = type.trim().toUpperCase(Locale.ROOT);
        return t.equals("AUTO_INCREMENT") || t.equals("AUTOINCREMENT");
    }

    public static Double toNumericDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Path resolveIndexesMetadataFile() {
        String base = (config != null && config.getStoragePath() != null) ? config.getStoragePath() : ".";
        return Path.of(base, databaseName + "_indexes.json");
    }

    private Path resolveSequencesFile() {
        String base = (config != null && config.getStoragePath() != null) ? config.getStoragePath() : ".";
        return Path.of(base, databaseName + "_sequences.json");
    }

    public synchronized void saveIndexesAndSequencesToDisk() {
        try {
            Path idxFile = resolveIndexesMetadataFile();
            if (idxFile.getParent() != null) Files.createDirectories(idxFile.getParent());
            StringBuilder idxSb = new StringBuilder("[\n");
            int i = 0;
            for (IndexInfo info : indexMetadata.values()) {
                if (i++ > 0) idxSb.append(",\n");
                idxSb.append("  {\"name\":\"").append(escapeJson(info.name()))
                     .append("\",\"collection\":\"").append(escapeJson(info.collection()))
                     .append("\",\"field\":\"").append(escapeJson(info.field()))
                     .append("\",\"type\":\"").append(escapeJson(info.type()))
                     .append("\",\"unique\":").append(info.unique())
                     .append(",\"entriesCount\":").append(info.entriesCount())
                     .append(",\"createdAt\":").append(info.createdAt()).append("}");
            }
            idxSb.append("\n]");
            Path tmpIdx = idxFile.resolveSibling(idxFile.getFileName().toString() + ".tmp");
            Files.writeString(tmpIdx, idxSb.toString(), StandardCharsets.UTF_8);
            Files.move(tmpIdx, idxFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            Path seqFile = resolveSequencesFile();
            if (seqFile.getParent() != null) Files.createDirectories(seqFile.getParent());
            StringBuilder seqSb = new StringBuilder("{\n");
            int j = 0;
            for (var entry : autoIncrementSequences.entrySet()) {
                if (j++ > 0) seqSb.append(",\n");
                seqSb.append("  \"").append(escapeJson(entry.getKey())).append("\": ").append(entry.getValue().get());
            }
            seqSb.append("\n}");
            Path tmpSeq = seqFile.resolveSibling(seqFile.getFileName().toString() + ".tmp");
            Files.writeString(tmpSeq, seqSb.toString(), StandardCharsets.UTF_8);
            Files.move(tmpSeq, seqFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {}
    }

    public final synchronized void loadIndexesAndSequencesFromDisk() {
        try {
            Path seqFile = resolveSequencesFile();
            if (Files.exists(seqFile)) {
                String raw = Files.readString(seqFile, StandardCharsets.UTF_8);
                if (!raw.isBlank()) {
                    Object parsed = new JettraJson().parse(raw);
                    if (parsed instanceof Map<?, ?> map) {
                        map.forEach((k, v) -> {
                            if (k != null && v instanceof Number n) {
                                autoIncrementSequences.computeIfAbsent(String.valueOf(k), x -> new AtomicLong(0))
                                        .accumulateAndGet(n.longValue(), Math::max);
                            }
                        });
                    }
                }
            }
            Path idxFile = resolveIndexesMetadataFile();
            if (Files.exists(idxFile)) {
                String raw = Files.readString(idxFile, StandardCharsets.UTF_8);
                if (!raw.isBlank()) {
                    Object parsed = new JettraJson().parse(raw);
                    if (parsed instanceof JsonArray arr) {
                        for (int i = 0; i < arr.size(); i++) {
                            Object item = arr.get(i);
                            if (item instanceof JsonObject o) {
                                String name = o.get("name") != null ? String.valueOf(o.get("name")) : null;
                                String col = o.get("collection") != null ? String.valueOf(o.get("collection")) : null;
                                String field = o.get("field") != null ? String.valueOf(o.get("field")) : null;
                                String type = o.get("type") != null ? String.valueOf(o.get("type")) : "BTREE";
                                boolean unique = Boolean.TRUE.equals(o.get("unique"));
                                long entries = o.get("entriesCount") instanceof Number n ? n.longValue() : 0L;
                                long created = o.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
                                if (name != null && col != null && field != null) {
                                    IndexInfo info = new IndexInfo(name, col, field, type.toUpperCase(Locale.ROOT), unique, entries, created);
                                    indexMetadata.putIfAbsent(name, info);
                                    indexData.computeIfAbsent(name, k -> UnifiedMap.newMap(64));
                                    if (isNumericType(type)) {
                                        numericIndexData.computeIfAbsent(name, k -> new TreeMap<>());
                                    }
                                    if (isAutoIncrementType(type)) {
                                        autoIncrementSequences.computeIfAbsent(sequenceKey(col, field), k -> new AtomicLong(0));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Genera el siguiente valor secuencial atómico para un campo {@code AUTO_INCREMENT}
     * y lo persiste de forma atómica para garantizar cero colisiones tras reinicios o failover.
     */
    public synchronized long nextAutoIncrementValue(String collection, String field) {
        String key = sequenceKey(collection, field);
        long next = autoIncrementSequences.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        saveIndexesAndSequencesToDisk();
        return next;
    }

    public long getAutoIncrementCurrentValue(String collection, String field) {
        AtomicLong seq = autoIncrementSequences.get(sequenceKey(collection, field));
        return seq != null ? seq.get() : 0L;
    }

    public synchronized void advanceSequenceIfHigher(String collection, String field, long value) {
        String key = sequenceKey(collection, field);
        AtomicLong seq = autoIncrementSequences.computeIfAbsent(key, k -> new AtomicLong(0));
        long prev = seq.get();
        if (value > prev) {
            seq.accumulateAndGet(value, Math::max);
            saveIndexesAndSequencesToDisk();
        }
    }

    /**
     * Retorna todos los campos con índice {@code AUTO_INCREMENT} definidos para una colección/bucket.
     */
    public List<IndexInfo> getAutoIncrementIndexes(String collection) {
        List<IndexInfo> out = new ArrayList<>();
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection) && isAutoIncrementType(info.type())) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * Aplica autogeneración de campos {@code AUTO_INCREMENT} y valida restricciones numéricas/unicidad
     * antes de insertar un registro en el bucket.
     */
    public synchronized void prepareAndValidateForInsert(String collection, String recordId, Map<String, Object> record) {
        if (collection == null || record == null) return;
        for (IndexInfo info : indexMetadata.values()) {
            if (!info.collection().equalsIgnoreCase(collection)) continue;
            String field = info.field();
            Object rawVal = record.get(field);

            if (isAutoIncrementType(info.type())) {
                if (rawVal == null || (rawVal instanceof String s && s.isBlank())
                        || (rawVal instanceof Number n && n.longValue() <= 0)) {
                    long generated = nextAutoIncrementValue(collection, field);
                    record.put(field, generated);
                } else {
                    Double num = toNumericDouble(rawVal);
                    if (num == null) {
                        throw new IllegalArgumentException(String.format(
                            "El campo AUTO_INCREMENT '%s' del bucket '%s' requiere un valor numérico entero. Recibido: '%s'.",
                            field, collection, rawVal));
                    }
                    long longVal = num.longValue();
                    Set<String> existing = findDocIds(collection, field, longVal);
                    if (!existing.isEmpty() && !(existing.size() == 1 && existing.contains(recordId))) {
                        throw new IllegalArgumentException(String.format(
                            "Colisión de valor único en índice AUTO_INCREMENT '%s' (%s=%d) del bucket '%s'.",
                            info.name(), field, longVal, collection));
                    }
                    record.put(field, longVal);
                    advanceSequenceIfHigher(collection, field, longVal);
                }
            } else if (isNumericType(info.type()) && rawVal != null) {
                Double num = toNumericDouble(rawVal);
                if (num == null) {
                    throw new IllegalArgumentException(String.format(
                        "El índice numérico '%s' sobre '%s.%s' requiere un valor numérico (entero o decimal). Recibido: '%s'.",
                        info.name(), collection, field, rawVal));
                }
                if (info.unique()) {
                    Set<String> existing = findDocIds(collection, field, num);
                    if (!existing.isEmpty() && !(existing.size() == 1 && existing.contains(recordId))) {
                        throw new IllegalArgumentException(String.format(
                            "Violación de índice numérico UNIQUE '%s' (%s=%s) en bucket '%s'.",
                            info.name(), field, rawVal, collection));
                    }
                }
            } else if (info.unique() && rawVal != null) {
                Set<String> existing = findDocIds(collection, field, rawVal);
                if (!existing.isEmpty() && !(existing.size() == 1 && existing.contains(recordId))) {
                    throw new IllegalArgumentException(String.format(
                        "Violación de índice UNIQUE '%s' (%s=%s) en bucket '%s'.",
                        info.name(), field, rawVal, collection));
                }
            }
        }
    }

    public synchronized IndexInfo createIndex(String collection, String indexName, String field, String type, boolean unique, DocumentEngine docEngine) {
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == io.jettra.store.cluster.ClusterNode.Role.SECONDARY) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite crear índices directamente.",
                config.getNodeId()
            ));
        }
        if (indexMetadata.containsKey(indexName)) {
            throw new IllegalArgumentException("Index '" + indexName + "' already exists in database '" + databaseName + "'.");
        }
        String normType = type != null ? type.trim().toUpperCase(Locale.ROOT) : "BTREE";
        boolean effectiveUnique = unique || isAutoIncrementType(normType);
        IndexInfo info = new IndexInfo(indexName, collection, field, normType, effectiveUnique, 0, System.currentTimeMillis());
        indexMetadata.put(indexName, info);
        indexData.put(indexName, UnifiedMap.newMap(128));
        if (isNumericType(normType)) {
            numericIndexData.put(indexName, new TreeMap<>());
        }
        if (isAutoIncrementType(normType)) {
            autoIncrementSequences.computeIfAbsent(sequenceKey(collection, field), k -> new AtomicLong(0));
        }

        if (docEngine != null) {
            rebuildIndex(indexName, docEngine);
        }
        saveIndexesAndSequencesToDisk();

        // Propagar orden Raft de creación de índice si este nodo es PRIMARY
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == io.jettra.store.cluster.ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicateCreateIndex(databaseName, collection, indexName, field, type, unique);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_INDEX_CREATED,
                config.getNodeId(), "cluster",
                String.format("Índice '%s' en colección '%s' de base '%s' creado y replicado al clúster", indexName, collection, databaseName),
                "db=" + databaseName + ",col=" + collection + ",idx=" + indexName
            );
        }

        return indexMetadata.get(indexName);
    }

    /**
     * Aplica la creación de índice proveniente de una trama de replicación Raft
     * emitida por el nodo primario.
     */
    public synchronized IndexInfo applyReplicatedIndex(String collection, String indexName, String field, String type, boolean unique, DocumentEngine docEngine) {
        String normType = type != null ? type.trim().toUpperCase(Locale.ROOT) : "BTREE";
        boolean effectiveUnique = unique || isAutoIncrementType(normType);
        IndexInfo info = new IndexInfo(indexName, collection, field, normType, effectiveUnique, 0, System.currentTimeMillis());
        indexMetadata.put(indexName, info);
        indexData.computeIfAbsent(indexName, k -> UnifiedMap.newMap(128));
        if (isNumericType(normType)) {
            numericIndexData.computeIfAbsent(indexName, k -> new TreeMap<>());
        }
        if (isAutoIncrementType(normType)) {
            autoIncrementSequences.computeIfAbsent(sequenceKey(collection, field), k -> new AtomicLong(0));
        }

        if (docEngine != null) {
            rebuildIndex(indexName, docEngine);
        }
        saveIndexesAndSequencesToDisk();
        return indexMetadata.get(indexName);
    }

    public synchronized boolean dropIndex(String indexName) {
        if (config != null && config.isClusterMultinodeActive() && config.getNodeRole() == io.jettra.store.cluster.ClusterNode.Role.SECONDARY) {
            throw new UnsupportedOperationException(String.format(
                "[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite eliminar índices directamente.",
                config.getNodeId()
            ));
        }
        indexData.remove(indexName);
        numericIndexData.remove(indexName);
        boolean removed = indexMetadata.remove(indexName) != null;
        if (removed) {
            saveIndexesAndSequencesToDisk();
        }
        if (removed && config != null && config.isClusterMultinodeActive() && config.getNodeRole() == io.jettra.store.cluster.ClusterNode.Role.PRIMARY) {
            io.jettra.store.JettraStoreServer server = io.jettra.store.JettraStoreServer.getActiveInstance();
            if (server != null) {
                server.replicateDropIndex(databaseName, indexName);
            }
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_INDEX_DROPPED,
                config.getNodeId(), "cluster",
                String.format("Índice '%s' de base '%s' eliminado y propagado al clúster", indexName, databaseName),
                "db=" + databaseName + ",idx=" + indexName
            );
        }
        return removed;
    }

    public synchronized boolean applyReplicatedDropIndex(String indexName) {
        indexData.remove(indexName);
        numericIndexData.remove(indexName);
        boolean removed = indexMetadata.remove(indexName) != null;
        if (removed) saveIndexesAndSequencesToDisk();
        return removed;
    }

    public synchronized IndexInfo rebuildIndex(String indexName, DocumentEngine docEngine) {
        IndexInfo info = indexMetadata.get(indexName);
        if (info == null) {
            throw new NoSuchElementException("Index '" + indexName + "' not found.");
        }

        // 1. Capacidad inicial inteligente y acotada para evitar reservar grandes tablas vacías en heap
        int configuredCap = (config != null) ? config.getIndexInitialCapacity() : 256;
        int initialCap;
        if (docEngine != null && docEngine.count() > 0) {
            long docCount = docEngine.count();
            initialCap = (int) Math.max(16, Math.min(docCount, Math.max(configuredCap, 16)));
        } else {
            initialCap = Math.max(16, Math.min(configuredCap, 64));
        }

        // 2. Almacenamiento compacto: fieldValue -> String (si es único) o UnifiedSet (si múltiple)
        UnifiedMap<Object, Object> inverted = new UnifiedMap<>(initialCap);
        boolean numeric = isNumericType(info.type());
        boolean autoInc = isAutoIncrementType(info.type());
        TreeMap<Double, UnifiedSet<String>> numTree = numeric ? new TreeMap<>() : null;
        long[] maxSeq = new long[]{0L};
        long[] count = new long[1];
        int maxKeys = (config != null) ? config.getIndexMaxInMemoryKeys() : 100000;
        boolean compactStorage = (config == null) || config.isIndexCompactStorage();

        if (docEngine != null) {
            docEngine.forEach(doc -> {
                if (doc != null) {
                    Object rawId = doc.get("_id");
                    if (rawId != null) {
                        String id = rawId.toString();
                        Object val = doc.get(info.field());
                        if (val != null) {
                            if (numeric) {
                                Double num = toNumericDouble(val);
                                if (num != null) {
                                    numTree.computeIfAbsent(num, k -> new UnifiedSet<>(2)).add(id);
                                    if (autoInc && num.longValue() > maxSeq[0]) {
                                        maxSeq[0] = num.longValue();
                                    }
                                }
                            }
                            if (compactStorage) {
                                Object existing = inverted.get(val);
                                if (existing == null) {
                                    if (inverted.size() < maxKeys) {
                                        inverted.put(val, id);
                                        count[0]++;
                                    }
                                } else if (existing instanceof String firstId) {
                                    if (!firstId.equals(id)) {
                                        UnifiedSet<String> set = new UnifiedSet<>(4);
                                        set.add(firstId);
                                        set.add(id);
                                        inverted.put(val, set);
                                        count[0]++;
                                    }
                                } else if (existing instanceof UnifiedSet<?> rawSet) {
                                    @SuppressWarnings("unchecked")
                                    UnifiedSet<String> set = (UnifiedSet<String>) rawSet;
                                    if (set.add(id)) {
                                        count[0]++;
                                    }
                                }
                            } else {
                                Object existing = inverted.get(val);
                                UnifiedSet<String> set;
                                if (existing instanceof UnifiedSet<?> rawSet) {
                                    @SuppressWarnings("unchecked")
                                    UnifiedSet<String> s = (UnifiedSet<String>) rawSet;
                                    set = s;
                                } else {
                                    set = new UnifiedSet<>(4);
                                    inverted.put(val, set);
                                }
                                if (set.add(id)) {
                                    count[0]++;
                                }
                            }
                        }
                    }
                }
            });
        }

        indexData.put(indexName, inverted);
        if (numeric && numTree != null) {
            numericIndexData.put(indexName, numTree);
        }
        if (autoInc && maxSeq[0] > 0) {
            advanceSequenceIfHigher(info.collection(), info.field(), maxSeq[0]);
        }
        IndexInfo updated = new IndexInfo(info.name(), info.collection(), info.field(), info.type(), info.unique(), count[0], info.createdAt());
        indexMetadata.put(indexName, updated);
        return updated;
    }

    public void onDocumentInsert(String collection, String id, Map<String, Object> doc) {
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection)) {
                Object val = doc.get(info.field());
                if (val != null) {
                    if (isNumericType(info.type())) {
                        Double num = toNumericDouble(val);
                        if (num != null) {
                            TreeMap<Double, UnifiedSet<String>> numTree = numericIndexData.computeIfAbsent(info.name(), k -> new TreeMap<>());
                            synchronized (numTree) {
                                numTree.computeIfAbsent(num, k -> new UnifiedSet<>(2)).add(id);
                            }
                            if (isAutoIncrementType(info.type())) {
                                advanceSequenceIfHigher(collection, info.field(), num.longValue());
                            }
                        }
                    }
                    UnifiedMap<Object, Object> inverted = indexData.computeIfAbsent(info.name(), k -> new UnifiedMap<>(256));
                    synchronized (inverted) {
                        Object existing = inverted.get(val);
                        if (existing == null) {
                            inverted.put(val, id);
                        } else if (existing instanceof String firstId) {
                            if (!firstId.equals(id)) {
                                UnifiedSet<String> set = new UnifiedSet<>(4);
                                set.add(firstId);
                                set.add(id);
                                inverted.put(val, set);
                            }
                        } else if (existing instanceof UnifiedSet<?> rawSet) {
                            @SuppressWarnings("unchecked")
                            UnifiedSet<String> set = (UnifiedSet<String>) rawSet;
                            set.add(id);
                        }
                    }
                }
            }
        }
    }

    public void onDocumentDelete(String collection, String id, Map<String, Object> oldDoc) {
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection)) {
                if (isNumericType(info.type())) {
                    TreeMap<Double, UnifiedSet<String>> numTree = numericIndexData.get(info.name());
                    if (numTree != null) {
                        synchronized (numTree) {
                            if (oldDoc != null && oldDoc.get(info.field()) != null) {
                                Double num = toNumericDouble(oldDoc.get(info.field()));
                                if (num != null) {
                                    UnifiedSet<String> set = numTree.get(num);
                                    if (set != null) {
                                        set.remove(id);
                                        if (set.isEmpty()) numTree.remove(num);
                                    }
                                }
                            } else {
                                numTree.values().removeIf(set -> {
                                    set.remove(id);
                                    return set.isEmpty();
                                });
                            }
                        }
                    }
                }
                UnifiedMap<Object, Object> inverted = indexData.get(info.name());
                if (inverted != null) {
                    synchronized (inverted) {
                        if (oldDoc != null) {
                            Object val = oldDoc.get(info.field());
                            if (val != null) {
                                Object existing = inverted.get(val);
                                if (existing instanceof String singleId) {
                                    if (singleId.equals(id)) {
                                        inverted.remove(val);
                                    }
                                } else if (existing instanceof UnifiedSet<?> rawSet) {
                                    @SuppressWarnings("unchecked")
                                    UnifiedSet<String> set = (UnifiedSet<String>) rawSet;
                                    set.remove(id);
                                    if (set.isEmpty()) {
                                        inverted.remove(val);
                                    }
                                }
                            }
                        } else {
                            List<Object> keysToRemove = new ArrayList<>();
                            for (var entry : inverted.entrySet()) {
                                Object v = entry.getValue();
                                if (v instanceof String singleId) {
                                    if (singleId.equals(id)) {
                                        keysToRemove.add(entry.getKey());
                                    }
                                } else if (v instanceof UnifiedSet<?> rawSet) {
                                    @SuppressWarnings("unchecked")
                                    UnifiedSet<String> set = (UnifiedSet<String>) rawSet;
                                    set.remove(id);
                                    if (set.isEmpty()) {
                                        keysToRemove.add(entry.getKey());
                                    }
                                }
                            }
                            for (Object k : keysToRemove) {
                                inverted.remove(k);
                            }
                        }
                    }
                }
            }
        }
    }

    public Set<String> findDocIds(String collection, String field, Object value) {
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection) && info.field().equalsIgnoreCase(field)) {
                if (isNumericType(info.type())) {
                    Double num = toNumericDouble(value);
                    TreeMap<Double, UnifiedSet<String>> numTree = numericIndexData.get(info.name());
                    if (num != null && numTree != null) {
                        synchronized (numTree) {
                            UnifiedSet<String> set = numTree.get(num);
                            return set != null ? Collections.unmodifiableSet(new HashSet<>(set)) : Collections.emptySet();
                        }
                    }
                }
                UnifiedMap<Object, Object> inverted = indexData.get(info.name());
                if (inverted != null) {
                    synchronized (inverted) {
                        Object existing = inverted.get(value);
                        if (existing == null) {
                            return Collections.emptySet();
                        }
                        if (existing instanceof String singleId) {
                            return Collections.singleton(singleId);
                        }
                        if (existing instanceof UnifiedSet<?> rawSet) {
                            @SuppressWarnings("unchecked")
                            UnifiedSet<String> set = (UnifiedSet<String>) rawSet;
                            return Collections.unmodifiableSet(new HashSet<>(set));
                        }
                    }
                }
            }
        }
        return Collections.emptySet();
    }

    /**
     * Consulta ordenada por rango numérico inclusivo {@code [minInclusive, maxInclusive]}
     * utilizando el árbol ordenado del índice {@code NUMERIC} o {@code AUTO_INCREMENT}.
     */
    public Set<String> findDocIdsByNumericRange(String collection, String field, Double minInclusive, Double maxInclusive) {
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection)
                    && info.field().equalsIgnoreCase(field)
                    && isNumericType(info.type())) {
                TreeMap<Double, UnifiedSet<String>> numTree = numericIndexData.get(info.name());
                if (numTree != null) {
                    synchronized (numTree) {
                        double low = minInclusive != null ? minInclusive : -Double.MAX_VALUE;
                        double high = maxInclusive != null ? maxInclusive : Double.MAX_VALUE;
                        if (low > high) return Collections.emptySet();
                        Set<String> result = new LinkedHashSet<>();
                        for (UnifiedSet<String> ids : numTree.subMap(low, true, high, true).values()) {
                            result.addAll(ids);
                        }
                        return Collections.unmodifiableSet(result);
                    }
                }
            }
        }
        return Collections.emptySet();
    }

    public List<IndexInfo> listIndexes(String collection) {
        List<IndexInfo> list = new ArrayList<>();
        for (IndexInfo info : indexMetadata.values()) {
            if (collection == null || collection.isBlank() || info.collection().equalsIgnoreCase(collection)) {
                list.add(info);
            }
        }
        list.sort(Comparator.comparing(IndexInfo::name));
        return list;
    }

    public IndexInfo getIndex(String indexName) {
        return indexMetadata.get(indexName);
    }

    public String getDatabaseName() {
        return databaseName;
    }
}

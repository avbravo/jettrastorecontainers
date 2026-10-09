package io.jettra.store.engine.index;

import io.jettra.collections.map.UnifiedMap;
import io.jettra.collections.set.UnifiedSet;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.models.DocumentEngine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor de índices secundarios de JettraStore optimizado con JettraCollections.
 * Aplica almacenamiento compacto (Singleton / Zero-Set), pre-dimensionado configurado
 * en database.properties (jettra.index.initial.capacity) y streaming perezoso sin toArray()
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

    public JettraIndexManager(String databaseName) {
        this(databaseName, JettraStoreConfig.load());
    }

    public JettraIndexManager(String databaseName, JettraStoreConfig config) {
        this.databaseName = databaseName;
        this.config = (config != null) ? config : JettraStoreConfig.load();
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
        IndexInfo info = new IndexInfo(indexName, collection, field, type.toUpperCase(), unique, 0, System.currentTimeMillis());
        indexMetadata.put(indexName, info);
        indexData.put(indexName, UnifiedMap.newMap(128));

        if (docEngine != null) {
            rebuildIndex(indexName, docEngine);
        }

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
        IndexInfo info = new IndexInfo(indexName, collection, field, type.toUpperCase(), unique, 0, System.currentTimeMillis());
        indexMetadata.put(indexName, info);
        indexData.computeIfAbsent(indexName, k -> UnifiedMap.newMap(128));

        if (docEngine != null) {
            rebuildIndex(indexName, docEngine);
        }
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
        boolean removed = indexMetadata.remove(indexName) != null;
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
        return indexMetadata.remove(indexName) != null;
    }

    public synchronized IndexInfo rebuildIndex(String indexName, DocumentEngine docEngine) {
        IndexInfo info = indexMetadata.get(indexName);
        if (info == null) {
            throw new NoSuchElementException("Index '" + indexName + "' not found.");
        }

        // 1. Capacidad inicial inteligente para evitar rehashes continuos en heap
        int initialCap = (config != null) ? config.getIndexInitialCapacity() : 65536;
        if (docEngine != null && docEngine.count() > 0) {
            long docCount = docEngine.count();
            initialCap = (int) Math.min(docCount, initialCap);
            initialCap = Math.max(initialCap, 1024);
        }

        // 2. Almacenamiento compacto: fieldValue -> String (si es único) o UnifiedSet (si múltiple)
        UnifiedMap<Object, Object> inverted = new UnifiedMap<>(initialCap);
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
        IndexInfo updated = new IndexInfo(info.name(), info.collection(), info.field(), info.type(), info.unique(), count[0], info.createdAt());
        indexMetadata.put(indexName, updated);
        return updated;
    }

    public void onDocumentInsert(String collection, String id, Map<String, Object> doc) {
        for (IndexInfo info : indexMetadata.values()) {
            if (info.collection().equalsIgnoreCase(collection)) {
                Object val = doc.get(info.field());
                if (val != null) {
                    UnifiedMap<Object, Object> inverted = indexData.computeIfAbsent(info.name(), k -> new UnifiedMap<>(1024));
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

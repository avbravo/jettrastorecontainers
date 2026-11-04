package io.jettra.store.engine.models;

import io.jettra.store.offheap.OffHeapSlottedMap;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KeyValueEngine {
    private final String namespace;
    private final io.jettra.store.core.JettraDatabase database;
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    private final OffHeapSlottedMap offHeapStore = new OffHeapSlottedMap();

    public KeyValueEngine(String namespace) {
        this(namespace, null);
    }

    public KeyValueEngine(String namespace, io.jettra.store.core.JettraDatabase database) {
        this.namespace = namespace;
        this.database = database;
    }

    private boolean isOffHeap() {
        return database != null && database.getStorageMode().isOffHeap();
    }

    public void put(String key, byte[] value) {
        if (database != null) {
            database.assertWritable();
        }
        applyReplicatedPut(key, value);
        if (database != null) {
            database.onKeyValuePut(namespace, key, value);
        }
    }

    public void applyReplicatedPut(String key, byte[] value) {
        if (isOffHeap()) {
            offHeapStore.put(key, value);
        } else {
            store.put(key, value);
        }
    }

    public byte[] get(String key) {
        if (isOffHeap()) {
            return offHeapStore.get(key);
        }
        return store.get(key);
    }

    /**
     * Lectura Cero-Copia de alto rendimiento que devuelve una vista directa de memoria nativa.
     */
    public MemorySegment getSlice(String key) {
        return offHeapStore.getSlice(key);
    }

    public boolean remove(String key) {
        if (database != null) {
            database.assertWritable();
        }
        boolean removed = applyReplicatedRemove(key);
        if (removed && database != null) {
            database.onKeyValueDelete(namespace, key);
        }
        return removed;
    }

    public boolean applyReplicatedRemove(String key) {
        if (isOffHeap()) {
            return offHeapStore.remove(key);
        }
        return store.remove(key) != null;
    }

    public boolean containsKey(String key) {
        if (isOffHeap()) {
            return offHeapStore.containsKey(key);
        }
        return store.containsKey(key);
    }

    public Map<String, byte[]> getAll() {
        return Collections.unmodifiableMap(store);
    }

    public String getNamespace() { return namespace; }

    public int size() { 
        return isOffHeap() ? offHeapStore.size() : store.size(); 
    }

    public OffHeapSlottedMap getOffHeapStore() {
        return offHeapStore;
    }

    public void putBatch(Map<String, byte[]> batch) {
        if (database != null) {
            database.assertWritable();
        }
        applyReplicatedPutBatch(batch);
        if (database != null) {
            for (var entry : batch.entrySet()) {
                database.onKeyValuePut(namespace, entry.getKey(), entry.getValue());
            }
        }
    }

    public void applyReplicatedPutBatch(Map<String, byte[]> batch) {
        if (isOffHeap()) {
            for (var entry : batch.entrySet()) {
                offHeapStore.put(entry.getKey(), entry.getValue());
            }
        } else {
            store.putAll(batch);
        }
    }

    public void clear() {
        if (database != null) {
            database.assertWritable();
        }
        applyReplicatedClear();
    }

    public void applyReplicatedClear() {
        if (isOffHeap()) {
            offHeapStore.close();
        }
        store.clear();
    }
}

package io.jettra.store.engine.models;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KeyValueEngine {
    private final String namespace;
    private final io.jettra.store.core.JettraDatabase database;
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    public KeyValueEngine(String namespace) {
        this(namespace, null);
    }

    public KeyValueEngine(String namespace, io.jettra.store.core.JettraDatabase database) {
        this.namespace = namespace;
        this.database = database;
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
        store.put(key, value);
    }

    public byte[] get(String key) {
        return store.get(key);
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
        return store.remove(key) != null;
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    public Map<String, byte[]> getAll() {
        return Collections.unmodifiableMap(store);
    }

    public String getNamespace() { return namespace; }
    public int size() { return store.size(); }

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
        store.putAll(batch);
    }

    public void clear() {
        if (database != null) {
            database.assertWritable();
        }
        applyReplicatedClear();
    }

    public void applyReplicatedClear() {
        store.clear();
    }
}

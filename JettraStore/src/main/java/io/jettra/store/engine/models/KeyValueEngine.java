package io.jettra.store.engine.models;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KeyValueEngine {
    private final String namespace;
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    public KeyValueEngine(String namespace) {
        this.namespace = namespace;
    }

    public void put(String key, byte[] value) {
        store.put(key, value);
    }

    public byte[] get(String key) {
        return store.get(key);
    }

    public boolean remove(String key) {
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
        store.putAll(batch);
    }
}

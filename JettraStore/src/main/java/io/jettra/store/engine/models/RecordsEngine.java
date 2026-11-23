package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordsEngine<T extends Record> {
    private final String entityName;
    private final Class<T> recordClass;
    private final Map<String, T> records = new ConcurrentHashMap<>();

    public RecordsEngine(String entityName, Class<T> recordClass) {
        this.entityName = entityName;
        this.recordClass = recordClass;
    }

    public void persist(String id, T instance) {
        String effectiveId = (id != null && !id.isBlank())
                ? id.trim()
                : io.jettra.store.core.JettraId.generate("default", entityName).toHexString();
        records.put(effectiveId, instance);
    }

    public String persist(T instance) {
        return insertStrict(null, "default", instance);
    }

    public String insertStrict(String id, String databaseName, T instance) {
        String effectiveId = (id != null && !id.isBlank())
                ? id.trim()
                : io.jettra.store.core.JettraId.generate(databaseName, entityName).toHexString();
        if (records.putIfAbsent(effectiveId, instance) != null) {
            throw new IllegalStateException("[PRIMARY KEY VIOLATION] Ya existe un record con el identificador '"
                    + effectiveId + "' en el bucket '" + entityName + "'.");
        }
        return effectiveId;
    }

    public T find(String id) {
        return records.get(id);
    }

    public List<T> listAll() {
        return new ArrayList<>(records.values());
    }

    public boolean remove(String id) {
        return records.remove(id) != null;
    }

    public void persistBatch(Map<String, T> batch) {
        if (batch != null) records.putAll(batch);
    }

    public void insertBatch(Map<String, T> batch) {
        persistBatch(batch);
    }

    public long count() {
        return records.size();
    }

    public Map<String, T> getAll() {
        return Collections.unmodifiableMap(records);
    }

    public Map<String, T> findAll() {
        return getAll();
    }

    public String getEntityName() { return entityName; }
    public Class<T> getRecordClass() { return recordClass; }
    public int size() { return records.size(); }

    public void clear() {
        records.clear();
    }
}

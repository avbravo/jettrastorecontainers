package com.jettra.memory.integration;

import com.jettra.memory.engine.DiskStorageEngine;
import io.jettra.ee.serialization.JettraSerialization;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Repositorio genérico compatible con contenedores Jakarta EE / Helidon y el ecosistema JettraEE.
 * Permite persistir entidades Serializable directamente en disco fuera del Heap
 * garantizando cero sobrecarga de Garbage Collection.
 *
 * @param <T> Tipo de entidad (debe implementar Serializable)
 */
public class JettraEERepository<T extends Serializable> {

    private final String entityNamespace;
    private final Class<T> entityClass;
    private final DiskStorageEngine storageEngine;

    public JettraEERepository(Class<T> entityClass, DiskStorageEngine storageEngine) {
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass requerida");
        this.entityNamespace = "ee:" + entityClass.getSimpleName().toLowerCase();
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine requerido");
    }

    public void save(String id, T entity) throws IOException {
        String key = entityNamespace + ":" + id;
        byte[] payload = JettraSerialization.serializeObject(entity);
        byte[] record = JettraSerialization.serializeRecord(id, 1, System.currentTimeMillis(), payload);
        storageEngine.put(key, record);
    }

    public Optional<T> findById(String id) throws IOException, ClassNotFoundException {
        String key = entityNamespace + ":" + id;
        byte[] recordBytes = storageEngine.get(key);
        if (recordBytes == null) {
            return Optional.empty();
        }
        byte[] payload = JettraSerialization.extractPayload(recordBytes);
        T entity = JettraSerialization.deserializeObject(payload, entityClass);
        return Optional.ofNullable(entity);
    }

    public boolean deleteById(String id) throws IOException {
        String key = entityNamespace + ":" + id;
        return storageEngine.delete(key);
    }

    public boolean existsById(String id) {
        String key = entityNamespace + ":" + id;
        return storageEngine.containsKey(key);
    }

    public DiskStorageEngine getStorageEngine() {
        return storageEngine;
    }
}

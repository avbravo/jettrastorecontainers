package com.jettra.memory.integration;

import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.serializer.JettraBinarySerializer;
import com.jettra.memory.serializer.JettraStoreBinaryRecord;

import java.io.IOException;
import java.util.Objects;

/**
 * Conector universal entre JettraMemory y el motor de base de datos JettraStore.
 * Permite persistir y recuperar registros binarios de motores multimodelo (KeyValue, Document, Vector, etc.)
 * con total compatibilidad de formato.
 */
public final class JettraStoreConnector {

    private final DiskStorageEngine storageEngine;

    public JettraStoreConnector(DiskStorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine requerido");
    }

    /**
     * Persiste un registro de JettraStore serializado directamente en disco fuera del Heap.
     */
    public void putRecord(byte engineId, String key, byte[] payload) throws IOException {
        String compositeKey = "js:" + engineId + ":" + key;
        byte[] serialized = JettraBinarySerializer.serializeJettraStore(engineId, key, payload);
        storageEngine.put(compositeKey, serialized);
    }

    /**
     * Recupera un registro binario de JettraStore.
     */
    public JettraStoreBinaryRecord getRecord(byte engineId, String key) throws IOException {
        String compositeKey = "js:" + engineId + ":" + key;
        byte[] bytes = storageEngine.get(compositeKey);
        if (bytes == null) {
            return null;
        }
        return JettraBinarySerializer.deserializeJettraStore(bytes);
    }

    /**
     * Elimina un registro de JettraStore mediante Tombstone en disco.
     */
    public boolean deleteRecord(byte engineId, String key) throws IOException {
        String compositeKey = "js:" + engineId + ":" + key;
        return storageEngine.delete(compositeKey);
    }

    public DiskStorageEngine getStorageEngine() {
        return storageEngine;
    }
}

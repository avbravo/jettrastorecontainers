package com.jettra.memory.serializer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Representación inmutable de un registro binario de JettraStore.
 * Totalmente compatible con la estructura off-heap de NativeMemTable y JettraDatabase.
 */
public record JettraStoreBinaryRecord(
        byte engineId,
        String key,
        byte[] payload,
        long timestamp,
        int version
) implements Serializable {

    public JettraStoreBinaryRecord {
        Objects.requireNonNull(key, "key no puede ser nula");
        if (payload == null) {
            payload = new byte[0];
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JettraStoreBinaryRecord that)) return false;
        return engineId == that.engineId &&
                timestamp == that.timestamp &&
                version == that.version &&
                Objects.equals(key, that.key) &&
                Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(engineId, key, timestamp, version);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}

package com.jettra.memory.cluster;

import java.io.Serializable;

/**
 * Trama de replicación transmitida entre nodos del clúster de JettraMemory.
 */
public record ReplicationFrame(
        long sequenceId,
        OperationType operation,
        String key,
        byte[] payload,
        long timestamp,
        String originNodeId
) implements Serializable {

    public enum OperationType {
        PUT,
        DELETE,
        SYNC_REQUEST,
        HEARTBEAT
    }

    public static ReplicationFrame put(long seq, String key, byte[] payload, String origin) {
        return new ReplicationFrame(seq, OperationType.PUT, key, payload, System.currentTimeMillis(), origin);
    }

    public static ReplicationFrame delete(long seq, String key, String origin) {
        return new ReplicationFrame(seq, OperationType.DELETE, key, null, System.currentTimeMillis(), origin);
    }
}

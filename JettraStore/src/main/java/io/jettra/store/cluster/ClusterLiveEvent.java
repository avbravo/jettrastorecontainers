package io.jettra.store.cluster;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Representa un evento en tiempo real del clúster de JettraStore.
 * Usado para auditoría, telemetría, failover y el comando interactivo 'cluster live'.
 */
public record ClusterLiveEvent(
    long timestamp,
    String type,
    String sourceNodeId,
    String targetNodeId,
    String message,
    String details
) implements Serializable {

    private static final DateTimeFormatter FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static final String TYPE_NODE_ONLINE       = "NODE_ONLINE";
    public static final String TYPE_NODE_OFFLINE      = "NODE_OFFLINE";
    public static final String TYPE_NODE_STOPPED      = "NODE_STOPPED";
    public static final String TYPE_LEADER_ELECTION   = "LEADER_ELECTION";
    public static final String TYPE_LEADER_PROMOTED   = "LEADER_PROMOTED";
    public static final String TYPE_DATABASE_CREATED  = "DATABASE_CREATED";
    public static final String TYPE_DATABASE_DROPPED  = "DATABASE_DROPPED";
    public static final String TYPE_DATABASE_DISTRIBUTED = "DATABASE_DISTRIBUTED";
    public static final String TYPE_ENGINE_CREATED    = "ENGINE_CREATED";
    public static final String TYPE_DATA_TRANSFER     = "DATA_TRANSFER";
    public static final String TYPE_RECORD_REPLICATED = "RECORD_REPLICATED";
    public static final String TYPE_DOCUMENT_REPLICATED = "DOCUMENT_REPLICATED";
    public static final String TYPE_DOCUMENT_DELETED  = "DOCUMENT_DELETED";

    public String formattedTimestamp() {
        return FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public String toJson() {
        return String.format(
            "{\"timestamp\":%d,\"formatted_time\":\"%s\",\"type\":\"%s\",\"source\":\"%s\",\"target\":\"%s\",\"message\":\"%s\",\"details\":\"%s\"}",
            timestamp, formattedTimestamp(),
            escapeJson(type), escapeJson(sourceNodeId), escapeJson(targetNodeId),
            escapeJson(message), escapeJson(details)
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

package io.jettra.store.cluster;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Trama binaria estructurada para el protocolo de consenso Raft y replicación en JettraStore.
 */
public record JettraRaftFrame(
    byte frameType,
    long term,
    long logIndex,
    String senderNodeId,
    String databaseName,
    String collectionName,
    String key,
    byte[] payload,
    long timestamp
) implements Serializable {

    // Tipos de trama soportados
    public static final byte TYPE_HEARTBEAT        = 0x01;
    public static final byte TYPE_HEARTBEAT_ACK    = 0x02;
    public static final byte TYPE_CREATE_DATABASE  = 0x03;
    public static final byte TYPE_DROP_DATABASE    = 0x04;
    public static final byte TYPE_PUT_RECORD       = 0x05;
    public static final byte TYPE_DELETE_RECORD    = 0x06;
    public static final byte TYPE_SYNC_CATALOG_REQ = 0x07;
    public static final byte TYPE_SYNC_CATALOG_RESP= 0x08;
    public static final byte TYPE_ACK              = 0x09;
    public static final byte TYPE_NACK             = 0x0A;
    public static final byte TYPE_PUT_DOCUMENT     = 0x0B;
    public static final byte TYPE_DELETE_DOCUMENT  = 0x0C;
    public static final byte TYPE_CREATE_INDEX     = 0x0D;
    public static final byte TYPE_DROP_INDEX       = 0x0E;
    public static final byte TYPE_SYNC_DATA_REQ    = 0x0F;
    public static final byte TYPE_SYNC_DATA_RESP   = 0x10;
    public static final byte TYPE_DISTRIBUTE_DATABASE = 0x11;
    public static final byte TYPE_NODE_STOPPING     = 0x12;
    public static final byte TYPE_LEADER_ELECTION   = 0x13;
    public static final byte TYPE_NEW_LEADER_PROMOTED = 0x14;
    public static final byte TYPE_CLUSTER_LIVE_EVENT = 0x15;

    public static JettraRaftFrame heartbeat(long term, String senderNodeId) {
        return new JettraRaftFrame(TYPE_HEARTBEAT, term, 0, senderNodeId, "", "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame heartbeatAck(long term, String senderNodeId) {
        return new JettraRaftFrame(TYPE_HEARTBEAT_ACK, term, 0, senderNodeId, "", "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame createDatabase(long term, long logIndex, String senderNodeId, String databaseName) {
        return createDatabase(term, logIndex, senderNodeId, databaseName, new byte[0]);
    }

    public static JettraRaftFrame createDatabase(long term, long logIndex, String senderNodeId, String databaseName, byte[] payload) {
        return new JettraRaftFrame(TYPE_CREATE_DATABASE, term, logIndex, senderNodeId, databaseName, "", "", payload != null ? payload : new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame distributeDatabase(long term, long logIndex, String senderNodeId, String databaseName, byte[] payload) {
        return new JettraRaftFrame(TYPE_DISTRIBUTE_DATABASE, term, logIndex, senderNodeId, databaseName, "", "", payload != null ? payload : new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame dropDatabase(long term, long logIndex, String senderNodeId, String databaseName) {
        return new JettraRaftFrame(TYPE_DROP_DATABASE, term, logIndex, senderNodeId, databaseName, "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame putRecord(long term, long logIndex, String senderNodeId, String databaseName, String collectionName, String key, byte[] payload) {
        return new JettraRaftFrame(TYPE_PUT_RECORD, term, logIndex, senderNodeId, databaseName, collectionName, key, payload != null ? payload : new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame deleteRecord(long term, long logIndex, String senderNodeId, String databaseName, String collectionName, String key) {
        return new JettraRaftFrame(TYPE_DELETE_RECORD, term, logIndex, senderNodeId, databaseName, collectionName, key, new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame putDocument(long term, long logIndex, String senderNodeId, String databaseName, String collectionName, String id, byte[] jsonPayload) {
        return new JettraRaftFrame(TYPE_PUT_DOCUMENT, term, logIndex, senderNodeId, databaseName, collectionName, id, jsonPayload != null ? jsonPayload : new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame deleteDocument(long term, long logIndex, String senderNodeId, String databaseName, String collectionName, String id) {
        return new JettraRaftFrame(TYPE_DELETE_DOCUMENT, term, logIndex, senderNodeId, databaseName, collectionName, id, new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame createIndex(long term, long logIndex, String senderNodeId, String databaseName, String collectionName, String indexName, String field, String type, boolean unique) {
        String meta = field + ":" + type + ":" + unique;
        byte[] payload = meta.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new JettraRaftFrame(TYPE_CREATE_INDEX, term, logIndex, senderNodeId, databaseName, collectionName, indexName, payload, System.currentTimeMillis());
    }

    public static JettraRaftFrame dropIndex(long term, long logIndex, String senderNodeId, String databaseName, String indexName) {
        return new JettraRaftFrame(TYPE_DROP_INDEX, term, logIndex, senderNodeId, databaseName, "", indexName, new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame syncCatalogReq(long term, String senderNodeId) {
        return new JettraRaftFrame(TYPE_SYNC_CATALOG_REQ, term, 0, senderNodeId, "", "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame syncDataReq(long term, String senderNodeId, String databaseName) {
        return new JettraRaftFrame(TYPE_SYNC_DATA_REQ, term, 0, senderNodeId, databaseName, "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame syncDataResp(long term, String senderNodeId, String databaseName, byte[] metaJsonBytes) {
        return new JettraRaftFrame(TYPE_SYNC_DATA_RESP, term, 0, senderNodeId, databaseName, "", "", metaJsonBytes != null ? metaJsonBytes : new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame syncCatalogResp(long term, String senderNodeId, String dbsCsv) {
        byte[] bytes = dbsCsv != null ? dbsCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new JettraRaftFrame(TYPE_SYNC_CATALOG_RESP, term, 0, senderNodeId, "", "", "", bytes, System.currentTimeMillis());
    }

    public static JettraRaftFrame ack(long term, long logIndex, String senderNodeId, String message) {
        byte[] bytes = message != null ? message.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new JettraRaftFrame(TYPE_ACK, term, logIndex, senderNodeId, "", "", "", bytes, System.currentTimeMillis());
    }

    public static JettraRaftFrame nack(long term, long logIndex, String senderNodeId, String reason) {
        byte[] bytes = reason != null ? reason.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new JettraRaftFrame(TYPE_NACK, term, logIndex, senderNodeId, "", "", "", bytes, System.currentTimeMillis());
    }

    public static JettraRaftFrame nodeStopping(long term, String senderNodeId, String role, String reason) {
        String msg = role + ":" + (reason != null ? reason : "Graceful shutdown");
        byte[] bytes = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new JettraRaftFrame(TYPE_NODE_STOPPING, term, 0, senderNodeId, "", "", "", bytes, System.currentTimeMillis());
    }

    public static JettraRaftFrame leaderElection(long term, long logIndex, String candidateNodeId) {
        return new JettraRaftFrame(TYPE_LEADER_ELECTION, term, logIndex, candidateNodeId, "", "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame newLeaderPromoted(long term, long logIndex, String newLeaderNodeId) {
        return new JettraRaftFrame(TYPE_NEW_LEADER_PROMOTED, term, logIndex, newLeaderNodeId, "", "", "", new byte[0], System.currentTimeMillis());
    }

    public static JettraRaftFrame clusterLiveEvent(long term, String senderNodeId, String eventJson) {
        byte[] bytes = eventJson != null ? eventJson.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new JettraRaftFrame(TYPE_CLUSTER_LIVE_EVENT, term, 0, senderNodeId, "", "", "", bytes, System.currentTimeMillis());
    }

    public String getPayloadAsString() {
        return payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : "";
    }
}

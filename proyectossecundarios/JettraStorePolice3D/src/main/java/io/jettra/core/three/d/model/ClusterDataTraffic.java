package io.jettra.core.three.d.model;

/**
 * Representa el tráfico real de datos que ocurre entre los diferentes nodos analizando
 * el clúster de JettraStore en tiempo real.
 * Se refleja físicamente en el mundo 3D mediante los camiones que circulan entre nodos
 * EXCLUSIVAMENTE cuando los nodos están transmitiendo datos de uno a otro nodo.
 */
public class ClusterDataTraffic {

    public enum TrafficType {
        RAFT_REPLICATION,      // Replicación de logs Raft entre Primary y Follower
        RING_OFFLOAD,          // Offloading de particiones en anillo dinámico
        SSTABLE_COMPACTION,    // Transferencia de bloques LSM/SSTables
        VECTOR_SYNC,           // Sincronización de índices vectoriales AI
        CLIENT_INGESTION       // Ingesta de lotes desde el balanceador/puerta de entrada
    }

    private final String id;
    private final String name;
    private final TrafficType trafficType;
    private String sourceNodeId;
    private String targetNodeId;
    private String payloadSummary;
    private long batchSizeBytes;
    private float transferSpeedMbps;
    private float progress; // 0.0 a 1.0 de source a target
    private boolean isReversing; // Retornando en vacío o completando viaje de ida
    private boolean isTransmitting; // Solo verdadero cuando hay transmisión activa

    public ClusterDataTraffic(String id, String name, TrafficType trafficType,
                              String sourceNodeId, String targetNodeId,
                              String payloadSummary, long batchSizeBytes, float transferSpeedMbps) {
        this.id = id;
        this.name = name;
        this.trafficType = trafficType;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.payloadSummary = payloadSummary;
        this.batchSizeBytes = batchSizeBytes;
        this.transferSpeedMbps = transferSpeedMbps;
        this.progress = 0.0f;
        this.isReversing = false;
        this.isTransmitting = false; // Inicia inactivo hasta que haya transmisión real
    }

    public synchronized void triggerTransfer(String src, String tgt, String summary, long bytes, float speedMbps) {
        this.sourceNodeId = src;
        this.targetNodeId = tgt;
        this.payloadSummary = summary;
        this.batchSizeBytes = bytes;
        this.transferSpeedMbps = speedMbps;
        this.progress = 0.0f;
        this.isReversing = false;
        this.isTransmitting = true;
    }

    public synchronized void updateBatch(long newBytes, float newSpeedMbps, String newSummary) {
        this.batchSizeBytes = newBytes;
        this.transferSpeedMbps = newSpeedMbps;
        this.payloadSummary = newSummary;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public TrafficType getTrafficType() { return trafficType; }
    public synchronized String getSourceNodeId() { return sourceNodeId; }
    public synchronized void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }
    public synchronized String getTargetNodeId() { return targetNodeId; }
    public synchronized void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
    public synchronized String getPayloadSummary() { return payloadSummary; }
    public synchronized void setPayloadSummary(String payloadSummary) { this.payloadSummary = payloadSummary; }
    public synchronized long getBatchSizeBytes() { return batchSizeBytes; }
    public synchronized void setBatchSizeBytes(long batchSizeBytes) { this.batchSizeBytes = batchSizeBytes; }
    public synchronized float getTransferSpeedMbps() { return transferSpeedMbps; }
    public synchronized void setTransferSpeedMbps(float transferSpeedMbps) { this.transferSpeedMbps = transferSpeedMbps; }
    public synchronized float getProgress() { return progress; }
    public synchronized void setProgress(float progress) { this.progress = progress; }
    public synchronized boolean isReversing() { return isReversing; }
    public synchronized void setReversing(boolean reversing) { isReversing = reversing; }

    public synchronized boolean isTransmitting() { return isTransmitting; }
    public synchronized void setTransmitting(boolean transmitting) { this.isTransmitting = transmitting; }

    public synchronized void advance(float dt) {
        if (!isTransmitting) return;

        float speed = Math.max(0.15f, Math.min(0.60f, transferSpeedMbps / 20.0f));
        progress += speed * dt;
        if (progress >= 1.0f) {
            // El lote de datos ha llegado exitosamente al nodo destino
            progress = 1.0f;
            isTransmitting = false;
        }
    }
}

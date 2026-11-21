package io.jettra.core.three.d.model;

/**
 * Representa una sesión y usuario conectado en tiempo real a JettraStore.
 * Su ciclo de vida y consultas dirigen el movimiento y estado de las Personas 3D.
 */
public class JettraLiveSession {

    public enum SessionPhase {
        APPROACHING_NODE,      // Caminando hacia el servidor con la consulta
        EXECUTING_QUERY,       // En el servidor procesando la consulta en JettraStore
        RETURNING_TO_BUILDING, // Retornando a la sede con el ACK o resultado obtenido
        IDLE_IN_BUILDING       // En la sede esperando el siguiente lote o query
    }

    private final String sessionId;
    private final String username;
    private final String clientIp;
    private final String zoneId;
    private String database;
    private String targetNodeId;
    private String currentOperation;
    private SessionPhase phase;
    private long totalQueries;
    private long bytesExchanged;
    private long queryStartTime;
    private long lastLatencyMs;
    private float progress;       // 0.0f a 1.0f en la trayectoria
    private float waitTimer;      // Tiempo restante en fase IDLE o EXECUTING

    public JettraLiveSession(String sessionId, String username, String clientIp, String zoneId,
                             String database, String targetNodeId, String initialOperation) {
        this.sessionId = sessionId;
        this.username = username;
        this.clientIp = clientIp;
        this.zoneId = zoneId;
        this.database = database;
        this.targetNodeId = targetNodeId;
        this.currentOperation = initialOperation;
        this.phase = SessionPhase.APPROACHING_NODE;
        this.totalQueries = 1;
        this.bytesExchanged = 1024;
        this.queryStartTime = System.currentTimeMillis();
        this.lastLatencyMs = 2;
        this.progress = 0.0f;
        this.waitTimer = 0.0f;
    }

    public void startNewQuery(String operation, String database, String targetNodeId) {
        this.currentOperation = operation;
        this.database = database;
        this.targetNodeId = targetNodeId;
        this.phase = SessionPhase.APPROACHING_NODE;
        this.queryStartTime = System.currentTimeMillis();
        this.progress = 0.0f;
        this.waitTimer = 0.0f;
        this.totalQueries++;
    }

    public void onReachedServer(long latencyMs) {
        this.phase = SessionPhase.EXECUTING_QUERY;
        this.lastLatencyMs = latencyMs;
        this.waitTimer = Math.max(0.6f, latencyMs / 1000.0f);
        this.bytesExchanged += (1024 + (long)(Math.random() * 4096));
    }

    public void onQueryCompleted() {
        this.phase = SessionPhase.RETURNING_TO_BUILDING;
        this.progress = 0.0f;
    }

    public void onReachedBuilding() {
        this.phase = SessionPhase.IDLE_IN_BUILDING;
        this.waitTimer = 1.0f + (float)(Math.random() * 2.5f);
    }

    public String getSessionId() { return sessionId; }
    public String getUsername() { return username; }
    public String getClientIp() { return clientIp; }
    public String getZoneId() { return zoneId; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
    public String getCurrentOperation() { return currentOperation; }
    public void setCurrentOperation(String currentOperation) { this.currentOperation = currentOperation; }
    public SessionPhase getPhase() { return phase; }
    public void setPhase(SessionPhase phase) { this.phase = phase; }
    public long getTotalQueries() { return totalQueries; }
    public long getBytesExchanged() { return bytesExchanged; }
    public long getLastLatencyMs() { return lastLatencyMs; }
    public void setLastLatencyMs(long lastLatencyMs) { this.lastLatencyMs = lastLatencyMs; }
    public float getProgress() { return progress; }
    public void setProgress(float progress) { this.progress = progress; }
    public float getWaitTimer() { return waitTimer; }
    public void setWaitTimer(float waitTimer) { this.waitTimer = waitTimer; }

    public void advance(float dt) {
        float speed = 0.22f;
        switch (phase) {
            case APPROACHING_NODE -> {
                progress += speed * dt;
                if (progress >= 1.0f) {
                    progress = 1.0f;
                    onReachedServer(2);
                }
            }
            case EXECUTING_QUERY -> {
                waitTimer -= dt;
                if (waitTimer <= 0) {
                    onQueryCompleted();
                }
            }
            case RETURNING_TO_BUILDING -> {
                progress -= speed * dt;
                if (progress <= 0.0f) {
                    progress = 0.0f;
                    onReachedBuilding();
                }
            }
            case IDLE_IN_BUILDING -> {
                waitTimer -= dt;
                if (waitTimer <= 0) {
                    startNewQuery(currentOperation, database, targetNodeId);
                }
            }
        }
    }

}

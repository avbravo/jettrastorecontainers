package io.jettra.core.three.d.model;

/**
 * Representa un agente canino (perro) JettraPolice activado en tiempo real en JettraStore.
 * Su movimiento 3D responde directamente a las inspecciones, alertas de saturación de Heap,
 * MemTable purges o eventos de quórum en el clúster.
 */
public class JettraPoliceAgent {

    public enum PoliceRole {
        HEAP_SENTINEL,       // Centinela de presión de Heap y prevención de OOM
        MEMTABLE_PURGE_DOG,  // Canino auditor de MemTable y flush a SSTables en disco
        RAFT_QUORUM_K9,      // Centinela de latidos de consenso y aislamiento de nodos caídos
        SECURITY_PATROL      // Patrullero de autenticación y sesiones de usuarios
    }

    private final String id;
    private final String name;
    private final PoliceRole role;
    private String targetNodeId;
    private String currentMission;
    private boolean alertActive;
    private String alertSeverity; // INFO, WARNING, CRITICAL
    private long interventionsCount;
    private float inspectTimer;

    public JettraPoliceAgent(String id, String name, PoliceRole role, String initialNodeId, String initialMission) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.targetNodeId = initialNodeId;
        this.currentMission = initialMission;
        this.alertActive = false;
        this.alertSeverity = "INFO";
        this.interventionsCount = 0;
        this.inspectTimer = 3.0f;
    }

    public void assignMission(String targetNodeId, String mission, boolean isAlert, String severity) {
        this.targetNodeId = targetNodeId;
        this.currentMission = mission;
        this.alertActive = isAlert;
        this.alertSeverity = severity;
        if (isAlert) {
            this.interventionsCount++;
        }
        this.inspectTimer = isAlert ? 5.0f : 3.0f;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public PoliceRole getRole() { return role; }
    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
    public String getCurrentMission() { return currentMission; }
    public void setCurrentMission(String currentMission) { this.currentMission = currentMission; }
    public boolean isAlertActive() { return alertActive; }
    public void setAlertActive(boolean alertActive) { this.alertActive = alertActive; }
    public String getAlertSeverity() { return alertSeverity; }
    public void setAlertSeverity(String alertSeverity) { this.alertSeverity = alertSeverity; }
    public long getInterventionsCount() { return interventionsCount; }
    public float getInspectTimer() { return inspectTimer; }
    public void setInspectTimer(float inspectTimer) { this.inspectTimer = inspectTimer; }

    private float patrolAngle = (float)(Math.random() * Math.PI * 2);

    public float getPatrolAngle() { return patrolAngle; }
    public void setPatrolAngle(float patrolAngle) { this.patrolAngle = patrolAngle; }

    public void advance(float dt) {
        float speed = alertActive ? 1.6f : 0.75f;
        patrolAngle += speed * dt;
        if (patrolAngle > Math.PI * 2) {
            patrolAngle -= (float)(Math.PI * 2);
        }
    }

}

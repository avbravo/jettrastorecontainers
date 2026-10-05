package com.jettra.memory.cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * Representa un nodo dentro del clúster distribuido de tres nodos de JettraMemory.
 */
public final class ClusterNode implements Serializable {

    public enum Role {
        PRIMARY,
        SECONDARY
    }

    public enum Status {
        ONLINE,
        SYNCING,
        OFFLINE
    }

    private final String nodeId;
    private final String host;
    private final int port;
    private volatile Role role;
    private volatile Status status;
    private volatile long lastHeartbeat;

    public ClusterNode(String nodeId, String host, int port, Role role) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId requerido");
        this.host = Objects.requireNonNull(host, "host requerido");
        this.port = port;
        this.role = role != null ? role : Role.SECONDARY;
        this.status = Status.ONLINE;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public void recordHeartbeat() { this.lastHeartbeat = System.currentTimeMillis(); }

    public boolean isOnline() {
        return this.status == Status.ONLINE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClusterNode that)) return false;
        return port == that.port && Objects.equals(nodeId, that.nodeId) && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, port);
    }

    @Override
    public String toString() {
        return "ClusterNode{" +
                "nodeId='" + nodeId + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", role=" + role +
                ", status=" + status +
                '}';
    }
}

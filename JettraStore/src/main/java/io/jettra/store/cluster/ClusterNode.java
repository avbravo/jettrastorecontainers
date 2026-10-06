package io.jettra.store.cluster;

import java.util.concurrent.atomic.AtomicLong;

public final class ClusterNode {
    public enum Role { PRIMARY, SECONDARY }
    public enum RaftState { LEADER, FOLLOWER, CANDIDATE, DISCONNECTED }
    public enum NodeStatus { RUNNING, STOPPED, OFFLINE }

    private final String id;
    private final String ip;
    private final int port;
    private Role role;
    private RaftState raftState;
    private volatile NodeStatus status;
    private volatile long lastHeartbeat;
    private final AtomicLong receivedOffloadedBytes = new AtomicLong(0);
    private final AtomicLong receivedRingSegments = new AtomicLong(0);

    public ClusterNode(String id, String ip, int port, Role role) {
        this.id = id;
        this.ip = ip;
        this.port = port;
        this.role = role;
        this.raftState = (role == Role.PRIMARY) ? RaftState.LEADER : RaftState.FOLLOWER;
        this.status = NodeStatus.RUNNING;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public void receiveOffloadedRingPayload(String sourceNodeId, long bytes) {
        if (status == NodeStatus.RUNNING) {
            receivedOffloadedBytes.addAndGet(bytes);
            receivedRingSegments.incrementAndGet();
            this.lastHeartbeat = System.currentTimeMillis();
        }
    }

    public void start() {
        this.status = NodeStatus.RUNNING;
        this.raftState = (role == Role.PRIMARY) ? RaftState.LEADER : RaftState.FOLLOWER;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public void stop() {
        this.status = NodeStatus.STOPPED;
        this.raftState = RaftState.DISCONNECTED;
    }

    public void markOffline() {
        this.status = NodeStatus.OFFLINE;
        this.raftState = RaftState.DISCONNECTED;
    }

    public boolean isOnline() {
        return status == NodeStatus.RUNNING;
    }

    public String getId() { return id; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public RaftState getRaftState() { return raftState; }
    public void setRaftState(RaftState raftState) { this.raftState = raftState; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public long getReceivedOffloadedBytes() { return receivedOffloadedBytes.get(); }
    public long getReceivedRingSegments() { return receivedRingSegments.get(); }
}

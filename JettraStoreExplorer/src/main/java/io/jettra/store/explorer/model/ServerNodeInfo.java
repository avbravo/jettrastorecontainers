package io.jettra.store.explorer.model;

import java.io.Serializable;

public class ServerNodeInfo implements Serializable {
    private String id;
    private String name;
    private String role; // LEADER, FOLLOWER, EDGE
    private String host;
    private int port;
    private float cpuPercent;
    private long heapUsedMb;
    private long heapMaxMb;
    private long panamaDirectMb;
    private long diskUsedMb;
    private int virtualThreads;
    private long qps;
    private float latencyMs;
    private long raftTerm;
    private boolean online;

    public ServerNodeInfo() {}

    public ServerNodeInfo(String id, String name, String role, String host, int port,
                          float cpuPercent, long heapUsedMb, long heapMaxMb, long panamaDirectMb,
                          long diskUsedMb, int virtualThreads, long qps, float latencyMs, long raftTerm, boolean online) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.host = host;
        this.port = port;
        this.cpuPercent = cpuPercent;
        this.heapUsedMb = heapUsedMb;
        this.heapMaxMb = heapMaxMb;
        this.panamaDirectMb = panamaDirectMb;
        this.diskUsedMb = diskUsedMb;
        this.virtualThreads = virtualThreads;
        this.qps = qps;
        this.latencyMs = latencyMs;
        this.raftTerm = raftTerm;
        this.online = online;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public float getCpuPercent() { return cpuPercent; }
    public long getHeapUsedMb() { return heapUsedMb; }
    public long getHeapMaxMb() { return heapMaxMb; }
    public long getPanamaDirectMb() { return panamaDirectMb; }
    public long getDiskUsedMb() { return diskUsedMb; }
    public int getVirtualThreads() { return virtualThreads; }
    public long getQps() { return qps; }
    public float getLatencyMs() { return latencyMs; }
    public long getRaftTerm() { return raftTerm; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
}

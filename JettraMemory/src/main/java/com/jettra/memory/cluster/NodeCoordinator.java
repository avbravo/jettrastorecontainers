package com.jettra.memory.cluster;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinador de membresía y consenso de quórum para el clúster distribuido de tres nodos.
 * Asegura la topología exacta de 3 nodos (1 Primario + 2 Secundarios o 3 pares activos),
 * verificando el quórum de mayoría (mínimo 2 nodos) para garantizar consistencia fuerte.
 */
public final class NodeCoordinator implements AutoCloseable {

    public static final int THREE_NODE_CLUSTER_SIZE = 3;
    public static final int QUORUM_MAJORITY = 2; // 2 de 3 nodos

    private final ClusterNode localNode;
    private final Map<String, ClusterNode> peerNodes = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private Thread heartbeatMonitorThread;

    public NodeCoordinator(ClusterNode localNode) {
        this.localNode = Objects.requireNonNull(localNode, "localNode requerido");
    }

    public void registerPeer(ClusterNode peer) {
        Objects.requireNonNull(peer, "peer requerido");
        if (peer.getNodeId().equals(localNode.getNodeId())) {
            throw new IllegalArgumentException("No se puede registrar el nodo local como par: " + peer.getNodeId());
        }
        peerNodes.put(peer.getNodeId(), peer);
    }

    public void removePeer(String nodeId) {
        peerNodes.remove(nodeId);
    }

    public ClusterNode getLocalNode() {
        return localNode;
    }

    public Map<String, ClusterNode> getPeerNodes() {
        return Collections.unmodifiableMap(peerNodes);
    }

    public List<ClusterNode> getAllClusterNodes() {
        List<ClusterNode> all = new ArrayList<>();
        all.add(localNode);
        all.addAll(peerNodes.values());
        return Collections.unmodifiableList(all);
    }

    /**
     * Determina si el clúster cuenta con quórum suficiente (al menos 2 nodos en línea).
     */
    public boolean hasQuorum() {
        int onlineCount = localNode.isOnline() ? 1 : 0;
        for (ClusterNode peer : peerNodes.values()) {
            if (peer.isOnline()) {
                onlineCount++;
            }
        }
        return onlineCount >= QUORUM_MAJORITY;
    }

    /**
     * Valida que la topología cumpla con los requisitos del clúster de tres nodos.
     */
    public boolean isConfiguredAsThreeNodeCluster() {
        return (peerNodes.size() + 1) == THREE_NODE_CLUSTER_SIZE;
    }

    public int getOnlineNodesCount() {
        int count = localNode.isOnline() ? 1 : 0;
        for (ClusterNode peer : peerNodes.values()) {
            if (peer.isOnline()) {
                count++;
            }
        }
        return count;
    }

    public void startHeartbeatMonitor(long intervalMs, long timeoutMs) {
        this.heartbeatMonitorThread = Thread.ofVirtual().name("Jettra-Heartbeat-Monitor-" + localNode.getNodeId()).start(() -> {
            while (active.get()) {
                try {
                    Thread.sleep(intervalMs);
                    if (!active.get()) break;

                    long now = System.currentTimeMillis();
                    localNode.recordHeartbeat();

                    for (ClusterNode peer : peerNodes.values()) {
                        if (now - peer.getLastHeartbeat() > timeoutMs) {
                            peer.setStatus(ClusterNode.Status.OFFLINE);
                        } else {
                            if (peer.getStatus() == ClusterNode.Status.OFFLINE) {
                                peer.setStatus(ClusterNode.Status.ONLINE);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void close() {
        active.set(false);
        if (heartbeatMonitorThread != null && heartbeatMonitorThread.isAlive()) {
            heartbeatMonitorThread.interrupt();
        }
    }
}

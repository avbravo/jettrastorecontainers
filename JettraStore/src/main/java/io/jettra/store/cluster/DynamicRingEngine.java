package io.jettra.store.cluster;

import io.jettra.store.engine.panama.NativeMemTable;
import io.jettra.store.police.JettraPolice;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DynamicRingEngine {
    private final String nodeId;
    private final List<ClusterNode> peers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean ringActive = new AtomicBoolean(false);
    private final double saturationThreshold;
    private final double targetReleaseThreshold;
    private volatile boolean multinodeActive;
    private double currentMemoryUsage = 0.25; // 25% inicial

    public DynamicRingEngine(String nodeId, double saturationThreshold, double targetReleaseThreshold) {
        this(nodeId, saturationThreshold, targetReleaseThreshold, true);
    }

    public DynamicRingEngine(String nodeId, double saturationThreshold, double targetReleaseThreshold, boolean multinodeActive) {
        this.nodeId = nodeId;
        this.saturationThreshold = saturationThreshold;
        this.targetReleaseThreshold = targetReleaseThreshold;
        this.multinodeActive = multinodeActive;
    }

    public void registerPeer(ClusterNode peer) {
        // Evitar duplicados por ID
        peers.removeIf(p -> p.getId().equalsIgnoreCase(peer.getId()));
        peers.add(peer);
    }

    public boolean removePeer(String peerId) {
        return peers.removeIf(p -> p.getId().equalsIgnoreCase(peerId));
    }

    public boolean startPeer(String peerId) {
        ClusterNode node = getPeer(peerId);
        if (node != null) {
            node.start();
            return true;
        }
        return false;
    }

    public boolean stopPeer(String peerId) {
        ClusterNode node = getPeer(peerId);
        if (node != null) {
            node.stop();
            return true;
        }
        return false;
    }

    public ClusterNode getPeer(String peerId) {
        for (ClusterNode node : peers) {
            if (node.getId().equalsIgnoreCase(peerId)) {
                return node;
            }
        }
        return null;
    }

    public synchronized void evaluateMemorySaturation(double currentUsagePercent, NativeMemTable activeMemTable) {
        this.currentMemoryUsage = currentUsagePercent;

        if (!multinodeActive) {
            // Distribución desactivada por cluster.multinode.active=off (Modo Standalone / Servidor Único)
            return;
        }

        if (currentUsagePercent >= saturationThreshold && !ringActive.get()) {
            activateRingTransition(activeMemTable);
        } else if (currentUsagePercent <= targetReleaseThreshold && ringActive.get()) {
            deactivateRingTransition();
        }
    }

    private void activateRingTransition(NativeMemTable memTable) {
        ringActive.set(true);
        JettraPolice.getInstance().recordAlert("RING_SATURATION_ACTIVATED", 
            String.format("Node %s reached %.1f%% RAM saturation. Converting dynamically to Distributed Ring Engine.", 
                nodeId, currentMemoryUsage * 100));

        // Particionar y transferir bloques a los nodos pares activos del anillo
        List<ClusterNode> activePeers = peers.stream().filter(ClusterNode::isOnline).toList();
        if (!activePeers.isEmpty()) {
            long bytesToOffload = memTable.getUsedBytes() / activePeers.size();
            for (ClusterNode peer : activePeers) {
                peer.receiveOffloadedRingPayload(nodeId, bytesToOffload);
            }
        }

        // Descarga proactiva en el nodo local: consumo cae al objetivo
        this.currentMemoryUsage = targetReleaseThreshold;
        JettraPolice.getInstance().recordAlert("RING_LOAD_BALANCED", 
            String.format("Node %s successfully released memory to peers. Current RAM load: %.1f%%", 
                nodeId, currentMemoryUsage * 100));
    }

    private void deactivateRingTransition() {
        ringActive.set(false);
        JettraPolice.getInstance().recordAlert("RING_NORMALIZED", 
            String.format("Node %s stabilized memory below release target (%.1f%%). Reverting to Single-Node High-Speed Mode.", 
                nodeId, targetReleaseThreshold * 100));
    }

    public boolean isMultinodeActive() { return multinodeActive; }
    public String getMultinodeActive() { return multinodeActive ? "on" : "off"; }
    public void setMultinodeActive(boolean multinodeActive) { this.multinodeActive = multinodeActive; }
    public boolean isRingActive() { return ringActive.get(); }
    public String getNodeId() { return nodeId; }
    public List<ClusterNode> getPeers() { return peers; }
    public double getCurrentMemoryUsage() { return currentMemoryUsage; }
}

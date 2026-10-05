package com.jettra.memory.api;

import com.jettra.memory.cluster.ClusterNode;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuración inmutable para el motor JettraMemory.
 */
public final class JettraMemoryConfig implements Serializable {

    private final Path storageDirectory;
    private final String storeName;
    private final double compactionThreshold;
    private final boolean autoGcEnabled;
    private final String nodeId;
    private final String host;
    private final int port;
    private final List<ClusterNode> peerNodes;
    private final boolean clusterModeEnabled;

    private JettraMemoryConfig(Builder builder) {
        this.storageDirectory = Objects.requireNonNull(builder.storageDirectory, "storageDirectory es requerido");
        this.storeName = builder.storeName;
        this.compactionThreshold = builder.compactionThreshold;
        this.autoGcEnabled = builder.autoGcEnabled;
        this.nodeId = builder.nodeId;
        this.host = builder.host;
        this.port = builder.port;
        this.peerNodes = Collections.unmodifiableList(new ArrayList<>(builder.peerNodes));
        this.clusterModeEnabled = builder.clusterModeEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getStorageDirectory() { return storageDirectory; }
    public String getStoreName() { return storeName; }
    public double getCompactionThreshold() { return compactionThreshold; }
    public boolean isAutoGcEnabled() { return autoGcEnabled; }
    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public List<ClusterNode> getPeerNodes() { return peerNodes; }
    public boolean isClusterModeEnabled() { return clusterModeEnabled; }

    public static final class Builder {
        private Path storageDirectory = Path.of("./data/jettra_memory");
        private String storeName = "jettra_store";
        private double compactionThreshold = 0.25; // 25% de fragmentación dispara GC
        private boolean autoGcEnabled = true;
        private String nodeId = "node-1";
        private String host = "127.0.0.1";
        private int port = 9101;
        private final List<ClusterNode> peerNodes = new ArrayList<>();
        private boolean clusterModeEnabled = false;

        public Builder storageDirectory(Path path) {
            this.storageDirectory = path;
            return this;
        }

        public Builder storeName(String name) {
            this.storeName = name;
            return this;
        }

        public Builder compactionThreshold(double threshold) {
            this.compactionThreshold = threshold;
            return this;
        }

        public Builder autoGcEnabled(boolean enabled) {
            this.autoGcEnabled = enabled;
            return this;
        }

        public Builder nodeId(String id) {
            this.nodeId = id;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder addPeer(ClusterNode peer) {
            this.peerNodes.add(peer);
            this.clusterModeEnabled = true;
            return this;
        }

        public Builder clusterMode(boolean enabled) {
            this.clusterModeEnabled = enabled;
            return this;
        }

        public JettraMemoryConfig build() {
            return new JettraMemoryConfig(this);
        }
    }
}

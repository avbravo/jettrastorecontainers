package io.jettra.store.core;

import io.jettra.store.cluster.ClusterNode;
import java.util.ArrayList;
import java.util.List;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class JettraStoreConfig {
    private final String nodeId;
    private final ClusterNode.Role nodeRole;
    private final String clusterPeers;
    private final String rawConfiguredPath;
    private final String storagePath;
    private final int memTableSizeMb;
    private final int ramGlobalLimitMb;
    private final boolean offHeapDirect;
    private final String fileExtension;
    private final StorageMode storageMode;
    private final int ringSaturationThresholdPercent;
    private final int ringReleaseTargetPercent;
    private final boolean jettraPoliceActive;
    private final long jettraPoliceIntervalMs;
    private final boolean jmhMetricsActive;
    private final String jwtAlgorithm;
    private final long jwtExpirationSeconds;
    private final String defaultAdminUsername;
    private final String defaultAdminPassword;
    private final int grpcPort;
    private final int restPort;
    private final boolean clusterMultinodeActive;

    // Configuración avanzada de almacenamiento de índices y prevención de OOM
    private final int indexInitialCapacity;
    private final int indexMaxInMemoryKeys;
    private final boolean indexCompactStorage;
    private final String indexStoragePath;
    private final int autoFlushBatchSize;
    private final int queryDefaultLimit;
    private final int queryMaxLimit;
    private final int queryPageSize;


    private static String getPropOrEnv(Properties props, String sysProp, String envVar, String defaultVal) {
        String sys = System.getProperty(sysProp);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env;
        return props.getProperty(sysProp, defaultVal);
    }

    public JettraStoreConfig(Properties props) {
        this.nodeId = getPropOrEnv(props, "jettra.cluster.node.id", "JETTRA_NODE_ID", "node-01");
        String roleStr = getPropOrEnv(props, "jettra.cluster.node.role", "JETTRA_NODE_ROLE", "PRIMARY");
        this.nodeRole = "PRIMARY".equalsIgnoreCase(roleStr) ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
        this.clusterPeers = getPropOrEnv(props, "jettra.cluster.peers", "JETTRA_CLUSTER_PEERS", "");
        String multinodeStr = getPropOrEnv(props, "cluster.multinode.active", "JETTRA_CLUSTER_MULTINODE_ACTIVE", "on").trim();
        this.clusterMultinodeActive = "on".equalsIgnoreCase(multinodeStr) || "true".equalsIgnoreCase(multinodeStr);

        String configuredPath = getPropOrEnv(props, "jettra.storage.path", "JETTRA_STORAGE_PATH", 
            props.getProperty("jettra.storage.path", "/jettra/data"));
        this.rawConfiguredPath = configuredPath;
        
        String resolvedPath = configuredPath;
        if (resolvedPath.startsWith("~" + java.io.File.separator) || resolvedPath.startsWith("~/")) {
            resolvedPath = System.getProperty("user.home") + resolvedPath.substring(1);
        } else if (resolvedPath.equals("~")) {
            resolvedPath = System.getProperty("user.home");
        }

        // Crear directorio de almacenamiento si no existe
        Path path = Path.of(resolvedPath);
        String effectivePath = resolvedPath;
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (Exception ex) {
            System.err.printf("[JettraStoreConfig] Advertencia: No se pudo crear directorio '%s': %s%n", 
                resolvedPath, ex.getMessage());
        }

        if (Files.exists(path) && Files.isWritable(path)) {
            effectivePath = resolvedPath;
        } else {
            System.err.printf("[JettraStoreConfig] Advertencia: Directorio '%s' no accesible para escritura. Conmutando a fallback local './data/jettra'.%n", 
                resolvedPath);
            effectivePath = "./data/jettra";
            try {
                Files.createDirectories(Path.of(effectivePath));
            } catch (Exception ignored) {}
        }

        this.storagePath = effectivePath;
        this.memTableSizeMb = Integer.parseInt(props.getProperty("jettra.storage.memtable.size.mb", "128"));
        this.ramGlobalLimitMb = Integer.parseInt(props.getProperty("jettra.storage.ram.global.limit.mb", "2048"));
        this.offHeapDirect = Boolean.parseBoolean(props.getProperty("jettra.storage.offheap.direct", "true"));
        this.fileExtension = props.getProperty("jettra.storage.file.extension", ".jettra");
        this.storageMode = StorageMode.fromString(props.getProperty("jettra.storage.mode", "JVM_RAM"));
        this.ringSaturationThresholdPercent = Integer.parseInt(props.getProperty("jettra.ring.saturation.threshold.percent", "85"));
        this.ringReleaseTargetPercent = Integer.parseInt(props.getProperty("jettra.ring.release.target.percent", "45"));
        this.jettraPoliceActive = Boolean.parseBoolean(props.getProperty("jettrapolice.active", "true"));
        this.jettraPoliceIntervalMs = Long.parseLong(props.getProperty("jettrapolice.interval.ms", "500"));
        try {
            double ramWarn = Double.parseDouble(props.getProperty("jettrapolice.ram.warning.threshold", "75"));
            double ramCrit = Double.parseDouble(props.getProperty("jettrapolice.ram.critical.threshold", "85"));
            int maxBatch = Integer.parseInt(props.getProperty("jettrapolice.max.safe.batch.size", "100"));
            io.jettra.store.police.JettraPolice.getInstance().setRamWarningThreshold(ramWarn);
            io.jettra.store.police.JettraPolice.getInstance().setRamCriticalThreshold(ramCrit);
            io.jettra.store.police.JettraPolice.getInstance().setMaxSafeBatchSize(maxBatch);
        } catch (Exception ignored) {}
        this.jmhMetricsActive = Boolean.parseBoolean(props.getProperty("jmh.metrics.active", "false"));
        this.jwtAlgorithm = props.getProperty("jettra.security.jwt.algorithm", "Ed25519");
        this.jwtExpirationSeconds = Long.parseLong(props.getProperty("jettra.security.jwt.expiration.seconds", "86400"));
        this.defaultAdminUsername = getPropOrEnv(props, "jettra.security.default.admin.username", "JETTRA_ADMIN_USERNAME", "admin");
        this.defaultAdminPassword = getPropOrEnv(props, "jettra.security.default.admin.password", "JETTRA_ADMIN_PASSWORD", "admin-jettra");
        this.grpcPort = Integer.parseInt(getPropOrEnv(props, "jettra.network.grpc.port", "JETTRA_GRPC_PORT", "9091"));
        this.restPort = Integer.parseInt(getPropOrEnv(props, "jettra.network.rest.port", "JETTRA_REST_PORT", "8080"));

        this.indexInitialCapacity = Integer.parseInt(props.getProperty("jettra.index.initial.capacity", "65536"));
        this.indexMaxInMemoryKeys = Integer.parseInt(props.getProperty("jettra.index.max.inmemory.keys", "100000"));
        this.indexCompactStorage = Boolean.parseBoolean(props.getProperty("jettra.index.compact.storage", "true"));
        
        String configuredIndexPath = props.getProperty("jettra.index.storage.path", resolvedPath + "/indexes");
        if (configuredIndexPath.startsWith("~" + java.io.File.separator) || configuredIndexPath.startsWith("~/")) {
            configuredIndexPath = System.getProperty("user.home") + configuredIndexPath.substring(1);
        }
        this.indexStoragePath = configuredIndexPath;
        this.autoFlushBatchSize = Integer.parseInt(props.getProperty("jettra.storage.autoflush.batch.size", "50000"));
        this.queryDefaultLimit = Integer.parseInt(props.getProperty("jettra.query.default.limit", "50"));
        this.queryMaxLimit = Integer.parseInt(props.getProperty("jettra.query.max.limit", "5000"));
        this.queryPageSize = Integer.parseInt(props.getProperty("jettra.query.pagesize", "50"));
    }

    public static JettraStoreConfig load() {
        JettraConfigValidator.ensureConfigFilesExist();
        Properties props = new Properties();
        // 1. Cargar defaults de resources del classpath
        try (InputStream is = JettraStoreConfig.class.getResourceAsStream("/database.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException ignored) {}

        // 2. Sobrescribir con archivo externo config/database.properties o database.properties si existe
        Path externalConfig = Path.of("config/database.properties");
        if (Files.exists(externalConfig)) {
            try (InputStream is = Files.newInputStream(externalConfig)) {
                props.load(is);
            } catch (IOException ignored) {}
        } else {
            Path currentConfig = Path.of("database.properties");
            if (Files.exists(currentConfig)) {
                try (InputStream is = Files.newInputStream(currentConfig)) {
                    props.load(is);
                } catch (IOException ignored) {}
            }
        }

        return new JettraStoreConfig(props);
    }

    public String getConfiguredStoragePath() { return rawConfiguredPath; }
    public String getStoragePath() { return storagePath; }
    public int getMemTableSizeMb() { return memTableSizeMb; }
    public int getRamGlobalLimitMb() { return ramGlobalLimitMb; }
    public boolean isOffHeapDirect() { return offHeapDirect; }
    public String getFileExtension() { return fileExtension; }
    public StorageMode getStorageMode() { return storageMode; }
    public int getRingSaturationThresholdPercent() { return ringSaturationThresholdPercent; }
    public int getRingReleaseTargetPercent() { return ringReleaseTargetPercent; }
    public boolean isJettraPoliceActive() { return jettraPoliceActive; }
    public long getJettraPoliceIntervalMs() { return jettraPoliceIntervalMs; }
    public boolean isJmhMetricsActive() { return jmhMetricsActive; }
    public String getJwtAlgorithm() { return jwtAlgorithm; }
    public long getJwtExpirationSeconds() { return jwtExpirationSeconds; }
    public String getDefaultAdminUsername() { return defaultAdminUsername; }
    public String getDefaultAdminPassword() { return defaultAdminPassword; }
    public int getGrpcPort() { return grpcPort; }
    public int getRestPort() { return restPort; }

    public int getIndexInitialCapacity() { return indexInitialCapacity; }
    public int getIndexMaxInMemoryKeys() { return indexMaxInMemoryKeys; }
    public boolean isIndexCompactStorage() { return indexCompactStorage; }
    public String getIndexStoragePath() { return indexStoragePath; }
    public int getAutoFlushBatchSize() { return autoFlushBatchSize; }
    public int getQueryDefaultLimit() { return queryDefaultLimit; }
    public int getQueryMaxLimit() { return queryMaxLimit; }
    public int getQueryPageSize() { return queryPageSize; }

    public boolean isClusterMultinodeActive() { return clusterMultinodeActive; }
    public String getClusterMultinodeActive() { return clusterMultinodeActive ? "on" : "off"; }

    public String getNodeId() { return nodeId; }
    public ClusterNode.Role getNodeRole() { return nodeRole; }
    public String getClusterPeers() { return clusterPeers; }

    public List<ClusterNode> getParsedPeers() {
        List<ClusterNode> list = new ArrayList<>();
        if (clusterPeers != null && !clusterPeers.isBlank()) {
            for (String entry : clusterPeers.split(",")) {
                String[] p = entry.trim().split(":");
                if (p.length >= 3) {
                    String id = p[0].trim();
                    String host = p[1].trim();
                    int port = Integer.parseInt(p[2].trim());
                    ClusterNode.Role role = (p.length >= 4 && "PRIMARY".equalsIgnoreCase(p[3].trim())) 
                        ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
                    list.add(new ClusterNode(id, host, port, role));
                }
            }
        }
        if (list.isEmpty()) {
            if ("node-01".equalsIgnoreCase(nodeId)) {
                list.add(new ClusterNode("node-02", "jettra-node-02", 9091, ClusterNode.Role.SECONDARY));
                list.add(new ClusterNode("node-03", "jettra-node-03", 9091, ClusterNode.Role.SECONDARY));
            } else if ("node-02".equalsIgnoreCase(nodeId)) {
                list.add(new ClusterNode("node-01", "jettra-node-01", 9091, ClusterNode.Role.PRIMARY));
                list.add(new ClusterNode("node-03", "jettra-node-03", 9091, ClusterNode.Role.SECONDARY));
            } else if ("node-03".equalsIgnoreCase(nodeId)) {
                list.add(new ClusterNode("node-01", "jettra-node-01", 9091, ClusterNode.Role.PRIMARY));
                list.add(new ClusterNode("node-02", "jettra-node-02", 9091, ClusterNode.Role.SECONDARY));
            }
        }
        return list;
    }
}

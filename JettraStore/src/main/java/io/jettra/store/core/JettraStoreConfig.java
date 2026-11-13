package io.jettra.store.core;

import io.jettra.store.cluster.ClusterNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class JettraStoreConfig {
    private final String nodeId;
    private final String nodeIp;
    private volatile ClusterNode.Role nodeRole;
    private final String clusterPeers;
    private final List<ClusterNode> parsedPeers;
    private final List<JettraConfigValidator.ClusterNodeInfo> allClusterNodes;
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

    public static String getPropOrEnv(Properties props, String sysProp, String envVar, String defaultVal) {
        return getPropOrEnv(props, new String[]{sysProp}, new String[]{envVar}, defaultVal);
    }

    public static String getPropOrEnv(Properties props, String[] sysProps, String[] envVars, String defaultVal) {
        if (sysProps != null) {
            for (String sp : sysProps) {
                String sys = System.getProperty(sp);
                if (sys != null && !sys.isBlank()) return sys.trim();
            }
        }
        if (envVars != null) {
            for (String ev : envVars) {
                String env = System.getenv(ev);
                if (env != null && !env.isBlank()) return env.trim();
            }
        }
        if (props != null && sysProps != null) {
            for (String sp : sysProps) {
                String val = props.getProperty(sp);
                if (val != null && !val.isBlank()) return val.trim();
            }
        }
        return defaultVal;
    }

    public JettraStoreConfig(Properties props) {
        this(props, new Properties(), new Properties());
    }

    public JettraStoreConfig(Properties props, Properties clusterProps) {
        this(props, clusterProps, new Properties());
    }

    public JettraStoreConfig(Properties props, Properties clusterProps, Properties serverProps) {
        Properties effectiveClusterProps = clusterProps != null ? clusterProps : new Properties();
        Properties effectiveServerProps = serverProps != null ? serverProps : new Properties();
        List<JettraConfigValidator.ClusterNodeInfo> clusterNodes = JettraConfigValidator.parseClusterNodes(effectiveClusterProps);
        this.allClusterNodes = Collections.unmodifiableList(clusterNodes);

        String rawNodeId = getPropOrEnv(props, 
            new String[]{"cluster.node.id", "jettra.node.id", "jettra.cluster.node.id", "node.id"}, 
            new String[]{"CLUSTER_NODE_ID", "JETTRA_NODE_ID", "JETTRA_CLUSTER_NODE_ID"}, 
            "node-01");

        // Sincronizar con el nodo correspondiente en cluster.config buscando por id o número de nodo (ej. node-01 o 01)
        JettraConfigValidator.ClusterNodeInfo currentNode = JettraConfigValidator.findNodeInCluster(clusterNodes, rawNodeId);
        this.nodeId = (currentNode != null && currentNode.id() != null && !currentNode.id().isBlank())
            ? currentNode.id()
            : rawNodeId;
        String nodeNum = (currentNode != null && currentNode.nodeNumber() != null)
            ? currentNode.nodeNumber()
            : JettraConfigValidator.extractNodeNumberOrNull(this.nodeId);

        String defaultRole = (currentNode != null && currentNode.role() != null && !currentNode.role().isBlank())
            ? currentNode.role()
            : "PRIMARY";
        String sysOrEnvRole = getPropOrEnv(null,
            new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
            new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"},
            null);
        String roleStr;
        if (sysOrEnvRole != null && !sysOrEnvRole.isBlank()) {
            roleStr = sysOrEnvRole;
        } else if (currentNode != null && !effectiveClusterProps.isEmpty()) {
            roleStr = defaultRole;
        } else {
            roleStr = getPropOrEnv(props,
                new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
                new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"},
                defaultRole);
        }
        this.nodeRole = "PRIMARY".equalsIgnoreCase(roleStr) ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;

        String defaultIp = (currentNode != null && currentNode.ip() != null && !currentNode.ip().isBlank())
            ? currentNode.ip()
            : effectiveServerProps.getProperty("server.host", "127.0.0.1");
        this.nodeIp = getPropOrEnv(props, 
            new String[]{"jettra.node.ip", "jettra.network.ip", "jettra.cluster.node.ip", "node.ip", "server.host"}, 
            new String[]{"JETTRA_NODE_IP", "JETTRA_IP", "JETTRA_NETWORK_IP", "JETTRA_SERVER_HOST"}, 
            defaultIp);

        int defaultGrpc = (currentNode != null && currentNode.grpcPort() > 0) ? currentNode.grpcPort() : 9091;
        this.grpcPort = Integer.parseInt(getPropOrEnv(props, 
            new String[]{"jettra.network.grpc.port", "jettra.grpc.port", "grpc.port"}, 
            new String[]{"JETTRA_GRPC_PORT", "JETTRA_NETWORK_GRPC_PORT"}, 
            String.valueOf(defaultGrpc)));

        int defaultRest = (currentNode != null && currentNode.restPort() > 0) ? currentNode.restPort() : 8082;
        if (effectiveServerProps.containsKey("server.port")) {
            try {
                defaultRest = Integer.parseInt(effectiveServerProps.getProperty("server.port").trim());
            } catch (Exception ignored) {}
        } else if (effectiveServerProps.containsKey("jettra.network.rest.port")) {
            try {
                defaultRest = Integer.parseInt(effectiveServerProps.getProperty("jettra.network.rest.port").trim());
            } catch (Exception ignored) {}
        }
        String configuredRest = getPropOrEnv(props,
            new String[]{"jettra.network.rest.port", "jettra.rest.port", "server.port", "jettra.server.port", "rest.port"},
            new String[]{"JETTRA_REST_PORT", "JETTRA_SERVER_PORT", "SERVER_PORT", "JETTRA_NETWORK_REST_PORT"},
            String.valueOf(defaultRest));
        this.restPort = Integer.parseInt(configuredRest);

        this.clusterPeers = getPropOrEnv(props, 
            new String[]{"jettra.cluster.peers", "cluster.peers"}, 
            new String[]{"JETTRA_CLUSTER_PEERS"}, 
            "");

        String defaultMultinode = (clusterProps != null && clusterProps.containsKey("cluster.multinode.active"))
            ? clusterProps.getProperty("cluster.multinode.active", "on") : "on";
        String multinodeStr = getPropOrEnv(props, 
            new String[]{"cluster.multinode.active", "jettra.cluster.multinode.active"}, 
            new String[]{"JETTRA_CLUSTER_MULTINODE_ACTIVE"}, 
            defaultMultinode).trim();
        this.clusterMultinodeActive = "on".equalsIgnoreCase(multinodeStr) || "true".equalsIgnoreCase(multinodeStr);

        String defaultStorage = effectiveServerProps.getProperty("data.dir",
            (currentNode != null && currentNode.storagePath() != null && !currentNode.storagePath().isBlank())
            ? currentNode.storagePath() : "/jettra/data");

        // 1. Prioridad: System property explícito
        String configuredPath = null;
        for (String sp : new String[]{"storage.path", "jettra.storage.path", "data.dir"}) {
            String val = System.getProperty(sp);
            if (val != null && !val.isBlank()) {
                configuredPath = val.trim();
                break;
            }
        }
        // 2. Variables de entorno
        if (configuredPath == null) {
            for (String ev : new String[]{"STORAGE_PATH", "JETTRA_STORAGE_PATH", "JETTRA_DATA_DIR", "DATA_DIR"}) {
                String val = System.getenv(ev);
                if (val != null && !val.isBlank()) {
                    configuredPath = val.trim();
                    break;
                }
            }
        }
        // 3. Archivo microprofile-config.properties
        if (configuredPath == null && props != null) {
            String spVal = props.getProperty("storage.path");
            String jspVal = props.getProperty("jettra.storage.path");
            String ddVal = props.getProperty("data.dir");

            boolean spCustom = spVal != null && !spVal.isBlank() && !spVal.equals("~/jettra/data") && !spVal.equals("/jettra/data");
            boolean jspCustom = jspVal != null && !jspVal.isBlank() && !jspVal.equals("~/jettra/data") && !jspVal.equals("/jettra/data");

            if (spCustom && !jspCustom) {
                configuredPath = spVal.trim();
            } else if (jspCustom && !spCustom) {
                configuredPath = jspVal.trim();
            } else if (spVal != null && !spVal.isBlank()) {
                configuredPath = spVal.trim();
            } else if (jspVal != null && !jspVal.isBlank()) {
                configuredPath = jspVal.trim();
            } else if (ddVal != null && !ddVal.isBlank()) {
                configuredPath = ddVal.trim();
            }
        }
        if (configuredPath == null) {
            configuredPath = defaultStorage;
        }
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
            boolean fallbackApplied = false;
            if (resolvedPath.startsWith("/jettra")) {
                Path userHomeFallback = Path.of(System.getProperty("user.home"), resolvedPath.substring(1));
                try {
                    Files.createDirectories(userHomeFallback);
                    if (Files.exists(userHomeFallback) && Files.isWritable(userHomeFallback)) {
                        effectivePath = userHomeFallback.toAbsolutePath().toString();
                        fallbackApplied = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!fallbackApplied) {
                System.err.printf("[JettraStoreConfig] Advertencia: Directorio '%s' no accesible para escritura. Conmutando a fallback local './data/jettra'.%n", 
                    resolvedPath);
                effectivePath = "./data/jettra";
                try {
                    Files.createDirectories(Path.of(effectivePath));
                } catch (Exception ignored) {}
            }
        }

        this.storagePath = effectivePath;
        this.memTableSizeMb = Integer.parseInt(props.getProperty("jettra.storage.memtable.size.mb", "128"));
        this.ramGlobalLimitMb = Integer.parseInt(props.getProperty("jettra.storage.ram.global.limit.mb", "2048"));
        this.offHeapDirect = Boolean.parseBoolean(props.getProperty("jettra.storage.offheap.direct", "true"));
        this.fileExtension = props.getProperty("jettra.storage.file.extension", ".jettra");
        String modeStr = getPropOrEnv(props, 
            new String[]{"jettra.storage.mode", "storage.mode"}, 
            new String[]{"JETTRA_STORAGE_MODE", "STORAGE_MODE"}, 
            "JVM_RAM");
        this.storageMode = StorageMode.fromString(modeStr);
        System.out.printf("[JettraStoreConfig] Parámetro 'jettra.storage.mode' configurado como: %s (%s)%n",
            this.storageMode.name(), this.storageMode.getDescription());
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

        this.indexInitialCapacity = Integer.parseInt(props.getProperty("jettra.index.initial.capacity", "65536"));
        this.indexMaxInMemoryKeys = Integer.parseInt(props.getProperty("jettra.index.max.inmemory.keys", "100000"));
        this.indexCompactStorage = Boolean.parseBoolean(props.getProperty("jettra.index.compact.storage", "true"));
        
        String configuredIndexPath = null;
        for (String sp : new String[]{"index.storage.path", "jettra.index.storage.path"}) {
            String val = System.getProperty(sp);
            if (val != null && !val.isBlank()) {
                configuredIndexPath = val.trim();
                break;
            }
        }
        if (configuredIndexPath == null) {
            for (String ev : new String[]{"INDEX_STORAGE_PATH", "JETTRA_INDEX_STORAGE_PATH"}) {
                String val = System.getenv(ev);
                if (val != null && !val.isBlank()) {
                    configuredIndexPath = val.trim();
                    break;
                }
            }
        }
        if (configuredIndexPath == null && props != null) {
            String isp = props.getProperty("index.storage.path");
            String jisp = props.getProperty("jettra.index.storage.path");
            boolean ispCustom = isp != null && !isp.isBlank() && !isp.equals("~/jettra/data/indexes") && !isp.equals("/jettra/data/indexes");
            boolean jispCustom = jisp != null && !jisp.isBlank() && !jisp.equals("~/jettra/data/indexes") && !jisp.equals("/jettra/data/indexes");

            if (ispCustom && !jispCustom) {
                configuredIndexPath = isp.trim();
            } else if (jispCustom && !ispCustom) {
                configuredIndexPath = jisp.trim();
            } else if (isp != null && !isp.isBlank()) {
                configuredIndexPath = isp.trim();
            } else if (jisp != null && !jisp.isBlank()) {
                configuredIndexPath = jisp.trim();
            }
        }
        if (configuredIndexPath == null) {
            configuredIndexPath = effectivePath + "/indexes";
        }
        if (configuredIndexPath.startsWith("~" + java.io.File.separator) || configuredIndexPath.startsWith("~/")) {
            configuredIndexPath = System.getProperty("user.home") + configuredIndexPath.substring(1);
        } else if (configuredIndexPath.equals("~")) {
            configuredIndexPath = System.getProperty("user.home");
        }
        this.indexStoragePath = configuredIndexPath;
        ensureStorageDirectoriesExist();
        this.autoFlushBatchSize = Integer.parseInt(props.getProperty("jettra.storage.autoflush.batch.size", "50000"));
        this.queryDefaultLimit = Integer.parseInt(props.getProperty("jettra.query.default.limit", "50"));
        this.queryMaxLimit = Integer.parseInt(props.getProperty("jettra.query.max.limit", "5000"));
        this.queryPageSize = Integer.parseInt(props.getProperty("jettra.query.pagesize", "50"));

        // Resolver pares del clúster con IPs y puertos configurados
        List<ClusterNode> peersList = new ArrayList<>();
        if (this.clusterPeers != null && !this.clusterPeers.isBlank()) {
            for (String entry : this.clusterPeers.split(",")) {
                String[] p = entry.trim().split(":");
                if (p.length >= 3) {
                    String id = p[0].trim();
                    String host = p[1].trim();
                    int port = Integer.parseInt(p[2].trim());
                    ClusterNode.Role role = (p.length >= 4 && "PRIMARY".equalsIgnoreCase(p[3].trim())) 
                        ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
                    peersList.add(new ClusterNode(id, host, port, role));
                }
            }
        } else if (!clusterNodes.isEmpty()) {
            for (JettraConfigValidator.ClusterNodeInfo node : clusterNodes) {
                if (!node.id().equalsIgnoreCase(this.nodeId) && (nodeNum == null || !nodeNum.equals(node.nodeNumber()))) {
                    ClusterNode.Role peerRole = "PRIMARY".equalsIgnoreCase(node.role()) 
                        ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
                    peersList.add(new ClusterNode(node.id(), node.ip(), node.grpcPort(), peerRole));
                }
            }
        }
        if (peersList.isEmpty()) {
            if ("node-01".equalsIgnoreCase(this.nodeId)) {
                peersList.add(new ClusterNode("node-02", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
                peersList.add(new ClusterNode("node-03", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
            } else if ("node-02".equalsIgnoreCase(this.nodeId)) {
                peersList.add(new ClusterNode("node-01", "127.0.0.1", 9091, ClusterNode.Role.PRIMARY));
                peersList.add(new ClusterNode("node-03", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
            } else if ("node-03".equalsIgnoreCase(this.nodeId)) {
                peersList.add(new ClusterNode("node-01", "127.0.0.1", 9091, ClusterNode.Role.PRIMARY));
                peersList.add(new ClusterNode("node-02", "127.0.0.1", 9091, ClusterNode.Role.SECONDARY));
            }
        }
        this.parsedPeers = Collections.unmodifiableList(peersList);
    }

    public static JettraStoreConfig load() {
        JettraConfigValidator.ensureConfigFilesExist();

        Path configPath = JettraConfigValidator.locateMicroProfileConfigFile();
        Path clusterPath = JettraConfigValidator.locateClusterConfigFile();

        Properties configProps = JettraConfigValidator.loadProperties(configPath, 
            "/META-INF/microprofile-config.properties", "/microprofile-config.properties", "/jettraserver.config");
        Properties clusterProps = JettraConfigValidator.loadProperties(clusterPath, 
            "/cluster.config", "/jettra.config");

        return new JettraStoreConfig(configProps, clusterProps, configProps);
    }

    public String getConfiguredStoragePath() { return rawConfiguredPath; }
    public String getStoragePath() { return storagePath; }

    /**
     * Verifica si existe el directorio de almacenamiento del nodo (storage.path)
     * obtenido desde cluster.config y lo crea si no existe para almacenar bases de datos y objetos.
     */
    public final void ensureStorageDirectoriesExist() {
        try {
            if (this.storagePath != null && !this.storagePath.isBlank()) {
                Path dir = Path.of(this.storagePath);
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                    System.out.printf("[JettraStoreConfig] Directorio de almacenamiento creado: %s%n",
                        dir.toAbsolutePath().normalize());
                }
            }
        } catch (Exception ex) {
            System.err.printf("[JettraStoreConfig] Advertencia al verificar/crear directorio de almacenamiento ('%s'): %s%n",
                this.storagePath, ex.getMessage());
        }
    }
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
    public String getNodeIp() { return nodeIp; }

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
    public void setNodeRole(ClusterNode.Role role) { this.nodeRole = role; }
    public String getClusterPeers() { return clusterPeers; }

    public List<ClusterNode> getParsedPeers() {
        return parsedPeers;
    }

    public List<JettraConfigValidator.ClusterNodeInfo> getAllClusterNodes() {
        return allClusterNodes;
    }
}

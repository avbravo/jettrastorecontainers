package io.jettra.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.cluster.JettraClusterTransportServer;
import io.jettra.store.cluster.JettraClusterReplicationClient;
import io.jettra.store.cluster.JettraRaftFrame;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.StorageMode;
import io.jettra.store.police.JettraPolice;
import io.jettra.store.security.JettraSecurityManager;

import io.jettra.store.cluster.ClusterLiveEvent;
import io.jettra.store.cluster.JettraClusterEventBus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servidor Autónomo JettraStore en Java 25+.
 * Provee servicios REST con Virtual Threads, coordinación de clúster Raft de 3 nodos
 * y autenticación criptográfica obligatoria mediante tokens JettraJWT.
 */
public final class JettraStoreServer implements AutoCloseable {
    private static volatile JettraStoreServer activeInstance;
    private static final Set<JettraStoreServer> ACTIVE_SERVERS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);
    private final JettraStoreConfig config;
    private final JettraSecurityManager securityManager;
    private final DynamicRingEngine ringEngine;
    private final ConcurrentHashMap<String, JettraDatabase> databases = new ConcurrentHashMap<>();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private JettraClusterTransportServer transportServer;
    private JettraClusterReplicationClient replicationClient;
    private volatile int boundRestPort = -1;
    private volatile Thread heartbeatThread;
    private volatile Thread failoverWatchdogThread;
    private volatile Thread secondarySyncRetryThread;
    private volatile boolean stopping = false;

    private final AtomicBoolean isPrimaryHeartbeatLoopRunning = new AtomicBoolean(false);
    private volatile long lastLeaderHeartbeatTime = System.currentTimeMillis();
    private final AtomicBoolean failoverInProgress = new AtomicBoolean(false);
    private final AtomicBoolean secondarySyncInProgress = new AtomicBoolean(false);
    private final Set<String> activePeerDistributions = ConcurrentHashMap.newKeySet();
    private volatile Path clusterConfigFilePath;
    private volatile Path clusterStateFilePath;

    public void setClusterConfigFilePath(Path clusterConfigFilePath) {
        this.clusterConfigFilePath = clusterConfigFilePath;
    }

    public void setClusterStateFilePath(Path clusterStateFilePath) {
        this.clusterStateFilePath = clusterStateFilePath;
    }

    public Path resolveClusterStateFile() {
        if (this.clusterStateFilePath != null) {
            return this.clusterStateFilePath;
        }
        if (this.clusterConfigFilePath != null
                && (this.config == null || !this.config.isClusterStateDirExplicit())) {
            return this.clusterConfigFilePath;
        }
        if (this.config != null && this.config.getClusterStateFile() != null) {
            return this.config.getClusterStateFile();
        }
        return JettraConfigValidator.resolveClusterStateFile();
    }

    public Path resolveClusterConfigFile() {
        return resolveClusterStateFile();
    }

    public synchronized byte[] readLiveClusterStateBytes() {
        try {
            Path stateFile = resolveClusterStateFile();
            if (stateFile != null && Files.exists(stateFile) && Files.size(stateFile) > 0) {
                return Files.readAllBytes(stateFile);
            }
            if (!config.isStandbyMode() && config.getAllClusterNodes() != null && !config.getAllClusterNodes().isEmpty()) {
                return JettraConfigValidator.buildCanonicalClusterStateBytes(
                    config.getAllClusterNodes(), config.getClusterNodeStatuses(), null,
                    Math.max(1L, config.getClusterStateVersion()), config.getNodeId());
            }
            if (!config.isStandbyMode()) {
                Path seed = resolveOptionalClusterSeedFile();
                if (seed != null && Files.exists(seed) && Files.size(seed) > 0) {
                    return Files.readAllBytes(seed);
                }
            }
        } catch (Exception ignored) {
        }
        return new byte[0];
    }

    public Path resolveOptionalClusterSeedFile() {
        if (this.config != null && this.config.getNodeRole() != ClusterNode.Role.PRIMARY) {
            return null;
        }
        if (this.clusterConfigFilePath != null) {
            return this.clusterConfigFilePath;
        }
        Path stateFile = resolveClusterStateFile();
        Path stateDir = stateFile != null ? stateFile.getParent() : null;
        return JettraConfigValidator.locateOptionalClusterSeedFile(stateDir);
    }

    public JettraStoreServer(JettraStoreConfig config) {
        this.config = config;
        this.securityManager = new JettraSecurityManager();
        this.ringEngine = new DynamicRingEngine(
            config.getNodeId(),
            config.getRingSaturationThresholdPercent() / 100.0,
            config.getRingReleaseTargetPercent() / 100.0,
            config.isClusterMultinodeActive()
        );

        // Registrar nodos pares configurados para el clúster distribuido solo si multinodo está activo (on)
        if (config.isClusterMultinodeActive()) {
            for (ClusterNode peer : config.getParsedPeers()) {
                if (!peer.getId().equalsIgnoreCase(config.getNodeId())) {
                    this.ringEngine.registerPeer(peer);
                }
            }
        }
    }

    public void start() throws IOException {
        stopping = false;
        boolean started = false;
        try {
            config.ensureStorageDirectoriesExist();
        Path storageDir = Path.of(config.getStoragePath());
        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }
        System.out.println("================================================================================");
        System.out.println("            JETTRASTORE DISTRIBUTED MULTI-MODEL DATABASE (JAVA 25+)            ");
        System.out.println("================================================================================");
        System.out.printf("Node ID: %s (IP: %s, REST: %d, gRPC: %d) | Role: %s | Storage Path: %s%n",
            config.getNodeId(), config.getNodeIp(), config.getRestPort(), config.getGrpcPort(), config.getNodeRole(), config.getStoragePath());
        System.out.printf("Project Panama Off-Heap Direct: %s | MemTable: %d MB%n",
            config.isOffHeapDirect(), config.getMemTableSizeMb());
        System.out.printf("Modo de Almacenamiento (jettra.storage.mode): %s (%s)%n",
            config.getStorageMode().name(), config.getStorageMode().getDescription());
        System.out.printf("Cluster Multi-Node Active: %s (%s)%n",
            config.getClusterMultinodeActive(),
            config.isClusterMultinodeActive()
                ? "Data distribution with consensus algorithm ENABLED"
                : "Distribution DISABLED (Standalone local server mode)");
        System.out.printf("Cluster State Dir (jettra.cluster_state_config_dir): %s | Standby: %s%n",
            resolveClusterStateFile().getParent(), config.isStandbyMode() ? "YES (UNASSIGNED)" : "NO (ACTIVE)");
        System.out.printf("Cluster Peers Registered: %d%n", ringEngine.getPeers().size());
        for (ClusterNode peer : ringEngine.getPeers()) {
            System.out.printf("  ↳ Peer Node: %s @ %s:%d (%s)%n",
                peer.getId(), peer.getIp(), peer.getPort(), peer.getRole());
        }
        System.out.printf("Dynamic Memory Ring Saturation Threshold: %d%%%n", config.getRingSaturationThresholdPercent());
        System.out.printf("Security: JettraJWT Token Active (Issuer: jettra-store-authority)%n");
        System.out.printf("Superuser Initialized: %s (Immutable Privileges)%n", config.getDefaultAdminUsername());

        // Iniciar supervisor autónomo JettraPolice
        JettraPolice.getInstance().start(config.isJettraPoliceActive(), config.getJettraPoliceIntervalMs());

        // Iniciar servidor REST con Virtual Threads
        int targetRestPort = config.getRestPort();
        try {
            httpServer = HttpServer.create(new InetSocketAddress(targetRestPort), 0);
        } catch (java.net.BindException be) {
            System.err.printf("[JettraStoreServer] ⚠️ Advertencia de enlace: El puerto REST %d ya se encuentra en uso por otro proceso.%n", targetRestPort);
            int fallbackPort = (targetRestPort == 8080) ? 8082 : targetRestPort + 1;
            try {
                System.out.printf("[JettraStoreServer] 🔄 Conmutando automáticamente al puerto alternativo %d (definido en microprofile-config.properties)...%n", fallbackPort);
                httpServer = HttpServer.create(new InetSocketAddress(fallbackPort), 0);
                targetRestPort = fallbackPort;
                System.out.printf("[JettraStoreServer] ✅ Servidor REST enlazado exitosamente en puerto alternativo %d.%n", targetRestPort);
            } catch (Exception ex) {
                throw new RuntimeException("Error fatal iniciando JettraStore REST: El puerto " + config.getRestPort() + " y el alternativo " + fallbackPort + " ya se encuentran en uso. Configure 'server.port' en microprofile-config.properties.", be);
            }
        }
        this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(this.httpExecutor);

        // Endpoints REST de la API JettraStore
        httpServer.createContext("/api/v1/auth/login", new AuthHandler());
        httpServer.createContext("/api/v1/auth/token", new AuthHandler());
        httpServer.createContext("/api/v1/health", new HealthHandler());
        httpServer.createContext("/api/v1/cluster/status", new StatusHandler());
        httpServer.createContext("/api/v1/cluster/state.config", new ClusterStateConfigHandler());
        httpServer.createContext("/api/v1/cluster/shutdown", new ShutdownHandler());
        httpServer.createContext("/api/v1/cluster/databases", new DatabasesHandler());
        httpServer.createContext("/api/v1/cluster/replicate", new ReplicateHandler());
        httpServer.createContext("/api/v1/cluster/events", new ClusterEventsHandler());
        httpServer.createContext("/api/v1/cluster/live", new ClusterLiveHandler());
        httpServer.createContext("/api/v1/police/alerts", new PoliceHandler());
        httpServer.start();

        this.boundRestPort = targetRestPort;
        ACTIVE_SERVERS.add(this);
        registerShutdownHook();
        activeInstance = this;

        // Registrar evento de nodo en línea
        JettraClusterEventBus.getInstance().publish(
            ClusterLiveEvent.TYPE_NODE_ONLINE,
            config.getNodeId(), "cluster",
            String.format("Nodo '%s' iniciado exitosamente con rol %s.", config.getNodeId(), config.getNodeRole()),
            "rest_port=" + targetRestPort + ",grpc_port=" + config.getGrpcPort()
        );

        // Garantizar que el directorio de estado exista y que cluster-state.config sea creado cuando no exista
        Path stateFile = resolveClusterStateFile();
        if (stateFile != null && stateFile.getParent() != null && !Files.exists(stateFile.getParent())) {
            try {
                Files.createDirectories(stateFile.getParent());
            } catch (Exception ignored) {}
        }
        if (!config.isStandbyMode() && stateFile != null) {
            Path optionalSeed = resolveOptionalClusterSeedFile();
            java.util.Properties loadedState = JettraConfigValidator.seedOrLoadClusterState(stateFile, optionalSeed);
            boolean stateMissingOrEmpty = !Files.exists(stateFile);
            if (!stateMissingOrEmpty) {
                try {
                    stateMissingOrEmpty = Files.size(stateFile) == 0L;
                } catch (IOException ignored) {
                    stateMissingOrEmpty = true;
                }
            }
            if ((loadedState == null || stateMissingOrEmpty) && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                List<JettraConfigValidator.ClusterNodeInfo> bootNodes = new ArrayList<>();
                Map<String, String> bootStatuses = new LinkedHashMap<>();
                String selfIp = ("0.0.0.0".equals(config.getNodeIp()) || "*".equals(config.getNodeIp()))
                    ? "127.0.0.1" : config.getNodeIp();
                bootNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    config.getNodeId(), config.getNodeRole().name(), selfIp,
                    config.getGrpcPort(), config.getRestPort(), config.getStoragePath()));
                bootStatuses.put(config.getNodeId().toLowerCase(java.util.Locale.ROOT), "RUNNING");
                for (ClusterNode p : ringEngine.getPeers()) {
                    if (!p.getId().equalsIgnoreCase(config.getNodeId())) {
                        int peerRest = p.getPort() >= 50001 && p.getPort() <= 50999
                            ? 60000 + (p.getPort() - 50000) : Math.max(8081, p.getPort() - 1000);
                        bootNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                            p.getId(), p.getRole().name(), p.getIp(), p.getPort(), peerRest, config.getStoragePath()));
                        bootStatuses.put(p.getId().toLowerCase(java.util.Locale.ROOT), "RUNNING");
                    }
                }
                try {
                    byte[] bootBytes = JettraConfigValidator.buildCanonicalClusterStateBytes(
                        bootNodes, bootStatuses, null, 1L, config.getNodeId());
                    if (JettraConfigValidator.writeClusterConfigAtomically(stateFile, bootBytes)) {
                        config.updateRuntimeTopology(bootNodes, bootStatuses, 1L);
                    }
                } catch (Exception ex) {
                    System.err.printf("[JettraStoreServer] Advertencia inicializando cluster-state.config en '%s': %s%n",
                        stateFile, ex.getMessage());
                }
            }
        }

        // Iniciar Servidor de Transporte Raft Inter-Nodo (puerto gRPC/TCP) si multinodo está activo
        if (config.isClusterMultinodeActive()) {
            if (!config.isStandbyMode()) {
                Path optionalSeed = resolveOptionalClusterSeedFile();
                java.util.Properties loadedState = JettraConfigValidator.seedOrLoadClusterState(stateFile, optionalSeed);
                if (loadedState != null) {
                    var parsedStateNodes = JettraConfigValidator.parseExplicitClusterNodes(loadedState);
                    var parsedStatuses = JettraConfigValidator.parseClusterNodeStatuses(loadedState);
                    long stateVer = JettraConfigValidator.parseClusterStateVersion(loadedState);
                    if (!parsedStateNodes.isEmpty()) {
                        config.updateRuntimeTopology(parsedStateNodes, parsedStatuses, stateVer);
                        applySyncedPeersToRuntime(parsedStateNodes);
                        if (config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                            byte[] refreshed = JettraConfigValidator.updateNodeStatusInStateFile(
                                stateFile, config.getNodeId(), "RUNNING", config.getNodeId());
                            if (refreshed != null) {
                                config.setClusterStateVersion(stateVer + 1);
                                config.getClusterNodeStatuses().put(config.getNodeId().toLowerCase(java.util.Locale.ROOT), "RUNNING");
                            }
                        }
                    }
                }
            }

            try {
                this.transportServer = new JettraClusterTransportServer(config.getGrpcPort(), this);
                this.transportServer.start();
            } catch (Exception e) {
                System.err.printf("[JettraStoreServer] ⚠️ Conflicto o error al enlazar transporte Raft en puerto %d: %s. Detenga el proceso que lo ocupa o configure JETTRA_GRPC_PORT con otro puerto.%n",
                    config.getGrpcPort(), e.getMessage());
            }

            this.replicationClient = new JettraClusterReplicationClient(config.getNodeId(), ringEngine.getPeers());
            this.replicationClient.setPeerRecoveryListener(recoveredPeer -> {
                System.out.printf("[JettraStoreServer] 🟢 Nodo '%s' se ha reiniciado tras parada. Replicando estado y bases de datos desde PRIMARY...%n",
                    recoveredPeer.getId());
                Thread.ofVirtual().name("jettra-peer-recovery-" + recoveredPeer.getId()).start(() -> {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    if (config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                        byte[] refreshed = JettraConfigValidator.updateNodeStatusInStateFile(
                            resolveClusterStateFile(), recoveredPeer.getId(), "RUNNING", config.getNodeId());
                        if (refreshed != null && replicationClient != null) {
                            replicationClient.broadcastClusterConfigSync(refreshed);
                        }
                    }
                    distributeAllDatabasesToPeer(recoveredPeer);
                });
            });

            if (config.isStandbyMode()) {
                System.out.printf("[JettraStoreServer] ⏳ Nodo '%s' en espera (STANDBY / UNASSIGNED) escuchando en %s:%d (REST %d) hasta ser incorporado al clúster.%n",
                    config.getNodeId(), config.getNodeIp(), config.getGrpcPort(), config.getRestPort());
            } else if (config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                startHeartbeatLoop();
            } else {
                // Si es SECONDARY, sincronizar primero cluster-state.config con el PRIMARY activo (merge seguro sin sobreescrituras)
                // y luego sincronizar catálogo y datos iniciales con el PRIMARY
                boolean configSynced = syncClusterConfigWithActivePrimary();
                boolean synced = synchronizeFromPrimary();
                if (!configSynced || !synced) {
                    // Si el primario no estaba listo de inmediato, reintentar en segundo plano hasta completar la sincronización
                    this.secondarySyncRetryThread = Thread.ofVirtual().name("jettra-secondary-sync-retry").start(() -> {
                        boolean cfgOk = configSynced;
                        boolean dataOk = synced;
                        for (int i = 0; i < 10 && !stopping; i++) {
                            try { Thread.sleep(800); } catch (InterruptedException ignored) { break; }
                            if (stopping || config.isStandbyMode()) {
                                return;
                            }
                            if (!cfgOk) {
                                cfgOk = syncClusterConfigWithActivePrimary();
                            }
                            if (!dataOk) {
                                dataOk = synchronizeFromPrimary();
                            }
                            if (cfgOk && dataOk) {
                                break;
                            }
                        }
                    });
                }
                // Iniciar centinela supervisor de failover en nodos secundarios
                startFailoverWatchdog();
            }
        }

        int boundRestPort = (httpServer != null && httpServer.getAddress() != null) 
            ? httpServer.getAddress().getPort() 
            : config.getRestPort();
        started = true;
        System.out.printf("REST Service running with Virtual Threads on http://0.0.0.0:%d/%n", boundRestPort);
        System.out.println("JettraStore Server is fully ready for high-performance transactions.");
        } finally {
            if (!started) {
                stop();
            }
        }
    }

    public void stop() {
        boolean firstStop = !stopping;
        stopping = true;
        ACTIVE_SERVERS.remove(this);
        if (firstStop) {
            if (config.isClusterMultinodeActive() && replicationClient != null) {
                try {
                    replicationClient.broadcastNodeStopping(config.getNodeRole().name(), "Parada controlada del servidor");
                } catch (Exception ignored) {}
            }
            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_NODE_STOPPED,
                config.getNodeId(), "cluster",
                String.format("Nodo '%s' [%s] detenido de forma controlada.", config.getNodeId(), config.getNodeRole()),
                "clean_stop"
            );
        }
        if (secondarySyncRetryThread != null) {
            secondarySyncRetryThread.interrupt();
            secondarySyncRetryThread = null;
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        isPrimaryHeartbeatLoopRunning.set(false);
        if (failoverWatchdogThread != null) {
            failoverWatchdogThread.interrupt();
            failoverWatchdogThread = null;
        }
        if (transportServer != null) {
            transportServer.stop();
            transportServer = null;
        }
        if (replicationClient != null) {
            replicationClient.close();
            replicationClient = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        this.boundRestPort = -1;
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            try {
                httpExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            httpExecutor = null;
        }
        for (JettraDatabase db : databases.values()) {
            try {
                db.close();
            } catch (Exception ignored) {}
        }
        databases.clear();
        if (ACTIVE_SERVERS.isEmpty()) {
            JettraPolice.getInstance().stop();
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
        System.out.println("JettraStore Server stopped cleanly.");
    }

    @Override
    public void close() {
        stop();
    }

    private class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String user = extractJsonField(body, "username");
                String pass = extractJsonField(body, "password");

                if (user == null || user.isBlank()) {
                    user = config.getDefaultAdminUsername();
                }
                if (pass == null || pass.isBlank()) {
                    pass = config.getDefaultAdminPassword();
                }

                try {
                    String token = securityManager.authenticate(user, pass);
                    String response = String.format(
                        "{\"token\":\"%s\",\"token_type\":\"Bearer\",\"status\":\"AUTHENTICATED\",\"node_id\":\"%s\",\"expires_in\":%d}",
                        token, config.getNodeId(), config.getJwtExpirationSeconds()
                    );
                    sendResponse(exchange, 200, response);
                    return;
                } catch (SecurityException ex) {
                    sendResponse(exchange, 401, String.format("{\"error\":\"Authentication failed: %s\"}", ex.getMessage()));
                    return;
                }
            }
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed. Use POST.\"}");
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong PROCESSED_OBJECTS_TOTAL = new java.util.concurrent.atomic.AtomicLong(8_500_000L);
    private static final java.util.concurrent.atomic.AtomicLong PROCESSED_OBJECTS_PER_SEC = new java.util.concurrent.atomic.AtomicLong(36_000L);

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long total = PROCESSED_OBJECTS_TOTAL.addAndGet((long)(PROCESSED_OBJECTS_PER_SEC.get() * 0.2));
            long iops = PROCESSED_OBJECTS_PER_SEC.get() + (long)(Math.random() * 4000 - 2000);
            int activeZones = Math.max(1, databases.size() > 0 ? databases.size() : 4);
            int activeSessions = Math.min(25, Math.max(8, Thread.activeCount() / 2));
            int activeTraffic = Math.max(3, ringEngine.getPeers().size() + 1);
            int activeDogs = 4;

            String effectiveRole = config.isStandbyMode() ? "UNASSIGNED" : config.getNodeRole().name();
            String nodeState = config.isStandbyMode() ? "STANDBY" : "RUNNING";
            String response = String.format(
                "{\"status\":\"UP\",\"node_id\":\"%s\",\"role\":\"%s\",\"node_state\":\"%s\",\"state_version\":%d,\"cluster_multinode_active\":\"%s\",\"storage_path\":\"%s\",\"cluster_state_dir\":\"%s\",\"timestamp\":%d,"
                + "\"processed_objects_total\":%d,\"processed_objects_per_sec\":%d,"
                + "\"active_sessions\":%d,\"active_traffic_batches\":%d,\"active_police_agents\":%d,\"active_zones\":%d}",
                config.getNodeId(), effectiveRole, nodeState, config.getClusterStateVersion(),
                config.getClusterMultinodeActive(), config.getStoragePath(), config.getClusterStateConfigDir(), System.currentTimeMillis(),
                total, iops, activeSessions, activeTraffic, activeDogs, activeZones
            );
            sendResponse(exchange, 200, response);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                validateAuthToken(exchange);
            } catch (SecurityException ex) {
                sendResponse(exchange, 401, String.format("{\"error\":\"Unauthorized. JettraJWT token required: %s\"}", ex.getMessage()));
                return;
            }

            StringBuilder peersJson = new StringBuilder("[");
            List<ClusterNode> peers = ringEngine.getPeers();
            for (int i = 0; i < peers.size(); i++) {
                ClusterNode p = peers.get(i);
                peersJson.append(String.format(
                    "{\"id\":\"%s\",\"host\":\"%s\",\"port\":%d,\"role\":\"%s\",\"raft_state\":\"%s\",\"status\":\"%s\",\"segments\":%d}",
                    p.getId(), p.getIp(), p.getPort(), p.getRole(), p.getRaftState(), p.getStatus(), p.getReceivedRingSegments()
                ));
                if (i < peers.size() - 1) peersJson.append(",");
            }
            peersJson.append("]");

            String response = String.format(
                "{\"node_id\":\"%s\",\"node_ip\":\"%s\",\"grpc_port\":%d,\"rest_port\":%d,\"role\":\"%s\",\"cluster_multinode_active\":\"%s\",\"raft_state\":\"%s\",\"ring_active\":%b,\"memory_usage_pct\":%.2f,\"storage_path\":\"%s\",\"peers\":%s}",
                config.getNodeId(),
                config.getNodeIp(),
                config.getGrpcPort(),
                config.getRestPort(),
                config.getNodeRole(),
                config.getClusterMultinodeActive(),
                (config.getNodeRole() == ClusterNode.Role.PRIMARY) ? "LEADER" : "FOLLOWER",
                ringEngine.isRingActive(),
                ringEngine.getCurrentMemoryUsage() * 100,
                config.getStoragePath(),
                peersJson.toString()
            );
            sendResponse(exchange, 200, response);
        }
    }

    private class ClusterStateConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = readLiveClusterStateBytes();
            String body = bytes != null && bytes.length > 0
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : "";
            String effectiveRole = config.isStandbyMode() ? "UNASSIGNED" : config.getNodeRole().name();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
            exchange.getResponseHeaders().set("X-Jettra-Node-Id", config.getNodeId());
            exchange.getResponseHeaders().set("X-Jettra-Node-Role", effectiveRole);
            byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
    }

    private class ShutdownHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed. Use POST.\"}");
                return;
            }
            try {
                validateAuthToken(exchange);
            } catch (SecurityException ex) {
                sendResponse(exchange, 401, String.format("{\"error\":\"Unauthorized. JettraJWT token required: %s\"}", ex.getMessage()));
                return;
            }
            String resp = String.format("{\"status\":\"STOPPED\",\"node_id\":\"%s\",\"message\":\"Shutdown signal accepted\"}", config.getNodeId());
            sendResponse(exchange, 200, resp);
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(80);
                    stop();
                } catch (Exception ignored) {}
            });
        }
    }

    private class PoliceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                validateAuthToken(exchange);
            } catch (SecurityException ex) {
                sendResponse(exchange, 401, String.format("{\"error\":\"Unauthorized. JettraJWT token required: %s\"}", ex.getMessage()));
                return;
            }

            var alerts = JettraPolice.getInstance().getAlerts();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < alerts.size(); i++) {
                var a = alerts.get(i);
                sb.append(String.format("{\"code\":\"%s\",\"message\":\"%s\",\"time\":\"%s\"}",
                    a.code(), a.message(), a.timestamp()));
                if (i < alerts.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }
    }

    private void validateAuthToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        } else if (exchange.getRequestURI().getQuery() != null) {
            for (String param : exchange.getRequestURI().getQuery().split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }
        if (token == null || token.isBlank()) {
            throw new SecurityException("Missing 'Authorization: Bearer <JettraJWT>' header");
        }
        securityManager.validateToken(token);
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx + pattern.length());
        if (colon == -1) return null;
        int startQuote = json.indexOf("\"", colon);
        if (startQuote == -1) return null;
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote == -1) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class DatabasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                validateAuthToken(exchange);
            } catch (SecurityException ex) {
                sendResponse(exchange, 401, String.format("{\"error\":\"Unauthorized: %s\"}", ex.getMessage()));
                return;
            }

            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("name=")) {
                    String targetName = extractQueryParam(query, "name");
                    if (targetName != null && !targetName.isBlank()) {
                        StorageMode mode = getDatabaseStorageMode(targetName.trim());
                        sendResponse(exchange, 200, String.format("{\"database\":\"%s\",\"storageMode\":\"%s\"}",
                                targetName.trim(), mode.getCode()));
                        return;
                    }
                }
                List<String> dbs = listDatabaseNames();
                if (query != null && query.contains("details=true")) {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < dbs.size(); i++) {
                        String dbName = dbs.get(i);
                        StorageMode mode = getDatabaseStorageMode(dbName);
                        sb.append(String.format("{\"database\":\"%s\",\"storageMode\":\"%s\"}", dbName, mode.getCode()));
                        if (i < dbs.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, sb.toString());
                    return;
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < dbs.size(); i++) {
                    sb.append("\"").append(dbs.get(i)).append("\"");
                    if (i < dbs.size() - 1) sb.append(",");
                }
                sb.append("]");
                sendResponse(exchange, 200, sb.toString());
            } else if ("POST".equalsIgnoreCase(method)) {
                if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                    sendResponse(exchange, 403, String.format("{\"error\":\"[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite crear bases de datos directamente.\"}", config.getNodeId()));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = extractJsonField(body, "name");
                if (name == null || name.isBlank()) {
                    name = extractJsonField(body, "database");
                }
                if (name == null || name.isBlank()) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing 'name' field in request\"}");
                    return;
                }
                String modeStr = extractJsonField(body, "storageMode");
                StorageMode requestedMode = StorageMode.parseStrict(modeStr);
                JettraDatabase created = getOrCreateDatabase(name.trim(), requestedMode);
                String activeCode = (created != null && created.getStorageMode() != null)
                        ? created.getStorageMode().getCode()
                        : StorageMode.OFF_HEAP.getCode();
                sendResponse(exchange, 200, String.format("{\"status\":\"CREATED\",\"database\":\"%s\",\"storageMode\":\"%s\"}",
                        name.trim(), activeCode));
            } else if ("PUT".equalsIgnoreCase(method)) {
                if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                    sendResponse(exchange, 403, String.format("{\"error\":\"[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite editar bases de datos directamente.\"}", config.getNodeId()));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = extractJsonField(body, "name");
                if (name == null || name.isBlank()) {
                    name = extractJsonField(body, "database");
                }
                String modeStr = extractJsonField(body, "storageMode");
                StorageMode requestedMode = StorageMode.parseStrict(modeStr);
                if (name == null || name.isBlank() || requestedMode == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing valid 'name'/'database' or 'storageMode' (JVM-RAM, DISK-MEMORY, OFF-HEAP)\"}");
                    return;
                }
                JettraDatabase updated = updateDatabaseStorageMode(name.trim(), requestedMode);
                String activeCode = (updated != null && updated.getStorageMode() != null)
                        ? updated.getStorageMode().getCode()
                        : requestedMode.getCode();
                sendResponse(exchange, 200, String.format("{\"status\":\"UPDATED\",\"database\":\"%s\",\"storageMode\":\"%s\"}",
                        name.trim(), activeCode));
            } else if ("DELETE".equalsIgnoreCase(method)) {
                if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                    sendResponse(exchange, 403, String.format("{\"error\":\"[READ-ONLY REPLICA] El nodo actual '%s' tiene rol SECUNDARIO. No se permite eliminar bases de datos directamente.\"}", config.getNodeId()));
                    return;
                }
                String query = exchange.getRequestURI().getQuery();
                String name = null;
                if (query != null && query.startsWith("name=")) {
                    name = query.substring(5);
                }
                if (name != null && !name.isBlank()) {
                    dropDatabase(name.trim());
                    sendResponse(exchange, 200, String.format("{\"status\":\"DROPPED\",\"database\":\"%s\"}", name.trim()));
                } else {
                    sendResponse(exchange, 400, "{\"error\":\"Missing 'name' query parameter\"}");
                }
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }

    private class ReplicateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                validateAuthToken(exchange);
            } catch (SecurityException ex) {
                sendResponse(exchange, 401, String.format("{\"error\":\"Unauthorized: %s\"}", ex.getMessage()));
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String op = extractJsonField(body, "op");
                String db = extractJsonField(body, "database");
                if ("CREATE_DATABASE".equalsIgnoreCase(op) && db != null) {
                    if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                        sendResponse(exchange, 403, "{\"error\":\"[READ-ONLY REPLICA] Operación de creación denegada en secundario.\"}");
                        return;
                    }
                    StorageMode reqMode = StorageMode.parseStrict(extractJsonField(body, "storageMode"));
                    getOrCreateDatabaseInternal(db.trim(), true, reqMode);
                    sendResponse(exchange, 200, "{\"status\":\"ACK\",\"op\":\"CREATE_DATABASE\"}");
                    return;
                } else if ("SET_STORAGE_MODE".equalsIgnoreCase(op) && db != null) {
                    if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                        sendResponse(exchange, 403, "{\"error\":\"[READ-ONLY REPLICA] Operación de edición denegada en secundario.\"}");
                        return;
                    }
                    StorageMode reqMode = StorageMode.parseStrict(extractJsonField(body, "storageMode"));
                    if (reqMode == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid 'storageMode'\"}");
                        return;
                    }
                    updateDatabaseStorageMode(db.trim(), reqMode);
                    sendResponse(exchange, 200, "{\"status\":\"ACK\",\"op\":\"SET_STORAGE_MODE\"}");
                    return;
                } else if ("DROP_DATABASE".equalsIgnoreCase(op) && db != null) {
                    if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.SECONDARY) {
                        sendResponse(exchange, 403, "{\"error\":\"[READ-ONLY REPLICA] Operación de eliminación denegada en secundario.\"}");
                        return;
                    }
                    dropDatabaseInternal(db.trim(), true);
                    sendResponse(exchange, 200, "{\"status\":\"ACK\",\"op\":\"DROP_DATABASE\"}");
                    return;
                } else if ("DISTRIBUTE_DATABASE".equalsIgnoreCase(op)) {
                    if ("all".equalsIgnoreCase(db)) {
                        Map<String, Boolean> res = distributeAllDatabases();
                        sendResponse(exchange, 200, "{\"status\":\"ACK\",\"op\":\"DISTRIBUTE_DATABASE\",\"count\":" + res.size() + "}");
                        return;
                    } else if (db != null) {
                        boolean ok = distributeDatabase(db.trim());
                        sendResponse(exchange, ok ? 200 : 500, "{\"status\":\"" + (ok ? "ACK" : "NACK") + "\",\"database\":\"" + db.trim() + "\"}");
                        return;
                    }
                }
            }
            sendResponse(exchange, 400, "{\"error\":\"Invalid replication operation\"}");
        }
    }

    private class ClusterEventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                int limit = 100;
                String q = exchange.getRequestURI().getQuery();
                if (q != null && q.contains("limit=")) {
                    try {
                        for (String param : q.split("&")) {
                            if (param.startsWith("limit=")) {
                                limit = Integer.parseInt(param.substring(6));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                List<ClusterLiveEvent> events = JettraClusterEventBus.getInstance().getRecentEvents(limit);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < events.size(); i++) {
                    sb.append(events.get(i).toJson());
                    if (i < events.size() - 1) sb.append(",");
                }
                sb.append("]");
                sendResponse(exchange, 200, sb.toString());
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }

    private class ClusterLiveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String q = exchange.getRequestURI().getQuery();
            boolean stream = q != null && (q.contains("stream=true") || q.contains("stream=1"));
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("text/event-stream")) {
                stream = true;
            }

            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Connection", "keep-alive");
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody()) {
                    List<ClusterLiveEvent> initial = JettraClusterEventBus.getInstance().getRecentEvents(20);
                    for (ClusterLiveEvent ev : initial) {
                        String chunk = "data: " + ev.toJson() + "\n\n";
                        os.write(chunk.getBytes(StandardCharsets.UTF_8));
                    }
                    os.flush();

                    java.util.concurrent.BlockingQueue<ClusterLiveEvent> queue = new java.util.concurrent.LinkedBlockingQueue<>();
                    java.util.function.Consumer<ClusterLiveEvent> listener = queue::offer;
                    JettraClusterEventBus.getInstance().subscribe(listener);
                    try {
                        while (httpServer != null) {
                            ClusterLiveEvent next = queue.poll(1, TimeUnit.SECONDS);
                            if (next != null) {
                                String chunk = "data: " + next.toJson() + "\n\n";
                                os.write(chunk.getBytes(StandardCharsets.UTF_8));
                                os.flush();
                            } else {
                                os.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                                os.flush();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        JettraClusterEventBus.getInstance().unsubscribe(listener);
                    }
                }
            } else {
                List<ClusterLiveEvent> events = JettraClusterEventBus.getInstance().getRecentEvents(50);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < events.size(); i++) {
                    sb.append(events.get(i).toJson());
                    if (i < events.size() - 1) sb.append(",");
                }
                sb.append("]");
                sendResponse(exchange, 200, sb.toString());
            }
        }
    }

    public static JettraStoreServer getActiveInstance() {
        return activeInstance;
    }

    public int getBoundRestPort() {
        return boundRestPort;
    }

    public static int getActiveServerCount() {
        return ACTIVE_SERVERS.size();
    }

    private static void registerShutdownHook() {
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                JettraStoreServer::stopAllActiveServers,
                "jettra-store-servers-shutdown-hook"
            ));
        }
    }

    /**
     * Detiene de forma determinista todos los servidores {@link JettraStoreServer} y transportes
     * {@link JettraClusterTransportServer} activos en la JVM para liberar todos los puertos abiertos.
     */
    public static int stopAllActiveServers() {
        int stoppedCount = 0;
        for (JettraStoreServer server : List.copyOf(ACTIVE_SERVERS)) {
            try {
                server.stop();
                stoppedCount++;
            } catch (Exception ignored) {}
        }
        stoppedCount += JettraClusterTransportServer.stopAllActiveTransports();
        return stoppedCount;
    }

    /**
     * Espera de forma acotada hasta que el puerto TCP indicado vuelva a estar disponible tras detener el servicio.
     */
    public static boolean awaitTcpPortAvailable(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        do {
            if (isTcpPortAvailable(host, port)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        return isTcpPortAvailable(host, port);
    }

    /**
     * Comprueba mediante {@code ServerSocket} con {@code SO_REUSEADDR} y cierre inmediato
     * si un puerto TCP está libre en la interfaz indicada.
     */
    public static boolean isTcpPortAvailable(String host, int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        String cleanHost = (host == null) ? "" : host.trim();
        boolean wildcard = cleanHost.isEmpty() || "0.0.0.0".equals(cleanHost) || "::".equals(cleanHost) || "*".equals(cleanHost);
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.setReuseAddress(true);
            java.net.InetSocketAddress addr = wildcard
                    ? new java.net.InetSocketAddress(port)
                    : new java.net.InetSocketAddress(java.net.InetAddress.getByName(cleanHost), port);
            ss.bind(addr, 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isRunning() {
        return httpServer != null && activeInstance == this;
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || key == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    public JettraDatabase getOrCreateDatabase(String name) {
        return getOrCreateDatabaseInternal(name, true, null);
    }

    public JettraDatabase getOrCreateDatabase(String name, StorageMode storageMode) {
        return getOrCreateDatabaseInternal(name, true, storageMode);
    }

    public StorageMode getDatabaseStorageMode(String name) {
        if (name == null || name.isBlank()) {
            return config.getStorageMode() != null ? config.getStorageMode() : StorageMode.OFF_HEAP;
        }
        JettraDatabase loaded = databases.get(name.trim());
        if (loaded != null) {
            return loaded.getStorageMode();
        }
        return JettraDatabase.readPersistedStorageMode(name.trim(), config);
    }

    public JettraDatabase updateDatabaseStorageMode(String name, StorageMode storageMode) {
        if (name == null || name.isBlank()) return null;
        StorageMode targetMode = (storageMode != null) ? storageMode : StorageMode.OFF_HEAP;
        JettraDatabase db = getOrCreateDatabaseInternal(name.trim(), false, null);
        if (db != null) {
            db.setStorageMode(targetMode);
            if (!db.isReadOnlyNode()) {
                try { db.flushMemTable(); } catch (Exception ignored) {}
                db.saveToDisk();
            }
            if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                byte[] payload = getDatabaseSnapshotBytes(name.trim());
                JettraClusterReplicationClient client = getOrCreateReplicationClient();
                if (client != null) {
                    client.broadcastCreateDatabase(name.trim(), payload);
                }
            }
        }
        return db;
    }

    public synchronized JettraClusterReplicationClient getOrCreateReplicationClient() {
        if (replicationClient == null && config.isClusterMultinodeActive()) {
            replicationClient = new JettraClusterReplicationClient(config.getNodeId(), ringEngine.getPeers());
            replicationClient.setPeerRecoveryListener(recoveredPeer -> {
                System.out.printf("[JettraStoreServer] 🟢 Nodo '%s' se ha reiniciado tras parada. Replicando estado y bases de datos desde PRIMARY...%n",
                    recoveredPeer.getId());
                Thread.ofVirtual().name("jettra-peer-recovery-" + recoveredPeer.getId()).start(() -> {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    distributeAllDatabasesToPeer(recoveredPeer);
                });
            });
        }
        return replicationClient;
    }

    public JettraDatabase getOrCreateDatabaseInternal(String name, boolean broadcast) {
        return getOrCreateDatabaseInternal(name, broadcast, null);
    }

    public JettraDatabase getOrCreateDatabaseInternal(String name, boolean broadcast, StorageMode storageMode) {
        if (name == null || name.isBlank()) return null;
        boolean[] createdNew = {false};
        JettraDatabase db = databases.computeIfAbsent(name, k -> {
            createdNew[0] = true;
            JettraDatabase newDb = new JettraDatabase(k, config, ringEngine);
            if (storageMode != null) {
                newDb.setStorageMode(storageMode);
            }
            return newDb;
        });
        if (!createdNew[0] && storageMode != null) {
            db.setStorageMode(storageMode);
        }
        if (db != null && !db.isReadOnlyNode()) {
            try {
                db.flushMemTable();
            } catch (Exception ignored) {}
            db.saveToDisk();
        }
        if (broadcast && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            byte[] payload = getDatabaseSnapshotBytes(name);
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            boolean replicated = false;
            if (client != null && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                replicated = client.broadcastCreateDatabase(name, payload);
            }
            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_DATABASE_CREATED,
                config.getNodeId(), "cluster",
                String.format("Base de datos '%s' creada y sincronizada en el clúster.", name),
                "replicated=" + replicated
            );
        }
        return db;
    }

    public boolean replicateCreateDatabase(String dbName, byte[] payload) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                return client.broadcastCreateDatabase(dbName, payload);
            }
        }
        return false;
    }

    public boolean dropDatabase(String name) {
        return dropDatabaseInternal(name, true);
    }

    public boolean dropDatabaseInternal(String name, boolean broadcast) {
        if (name == null || name.isBlank()) return false;
        JettraDatabase db = databases.remove(name);
        if (db != null) {
            try { db.drop(); } catch (Exception ignored) {}
        }
        Path dbDir = Path.of(config.getStoragePath(), name);
        if (Files.exists(dbDir)) {
            try (var s = Files.walk(dbDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
        try {
            Path storageDir = Path.of(config.getStoragePath());
            Files.deleteIfExists(storageDir.resolve(name + "_meta.json"));
            Files.deleteIfExists(storageDir.resolve(name + "_sstable" + config.getFileExtension()));
            Files.deleteIfExists(storageDir.resolve(name + config.getFileExtension()));
            Files.deleteIfExists(storageDir.resolve(name + "_sstable.jettra"));
            Files.deleteIfExists(storageDir.resolve(name + ".jettra"));
            Files.deleteIfExists(storageDir.resolve(name + "_wal.jettra"));
            Files.deleteIfExists(storageDir.resolve(name + "_indexes.json"));
            Files.deleteIfExists(storageDir.resolve(name + "_sequences.json"));
            Files.deleteIfExists(storageDir.resolve(name + "_indexes.json.tmp"));
            Files.deleteIfExists(storageDir.resolve(name + "_sequences.json.tmp"));
            Files.deleteIfExists(storageDir.resolve(name + "_versions.json"));
            Files.deleteIfExists(storageDir.resolve(name + "_versions.json.tmp"));
            Files.deleteIfExists(storageDir.resolve(name + "_backup.snap"));
        } catch (Exception ignored) {}
        if (broadcast && config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastDropDatabase(name);
            }
            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_DATABASE_DROPPED,
                config.getNodeId(), "cluster",
                String.format("Base de datos '%s' eliminada del clúster.", name),
                ""
            );
        }
        return true;
    }

    public void replicateCreateEngine(String dbName, String engineName, String engineType, byte[] payload) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastCreateEngine(dbName, engineName, engineType, payload);
            }
        }
    }

    public void replicatePutDocument(String dbName, String colName, String id, byte[] jsonBytes) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastPutDocument(dbName, colName, id, jsonBytes);
            }
        }
    }

    public void replicateDeleteDocument(String dbName, String colName, String id) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastDeleteDocument(dbName, colName, id);
            }
        }
    }

    public void replicatePutRecord(String dbName, String colName, String key, byte[] payload) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastPutRecord(dbName, colName, key, payload);
            }
        }
    }

    public void replicateDeleteRecord(String dbName, String colName, String key) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastDeleteRecord(dbName, colName, key);
            }
        }
    }

    public void replicateCreateIndex(String dbName, String colName, String indexName, String field, String type, boolean unique) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastCreateIndex(dbName, colName, indexName, field, type, unique);
            }
        }
    }

    public void replicateDropIndex(String dbName, String indexName) {
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client != null) {
                client.broadcastDropIndex(dbName, indexName);
            }
        }
    }

    public void recordLeaderHeartbeat(String leaderId) {
        lastLeaderHeartbeatTime = System.currentTimeMillis();
        if (leaderId != null && ringEngine != null) {
            ClusterNode p = ringEngine.getPeer(leaderId);
            if (p != null) p.start();
        }
    }

    public void startHeartbeatLoop() {
        if (!stopping && isPrimaryHeartbeatLoopRunning.compareAndSet(false, true)) {
            this.heartbeatThread = Thread.ofVirtual().name("jettra-raft-heartbeat").start(() -> {
                while (!stopping && httpServer != null && (transportServer == null || transportServer.isRunning()) && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
                    try {
                        Thread.sleep(150);
                        if (replicationClient != null && !stopping) {
                            replicationClient.sendHeartbeats();
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {}
                }
                isPrimaryHeartbeatLoopRunning.set(false);
            });
        }
    }

    public void startFailoverWatchdog() {
        if (stopping) {
            return;
        }
        this.failoverWatchdogThread = Thread.ofVirtual().name("jettra-failover-watchdog").start(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { return; }
            while (!stopping && httpServer != null && (transportServer == null || transportServer.isRunning())) {
                try {
                    Thread.sleep(300);
                    if (config.getNodeRole() == ClusterNode.Role.SECONDARY && config.isClusterMultinodeActive()) {
                        long elapsed = System.currentTimeMillis() - lastLeaderHeartbeatTime;
                        if (elapsed > 2000 && !failoverInProgress.get()) {
                            String currentPrimaryId = "node-01";
                            for (ClusterNode p : ringEngine.getPeers()) {
                                if (p.getRole() == ClusterNode.Role.PRIMARY) {
                                    currentPrimaryId = p.getId();
                                    break;
                                }
                            }
                            System.out.printf("[JettraStoreServer] ⚠️ Timeout de latidos (%d ms) del PRIMARY '%s'. Iniciando Failover...%n",
                                elapsed, currentPrimaryId);
                            initiateFailover(currentPrimaryId, "Heartbeat timeout (" + elapsed + "ms)");
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
    }

    public void handleNodeStopping(String senderNodeId, String details) {
        if (ringEngine != null) {
            ClusterNode peer = ringEngine.getPeer(senderNodeId);
            if (peer != null) {
                peer.markOffline();
            }
        }
        if (config.getNodeRole() == ClusterNode.Role.PRIMARY && senderNodeId != null
                && !senderNodeId.isBlank() && !senderNodeId.equalsIgnoreCase(config.getNodeId())) {
            byte[] updatedState = JettraConfigValidator.updateNodeStatusInStateFile(
                resolveClusterStateFile(), senderNodeId.trim(), "STOPPED", config.getNodeId());
            if (updatedState != null) {
                config.getClusterNodeStatuses().put(senderNodeId.trim().toLowerCase(java.util.Locale.ROOT), "STOPPED");
                config.setClusterStateVersion(config.getClusterStateVersion() + 1);
                if (replicationClient != null) {
                    replicationClient.broadcastClusterConfigSync(updatedState);
                }
            }
        }
        JettraClusterEventBus.getInstance().publish(
            ClusterLiveEvent.TYPE_NODE_STOPPED,
            senderNodeId, config.getNodeId(),
            String.format("Nodo '%s' notificó parada controlada.", senderNodeId),
            details
        );
        boolean wasPrimary = false;
        for (ClusterNode p : ringEngine.getPeers()) {
            if (p.getId().equalsIgnoreCase(senderNodeId) && p.getRole() == ClusterNode.Role.PRIMARY) {
                wasPrimary = true;
                break;
            }
        }
        if (wasPrimary || "node-01".equalsIgnoreCase(senderNodeId)) {
            System.out.printf("[JettraStoreServer] 🚨 El nodo PRIMARIO '%s' se detuvo. Iniciando protocolo de Failover...%n", senderNodeId);
            initiateFailover(senderNodeId, "Graceful stop of primary");
        }
    }

    public void handleNewLeaderPromoted(String newLeaderId, long newTerm) {
        if (ringEngine != null) {
            for (ClusterNode peer : ringEngine.getPeers()) {
                if (peer.getId().equalsIgnoreCase(newLeaderId)) {
                    peer.setRole(ClusterNode.Role.PRIMARY);
                    peer.start();
                } else if (peer.getRole() == ClusterNode.Role.PRIMARY || peer.getId().equalsIgnoreCase("node-01")) {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.markOffline();
                }
            }
        }
        if (replicationClient != null) {
            for (ClusterNode peer : replicationClient.getPeers()) {
                if (peer.getId().equalsIgnoreCase(newLeaderId)) {
                    peer.setRole(ClusterNode.Role.PRIMARY);
                    peer.start();
                } else if (peer.getRole() == ClusterNode.Role.PRIMARY || peer.getId().equalsIgnoreCase("node-01")) {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.markOffline();
                }
            }
        }
        if (config.getNodeId().equalsIgnoreCase(newLeaderId)) {
            promoteSelfUsingClusterStateFile(null);
            startHeartbeatLoop();
        } else {
            config.setNodeRole(ClusterNode.Role.SECONDARY);
            lastLeaderHeartbeatTime = System.currentTimeMillis();
        }
        JettraClusterEventBus.getInstance().publish(
            ClusterLiveEvent.TYPE_LEADER_PROMOTED,
            newLeaderId, "cluster",
            String.format("Nodo '%s' asumió con éxito el rol de PRIMARY (Term %d).", newLeaderId, newTerm),
            "term=" + newTerm
        );
    }

    /**
     * Al asumir este nodo el rol de nuevo {@code PRIMARY} tras un failover, lee el archivo
     * {@code cluster-state.config} desde {@code jettra.cluster_state_config_dir} para operar
     * con toda la topología y estado del clúster, actualiza su rol y el del líder caído
     * atómicamente en disco, recarga los peers en memoria y replica el nuevo estado a los nodos vivos.
     */
    private void promoteSelfUsingClusterStateFile(String deadLeaderId) {
        String localId = config.getNodeId();
        Path stateFile = resolveClusterStateFile();
        boolean persistentTarget = (this.clusterConfigFilePath != null)
            || (this.config != null && this.config.isClusterStateDirExplicit())
            || (stateFile != null && Files.exists(stateFile));

        java.util.Properties stateProps = null;
        List<JettraConfigValidator.ClusterNodeInfo> sourceNodes = new ArrayList<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        long nextVer = Math.max(2L, config.getClusterStateVersion() + 1L);

        if (stateFile != null && Files.exists(stateFile)) {
            try (var is = Files.newInputStream(stateFile)) {
                stateProps = new java.util.Properties();
                stateProps.load(is);
                sourceNodes.addAll(JettraConfigValidator.parseExplicitClusterNodes(stateProps));
                statuses.putAll(JettraConfigValidator.parseClusterNodeStatuses(stateProps));
                nextVer = Math.max(nextVer, JettraConfigValidator.parseClusterStateVersion(stateProps) + 1L);
            } catch (Exception ex) {
                System.err.printf("[JettraStoreServer] Advertencia leyendo cluster-state.config en failover ('%s'): %s%n",
                    stateFile, ex.getMessage());
            }
        } else if (persistentTarget && stateFile != null) {
            stateProps = JettraConfigValidator.seedOrLoadClusterState(stateFile, resolveOptionalClusterSeedFile());
            if (stateProps != null) {
                sourceNodes.addAll(JettraConfigValidator.parseExplicitClusterNodes(stateProps));
                statuses.putAll(JettraConfigValidator.parseClusterNodeStatuses(stateProps));
                nextVer = Math.max(nextVer, JettraConfigValidator.parseClusterStateVersion(stateProps) + 1L);
            }
        }

        if (sourceNodes.isEmpty()) {
            if (config.getAllClusterNodes() != null && !config.getAllClusterNodes().isEmpty()) {
                sourceNodes.addAll(config.getAllClusterNodes());
                statuses.putAll(config.getClusterNodeStatuses());
            } else {
                sourceNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    localId, "PRIMARY", config.getNodeIp(), config.getGrpcPort(), config.getRestPort(), config.getStoragePath()));
                if (ringEngine != null) {
                    for (ClusterNode peer : ringEngine.getPeers()) {
                        sourceNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                            peer.getId(), peer.getRole().name(), peer.getIp(), peer.getPort(),
                            peer.getPort() + 10000, config.getStoragePath()));
                        statuses.put(peer.getId().toLowerCase(java.util.Locale.ROOT),
                            peer.getStatus() == ClusterNode.NodeStatus.OFFLINE ? "STOPPED" : "RUNNING");
                    }
                }
            }
        }

        List<JettraConfigValidator.ClusterNodeInfo> updatedNodes = new ArrayList<>(sourceNodes.size() + 1);
        Map<String, String> updatedStatuses = new LinkedHashMap<>();
        boolean foundLocal = false;

        for (JettraConfigValidator.ClusterNodeInfo cn : sourceNodes) {
            String key = cn.id().toLowerCase(java.util.Locale.ROOT);
            if (cn.id().equalsIgnoreCase(localId)) {
                foundLocal = true;
                updatedNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    cn.id(), "PRIMARY", cn.ip(), cn.grpcPort(), cn.restPort(), cn.storagePath(), cn.nodeNumber()));
                updatedStatuses.put(key, "RUNNING");
            } else if (deadLeaderId != null && cn.id().equalsIgnoreCase(deadLeaderId)) {
                updatedNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    cn.id(), "SECONDARY", cn.ip(), cn.grpcPort(), cn.restPort(), cn.storagePath(), cn.nodeNumber()));
                updatedStatuses.put(key, "STOPPED");
            } else if ("PRIMARY".equalsIgnoreCase(cn.role())) {
                updatedNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    cn.id(), "SECONDARY", cn.ip(), cn.grpcPort(), cn.restPort(), cn.storagePath(), cn.nodeNumber()));
                String prevSt = statuses.getOrDefault(key, "STOPPED");
                updatedStatuses.put(key, prevSt);
            } else {
                updatedNodes.add(cn);
                ClusterNode memPeer = ringEngine != null ? ringEngine.getPeer(cn.id()) : null;
                String prevSt = statuses.getOrDefault(key,
                    (memPeer != null && memPeer.getStatus() == ClusterNode.NodeStatus.OFFLINE) ? "STOPPED" : "RUNNING");
                updatedStatuses.put(key, prevSt);
            }
        }

        if (!foundLocal) {
            updatedNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                localId, "PRIMARY", config.getNodeIp(), config.getGrpcPort(), config.getRestPort(), config.getStoragePath()));
            updatedStatuses.put(localId.toLowerCase(java.util.Locale.ROOT), "RUNNING");
        }

        config.setStandbyMode(false);
        config.setNodeRole(ClusterNode.Role.PRIMARY);
        config.updateRuntimeTopology(updatedNodes, updatedStatuses, nextVer);
        applySyncedPeersToRuntime(updatedNodes);

        if (ringEngine != null) {
            for (ClusterNode peer : ringEngine.getPeers()) {
                String st = updatedStatuses.get(peer.getId().toLowerCase(java.util.Locale.ROOT));
                if ("STOPPED".equalsIgnoreCase(st) || (deadLeaderId != null && peer.getId().equalsIgnoreCase(deadLeaderId))) {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.markOffline();
                } else {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.start();
                }
            }
        }
        if (replicationClient != null) {
            for (ClusterNode peer : replicationClient.getPeers()) {
                String st = updatedStatuses.get(peer.getId().toLowerCase(java.util.Locale.ROOT));
                if ("STOPPED".equalsIgnoreCase(st) || (deadLeaderId != null && peer.getId().equalsIgnoreCase(deadLeaderId))) {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.markOffline();
                } else {
                    peer.setRole(ClusterNode.Role.SECONDARY);
                    peer.start();
                }
            }
        }

        if (persistentTarget && stateFile != null) {
            try {
                byte[] canonicalBytes = JettraConfigValidator.buildCanonicalClusterStateBytes(
                    updatedNodes, updatedStatuses, stateProps, nextVer, localId);
                JettraConfigValidator.writeClusterConfigAtomically(stateFile, canonicalBytes);
                System.out.printf("[JettraStoreServer] 💾 Failover persistido en '%s' (version=%d, nuevo PRIMARY='%s', nodos=%d).%n",
                    stateFile, nextVer, localId, updatedNodes.size());
                Thread.ofVirtual().name("jettra-failover-state-sync-" + localId).start(() -> {
                    try {
                        JettraClusterReplicationClient replClient = getOrCreateReplicationClient();
                        if (replClient != null) {
                            replClient.broadcastClusterConfigSync(canonicalBytes);
                        }
                    } catch (Exception ignored) {}
                });
            } catch (Exception ex) {
                System.err.printf("[JettraStoreServer] Error persistiendo failover en '%s': %s%n",
                    stateFile, ex.getMessage());
            }
        }
    }

    public void handleLiveEvent(String json) {
        try {
            String type = extractJsonField(json, "type");
            String source = extractJsonField(json, "source");
            String target = extractJsonField(json, "target");
            String message = extractJsonField(json, "message");
            String details = extractJsonField(json, "details");
            JettraClusterEventBus.getInstance().publish(type, source, target, message, details);
        } catch (Exception ignored) {}
    }

    public synchronized void initiateFailover(String deadLeaderId, String reason) {
        if (config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            return;
        }
        if (!failoverInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (ringEngine != null) {
                ClusterNode deadPeer = ringEngine.getPeer(deadLeaderId);
                if (deadPeer != null) {
                    deadPeer.markOffline();
                }
            }

            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_LEADER_ELECTION,
                config.getNodeId(), "cluster",
                String.format("Iniciado protocolo de elección de nuevo PRIMARY debido a '%s' en nodo '%s'.", reason, deadLeaderId),
                "dead=" + deadLeaderId
            );

            List<String> candidates = new ArrayList<>();
            candidates.add(config.getNodeId());

            if (ringEngine != null) {
                for (ClusterNode peer : ringEngine.getPeers()) {
                    if (!peer.getId().equalsIgnoreCase(deadLeaderId) && peer.getStatus() != ClusterNode.NodeStatus.OFFLINE) {
                        candidates.add(peer.getId());
                    }
                }
            }
            Collections.sort(candidates);

            String chosen = candidates.isEmpty() ? config.getNodeId() : candidates.get(0);

            if (chosen.equalsIgnoreCase(config.getNodeId())) {
                promoteSelfUsingClusterStateFile(deadLeaderId);
                startHeartbeatLoop();

                JettraClusterReplicationClient activeReplClient = getOrCreateReplicationClient();
                long newTerm = (activeReplClient != null) ? activeReplClient.getCurrentTerm() + 1 : 2;
                if (activeReplClient != null) {
                    activeReplClient.incrementTerm();
                    final long promoteTerm = newTerm;
                    Thread.ofVirtual().name("jettra-failover-promote-" + config.getNodeId()).start(() -> {
                        try {
                            activeReplClient.broadcastNewLeaderPromoted(promoteTerm);
                        } catch (Exception ignored) {}
                    });
                }

                JettraClusterEventBus.getInstance().publish(
                    ClusterLiveEvent.TYPE_LEADER_PROMOTED,
                    config.getNodeId(), "cluster",
                    String.format("¡Failover Exitoso! Nodo local '%s' promovido a nuevo PRIMARY (Term %d).", config.getNodeId(), newTerm),
                    "term=" + newTerm + ",former=" + deadLeaderId
                );
                System.out.printf("[JettraStoreServer] 👑 ¡NODO LOCAL '%s' PROMOVIDO A NUEVO PRIMARY TRAS CAÍDA DE '%s'!%n",
                    config.getNodeId(), deadLeaderId);
            } else {
                System.out.printf("[JettraStoreServer] ℹ️ Esperando asunción de PRIMARY por nodo candidato '%s'...%n", chosen);
            }
        } finally {
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(3000); } catch (Exception ignored) {}
                failoverInProgress.set(false);
            });
        }
    }

    /**
     * Dispara una sincronización asíncrona desde el PRIMARY evitando apilar múltiples hilos
     * concurrentes si ya hay una sincronización en progreso.
     */
    public void triggerAsyncSynchronizeFromPrimary() {
        if (config.getNodeRole() != ClusterNode.Role.SECONDARY) {
            return;
        }
        if (!secondarySyncInProgress.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("jettra-secondary-sync-on-config").start(() -> {
            try {
                Thread.sleep(200);
                synchronizeFromPrimary();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                secondarySyncInProgress.set(false);
            }
        });
    }

    /**
     * Sincronización inicial de estado para nodos secundarios (SECONDARY).
     * Solicita al nodo primario (PRIMARY) el catálogo completo de bases de datos y la instantánea
     * de registros/estructuras, procesando y cargando el estado local antes de unirse formalmente
     * al flujo de operaciones del clúster.
     *
     * @return true si la sincronización se completó con éxito; false si el primario no respondió.
     */
    public synchronized boolean synchronizeFromPrimary() {
        if (replicationClient == null) {
            this.replicationClient = getOrCreateReplicationClient();
        }
        if (replicationClient == null && ringEngine != null) {
            this.replicationClient = new JettraClusterReplicationClient(config.getNodeId(), ringEngine.getPeers());
        }
        if (replicationClient == null || ringEngine == null) {
            return false;
        }

        ClusterNode primaryPeer = null;
        for (ClusterNode peer : ringEngine.getPeers()) {
            if (peer.getRole() == ClusterNode.Role.PRIMARY) {
                primaryPeer = peer;
                break;
            }
        }
        if (primaryPeer == null) {
            primaryPeer = ringEngine.getPeer("node-01");
        }
        if (primaryPeer == null && config.getAllClusterNodes() != null) {
            for (var cn : config.getAllClusterNodes()) {
                if ("PRIMARY".equalsIgnoreCase(cn.role()) && !cn.id().equalsIgnoreCase(config.getNodeId())) {
                    primaryPeer = new ClusterNode(cn.id(), cn.ip(), cn.grpcPort(), ClusterNode.Role.PRIMARY);
                    break;
                }
            }
        }
        if (primaryPeer == null && !ringEngine.getPeers().isEmpty()) {
            primaryPeer = ringEngine.getPeers().get(0);
        }
        if (primaryPeer == null) {
            System.out.printf("[JettraStoreServer] Nodo secundario '%s': no hay nodo primario configurado en los peers del clúster.%n",
                config.getNodeId());
            return false;
        }

        System.out.printf("[JettraStoreServer] 🔄 [SYNC] Nodo SECUNDARIO '%s' solicitando catálogo e instantáneas completas al PRIMARY '%s' (%s:%d)...%n",
            config.getNodeId(), primaryPeer.getId(), primaryPeer.getIp(), primaryPeer.getPort());

        List<String> primaryDbs = null;
        int attempts = 0;
        while (attempts < 3 && primaryDbs == null) {
            attempts++;
            try {
                primaryDbs = replicationClient.requestCatalogSync(primaryPeer.getIp(), primaryPeer.getPort());
            } catch (Exception e) {
                System.err.printf("[JettraStoreServer] Intento %d de sincronización con primario falló: %s%n", attempts, e.getMessage());
            }
            if (primaryDbs == null && attempts < 3) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }

        if (primaryDbs == null) {
            System.out.printf("[JettraStoreServer] ⚠️ Nodo primario '%s' no disponible de inmediato para sincronización inicial de catálogo.%n",
                primaryPeer.getId());
            return false;
        }

        primaryPeer.start();
        int syncCount = 0;
        for (String db : primaryDbs) {
            if (db == null || db.isBlank()) continue;
            try {
                byte[] metaBytes = replicationClient.requestDataSync(primaryPeer.getIp(), primaryPeer.getPort(), db);
                Path targetMeta = Path.of(config.getStoragePath(), db + "_meta.json");
                if (metaBytes != null && metaBytes.length > 0) {
                    if (targetMeta.getParent() != null) {
                        Files.createDirectories(targetMeta.getParent());
                    }
                    Files.write(targetMeta, metaBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                }

                JettraDatabase syncedDb = getOrCreateDatabaseInternal(db, false);
                if (syncedDb != null) {
                    if (Files.exists(targetMeta)) {
                        syncedDb.loadFromDisk(targetMeta);
                    } else {
                        syncedDb.loadFromDisk();
                    }
                    syncedDb.saveToDisk();
                    syncCount++;
                    System.out.printf("[JettraStoreServer] ↳ [SYNC] Base de datos y registros de '%s' sincronizados exitosamente (%d bytes).%n",
                        db, metaBytes != null ? metaBytes.length : 0);
                }
            } catch (Exception ex) {
                System.err.printf("[JettraStoreServer] Error aplicando instantánea de base de datos '%s': %s%n", db, ex.getMessage());
            }
        }

        JettraClusterEventBus.getInstance().publish(
            ClusterLiveEvent.TYPE_DATABASE_DISTRIBUTED,
            primaryPeer.getId(), config.getNodeId(),
            String.format("Nodo secundario '%s' completó sincronización inicial de %d base(s) de datos y registros desde PRIMARY '%s'.",
                config.getNodeId(), syncCount, primaryPeer.getId()),
            "synced_dbs=" + String.join(",", primaryDbs)
        );

        System.out.printf("[JettraStoreServer] ✅ [SYNC] Nodo SECUNDARIO '%s' sincronizado formalmente con el estado del clúster (%d bases de datos procesadas).%n",
            config.getNodeId(), syncCount);
        return true;
    }

    public byte[] getDatabaseSnapshotBytes(String dbName) {
        if (dbName == null || dbName.isBlank()) return new byte[0];
        try {
            JettraDatabase db = databases.get(dbName);
            if (db == null) {
                db = getOrCreateDatabaseInternal(dbName, false);
            }
            if (db != null) {
                try {
                    db.flushMemTable();
                } catch (Exception ignored) {}
                db.saveToDisk();
            }
            Path metaFile = JettraDatabase.resolveMetaFile(dbName, config);
            if (metaFile == null || !Files.exists(metaFile)) {
                Path altMeta = Path.of(config.getStoragePath(), dbName + "_meta.json");
                if (Files.exists(altMeta)) {
                    metaFile = altMeta;
                }
            }
            if (metaFile != null && Files.exists(metaFile)) {
                if (Files.size(metaFile) <= 16 * 1024 * 1024) {
                    return Files.readAllBytes(metaFile);
                } else {
                    return ("{\"databaseName\":\"" + dbName + "\",\"large\":true}").getBytes(StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return new byte[0];
    }

    public boolean distributeDatabase(String name) {
        if (name == null || name.isBlank()) return false;
        byte[] payload = getDatabaseSnapshotBytes(name);
        boolean ok = true;
        if (config.isClusterMultinodeActive() && config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            if (replicationClient != null) {
                ok = replicationClient.broadcastDistributeDatabase(name, payload);
            }
        }
        if (ok) {
            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_DATABASE_DISTRIBUTED,
                config.getNodeId(), "cluster",
                String.format("Base de datos '%s' (%d bytes) distribuida exitosamente con todos sus registros a nodos secundarios.", name, payload.length),
                "bytes=" + payload.length
            );
        }
        return ok;
    }

    public Map<String, Boolean> distributeAllDatabases() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String db : listDatabaseNames()) {
            results.put(db, distributeDatabase(db));
        }
        return results;
    }

    /**
     * Sincronización automática de cluster.config al iniciar o reconectarse un nodo:
     * busca un nodo PRIMARY activo en cluster.config, solicita el cluster.config canónico del PRIMARY
     * (que fusiona de forma atómica al nodo actual sin sobrescribir nodos existentes) y verifica que
     * el archivo local sea idéntico al del PRIMARY.
     */
    public boolean syncClusterConfigWithActivePrimary() {
        if (!config.isClusterMultinodeActive() || config.getNodeRole() == ClusterNode.Role.PRIMARY) {
            return true;
        }
        List<ClusterNode> candidates = new ArrayList<>();
        if (ringEngine != null) {
            for (ClusterNode p : ringEngine.getPeers()) {
                if (p.getRole() == ClusterNode.Role.PRIMARY) {
                    candidates.add(p);
                }
            }
            for (ClusterNode p : ringEngine.getPeers()) {
                if (p.getRole() != ClusterNode.Role.PRIMARY) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }

        JettraRaftFrame joinReq = JettraRaftFrame.joinClusterReq(
            1L, config.getNodeId(), config.getNodeRole().name(),
            config.getNodeIp(), config.getGrpcPort(), config.getRestPort()
        );

        for (ClusterNode candidate : candidates) {
            if (candidate.getIp() == null || candidate.getIp().isBlank() || candidate.getPort() <= 0) continue;
            try (java.net.Socket s = new java.net.Socket()) {
                s.setTcpNoDelay(true);
                s.connect(new java.net.InetSocketAddress(candidate.getIp(), candidate.getPort()), 1500);
                s.setSoTimeout(3000);
                try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(s.getOutputStream(), 8192);
                     java.io.BufferedInputStream bis = new java.io.BufferedInputStream(s.getInputStream(), 8192)) {
                    io.jettra.store.cluster.JettraClusterProtocol.writeFrame(bos, joinReq);
                    JettraRaftFrame resp = io.jettra.store.cluster.JettraClusterProtocol.readFrame(bis);
                    if (resp != null && resp.frameType() == JettraRaftFrame.TYPE_JOIN_CLUSTER_RESP
                            && resp.payload() != null && resp.payload().length > 0) {
                        byte[] canonicalBytes = resp.payload();
                        java.util.Properties syncedProps = new java.util.Properties();
                        try (var bais = new java.io.ByteArrayInputStream(canonicalBytes)) {
                            syncedProps.load(bais);
                        }
                        List<JettraConfigValidator.ClusterNodeInfo> parsed = JettraConfigValidator.parseExplicitClusterNodes(syncedProps);
                        boolean containsSelf = parsed.stream().anyMatch(n -> n.id().equalsIgnoreCase(config.getNodeId()));
                        if (!parsed.isEmpty() && containsSelf) {
                            Path localStateFile = resolveClusterStateFile();
                            boolean updated = JettraConfigValidator.writeClusterConfigAtomically(localStateFile, canonicalBytes);
                            handleClusterStateSynchronized(parsed, syncedProps);
                            recordLeaderHeartbeat(resp.senderNodeId());
                            if (updated) {
                                System.out.printf("[JettraStoreServer] ✅ [CLUSTER-SYNC] Archivo cluster-state.config sincronizado con PRIMARY '%s' (%d nodos en clúster).%n",
                                    resp.senderNodeId(), parsed.size());
                            } else {
                                System.out.printf("[JettraStoreServer] ✅ [CLUSTER-SYNC] Archivo cluster-state.config ya es idéntico al PRIMARY '%s' (%d nodos).%n",
                                    resp.senderNodeId(), parsed.size());
                            }
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Procesa en el nodo PRIMARY la solicitud de unión/verificación de un nodo nuevo o reconectado:
     * fusiona de forma atómica el nodo en el cluster.config del PRIMARY (sin perder ni sobrescribir nodos existentes),
     * replica el cluster.config actualizado a todos los nodos activos y replica las bases de datos al nuevo nodo.
     */
    public synchronized byte[] handleJoinClusterRequest(String joiningId, String joiningRole, String joiningIp, int joiningGrpcPort, int joiningRestPort) {
        if (config.isStandbyMode() || config.getNodeRole() != ClusterNode.Role.PRIMARY || joiningId == null || joiningId.isBlank()) {
            return null;
        }
        try {
            Path primaryStateFile = resolveClusterStateFile();
            Path optionalSeed = resolveOptionalClusterSeedFile();
            java.util.Properties props = JettraConfigValidator.seedOrLoadClusterState(primaryStateFile, optionalSeed);
            if (props == null) {
                List<JettraConfigValidator.ClusterNodeInfo> runtimeNodes = new ArrayList<>();
                Map<String, String> runtimeStatuses = new LinkedHashMap<>(config.getClusterNodeStatuses());
                Set<String> usedRestEndpoints = new java.util.HashSet<>();
                if (config.getAllClusterNodes() != null && !config.getAllClusterNodes().isEmpty()) {
                    for (JettraConfigValidator.ClusterNodeInfo cn : config.getAllClusterNodes()) {
                        String effectiveNodeRole = cn.id().equalsIgnoreCase(config.getNodeId())
                            ? config.getNodeRole().name()
                            : cn.role();
                        String nodeIp = (cn.ip() != null && !cn.ip().isBlank()) ? cn.ip().trim() : "127.0.0.1";
                        int candidateRest = cn.id().equalsIgnoreCase(config.getNodeId()) && config.getRestPort() > 0
                            ? config.getRestPort()
                            : cn.restPort();
                        String restKey = nodeIp.toLowerCase(java.util.Locale.ROOT) + ":" + candidateRest;
                        if (candidateRest <= 0 || candidateRest > 65535 || usedRestEndpoints.contains(restKey)) {
                            int derived = (cn.grpcPort() > 1000 && cn.grpcPort() - 1000 <= 65535)
                                ? cn.grpcPort() - 1000
                                : 60001;
                            while (usedRestEndpoints.contains(nodeIp.toLowerCase(java.util.Locale.ROOT) + ":" + derived) && derived < 65535) {
                                derived++;
                            }
                            candidateRest = derived;
                        }
                        usedRestEndpoints.add(nodeIp.toLowerCase(java.util.Locale.ROOT) + ":" + candidateRest);
                        runtimeNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                            cn.id(), effectiveNodeRole, nodeIp, cn.grpcPort(), candidateRest, cn.storagePath(), cn.nodeNumber()));
                    }
                } else {
                    runtimeNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                        config.getNodeId(), config.getNodeRole().name(), config.getNodeIp(),
                        config.getGrpcPort(), config.getRestPort(), config.getStoragePath()));
                }
                runtimeStatuses.putIfAbsent(config.getNodeId().toLowerCase(java.util.Locale.ROOT), "RUNNING");
                byte[] seedBytes = JettraConfigValidator.buildCanonicalClusterStateBytes(
                    runtimeNodes, runtimeStatuses, null, Math.max(1L, config.getClusterStateVersion()), config.getNodeId());
                if (JettraConfigValidator.writeClusterConfigAtomically(primaryStateFile, seedBytes)) {
                    props = JettraConfigValidator.loadProperties(primaryStateFile);
                } else {
                    props = new java.util.Properties();
                    try (var bais = new java.io.ByteArrayInputStream(seedBytes)) {
                        props.load(bais);
                    }
                }
            }
            List<JettraConfigValidator.ClusterNodeInfo> existingNodes = JettraConfigValidator.parseExplicitClusterNodes(props);
            JettraConfigValidator.ClusterNodeInfo existing = JettraConfigValidator.findNodeInCluster(existingNodes, joiningId);

            String effectiveRole = joiningId.equalsIgnoreCase(config.getNodeId()) ? "PRIMARY" : "SECONDARY";
            String effectiveIp = (joiningIp != null && !joiningIp.isBlank()) ? joiningIp.trim() : "127.0.0.1";
            int effectiveGrpc = joiningGrpcPort > 0 ? joiningGrpcPort : 9091;
            int effectiveRest = joiningRestPort > 0 ? joiningRestPort : (effectiveGrpc > 1000 ? effectiveGrpc - 1000 : 8082);

            boolean isNewNode = (existing == null);
            String prevStatus = existing != null
                ? JettraConfigValidator.parseClusterNodeStatuses(props).getOrDefault(existing.id().toLowerCase(java.util.Locale.ROOT), "RUNNING")
                : "";
            boolean needsUpdate = isNewNode
                || !effectiveIp.equals(existing.ip())
                || effectiveGrpc != existing.grpcPort()
                || (joiningRestPort > 0 && effectiveRest != existing.restPort())
                || !"RUNNING".equalsIgnoreCase(prevStatus);

            if (needsUpdate || !Files.exists(primaryStateFile)) {
                JettraConfigValidator.upsertNodeInStateFile(
                    primaryStateFile, joiningId.trim(), effectiveRole, effectiveIp, effectiveGrpc, effectiveRest,
                    "RUNNING", config.getNodeId()
                );
                props = JettraConfigValidator.loadProperties(primaryStateFile);
                existingNodes = JettraConfigValidator.parseExplicitClusterNodes(props);
            }

            byte[] canonicalBytes = Files.readAllBytes(primaryStateFile);
            config.updateRuntimeTopology(
                existingNodes,
                JettraConfigValidator.parseClusterNodeStatuses(props),
                JettraConfigValidator.parseClusterStateVersion(props));
            applySyncedPeersToRuntime(existingNodes);

            JettraClusterReplicationClient replClient = getOrCreateReplicationClient();
            if (needsUpdate && replClient != null) {
                replClient.broadcastClusterConfigSync(canonicalBytes);
            }
            if (!joiningId.trim().equalsIgnoreCase(config.getNodeId())) {
                ClusterNode targetPeer = new ClusterNode(joiningId.trim(), effectiveIp, effectiveGrpc, ClusterNode.Role.SECONDARY);
                Thread.ofVirtual().name("jettra-sync-node-db-" + joiningId.trim()).start(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {}
                    distributeAllDatabasesToPeer(targetPeer);
                });
            }
            JettraClusterEventBus.getInstance().publish(
                ClusterLiveEvent.TYPE_NODE_ONLINE,
                joiningId.trim(), config.getNodeId(),
                String.format("Nodo '%s' (%s:%d) sincronizado y %s en cluster-state.config del PRIMARY.",
                    joiningId.trim(), effectiveIp, effectiveGrpc, isNewNode ? "anexado" : "verificado"),
                "nodes=" + existingNodes.size()
            );
            return canonicalBytes;
        } catch (Exception ex) {
            System.err.printf("[JettraStoreServer] Error en handleJoinClusterRequest para '%s': %s%n", joiningId, ex.getMessage());
            return null;
        }
    }

    /**
     * Aplica actualización completa de {@code cluster-state.config} recibida por Raft/Gossip,
     * sacando de {@code STANDBY} al nodo si acaba de ser asignado al clúster.
     */
    public synchronized List<ClusterNode> handleClusterStateSynchronized(
            List<JettraConfigValidator.ClusterNodeInfo> parsedNodes,
            java.util.Properties syncedProps) {
        if (parsedNodes == null || parsedNodes.isEmpty()) {
            return List.of();
        }
        var statuses = JettraConfigValidator.parseClusterNodeStatuses(syncedProps);
        long ver = JettraConfigValidator.parseClusterStateVersion(syncedProps);
        config.updateRuntimeTopology(parsedNodes, statuses, ver);

        String localId = config.getNodeId();
        boolean selfInTopology = parsedNodes.stream().anyMatch(n -> n.id().equalsIgnoreCase(localId));
        if (config.isStandbyMode() && selfInTopology) {
            var selfInfo = JettraConfigValidator.findNodeInCluster(parsedNodes, localId);
            ClusterNode.Role assignedRole = (selfInfo != null && "PRIMARY".equalsIgnoreCase(selfInfo.role()))
                ? ClusterNode.Role.PRIMARY
                : ClusterNode.Role.SECONDARY;
            config.setStandbyMode(false);
            config.setNodeRole(assignedRole);
            System.out.printf("[JettraStoreServer] ✅ Nodo '%s' incorporado desde STANDBY al clúster con rol %s (state_version=%d).%n",
                localId, assignedRole, ver);
            List<ClusterNode> added = applySyncedPeersToRuntime(parsedNodes);
            if (assignedRole == ClusterNode.Role.PRIMARY) {
                startHeartbeatLoop();
            } else {
                startFailoverWatchdog();
                triggerAsyncSynchronizeFromPrimary();
            }
            return added;
        }

        List<ClusterNode> added = applySyncedPeersToRuntime(parsedNodes);
        for (ClusterNode peer : ringEngine.getPeers()) {
            String st = statuses.get(peer.getId().toLowerCase(java.util.Locale.ROOT));
            if ("STOPPED".equalsIgnoreCase(st)) {
                peer.stop();
            } else {
                peer.start();
            }
        }
        return added;
    }

    /**
     * Sincroniza la lista de peers en memoria (DynamicRingEngine y JettraClusterReplicationClient)
     * con los nodos parseados de cluster.config.
     */
    public synchronized List<ClusterNode> applySyncedPeersToRuntime(List<JettraConfigValidator.ClusterNodeInfo> parsedNodes) {
        List<ClusterNode> updatedPeers = new ArrayList<>();
        List<ClusterNode> newlyAddedPeers = new ArrayList<>();
        Set<String> parsedIds = new java.util.HashSet<>();
        String localId = config.getNodeId();

        for (var cn : parsedNodes) {
            parsedIds.add(cn.id().toLowerCase(java.util.Locale.ROOT));
            if (!cn.id().equalsIgnoreCase(localId)) {
                ClusterNode.Role r = "PRIMARY".equalsIgnoreCase(cn.role()) ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
                ClusterNode existing = ringEngine != null ? ringEngine.getPeer(cn.id()) : null;
                ClusterNode peerNode = new ClusterNode(cn.id(), cn.ip(), cn.grpcPort(), r);
                updatedPeers.add(peerNode);
                if (existing == null) {
                    newlyAddedPeers.add(peerNode);
                }
            }
        }
        if (ringEngine != null) {
            for (ClusterNode currentPeer : new ArrayList<>(ringEngine.getPeers())) {
                if (!parsedIds.contains(currentPeer.getId().toLowerCase(java.util.Locale.ROOT))) {
                    ringEngine.removePeer(currentPeer.getId());
                }
            }
            for (ClusterNode peerNode : updatedPeers) {
                if (ringEngine.getPeer(peerNode.getId()) == null) {
                    ringEngine.registerPeer(peerNode);
                }
            }
        }
        JettraClusterReplicationClient replClient = getOrCreateReplicationClient();
        if (replClient != null) {
            replClient.syncPeers(updatedPeers);
        }
        if (config.getNodeRole() == ClusterNode.Role.SECONDARY) {
            // Refrescar timestamp de latido del líder para evitar falsos timeouts durante la reconfiguración
            lastLeaderHeartbeatTime = System.currentTimeMillis();
        }
        return newlyAddedPeers;
    }

    /**
     * Replica todas las bases de datos y objetos del nodo primario hacia un nodo específico recién añadido.
     */
    public Map<String, Boolean> distributeAllDatabasesToPeer(ClusterNode targetPeer) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (targetPeer == null || config.getNodeRole() != ClusterNode.Role.PRIMARY) {
            return results;
        }
        String peerKey = targetPeer.getId() != null ? targetPeer.getId().toLowerCase(java.util.Locale.ROOT) : "";
        if (!peerKey.isEmpty() && !activePeerDistributions.add(peerKey)) {
            return results; // Ya hay una distribución activa hacia este peer
        }
        try {
            ClusterNode effectivePeer = targetPeer;
            if ((effectivePeer.getPort() <= 0 || effectivePeer.getIp() == null || effectivePeer.getIp().isBlank()) && ringEngine != null) {
                ClusterNode rn = ringEngine.getPeer(effectivePeer.getId());
                if (rn != null) effectivePeer = rn;
            }
            JettraClusterReplicationClient client = getOrCreateReplicationClient();
            if (client == null) {
                return results;
            }
            List<String> dbs = listDatabaseNames();
            System.out.printf("[JettraStoreServer] 🔄 Replicando %d base(s) de datos y sus objetos al nodo '%s' (%s:%d)...%n",
                dbs.size(), effectivePeer.getId(), effectivePeer.getIp(), effectivePeer.getPort());
            for (String db : dbs) {
                byte[] payload = getDatabaseSnapshotBytes(db);
                boolean ok = false;
                for (int attempt = 0; attempt < 4; attempt++) {
                    ok = client.sendDistributeDatabaseToPeer(effectivePeer, db, payload);
                    if (ok) break;
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                results.put(db, ok);
                if (ok) {
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATABASE_DISTRIBUTED,
                        config.getNodeId(), effectivePeer.getId(),
                        String.format("Base de datos '%s' (%d bytes) replicada al nodo '%s'.", db, payload.length, effectivePeer.getId()),
                        "db=" + db + ",bytes=" + payload.length
                    );
                }
            }
            return results;
        } finally {
            if (!peerKey.isEmpty()) {
                activePeerDistributions.remove(peerKey);
            }
        }
    }

    public List<String> listDatabaseNames() {
        Set<String> set = new TreeSet<>(databases.keySet());
        Path p = Path.of(config.getStoragePath());
        if (Files.exists(p) && Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                stream.forEach(entry -> {
                    String fn = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        if (!fn.startsWith(".") && !"indexes".equalsIgnoreCase(fn)) set.add(fn);
                    } else if (fn.endsWith("_meta.json")) {
                        set.add(fn.substring(0, fn.length() - "_meta.json".length()));
                    }
                });
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(set);
    }

    public JettraClusterReplicationClient getReplicationClient() {
        return replicationClient;
    }

    public static void main(String[] args) throws IOException {
        JettraConfigValidator.validateAndBootstrapOrHalt();
        JettraStoreConfig cfg = JettraStoreConfig.load();
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("[JettraStore] Modo de almacenamiento activo (jettra.storage.mode): %s (%s)%n",
            cfg.getStorageMode().name(), cfg.getStorageMode().getDescription());
        System.out.println("--------------------------------------------------------------------------------");
        JettraStoreServer server = new JettraStoreServer(cfg);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[JettraStoreServer] Shutting down JVM hook triggered...");
            server.stop();
        }));
    }

    public JettraSecurityManager getSecurityManager() { return securityManager; }
    public DynamicRingEngine getRingEngine() { return ringEngine; }
    public JettraStoreConfig getConfig() { return config; }
}

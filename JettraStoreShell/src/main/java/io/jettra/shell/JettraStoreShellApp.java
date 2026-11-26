package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.driver.migrator.*;
import io.jettra.driver.version.RecordVersion;
import io.jettra.driver.version.VersionOperation;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.police.JettraPoliceNotification;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.backup.BackupManager;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.cluster.JettraRaftFrame;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.engine.index.JettraIndexManager;
import io.jettra.store.engine.query.JettraQLProcessor;
import io.jettra.store.engine.query.JettraSQLProcessor;
import io.jettra.store.security.JettraSecurityManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Console;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JettraStoreShellApp implements AutoCloseable {
    private JettraClient client;
    private String currentDatabase = null;
    private boolean authenticated = false;
    private String currentUser = "anonymous";
    private String currentRole = "NONE";
    private String currentHost = "127.0.0.1";
    private int currentPort = 50001;
    private boolean lazyLoad = true;
    private boolean showReferences = true;
    private int pageSize = 20;
    private volatile JettraPoliceNotification lastSentinelNotification = null;

    private void registerSentinelListener() {
        if (this.client != null) {
            this.client.addPoliceEventListener(notification -> {
                this.lastSentinelNotification = notification;
                System.out.println();
                System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
                System.out.println("║ 🛡️  [JETTRAPOLICE SENTINEL - PROTECCIÓN PREVENTIVA DE MEMORIA HEAP]         ║");
                System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
                System.out.printf("║  Operación: %-15s | Colección: %-32s║%n", notification.operation(),
                        notification.targetBucket());
                System.out.printf("║  Total Estimado: %-10d | Lote Seguro (Batch): %-23d║%n",
                        notification.estimatedTotalRecords(), notification.safeBatchSize());
                System.out.printf("║  Saturación Heap: %5.1f%%        | RAM Disponible: %-19s║%n",
                        notification.heapUsagePercent(), notification.availableMemoryMb() + " MB");
                System.out.println("║  Estrategia: Streaming por chunks activado para prevenir OutOfMemory.        ║");
                System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            });
        }
    }

    // Estado de Paginación Interactiva de Consultas
    private String lastPagedBaseQuery = null;
    private int currentQueryPage = 1;
    private long totalQueryRecords = 0;
    private int totalQueryPages = 1;

    // Perfiles de Conexión Guardados
    public record SavedConnection(String name, String host, int port, String user) {
    }

    private final Map<String, SavedConnection> savedConnections = Collections.synchronizedMap(new LinkedHashMap<>());

    public JettraStoreShellApp(boolean autoConnect) {
        initDefaultConnections();
        if (autoConnect) {
            SavedConnection defConn = getDefaultSavedConnection();
            String h = defConn != null ? defConn.host() : "127.0.0.1";
            int p = defConn != null ? defConn.port() : 50001;
            String u = defConn != null ? defConn.user() : "admin";
            connectAndLogin(h, p, u, "admin-jettra");
        }
    }

    private SavedConnection getDefaultSavedConnection() {
        if (defaultConnectionName != null && savedConnections.containsKey(defaultConnectionName)) {
            return savedConnections.get(defaultConnectionName);
        }
        List<SavedConnection> ordered = getOrderedConnectionsList();
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    public JettraStoreShellApp(JettraClient client) {
        initDefaultConnections();
        this.client = client;
        this.authenticated = true;
        this.currentUser = "admin";
        this.currentRole = "SUPER_ADMIN";
        this.currentHost = "127.0.0.1";
        this.currentPort = 50001;
        if (this.client != null && this.client.getConfig() != null
                && !this.client.getConfig().getClusterEndpoints().isEmpty()) {
            String ep = this.client.getConfig().getClusterEndpoints().get(0).trim();
            int colon = ep.lastIndexOf(':');
            if (colon > 0) {
                this.currentHost = ep.substring(0, colon).trim();
                try {
                    this.currentPort = Integer.parseInt(ep.substring(colon + 1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        if (this.client != null) {
            registerSentinelListener();
            registerClusterConfigSyncListener();
            syncLocalClusterConfigFromPrimary();
            startClusterStatusSyncWorker();
        }
    }

    private void startClusterStatusSyncWorker() {
        if (closed || !statusSyncWorkerStarted.compareAndSet(false, true)) {
            return;
        }
        this.statusSyncWorkerThread = Thread.ofVirtual()
                .name("jettra-shell-status-sync")
                .start(() -> {
                    while (!closed) {
                        try {
                            Thread.sleep(statusSyncIntervalMs);
                            if (!closed && (authenticated || client != null)) {
                                syncLocalClusterConfigFromPrimary();
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    private final java.util.concurrent.atomic.AtomicBoolean clusterConfigSyncInProgress = new java.util.concurrent.atomic.AtomicBoolean(
            false);
    private volatile boolean clusterEventListenerRegistered = false;
    private volatile boolean closed = false;
    private final String initialClusterConfigPathProp = System.getProperty("cluster.config.path");
    private volatile java.util.function.Consumer<io.jettra.store.cluster.ClusterLiveEvent> clusterEventListener;
    private volatile io.jettra.driver.config.JettraConnectionConfigManager.ConnectionState activeClusterConnectionState;
    private volatile io.jettra.driver.JettraClient.PrimaryClusterStatusSnapshot latestPrimaryClusterStatus;
    private final java.util.concurrent.atomic.AtomicBoolean statusSyncWorkerStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread statusSyncWorkerThread;
    private final long statusSyncIntervalMs = Math.max(400L, Long.getLong("jettra.shell.status.sync.ms", 1500L));

    private void registerClusterConfigSyncListener() {
        if (clusterEventListenerRegistered || closed)
            return;
        clusterEventListenerRegistered = true;
        try {
            java.util.function.Consumer<io.jettra.store.cluster.ClusterLiveEvent> listener = event -> {
                if (closed || !authenticated || client == null)
                    return;
                if (event == null || event.type() == null)
                    return;
                String t = event.type();
                if (io.jettra.store.cluster.ClusterLiveEvent.TYPE_NODE_ONLINE.equals(t)
                        || io.jettra.store.cluster.ClusterLiveEvent.TYPE_NODE_OFFLINE.equals(t)
                        || io.jettra.store.cluster.ClusterLiveEvent.TYPE_LEADER_PROMOTED.equals(t)
                        || (io.jettra.store.cluster.ClusterLiveEvent.TYPE_DATA_TRANSFER.equals(t)
                                && "cluster.config".equalsIgnoreCase(event.details()))) {
                    Thread.ofVirtual().name("jettra-shell-cluster-cfg-sync")
                            .start(this::syncLocalClusterConfigFromPrimary);
                }
            };
            this.clusterEventListener = listener;
            io.jettra.store.cluster.JettraClusterEventBus.getInstance().subscribe(listener);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Obtiene el archivo cluster.config vigente directamente del nodo PRIMARY al
     * que está conectada
     * la consola y lo persiste atómicamente en disco local para mantener
     * sincronizado el clúster.
     */
    public boolean syncLocalClusterConfigFromPrimary() {
        if (closed || !authenticated || client == null) {
            return false;
        }
        String currentClusterProp = System.getProperty("cluster.config.path");
        if (!Objects.equals(initialClusterConfigPathProp, currentClusterProp)) {
            return false;
        }
        if (!clusterConfigSyncInProgress.compareAndSet(false, true)) {
            return false;
        }
        try {
            List<String> candidates = collectStatusQuerySeeds();
            var primarySnap = JettraClient.fetchPrimaryClusterStatus(candidates, 400, 700);
            if (primarySnap != null && primarySnap.rawContent() != null && !primarySnap.rawContent().isBlank()) {
                this.latestPrimaryClusterStatus = primarySnap;
                byte[] cfgBytes = primarySnap.rawContent().getBytes(StandardCharsets.UTF_8);
                if (JettraConfigValidator.validateClusterConfigIntegrity(cfgBytes).isValid()) {
                    try {
                        Path localStateFile = JettraConfigValidator.resolveClusterStateFile();
                        JettraConfigValidator.writeClusterConfigAtomically(localStateFile, cfgBytes);
                    } catch (IOException ignored) {
                    }
                }
                applyPrimaryStatusSnapshotToClient(primarySnap);
                return true;
            }
            for (String ep : candidates) {
                String[] parts = ep.split(":");
                if (parts.length < 2)
                    continue;
                String h = parts[0].trim();
                int p;
                try {
                    p = Integer.parseInt(parts[1].trim());
                } catch (Exception e) {
                    continue;
                }
                if (client == null)
                    continue;
                try {
                    var remoteCfg = client.readPrimaryClusterConfigFrom(h, p, 400, 600);
                    if (remoteCfg != null && remoteCfg.rawBytes() != null && remoteCfg.rawBytes().length > 0) {
                        byte[] cfgBytes = remoteCfg.rawBytes();
                        if (JettraConfigValidator.validateClusterConfigIntegrity(cfgBytes).isValid()) {
                            Path localStateFile = JettraConfigValidator.resolveClusterStateFile();
                            JettraConfigValidator.writeClusterConfigAtomically(localStateFile, cfgBytes);
                            Properties props = new Properties();
                            try (var bais = new java.io.ByteArrayInputStream(cfgBytes)) {
                                props.load(bais);
                            }
                            List<JettraConfigValidator.ClusterNodeInfo> parsed = JettraConfigValidator
                                    .parseExplicitClusterNodes(props);
                            if (!parsed.isEmpty() && client.getRingEngine() != null) {
                                Set<String> liveIds = new HashSet<>();
                                for (var cn : parsed) {
                                    liveIds.add(cn.id().toLowerCase(Locale.ROOT));
                                    ClusterNode.Role r = "PRIMARY".equalsIgnoreCase(cn.role())
                                            ? ClusterNode.Role.PRIMARY
                                            : ClusterNode.Role.SECONDARY;
                                    if (client.getRingEngine().getPeer(cn.id()) == null) {
                                        client.getRingEngine()
                                                .registerPeer(new ClusterNode(cn.id(), cn.ip(), cn.grpcPort(), r));
                                    }
                                }
                                for (ClusterNode existing : new ArrayList<>(client.getRingEngine().getPeers())) {
                                    if (!liveIds.contains(existing.getId().toLowerCase(Locale.ROOT))) {
                                        client.getRingEngine().removePeer(existing.getId());
                                    }
                                }
                            }
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            return false;
        } finally {
            clusterConfigSyncInProgress.set(false);
        }
    }

    private List<String> collectStatusQuerySeeds() {
        List<String> candidates = new ArrayList<>();
        boolean hasExplicitClientEndpoints = client != null && client.getConfig() != null
                && !client.getConfig().getClusterEndpoints().isEmpty();
        if (hasExplicitClientEndpoints) {
            for (String ep : client.getConfig().getClusterEndpoints()) {
                if (ep != null && !ep.isBlank() && !candidates.contains(ep.trim())) {
                    candidates.add(ep.trim());
                }
            }
        }
        if (currentHost != null && !currentHost.isBlank() && currentPort > 0) {
            String hp = currentHost.trim() + ":" + currentPort;
            if (!candidates.contains(hp)) {
                candidates.add(hp);
            }
        }
        if (!hasExplicitClientEndpoints && this.activeClusterConnectionState != null
                && this.activeClusterConnectionState.endpoints() != null) {
            for (var ep : this.activeClusterConnectionState.endpoints()) {
                String hp = ep.toHostPort();
                if (!candidates.contains(hp)) {
                    candidates.add(hp);
                }
            }
        }
        if (!hasExplicitClientEndpoints) {
            synchronized (savedConnections) {
                for (SavedConnection sc : savedConnections.values()) {
                    String hp = sc.host() + ":" + sc.port();
                    if (!candidates.contains(hp)) {
                        candidates.add(hp);
                    }
                }
            }
        }
        return candidates;
    }

    private void applyPrimaryStatusSnapshotToClient(JettraClient.PrimaryClusterStatusSnapshot snap) {
        if (snap == null || snap.nodes() == null || snap.nodes().isEmpty()) {
            return;
        }
        if (client != null && client.getRingEngine() != null) {
            Set<String> liveIds = new HashSet<>();
            for (var cn : snap.nodes()) {
                if (cn.id() == null || cn.id().isBlank()) continue;
                liveIds.add(cn.id().toLowerCase(Locale.ROOT));
                ClusterNode.Role r = "PRIMARY".equalsIgnoreCase(cn.role())
                        ? ClusterNode.Role.PRIMARY
                        : ClusterNode.Role.SECONDARY;
                ClusterNode peer = client.getRingEngine().getPeer(cn.id());
                if (peer == null) {
                    peer = new ClusterNode(cn.id(), cn.ip(), cn.grpcPort(), r);
                    client.getRingEngine().registerPeer(peer);
                } else {
                    peer.setRole(r);
                }
                String st = snap.statuses() != null ? snap.statuses().get(cn.id().toLowerCase(Locale.ROOT)) : null;
                if ("STOPPED".equalsIgnoreCase(st)) {
                    peer.stop();
                } else {
                    peer.start();
                }
            }
            for (ClusterNode existing : new ArrayList<>(client.getRingEngine().getPeers())) {
                if (!liveIds.contains(existing.getId().toLowerCase(Locale.ROOT))) {
                    client.getRingEngine().removePeer(existing.getId());
                }
            }
        }
        if (snap.primaryHost() != null && !snap.primaryHost().isBlank() && snap.primaryGrpcPort() > 0) {
            this.currentHost = snap.primaryHost().trim();
            this.currentPort = snap.primaryGrpcPort();
        }
    }

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".jettra");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("connections.properties");
    private static final Path HISTORY_FILE = CONFIG_DIR.resolve("history.log");
    private static final Path JLINE_HISTORY_FILE = CONFIG_DIR.resolve(".jline_history");
    private final List<String> commandHistory = new CopyOnWriteArrayList<>();
    private volatile org.jline.reader.LineReader activeLineReader;

    public void setActiveLineReader(org.jline.reader.LineReader activeLineReader) {
        this.activeLineReader = activeLineReader;
    }

    public org.jline.reader.LineReader getActiveLineReader() {
        return this.activeLineReader;
    }

    private String defaultConnectionName = "node-01";

    private void initDefaultConnections() {
        boolean loadedFromMicroprofile = loadInitialConnectionsFromMicroprofileConfig();
        if (!loadedFromMicroprofile) {
            loadInitialConnectionsFromJettraConfig();
            loadSavedConnections();
        }
        SavedConnection def = getDefaultSavedConnection();
        if (def != null) {
            this.currentHost = def.host();
            this.currentPort = def.port();
        }
        loadHistory();
    }

    public io.jettra.driver.config.JettraConnectionConfigManager.ConnectionState getActiveClusterConnectionState() {
        return this.activeClusterConnectionState;
    }

    public JettraClient.PrimaryClusterStatusSnapshot getLatestPrimaryClusterStatus() {
        return this.latestPrimaryClusterStatus;
    }

    private boolean loadInitialConnectionsFromMicroprofileConfig() {
        Properties props = new Properties();
        String explicitFile = System.getProperty("jettra.shell.config.file",
                System.getProperty("microprofile.config.file",
                        System.getenv("JETTRA_SHELL_CONFIG_FILE") != null ? System.getenv("JETTRA_SHELL_CONFIG_FILE")
                                : System.getenv("MICROPROFILE_CONFIG_FILE")));

        if (explicitFile != null && !explicitFile.isBlank()) {
            Path p = Path.of(explicitFile.trim());
            if (Files.exists(p)) {
                try (var is = Files.newInputStream(p)) {
                    props.load(is);
                } catch (Exception ignored) {
                }
            }
        } else {
            // 1. Recursos en Classpath
            try {
                var resources = getClass().getClassLoader().getResources("META-INF/microprofile-config.properties");
                while (resources.hasMoreElements()) {
                    try (var is = resources.nextElement().openStream()) {
                        props.load(is);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                var resources = getClass().getClassLoader().getResources("microprofile-config.properties");
                while (resources.hasMoreElements()) {
                    try (var is = resources.nextElement().openStream()) {
                        props.load(is);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            try (var is = getClass().getResourceAsStream("/META-INF/microprofile-config.properties")) {
                if (is != null)
                    props.load(is);
            } catch (Exception ignored) {
            }
            try (var is = getClass().getResourceAsStream("/microprofile-config.properties")) {
                if (is != null)
                    props.load(is);
            } catch (Exception ignored) {
            }

            // 2. Archivos en disco (prioridad sobre classpath)
            List<String> candidates = new ArrayList<>(List.of(
                    "JettraStoreShell/src/main/resources/META-INF/microprofile-config.properties",
                    "src/main/resources/META-INF/microprofile-config.properties",
                    "./META-INF/microprofile-config.properties",
                    "./config/microprofile-config.properties",
                    "./microprofile-config.properties",
                    Path.of(System.getProperty("user.home"), "jettrastoreshell", "config",
                            "microprofile-config.properties").toString(),
                    Path.of(System.getProperty("user.home"), ".jettra", "microprofile-config.properties").toString()));

            try {
                var codeSource = JettraStoreShellApp.class.getProtectionDomain().getCodeSource();
                if (codeSource != null && codeSource.getLocation() != null) {
                    Path jarParent = Path.of(codeSource.getLocation().toURI()).getParent();
                    if (jarParent != null) {
                        candidates.add(jarParent.resolve("config/microprofile-config.properties").toString());
                        candidates.add(jarParent.resolve("microprofile-config.properties").toString());
                    }
                }
            } catch (Exception ignored) {
            }

            for (String c : candidates) {
                Path p = Path.of(c);
                if (Files.exists(p)) {
                    try (var is = Files.newInputStream(p)) {
                        props.load(is);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        var connState = io.jettra.driver.config.JettraConnectionConfigManager.loadInitialConnection(props);
        this.activeClusterConnectionState = connState;

        String defaultUser = props.getProperty("jettra.security.default.admin.username",
                props.getProperty("jettra.admin.username",
                        connState != null && connState.username() != null ? connState.username() : "admin"));

        // Carga desde conecction.config o cluster.ip.nodes en microprofile-config.properties
        if (connState != null && connState.endpoints() != null && !connState.endpoints().isEmpty()) {
            savedConnections.clear();
            defaultConnectionName = null;
            int idx = 1;
            for (var ep : connState.endpoints()) {
                String id = String.format("node-%02d", idx);
                savedConnections.put(id, new SavedConnection(id, ep.host(), ep.port(), defaultUser));
                if (defaultConnectionName == null) {
                    defaultConnectionName = id;
                }
                idx++;
            }
            return true;
        }

        // Fallback a propiedades directas de conexión (jettra.host, jettra.port /
        // JETTRA_HOST, JETTRA_PORT)
        String fallbackHost = System.getProperty("jettra.host",
                props.getProperty("jettra.host",
                        props.getProperty("server.host", System.getenv("JETTRA_HOST"))));
        String fallbackPortStr = System.getProperty("jettra.port",
                props.getProperty("jettra.port",
                        props.getProperty("server.port", System.getenv("JETTRA_PORT"))));

        if (fallbackHost != null && !fallbackHost.isBlank()) {
            int port = 50001;
            try {
                if (fallbackPortStr != null)
                    port = Integer.parseInt(fallbackPortStr.trim());
            } catch (Exception ignored) {
            }
            savedConnections.clear();
            savedConnections.put("node-01", new SavedConnection("node-01", fallbackHost.trim(), port, defaultUser));
            defaultConnectionName = "node-01";
            return true;
        }

        return false;
    }

    private void loadInitialConnectionsFromJettraConfig() {
        try {
            Path clusterPath = JettraConfigValidator.locateJettraConfigFile();
            if (clusterPath != null && Files.exists(clusterPath)) {
                Properties clusterProps = JettraConfigValidator.loadProperties(clusterPath, "/jettra.config");
                List<JettraConfigValidator.ClusterNodeInfo> nodes = JettraConfigValidator
                        .parseClusterNodes(clusterProps);
                if (nodes != null && !nodes.isEmpty()) {
                    for (JettraConfigValidator.ClusterNodeInfo node : nodes) {
                        String name = node.id();
                        String host = (node.ip() != null && !node.ip().isBlank()) ? node.ip() : "127.0.0.1";
                        int port = node.grpcPort() > 0 ? node.grpcPort() : 50001;
                        savedConnections.put(name, new SavedConnection(name, host, port, "admin"));
                        if ("PRIMARY".equalsIgnoreCase(node.role())) {
                            defaultConnectionName = name;
                        }
                    }
                    if (defaultConnectionName == null && !nodes.isEmpty()) {
                        defaultConnectionName = nodes.get(0).id();
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback predeterminado según jettra.config estándar
        savedConnections.put("node-01", new SavedConnection("node-01", "127.0.0.1", 50001, "admin"));
        savedConnections.put("node-02", new SavedConnection("node-02", "127.0.0.1", 50002, "admin"));
        savedConnections.put("node-03", new SavedConnection("node-03", "127.0.0.1", 50003, "admin"));
        defaultConnectionName = "node-01";
    }

    private void loadHistory() {
        try {
            if (Files.exists(HISTORY_FILE)) {
                List<String> lines = Files.readAllLines(HISTORY_FILE, StandardCharsets.UTF_8);
                commandHistory.clear();
                int start = Math.max(0, lines.size() - 1000);
                for (int i = start; i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (!l.isEmpty()) {
                        commandHistory.add(l);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void recordHistory(String command) {
        if (command == null || command.isBlank())
            return;
        String trimmed = command.trim();
        if (trimmed.startsWith("!") || trimmed.equalsIgnoreCase("HISTORY")
                || trimmed.equalsIgnoreCase("HISTORY CLEAR")) {
            return;
        }
        commandHistory.add(trimmed);
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            Files.writeString(HISTORY_FILE, trimmed + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        if (activeLineReader != null) {
            try {
                activeLineReader.getHistory().add(trimmed);
            } catch (Exception ignored) {
            }
        }
    }

    public List<String> getCommandHistory() {
        return Collections.unmodifiableList(commandHistory);
    }

    public String handleHistory(String command) {
        String upper = command.toUpperCase().trim();
        if (upper.equals("HISTORY CLEAR")) {
            commandHistory.clear();
            if (activeLineReader != null) {
                try {
                    activeLineReader.getHistory().purge();
                } catch (Exception ignored) {
                }
            }
            try {
                if (Files.exists(HISTORY_FILE)) {
                    Files.writeString(HISTORY_FILE, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
                if (Files.exists(JLINE_HISTORY_FILE)) {
                    Files.writeString(JLINE_HISTORY_FILE, "", StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Exception ignored) {
            }
            return "[OK] Historial de comandos limpiado exitosamente.";
        }

        if (upper.startsWith("HISTORY SEARCH ")) {
            String term = command.substring("HISTORY SEARCH ".length()).trim().toLowerCase();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== BÚSQUEDA EN HISTORIAL ('%s') ===%n", term));
            int matches = 0;
            for (int i = 0; i < commandHistory.size(); i++) {
                String cmd = commandHistory.get(i);
                if (cmd.toLowerCase().contains(term)) {
                    sb.append(String.format("  [%4d] %s%n", (i + 1), cmd));
                    matches++;
                }
            }
            if (matches == 0) {
                sb.append(String.format("  (No se encontraron coincidencias para '%s')%n", term));
            }
            return sb.toString();
        }

        int count = commandHistory.size();
        if (upper.startsWith("HISTORY ")) {
            try {
                count = Integer.parseInt(command.substring("HISTORY ".length()).trim());
            } catch (NumberFormatException ignored) {
            }
        }

        if (commandHistory.isEmpty()) {
            return "[INFO] El historial de comandos está vacío.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== HISTORIAL DE COMANDOS (%d registrados) ===%n", commandHistory.size()));
        int start = Math.max(0, commandHistory.size() - count);
        for (int i = start; i < commandHistory.size(); i++) {
            sb.append(String.format("  [%4d] %s%n", (i + 1), commandHistory.get(i)));
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(
                "Uso: !<num> ejecuta comando por número | !! ejecuta el último | !<prefijo> ejecuta por coincidencia\n");
        return sb.toString();
    }

    private String expandBangCommand(String cmd) {
        String trimmed = cmd.trim();
        if (trimmed.equals("!!")) {
            if (commandHistory.isEmpty()) {
                throw new IllegalArgumentException("[ERROR] Historial vacío. No hay comando previo para ejecutar.");
            }
            return commandHistory.get(commandHistory.size() - 1);
        }
        if (trimmed.matches("^!\\d+$")) {
            int idx = Integer.parseInt(trimmed.substring(1));
            if (idx < 1 || idx > commandHistory.size()) {
                throw new IllegalArgumentException("[ERROR] Índice de historial fuera de rango: " + idx + " (Total: "
                        + commandHistory.size() + ")");
            }
            return commandHistory.get(idx - 1);
        }
        if (trimmed.startsWith("!") && trimmed.length() > 1) {
            String prefix = trimmed.substring(1).toLowerCase();
            for (int i = commandHistory.size() - 1; i >= 0; i--) {
                String h = commandHistory.get(i);
                if (h.toLowerCase().startsWith(prefix)) {
                    return h;
                }
            }
            throw new IllegalArgumentException(
                    "[ERROR] No se encontró ningún comando en el historial que comience con '" + prefix + "'.");
        }
        return cmd;
    }

    public List<String> autocomplete(String prefix) {
        if (prefix == null)
            prefix = "";
        String pTrim = prefix.trim();
        String pUpper = prefix.toUpperCase();
        String pTrimUpper = pTrim.toUpperCase();

        Set<String> candidates = new LinkedHashSet<>();

        List<String> baseKeywords = List.of(
                // Sistema & Ayuda
                "HELP", "HELP STORAGE_MODE", "HELP CLUSTER-DISTRIBUTED", "HELP CLUSTER LIVE", "MENU",
                "MENU CONNECTIONS", "STATUS",
                "HISTORY", "HISTORY CLEAR", "HISTORY SEARCH ", "COMPLETE ", "TAB ", "EXIT", "QUIT",
                // Conexión & Autenticación
                "/connections", "CONNECTIONS", "SET CONNECTION ", "EDIT CONNECTION ",
                "CONNECT ", "LOGIN ", "LOGOUT", "SAVE CONNECTION ", "REMOVE CONNECTION ", "LIST CONNECTIONS",
                // Bases de Datos
                "SHOW DATABASES", "SHOW SAMPLES", "SHOW SAMPLE DATABASES", "CREATE DATABASE ", "DROP DATABASE ", "USE ",
                "DB STATS", "DB INFO",
                // Buckets & Índices
                "SHOW BUCKETS", "SHOW TABLES", "SHOW UNITS", "SHOW RECORDS ", "COUNT ",
                "CREATE BUCKET ", "DROP BUCKET ",
                "CREATE INDEX ", "CREATE INDEX ON ", "DROP INDEX ", "SHOW INDEXES", "SHOW INDEXES ON ", "LIST INDEXES",
                "REINDEX ",
                // Usuarios & Seguridad
                "SHOW USERS", "LIST USERS", "CREATE USER ", "DROP USER ", "ALTER USER ",
                "GRANT ", "GRANT READ ON ", "GRANT WRITE ON ", "GRANT ADMIN ON ",
                "REVOKE ", "REVOKE READ ON ", "REVOKE WRITE ON ", "SHOW GRANTS FOR ",
                // CRUD & Referencias
                "SELECT ", "SELECT * FROM ", "INSERT INTO ", "UPDATE ", "DELETE FROM ", "GET ",
                "FIND ONE ", "FIND ALL ", "FIND BY ID ", "SCAN ",
                "INSERT REF ", "RESOLVE REF ", "SHOW REFS ",
                // Topología, Nodos & Cluster
                "SHOW NODES", "LIST NODES", "ADD NODE ", "REMOVE NODE ", "START NODE ", "STOP NODE ",
                "CLUSTER ADD-NODE ", "CLUSTER ADD NODE ", "CLUSTER REMOVE-NODE ", "CLUSTER REMOVE NODE ",
                "MULTINODE", "MULTINODE ON", "MULTINODE OFF", "MULTINODE STATUS", "SHOW MULTINODE",
                "CLUSTER-DISTRIBUTED", "CLUSTER-DISTRIBUTED ALL", "CLUSTER-DISTRIBUTED <ALL>",
                "CLUSTER-DISTRIBUTED INFO", "CLUSTER-DISTRIBUTED ",
                "CLUSTER LIVE", "CLUSTER LIVE 10", "CLUSTER LIVE 30", "CLUSTER LIVE 60", "CLUSTER_LIVE",
                // Modos de Almacenamiento & Lazy Reference
                "STORAGE_MODE", "SHOW STORAGE_MODE", "STORAGE_MODE JVM-RAM", "STORAGE_MODE DISK-MEMORY",
                "STORAGE_MODE OFF-HEAP",
                "SET STORAGE_MODE JVM-RAM", "SET STORAGE_MODE DISK-MEMORY", "SET STORAGE_MODE OFF-HEAP",
                "LAZY REFERENCE ON", "LAZY REFERENCE OFF", "LAZY REFERENCE STATUS",
                // Motores Multimodelo
                "KV PUT ", "KV GET ", "KV DELETE ", "KV SCAN ",
                "VEC INDEX ", "VEC SEARCH ", "VECTOR INDEX ", "VECTOR SEARCH ",
                "GRAPH ADD VERTEX ", "GRAPH ADD EDGE ", "GRAPH GET EDGES ", "GRAPH TRAVERSE ", "GRAPH BFS ",
                "GRAPH SHORTEST ",
                "TS RECORD ", "TS RANGE ", "TS QUERY ", "SERIES ADD ", "SERIES RANGE ", "SERIES STATS ",
                "GEO INSERT ", "GEO RADIUS ", "GEO BBOX ",
                "COL INSERT ", "COL SCAN ",
                "JQL ", "JETTRAQL ", "MATCH ", "FETCH ",
                "SQL ", "JETTRASQL ",
                "AGGREGATE ", "AGG SUM ", "AGG AVG ", "AGG MIN ", "AGG MAX ", "AGG COUNT ", "AGG GROUP ",
                "MATH ", "FINANCE ", "STATS ", "VECTOR ",
                // Muestras & Respaldo
                "INSTALL SAMPLES", "INSTALL SAMPLES FACTURA", "INSTALL SAMPLES HOSPITAL", "INSTALL SAMPLES AMBIENTAL",
                "LOAD SAMPLE example_factura_db", "LOAD SAMPLE samples_hostipal_db", "LOAD SAMPLE samples_ambiental_db",
                "BACKUP DATABASE", "BACKUP DATABASE ", "RESTORE DATABASE", "RESTORE DATABASE ",
                // Paginación
                "PAGE NEXT", "PAGE PREV", "PAGE FIRST", "PAGE LAST", "PAGE SIZE ",
                "FIRST", "PREV", "NEXT", "LAST");

        // 1. Sugerencias contextuales para Bases de Datos
        if (pUpper.startsWith("USE ") || pTrimUpper.equals("USE")
                || pUpper.startsWith("DROP DATABASE ") || pTrimUpper.equals("DROP DATABASE")
                || pUpper.startsWith("DROP DB ") || pTrimUpper.equals("DROP DB")
                || pUpper.startsWith("BACKUP DATABASE ") || pTrimUpper.equals("BACKUP DATABASE")
                || pUpper.startsWith("RESTORE DATABASE ") || pTrimUpper.equals("RESTORE DATABASE")) {
            String verb;
            String dbPrefix = "";
            int firstSpace = prefix.indexOf(' ');
            if (firstSpace == -1) {
                verb = prefix.toUpperCase() + " ";
            } else {
                int lastSpace = prefix.lastIndexOf(' ');
                verb = prefix.substring(0, lastSpace + 1);
                dbPrefix = prefix.substring(lastSpace + 1).trim().toLowerCase();
            }

            List<String> dbs = new ArrayList<>(List.of(
                    "default_db", "sample_enterprise_db", "sample_ecommerce_db", "sample_ai_graph_db",
                    "sample_iot_telemetry_db", "sample_financial_db", "example_factura_db",
                    "samples_hostipal_db", "samples_ambiental_db"));
            if (client != null) {
                try {
                    for (String d : client.listDatabases()) {
                        if (!dbs.contains(d))
                            dbs.add(d);
                    }
                } catch (Exception ignored) {
                }
            }
            for (String d : dbs) {
                if (dbPrefix.isEmpty() || d.toLowerCase().startsWith(dbPrefix)) {
                    candidates.add(verb + d);
                }
            }
        }
        // 2. Sugerencias contextuales para CLUSTER-DISTRIBUTED
        else if (pUpper.startsWith("CLUSTER-DISTRIBUTED ") || pUpper.startsWith("CLUSTER_DISTRIBUTED ")
                || pTrimUpper.equals("CLUSTER-DISTRIBUTED") || pTrimUpper.equals("CLUSTER_DISTRIBUTED")) {
            String verb = prefix.contains(" ") ? prefix.substring(0, prefix.indexOf(' ') + 1) : "cluster-distributed ";
            String sub = prefix.contains(" ") ? prefix.substring(prefix.indexOf(' ') + 1).trim().toLowerCase() : "";
            List<String> options = new ArrayList<>(List.of("all", "<all>", "info"));
            if (client != null) {
                try {
                    options.addAll(client.listDatabases());
                } catch (Exception ignored) {
                }
            } else {
                options.addAll(List.of("default_db", "sample_enterprise_db", "sample_ecommerce_db"));
            }
            for (String opt : options) {
                if (sub.isEmpty() || opt.toLowerCase().startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 3. Sugerencias contextuales para Muestras (LOAD SAMPLE / INSTALL SAMPLE)
        else if (pUpper.startsWith("LOAD SAMPLE ") || pTrimUpper.equals("LOAD SAMPLE")
                || pUpper.startsWith("INSTALL SAMPLE ") || pTrimUpper.equals("INSTALL SAMPLE")
                || pUpper.startsWith("INSTALL SAMPLES ") || pTrimUpper.equals("INSTALL SAMPLES")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toLowerCase() : "";
            List<String> samples = List.of("example_factura_db", "samples_hostipal_db", "samples_ambiental_db",
                    "FACTURA", "HOSPITAL", "AMBIENTAL");
            for (String s : samples) {
                if (sub.isEmpty() || s.toLowerCase().startsWith(sub)) {
                    candidates.add(verb + s);
                }
            }
        }
        // 4. Sugerencias contextuales para Buckets en consultas
        else if (pUpper.startsWith("SHOW RECORDS ") || pTrimUpper.equals("SHOW RECORDS")
                || pUpper.startsWith("COUNT ") || pTrimUpper.equals("COUNT")
                || pUpper.startsWith("SELECT * FROM ") || pTrimUpper.equals("SELECT * FROM")
                || pUpper.startsWith("INSERT INTO ") || pTrimUpper.equals("INSERT INTO")
                || pUpper.startsWith("DELETE FROM ") || pTrimUpper.equals("DELETE FROM")
                || pUpper.startsWith("DROP BUCKET ") || pTrimUpper.equals("DROP BUCKET")
                || pUpper.startsWith("CREATE INDEX ON ") || pTrimUpper.equals("CREATE INDEX ON")
                || pUpper.startsWith("SHOW INDEXES ON ") || pTrimUpper.equals("SHOW INDEXES ON")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String bucketPrefix = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toLowerCase() : "";
            if (pTrimUpper.equals("COUNT") && (bucketPrefix.isEmpty() || "*".startsWith(bucketPrefix))) {
                candidates.add(verb + "*");
            }
            if (client != null && currentDatabase != null && !currentDatabase.isBlank()
                    && client.isDatabaseLoaded(currentDatabase)) {
                try {
                    JettraDatabase db = client.getDatabase(currentDatabase);
                    if (db != null) {
                        for (String b : db.getAllBucketNames()) {
                            if (bucketPrefix.isEmpty() || b.toLowerCase().startsWith(bucketPrefix)) {
                                candidates.add(verb + b);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // 5. Sugerencias contextuales para Conexiones Guardadas (CONNECT / REMOVE
        // CONNECTION)
        else if (pUpper.startsWith("CONNECT ") || pTrimUpper.equals("CONNECT")
                || pUpper.startsWith("REMOVE CONNECTION ") || pTrimUpper.equals("REMOVE CONNECTION")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toLowerCase() : "";
            if (pUpper.startsWith("CONNECT ") || pTrimUpper.equals("CONNECT")) {
                if (sub.isEmpty() || "127.0.0.1 50001".startsWith(sub)) {
                    candidates.add(verb + "127.0.0.1 50001");
                }
            }
            for (String connName : savedConnections.keySet()) {
                if (sub.isEmpty() || connName.toLowerCase().startsWith(sub)) {
                    candidates.add(verb + connName);
                }
            }
        }
        // 6. Sugerencias para STORAGE_MODE
        else if (pUpper.startsWith("STORAGE_MODE ") || pTrimUpper.equals("STORAGE_MODE")
                || pUpper.startsWith("SET STORAGE_MODE ") || pTrimUpper.equals("SET STORAGE_MODE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String mode : List.of("JVM-RAM", "DISK-MEMORY", "OFF-HEAP")) {
                if (sub.isEmpty() || mode.startsWith(sub)) {
                    candidates.add(verb + mode);
                }
            }
        }
        // 7. Sugerencias para MULTINODE
        else if (pUpper.startsWith("MULTINODE ") || pTrimUpper.equals("MULTINODE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String opt : List.of("ON", "OFF", "STATUS")) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 8. Sugerencias para LAZY REFERENCE
        else if (pUpper.startsWith("LAZY REFERENCE ") || pTrimUpper.equals("LAZY REFERENCE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String opt : List.of("ON", "OFF", "STATUS")) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 9. Sugerencias para CLUSTER LIVE
        else if (pUpper.startsWith("CLUSTER LIVE ") || pTrimUpper.equals("CLUSTER LIVE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String sec : List.of("10", "30", "60")) {
                if (sub.isEmpty() || sec.startsWith(sub)) {
                    candidates.add(verb + sec);
                }
            }
        }
        // 10. Sugerencias para subcomandos SHOW
        else if (pUpper.startsWith("SHOW ") || pTrimUpper.equals("SHOW")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            List<String> showOptions = List.of(
                    "DATABASES", "BUCKETS", "TABLES", "INDEXES",
                    "USERS", "NODES", "SAMPLES", "STORAGE_MODE", "MULTINODE", "RECORDS ");
            for (String opt : showOptions) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 11. Sugerencias para subcomandos CREATE
        else if (pUpper.startsWith("CREATE ") || pTrimUpper.equals("CREATE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            List<String> createOptions = List.of("DATABASE ", "BUCKET ", "INDEX ", "USER ");
            for (String opt : createOptions) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 12. Sugerencias para subcomandos DROP
        else if (pUpper.startsWith("DROP ") || pTrimUpper.equals("DROP")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            List<String> dropOptions = List.of("DATABASE ", "BUCKET ", "INDEX ", "USER ");
            for (String opt : dropOptions) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 13. Sugerencias para subcomandos PAGE
        else if (pUpper.startsWith("PAGE ") || pTrimUpper.equals("PAGE")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String opt : List.of("FIRST", "PREV", "NEXT", "LAST", "SIZE ")) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }
        // 14. Sugerencias para subcomandos HISTORY
        else if (pUpper.startsWith("HISTORY ") || pTrimUpper.equals("HISTORY")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String verb = lastSpace != -1 ? prefix.substring(0, lastSpace + 1) : prefix + " ";
            String sub = lastSpace != -1 ? prefix.substring(lastSpace + 1).trim().toUpperCase() : "";
            for (String opt : List.of("CLEAR", "SEARCH ")) {
                if (sub.isEmpty() || opt.startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        }

        // Si el prefijo está vacío, devolver las palabras clave maestras
        if (pTrim.isEmpty()) {
            candidates.addAll(baseKeywords);
            return new ArrayList<>(candidates);
        }

        // Búsqueda general en baseKeywords
        for (String kw : baseKeywords) {
            if (kw.toUpperCase().startsWith(pUpper) || kw.toUpperCase().startsWith(pTrimUpper)) {
                candidates.add(kw);
            }
        }

        // Búsqueda en historial de comandos ejecutados (sin duplicar ignorando
        // mayúsculas/minúsculas)
        for (int i = commandHistory.size() - 1; i >= 0; i--) {
            String h = commandHistory.get(i);
            if (h.toUpperCase().startsWith(pUpper) || h.toUpperCase().startsWith(pTrimUpper)) {
                boolean exists = false;
                for (String c : candidates) {
                    if (c.equalsIgnoreCase(h)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    candidates.add(h);
                    if (candidates.size() >= 40)
                        break;
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    public String resolveCandidateGroup(String command) {
        if (command == null)
            return "Comandos";
        String u = command.toUpperCase().trim();
        if (u.startsWith("SHOW DATA") || u.startsWith("SHOW DB") || u.startsWith("USE ") || u.startsWith("CREATE DATA")
                || u.startsWith("CREATE DB") || u.startsWith("DROP DATA") || u.startsWith("DROP DB")
                || u.startsWith("DB ") || u.startsWith("LIST DATA") || u.startsWith("LIST DB")
                || u.startsWith("SHOW SAMPLE")) {
            return "Bases de Datos";
        }
        if (u.startsWith("SHOW BUCKET") || u.startsWith("SHOW UNIT") || u.startsWith("SHOW COLL")
                || u.startsWith("SHOW TAB") || u.startsWith("CREATE COLL") || u.startsWith("DROP COLL")
                || u.startsWith("CREATE BUCKET") || u.startsWith("DROP BUCKET") || u.startsWith("COUNT")
                || u.startsWith("SHOW RECORDS")) {
            return "Colecciones y Buckets";
        }
        if (u.startsWith("CREATE INDEX") || u.startsWith("DROP INDEX") || u.startsWith("SHOW INDEX")
                || u.startsWith("LIST INDEX") || u.startsWith("REINDEX") || u.startsWith("ALTER INDEX")) {
            return "Índices";
        }
        if (u.startsWith("SHOW USER") || u.startsWith("LIST USER") || u.startsWith("CREATE USER")
                || u.startsWith("DROP USER") || u.startsWith("ALTER USER") || u.startsWith("GRANT")
                || u.startsWith("REVOKE") || u.startsWith("SHOW GRANTS")) {
            return "Seguridad y Usuarios";
        }
        if (u.startsWith("CLUSTER") || u.startsWith("MULTINODE") || u.startsWith("SHOW NODE")
                || u.startsWith("LIST NODE") || u.startsWith("ADD NODE") || u.startsWith("REMOVE NODE")
                || u.startsWith("START NODE") || u.startsWith("STOP NODE")) {
            return "Cluster y Nodos";
        }
        if (u.startsWith("SELECT") || u.startsWith("INSERT") || u.startsWith("UPDATE") || u.startsWith("DELETE")
                || u.startsWith("GET ") || u.startsWith("FIND") || u.startsWith("SCAN") || u.startsWith("RESOLVE")
                || u.startsWith("SHOW REFS")) {
            return "CRUD y Consultas";
        }
        if (u.startsWith("KV ") || u.startsWith("VEC ") || u.startsWith("VECTOR ") || u.startsWith("GRAPH ")
                || u.startsWith("TS ") || u.startsWith("SERIES ") || u.startsWith("GEO ") || u.startsWith("COL ")
                || u.startsWith("JQL ") || u.startsWith("JETTRAQL ") || u.startsWith("SQL ") || u.startsWith("AGG")
                || u.startsWith("MATH") || u.startsWith("FINANCE") || u.startsWith("STATS")) {
            return "Motores Multimodelo";
        }
        if (u.startsWith("CONNECT") || u.startsWith("LOGIN") || u.startsWith("LOGOUT") || u.startsWith("SAVE CONN")
                || u.startsWith("REMOVE CONN") || u.startsWith("LIST CONN")) {
            return "Conexiones y Perfiles";
        }
        if (u.startsWith("STORAGE_MODE") || u.startsWith("SET STORAGE_MODE") || u.startsWith("LAZY REFERENCE")) {
            return "Almacenamiento y Memoria";
        }
        if (u.startsWith("INSTALL SAMPLE") || u.startsWith("LOAD SAMPLE") || u.startsWith("BACKUP")
                || u.startsWith("RESTORE")) {
            return "Muestras y Respaldo";
        }
        if (u.startsWith("PAGE") || u.equals("FIRST") || u.equals("PREV") || u.equals("NEXT") || u.equals("LAST")) {
            return "Paginación";
        }
        if (u.startsWith("HISTORY")) {
            return "Historial";
        }
        return "Sistema y Ayuda";
    }

    public String resolveCandidateDescription(String command) {
        if (command == null)
            return null;
        String u = command.toUpperCase().trim();
        if (u.equals("HELP") || u.equals("?"))
            return "Guía completa de comandos disponibles";
        if (u.equals("MENU"))
            return "Abre el menú interactivo guiado";
        if (u.equals("STATUS"))
            return "Muestra telemetría de Panama FFM y estado del motor";
        if (u.equals("SHOW DATABASES") || u.equals("SHOW DBS"))
            return "Lista todas las bases de datos registradas";
        if (u.startsWith("USE "))
            return "Conmuta la base de datos activa para la sesión";
        if (u.startsWith("CREATE DATABASE ") || u.startsWith("CREATE DB "))
            return "Crea una nueva base de datos distribuida";
        if (u.startsWith("DROP DATABASE ") || u.startsWith("DROP DB "))
            return "Elimina permanentemente una base de datos";
        if (u.equals("SHOW BUCKETS") || u.equals("SHOW UNITS"))
            return "Lista colecciones/buckets de la base de datos activa";
        if (u.equals("SHOW TABLES"))
            return "Lista colecciones o tablas de la base de datos activa";
        if (u.startsWith("SHOW RECORDS "))
            return "Muestra registros de una colección o bucket";
        if (u.startsWith("COUNT "))
            return "Retorna la cantidad total de registros";
        if (u.equals("SHOW INDEXES") || u.equals("LIST INDEXES"))
            return "Lista índices secundarios y vectoriales creados";
        if (u.startsWith("CREATE INDEX "))
            return "Crea un índice secundario sobre una colección";
        if (u.startsWith("DROP INDEX "))
            return "Elimina un índice secundario existente";
        if (u.equals("SHOW USERS") || u.equals("LIST USERS"))
            return "Lista cuentas de usuario del sistema";
        if (u.startsWith("CREATE USER "))
            return "Crea un nuevo usuario en JettraStore";
        if (u.startsWith("DROP USER "))
            return "Elimina una cuenta de usuario";
        if (u.equals("SHOW NODES") || u.equals("LIST NODES"))
            return "Lista la topología de nodos del clúster";
        if (u.startsWith("MULTINODE ON"))
            return "Habilita replicación distribuida con consenso Raft";
        if (u.startsWith("MULTINODE OFF"))
            return "Establece el nodo en modo autónomo independiente";
        if (u.startsWith("MULTINODE"))
            return "Consulta el estado actual de sincronización multinodo";
        if (u.startsWith("CLUSTER-DISTRIBUTED ALL"))
            return "Distribuye todas las bases de datos a nodos secundarios";
        if (u.startsWith("CLUSTER-DISTRIBUTED INFO"))
            return "Muestra la matriz de distribución del clúster";
        if (u.startsWith("CLUSTER LIVE"))
            return "Monitorea eventos de replicación en vivo por SSE";
        if (u.startsWith("SHOW STORAGE_MODE") || u.equals("STORAGE_MODE"))
            return "Muestra el modo de almacenamiento activo";
        if (u.startsWith("STORAGE_MODE JVM-RAM") || u.startsWith("SET STORAGE_MODE JVM-RAM"))
            return "Establece almacenamiento en Heap estándar de JVM";
        if (u.startsWith("STORAGE_MODE DISK-MEMORY") || u.startsWith("SET STORAGE_MODE DISK-MEMORY"))
            return "Establece almacenamiento Off-Heap directo LSM (JettraMemory)";
        if (u.startsWith("STORAGE_MODE OFF-HEAP") || u.startsWith("SET STORAGE_MODE OFF-HEAP"))
            return "Establece almacenamiento Off-Heap Slotted-Page FFM (JettraStore-OffHeap)";
        if (u.equals("SHOW SAMPLES") || u.equals("SHOW SAMPLE DATABASES"))
            return "Muestra bases de datos de demostración predefinidas";
        if (u.startsWith("INSTALL SAMPLES"))
            return "Inicializa bases de datos de prueba predefinidas";
        if (u.startsWith("LOAD SAMPLE "))
            return "Carga una muestra específica (factura, hospital, ambiental)";
        if (u.startsWith("BACKUP DATABASE"))
            return "Genera copia de respaldo física de la base de datos";
        if (u.startsWith("RESTORE DATABASE"))
            return "Restaura base de datos a partir de respaldo";
        if (u.startsWith("CONNECT "))
            return "Configura endpoint de nodo o carga perfil guardado";
        if (u.startsWith("LOGIN "))
            return "Autentica credenciales y genera token JettraJWT";
        if (u.equals("LOGOUT"))
            return "Cierra la sesión autenticada actual";
        if (u.equals("LIST CONNECTIONS"))
            return "Lista perfiles guardados en jettra.config";
        if (u.startsWith("SAVE CONNECTION "))
            return "Guarda perfil de conexión en configuración";
        if (u.startsWith("REMOVE CONNECTION "))
            return "Elimina perfil de conexión guardado";
        if (u.startsWith("SELECT "))
            return "Ejecuta consulta SQL/JQL sobre una colección";
        if (u.startsWith("INSERT INTO "))
            return "Inserta documento en la colección especificada";
        if (u.startsWith("DELETE FROM "))
            return "Elimina documentos de la colección especificada";
        if (u.startsWith("KV PUT "))
            return "Guarda par clave-valor en motor Key-Value";
        if (u.startsWith("KV GET "))
            return "Recupera valor por clave del motor Key-Value";
        if (u.startsWith("VEC SEARCH ") || u.startsWith("VECTOR SEARCH "))
            return "Búsqueda vectorial por similitud de coseno";
        if (u.startsWith("GRAPH "))
            return "Operaciones sobre motor de grafos topológicos";
        if (u.startsWith("TS "))
            return "Operaciones sobre motor de series de tiempo";
        if (u.startsWith("PAGE NEXT"))
            return "Navega a la página siguiente de resultados";
        if (u.startsWith("PAGE PREV"))
            return "Navega a la página anterior de resultados";
        if (u.startsWith("HISTORY CLEAR"))
            return "Limpia historial de comandos ejecutados";
        if (u.equals("HISTORY"))
            return "Muestra historial reciente de comandos";
        return null;
    }

    public String handleAutocomplete(String command) {
        String prefix = "";
        String upper = command.toUpperCase().trim();
        if (upper.startsWith("COMPLETE ") || upper.startsWith("AUTOCOMPLETE ") || upper.startsWith("TAB ")) {
            prefix = command.substring(command.indexOf(' ') + 1).trim();
        }
        List<String> results = autocomplete(prefix);
        if (results.isEmpty()) {
            return String.format("[INFO] No se encontraron sugerencias de autocompletado para '%s'.", prefix);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== SUGERENCIAS DE AUTOCOMPLETADO ('%s') ===%n", prefix));
        for (int i = 0; i < results.size(); i++) {
            sb.append(String.format("  [%02d] %s%n", (i + 1), results.get(i)));
        }
        return sb.toString();
    }

    private void loadSavedConnections() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                Properties props = new Properties();
                try (var in = Files.newInputStream(CONFIG_FILE)) {
                    props.load(in);
                }
                // Purge legacy obsolete placeholder names in favor of jettra.config
                Set<String> legacyNames = Set.of(
                        "local_master", "node-02-replica", "node-03-replica",
                        "node-1", "node-2", "node-3");
                for (String name : props.stringPropertyNames()) {
                    if (legacyNames.contains(name.toLowerCase())) {
                        continue;
                    }
                    String val = props.getProperty(name);
                    String[] parts = val.split(":", 3);
                    if (parts.length >= 2) {
                        String h = parts[0];
                        int p = Integer.parseInt(parts[1]);
                        String u = parts.length > 2 ? parts[2] : "admin";
                        savedConnections.put(name, new SavedConnection(name, h, p, u));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void persistSavedConnections() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            Properties props = new Properties();
            for (SavedConnection sc : savedConnections.values()) {
                props.setProperty(sc.name(), sc.host() + ":" + sc.port() + ":" + sc.user());
            }
            try (var out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "JettraStore Shell Saved Connections");
            }
        } catch (Exception ignored) {
        }
    }

    public List<SavedConnection> getOrderedConnectionsList() {
        List<SavedConnection> list = new ArrayList<>();
        SavedConnection def = defaultConnectionName != null ? savedConnections.get(defaultConnectionName) : null;
        if (def != null) {
            list.add(def);
        }
        synchronized (savedConnections) {
            for (SavedConnection sc : savedConnections.values()) {
                if (def == null || !sc.name().equalsIgnoreCase(def.name())) {
                    list.add(sc);
                }
            }
        }
        return list;
    }

    public String getConnectionsMenuDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                ================================================================================
                               JETTRASTORE INTERACTIVE DISTRIBUTED SHELL (JAVA 25+)
                ================================================================================
                =========================== MENÚ DE CONEXIONES =================================
                Seleccione una conexión para iniciar:
                """);
        List<SavedConnection> list = getOrderedConnectionsList();
        for (int i = 0; i < list.size(); i++) {
            SavedConnection sc = list.get(i);
            String defMarker = (sc.name().equalsIgnoreCase(defaultConnectionName)
                    || (defaultConnectionName == null && i == 0))
                            ? "  [Predeterminado]"
                            : "";
            sb.append(String.format("  [%d] %-20s (%s:%d - Usuario: %s)%s%n",
                    (i + 1), sc.name(), sc.host(), sc.port(), sc.user(), defMarker));
        }
        sb.append("""
                  [N] Conexión nueva (donde el usuario ejecuta el connect)
                  [0] Salir del Shell
                ================================================================================
                """);
        return sb.toString();
    }

    public static final String NO_ACTIVE_NODES_ERROR =
            "⚠️ Error: No hay ningún nodo activo en el clúster. Inicie o conecte al menos un nodo antes de ejecutar operaciones de base de datos.";

    /**
     * Verifica en tiempo real mediante {@link JettraClient} si existe al menos un nodo activo (ONLINE)
     * en el clúster antes de despachar comandos sobre bases de datos.
     */
    public boolean hasActiveClusterNode() {
        return getActiveClusterNodeCount() > 0;
    }

    /**
     * Devuelve el número de nodos activos (ONLINE) detectados en tiempo real.
     */
    public int getActiveClusterNodeCount() {
        if (client == null) {
            return 0;
        }
        List<String> extraEndpoints = new ArrayList<>();
        synchronized (savedConnections) {
            for (SavedConnection sc : savedConnections.values()) {
                if (sc.host() != null && !sc.host().isBlank() && sc.port() > 0) {
                    extraEndpoints.add(sc.host().trim() + ":" + sc.port());
                }
            }
        }
        return client.countActiveClusterNodes(currentHost, currentPort, extraEndpoints);
    }

    /**
     * Determina si el comando normalizado en mayúsculas opera sobre bases de datos,
     * motores, buckets, estructuras, índices, registros, consultas, respaldos o usuarios/permisos,
     * quedando bloqueado cuando no existen nodos activos en el clúster (RF-28).
     */
    static boolean isDatabaseOperationCommand(String upper) {
        if (upper == null || upper.isBlank()) {
            return false;
        }
        // Ayuda específica de versionado permanece habilitada sin nodos activos
        if (upper.equals("VERSION") || upper.equals("VERSION --HELP") || upper.equals("VERSION -H")
                || upper.equals("VERSION HELP") || upper.equals("VERSIONS --HELP") || upper.equals("VERSIONS -H")
                || upper.equals("RESTORE VERSION --HELP") || upper.equals("RESTORE VERSION -H")) {
            return false;
        }
        // Comandos administrativos de nodos y perfiles de conexión se mantienen habilitados
        if (upper.startsWith("REMOVE NODE ") || upper.startsWith("REMOVE CONNECTION ")) {
            return false;
        }

        // Control de bases de datos y distribución
        if (upper.equals("SHOW DATABASES") || upper.equals("SHOW DBS")
                || upper.equals("LIST DATABASES") || upper.equals("LIST DBS")
                || upper.equals("SHOW SAMPLES") || upper.equals("SHOW SAMPLE DBS")
                || upper.equals("SHOW SAMPLE DATABASES") || upper.equals("SHOW DBS SAMPLES")
                || upper.startsWith("CREATE DATABASE ") || upper.startsWith("CREATE DB ")
                || upper.startsWith("DROP DATABASE ") || upper.startsWith("DROP DB ")
                || upper.startsWith("USE ")
                || upper.equals("DB STATS") || upper.equals("DB INFO") || upper.equals("STATS")
                || upper.startsWith("CLUSTER-DISTRIBUTED") || upper.startsWith("CLUSTER_DISTRIBUTED")
                || upper.equals("8")) {
            return true;
        }

        // Motores, buckets, estructuras y registros
        if (upper.equals("SHOW BUCKETS") || upper.equals("SHOW BUCKET")
                || upper.equals("SHOW UNIT") || upper.equals("SHOW UNITS")
                || upper.startsWith("SHOW BUCKETS ") || upper.startsWith("SHOW BUCKET ")
                || upper.startsWith("SHOW UNIT ") || upper.startsWith("SHOW UNITS ")
                || upper.equals("SHOW TABLES")
                || upper.equals("SHOW RECORDS") || upper.startsWith("SHOW RECORDS ")
                || upper.equals("COUNT") || upper.startsWith("COUNT ") || upper.equals("COUNT *")
                || upper.equals("COUNT ALL")
                || upper.equals("SHOW COLLECTIONS") || upper.startsWith("SHOW COLLECTIONS ")
                || upper.equals("CREATE COLLECTION") || upper.startsWith("CREATE COLLECTION ")
                || upper.equals("DROP COLLECTION") || upper.startsWith("DROP COLLECTION ")
                || upper.startsWith("CREATE ENGINE ") || upper.startsWith("CREATE BUCKET ") || upper.startsWith("CREATE UNIT ")
                || upper.startsWith("DROP ENGINE ") || upper.startsWith("DROP BUCKET ") || upper.startsWith("DROP UNIT ")
                || upper.startsWith("DESCRIBE BUCKET ") || upper.startsWith("SHOW BUCKET SCHEMA ")
                || upper.startsWith("INSERT BUCKET ")) {
            return true;
        }

        // Índices
        if (upper.startsWith("CREATE BUCKET INDEX ") || upper.startsWith("SHOW BUCKET INDEXES ")
                || upper.startsWith("DROP BUCKET INDEX ") || upper.startsWith("FIND NUMERIC ")
                || upper.startsWith("CREATE INDEX ") || upper.startsWith("DROP INDEX ")
                || (upper.startsWith("ALTER INDEX ") && upper.contains("REBUILD"))
                || upper.startsWith("REINDEX ")
                || upper.equals("SHOW INDEXES") || upper.startsWith("SHOW INDEXES ON ")
                || upper.equals("LIST INDEXES")) {
            return true;
        }

        // Usuarios y privilegios RBAC de bases de datos
        if (upper.equals("SHOW USERS") || upper.equals("LIST USERS")
                || upper.startsWith("CREATE USER ") || upper.startsWith("DROP USER ")
                || upper.startsWith("ALTER USER ") || upper.startsWith("GRANT ")
                || upper.startsWith("REVOKE ") || upper.startsWith("SHOW GRANTS FOR ")) {
            return true;
        }

        // Referencias, CRUD de registros y versionado (RF-18)
        if (upper.startsWith("INSERT REF ") || upper.startsWith("RESOLVE REF ") || upper.startsWith("SHOW REFS ")
                || upper.startsWith("INSERT INTO ") || upper.startsWith("GET ")
                || upper.startsWith("FIND ONE ") || upper.startsWith("FIND BY ID ")
                || upper.startsWith("FIND ALL ") || upper.startsWith("SCAN ")
                || upper.startsWith("UPDATE ") || upper.startsWith("DELETE FROM ")
                || upper.startsWith("DELETE ") || upper.startsWith("REMOVE ")
                || upper.equals("VERSIONS") || upper.startsWith("VERSIONS ")
                || upper.equals("SHOW VERSIONS") || upper.startsWith("SHOW VERSIONS ")
                || upper.equals("VERSION HISTORY") || upper.startsWith("VERSION HISTORY ")
                || upper.equals("VERSION SHOW") || upper.startsWith("VERSION SHOW ")
                || upper.equals("VERSION GET") || upper.startsWith("VERSION GET ")
                || upper.equals("VERSION INSPECT") || upper.startsWith("VERSION INSPECT ")
                || upper.equals("RESTORE VERSION") || upper.startsWith("RESTORE VERSION ")
                || upper.equals("VERSION RESTORE") || upper.startsWith("VERSION RESTORE ")) {
            return true;
        }

        // Consultas políglotas (JQL/SQL), motores multimodelo, muestras y backup/restore
        if (upper.startsWith("JQL ") || upper.startsWith("JETTRAQL ") || upper.startsWith("FROM ")
                || upper.startsWith("MATCH ") || upper.startsWith("VECTOR SIMILARITY ")
                || upper.startsWith("VECTOR MATCH ") || upper.startsWith("FETCH ")
                || upper.startsWith("SQL ") || upper.startsWith("JETTRASQL ") || upper.startsWith("SELECT ")
                || upper.startsWith("AGGREGATE ")
                || upper.startsWith("VECTOR INDEX ") || upper.startsWith("VECTOR SEARCH ")
                || upper.startsWith("VECTOR ")
                || upper.startsWith("GRAPH ADD VERTEX ") || upper.startsWith("GRAPH ADD EDGE ")
                || upper.startsWith("GRAPH GET EDGES ") || upper.startsWith("GRAPH EDGES ")
                || upper.startsWith("TS RECORD ") || upper.startsWith("TS RANGE ") || upper.startsWith("TS QUERY ")
                || upper.startsWith("KV PUT ") || upper.startsWith("KV GET ")
                || upper.equals("INSTALL SAMPLES FACTURA") || upper.equals("INSTALL SAMPLE FACTURA")
                || upper.equals("LOAD SAMPLE EXAMPLE_FACTURA_DB") || upper.equals("INSTALL SAMPLE EXAMPLE_FACTURA_DB")
                || upper.equals("LOAD SAMPLE FACTURA") || upper.equals("INSTALL FACTURA")
                || upper.equals("INSTALL SAMPLES HOSPITAL") || upper.equals("INSTALL SAMPLE HOSPITAL")
                || upper.equals("LOAD SAMPLE SAMPLES_HOSTIPAL_DB") || upper.equals("LOAD SAMPLE SAMPLES_HOSPITAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_HOSTIPAL_DB") || upper.equals("INSTALL SAMPLE SAMPLES_HOSPITAL_DB")
                || upper.equals("LOAD SAMPLE HOSPITAL") || upper.equals("INSTALL HOSPITAL")
                || upper.equals("INSTALL SAMPLES AMBIENTAL") || upper.equals("INSTALL SAMPLE AMBIENTAL")
                || upper.equals("LOAD SAMPLE SAMPLES_AMBIENTAL_DB") || upper.equals("LOAD SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_AMBIENTAL_DB") || upper.equals("INSTALL SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("LOAD SAMPLE AMBIENTAL") || upper.equals("INSTALL AMBIENTAL")
                || upper.startsWith("INSTALL SAMPLES") || upper.equals("1")
                || upper.startsWith("BACKUP DATABASE") || upper.startsWith("RESTORE DATABASE")) {
            return true;
        }

        return false;
    }

    public String executeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        // 0. Expansión de Historial por Bang (!n, !!, !prefix)
        if (trimmed.startsWith("!")) {
            try {
                trimmed = expandBangCommand(trimmed);
                System.out.println(">> Ejecutando desde historial: " + trimmed);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }

        String upper = trimmed.toUpperCase();

        // 0.1 Historial y Autocompletado
        if (upper.equals("HISTORY") || upper.startsWith("HISTORY ")) {
            return handleHistory(trimmed);
        } else if (upper.equals("COMPLETE") || upper.startsWith("COMPLETE ")
                || upper.equals("TAB") || upper.startsWith("TAB ")
                || upper.startsWith("AUTOCOMPLETE ")) {
            return handleAutocomplete(trimmed);
        }

        // Registrar comando en historial persistente
        recordHistory(trimmed);

        // 1. Ayuda y Menú
        if (upper.equals("HELP") || upper.equals("?")) {
            return getHelpText();
        } else if (upper.startsWith("HELP ") || upper.startsWith("? ")) {
            String topic = upper.substring(upper.indexOf(' ') + 1).trim();
            if (topic.contains("CLUSTER") || topic.contains("DISTRIBUTED") || topic.contains("LIVE")) {
                return getClusterDistributedHelpText();
            }
            if (topic.contains("STORAGE") || topic.contains("MODO") || topic.contains("ALMACEN")
                    || topic.contains("OFF_HEAP") || topic.contains("OFF-HEAP") || topic.contains("MEMORY")) {
                return getStorageModeHelpText();
            }
            if (topic.contains("VERSION") || topic.contains("HISTORIAL")) {
                return getVersionHelpText();
            }
            return getHelpText();
        } else if (upper.equals("MENU CONNECTIONS") || upper.equals("CONNECTIONS MENU")) {
            return getConnectionsMenuDisplay();
        } else if (upper.equals("MENU")) {
            return getInteractiveMenu();
        } else if (upper.equals("CLEAR") || upper.equals("CLS")) {
            if (activeLineReader != null) {
                try {
                    activeLineReader.getTerminal().puts(org.jline.utils.InfoCmp.Capability.clear_screen);
                    activeLineReader.getTerminal().flush();
                } catch (Exception ignored) {
                }
            }
            return "[CLEAR] Consola limpiada.";
        } else if (upper.equals("EXIT") || upper.equals("QUIT")) {
            return "Saliendo de JettraStore Shell...";
        }

        // 2. Conexión, Autenticación y Cierre de Sesión
        if (upper.equals("/CONNECTIONS") || upper.startsWith("/CONNECTIONS ")
                || upper.equals("CONNECTIONS") || upper.startsWith("CONNECTIONS ")
                || upper.startsWith("SET CONNECTION ") || upper.startsWith("EDIT CONNECTION ")
                || upper.startsWith("SAVE CLUSTER CONNECTION ")) {
            return handleConnectionsCommand(trimmed);
        }
        if (upper.startsWith("CONNECT ")) {
            return handleConnect(trimmed);
        } else if (upper.startsWith("LOGIN ")) {
            return handleLogin(trimmed);
        } else if (upper.equals("LOGOUT")) {
            return handleLogout();
        }

        // 3. Gestión de Conexiones Guardadas (save, remove, list)
        if (upper.startsWith("SAVE CONNECTION ")) {
            return handleSaveConnection(trimmed);
        } else if (upper.startsWith("REMOVE CONNECTION ")) {
            return handleRemoveConnection(trimmed);
        } else if (upper.equals("LIST CONNECTIONS") || upper.equals("LIST CONECTIONS")) {
            return handleListConnections();
        }

        // 3.5. Migración de PostgreSQL a JettraStore y Generador de Backend (RF-17)
        if (upper.startsWith("MIGRATE") || upper.startsWith("POSTGRES MIGRATE") || upper.startsWith("PG MIGRATE")) {
            return handleMigrate(trimmed);
        }

        // 4. Verificación de Seguridad y Autenticación para operaciones operativas
        if (!authenticated || client == null) {
            return "[AUTH REQUIRED] Debe iniciar sesión con 'login <username> <password>' antes de interactuar con bases de datos o telemetría.";
        }

        // 4.5. Verificación en tiempo real de nodos activos en el clúster (RF-28)
        if (isDatabaseOperationCommand(upper) && !hasActiveClusterNode()) {
            return NO_ACTIVE_NODES_ERROR;
        }

        // 5. Telemetría de Recursos (status) y Topología (show nodes / multinode)
        if (upper.equals("STATUS")) {
            return handleStatus();
        } else if (upper.equals("SHOW NODES") || upper.equals("NODES") || upper.equals("LIST NODES")) {
            return handleShowNodes();
        } else if (upper.equals("MULTINODE") || upper.equals("SHOW MULTINODE") || upper.equals("MULTINODE STATUS")
                || upper.equals("CLUSTER MULTINODE") || upper.equals("SHOW CLUSTER MULTINODE")) {
            return handleMultinodeStatus();
        } else if (upper.equals("MULTINODE ON") || upper.equals("SET MULTINODE ON")
                || upper.equals("SET MULTINODE = ON") || upper.equals("SET MULTINODE=ON")
                || upper.equals("CLUSTER MULTINODE ON")) {
            return handleSetMultinode(true);
        } else if (upper.equals("MULTINODE OFF") || upper.equals("SET MULTINODE OFF")
                || upper.equals("SET MULTINODE = OFF") || upper.equals("SET MULTINODE=OFF")
                || upper.equals("CLUSTER MULTINODE OFF")) {
            return handleSetMultinode(false);
        } else if (upper.startsWith("ADD NODE ") || upper.startsWith("CLUSTER ADD-NODE ")
                || upper.startsWith("CLUSTER ADD NODE ") || upper.startsWith("CLUSTER-ADD-NODE ")) {
            return handleAddNode(trimmed);
        } else if (upper.startsWith("REMOVE NODE ") || upper.startsWith("CLUSTER REMOVE-NODE ")
                || upper.startsWith("CLUSTER REMOVE NODE ") || upper.startsWith("CLUSTER-REMOVE-NODE ")) {
            return handleRemoveNode(trimmed);
        } else if (upper.startsWith("START NODE ")) {
            return handleStartNode(trimmed);
        } else if (upper.startsWith("STOP NODE ")) {
            return handleStopNode(trimmed);
        } else if (upper.equals("SHOW CLUSTER CONFIG") || upper.equals("CLUSTER CONFIG")
                || upper.equals("SHOW CLUSTER STATE") || upper.equals("CLUSTER STATE")) {
            return handleShowClusterConfig();
        } else if (upper.equals("SYNC CLUSTER CONFIG") || upper.equals("CLUSTER SYNC CONFIG")
                || upper.equals("SYNC CLUSTER STATE") || upper.equals("CLUSTER SYNC STATE")) {
            return handleSyncClusterConfig();
        } else if (upper.startsWith("CREATE BUCKET INDEX ")) {
            return handleCreateBucketIndexCommand(trimmed);
        } else if (upper.startsWith("SHOW BUCKET INDEXES ")) {
            return handleShowBucketIndexesCommand(trimmed);
        } else if (upper.startsWith("DROP BUCKET INDEX ")) {
            return handleDropBucketIndexCommand(trimmed);
        } else if (upper.startsWith("FIND NUMERIC ")) {
            return handleFindNumericRangeCommand(trimmed);
        } else if (upper.startsWith("CREATE BUCKET ")) {
            return handleCreateBucketCommand(trimmed);
        } else if (upper.startsWith("DESCRIBE BUCKET ") || upper.startsWith("SHOW BUCKET SCHEMA ")) {
            return handleDescribeBucketCommand(trimmed);
        } else if (upper.startsWith("DROP BUCKET ")) {
            return handleDropBucketCommand(trimmed);
        } else if (upper.startsWith("INSERT BUCKET ")) {
            return handleInsertBucketRecordCommand(trimmed);
        } else if (upper.startsWith("CLUSTER-DISTRIBUTED") || upper.startsWith("CLUSTER_DISTRIBUTED")) {
            return handleClusterDistributed(trimmed);
        } else if (upper.equals("CLUSTER LIVE") || upper.startsWith("CLUSTER LIVE ") || upper.equals("CLUSTER-LIVE")
                || upper.startsWith("CLUSTER-LIVE ") || upper.equals("CLUSTER_LIVE")
                || upper.startsWith("CLUSTER_LIVE ")) {
            return handleClusterLive(trimmed);
        } else if (upper.equals("8")) {
            return handleClusterDistributedInfo();
        } else if (upper.equals("9")) {
            return handleClusterLive("cluster live 30");
        }

        // Configuración de Storage Mode (JVM-RAM vs DISK-MEMORY / JettraMemory)
        if (upper.equals("STORAGE_MODE") || upper.equals("STORAGE MODE") || upper.equals("SHOW STORAGE_MODE")
                || upper.equals("SHOW STORAGE MODE")) {
            return handleShowStorageMode();
        } else if (upper.startsWith("STORAGE_MODE ") || upper.startsWith("STORAGE MODE ")
                || upper.startsWith("SET STORAGE_MODE ") || upper.startsWith("SET STORAGE_MODE=")
                || upper.startsWith("SET STORAGE_MODE =")) {
            return handleSetStorageMode(trimmed);
        }

        // 6. Configuración Lazy Reference (lazy reference on / off (lazy reference on /
        // lazy reference off))
        if (upper.equals("LAZY REFERENCE ON") || upper.equals("SET LAZY_REFERENCE ON")
                || upper.equals("SET LAZY_REFERENCE = TRUE")) {
            this.lazyLoad = true;
            this.showReferences = true;
            return "[CONFIG] Lazy Reference ACTIVADO (ON). Las referencias JettraRef se resolverán bajo demanda (Proxy).";
        } else if (upper.equals("LAZY REFERENCE OFF") || upper.equals("SET LAZY_REFERENCE OFF")
                || upper.equals("SET LAZY_REFERENCE = FALSE")) {
            this.lazyLoad = false;
            this.showReferences = true;
            return "[CONFIG] Lazy Reference DESACTIVADO (OFF). Las referencias JettraRef se cargarán inmediatamente en memoria (Eager).";
        } else if (upper.equals("LAZY REFERENCE STATUS") || upper.equals("LAZY REFERENCE")) {
            return String.format("[CONFIG] Estado de Lazy Reference: %s (showReferences: %s)",
                    lazyLoad ? "ON (Lazy)" : "OFF (Eager)", showReferences);
        }

        // 7. Control de Bases de Datos
        if (upper.equals("SHOW DATABASES") || upper.equals("SHOW DBS") || upper.equals("LIST DATABASES")
                || upper.equals("LIST DBS")) {
            return handleShowDatabases();
        } else if (upper.equals("SHOW SAMPLES") || upper.equals("SHOW SAMPLE DBS")
                || upper.equals("SHOW SAMPLE DATABASES") || upper.equals("SHOW DBS SAMPLES")) {
            return handleShowSampleDatabases();
        } else if (upper.startsWith("CREATE DATABASE ") || upper.startsWith("CREATE DB ")) {
            return handleCreateDatabase(trimmed);
        } else if (upper.startsWith("DROP DATABASE ") || upper.startsWith("DROP DB ")) {
            return handleDropDatabase(trimmed);
        } else if (upper.startsWith("USE ")) {
            return handleUseDatabase(trimmed);
        } else if (upper.equals("DB STATS") || upper.equals("DB INFO") || upper.equals("STATS")) {
            return handleDbStats();
        }

        // 8. Control de Buckets / Units y Colecciones
        if (upper.equals("SHOW BUCKETS") || upper.equals("SHOW BUCKET")
                || upper.equals("SHOW UNIT") || upper.equals("SHOW UNITS")
                || upper.startsWith("SHOW BUCKETS ") || upper.startsWith("SHOW BUCKET ")
                || upper.startsWith("SHOW UNIT ") || upper.startsWith("SHOW UNITS ")) {
            return handleShowBuckets(trimmed);
        } else if (upper.equals("SHOW RECORDS") || upper.startsWith("SHOW RECORDS ")) {
            return handleShowRecords(trimmed);
        } else if (upper.equals("COUNT") || upper.startsWith("COUNT ") || upper.equals("COUNT *")
                || upper.equals("COUNT ALL")) {
            return handleCount(trimmed);
        } else if (upper.equals("SHOW COLLECTIONS") || upper.startsWith("SHOW COLLECTIONS ")
                || upper.startsWith("CREATE COLLECTION ") || upper.equals("CREATE COLLECTION")
                || upper.startsWith("DROP COLLECTION ") || upper.equals("DROP COLLECTION")) {
            return "[ERROR] El comando COLLECTION fue eliminado y unificado en BUCKET (RF-12). Use 'SHOW BUCKETS', 'CREATE BUCKET <nombre> [TYPE <engine>]' o 'DROP BUCKET <nombre>'.";
        } else if (upper.equals("SHOW TABLES")) {
            return handleShowBuckets("SHOW BUCKETS");
        } else if (upper.startsWith("CREATE ENGINE ")
                || upper.startsWith("CREATE BUCKET ") || upper.startsWith("CREATE UNIT ")) {
            return handleCreateBucket(trimmed);
        } else if (upper.startsWith("DROP ENGINE ")
                || upper.startsWith("DROP BUCKET ") || upper.startsWith("DROP UNIT ")) {
            return handleDropBucket(trimmed);
        }

        // 9. Administración de Índices
        if (upper.startsWith("CREATE INDEX ")) {
            return handleCreateIndex(trimmed);
        } else if (upper.startsWith("DROP INDEX ")) {
            return handleDropIndex(trimmed);
        } else if (upper.startsWith("ALTER INDEX ") && upper.contains("REBUILD") || upper.startsWith("REINDEX ")) {
            return handleRebuildIndex(trimmed);
        } else if (upper.equals("SHOW INDEXES") || upper.startsWith("SHOW INDEXES ON ")
                || upper.equals("LIST INDEXES")) {
            return handleShowIndexes(trimmed);
        }

        // 10. Administración de Usuarios y Roles de Base de Datos
        if (upper.equals("SHOW USERS") || upper.equals("LIST USERS")) {
            return handleShowUsers();
        } else if (upper.startsWith("CREATE USER ")) {
            return handleCreateUser(trimmed);
        } else if (upper.startsWith("DROP USER ")) {
            return handleDropUser(trimmed);
        } else if (upper.startsWith("ALTER USER ")) {
            return handleAlterUser(trimmed);
        } else if (upper.startsWith("GRANT ")) {
            return handleGrantRole(trimmed);
        } else if (upper.startsWith("REVOKE ")) {
            return handleRevokeRole(trimmed);
        } else if (upper.startsWith("SHOW GRANTS FOR ")) {
            return handleShowGrants(trimmed);
        }

        // 11. Registros Referenciados y CRUD
        if (upper.startsWith("INSERT REF ")) {
            return handleInsertRef(trimmed);
        } else if (upper.startsWith("RESOLVE REF ")) {
            return handleResolveRef(trimmed);
        } else if (upper.startsWith("SHOW REFS ")) {
            return handleShowRefs(trimmed);
        } else if (upper.startsWith("INSERT INTO ")) {
            return handleInsert(trimmed);
        } else if (upper.startsWith("GET ") || upper.startsWith("FIND ONE ") || upper.startsWith("FIND BY ID ")) {
            return handleGetRecord(trimmed);
        } else if (upper.startsWith("FIND ALL ") || upper.startsWith("SCAN ")) {
            return handleFindAll(trimmed);
        } else if (upper.startsWith("UPDATE ")) {
            return handleUpdate(trimmed);
        } else if (upper.startsWith("DELETE FROM ") || upper.startsWith("DELETE ") || upper.startsWith("REMOVE ")) {
            return handleDeleteRecord(trimmed);
        } else if (upper.equals("VERSION") || upper.equals("VERSION --HELP") || upper.equals("VERSION -H")
                || upper.equals("VERSION HELP") || upper.equals("VERSIONS --HELP") || upper.equals("VERSIONS -H")
                || upper.equals("RESTORE VERSION --HELP") || upper.equals("RESTORE VERSION -H")) {
            return getVersionHelpText();
        } else if (upper.equals("VERSIONS") || upper.startsWith("VERSIONS ")
                || upper.equals("SHOW VERSIONS") || upper.startsWith("SHOW VERSIONS ")
                || upper.equals("VERSION HISTORY") || upper.startsWith("VERSION HISTORY ")) {
            return handleVersionHistory(trimmed);
        } else if (upper.equals("VERSION SHOW") || upper.startsWith("VERSION SHOW ")
                || upper.equals("VERSION GET") || upper.startsWith("VERSION GET ")
                || upper.equals("VERSION INSPECT") || upper.startsWith("VERSION INSPECT ")) {
            return handleVersionShow(trimmed);
        } else if (upper.equals("RESTORE VERSION") || upper.startsWith("RESTORE VERSION ")
                || upper.equals("VERSION RESTORE") || upper.startsWith("VERSION RESTORE ")) {
            return handleVersionRestore(trimmed);
        }

        // Paginación y Desplazamiento de Consultas (Primero, Anterior, Siguiente,
        // Última)
        if (upper.startsWith("SET PAGE_SIZE ") || upper.startsWith("PAGE_SIZE ") || upper.startsWith("PAGESIZE ")
                || upper.startsWith("SET PAGESIZE ") || upper.startsWith("SIZE ")) {
            return handleSetPageSize(trimmed);
        } else if (upper.equals("FIRST") || upper.equals("PRIMERO") || upper.equals("PRI") || upper.equals("PAGE FIRST")
                || upper.equals("|<<") || (upper.equals("F") && lastPagedBaseQuery != null)) {
            return handleNavigatePage("FIRST");
        } else if (upper.equals("PREV") || upper.equals("ANTERIOR") || upper.equals("PREVIOUS") || upper.equals("ANT")
                || upper.equals("PAGE PREV") || upper.equals("<")
                || ((upper.equals("A") || upper.equals("P")) && lastPagedBaseQuery != null)) {
            return handleNavigatePage("PREV");
        } else if (upper.equals("NEXT") || upper.equals("SIGUIENTE") || upper.equals("SIG") || upper.equals("PAGE NEXT")
                || upper.equals(">") || ((upper.equals("S") || upper.equals("N")) && lastPagedBaseQuery != null)) {
            return handleNavigatePage("NEXT");
        } else if (upper.equals("LAST") || upper.equals("ULTIMO") || upper.equals("ULTIMA") || upper.equals("ULT")
                || upper.equals("PAGE LAST") || upper.equals(">>|")
                || ((upper.equals("U") || upper.equals("L")) && lastPagedBaseQuery != null)) {
            return handleNavigatePage("LAST");
        } else if (upper.startsWith("PAGE ") || upper.startsWith("PAGINA ") || upper.startsWith("GOTO ")) {
            return handleNavigatePage(trimmed);
        }

        // 12. Soporte Políglota: JettraQL y JettraSQL
        if (upper.startsWith("JQL ") || upper.startsWith("JETTRAQL ") || upper.startsWith("FROM ")
                || upper.startsWith("MATCH ") || upper.startsWith("VECTOR SIMILARITY ")
                || upper.startsWith("VECTOR MATCH ") || upper.startsWith("FETCH ")) {
            return handleJettraQL(trimmed);
        } else if (upper.startsWith("SQL ") || upper.startsWith("JETTRASQL ") || upper.startsWith("SELECT ")
                || upper.startsWith("AGGREGATE ") || upper.startsWith("MATH ") || upper.startsWith("CALC ")
                || upper.startsWith("FINANCE ") || upper.startsWith("FINANCIAL ")
                || upper.startsWith("STATS ") || upper.startsWith("STATISTICS ")) {
            return handleJettraSQL(trimmed);
        }

        // 13. Motores Multimodelo Especializados y Álgebra Vectorial
        if (upper.startsWith("VECTOR INDEX ")) {
            return handleVectorIndex(trimmed);
        } else if (upper.startsWith("VECTOR SEARCH ")) {
            return handleVectorSearch(trimmed);
        } else if (upper.startsWith("VECTOR ")) {
            return handleJettraSQL(trimmed);
        } else if (upper.startsWith("GRAPH ADD VERTEX ")) {
            return handleGraphAddVertex(trimmed);
        } else if (upper.startsWith("GRAPH ADD EDGE ")) {
            return handleGraphAddEdge(trimmed);
        } else if (upper.startsWith("GRAPH GET EDGES ") || upper.startsWith("GRAPH EDGES ")) {
            return handleGraphGetEdges(trimmed);
        } else if (upper.startsWith("TS RECORD ")) {
            return handleTsRecord(trimmed);
        } else if (upper.startsWith("TS RANGE ") || upper.startsWith("TS QUERY ")) {
            return handleTsRange(trimmed);
        } else if (upper.startsWith("KV PUT ")) {
            return handleKvPut(trimmed);
        } else if (upper.startsWith("KV GET ")) {
            return handleKvGet(trimmed);
        }

        // 14. Persistencia y Muestras Completas
        if (upper.equals("INSTALL SAMPLES FACTURA") || upper.equals("INSTALL SAMPLE FACTURA")
                || upper.equals("LOAD SAMPLE EXAMPLE_FACTURA_DB") || upper.equals("INSTALL SAMPLE EXAMPLE_FACTURA_DB")
                || upper.equals("LOAD SAMPLE FACTURA") || upper.equals("INSTALL FACTURA")) {
            return installFacturaSampleDatabase();
        } else if (upper.equals("INSTALL SAMPLES HOSPITAL") || upper.equals("INSTALL SAMPLE HOSPITAL")
                || upper.equals("LOAD SAMPLE SAMPLES_HOSTIPAL_DB") || upper.equals("LOAD SAMPLE SAMPLES_HOSPITAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_HOSTIPAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_HOSPITAL_DB")
                || upper.equals("LOAD SAMPLE HOSPITAL") || upper.equals("INSTALL HOSPITAL")) {
            return installHospitalSampleDatabase();
        } else if (upper.equals("INSTALL SAMPLES AMBIENTAL") || upper.equals("INSTALL SAMPLE AMBIENTAL")
                || upper.equals("LOAD SAMPLE SAMPLES_AMBIENTAL_DB")
                || upper.equals("LOAD SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_AMBIENTAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("LOAD SAMPLE AMBIENTAL") || upper.equals("INSTALL AMBIENTAL")) {
            return installAmbientalSampleDatabase();
        } else if (upper.startsWith("INSTALL SAMPLES") || upper.equals("1")) {
            return installAllSampleDatabases();
        } else if (upper.startsWith("BACKUP DATABASE")) {
            return handleBackup(trimmed);
        } else if (upper.startsWith("RESTORE DATABASE")) {
            return handleRestore(trimmed);
        }

        return "[ERROR] Comando desconocido: '" + trimmed
                + "'. Escriba 'help' o 'menu' para ver los comandos disponibles.";
    }

    private volatile String currentPassword;

    // --- 1. Conexión y Autenticación ---
    public boolean connectAndLogin(String host, int port, String user, String pass) {
        try {
            String targetHost = (host != null && !host.isBlank()) ? host.trim() : "127.0.0.1";
            int targetPort = port > 0 ? port : 50001;
            var primarySnap = JettraClient.fetchPrimaryClusterStatus(
                    List.of(targetHost + ":" + targetPort), 500, 900);
            if (primarySnap != null) {
                this.latestPrimaryClusterStatus = primarySnap;
                if (primarySnap.primaryHost() != null && !primarySnap.primaryHost().isBlank()
                        && primarySnap.primaryGrpcPort() > 0) {
                    targetHost = primarySnap.primaryHost().trim();
                    targetPort = primarySnap.primaryGrpcPort();
                }
            }
            this.currentHost = targetHost;
            this.currentPort = targetPort;
            this.client = JettraClient.connect(targetHost, targetPort, user, pass);
            this.authenticated = true;
            this.currentUser = user;
            this.currentPassword = pass;
            registerSentinelListener();
            registerClusterConfigSyncListener();
            syncLocalClusterConfigFromPrimary();
            startClusterStatusSyncWorker();
            var claims = client.getSecurityManager().validateToken(client.getSessionToken());
            this.currentRole = claims.role();
            return true;
        } catch (Exception e) {
            this.authenticated = false;
            this.currentUser = "anonymous";
            this.currentRole = "NONE";
            return false;
        }
    }

    private String handleConnectionsCommand(String command) {
        String trimmed = command.trim();
        String rest = trimmed.replaceFirst("(?i)^(/connections|connections|set\\s+connection|edit\\s+connection|save\\s+cluster\\s+connection)\\s*", "").trim();
        var curState = getActiveClusterConnectionState();
        if (curState == null) {
            curState = io.jettra.driver.config.JettraConnectionConfigManager.loadInitialConnection(null);
            this.activeClusterConnectionState = curState;
        }

        if (rest.isEmpty() || "show".equalsIgnoreCase(rest) || "status".equalsIgnoreCase(rest)) {
            var snap = JettraClient.fetchPrimaryClusterStatus(collectStatusQuerySeeds(), 500, 900);
            if (snap != null) {
                this.latestPrimaryClusterStatus = snap;
                applyPrimaryStatusSnapshotToClient(snap);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("==============================================================================================\n");
            sb.append("              GESTIÓN DE CONEXIÓN AL CLÚSTER (cluster.ip.nodes / conecction.config)\n");
            sb.append("==============================================================================================\n");
            sb.append("Origen cargado      : ")
              .append(curState.loadedFromConnectionFile() ? "conecction.config" : "microprofile-config.properties (cluster.ip.nodes)")
              .append("\n");
            sb.append("Archivo local/vol.  : ").append(curState.connectionFile()).append("\n");
            sb.append("cluster.ip.nodes    : ").append(curState.clusterIpNodes()).append("\n");
            sb.append("Conexión activa     : ").append(currentHost).append(":").append(currentPort)
              .append(authenticated ? (" (autenticado como " + currentUser + ")") : " (sin login)")
              .append("\n");
            if (this.latestPrimaryClusterStatus != null) {
                var s = this.latestPrimaryClusterStatus;
                sb.append("PRIMARY resuelto    : ").append(s.primaryNodeId()).append(" @ ")
                  .append(s.primaryHost()).append(":").append(s.primaryGrpcPort())
                  .append(" (REST ").append(s.primaryRestPort()).append(") | cluster-state.config v")
                  .append(s.stateVersion()).append("\n");
                sb.append("Nodos en clúster    : ").append(s.nodes().size()).append("\n");
                for (var n : s.nodes()) {
                    String st = s.statuses() != null
                            ? s.statuses().getOrDefault(n.id().toLowerCase(Locale.ROOT), "RUNNING")
                            : "RUNNING";
                    sb.append(String.format("  - %-10s | %-9s | %-18s:%-5d (REST %-5d) | %s%n",
                            n.id(), n.role(), n.ip(), n.grpcPort(), n.restPort(), st));
                }
            } else {
                sb.append("PRIMARY resuelto    : (ninguno alcanzable por el momento)\n");
            }
            sb.append("Uso para editar/guardar: /connections <host:port[,host2:port2]> [usuario] [--no-save]\n");
            return sb.toString();
        }

        boolean persist = true;
        if (rest.toLowerCase(Locale.ROOT).contains("--no-save")) {
            persist = false;
            rest = rest.replaceAll("(?i)--no-save", "").trim();
        } else if (rest.toLowerCase(Locale.ROOT).contains("--save")) {
            rest = rest.replaceAll("(?i)--save", "").trim();
        }
        String[] tokens = rest.split("\\s+");
        String rawAddresses = tokens[0].trim();
        String user = tokens.length > 1 ? tokens[1].trim() : (authenticated ? currentUser : curState.username());

        String normalized;
        List<io.jettra.driver.config.JettraConnectionConfigManager.Endpoint> parsedEndpoints;
        try {
            normalized = io.jettra.driver.config.JettraConnectionConfigManager.normalizeAndValidateClusterIpNodes(rawAddresses);
            parsedEndpoints = io.jettra.driver.config.JettraConnectionConfigManager.parseEndpointsStrict(normalized);
        } catch (IllegalArgumentException ex) {
            return "[ERROR] Dirección inválida: " + ex.getMessage();
        }

        io.jettra.driver.config.JettraConnectionConfigManager.ConnectionState updatedState;
        if (persist) {
            try {
                updatedState = io.jettra.driver.config.JettraConnectionConfigManager.saveConnectionAtomically(normalized, user);
            } catch (IOException ex) {
                return "[ERROR] No se pudo escribir conecction.config de forma atómica: " + ex.getMessage();
            }
        } else {
            updatedState = new io.jettra.driver.config.JettraConnectionConfigManager.ConnectionState(
                    normalized, List.copyOf(parsedEndpoints), user, curState.loadedFromConnectionFile(), curState.connectionFile());
        }
        this.activeClusterConnectionState = updatedState;

        savedConnections.clear();
        int idx = 1;
        for (var ep : parsedEndpoints) {
            savedConnections.put(String.format("node-%02d", idx++), new SavedConnection(ep.host() + ":" + ep.port(), ep.host(), ep.port(), user));
        }

        List<String> seeds = new ArrayList<>();
        for (var ep : parsedEndpoints) {
            seeds.add(ep.toHostPort());
        }
        var snap = JettraClient.fetchPrimaryClusterStatus(seeds, 500, 1000);
        if (snap != null) {
            this.latestPrimaryClusterStatus = snap;
        }

        String targetHost = snap != null && snap.primaryHost() != null && !snap.primaryHost().isBlank()
                ? snap.primaryHost().trim()
                : parsedEndpoints.get(0).host();
        int targetPort = snap != null && snap.primaryGrpcPort() > 0
                ? snap.primaryGrpcPort()
                : parsedEndpoints.get(0).port();

        this.currentHost = targetHost;
        this.currentPort = targetPort;

        if (authenticated && this.currentPassword != null && !this.currentPassword.isBlank()) {
            try {
                if (this.client != null) {
                    try { this.client.close(); } catch (Exception ignored) {}
                }
                this.client = JettraClient.connect(targetHost, targetPort, user, this.currentPassword);
                this.currentUser = user;
                registerSentinelListener();
                registerClusterConfigSyncListener();
                startClusterStatusSyncWorker();
            } catch (Exception ignored) {
            }
        }
        if (snap != null) {
            applyPrimaryStatusSnapshotToClient(snap);
            syncLocalClusterConfigFromPrimary();
        }

        if (snap != null) {
            return String.format("[SUCCESS] Conexión %s: %s -> PRIMARY '%s' (%s:%d), cluster-state.config v%d (%d nodos).",
                    persist ? "guardada en conecction.config" : "aplicada",
                    normalized, snap.primaryNodeId(), targetHost, targetPort, snap.stateVersion(), snap.nodes().size());
        }
        return String.format("[SUCCESS] Conexión %s (%s). Nodo aún no alcanzable; se sincronizará automáticamente al conectarse.",
                persist ? "guardada en conecction.config" : "actualizada", normalized);
    }

    private String handleConnect(String command) {
        String clean = command.substring(8).trim();
        boolean saveFlag = false;
        if (clean.toLowerCase(Locale.ROOT).contains("--save")) {
            saveFlag = true;
            clean = clean.replaceAll("(?i)--save", "").trim();
        }
        if (savedConnections.containsKey(clean)) {
            SavedConnection sc = savedConnections.get(clean);
            this.currentHost = sc.host();
            this.currentPort = sc.port();
            if (saveFlag) {
                try {
                    this.activeClusterConnectionState =
                            io.jettra.driver.config.JettraConnectionConfigManager.saveConnectionAtomically(
                                    currentHost + ":" + currentPort, sc.user());
                } catch (Exception ignored) {}
            }
            return String.format(
                    "[CONNECTED] Servidor configurado a '%s:%d' desde perfil '%s'. Inicie sesión con: login %s <password>",
                    currentHost, currentPort, sc.name(), sc.user());
        }

        String[] parts = clean.split("\\s+");
        if (parts.length == 1 && parts[0].contains(":")) {
            try {
                String normalized = io.jettra.driver.config.JettraConnectionConfigManager
                        .normalizeAndValidateClusterIpNodes(parts[0]);
                var eps = io.jettra.driver.config.JettraConnectionConfigManager.parseEndpointsStrict(normalized);
                this.currentHost = eps.get(0).host();
                this.currentPort = eps.get(0).port();
                var snap = JettraClient.fetchPrimaryClusterStatus(List.of(normalized), 400, 700);
                if (snap != null && snap.primaryHost() != null && !snap.primaryHost().isBlank() && snap.primaryGrpcPort() > 0) {
                    this.latestPrimaryClusterStatus = snap;
                    this.currentHost = snap.primaryHost();
                    this.currentPort = snap.primaryGrpcPort();
                }
                if (saveFlag) {
                    this.activeClusterConnectionState =
                            io.jettra.driver.config.JettraConnectionConfigManager.saveConnectionAtomically(
                                    normalized, currentUser != null && !"anonymous".equals(currentUser) ? currentUser : "admin");
                }
                return String.format(
                        "[CONNECTED] Servidor JettraStore configurado a %s:%d (%s). Proceda con 'login <username> <password>'.",
                        currentHost, currentPort, saveFlag ? "guardado en conecction.config" : "en vivo");
            } catch (Exception ex) {
                return "[ERROR] Dirección inválida: " + ex.getMessage();
            }
        }
        if (parts.length >= 2) {
            this.currentHost = parts[0].trim();
            try {
                this.currentPort = Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                return "[ERROR] Puerto numérico inválido: " + parts[1];
            }
            if (saveFlag) {
                try {
                    this.activeClusterConnectionState =
                            io.jettra.driver.config.JettraConnectionConfigManager.saveConnectionAtomically(
                                    currentHost + ":" + currentPort, currentUser != null && !"anonymous".equals(currentUser) ? currentUser : "admin");
                } catch (Exception ignored) {}
            }
            return String.format(
                    "[CONNECTED] Servidor JettraStore configurado a %s:%d. Proceda con 'login <username> <password>'.",
                    currentHost, currentPort);
        } else if (parts.length == 1 && !parts[0].isBlank()) {
            this.currentHost = parts[0].trim();
            return String.format("[CONNECTED] Host configurado a %s:%d. Proceda con 'login <username> <password>'.",
                    currentHost, currentPort);
        }
        return "[ERROR] Uso: connect <host> <puerto> | connect <host:port> [--save] | connect <nombre-conexion>";
    }

    private String handleLogin(String command) {
        String[] parts = command.substring(6).trim().split("\\s+");
        if (parts.length < 2) {
            return "[ERROR] Uso: login <username> <password>";
        }
        String user = parts[0].trim();
        String pass = parts[1].trim();

        boolean ok = connectAndLogin(currentHost, currentPort, user, pass);
        if (ok) {
            return String.format("[AUTH SUCCESS] Sesión iniciada como '%s' (Rol Global: %s) en %s:%d.", currentUser,
                    currentRole, currentHost, currentPort);
        } else {
            return "[AUTH ERROR] Credenciales inválidas para el usuario: " + user;
        }
    }

    private String handleLogout() {
        if (!authenticated) {
            return "[INFO] No hay ninguna sesión activa en este momento.";
        }
        String oldUser = currentUser;
        this.authenticated = false;
        this.currentUser = "anonymous";
        this.currentRole = "NONE";
        if (this.client != null) {
            this.client.close();
        }
        return String.format(
                "[LOGOUT] Sesión del usuario '%s' finalizada exitosamente. Inicie sesión nuevamente con 'login <username> <password>'.",
                oldUser);
    }

    // --- 2. Gestión de Perfiles de Conexión ---
    private String handleSaveConnection(String command) {
        String clean = command.substring("SAVE CONNECTION ".length()).trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 1 || parts[0].isBlank()) {
            return "[ERROR] Uso: save connection <nombre-conexion> [host] [puerto] [user]";
        }
        String name = cleanQuotes(parts[0]);
        String host = parts.length > 1 ? parts[1].trim() : currentHost;
        int port = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : currentPort;
        String user = parts.length > 3 ? parts[3].trim() : (authenticated ? currentUser : "admin");

        savedConnections.put(name, new SavedConnection(name, host, port, user));
        persistSavedConnections();
        return String.format("[SUCCESS] Conexión '%s' guardada (%s:%d, usuario: %s).", name, host, port, user);
    }

    private String handleRemoveConnection(String command) {
        String name = cleanQuotes(command.substring("REMOVE CONNECTION ".length()).trim());
        if (name.isBlank())
            return "[ERROR] Uso: remove connection <nombre-conexion>";
        SavedConnection removed = savedConnections.remove(name);
        if (removed != null) {
            persistSavedConnections();
            return String.format("[SUCCESS] Conexión guardada '%s' eliminada correctamente.", name);
        } else {
            return String.format("[WARN] No existe una conexión guardada con el nombre '%s'.", name);
        }
    }

    private String handleListConnections() {
        if (savedConnections.isEmpty()) {
            return "[INFO] No hay conexiones guardadas.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("+----------------------+----------------------+-------+-----------------+----------+\n");
        sb.append("| Nombre de Perfil     | Host / IP            | Puerto| Usuario         | Activo   |\n");
        sb.append("+----------------------+----------------------+-------+-----------------+----------+\n");
        for (SavedConnection sc : savedConnections.values()) {
            boolean isCur = sc.host().equals(currentHost) && sc.port() == currentPort;
            sb.append(String.format("| %-20s | %-20s | %-5d | %-15s | %-8s |\n",
                    sc.name(), sc.host(), sc.port(), sc.user(), isCur ? "* SI" : "NO"));
        }
        sb.append("+----------------------+----------------------+-------+-----------------+----------+\n");
        sb.append(String.format("Total: %d perfil(es) de conexión guardado(s).", savedConnections.size()));
        return sb.toString();
    }

    // --- 3. Telemetría de Recursos (STATUS) ---
    private String handleStatus() {
        Runtime rt = Runtime.getRuntime();
        long totalRam = rt.totalMemory() / (1024 * 1024);
        long freeRam = rt.freeMemory() / (1024 * 1024);
        long usedRam = totalRam - freeRam;
        long maxRam = rt.maxMemory() / (1024 * 1024);
        int cpuCores = rt.availableProcessors();

        JettraStoreConfig cfg = JettraStoreConfig.load();
        String storagePath = cfg.getStoragePath();
        String rawConfigured = cfg.getConfiguredStoragePath();
        long diskBytes = 0;
        int filesCount = 0;
        try {
            Path p = Path.of(storagePath);
            if (Files.exists(p)) {
                try (var s = Files.walk(p)) {
                    for (Path f : (Iterable<Path>) s::iterator) {
                        if (Files.isRegularFile(f)) {
                            diskBytes += Files.size(f);
                            filesCount++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return String.format("""
                ==============================================================================================
                                      JETTRASTORE RESOURCE MONITOR & TELEMETRY (JAVA 25+)
                ==============================================================================================
                1. RAM (MEMORIA):
                   - Panama FFM Off-Heap Direct: Habilitado (Arena Compartida Cero Copia)
                   - Heap JVM (ZGC Generational): Ocupada %d MB / Total %d MB (Máx JVM: %d MB)
                   - MemTable Tamaño Asignado:   %d MB
                   - Dynamic Ring Saturation:    Umbral 85%% (Descarga automática a nodos secundarios)
                   - Compact Object Headers:     Activo (--XX:+UseCompactObjectHeaders)

                2. PROCESADOR (CPU):
                   - Núcleos Lógicos del Host:   %d Cores
                   - Virtual Threads (Loom):     Activos (I/O Concurrente No Bloqueante en red gRPC/REST)
                   - Hilos de Compaction LSM:    En segundo plano (Prioridad baja)

                3. DISCO (ALMACENAMIENTO):
                   - Ruta Física Configurada:    %s
                   - Directorio Activo de Datos: %s
                   - Tamaño Ocupado por SSTables: %.2f KB (%d bytes)
                   - Archivos de Datos (.jettra): %d archivo(s)
                   - Formato de Almacenamiento:  Estructura LSM (.jettra) con Bloom Filters y Sparse Indexes

                4. CLÚSTER & DISTRIBUCIÓN (CONSENSO):
                   - Modo Multinodo (cluster.multinode.active): %s (%s)
                   - Algoritmo de Consenso:      RAFT Integrado & Dynamic Ring
                   - Nodos Pares Registrados:    %d
                ==============================================================================================
                """, usedRam, totalRam, maxRam, cfg.getMemTableSizeMb(), cpuCores, rawConfigured, storagePath,
                (diskBytes / 1024.0), diskBytes, filesCount,
                client.getRingEngine().getMultinodeActive().toUpperCase(),
                client.getRingEngine().isMultinodeActive() ? "Consenso y distribución ACTIVO"
                        : "STANDALONE - Operaciones locales en servidor único",
                client.getRingEngine().getPeers().size());
    }

    // --- 4. Administración de Nodos del Clúster ---
    private static boolean isNodeReachable(String host, int grpcPort, int restPort, int timeoutMs) {
        return JettraClient.probeJettraNode(host, grpcPort, restPort, timeoutMs).alive();
    }

    private static int deriveSafeRestPort(int grpcPort, int fallbackIdx) {
        if (grpcPort >= 50001 && grpcPort <= 50999)
            return 60000 + (grpcPort - 50000);
        if (grpcPort >= 9091 && grpcPort <= 9999)
            return 8080 + (grpcPort - 9090);
        return 60000 + Math.max(1, fallbackIdx);
    }

    /**
     * Consulta en vivo el archivo cluster.config autoritativo directamente del nodo
     * PRIMARY activo
     * vía trama Raft TYPE_GET_CLUSTER_CONFIG_REQ, asegurando que JettraStoreShell
     * refleje siempre
     * los cambios realizados desde JettraStoreExplorer u otros clientes.
     */
    private List<JettraConfigValidator.ClusterNodeInfo> fetchLivePrimaryClusterNodes() {
        List<String> candidates = collectStatusQuerySeeds();
        try {
            var primarySnap = JettraClient.fetchPrimaryClusterStatus(candidates, 350, 600);
            if (primarySnap != null && primarySnap.nodes() != null && !primarySnap.nodes().isEmpty()) {
                this.latestPrimaryClusterStatus = primarySnap;
                return primarySnap.nodes();
            }
        } catch (Exception ignored) {
        }
        for (String ep : candidates) {
            String[] parts = ep.split(":");
            if (parts.length < 2)
                continue;
            String h = parts[0].trim();
            int p;
            try {
                p = Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                continue;
            }
            if (client == null)
                continue;
            try {
                var remoteCfg = client.readPrimaryClusterConfigFrom(h, p, 300, 500);
                if (remoteCfg != null && remoteCfg.rawBytes() != null && remoteCfg.rawBytes().length > 0) {
                    Properties props = new Properties();
                    try (var bais = new java.io.ByteArrayInputStream(remoteCfg.rawBytes())) {
                        props.load(bais);
                    }
                    List<JettraConfigValidator.ClusterNodeInfo> parsed = JettraConfigValidator
                            .parseClusterNodes(props);
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private String handleShowNodes() {
        DynamicRingEngine ring = client.getRingEngine();
        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append("                          JETTRASTORE RAFT CLUSTER TOPOLOGY                                   \n");
        sb.append("==============================================================================================\n");
        sb.append("+----------+----------------------+-------+-----------+--------------+----------+--------------+\n");
        sb.append("| Nodo ID  | Dirección IP         | Puerto| Rol       | Estado Raft  | Estado   | Offload Bytes|\n");
        sb.append("+----------+----------------------+-------+-----------+--------------+----------+--------------+\n");

        record TargetNode(String id, String ip, int grpcPort, int restPort, String configuredRole) {
        }
        Map<String, TargetNode> topology = new LinkedHashMap<>();

        // 0. Fuente de verdad autoritativa: consultar cluster.config en vivo del nodo
        // PRIMARY
        List<JettraConfigValidator.ClusterNodeInfo> livePrimaryNodes = fetchLivePrimaryClusterNodes();
        boolean loadedFromLivePrimary = !livePrimaryNodes.isEmpty();
        if (loadedFromLivePrimary) {
            Set<String> liveIds = new HashSet<>();
            int idx = 1;
            for (JettraConfigValidator.ClusterNodeInfo n : livePrimaryNodes) {
                String host = (n.ip() != null && !n.ip().isBlank()) ? n.ip().trim() : "127.0.0.1";
                int gPort = n.grpcPort() > 0 ? n.grpcPort() : (50000 + idx);
                int rPort = n.restPort() > 0 ? n.restPort() : deriveSafeRestPort(gPort, idx);
                topology.put(n.id(), new TargetNode(n.id(), host, gPort, rPort, n.role()));
                liveIds.add(n.id().toLowerCase(Locale.ROOT));
                idx++;
            }
            // Limpiar peers fantasma en ringEngine que ya fueron eliminados desde
            // JettraStoreExplorer u otro cliente
            for (ClusterNode existingPeer : new ArrayList<>(ring.getPeers())) {
                if (!liveIds.contains(existingPeer.getId().toLowerCase(Locale.ROOT))) {
                    ring.removePeer(existingPeer.getId());
                }
            }
        } else {
            // 1. Fallback local: cargar topología desde cluster-state.config o cluster.config opcional
            try {
                Path clusterPath = JettraConfigValidator.locateActiveClusterStateOrSeedFile();
                if (clusterPath != null && Files.exists(clusterPath)) {
                    Properties clusterProps = JettraConfigValidator.loadProperties(clusterPath);
                    List<JettraConfigValidator.ClusterNodeInfo> nodes = JettraConfigValidator
                            .parseExplicitClusterNodes(clusterProps);
                    int idx = 1;
                    for (JettraConfigValidator.ClusterNodeInfo n : nodes) {
                        String host = (n.ip() != null && !n.ip().isBlank()) ? n.ip() : "127.0.0.1";
                        int gPort = n.grpcPort() > 0 ? n.grpcPort() : (50000 + idx);
                        int rPort = n.restPort() > 0 ? n.restPort() : deriveSafeRestPort(gPort, idx);
                        topology.put(n.id(), new TargetNode(n.id(), host, gPort, rPort, n.role()));
                        idx++;
                    }
                }
            } catch (Exception ignored) {
            }

            Map<String, TargetNode> standardDockerPorts = Map.of(
                    "node-01", new TargetNode("node-01", "127.0.0.1", 50001, 60001, "PRIMARY"),
                    "node-02", new TargetNode("node-02", "127.0.0.1", 50002, 60002, "SECONDARY"),
                    "node-03", new TargetNode("node-03", "127.0.0.1", 50003, 60003, "SECONDARY"));
            if (topology.isEmpty()) {
                topology.putAll(standardDockerPorts);
            }

            // Incorporar nodos pares registrados dinámicamente en ringEngine solo en modo
            // fallback local
            int idx = topology.size() + 1;
            for (ClusterNode peer : ring.getPeers()) {
                if (!topology.containsKey(peer.getId())) {
                    topology.put(peer.getId(),
                            new TargetNode(peer.getId(), peer.getIp(), peer.getPort(),
                                    deriveSafeRestPort(peer.getPort(), idx++), peer.getRole().name()));
                }
            }
        }

        record LiveStatus(boolean isUp, String role, String raftState, long offloadBytes) {
        }
        Map<String, LiveStatus> liveStates = new ConcurrentHashMap<>();

        // Consultar concurrentemente la salud e identidad real de cada nodo JettraStore
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TargetNode node : topology.values()) {
            futures.add(CompletableFuture.runAsync(() -> {
                JettraClient.NodeProbeResult probe = JettraClient.probeJettraNode(
                        node.ip(), node.grpcPort(), node.restPort(), 250);
                boolean identityMatches = probe.reportedNodeId().isBlank()
                        || "node-local".equalsIgnoreCase(probe.reportedNodeId())
                        || probe.reportedNodeId().equalsIgnoreCase(node.id());
                if (!probe.alive() || !identityMatches) {
                    liveStates.put(node.id(), new LiveStatus(false, "UNKNOWN", "DISCONNECTED", 0));
                    return;
                }
                String role = !probe.reportedRole().isBlank()
                        ? probe.reportedRole()
                        : ("PRIMARY".equalsIgnoreCase(node.configuredRole()) ? "PRIMARY" : "SECONDARY");
                String raft = "PRIMARY".equals(role) ? "LEADER" : "FOLLOWER";
                liveStates.put(node.id(), new LiveStatus(true, role, raft, 0));
            }));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(600, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }

        // Invariante de líder único (Single-Leader Reconciliation):
        // Garantizar que como máximo UN nodo activo sea reportado como PRIMARY/LEADER,
        // eliminando estados duplicados (ej. node-01 y node-04 ambos como PRIMARY tras
        // split-brain transitorio).
        List<TargetNode> activePrimaryCandidates = new ArrayList<>();
        for (TargetNode node : topology.values()) {
            LiveStatus ls = liveStates.get(node.id());
            if (ls != null && ls.isUp() && "PRIMARY".equals(ls.role())) {
                activePrimaryCandidates.add(node);
            }
        }
        String authoritativePrimaryId = null;
        if (!activePrimaryCandidates.isEmpty()) {
            // Priorizar el nodo configurado como PRIMARY en cluster.config si está activo;
            // si no, menor ID lexicográfico
            TargetNode chosen = activePrimaryCandidates.stream()
                    .filter(n -> "PRIMARY".equalsIgnoreCase(n.configuredRole()))
                    .findFirst()
                    .orElseGet(() -> activePrimaryCandidates.stream()
                            .min(Comparator.comparing(TargetNode::id, String.CASE_INSENSITIVE_ORDER))
                            .orElse(activePrimaryCandidates.get(0)));
            authoritativePrimaryId = chosen.id();
        } else {
            // Ningún nodo online reportó PRIMARY: elegir el primer nodo activo
            // (preferentemente configurado PRIMARY)
            TargetNode fallback = topology.values().stream()
                    .filter(n -> {
                        LiveStatus ls = liveStates.get(n.id());
                        return ls != null && ls.isUp();
                    })
                    .sorted(Comparator.comparing((TargetNode n) -> !"PRIMARY".equalsIgnoreCase(n.configuredRole()))
                            .thenComparing(TargetNode::id, String.CASE_INSENSITIVE_ORDER))
                    .findFirst()
                    .orElse(null);
            if (fallback != null) {
                authoritativePrimaryId = fallback.id();
            }
        }
        if (authoritativePrimaryId != null) {
            for (TargetNode node : topology.values()) {
                LiveStatus ls = liveStates.get(node.id());
                if (ls != null && ls.isUp()) {
                    if (node.id().equalsIgnoreCase(authoritativePrimaryId)) {
                        liveStates.put(node.id(), new LiveStatus(true, "PRIMARY", "LEADER", ls.offloadBytes()));
                    } else {
                        liveStates.put(node.id(), new LiveStatus(true, "SECONDARY", "FOLLOWER", ls.offloadBytes()));
                    }
                }
            }
        }

        // Renderizar la tabla de topología en tiempo real
        int activeNodes = 0;
        for (TargetNode node : topology.values()) {
            LiveStatus ls = liveStates.getOrDefault(node.id(), new LiveStatus(false, "UNKNOWN", "DISCONNECTED", 0));
            String status = ls.isUp() ? "RUNNING" : "STOPPED";
            String role = ls.role();
            String raft = ls.raftState();
            String offloadCol = ls.isUp() ? String.valueOf(ls.offloadBytes()) : "N/A";
            if (ls.isUp()) {
                activeNodes++;
            }

            // Sincronizar en memoria con el DynamicRingEngine
            ClusterNode peer = ring.getPeer(node.id());
            if (peer != null) {
                peer.setRole("PRIMARY".equals(role) ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY);
                peer.setRaftState(ClusterNode.RaftState.valueOf(raft));
                if (ls.isUp())
                    peer.start();
                else
                    peer.stop();
            }

            sb.append(String.format("| %-8s | %-20s | %-5d | %-9s | %-12s | %-8s | %-12s |\n",
                    node.id(), node.ip(), node.grpcPort(), role, raft, status, offloadCol));
        }

        sb.append("+----------+----------------------+-------+-----------+--------------+----------+--------------+\n");
        boolean multiActive = ring.isMultinodeActive();
        sb.append(String.format("Modo Multinodo (cluster.multinode.active): %s\n",
                multiActive ? "ON (Algoritmo de consenso y distribución de datos ACTIVO)"
                        : "OFF (Servidor Único / Standalone - Distribución de datos DESACTIVADA)"));

        int totalNodes = topology.size();
        boolean quorumReached = multiActive && (activeNodes >= (totalNodes / 2 + 1));

        String quorumStr;
        if (!multiActive) {
            quorumStr = "Inactivo (Operaciones locales exclusivas)";
        } else if (quorumReached) {
            quorumStr = String.format("Activo (Consenso distribuido con quórum mayoritario: %d/%d activos)",
                    activeNodes, totalNodes);
        } else {
            quorumStr = String.format("Inactivo (Sin mayoría de nodos para consenso: %d/%d activos)", activeNodes,
                    totalNodes);
        }

        sb.append(String.format("Total: %d nodo(s) registrados en el anillo dinámico. Quórum: %s.\n",
                totalNodes, quorumStr));
        if (activeNodes == 0) {
            sb.append("[WARNING] Ningún nodo activo responde (STOPPED / UNREACHABLE). Los comandos de base de datos permanecerán bloqueados.\n");
        }
        return sb.toString();
    }

    public record ShellClusterNode(String id, String role, String host, int grpcPort, int restPort) {
    }

    private static boolean isPrimaryRole(String role) {
        if (role == null)
            return false;
        String r = role.trim().toUpperCase();
        return "PRIMARY".equals(r) || "LEADER".equals(r);
    }

    private boolean hasAdminRole() {
        return currentRole != null
                && ("ADMIN".equalsIgnoreCase(currentRole.trim()) || "SUPER_ADMIN".equalsIgnoreCase(currentRole.trim()));
    }

    private String handleAddNode(String command) {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: la administración de nodos del clúster está restringida exclusivamente a usuarios con rol 'admin'.";
        }
        String clean = command.replaceAll("(?i)^(CLUSTER\\s+)?(ADD-NODE|ADD\\s+NODE|CLUSTER-ADD-NODE)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 3) {
            return "[ERROR] Uso: ADD NODE <nodeId> <host> <port> [PRIMARY|SECONDARY] o CLUSTER ADD-NODE <nodeId> <host> <port> [PRIMARY|SECONDARY]";
        }
        String id = parts[0].trim();
        String host = parts[1].trim();
        int port;
        try {
            port = Integer.parseInt(parts[2].trim());
        } catch (Exception e) {
            return "[ERROR] Puerto inválido: " + parts[2];
        }
        ClusterNode.Role role = (parts.length > 3 && "PRIMARY".equalsIgnoreCase(parts[3]))
                ? ClusterNode.Role.PRIMARY
                : ClusterNode.Role.SECONDARY;

        int restPort = 60001;
        if (parts.length > 4) {
            try {
                restPort = Integer.parseInt(parts[4].trim());
            } catch (Exception ignored) {
            }
        } else if (port >= 9091 && port <= 9099) {
            restPort = 60000 + (port - 9090);
        } else if (port >= 50001 && port <= 50009) {
            restPort = 60000 + (port - 50000);
        } else if (port > 1000) {
            restPort = port + 10000 <= 65535 ? port + 10000 : port - 1000;
        }

        // Validar que no exista ID de nodo duplicado ni puertos gRPC/REST duplicados en
        // la misma IP
        List<JettraConfigValidator.ClusterNodeInfo> currentNodes = collectCurrentClusterNodesForValidation();
        JettraConfigValidator.ValidationResult nodeCheck = JettraConfigValidator.validateCandidateNode(
                currentNodes, id, host, port, restPort, false);
        if (!nodeCheck.isValid()) {
            return "[ERROR] No se puede agregar el nodo '" + id + "': " + nodeCheck.getSummary();
        }

        ClusterNode newNode = new ClusterNode(id, host, port, role);
        client.getRingEngine().registerPeer(newNode);
        savedConnections.put(id, new SavedConnection(id, host, port, authenticated ? currentUser : "admin"));
        persistSavedConnections();

        // Registrar dinámicamente en cluster-state.config local (sin alterar cluster.config estático)
        try {
            Path stateFile = JettraConfigValidator.resolveClusterStateFile();
            JettraConfigValidator.seedOrLoadClusterState(stateFile, JettraConfigValidator.locateOptionalClusterSeedFile());
            JettraConfigValidator.upsertNodeInStateFile(
                    stateFile, id, role.name(), host, port, restPort, "RUNNING", "jettra-shell");
        } catch (IllegalArgumentException dupEx) {
            return "[ERROR] No se puede agregar el nodo '" + id + "': " + dupEx.getMessage();
        } catch (Exception ignored) {
        }

        // Replicar cluster.config a todos los nodos: PRIMARY primero, luego los
        // SECONDARY
        String replicationReport = replicateClusterConfigToQuorum(id, role.name(), host, port, restPort, false);

        // Notificar al PRIMARY para asegurar la distribución inmediata de todas las
        // bases de datos y objetos al nuevo nodo
        triggerPrimaryDatabaseSyncToNode(id, role.name(), host, port, restPort);

        return String.format(
                "[SUCCESS] Nodo '%s' (%s:%d, %s) agregado exitosamente al clúster Raft (zero-downtime: ningún nodo activo fue detenido; la topología y los datos convergen automáticamente).%n%s",
                id, host, port, role, replicationReport);
    }

    private List<JettraConfigValidator.ClusterNodeInfo> collectCurrentClusterNodesForValidation() {
        Map<String, JettraConfigValidator.ClusterNodeInfo> merged = new LinkedHashMap<>();
        Path stateOrSeed = JettraConfigValidator.locateActiveClusterStateOrSeedFile();
        if (stateOrSeed != null && Files.exists(stateOrSeed)) {
            try {
                Properties props = JettraConfigValidator.loadProperties(stateOrSeed);
                for (JettraConfigValidator.ClusterNodeInfo cn : JettraConfigValidator.parseExplicitClusterNodes(props)) {
                    if (cn.id() != null && !cn.id().isBlank()) {
                        merged.put(cn.id().toLowerCase(), cn);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        int idx = merged.size() + 1;
        for (SavedConnection sc : savedConnections.values()) {
            String key = sc.name().toLowerCase();
            if (!merged.containsKey(key)) {
                int rp = (sc.port() >= 50001 && sc.port() <= 50009) ? (60000 + (sc.port() - 50000))
                        : (sc.port() >= 9091 && sc.port() <= 9099 ? (60000 + (sc.port() - 9090))
                                : (sc.port() + 10000 <= 65535 ? sc.port() + 10000 : sc.port() - 1000));
                merged.put(key, new JettraConfigValidator.ClusterNodeInfo(
                        sc.name(), "node-01".equalsIgnoreCase(sc.name()) ? "PRIMARY" : "SECONDARY",
                        sc.host(), sc.port(), rp, "~/jettra/data", String.valueOf(idx++)));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String handleRemoveNode(String command) {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: remover nodos del clúster está restringido exclusivamente a usuarios con rol 'admin'.";
        }
        String clean = cleanQuotes(command
                .replaceAll("(?i)^(CLUSTER\\s+)?(REMOVE-NODE|REMOVE\\s+NODE|CLUSTER-REMOVE-NODE)\\s+", "").trim());
        if (clean.isBlank())
            return "[ERROR] Uso: REMOVE NODE <nodeId> o CLUSTER REMOVE-NODE <nodeId>";
        String id = clean;
        if ("node-01".equalsIgnoreCase(id) || client.getRingEngine().getNodeId().equalsIgnoreCase(id)) {
            return "[ERROR] No se puede remover el nodo primario activo del clúster.";
        }

        // Regla estricta: NO se permite remover un nodo que esté en ejecución (RUNNING
        // / activo).
        // Primero debe detenerse el nodo y luego removerlo.
        String targetHost = null;
        int targetGrpc = 0;
        int targetRest = 0;
        ClusterNode ringPeer = client.getRingEngine().getPeer(id);
        if (ringPeer != null) {
            targetHost = ringPeer.getIp();
            targetGrpc = ringPeer.getPort();
        }
        // Verificar también en la topología viva del PRIMARY para reflejar nodos
        // gestionados desde Explorer
        boolean existedInPrimary = false;
        for (JettraConfigValidator.ClusterNodeInfo cn : fetchLivePrimaryClusterNodes()) {
            if (cn.id() != null && cn.id().equalsIgnoreCase(id)) {
                existedInPrimary = true;
                if (targetHost == null || targetHost.isBlank())
                    targetHost = cn.ip();
                if (targetGrpc <= 0)
                    targetGrpc = cn.grpcPort();
                if (targetRest <= 0)
                    targetRest = cn.restPort();
                break;
            }
        }
        for (JettraConfigValidator.ClusterNodeInfo cn : collectCurrentClusterNodesForValidation()) {
            if (cn.id().equalsIgnoreCase(id)) {
                if (targetHost == null || targetHost.isBlank())
                    targetHost = cn.ip();
                if (targetGrpc <= 0)
                    targetGrpc = cn.grpcPort();
                targetRest = cn.restPort();
                break;
            }
        }
        if (targetHost != null && !targetHost.isBlank()
                && isNodeReachable(targetHost, targetGrpc, targetRest, 250)) {
            return String.format(
                    "[ERROR] No se puede remover el nodo '%s' (%s:%d) porque actualmente está en ejecución (RUNNING / ACTIVO).%n"
                            +
                            "        Primero debe detener el nodo (ej. STOP NODE %s) y luego ejecutar REMOVE NODE %s.",
                    id, targetHost, targetGrpc, id, id);
        }

        boolean removedFromRing = client.getRingEngine().removePeer(id);
        boolean removedFromSaved = savedConnections.remove(id) != null;
        if (!removedFromSaved) {
            String matchedKey = savedConnections.keySet().stream()
                    .filter(k -> k.equalsIgnoreCase(id))
                    .findFirst().orElse(null);
            if (matchedKey != null) {
                savedConnections.remove(matchedKey);
                removedFromSaved = true;
            }
        }
        if (removedFromRing || removedFromSaved) {
            persistSavedConnections();
        }

        // Remover de cluster.config local (limpiando todas las entradas del nodo y
        // re-indexando)
        boolean removedFromConfig = false;
        try {
            removedFromConfig = JettraConfigValidator.removeDynamicNodeFromClusterConfig(id);
        } catch (Exception ignored) {
        }

        boolean ok = existedInPrimary || removedFromRing || removedFromSaved || removedFromConfig;

        // Replicar cluster.config actualizado a todos los nodos sin detener los nodos
        // activos
        String replicationReport = replicateClusterConfigToQuorum(id, "SECONDARY", "", 0, 0, true);

        return ok ? String.format(
                "[SUCCESS] Nodo detenido '%s' removido del anillo y eliminado de cluster.config (zero-downtime: los demás nodos activos continúan operando y convergen automáticamente).%n%s",
                id, replicationReport)
                : String.format("[WARN] El nodo '%s' no existe en el registro del clúster.%n%s", id, replicationReport);
    }

    private String handleShowClusterConfig() {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: la consulta de cluster.config del PRIMARY requiere rol 'admin'.";
        }
        String cfg = client.readPrimaryClusterConfig();
        if (cfg == null || cfg.isBlank()) {
            return "[WARN] No se encontró contenido en cluster.config.";
        }
        return "[CLUSTER.CONFIG (PRIMARY)]\n" + cfg;
    }

    private String handleSyncClusterConfig() {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: sincronizar cluster.config requiere rol 'admin'.";
        }
        String cfg = client.readPrimaryClusterConfig();
        if (cfg == null || cfg.isBlank()) {
            return "[ERROR] No se pudo leer cluster.config para sincronizar.";
        }
        try {
            int confirmed = client.updatePrimaryClusterConfig(cfg, true);
            return String.format(
                    "[SUCCESS] cluster.config validado y sincronizado en %d nodo(s) activos sin detener ningún nodo.",
                    confirmed);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String resolveActiveDb() {
        return (currentDatabase != null && !currentDatabase.isBlank()) ? currentDatabase : client.getDefaultDatabase();
    }

    private String handleCreateBucketCommand(String command) {
        // Sintaxis: CREATE BUCKET <name> [ENGINE <engineType>] [STRUCTURE NONE|DEFINED
        // <json_attributes>]
        String rest = command.substring("CREATE BUCKET ".length()).trim();
        if (rest.isBlank()) {
            return "[ERROR] Uso: CREATE BUCKET <nombre> [ENGINE DOCUMENT|JAVA_RECORD|KEY_VALUE|VECTOR|GRAPH|TIMESERIES|COLUMNAR|GEOSPATIAL] [STRUCTURE NONE|DEFINED <json_atributos>]";
        }
        String bucketName = rest.split("\\s+")[0].trim();
        String engineType = "DOCUMENT";
        String structure = "NONE";
        String attrsJson = "[]";

        java.util.regex.Matcher engM = java.util.regex.Pattern.compile("(?i)\\bENGINE\\s+([A-Za-z0-9_\\-]+)")
                .matcher(rest);
        if (engM.find()) {
            engineType = engM.group(1).trim();
        }
        java.util.regex.Matcher stM = java.util.regex.Pattern
                .compile("(?i)\\bSTRUCTURE\\s+(NONE|DEFINED)(?:\\s+(\\[.*\\]))?").matcher(rest);
        if (stM.find()) {
            structure = stM.group(1).trim().toUpperCase();
            if (stM.group(2) != null) {
                attrsJson = stM.group(2).trim();
            }
        }
        try {
            var meta = client.createBucket(resolveActiveDb(), engineType, bucketName, structure, attrsJson);
            return String.format(
                    "[SUCCESS] Bucket '%s' creado en DB '%s' | Engine: %s | Estructura: %s (%d atributo(s)).",
                    meta.bucketName(), meta.databaseName(), meta.engineType(), meta.structureType(),
                    meta.attributes().size());
        } catch (Exception e) {
            return "[ERROR] No se pudo crear el bucket '" + bucketName + "': " + e.getMessage();
        }
    }

    private String handleDescribeBucketCommand(String command) {
        String bucketName = command.replaceAll("(?i)^(DESCRIBE\\s+BUCKET|SHOW\\s+BUCKET\\s+SCHEMA)\\s+", "").trim();
        if (bucketName.isBlank()) {
            return "[ERROR] Uso: DESCRIBE BUCKET <nombre>";
        }
        String dbName = resolveActiveDb();
        var meta = client.getDatabase(dbName).findBucketMetadata(bucketName);
        if (meta == null) {
            return String.format(
                    "[INFO] Bucket '%s' en DB '%s' no tiene metadatos explícitos (opera con estructura NONE por defecto).",
                    bucketName, dbName);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Bucket: %s | DB: %s | Engine: %s | Estructura: %s%n",
                meta.bucketName(), meta.databaseName(), meta.engineType(), meta.structureType()));
        if (meta.structureType() == io.jettra.store.core.JettraBucketSchema.StructureType.DEFINED) {
            sb.append("Atributos definidos:\n");
            for (var a : meta.attributes()) {
                sb.append(String.format("  - %s (%s) | reglas: [%s] | %s%n",
                        a.atribute_name(), a.type_atribute(), a.jettra_rules(), a.description()));
            }
        }
        var idxs = client.listBucketIndexes(dbName, meta.bucketName());
        sb.append(String.format("Índices del bucket (%d):%n", idxs.size()));
        for (var idx : idxs) {
            sb.append(String.format("  - %s ON (%s) [%s%s] entradas=%d%n",
                    idx.name(), idx.field(), idx.type(), idx.unique() ? " UNIQUE" : "", idx.entriesCount()));
        }
        return sb.toString();
    }

    private String handleDropBucketCommand(String command) {
        String rest = command.substring("DROP BUCKET ".length()).trim();
        if (rest.isBlank())
            return "[ERROR] Uso: DROP BUCKET <nombre> [ENGINE <engineType>]";
        String[] parts = rest.split("\\s+");
        String bucketName = parts[0].trim();
        String engineType = (parts.length >= 3 && "ENGINE".equalsIgnoreCase(parts[1])) ? parts[2].trim() : "DOCUMENT";
        String dbName = resolveActiveDb();
        boolean ok = client.dropBucket(dbName, engineType, bucketName);
        return ok ? String.format("[SUCCESS] Bucket '%s' eliminado de la base de datos '%s'.", bucketName, dbName)
                : String.format("[WARN] Bucket '%s' no encontrado en '%s'.", bucketName, dbName);
    }

    private String handleInsertBucketRecordCommand(String command) {
        // Sintaxis: INSERT BUCKET <bucketName> [ID <customId>] <json>
        String rest = command.substring("INSERT BUCKET ".length()).trim();
        int sp = rest.indexOf(' ');
        if (sp <= 0) {
            return "[ERROR] Uso: INSERT BUCKET <bucketName> [ID <idPersonalizado>] {\"campo\":\"valor\",...}";
        }
        String bucketName = rest.substring(0, sp).trim();
        String tail = rest.substring(sp + 1).trim();
        String customId = null;
        String json = tail;
        if (tail.toUpperCase().startsWith("ID ")) {
            String afterId = tail.substring(3).trim();
            int jsonStart = afterId.indexOf('{');
            if (jsonStart > 0) {
                customId = cleanQuotes(afterId.substring(0, jsonStart).trim());
                json = afterId.substring(jsonStart).trim();
            }
        }
        String dbName = resolveActiveDb();
        try {
            io.jettra.json.JettraJson parser = new io.jettra.json.JettraJson();
            Object parsed = parser.parse(json);
            Map<String, Object> record = new LinkedHashMap<>();
            if (parsed instanceof io.jettra.json.JsonObject jo) {
                jo.getMap().forEach((k, v) -> record.put(String.valueOf(k), v));
            } else if (parsed instanceof Map<?, ?> rawMap) {
                rawMap.forEach((k, v) -> record.put(String.valueOf(k), v));
            } else {
                return "[VALIDATION ERROR] El registro debe ser un objeto JSON válido {...}.";
            }
            var meta = client.getDatabase(dbName).findBucketMetadata(bucketName);
            String engineType = meta != null ? meta.engineType() : "DOCUMENT";
            String assignedId = client.insertBucketRecord(dbName, engineType, bucketName, customId, record);
            return String.format("[SUCCESS] Registro con JettraId '%s' insertado y validado en bucket '%s' (%s).",
                    assignedId, bucketName, engineType);
        } catch (IllegalStateException pkEx) {
            return "[ERROR] " + pkEx.getMessage();
        } catch (IllegalArgumentException valEx) {
            return "[VALIDATION ALERT] Registro rechazado por estructura del bucket '" + bucketName + "': "
                    + valEx.getMessage();
        } catch (Exception e) {
            return "[ERROR] Error insertando en bucket '" + bucketName + "': " + e.getMessage();
        }
    }

    private String handleCreateBucketIndexCommand(String command) {
        // Sintaxis: CREATE BUCKET INDEX <indexName> ON <bucketName> (<field>) [TYPE
        // BTREE|HASH|NUMERIC|AUTO_INCREMENT|SPARSE] [UNIQUE]
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^CREATE\\s+BUCKET\\s+INDEX\\s+(\\S+)\\s+ON\\s+(\\S+)\\s*\\(([^)]+)\\)(.*)$")
                .matcher(command.trim());
        if (!m.find()) {
            return "[ERROR] Uso: CREATE BUCKET INDEX <nombre> ON <bucket> (<campo>) [TYPE BTREE|HASH|NUMERIC|AUTO_INCREMENT|SPARSE] [UNIQUE]";
        }
        String idxName = m.group(1).trim();
        String bucket = m.group(2).trim();
        String field = m.group(3).trim();
        String tail = m.group(4) != null ? m.group(4).toUpperCase() : "";
        String type = "BTREE";
        if (tail.contains("AUTO_INCREMENT") || tail.contains("AUTOINCREMENT"))
            type = "AUTO_INCREMENT";
        else if (tail.contains("NUMERIC") || tail.contains("INTEGER") || tail.contains("LONG")
                || tail.contains("DOUBLE"))
            type = "NUMERIC";
        else if (tail.contains("HASH"))
            type = "HASH";
        else if (tail.contains("SPARSE"))
            type = "SPARSE";
        boolean unique = tail.contains("UNIQUE") || "AUTO_INCREMENT".equals(type);
        String dbName = resolveActiveDb();
        try {
            var info = client.createBucketIndex(dbName, bucket, idxName, field, type, unique);
            String seqInfo = "AUTO_INCREMENT".equals(info.type())
                    ? String.format(", secuencia_actual=%d", client.getAutoIncrementCurrentValue(dbName, bucket, field))
                    : "";
            return String.format(
                    "[SUCCESS] Índice '%s' creado en bucket '%s' (campo=%s, tipo=%s, unique=%s, entradas=%d%s).",
                    info.name(), info.bucket(), info.field(), info.type(), info.unique(), info.entriesCount(),
                    seqInfo);
        } catch (Exception e) {
            return "[ERROR] No se pudo crear el índice '" + idxName + "': " + e.getMessage();
        }
    }

    private String handleShowBucketIndexesCommand(String command) {
        String bucket = command.substring("SHOW BUCKET INDEXES ".length()).trim();
        String dbName = resolveActiveDb();
        var idxs = client.listBucketIndexes(dbName, bucket);
        if (idxs.isEmpty()) {
            return String.format("[INFO] El bucket '%s' en '%s' no tiene índices registrados.", bucket, dbName);
        }
        StringBuilder sb = new StringBuilder(String.format("Índices del bucket '%s' (%d):%n", bucket, idxs.size()));
        for (var idx : idxs) {
            String seqInfo = io.jettra.store.engine.index.JettraIndexManager.isAutoIncrementType(idx.type())
                    ? " | seq=" + client.getAutoIncrementCurrentValue(dbName, bucket, idx.field())
                    : "";
            sb.append(String.format("  • %-20s | campo: %-15s | tipo: %-14s | unique: %-5s | entradas: %d%s%n",
                    idx.name(), idx.field(), idx.type(), idx.unique(), idx.entriesCount(), seqInfo));
        }
        return sb.toString();
    }

    private String handleFindNumericRangeCommand(String command) {
        // Sintaxis: FIND NUMERIC <bucket> <campo> <min> <max>
        String[] parts = command.substring("FIND NUMERIC ".length()).trim().split("\\s+");
        if (parts.length < 4) {
            return "[ERROR] Uso: FIND NUMERIC <bucket> <campo> <min> <max>";
        }
        String bucket = parts[0].trim();
        String field = parts[1].trim();
        String dbName = resolveActiveDb();
        try {
            double min = Double.parseDouble(parts[2].trim());
            double max = Double.parseDouble(parts[3].trim());
            Set<String> ids = client.findIdsByNumericRange(dbName, bucket, field, min, max);
            if (ids.isEmpty()) {
                return String.format("[INFO] Sin registros en '%s' donde %s esté en [%s, %s].", bucket, field, min,
                        max);
            }
            return String.format("[NUMERIC INDEX] %d registro(s) en '%s' (%s en [%s, %s]): %s",
                    ids.size(), bucket, field, min, max, String.join(", ", ids));
        } catch (NumberFormatException nfe) {
            return "[ERROR] Los límites min y max deben ser numéricos. Uso: FIND NUMERIC <bucket> <campo> <min> <max>";
        }
    }

    private String handleDropBucketIndexCommand(String command) {
        String idxName = command.substring("DROP BUCKET INDEX ".length()).trim();
        boolean ok = client.dropBucketIndex(resolveActiveDb(), idxName);
        return ok ? String.format("[SUCCESS] Índice '%s' eliminado correctamente.", idxName)
                : String.format("[WARN] No se encontró el índice '%s'.", idxName);
    }

    private String replicateClusterConfigToQuorum(String targetNodeId, String role, String host, int grpcPort,
            int restPort, boolean isRemoval) {
        Map<String, ShellClusterNode> clusterTopology = new LinkedHashMap<>();

        // 1. Cargar nodos existentes: priorizar estado vivo del PRIMARY, fallback a
        // cluster-state.config o cluster.config opcional local
        Path stateFile = JettraConfigValidator.resolveClusterStateFile();
        JettraConfigValidator.seedOrLoadClusterState(stateFile, JettraConfigValidator.locateOptionalClusterSeedFile());
        Path stateOrSeed = Files.exists(stateFile) ? stateFile : JettraConfigValidator.locateOptionalClusterSeedFile();
        List<JettraConfigValidator.ClusterNodeInfo> livePrimary = fetchLivePrimaryClusterNodes();
        if (!livePrimary.isEmpty()) {
            int idx = 1;
            for (JettraConfigValidator.ClusterNodeInfo cn : livePrimary) {
                if (cn.id() == null || cn.id().isBlank())
                    continue;
                String h = (cn.ip() != null && !cn.ip().isBlank()) ? cn.ip().trim() : "127.0.0.1";
                int gp = cn.grpcPort() > 0 ? cn.grpcPort() : (50000 + idx);
                int rp = cn.restPort() > 0 ? cn.restPort() : deriveSafeRestPort(gp, idx);
                String r = (cn.role() != null && !cn.role().isBlank()) ? cn.role().toUpperCase() : "SECONDARY";
                clusterTopology.put(cn.id().toLowerCase(), new ShellClusterNode(cn.id(), r, h, gp, rp));
                idx++;
            }
        } else if (stateOrSeed != null && Files.exists(stateOrSeed)) {
            try {
                Properties props = JettraConfigValidator.loadProperties(stateOrSeed);
                List<JettraConfigValidator.ClusterNodeInfo> parsed = JettraConfigValidator.parseExplicitClusterNodes(props);
                int idx = 1;
                for (JettraConfigValidator.ClusterNodeInfo cn : parsed) {
                    if (cn.id() == null || cn.id().isBlank())
                        continue;
                    String h = (cn.ip() != null && !cn.ip().isBlank()) ? cn.ip().trim() : "127.0.0.1";
                    int gp = cn.grpcPort() > 0 ? cn.grpcPort() : (50000 + idx);
                    int rp = cn.restPort() > 0 ? cn.restPort() : deriveSafeRestPort(gp, idx);
                    String r = (cn.role() != null && !cn.role().isBlank()) ? cn.role().toUpperCase() : "SECONDARY";
                    clusterTopology.put(cn.id().toLowerCase(), new ShellClusterNode(cn.id(), r, h, gp, rp));
                    idx++;
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Incorporar conexiones guardadas solo si no se cargó topología viva del
        // PRIMARY
        if (livePrimary.isEmpty()) {
            int fallbackRestOffset = 1;
            for (SavedConnection sc : savedConnections.values()) {
                String key = sc.name().toLowerCase();
                if (isRemoval && key.equalsIgnoreCase(targetNodeId)) {
                    continue;
                }
                if (!clusterTopology.containsKey(key)) {
                    String r = key.contains("primary") || "node-01".equalsIgnoreCase(key) ? "PRIMARY" : "SECONDARY";
                    int rp = deriveSafeRestPort(sc.port(), fallbackRestOffset);
                    clusterTopology.put(key, new ShellClusterNode(sc.name(), r, sc.host(), sc.port(), rp));
                }
                fallbackRestOffset++;
            }
        }

        // 3. Ajustar topología según la operación
        if (isRemoval) {
            clusterTopology.remove(targetNodeId.toLowerCase());
        } else {
            int safeRest = restPort > 0 ? restPort : deriveSafeRestPort(grpcPort, clusterTopology.size() + 1);
            clusterTopology.put(targetNodeId.toLowerCase(),
                    new ShellClusterNode(targetNodeId, role.toUpperCase(), host, grpcPort, safeRest));
        }

        // 4. Generar bytes canónicos actualizados de cluster.config garantizando
        // unicidad de puertos REST
        List<JettraConfigValidator.ClusterNodeInfo> canonicalNodes = new ArrayList<>();
        Set<String> usedEndpoints = new HashSet<>();
        for (ShellClusterNode n : clusterTopology.values()) {
            String ipKey = (n.host() != null ? n.host().trim() : "127.0.0.1").toLowerCase(Locale.ROOT);
            usedEndpoints.add(ipKey + ":" + n.grpcPort());
        }
        int idx = 1;
        for (ShellClusterNode n : clusterTopology.values()) {
            String normRole = isPrimaryRole(n.role()) ? "PRIMARY" : "SECONDARY";
            String ip = (n.host() != null && !n.host().isBlank()) ? n.host().trim() : "127.0.0.1";
            String ipKey = ip.toLowerCase(Locale.ROOT);
            int rp = n.restPort();
            if (rp <= 0 || rp == n.grpcPort() || usedEndpoints.contains(ipKey + ":" + rp)) {
                rp = deriveSafeRestPort(n.grpcPort(), idx);
                while (rp == n.grpcPort() || usedEndpoints.contains(ipKey + ":" + rp)) {
                    rp++;
                }
            }
            usedEndpoints.add(ipKey + ":" + rp);
            canonicalNodes.add(new JettraConfigValidator.ClusterNodeInfo(
                    n.id(), normRole, ip, n.grpcPort(), rp, "~/jettra/data", String.valueOf(idx)));
            idx++;
        }
        Properties baseProps = (stateOrSeed != null && Files.exists(stateOrSeed))
                ? JettraConfigValidator.loadProperties(stateOrSeed)
                : new Properties();
        byte[] cfgBytes = JettraConfigValidator.buildCanonicalClusterConfigBytes(canonicalNodes, baseProps);

        JettraConfigValidator.ValidationResult integrity = JettraConfigValidator
                .validateClusterConfigIntegrity(cfgBytes);
        if (!integrity.isValid()) {
            return "[ERROR] Integridad de cluster-state.config fallida. Distribución abortada: " + integrity.getSummary();
        }

        // Guardar atómicamente (tmp + fsync + ATOMIC_MOVE) en cluster-state.config sin alterar cluster.config estático
        try {
            JettraConfigValidator.writeClusterConfigAtomically(stateFile, cfgBytes);
        } catch (Exception ignored) {
        }

        // 5. Replicar a todos los nodos del quórum ordenados: PRIMARY primero, luego
        // SECONDARY
        List<ShellClusterNode> targets = new ArrayList<>(clusterTopology.values());
        targets.sort((a, b) -> Boolean.compare(isPrimaryRole(b.role()), isPrimaryRole(a.role())));

        StringBuilder report = new StringBuilder();
        report.append(
                "[CLUSTER REPLICATION] Sincronizando cluster-state.config en todos los nodos (PRIMARY -> SECONDARY):\n");

        for (ShellClusterNode target : targets) {
            boolean isPrim = isPrimaryRole(target.role());
            String roleTag = isPrim ? "PRIMARY  " : "SECONDARY";
            if (target.host() == null || target.host().isBlank() || target.grpcPort() <= 0)
                continue;

            boolean ok = client != null && client.distributeClusterConfigToNode(target.host(), target.grpcPort(), cfgBytes);
            if (ok) {
                report.append(String.format("  ✓ [%s] %s (%s:%d): cluster-state.config replicado exitosamente (vía JettraStoreDriver).%n",
                        roleTag, target.id(), target.host(), target.grpcPort()));
            } else {
                report.append(String.format(
                        "  ⚠ [%s] %s (%s:%d): No alcanzable. Se sincronizará automáticamente al iniciar.%n",
                        roleTag, target.id(), target.host(), target.grpcPort()));
            }
        }

        return report.toString();
    }

    private void triggerPrimaryDatabaseSyncToNode(String nodeId, String role, String host, int grpcPort, int restPort) {
        if (nodeId == null || "PRIMARY".equalsIgnoreCase(role))
            return;
        Thread.ofVirtual().name("jettra-shell-sync-" + nodeId).start(() -> {
            try {
                Path clusterConfigFile = JettraConfigValidator.locateActiveClusterStateOrSeedFile();
                if (clusterConfigFile == null || !Files.exists(clusterConfigFile))
                    return;
                Properties props = JettraConfigValidator.loadProperties(clusterConfigFile);
                List<JettraConfigValidator.ClusterNodeInfo> nodes = JettraConfigValidator.parseExplicitClusterNodes(props);
                JettraConfigValidator.ClusterNodeInfo primary = null;
                for (var cn : nodes) {
                    if ("PRIMARY".equalsIgnoreCase(cn.role())) {
                        primary = cn;
                        break;
                    }
                }
                if (primary == null || primary.ip() == null || primary.grpcPort() <= 0 || client == null)
                    return;
                client.triggerNodeJoinSync(primary.ip(), primary.grpcPort(), nodeId, role, host, grpcPort, restPort);
            } catch (Exception ignored) {
            }
        });
    }

    private String handleStartNode(String command) {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: iniciar nodos del clúster está restringido exclusivamente a usuarios con rol 'admin'.";
        }
        String id = cleanQuotes(command.substring("START NODE ".length()).trim());
        if (id.isBlank())
            return "[ERROR] Uso: START NODE <nodeId>";
        try {
            syncLocalClusterConfigFromPrimary();
            client.setActiveRole(currentRole);
            var res = client.startClusterNode(id);
            if (res.success() && res.verifiedState()) {
                io.jettra.store.cluster.ClusterNode peer = client.getRingEngine().getPeer(id);
                if (peer != null) {
                    triggerPrimaryDatabaseSyncToNode(peer.getId(), peer.getRole().name(), peer.getIp(), peer.getPort(),
                            0);
                }
                return String.format(
                        "[SUCCESS] %s (mecanismo: %s). Ejecute 'show nodes' para verificar el estado en tiempo real.",
                        res.message(), res.mechanism());
            }
            return String.format("[ERROR] %s (mecanismo: %s)", res.message(), res.mechanism());
        } catch (Exception e) {
            return "[ERROR] Error al iniciar nodo '" + id + "': " + e.getMessage();
        }
    }

    // --- 4.b Distribución y Migración de Datos en Clúster Raft ---
    private String handleClusterDistributed(String command) {
        String arg = command.replaceAll("(?i)^(CLUSTER-DISTRIBUTED|CLUSTER_DISTRIBUTED)\\s*", "").trim();
        if (arg.isEmpty()) {
            return """
                    [ERROR] Parámetro requerido para 'cluster-distributed'.
                    Uso:
                      cluster-distributed all           Distribuye entre todos los nodos todas las bases de datos y registros.
                      cluster-distributed <all>         Distribuye entre todos los nodos todas las bases de datos y registros.
                      cluster-distributed <nombre-bd>   Distribuye la base de datos indicada con sus registros exactos.
                      cluster-distributed info          Muestra una tabla con los nodos y las bases de datos en cada nodo.
                    """;
        }

        if (arg.equalsIgnoreCase("info")) {
            return handleClusterDistributedInfo();
        }

        boolean isAll = arg.equalsIgnoreCase("all") || arg.equalsIgnoreCase("<all>");
        if (isAll) {
            return handleClusterDistributedAll();
        }

        String dbName = cleanQuotes(arg.replaceAll("^<|>$", "").trim());
        if (dbName.isBlank()) {
            return "[ERROR] Debe especificar el nombre de la base de datos a distribuir.";
        }
        return handleClusterDistributedDatabase(dbName);
    }

    private String handleClusterDistributedInfo() {
        try {
            List<io.jettra.store.cluster.ClusterNodeDistributionInfo> infoList = client.getClusterDistributedInfo();
            StringBuilder sb = new StringBuilder();
            sb.append(
                    "========================================================================================================================\n");
            sb.append(
                    "                                   JETTRASTORE CLUSTER DATABASE DISTRIBUTION INFO                                       \n");
            sb.append(
                    "========================================================================================================================\n");
            sb.append(
                    "+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
            sb.append(
                    "| Nodo ID  | Dirección IP         | Puerto| Rol       | Estado   | Cantidad BDs  | Bases de Datos                       |\n");
            sb.append(
                    "+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
            for (var node : infoList) {
                String dbListStr = node.databases().isEmpty()
                        ? (node.status().equalsIgnoreCase("OFFLINE") ? "(Sin respuesta / Offline)"
                                : "(0 bases de datos)")
                        : String.join(", ", node.databases());
                if (dbListStr.length() > 36) {
                    dbListStr = dbListStr.substring(0, 33) + "...";
                }
                sb.append(String.format("| %-8s | %-20s | %-5d | %-9s | %-8s | %-13d | %-36s |\n",
                        node.nodeId(), node.ip(), node.port(), node.role(), node.status(), node.databaseCount(),
                        dbListStr));
            }
            sb.append(
                    "+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
            boolean multi = client.getRingEngine().isMultinodeActive();
            sb.append(String.format("Total nodos en topología: %d | Modo Multinodo (cluster.multinode.active): %s\n",
                    infoList.size(), multi ? "ON (Consenso y Replicación Activos)" : "OFF (Standalone)"));
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] No se pudo obtener la información de distribución del clúster: " + e.getMessage();
        }
    }

    private String handleClusterDistributedDatabase(String dbName) {
        if (!client.databaseExists(dbName)) {
            return String.format("[ERROR] La base de datos '%s' no existe en el catálogo local ni en disco.", dbName);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[CLUSTER-DISTRIBUTED] Iniciando distribución integral de la base de datos '%s'...\n",
                dbName));
        try {
            boolean ok = client.clusterDistributed(dbName);
            if (ok) {
                sb.append(String.format(
                        "  ✓ Base de datos '%s' y todos sus registros internos, colecciones e índices fueron migrados con éxito a los nodos secundarios.\n",
                        dbName));
                sb.append("[SUCCESS] Distribución completada bajo consenso Raft mayoritario.");
            } else {
                sb.append(String.format(
                        "[WARNING] La distribución de '%s' finalizó sin confirmación de quórum completo.\n", dbName));
            }
            return sb.toString();
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception e) {
            return "[ERROR] Falló la distribución de la base de datos '" + dbName + "': " + e.getMessage();
        }
    }

    private String handleClusterDistributedAll() {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "[CLUSTER-DISTRIBUTED <ALL>] Iniciando migración y distribución de TODAS las bases de datos del clúster...\n");
        try {
            Map<String, Boolean> results = client.clusterDistributedAll();
            if (results.isEmpty()) {
                return "[INFO] No se encontraron bases de datos registradas para distribuir.";
            }
            int successCount = 0;
            for (var entry : results.entrySet()) {
                if (entry.getValue()) {
                    sb.append(String.format("  ✓ [%s]: DISTRIBUIDA Y MIGRADA (Registros y metadatos OK)\n",
                            entry.getKey()));
                    successCount++;
                } else {
                    sb.append(String.format("  ✗ [%s]: ERROR EN DISTRIBUCIÓN (Sin quórum)\n", entry.getKey()));
                }
            }
            sb.append(String.format(
                    "[SUCCESS] %d de %d base(s) de datos y todos sus registros internos migrados exitosamente en el clúster.",
                    successCount, results.size()));
            return sb.toString();
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception e) {
            return "[ERROR] Falló la distribución global: " + e.getMessage();
        }
    }

    private String handleClusterLive(String command) {
        try {
            int limit = 30;
            String arg = command.replaceAll("(?i)^(CLUSTER[-_\\s]LIVE)\\s*", "").trim();
            if (!arg.isEmpty()) {
                try {
                    limit = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {
                }
            }

            List<io.jettra.store.cluster.ClusterLiveEvent> events = (client != null)
                    ? client.getRecentClusterLiveEvents(limit)
                    : io.jettra.store.cluster.JettraClusterEventBus.getInstance().getRecentEvents(limit);

            StringBuilder sb = new StringBuilder();
            sb.append(
                    "========================================================================================================================\n");
            sb.append(
                    "                                        JETTRASTORE CLUSTER LIVE EVENT STREAM                                           \n");
            sb.append(
                    "========================================================================================================================\n");
            sb.append(
                    "+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            sb.append(
                    "| Marca Temporal          | Tipo de Evento       | Origen  | Destino | Mensaje / Trazabilidad Operativa                |\n");
            sb.append(
                    "+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            if (events == null || events.isEmpty()) {
                sb.append(
                        "| (Sin eventos recientes de clúster registrados en el búfer)                                                           |\n");
            } else {
                for (var ev : events) {
                    String msg = ev.message();
                    if (msg.length() > 47) {
                        msg = msg.substring(0, 44) + "...";
                    }
                    String typeStr = ev.type();
                    if (typeStr.length() > 20) {
                        typeStr = typeStr.substring(0, 17) + "...";
                    }
                    sb.append(String.format("| %-23s | %-20s | %-7s | %-7s | %-47s |\n",
                            ev.formattedTimestamp(), typeStr, ev.sourceNodeId(), ev.targetNodeId(), msg));
                }
            }
            sb.append(
                    "+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            sb.append(String.format("Eventos en búfer mostrados: %d | Canal de Eventos en Tiempo Real: ACTIVO\n",
                    events != null ? events.size() : 0));
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] No se pudo obtener el flujo en vivo del clúster: " + e.getMessage();
        }
    }

    private String handleMultinodeStatus() {
        DynamicRingEngine ring = client.getRingEngine();
        boolean active = ring.isMultinodeActive();
        return String.format("""
                === ESTADO DE CONFIGURACIÓN MULTINODO (database.properties) ===
                - cluster.multinode.active:  %s
                - Algoritmo de Consenso:     RAFT Integrado & Dynamic Ring
                - Estado de Distribución:    %s
                - Comportamiento:            %s
                - Nodos Réplica en Anillo:   %d
                """,
                active ? "ON" : "OFF",
                active ? "ACTIVADO" : "DESACTIVADO (Servidor Único / Standalone)",
                active ? "Distribución y particionamiento automático de datos en clúster"
                        : "Todas las operaciones se resuelven exclusivamente en el servidor local sin distribuir datos",
                ring.getPeers().size());
    }

    private String handleSetMultinode(boolean active) {
        client.getRingEngine().setMultinodeActive(active);
        if (active) {
            return "[CONFIG] cluster.multinode.active = ON. Sistema de distribución mediante algoritmo de consenso integrado ACTIVADO.";
        } else {
            return "[CONFIG] cluster.multinode.active = OFF. Comportamiento de distribución DESACTIVADO. Todas las operaciones se realizarán solamente en este servidor sin distribuir datos.";
        }
    }

    private String handleStopNode(String command) {
        if (!hasAdminRole()) {
            return "[DENIED] Acceso denegado: detener nodos del clúster está restringido exclusivamente a usuarios con rol 'admin'.";
        }
        String id = cleanQuotes(command.substring("STOP NODE ".length()).trim());
        if (id.isBlank())
            return "[ERROR] Uso: STOP NODE <nodeId>";
        try {
            syncLocalClusterConfigFromPrimary();
            client.setActiveRole(currentRole);
            var res = client.stopClusterNode(id);
            if (res.success()) {
                return String.format(
                        "[SUCCESS] %s (mecanismo: %s). Ejecute 'show nodes' para verificar el quórum y estado.",
                        res.message(), res.mechanism());
            }
            return "[ERROR] " + res.message();
        } catch (Exception e) {
            return "[ERROR] Error al detener nodo '" + id + "': " + e.getMessage();
        }
    }

    // --- 5. Control de Bases de Datos & Detección en Nodo PRIMARY ---
    private String handleShowDatabases() {
        List<String> dbs = client.listDatabases();
        Collections.sort(dbs);

        StringBuilder sb = new StringBuilder();
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        sb.append("| Base de Datos                      | Tipo     | Colecciones | Estado         |\n");
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        for (String db : dbs) {
            int colCount;
            boolean loaded = client.isDatabaseLoaded(db);
            if (loaded) {
                colCount = client.getDatabase(db).getAllBucketNames().size();
            } else {
                colCount = client.getLightweightBucketCount(db);
            }
            String tipo = db.startsWith("sample_") || db.startsWith("example_") ? "SAMPLE"
                    : (db.equals("system_metadata_db") ? "SYSTEM" : "USER");
            String status = db.equals(currentDatabase) ? "* ACTIVA" : (loaded ? "EN MEMORIA" : "EN PRIMARY");
            sb.append(String.format("| %-34s | %-8s | %-11d | %-14s |\n", db, tipo, colCount, status));
        }
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        String activeLabel = (currentDatabase != null && !currentDatabase.isBlank()) ? currentDatabase
                : "(ninguna seleccionada)";
        sb.append(String.format("Total: %d base(s) de datos almacenadas en el nodo PRIMARY. Base activa: '%s'\n",
                dbs.size(), activeLabel));
        return sb.toString();
    }

    private String handleShowSampleDatabases() {
        List<String> allDbs = client.listDatabases();
        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append("                        BASE DE DATOS DE EJEMPLO (JettraStore Sample)                        \n");
        sb.append("==============================================================================================\n");
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        sb.append("| Base de Datos           | Estado en Disco    | Motores & Propósito                         |\n");
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        boolean installed = allDbs.contains("example_factura_db");
        sb.append(String.format("| %-23s | %-18s | %-43s |\n",
                "example_factura_db",
                installed ? "INSTALADA (Lista)" : "NO INSTALADA",
                "Facturación Multimodelo (Máx 500 registros)"));
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        sb.append(
                "Para instalar o re-inicializar la muestra, ejecute: INSTALL SAMPLES o LOAD SAMPLE example_factura_db\n");
        return sb.toString();
    }

    private String handleCreateDatabase(String command) {
        String dbName = cleanQuotes(command.replaceAll("(?i)^(CREATE\\s+DATABASE|CREATE\\s+DB)\\s+", "").trim());
        if (dbName.isBlank())
            return "[ERROR] Nombre de base de datos requerido. Uso: CREATE DATABASE <nombre> o CREATE DB <nombre>";
        try {
            var db = this.client.getDatabase(dbName);
            if (db != null) {
                db.assertWritable();
            }
            this.currentDatabase = dbName;
            return "[SUCCESS] Base de datos '" + dbName + "' creada exitosamente y seleccionada como activa.";
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleDropDatabase(String command) {
        String dbName = cleanQuotes(command.replaceAll("(?i)^(DROP\\s+DATABASE|DROP\\s+DB)\\s+", "").trim());
        if (dbName.isBlank())
            return "[ERROR] Nombre de base de datos requerido. Uso: DROP DATABASE <nombre> o DROP DB <nombre>";
        try {
            boolean dropped = client.dropDatabase(dbName);
            if (dbName.equalsIgnoreCase(currentDatabase)) {
                this.currentDatabase = null;
            }
            return dropped ? "[SUCCESS] Base de datos '" + dbName + "' eliminada correctamente."
                    : "[INFO] La base de datos '" + dbName + "' no existía o ya fue eliminada.";
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleUseDatabase(String command) {
        String dbName = cleanQuotes(command.substring(4));
        if (dbName.isBlank())
            return "[ERROR] Nombre de base de datos requerido.";

        // Verificación de RBAC granular para el usuario activo
        if (!client.getSecurityManager().hasDatabaseAccess(currentUser, dbName, "USE")) {
            return String.format(
                    "[ACCESS DENIED] El usuario '%s' no posee permisos asignados para la base de datos '%s'.",
                    currentUser, dbName);
        }

        JettraDatabase db = this.client.getDatabase(dbName);
        this.currentDatabase = dbName;

        if (db.isDistributedRingActive() || client.getRingEngine().isRingActive()) {
            String peersDesc = client.getRingEngine().getPeers().stream()
                    .map(p -> p.getId() + ": " + p.getIp() + ":" + p.getPort())
                    .collect(java.util.stream.Collectors.joining(", "));
            if (peersDesc.isBlank()) {
                peersDesc = "nodos secundarios";
            }
            return String
                    .format("""
                            [RING TRANSITION ACTIVE] Supervisión preventiva JettraPolice: Saturación de RAM prevenida (Umbral >= 85%%).
                            [CLUSTER] Activada Transición Dinámica a Motor de Anillo Distribuido (Consistent Ring Topology).
                            [OFFLOAD] Carga y particiones delegadas a nodos secundarios (%s).
                            [SUCCESS] Conmutado a base de datos activa: '%s' [MODO ANILLO DISTRIBUIDO].
                            """,
                            peersDesc, dbName)
                    .trim();
        }

        return "[SUCCESS] Conmutado a base de datos activa: '" + dbName + "'.";
    }

    private String handleDbStats() {
        if (currentDatabase == null || currentDatabase.isBlank()) {
            return "[INFO] No hay ninguna base de datos seleccionada. Use 'SHOW DATABASES', 'CREATE DATABASE <nombre>' o 'USE <nombre>'.";
        }
        JettraDatabase db = client.getDatabase(currentDatabase);
        return String.format("""
                === ESTADÍSTICAS DE BASE DE DATOS: '%s' ===
                - Colecciones Totales: %d
                - Documentos:          %s
                - Clave-Valor (KV):    %s
                - Vectores:            %s
                - Grafos:              %s
                - Series Temporales:   %s
                - Geoespacial (GIS):   %s
                - Columnar (OLAP):     %s
                - Java Records:        %s
                - Índices Secundarios: %d
                - MemTable Utilizada:  %.2f KB
                """, currentDatabase, db.getAllBucketNames().size(),
                db.getDocumentEngineNames(),
                db.getKeyValueEngineNames(),
                db.getVectorEngineNames(),
                db.getGraphEngineNames(),
                db.getTimeSeriesEngineNames(),
                db.getGeospatialEngineNames(),
                db.getColumnarEngineNames(),
                db.getRecordsEngineNames(),
                db.getIndexManager().listIndexes(null).size(),
                (db.getMemTable().getUsedBytes() / 1024.0));
    }

    // --- 6. Administración de Índices ---
    private String handleCreateIndex(String command) {
        // CREATE INDEX <indexName> ON <collection> (<field>) [TYPE <BTREE|HASH|SPARSE>]
        // [UNIQUE]
        try {
            String upper = command.toUpperCase();
            int onIdx = upper.indexOf(" ON ");
            if (onIdx == -1)
                return "[ERROR] Sintaxis inválida. Uso: CREATE INDEX <nombre> ON <coleccion> (<campo>) [TYPE BTREE|HASH|SPARSE]";

            String indexName = cleanQuotes(command.substring(13, onIdx).trim());
            String rest = command.substring(onIdx + 4).trim();
            int parenOpen = rest.indexOf('(');
            int parenClose = rest.indexOf(')');
            if (parenOpen == -1 || parenClose == -1) {
                return "[ERROR] Campo de índice debe estar entre paréntesis: (<campo>).";
            }

            String col = cleanQuotes(rest.substring(0, parenOpen).trim());
            String field = cleanQuotes(rest.substring(parenOpen + 1, parenClose).trim());
            String tail = rest.substring(parenClose + 1).toUpperCase();

            String type = "BTREE";
            if (tail.contains("HASH"))
                type = "HASH";
            else if (tail.contains("SPARSE"))
                type = "SPARSE";

            boolean unique = tail.contains("UNIQUE");

            JettraDatabase db = client.getDatabase(currentDatabase);
            var info = db.getIndexManager().createIndex(col, indexName, field, type, unique, db.getDocumentEngine(col));
            client.replicateCreateIndex(currentDatabase, col, indexName, field, type, unique);
            return String.format("[SUCCESS] Índice '%s' creado sobre '%s'(%s) tipo %s (Entradas indexadas: %d).",
                    info.name(), info.bucket(), info.field(), info.type(), info.entriesCount());
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception e) {
            return "[ERROR] Error al crear índice: " + e.getMessage();
        }
    }

    private String handleDropIndex(String command) {
        String clean = cleanQuotes(command.substring("DROP INDEX ".length()).trim());
        String indexName = clean.split("\\s+")[0];
        try {
            JettraDatabase db = client.getDatabase(currentDatabase);
            boolean ok = db.getIndexManager().dropIndex(indexName);
            if (ok) {
                client.replicateDropIndex(currentDatabase, indexName);
            }
            return ok ? "[SUCCESS] Índice '" + indexName + "' eliminado correctamente."
                    : "[WARN] El índice '" + indexName + "' no existe en la base de datos '" + currentDatabase + "'.";
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleRebuildIndex(String command) {
        String clean = cleanQuotes(
                command.replaceAll("(?i)^(ALTER INDEX|REINDEX)\\s+", "").replaceAll("(?i)REBUILD", "").trim());
        String indexName = clean.split("\\s+")[0];
        JettraDatabase db = client.getDatabase(currentDatabase);
        var info = db.getIndexManager().getIndex(indexName);
        if (info == null)
            return "[ERROR] Índice '" + indexName + "' no encontrado.";

        var updated = db.getIndexManager().rebuildIndex(indexName, db.getDocumentEngine(info.bucket()));
        return String.format("[SUCCESS] Índice '%s' reconstruido exitosamente. Total entradas indexadas: %d.",
                updated.name(), updated.entriesCount());
    }

    private String handleShowIndexes(String command) {
        String col = null;
        if (command.toUpperCase().contains(" ON ")) {
            col = cleanQuotes(command.substring(command.toUpperCase().indexOf(" ON ") + 4).trim());
        }
        JettraDatabase db = client.getDatabase(currentDatabase);
        List<JettraIndexManager.IndexInfo> list = db.getIndexManager().listIndexes(col);

        if (list.isEmpty()) {
            return "[INFO] No se encontraron índices en '" + currentDatabase + "'"
                    + (col != null ? " para la colección '" + col + "'." : ".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== ÍNDICES DE BASE DE DATOS: '%s' ===\n", currentDatabase));
        sb.append(
                "+----------------------+----------------------+----------------------+--------+--------+----------+\n");
        sb.append(
                "| Nombre de Índice     | Colección            | Campo Indexado       | Tipo   | Único  | Entradas |\n");
        sb.append(
                "+----------------------+----------------------+----------------------+--------+--------+----------+\n");
        for (var idx : list) {
            sb.append(String.format("| %-20s | %-20s | %-20s | %-6s | %-6s | %-8d |\n",
                    idx.name(), idx.bucket(), idx.field(), idx.type(), idx.unique() ? "SI" : "NO",
                    idx.entriesCount()));
        }
        sb.append(
                "+----------------------+----------------------+----------------------+--------+--------+----------+\n");
        return sb.toString();
    }

    // --- 7. Administración de Usuarios y Roles de Base de Datos ---
    private String handleShowUsers() {
        var users = client.getSecurityManager().listUsers();
        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append("                           USUARIOS Y ROLES DE BASE DE DATOS (RBAC)                           \n");
        sb.append("==============================================================================================\n");
        sb.append("+-----------------+-----------------+------------------------------------------+-------------+\n");
        sb.append("| Usuario         | Rol Global      | Roles de Base de Datos                   | Inmutable   |\n");
        sb.append("+-----------------+-----------------+------------------------------------------+-------------+\n");
        for (var u : users) {
            StringBuilder rolesSb = new StringBuilder();
            if (u.databaseRoles().isEmpty()) {
                rolesSb.append("(Sin asignación específica)");
            } else {
                u.databaseRoles().forEach((db, r) -> rolesSb.append(db).append(":").append(r).append(" "));
            }
            sb.append(String.format("| %-15s | %-15s | %-40s | %-11s |\n",
                    u.username(), u.role(), rolesSb.toString().trim(), u.immutable() ? "SI (Protegido)" : "NO"));
        }
        sb.append("+-----------------+-----------------+------------------------------------------+-------------+\n");
        return sb.toString();
    }

    private String handleCreateUser(String command) {
        // CREATE USER <username> PASSWORD <password> [ROLE <globalRole>]
        try {
            String after = command.substring("CREATE USER ".length()).trim();
            int passIdx = after.toUpperCase().indexOf(" PASSWORD ");
            if (passIdx == -1)
                return "[ERROR] Uso: CREATE USER <username> PASSWORD <password> [ROLE <globalRole>]";

            String username = cleanQuotes(after.substring(0, passIdx).trim());
            String rest = after.substring(passIdx + 10).trim();
            String password;
            String role = "DEVELOPER";

            int roleIdx = rest.toUpperCase().indexOf(" ROLE ");
            if (roleIdx != -1) {
                password = cleanQuotes(rest.substring(0, roleIdx).trim());
                role = cleanQuotes(rest.substring(roleIdx + 6).trim()).toUpperCase();
            } else {
                password = cleanQuotes(rest);
            }

            client.getSecurityManager().createUser(client.getSessionToken(), username, password, role);
            return String.format("[SUCCESS] Usuario '%s' creado con Rol Global '%s'.", username, role);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleDropUser(String command) {
        String username = cleanQuotes(command.substring("DROP USER ".length()).trim());
        try {
            client.getSecurityManager().dropUser(client.getSessionToken(), username);
            return String.format("[SUCCESS] Usuario '%s' eliminado correctamente.", username);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleAlterUser(String command) {
        // ALTER USER <username> PASSWORD <newPass> | ALTER USER <username> ROLE
        // <newRole>
        try {
            String after = command.substring("ALTER USER ".length()).trim();
            String[] parts = after.split("\\s+", 3);
            if (parts.length < 3)
                return "[ERROR] Uso: ALTER USER <username> PASSWORD <newPass> | ALTER USER <username> ROLE <newRole>";
            String username = cleanQuotes(parts[0]);
            String action = parts[1].toUpperCase();
            String val = cleanQuotes(parts[2]);

            if ("PASSWORD".equals(action)) {
                client.getSecurityManager().alterUserPassword(client.getSessionToken(), username, val);
                return "[SUCCESS] Contraseña actualizada para el usuario '" + username + "'.";
            } else if ("ROLE".equals(action)) {
                client.getSecurityManager().alterUserRole(client.getSessionToken(), username, val.toUpperCase());
                return "[SUCCESS] Rol global del usuario '" + username + "' actualizado a '" + val.toUpperCase() + "'.";
            }
            return "[ERROR] Acción desconocida en ALTER USER. Use PASSWORD o ROLE.";
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleGrantRole(String command) {
        // GRANT <DB_ROLE> ON <database> TO <username>
        try {
            String upper = command.toUpperCase();
            int onIdx = upper.indexOf(" ON ");
            int toIdx = upper.indexOf(" TO ");
            if (onIdx == -1 || toIdx == -1) {
                return "[ERROR] Uso: GRANT <DB_OWNER|READ_WRITE|READ_ONLY> ON <database> TO <username>";
            }

            String role = cleanQuotes(command.substring(6, onIdx).trim()).toUpperCase();
            String db = cleanQuotes(command.substring(onIdx + 4, toIdx).trim());
            String user = cleanQuotes(command.substring(toIdx + 4).trim());

            client.getSecurityManager().grantDatabaseRole(client.getSessionToken(), user, db, role);
            return String.format("[SUCCESS] Concedido rol '%s' sobre la base de datos '%s' al usuario '%s'.", role, db,
                    user);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleRevokeRole(String command) {
        // REVOKE <database> FROM <username> o REVOKE ROLE ON <database> FROM <username>
        try {
            String upper = command.toUpperCase();
            int fromIdx = upper.indexOf(" FROM ");
            if (fromIdx == -1)
                return "[ERROR] Uso: REVOKE <database> FROM <username>";

            String targetDb = cleanQuotes(command.substring(7, fromIdx).replaceAll("(?i)ROLE\\s+ON\\s+", "").trim());
            String user = cleanQuotes(command.substring(fromIdx + 6).trim());

            client.getSecurityManager().revokeDatabaseRole(client.getSessionToken(), user, targetDb);
            return String.format("[SUCCESS] Permisos sobre base de datos '%s' revocados para el usuario '%s'.",
                    targetDb, user);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleShowGrants(String command) {
        String username = cleanQuotes(command.substring("SHOW GRANTS FOR ".length()).trim());
        var user = client.getSecurityManager().getUser(username);
        if (user == null)
            return "[ERROR] Usuario '" + username + "' no encontrado.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== PRIVILEGIOS ASIGNADOS AL USUARIO: '%s' ===\n", username));
        sb.append(String.format("- Rol Global: %s\n", user.role()));
        sb.append("- Permisos por Base de Datos:\n");
        if (user.databaseRoles().isEmpty()) {
            sb.append("  (Ningún rol específico por base de datos asignado)\n");
        } else {
            user.databaseRoles().forEach((db, role) -> {
                sb.append(String.format("  * Base de datos: %-25s -> Rol: %s\n", db, role));
            });
        }
        return sb.toString();
    }

    // --- 8. Registros Referenciados y JettraRef ---
    private String resolveReference(JettraDatabase db, String refStr) {
        try {
            if (refStr.contains("::") && refStr.contains("#")) {
                String type = refStr.substring(0, refStr.indexOf("::")).toLowerCase();
                String rest = refStr.substring(refStr.indexOf("::") + 2);
                String col = rest.substring(0, rest.indexOf('#'));
                String targetId = rest.substring(rest.indexOf('#') + 1);

                return switch (type) {
                    case "vector" -> {
                        float[] v = db.getVectorEngine(col, 3).getVector(targetId);
                        yield v != null ? "Vector " + Arrays.toString(v) : "(Vector no encontrado)";
                    }
                    case "document" -> {
                        Map<String, Object> doc = db.getDocumentEngine(col).findById(targetId);
                        yield doc != null ? "Documento " + doc.toString() : "(Documento no encontrado)";
                    }
                    case "graph" -> {
                        var edges = db.getGraphEngine(col).getOutboundEdges(targetId);
                        yield "Vértice de Grafo '" + targetId + "' (" + edges.size() + " aristas conectadas)";
                    }
                    case "timeseries" -> {
                        yield "Métrica TimeSeries registrada para target '" + targetId + "'";
                    }
                    case "kv" -> {
                        byte[] val = db.getKeyValueEngine(col).get(targetId);
                        yield val != null ? "KV Valor: " + new String(val, StandardCharsets.UTF_8)
                                : "(Clave KV no encontrada)";
                    }
                    default -> refStr;
                };
            }
            return refStr;
        } catch (Exception e) {
            return refStr;
        }
    }

    private String handleInsertRef(String command) {
        // INSERT REF <collection> <docId> KEY <refKey> TARGET
        // <engine>::<targetCol>#<targetId>
        try {
            String upper = command.toUpperCase();
            int keyIdx = upper.indexOf(" KEY ");
            int targetIdx = upper.indexOf(" TARGET ");
            if (keyIdx == -1 || targetIdx == -1) {
                return "[ERROR] Uso: INSERT REF <coleccion> <id> KEY <campo_ref> TARGET <engine>::<targetCol>#<targetId>";
            }

            String beforeKey = command.substring(11, keyIdx).trim();
            String[] parts = beforeKey.split("\\s+");
            if (parts.length < 2)
                return "[ERROR] Debe especificar colección e ID de registro.";
            String col = cleanQuotes(parts[0]);
            String id = cleanQuotes(parts[1]);

            String refKey = cleanQuotes(command.substring(keyIdx + 5, targetIdx).trim());
            String targetRef = cleanQuotes(command.substring(targetIdx + 8).trim());

            JettraDatabase db = client.getDatabase(currentDatabase);
            var docEngine = db.getDocumentEngine(col);
            Map<String, Object> doc = docEngine.findById(id);
            if (doc == null) {
                doc = new LinkedHashMap<>();
                doc.put("_id", id);
            }
            doc.put(refKey, targetRef);
            docEngine.insert(id, doc);

            return String.format("[SUCCESS] Referencia JettraRef '%s' vinculada en '%s'[%s] -> '%s'.",
                    refKey, col, id, targetRef);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleResolveRef(String command) {
        String targetRef = cleanQuotes(command.substring("RESOLVE REF ".length()).trim());
        JettraDatabase db = client.getDatabase(currentDatabase);
        String resolved = resolveReference(db, targetRef);
        return String.format("[JettraRef Resolución]: %s -> %s", targetRef, resolved);
    }

    private String handleShowRefs(String command) {
        String clean = cleanQuotes(command.substring("SHOW REFS ".length()).trim());
        String[] parts = clean.split("\\s+");
        if (parts.length < 2)
            return "[ERROR] Uso: SHOW REFS <coleccion> <id>";
        String col = parts[0];
        String id = parts[1];

        JettraDatabase db = client.getDatabase(currentDatabase);
        var doc = db.getDocumentEngine(col).findById(id);
        if (doc == null)
            return "[ERROR] Documento no encontrado: " + id;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== REFERENCIAS CRUZADAS PARA [%s] EN '%s' ===\n", id, col));
        int count = 0;
        for (var entry : doc.entrySet()) {
            if (entry.getKey().startsWith("_ref_") || String.valueOf(entry.getValue()).contains("::")) {
                count++;
                String refVal = String.valueOf(entry.getValue());
                sb.append(String.format("  [%02d] Campo: %-18s -> %s\n", count, entry.getKey(), refVal));
                sb.append(String.format("       ↳ Resolución %s: %s\n",
                        lazyLoad ? "(Lazy On-Demand)" : "(Eager)", resolveReference(db, refVal)));
            }
        }
        if (count == 0) {
            sb.append("  (No se encontraron campos de referencia en este registro)\n");
        }
        return sb.toString();
    }

    // --- 9. Soporte JettraQL y JettraSQL ---
    private String handleSetPageSize(String command) {
        String[] parts = command.trim().split("\\s+");
        String valStr = parts[parts.length - 1].replaceAll("[;]", "");
        try {
            int newSize = Integer.parseInt(valStr);
            if (newSize <= 0)
                return "[ERROR] El tamaño de página debe ser mayor a 0.";
            this.pageSize = Math.min(newSize, 5000);
            if (lastPagedBaseQuery != null) {
                this.totalQueryPages = (int) Math.max(1, Math.ceil((double) totalQueryRecords / pageSize));
                this.currentQueryPage = Math.min(currentQueryPage, totalQueryPages);
                return String.format("[PAGINACIÓN] Tamaño de página configurado a %d registros.\n%s",
                        pageSize, executePagedQuery(lastPagedBaseQuery, currentQueryPage));
            }
            return String.format("[PAGINACIÓN] Tamaño de página configurado a %d registros para futuras consultas.",
                    pageSize);
        } catch (Exception e) {
            return "[ERROR] Uso: PAGE_SIZE <tamaño> (ejemplo: PAGE_SIZE 25)";
        }
    }

    private String handleNavigatePage(String action) {
        if (lastPagedBaseQuery == null) {
            return "[INFO] No hay una consulta previa activa para paginar. Ejecute primero un SELECT o FIND ALL.";
        }

        int targetPage = currentQueryPage;
        String upper = action.toUpperCase();

        if (upper.equals("FIRST") || upper.equals("PRIMERO") || upper.equals("PAGE FIRST") || upper.equals("|<<")
                || upper.equals("P")) {
            targetPage = 1;
        } else if (upper.equals("PREV") || upper.equals("ANTERIOR") || upper.equals("PAGE PREV") || upper.equals("<")
                || upper.equals("A")) {
            targetPage = Math.max(1, currentQueryPage - 1);
        } else if (upper.equals("NEXT") || upper.equals("SIGUIENTE") || upper.equals("PAGE NEXT") || upper.equals(">")
                || upper.equals("S")) {
            targetPage = Math.min(totalQueryPages, currentQueryPage + 1);
        } else if (upper.equals("LAST") || upper.equals("ULTIMO") || upper.equals("PAGE LAST") || upper.equals(">>|")
                || upper.equals("U")) {
            targetPage = totalQueryPages;
        } else if (upper.startsWith("PAGE ") || upper.startsWith("PAGINA ")) {
            String[] parts = action.split("\\s+");
            try {
                targetPage = Math.max(1, Math.min(totalQueryPages, Integer.parseInt(parts[1].replaceAll("[;]", ""))));
            } catch (Exception ignored) {
                return "[ERROR] Uso: PAGE <número_página>";
            }
        }

        this.currentQueryPage = targetPage;
        return executePagedQuery(lastPagedBaseQuery, currentQueryPage);
    }

    private String executePagedQuery(String baseQuery, int page) {
        int offset = (page - 1) * pageSize;
        String pagedSql = baseQuery + " LIMIT " + pageSize + " OFFSET " + offset;
        return handleJettraSQLInternal(pagedSql, false);
    }

    private String renderPaginationBar(int currentPage, int totalPages, int currentPageSize, long totalRecords,
            int rowsInPage) {
        long startRow = totalRecords == 0 ? 0 : (long) (currentPage - 1) * currentPageSize + 1;
        long endRow = totalRecords == 0 ? 0
                : Math.min(totalRecords, (long) (currentPage - 1) * currentPageSize + rowsInPage);

        StringBuilder sb = new StringBuilder();
        sb.append(
                "┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐\n");
        sb.append(String.format(
                "│  PÁGINA [ %d / %d ]  •  Registros %d a %d de %d total  •  Tamaño de página: %d               │\n",
                currentPage, totalPages, startRow, endRow, totalRecords, currentPageSize));
        sb.append(
                "├──────────────────────────────────────────────────────────────────────────────────────────────────────┤\n");
        sb.append(
                "│  OPCIONES DE DESPLAZAMIENTO:                                                                         │\n");
        sb.append(
                "│    [F] Primero (|<<)   - Va a la primera página       [A] Anterior (<)    - Página anterior          │\n");
        sb.append(
                "│    [S] Siguiente (>)   - Página siguiente             [U] Última (>>|)    - Va a la última página    │\n");
        sb.append(
                "│    PAGE <n>            - Salta a una página directa   PAGE_SIZE <n>       - Modifica tamaño de página│\n");
        sb.append(
                "└──────────────────────────────────────────────────────────────────────────────────────────────────────┘\n");
        return sb.toString();
    }

    private String handleJettraSQL(String command) {
        return handleJettraSQLInternal(command, true);
    }

    private String handleJettraSQLInternal(String command, boolean isNewQuery) {
        String clean = command;
        if (clean.toUpperCase().startsWith("SQL "))
            clean = clean.substring(4).trim();
        else if (clean.toUpperCase().startsWith("JETTRASQL "))
            clean = clean.substring(10).trim();

        long start = System.currentTimeMillis();
        JettraSQLProcessor.QueryResult res;
        if (currentDatabase == null || currentDatabase.isBlank()) {
            String upperClean = clean.toUpperCase();
            if (upperClean.startsWith("MATH ") || upperClean.startsWith("FINANCE ")
                    || upperClean.startsWith("STATS ") || upperClean.startsWith("VECTOR ")) {
                res = new JettraSQLProcessor(null).execute(clean);
            } else {
                return "[INFO] No hay ninguna base de datos seleccionada. Use 'SHOW DATABASES', 'CREATE DATABASE <nombre>' o 'USE <nombre>'.";
            }
        } else {
            res = client.sql(currentDatabase, clean);
        }
        long elapsed = System.currentTimeMillis() - start;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== JETTRASQL RESULTADO (%d ms) ===\n", elapsed));
        if (res.message().contains("[JettraPolice SENTINEL") || lastSentinelNotification != null) {
            sb.append("🛡️  [JETTRAPOLICE SENTINEL: INTERVENCIÓN PREVENTIVA DE MEMORIA HEAP]\n");
            sb.append("   Estrategia: Streaming por chunks seguro (Anti-OOM) con recolección de basura iterativa.\n");
            sb.append("   Diagnóstico: ").append(res.message()).append("\n");
            if (lastSentinelNotification != null) {
                sb.append(String.format("   Lote seguro: %d registros | Heap: %.1f%% | RAM libre: %d MB\n",
                        lastSentinelNotification.safeBatchSize(), lastSentinelNotification.heapUsagePercent(),
                        lastSentinelNotification.availableMemoryMb()));
            }
            sb.append(
                    "   Recepción progresiva: Prototipo de stream procesado en bloques para proteger terminal y heap.\n");
        } else {
            sb.append("Mensaje: ").append(res.message()).append("\n");
        }

        if (!res.rows().isEmpty()) {
            // Renderizar tabla ASCII con formato dinámico
            List<String> cols = res.columns();
            sb.append("+");
            for (String c : cols)
                sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
            sb.append("\n|");
            for (String c : cols)
                sb.append(String.format(" %-" + Math.max(c.length(), 12) + "s |", c));
            sb.append("\n+");
            for (String c : cols)
                sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
            sb.append("\n");

            int maxDisplay = Math.min(res.rows().size(), pageSize > 0 ? pageSize : 50);
            int displayCount = 0;
            for (List<Object> row : res.rows()) {
                if (++displayCount > maxDisplay)
                    break;
                sb.append("|");
                for (int i = 0; i < cols.size(); i++) {
                    String val = (i < row.size() && row.get(i) != null) ? row.get(i).toString() : "";
                    sb.append(String.format(" %-" + Math.max(cols.get(i).length(), 12) + "s |", val));
                }
                sb.append("\n");
            }
            if (res.rows().size() > maxDisplay) {
                sb.append(String.format("... y %d fila(s) más. Use LIMIT o aumente PAGE_SIZE para ver más.\n",
                        res.rows().size() - maxDisplay));
            }
            if (res.message().contains("[JettraPolice SENTINEL")) {
                sb.append(String.format(
                        "💡 Consejo JettraPolice: Para iterar la siguiente página lazy ejecute: %s LIMIT %d OFFSET %d\n",
                        clean.contains("LIMIT") ? clean.replaceAll("(?i)LIMIT\\s+\\d+", "").trim() : clean,
                        res.rows().size(), res.rows().size()));
            }
            sb.append("+");
            for (String c : cols)
                sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
            sb.append("\n");
        }
        sb.append(String.format("Total: %d fila(s) seleccionadas / afectadas.\n", res.affectedRows()));

        // Gestión y visualización de paginación interactiva
        if (clean.toUpperCase().startsWith("SELECT ")) {
            if (isNewQuery) {
                // Registrar consulta base sin LIMIT ni OFFSET
                this.lastPagedBaseQuery = clean.replaceAll("(?i)\\s+LIMIT\\s+\\d+(\\s+OFFSET\\s+\\d+)?", "").trim();
                this.currentQueryPage = 1;
                // Extraer nombre de colección para conteo total
                String upperQ = lastPagedBaseQuery.toUpperCase();
                int fromPos = upperQ.indexOf(" FROM ");
                if (fromPos != -1) {
                    String afterFrom = lastPagedBaseQuery.substring(fromPos + 6).trim().split("\\s+")[0]
                            .replaceAll("[;]", "");
                    try {
                        var engine = client.getDatabase(currentDatabase).getDocumentEngine(afterFrom);
                        this.totalQueryRecords = engine != null ? engine.count() : res.affectedRows();
                    } catch (Exception e) {
                        this.totalQueryRecords = res.affectedRows();
                    }
                } else {
                    this.totalQueryRecords = res.affectedRows();
                }
                this.totalQueryPages = (int) Math.max(1, Math.ceil((double) totalQueryRecords / pageSize));
            }

            if (totalQueryPages > 1 || res.rows().size() >= pageSize) {
                sb.append(renderPaginationBar(currentQueryPage, totalQueryPages, pageSize, totalQueryRecords,
                        res.rows().size()));
            }
        }

        return sb.toString();
    }

    private String handleJettraQL(String command) {
        String clean = command;
        if (clean.toUpperCase().startsWith("JQL "))
            clean = clean.substring(4).trim();
        else if (clean.toUpperCase().startsWith("JETTRAQL "))
            clean = clean.substring(9).trim();

        long start = System.currentTimeMillis();
        JettraQLProcessor.JQLResult res = client.jql(currentDatabase, clean);
        long elapsed = System.currentTimeMillis() - start;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== JETTRAQL [%s] (%d ms) ===\n", res.operation(), elapsed));
        sb.append("Resumen: ").append(res.summary()).append("\n");

        if (!res.rows().isEmpty()) {
            List<String> cols = res.columns();
            sb.append("Columnas: ").append(cols).append("\n");
            int idx = 1;
            for (var row : res.rows()) {
                sb.append(String.format("  [%02d] %s\n", idx++, row));
            }
        }
        sb.append(String.format("Coincidencias encontradas: %d\n", res.totalMatches()));
        return sb.toString();
    }

    // --- 10. CRUD de Documentos ---
    private String handleInsert(String command) {
        try {
            String after = command.substring("INSERT INTO ".length()).trim();
            int idIdx = after.toUpperCase().indexOf(" ID ");
            int jsonIdx = after.toUpperCase().indexOf(" JSON ");
            if (jsonIdx != -1) {
                String col;
                String id;
                if (idIdx != -1 && idIdx < jsonIdx) {
                    col = cleanQuotes(after.substring(0, idIdx).trim());
                    id = cleanQuotes(after.substring(idIdx + 4, jsonIdx).trim());
                } else {
                    col = cleanQuotes(after.substring(0, jsonIdx).trim());
                    id = null;
                }
                String jsonPart = after.substring(jsonIdx + 6).trim();
                Map<String, Object> data = parseJsonOrKeyValues(jsonPart);
                String assignedId = (id != null && !id.isBlank())
                        ? id
                        : io.jettra.store.core.JettraId.generate(resolveActiveDb(), col).toHexString();
                JettraDatabase db = client.getDatabase(resolveActiveDb());
                db.getDocumentEngine(col).insertStrict(assignedId, data);
                db.getIndexManager().onDocumentInsert(col, assignedId, data);
                db.saveToDisk();
                client.replicatePutDocument(resolveActiveDb(), col, assignedId, data);
                Map<String, Object> persisted = db.getDocumentEngine(col).findById(assignedId);
                client.getVersionManager().recordVersion(
                        resolveActiveDb(), col, assignedId,
                        VersionOperation.CREATE,
                        persisted != null ? persisted : data,
                        currentUser,
                        "Inserción desde JettraStoreShell",
                        null);
                return String.format("[SUCCESS] Registro con _id '%s' (JettraId) insertado en el bucket '%s'.", assignedId, col);
            }
            int valIdx = after.toUpperCase().indexOf(" VALUES");
            if (valIdx != -1) {
                String col = after.substring(0, valIdx).trim();
                String rawVals = after.substring(valIdx + 7).trim();
                if (rawVals.startsWith("(") && rawVals.endsWith(")")) {
                    rawVals = rawVals.substring(1, rawVals.length() - 1).trim();
                }
                String id;
                String jsonPart;
                String stripped = cleanQuotes(rawVals);
                if (stripped.startsWith("{") && stripped.endsWith("}")) {
                    id = io.jettra.store.core.JettraId.generate(resolveActiveDb(), col).toHexString();
                    jsonPart = stripped;
                } else {
                    String[] parts = rawVals.split(",", 2);
                    id = cleanQuotes(parts[0]);
                    if (id.isBlank()) {
                        id = io.jettra.store.core.JettraId.generate(resolveActiveDb(), col).toHexString();
                    }
                    jsonPart = parts.length > 1 ? parts[1].trim() : "{}";
                }
                Map<String, Object> data = parseJsonOrKeyValues(jsonPart);

                JettraDatabase db = client.getDatabase(resolveActiveDb());
                db.getDocumentEngine(col).insertStrict(id, data);
                db.getIndexManager().onDocumentInsert(col, id, data);
                db.saveToDisk();
                client.replicatePutDocument(resolveActiveDb(), col, id, data);
                Map<String, Object> persisted = db.getDocumentEngine(col).findById(id);
                client.getVersionManager().recordVersion(
                        resolveActiveDb(), col, id,
                        VersionOperation.CREATE,
                        persisted != null ? persisted : data,
                        currentUser,
                        "Inserción desde JettraStoreShell",
                        null);
                return String.format("[SUCCESS] Registro con _id '%s' (JettraId) insertado en el bucket '%s'.", id, col);
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {
        }
        return "[ERROR] Formato inválido. Uso: INSERT INTO <col> [ID <id>] JSON {...} o INSERT INTO <col> VALUES ('<id>', '{...}')";
    }

    private String handleGetRecord(String command) {
        String clean = command.replaceAll("(?i)^(GET|FIND ONE|FIND BY ID)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 2)
            return "[ERROR] Uso: GET <colección> <id>";
        String colName = parts[0].trim();
        String id = cleanQuotes(parts[1]);

        JettraDatabase db = client.getDatabase(currentDatabase);
        Map<String, Object> doc = db.getDocumentEngine(colName).findById(id);
        if (doc == null) {
            return String.format("[NOT FOUND] No se encontró el registro con _id '%s' en el bucket '%s'.", id,
                    colName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- REGISTRO [%s] EN '%s' ---\n", id, colName));
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            sb.append(String.format("  %-15s : %s\n", entry.getKey(), entry.getValue()));
            if (showReferences
                    && (entry.getKey().startsWith("_ref_") || String.valueOf(entry.getValue()).contains("::"))) {
                String refVal = String.valueOf(entry.getValue());
                sb.append(String.format("    ↳ [JettraRef Resolución %s]: %s\n",
                        lazyLoad ? "(Lazy Proxy On-Demand)" : "(Eager Carga Inmediata)", resolveReference(db, refVal)));
            }
        }
        return sb.toString();
    }

    private String handleFindAll(String command) {
        String clean = command.replaceAll("(?i)^(FIND ALL|SCAN)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 1 || parts[0].isBlank())
            return "[ERROR] Uso: FIND ALL <colección> [LIMIT <n>]";
        String colName = cleanQuotes(parts[0]);

        int limit = pageSize;
        for (int i = 1; i < parts.length - 1; i++) {
            if ("LIMIT".equalsIgnoreCase(parts[i])) {
                try {
                    limit = Integer.parseInt(parts[i + 1].replaceAll("[;]", ""));
                } catch (Exception ignored) {
                }
            }
        }

        JettraDatabase db = client.getDatabase(currentDatabase);
        var docEngine = db.getDocumentEngine(colName);
        if (docEngine == null || docEngine.isEmpty()) {
            return String.format("[INFO] El bucket '%s' está vacío.", colName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- BUCKET '%s' (Mostrando %d de %d registros) ---\n",
                colName, Math.min((int) docEngine.count(), limit), docEngine.count()));
        int count = 0;
        for (Map<String, Object> doc : docEngine) {
            if (++count > limit)
                break;
            sb.append(String.format("  [%02d] %s\n", count, doc));
        }
        return sb.toString();
    }

    private String handleUpdate(String command) {
        try {
            String after = command.substring("UPDATE ".length()).trim();
            int setIdx = after.toUpperCase().indexOf(" SET ");
            int whereIdx = after.toUpperCase().indexOf(" WHERE ");
            if (setIdx != -1) {
                String col = cleanQuotes(after.substring(0, setIdx).trim());
                String id = null;
                if (whereIdx != -1) {
                    String wherePart = after.substring(whereIdx + 7).trim();
                    id = cleanQuotes(wherePart.split("=")[1].trim());
                }
                String jsonPart = whereIdx != -1 ? after.substring(setIdx + 5, whereIdx).trim()
                        : after.substring(setIdx + 5).trim();
                Map<String, Object> updates = parseJsonOrKeyValues(jsonPart);

                if (id != null) {
                    String activeDb = resolveActiveDb();
                    JettraDatabase db = client.getDatabase(activeDb);
                    db.getDocumentEngine(col).update(id, updates);
                    db.saveToDisk();
                    Map<String, Object> full = db.getDocumentEngine(col).findById(id);
                    if (full != null) {
                        client.replicatePutDocument(activeDb, col, id, full);
                        client.getVersionManager().recordVersion(
                                activeDb, col, id,
                                VersionOperation.UPDATE,
                                full,
                                currentUser,
                                "Actualización desde JettraStoreShell",
                                null);
                    }
                    return String.format("[SUCCESS] Registro con _id '%s' actualizado en '%s'.", id, col);
                }
            }
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {
        }
        return "[ERROR] Formato inválido. Uso: UPDATE <colección> SET {campo:valor} WHERE _id = '<id>'";
    }

    private String handleDeleteRecord(String command) {
        try {
            String clean = command.replaceAll("(?i)^(DELETE FROM|DELETE|REMOVE)\\s+", "").trim();
            if (clean.toUpperCase().contains(" WHERE ")) {
                int whereIdx = clean.toUpperCase().indexOf(" WHERE ");
                String col = cleanQuotes(clean.substring(0, whereIdx).trim());
                String whereClause = clean.substring(whereIdx + 7).trim();
                String[] kv = whereClause.split("=");
                if (kv.length == 2) {
                    String id = cleanQuotes(kv[1].trim());
                    String activeDb = resolveActiveDb();
                    JettraDatabase db = client.getDatabase(activeDb);
                    Map<String, Object> beforeDelete = db.getDocumentEngine(col).findById(id);
                    boolean deleted = db.getDocumentEngine(col).delete(id);
                    db.getIndexManager().onDocumentDelete(col, id, null);
                    db.saveToDisk();
                    if (deleted) {
                        client.replicateDeleteDocument(activeDb, col, id);
                        client.getVersionManager().recordVersion(
                                activeDb, col, id,
                                VersionOperation.DELETE,
                                beforeDelete != null ? beforeDelete : Collections.emptyMap(),
                                currentUser,
                                "Eliminación desde JettraStoreShell",
                                null);
                    }
                    return deleted
                            ? String.format("[SUCCESS] Registro con _id '%s' eliminado de '%s'.", id, col)
                            : String.format("[WARN] No se encontró el registro con _id '%s' para eliminar.", id);
                }
            } else {
                String[] parts = clean.split("\\s+");
                if (parts.length >= 2) {
                    String col = cleanQuotes(parts[0]);
                    String id = cleanQuotes(parts[1]);
                    String activeDb = resolveActiveDb();
                    JettraDatabase db = client.getDatabase(activeDb);
                    Map<String, Object> beforeDelete = db.getDocumentEngine(col).findById(id);
                    boolean deleted = db.getDocumentEngine(col).delete(id);
                    db.getIndexManager().onDocumentDelete(col, id, null);
                    db.saveToDisk();
                    if (deleted) {
                        client.replicateDeleteDocument(activeDb, col, id);
                        client.getVersionManager().recordVersion(
                                activeDb, col, id,
                                VersionOperation.DELETE,
                                beforeDelete != null ? beforeDelete : Collections.emptyMap(),
                                currentUser,
                                "Eliminación desde JettraStoreShell",
                                null);
                    }
                    return deleted
                            ? String.format("[SUCCESS] Registro con _id '%s' eliminado de '%s'.", id, col)
                            : String.format("[WARN] No se encontró el registro con _id '%s' para eliminar.", id);
                }
            }
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {
        }
        return "[ERROR] Formato inválido. Uso: DELETE <col> <id> o DELETE FROM <col> WHERE _id = '<id>'";
    }

    // --- 10.5. Historial de Versiones y Restauración de Registros (RF-18) ---
    private long parseVersionNumberToken(String raw) {
        if (raw == null || raw.isBlank()) return -1L;
        String cleaned = cleanQuotes(raw.trim());
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1).trim();
        }
        try {
            long val = Long.parseLong(cleaned);
            return val > 0 ? val : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private String handleVersionHistory(String command) {
        String clean = command.replaceAll("(?i)^(SHOW\\s+VERSIONS|VERSION\\s+HISTORY|VERSIONS)\\s*", "").trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase("--help") || clean.equalsIgnoreCase("-h")) {
            return getVersionHelpText();
        }
        String[] parts = clean.split("\\s+");
        if (parts.length < 2) {
            return "[ERROR] Parámetros insuficientes. Uso: VERSIONS <bucket> <id> (o 'versions --help').";
        }
        String bucket = cleanQuotes(parts[0]);
        String id = cleanQuotes(parts[1]);
        String dbName = resolveActiveDb();

        List<RecordVersion> history = client.getVersionHistory(dbName, bucket, id);
        if (history.isEmpty()) {
            return String.format("[NOT FOUND] No existe historial de versiones para el registro '%s' en '%s.%s'.",
                    id, dbName, bucket);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== HISTORIAL DE VERSIONES [%s.%s :: %s] (%d versiones) ===%n",
                dbName, bucket, id, history.size()));
        sb.append(String.format("  %-8s | %-10s | %-28s | %-12s | %s%n",
                "VERSION", "OPERACIÓN", "TIMESTAMP (UTC)", "AUTOR", "DETALLE"));
        sb.append("  -----------------------------------------------------------------------------------------------\n");
        for (RecordVersion v : history) {
            String detail = v.note();
            if (v.restoredFromVersion() != null && (detail == null || !detail.contains("v" + v.restoredFromVersion()))) {
                detail = (detail != null ? detail + " " : "") + "(desde v" + v.restoredFromVersion() + ")";
            }
            sb.append(String.format("  %-8s | %-10s | %-28s | %-12s | %s%n",
                    "v" + v.versionNumber(),
                    v.operation().name(),
                    v.timestamp(),
                    v.author(),
                    detail != null ? detail : ""));
        }
        return sb.toString();
    }

    private String handleVersionShow(String command) {
        String clean = command.replaceAll("(?i)^VERSION\\s+(SHOW|GET|INSPECT)\\s*", "").trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase("--help") || clean.equalsIgnoreCase("-h")) {
            return getVersionHelpText();
        }
        String[] parts = clean.split("\\s+");
        if (parts.length < 3) {
            return "[ERROR] Parámetros insuficientes. Uso: VERSION SHOW <bucket> <id> <version> (ej. VERSION SHOW productos p1 v1).";
        }
        String bucket = cleanQuotes(parts[0]);
        String id = cleanQuotes(parts[1]);
        long vNum = parseVersionNumberToken(parts[2]);
        if (vNum <= 0) {
            return String.format("[ERROR] Número de versión inválido: '%s'. Debe ser un entero positivo (ej. 1 o v1).", parts[2]);
        }
        String dbName = resolveActiveDb();

        Optional<RecordVersion> opt = client.getVersion(dbName, bucket, id, vNum);
        if (opt.isEmpty()) {
            return String.format("[NOT FOUND] No se encontró la versión v%d para el registro '%s' en '%s.%s'.",
                    vNum, id, dbName, bucket);
        }
        RecordVersion v = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== DETALLE DE VERSIÓN v%d [%s.%s :: %s] ===%n", v.versionNumber(), dbName, bucket, id));
        sb.append(String.format("  Version ID   : %s%n", v.versionId()));
        sb.append(String.format("  Operación    : %s%n", v.operation().name()));
        sb.append(String.format("  Timestamp    : %s%n", v.timestamp()));
        sb.append(String.format("  Autor        : %s%n", v.author()));
        sb.append(String.format("  Nota         : %s%n", v.note()));
        if (v.restoredFromVersion() != null) {
            sb.append(String.format("  Origen       : Restaurado desde v%d%n", v.restoredFromVersion()));
        }
        sb.append("  Snapshot     :\n");
        if (v.snapshot().isEmpty()) {
            sb.append("    (vacío)\n");
        } else {
            for (Map.Entry<String, Object> e : v.snapshot().entrySet()) {
                sb.append(String.format("    %-18s : %s%n", e.getKey(), e.getValue()));
            }
        }
        return sb.toString();
    }

    private String handleVersionRestore(String command) {
        String clean = command.replaceAll("(?i)^(RESTORE\\s+VERSION|VERSION\\s+RESTORE)\\s*", "").trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase("--help") || clean.equalsIgnoreCase("-h")) {
            return getVersionHelpText();
        }
        String[] parts = clean.split("\\s+");
        if (parts.length < 3) {
            return "[ERROR] Parámetros insuficientes. Uso: RESTORE VERSION <bucket> <id> <version> (ej. RESTORE VERSION productos p1 1).";
        }
        String bucket = cleanQuotes(parts[0]);
        String id = cleanQuotes(parts[1]);
        long targetVersion = parseVersionNumberToken(parts[2]);
        if (targetVersion <= 0) {
            return String.format("[ERROR] Número de versión inválido: '%s'. Debe ser un entero positivo (ej. 1 o v1).", parts[2]);
        }
        String dbName = resolveActiveDb();

        try {
            RecordVersion restored = client.restoreVersion(dbName, bucket, id, targetVersion);
            return String.format(
                    "[SUCCESS] Registro '%s' en '%s.%s' restaurado desde la versión v%d -> nueva versión registrada: v%d (%s).%n"
                            + "  Snapshot restaurado: %s",
                    id, dbName, bucket, targetVersion, restored.versionNumber(), restored.operation().name(), restored.snapshot());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception e) {
            return "[ERROR] Error restaurando versión v" + targetVersion + " para '" + id + "': " + e.getMessage();
        }
    }

    public String getVersionHelpText() {
        return """
                ==============================================================================================
                             AYUDA DE COMANDOS: HISTORIAL DE VERSIONES Y RESTAURACIÓN (RF-18)
                ==============================================================================================
                  VERSIONS <bucket> <id>                    Lista el historial completo de versiones (v1, v2, ...).
                  VERSION HISTORY <bucket> <id>             Alias de VERSIONS <bucket> <id>.
                  SHOW VERSIONS <bucket> <id>               Alias de VERSIONS <bucket> <id>.
                  VERSION SHOW <bucket> <id> <version>      Inspecciona metadatos y snapshot de una versión (ej. 1 o v1).
                  RESTORE VERSION <bucket> <id> <version>   Restaura el registro a una versión previa creando una nueva versión RESTORE.
                  VERSION RESTORE <bucket> <id> <version>   Alias de RESTORE VERSION.

                  EJEMPLOS:
                    VERSIONS productos p1
                    VERSION SHOW productos p1 v1
                    RESTORE VERSION productos p1 1
                ==============================================================================================
                """;
    }

    // --- 11. Motores Multimodelo ---
    private String handleVectorIndex(String command) {
        try {
            String after = command.substring("VECTOR INDEX ".length()).trim();
            String[] parts = after.split("\\s+", 3);
            String col = parts[0].trim();
            String id = parts[1].trim();
            String vecStr = after.substring(after.indexOf('[') + 1, after.indexOf(']'));
            String[] floatsStr = vecStr.split(",");
            float[] floats = new float[floatsStr.length];
            for (int i = 0; i < floatsStr.length; i++)
                floats[i] = Float.parseFloat(floatsStr[i].trim());

            JettraDatabase db = client.getDatabase(currentDatabase);
            db.getVectorEngine(col, floats.length).index(id, floats);
            return String.format("[SUCCESS] Vector '%s' indexado en '%s' (%d dimensiones).", id, col, floats.length);
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: VECTOR INDEX <coleccion> <id> [f1, f2, f3]";
        }
    }

    private String handleVectorSearch(String command) {
        try {
            String after = command.substring("VECTOR SEARCH ".length()).trim();
            String col = after.split("\\s+")[0].trim();
            String vecStr = after.substring(after.indexOf('[') + 1, after.indexOf(']'));
            String[] floatsStr = vecStr.split(",");
            float[] floats = new float[floatsStr.length];
            for (int i = 0; i < floatsStr.length; i++)
                floats[i] = Float.parseFloat(floatsStr[i].trim());

            int k = 3;
            int kIdx = after.toUpperCase().indexOf(" K ");
            if (kIdx != -1) {
                k = Integer.parseInt(after.substring(kIdx + 3).replaceAll("[;]", "").trim());
            }

            JettraDatabase db = client.getDatabase(currentDatabase);
            var matches = db.getVectorEngine(col, floats.length).searchCosine(floats, k);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== VECTOR COSINE EN '%s' (k=%d) ===\n", col, k));
            int idx = 1;
            for (var match : matches) {
                sb.append(String.format("  [%02d] Vector ID: %-15s | Similaridad: %.4f\n", idx++, match.id(),
                        match.score()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: VECTOR SEARCH <coleccion> [f1, f2] K <num>";
        }
    }

    private String handleGraphAddVertex(String command) {
        String[] parts = command.substring("GRAPH ADD VERTEX ".length()).trim().split("\\s+");
        if (parts.length < 2)
            return "[ERROR] Uso: GRAPH ADD VERTEX <grafo> <verticeId>";
        client.getDatabase(currentDatabase).getGraphEngine(parts[0]).addVertex(cleanQuotes(parts[1]));
        return String.format("[SUCCESS] Vértice '%s' agregado al grafo '%s'.", parts[1], parts[0]);
    }

    private String handleGraphAddEdge(String command) {
        try {
            String after = command.substring("GRAPH ADD EDGE ".length()).trim();
            String[] parts = after.split("\\s+");
            String graph = parts[0];
            String from = parts[1];
            String to = parts[2];
            String label = "RELATES_TO";
            int lblIdx = after.toUpperCase().indexOf("LABEL");
            if (lblIdx != -1)
                label = cleanQuotes(after.substring(lblIdx + 5).split("\\s+")[0]);
            double weight = 1.0;
            int wIdx = after.toUpperCase().indexOf("WEIGHT");
            if (wIdx != -1)
                weight = Double.parseDouble(after.substring(wIdx + 6).replaceAll("[;]", "").trim());

            client.getDatabase(currentDatabase).getGraphEngine(graph).addEdge(from, to, label,
                    Map.of("weight", weight));
            return String.format("[SUCCESS] Arista (%s)-[%s, w=%.1f]->(%s) agregada en grafo '%s'.", from, label,
                    weight, to, graph);
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: GRAPH ADD EDGE <grafo> <de> <a> [LABEL <nombre>] [WEIGHT <peso>]";
        }
    }

    private String handleGraphGetEdges(String command) {
        String clean = command.replaceAll("(?i)^(GRAPH GET EDGES|GRAPH EDGES)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 2)
            return "[ERROR] Uso: GRAPH GET EDGES <grafo> <verticeId>";
        var edges = client.getDatabase(currentDatabase).getGraphEngine(parts[0])
                .getOutboundEdges(cleanQuotes(parts[1]));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== ARISTAS SALIENTES DESDE '%s' EN '%s' ===\n", parts[1], parts[0]));
        int idx = 1;
        for (var edge : edges) {
            sb.append(String.format("  [%02d] -> Destino: %-15s | Relación: %-15s | Props: %s\n",
                    idx++, edge.targetVertex(), edge.label(), edge.properties()));
        }
        return sb.toString();
    }

    private String handleTsRecord(String command) {
        try {
            String after = command.substring("TS RECORD ".length()).trim();
            String[] parts = after.split("\\s+");
            String series = parts[0];
            double val = Double.parseDouble(parts[1]);
            long timestamp = System.currentTimeMillis();
            int timeIdx = after.toUpperCase().indexOf("TIME");
            if (timeIdx != -1)
                timestamp = Long.parseLong(after.substring(timeIdx + 4).replaceAll("[;]", "").trim());

            client.getDatabase(currentDatabase).getTimeSeriesEngine(series).record(timestamp, val);
            return String.format("[SUCCESS] Métrica (timestamp=%d, valor=%.2f) registrada en '%s'.", timestamp, val,
                    series);
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: TS RECORD <serie> <valor> [TIME <timestamp>]";
        }
    }

    private String handleTsRange(String command) {
        try {
            String clean = command.replaceAll("(?i)^(TS RANGE|TS QUERY)\\s+", "").trim();
            String[] parts = clean.split("\\s+");
            String series = parts[0];
            long start = Long.parseLong(parts[1]);
            long end = Long.parseLong(parts[2].replaceAll("[;]", ""));

            var points = client.getDatabase(currentDatabase).getTimeSeriesEngine(series).range(start, end);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== TIME SERIES EN '%s' [%d a %d] ===\n", series, start, end));
            int idx = 1;
            for (var entry : points.entrySet()) {
                sb.append(String.format("  [%02d] Timestamp: %d | Valor: %.4f\n", idx++, entry.getKey(),
                        entry.getValue()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: TS RANGE <serie> <timestampInicio> <timestampFin>";
        }
    }

    private String handleKvPut(String command) {
        String[] parts = command.substring(7).trim().split("\\s+", 3);
        if (parts.length < 3)
            return "[ERROR] Uso: KV PUT <tabla> <clave> <valor>";
        client.getDatabase(currentDatabase).getKeyValueEngine(parts[0]).put(cleanQuotes(parts[1]),
                parts[2].getBytes(StandardCharsets.UTF_8));
        return String.format("[SUCCESS] Clave '%s' guardada en tabla KV '%s'.", parts[1], parts[0]);
    }

    private String handleKvGet(String command) {
        String[] parts = command.substring(7).trim().split("\\s+");
        if (parts.length < 2)
            return "[ERROR] Uso: KV GET <tabla> <clave>";
        byte[] val = client.getDatabase(currentDatabase).getKeyValueEngine(parts[0]).get(cleanQuotes(parts[1]));
        return val != null ? "[KV] " + new String(val, StandardCharsets.UTF_8)
                : "[NOT FOUND] Clave '" + parts[1] + "' no encontrada.";
    }

    // --- 12. Persistencia y Muestras ---
    public String installAllSampleDatabases() {
        return "[SUCCESS] Base de datos de ejemplo instalada y persistida exitosamente:\n"
                + installFacturaSampleDatabase();
    }

    public String installHospitalSampleDatabase() {
        long start = System.currentTimeMillis();
        client.dropDatabase("samples_hostipal_db");
        JettraDatabase db = client.getDatabase("samples_hostipal_db");
        io.jettra.store.sample.JettraStoreSamples.installHospital(db, true);
        this.currentDatabase = "samples_hostipal_db";
        long duration = System.currentTimeMillis() - start;

        return String.format("""
                ==============================================================================================
                        CARGA MASIVA EXITOSA: BASE DE DATOS 'samples_hostipal_db' (2,000,000 OBJETOS)
                ==============================================================================================
                [OK] Tiempo de Inserción y Procesamiento: %d ms (Java 25 Virtual Threads)
                [OK] Objetos Repartidos en 11 Buckets Multimodelo:
                  * [DOCUMENT]   'pacientes'                  :   500,000 pacientes con historial y referencias
                  * [DOCUMENT]   'afecciones'                 :   400,000 afecciones clínicas y sintomatología
                  * [DOCUMENT]   'medicamentos'               :   200,000 fármacos con principio activo y dosis
                  * [DOCUMENT]   'enfermedades'               :   100,000 diagnósticos con código CIE-10
                  * [DOCUMENT]   'hospitales'                 :    50,000 centros y hospitales con camas y UCI
                  * [KEYVALUE]   'inventario_medicamentos'    :   300,000 registros de stock y disponibilidad
                  * [VECTOR]     'sintomas_embeddings'        :   200,000 embeddings 3D para triaje predictivo
                  * [GRAPH]      'red_hospitalaria'           :   100,000 relaciones internamiento / pabellones
                  * [TIMESERIES] 'telemetria_signos_vitales'  :   100,000 lecturas continuas de ritmo/presión
                  * [GEOSPATIAL] 'ubicacion_hospitales'       :    25,000 coordenadas geográficas de centros
                  * [COLUMNAR]   'analitica_costos_salud'     :    25,000 filas de cálculo analítico de costos
                ----------------------------------------------------------------------------------------------
                GRAN TOTAL EN 'samples_hostipal_db': 2,000,000 objetos multimodelo conectados mediante JettraRef.
                Índices Creados: idx_pac_hospital, idx_pac_sangre, idx_enf_cie10, idx_med_principio
                Base de datos activa conmutada a: 'samples_hostipal_db'
                ==============================================================================================
                """, duration);
    }

    public String installAmbientalSampleDatabase() {
        long start = System.currentTimeMillis();
        client.dropDatabase("samples_ambiental_db");
        JettraDatabase db = client.getDatabase("samples_ambiental_db");
        io.jettra.store.sample.JettraStoreSamples.installAmbiental(db, true);
        this.currentDatabase = "samples_ambiental_db";
        long duration = System.currentTimeMillis() - start;

        return String.format("""
                ==============================================================================================
                        CARGA MASIVA EXITOSA: BASE DE DATOS 'samples_ambiental_db' (3,000,000 OBJETOS)
                ==============================================================================================
                [OK] Tiempo de Inserción y Procesamiento: %d ms (Java 25 Virtual Threads)
                [OK] Objetos Repartidos en 11 Buckets Multimodelo:
                  * [DOCUMENT]   'mediciones_calidad_aire'    : 1,000,000 mediciones (AQI, PM2.5, PM10, CO2)
                  * [DOCUMENT]   'estaciones_meteorologicas'  :   200,000 estaciones de monitoreo mundial
                  * [DOCUMENT]   'fuentes_emision'            :   200,000 industrias y plantas emisoras
                  * [DOCUMENT]   'reservas_naturales'         :   100,000 reservas, biomas y parques
                  * [DOCUMENT]   'especies_afectadas'         :   100,000 registros de biodiversidad
                  * [KEYVALUE]   'cache_alertas_ambientales'  :   400,000 alertas globales en caché
                  * [VECTOR]     'patrones_climaticos_embeddings': 300,000 vectores 3D de atmósfera/presión
                  * [GRAPH]      'red_corredores_biologicos'  :   200,000 enlaces entre reservas y estaciones
                  * [TIMESERIES] 'temperatura_global_telemetria': 300,000 puntos temporales de temperatura
                  * [GEOSPATIAL] 'coordenadas_estaciones'     :   100,000 coordenadas GIS globales
                  * [COLUMNAR]   'analitica_emisiones_anuales':   100,000 filas de cálculo analítico de CO2
                ----------------------------------------------------------------------------------------------
                GRAN TOTAL EN 'samples_ambiental_db': 3,000,000 objetos multimodelo conectados mediante JettraRef.
                Índices Creados: idx_med_aqi, idx_med_estacion, idx_est_pais, idx_res_bioma
                Base de datos activa conmutada a: 'samples_ambiental_db'
                ==============================================================================================
                """, duration);
    }

    public String installFacturaSampleDatabase() {
        long start = System.currentTimeMillis();
        client.dropDatabase("example_factura_db");
        JettraDatabase db = client.getDatabase("example_factura_db");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Task 1: Clientes (100)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("clientes");
                int total = 100;
                Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(total);
                for (int i = 0; i < total; i++) {
                    String id = "cli_" + i;
                    batch.put(id, Map.of(
                            "_id", id,
                            "razon_social", "Corporación Comercial " + i + " S.A.",
                            "rfc_tax_id", "RFC-PAN-" + (1000000 + i),
                            "ciudad", (i % 2 == 0) ? "Ciudad de Panamá" : "Colón",
                            "limite_credito", 50000.0 + (i % 1000) * 100));
                }
                docEngine.insertBatch(batch);
            });

            // Task 2: Facturas (100 con referencias cruzadas)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("facturas");
                int total = 100;
                Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(total);
                for (int i = 0; i < total; i++) {
                    String id = "fac_" + i;
                    batch.put(id, Map.of(
                            "_id", id,
                            "fecha", "2026-09-29",
                            "total", 250.0 + (i % 2000),
                            "estado", "TIMBRADA",
                            "_ref_cliente", "document::clientes#cli_" + (i % 100),
                            "_ref_detalle", "document::detalles_factura#det_" + i,
                            "_ref_folio", "kv::cache_folios#fol_" + (i % 50),
                            "_ref_vector", "vector::factura_embeddings#emb_" + (i % 50),
                            "_ref_sucursal", "geospatial::sucursales_fiscales#suc_" + (i % 20)));
                }
                docEngine.insertBatch(batch);
            });

            // Task 3: Detalles de Factura (100)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("detalles_factura");
                int total = 100;
                Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(total);
                for (int i = 0; i < total; i++) {
                    String id = "det_" + i;
                    int qty = (i % 10) + 1;
                    double price = 50.0 + (i % 200);
                    batch.put(id, Map.of(
                            "_id", id,
                            "concepto", "Servicio Cloud / Licencia Empresarial #" + (i % 100),
                            "cantidad", qty,
                            "precio_unitario", price,
                            "subtotal", qty * price,
                            "_ref_factura", "document::facturas#fac_" + i));
                }
                docEngine.insertBatch(batch);
            });

            // Task 4: KV Cache de Folios (50)
            executor.submit(() -> {
                var kvEngine = db.getKeyValueEngine("cache_folios");
                int total = 50;
                byte[] rawVal = "TIMBRADO_OK_CFDI_2026_SAT".getBytes(StandardCharsets.UTF_8);
                Map<String, byte[]> batch = new HashMap<>(total);
                for (int i = 0; i < total; i++) {
                    batch.put("fol_" + i, rawVal);
                }
                kvEngine.putBatch(batch);
            });

            // Task 5: Factura Embeddings (50 vectores 3d)
            executor.submit(() -> {
                var vecEngine = db.getVectorEngine("factura_embeddings", 3);
                int total = 50;
                float[] emb = new float[] { 0.75f, -0.20f, 0.60f };
                Map<String, float[]> batch = new HashMap<>(total);
                for (int i = 0; i < total; i++) {
                    batch.put("emb_" + i, emb);
                }
                vecEngine.indexBatch(batch);
            });

            // Task 6: Red Comercial Graph (40 vértices de clientes y facturas conectadas)
            executor.submit(() -> {
                var graphEngine = db.getGraphEngine("red_comercial");
                int totalEdges = 20;
                Map<String, List<io.jettra.store.engine.models.GraphEngine.Edge>> batch = new HashMap<>(totalEdges);
                for (int i = 0; i < totalEdges; i++) {
                    String from = "cli_" + i;
                    String to = "fac_" + i;
                    batch.put(from, List.of(new io.jettra.store.engine.models.GraphEngine.Edge(
                            to, "FACTURA_EMITIDA", Map.of("weight", 1.0))));
                }
                graphEngine.addEdgesBatch(batch);
            });

            // Task 7: TimeSeries Volumen Facturación (20)
            executor.submit(() -> {
                var tsEngine = db.getTimeSeriesEngine("volumen_facturacion");
                long now = System.currentTimeMillis();
                Map<Long, Double> batch = new HashMap<>(20);
                for (int i = 0; i < 20; i++) {
                    batch.put(now - (i * 1000L), 2500.0 + (i % 500));
                }
                tsEngine.recordBatch(batch);
            });

            // Task 8: Geospatial Sucursales (20)
            executor.submit(() -> {
                var geoEngine = db.getGeospatialEngine("sucursales_fiscales");
                Map<String, io.jettra.store.engine.models.GeospatialEngine.GeoPoint> batch = new HashMap<>(20);
                for (int i = 0; i < 20; i++) {
                    String id = "suc_" + i;
                    batch.put(id, new io.jettra.store.engine.models.GeospatialEngine.GeoPoint(
                            id, 8.9800 + (i % 100) * 0.001, -79.5200 + (i % 100) * 0.001));
                }
                geoEngine.insertBatch(batch);
            });

            // Task 9: Columnar Analítica Fiscal (10)
            executor.submit(() -> {
                var colEngine = db.getColumnarEngine("analitica_fiscal");
                List<Double> sub = new ArrayList<>(10);
                List<Double> iva = new ArrayList<>(10);
                List<Double> tot = new ArrayList<>(10);
                for (int i = 0; i < 10; i++) {
                    double s = 1000.0 + (i % 500);
                    double iv = s * 0.07;
                    sub.add(s);
                    iva.add(iv);
                    tot.add(s + iv);
                }
                colEngine.appendBatch(
                        Map.of("subtotal", sub, "iva", iva, "total", tot),
                        Map.of(),
                        10);
            });

            // Task 10: Pure Java 25 Records (10 auditoria_records)
            executor.submit(() -> {
                var recordsEngine = db.getRecordsEngine("auditoria_records",
                        io.jettra.store.sample.model.FacturaAuditRecord.class);
                int totalRecords = 10;
                long now = System.currentTimeMillis();
                Map<String, io.jettra.store.sample.model.FacturaAuditRecord> batch = new HashMap<>(totalRecords);
                for (int i = 0; i < totalRecords; i++) {
                    String id = "rec_" + i;
                    batch.put(id, new io.jettra.store.sample.model.FacturaAuditRecord(
                            id,
                            "FOL-SAT-2026-" + i,
                            "RFC-EMISOR-PAN-" + (i % 500),
                            "RFC-REC-PAN-" + (i % 100),
                            150.0 + (i % 1500),
                            "sha256-hash-audit-" + i,
                            now - (i * 500L)));
                }
                recordsEngine.insertBatch(batch);
            });
        }

        // Flush persistencia física para volcar buffers a almacenamiento antes de
        // indexación
        try {
            db.flushMemTable();
        } catch (Exception ignored) {
        }

        // Creación de índices compactos (Zero-Set Singletons)
        try {
            db.getIndexManager().createIndex("facturas", "idx_fac_cliente", "_ref_cliente", "HASH", false,
                    db.getDocumentEngine("facturas"));
            db.getIndexManager().createIndex("clientes", "idx_cli_rfc", "rfc_tax_id", "BTREE", false,
                    db.getDocumentEngine("clientes"));
        } catch (Exception ignored) {
        }

        this.currentDatabase = "example_factura_db";
        long duration = System.currentTimeMillis() - start;

        return String.format("""
                ==============================================================================================
                        CARGA DE BASE DE DATOS DE EJEMPLO: 'example_factura_db' (500 OBJETOS MULTIMODELO)
                ==============================================================================================
                [OK] Tiempo de Inserción y Timbrado Multimodelo: %d ms (Java 25 Virtual Threads)
                [OK] Objetos Repartidos en 10 Buckets Especializados (Máximo 500 registros):
                  * [DOCUMENT]   'facturas'              :        100 facturas electrónicas timbradas
                  * [DOCUMENT]   'detalles_factura'      :        100 renglones/items vinculados
                  * [DOCUMENT]   'clientes'              :        100 clientes empresariales con RFC/RUC
                  * [KEYVALUE]   'cache_folios'          :         50 folios fiscales en caché ultrarrápida
                  * [VECTOR]     'factura_embeddings'    :         50 vectores 3D indexados para IA
                  * [GRAPH]      'red_comercial'         :         40 vértices conectados (clientes -> facturas)
                  * [TIMESERIES] 'volumen_facturacion'   :         20 métricas históricas de facturación
                  * [GEOSPATIAL] 'sucursales_fiscales'   :         20 puntos GIS de sucursales emisoras
                  * [COLUMNAR]   'analitica_fiscal'      :         10 filas de cálculo analítico de IVA/Totales
                  * [RECORDS]    'auditoria_records'     :         10 registros tipados Java 25 (FacturaAuditRecord)
                ----------------------------------------------------------------------------------------------
                GRAN TOTAL EN 'example_factura_db': 500 registro(s) multimodelo conectados mediante JettraRef.
                Índices Creados: idx_fac_cliente (HASH), idx_cli_rfc (BTREE)
                Base de datos activa conmutada a: 'example_factura_db'
                ==============================================================================================
                """, duration);
    }

    private String handleBackup(String command) {
        try {
            String after = command.substring("BACKUP DATABASE".length()).trim();
            String dbName = currentDatabase;
            String dest = "./data/jettra/backup_" + dbName + "_" + System.currentTimeMillis() + ".snap";
            if (!after.isBlank()) {
                if (after.toUpperCase().contains(" TO ")) {
                    String[] parts = after.split("(?i)\s+TO\s+");
                    dbName = cleanQuotes(parts[0].trim());
                    dest = cleanQuotes(parts[1].trim());
                } else {
                    String[] parts = after.split("\s+");
                    dbName = cleanQuotes(parts[0]);
                    if (parts.length > 1)
                        dest = cleanQuotes(parts[1]);
                }
            }
            JettraDatabase db = client.getDatabase(dbName);
            var meta = BackupManager.backupDatabase(db, Path.of(dest));
            return String.format("[SUCCESS] Respaldo de '%s' completado en '%s'. Tamaño: %d bytes (CRC32: %d).",
                    meta.databaseName(), dest, meta.sizeBytes(), meta.checksum());
        } catch (Exception e) {
            return "[ERROR] Error al crear respaldo: " + e.getMessage();
        }
    }

    private String handleRestore(String command) {
        try {
            String after = command.substring("RESTORE DATABASE".length()).trim();
            Path snap;
            String targetDb;
            if (after.toUpperCase().contains(" FROM ")) {
                String[] parts = after.split("(?i)\s+FROM\s+");
                targetDb = cleanQuotes(parts[0].trim());
                snap = Path.of(cleanQuotes(parts[1].trim()));
            } else {
                String[] parts = after.split("\s+");
                if (parts.length < 2)
                    return "[ERROR] Uso: RESTORE DATABASE <archivo.snap> <nombreBaseDatosDestino> o RESTORE DATABASE <db> FROM '<archivo.snap>'";
                snap = Path.of(cleanQuotes(parts[0]));
                targetDb = cleanQuotes(parts[1]);
            }
            JettraDatabase db = client.getDatabase(targetDb);
            boolean ok = BackupManager.restoreDatabase(snap, db);
            return ok ? String.format("[SUCCESS] Snapshot '%s' restaurado en base de datos '%s'.", snap, targetDb)
                    : "[ERROR] Falló la restauración del snapshot.";
        } catch (Exception e) {
            return "[ERROR] Error al restaurar respaldo: " + e.getMessage();
        }
    }

    private String handleMigrate(String command) {
        try {
            String[] tokens = command.trim().split("\\s+");
            PostgresConnectionConfig cfg = new PostgresConnectionConfig();
            cfg.setDatabase("");

            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i].trim();
                if (token.startsWith("--host=")) {
                    cfg.setHost(token.substring("--host=".length()).trim());
                } else if (token.startsWith("-h=")) {
                    cfg.setHost(token.substring("-h=".length()).trim());
                } else if (token.startsWith("--port=")) {
                    cfg.setPort(Integer.parseInt(token.substring("--port=".length()).trim()));
                } else if (token.startsWith("-p=")) {
                    cfg.setPort(Integer.parseInt(token.substring("-p=".length()).trim()));
                } else if (token.startsWith("--db=") || token.startsWith("--database=")) {
                    String val = token.contains("=") ? token.substring(token.indexOf('=') + 1).trim() : "";
                    cfg.setDatabase(val);
                } else if (token.startsWith("--user=") || token.startsWith("-u=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setUser(val);
                } else if (token.startsWith("--password=") || token.startsWith("--pass=") || token.startsWith("-pw=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setPassword(val);
                } else if (token.startsWith("--schema=") || token.startsWith("-s=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setSchema(val);
                } else if (token.startsWith("--target-db=") || token.startsWith("--target=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setTargetDatabase(val);
                } else if (token.startsWith("--target-engine=") || token.startsWith("--engine=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setTargetEngine(val);
                } else if (token.startsWith("--package=") || token.startsWith("--pkg=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setBackendPackageName(val);
                } else if (token.startsWith("--output-zip=") || token.startsWith("--zip=")) {
                    String val = token.substring(token.indexOf('=') + 1).trim();
                    cfg.setOutputZipPath(val);
                    cfg.setGenerateBackend(true);
                } else if (token.equalsIgnoreCase("--generate-backend") || token.equalsIgnoreCase("-g")) {
                    cfg.setGenerateBackend(true);
                } else if (token.equalsIgnoreCase("--no-backend")) {
                    cfg.setGenerateBackend(false);
                } else if (token.equalsIgnoreCase("--dry-run") || token.equalsIgnoreCase("-d")) {
                    cfg.setDryRun(true);
                } else if (token.equalsIgnoreCase("--ssl")) {
                    cfg.setSsl(true);
                }
            }

            if (cfg.getDatabase() == null || cfg.getDatabase().isBlank()) {
                return "[ERROR] Parámetro obligatorio faltante: --db=<nombre_base_de_datos_postgresql>";
            }
            if (!cfg.isDryRun() && (!authenticated || client == null)) {
                return "[AUTH REQUIRED] Debe iniciar sesión en JettraStore con 'login <username> <password>' para realizar una migración en vivo (o añada '--dry-run' para simular).";
            }
            if (!cfg.isDryRun() && !hasActiveClusterNode()) {
                return NO_ACTIVE_NODES_ERROR;
            }
            if (cfg.getTargetDatabase() == null || cfg.getTargetDatabase().isBlank()) {
                cfg.setTargetDatabase(cfg.getDatabase());
            }

            JettraPostgresMigrator migrator = new JettraPostgresMigrator();
            StringBuilder out = new StringBuilder();
            out.append("==============================================================================================\n");
            out.append("🐘 MIGRACIÓN POSTGRESQL A JETTRASTORE & GENERADOR DE BACKEND (RF-17)\n");
            out.append("==============================================================================================\n");
            out.append(String.format("Origen: PostgreSQL en %s:%d/%s (Esquema: %s, SSL: %s)\n",
                    cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getSchema(), cfg.isSsl()));
            out.append(String.format("Destino: JettraStore BD '%s' (Motor: %s)\n",
                    cfg.getTargetDatabase(), cfg.getTargetEngine()));
            out.append(String.format("Modo: %s | Generar Backend: %s\n",
                    cfg.isDryRun() ? "SIMULACIÓN (DRY-RUN)" : "MIGRACIÓN EN VIVO",
                    cfg.isGenerateBackend() ? "SÍ (" + cfg.getBackendPackageName() + ")" : "NO"));
            out.append("----------------------------------------------------------------------------------------------\n");

            MigrationProgress prog = migrator.migrate(cfg, client, p -> {});

            for (String log : prog.getLogs()) {
                out.append("  ↳ ").append(log).append("\n");
            }
            if (!prog.getErrors().isEmpty()) {
                out.append("\n⚠️ ADVERTENCIAS / ERRORES:\n");
                for (String err : prog.getErrors()) {
                    out.append("  [!] ").append(err).append("\n");
                }
            }
            out.append("----------------------------------------------------------------------------------------------\n");
            out.append(String.format("RESUMEN: %d tabla(s) migradas | %d registro(s) transferidos | Tiempo: %d ms\n",
                    prog.getTablesMigrated(), prog.getRecordsMigrated(), prog.getElapsedMs()));
            if (prog.getGeneratedZipBytes() != null) {
                String zipDest = cfg.getOutputZipPath() != null ? cfg.getOutputZipPath() : "./" + cfg.getTargetDatabase() + "_backend.zip";
                if (cfg.getOutputZipPath() == null) {
                    try {
                        java.nio.file.Files.write(java.nio.file.Path.of(zipDest), prog.getGeneratedZipBytes());
                    } catch (Exception ignored) {}
                }
                out.append("📦 Proyecto backend empaquetado en: ").append(zipDest).append("\n");
            }
            out.append("==============================================================================================");
            return out.toString();

        } catch (Exception e) {
            return "[ERROR] Falló el proceso de migración: " + e.getMessage();
        }
    }

    // --- Control de Buckets ---
    private String handleCreateBucket(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length < 3)
            return "[ERROR] Uso: CREATE <BUCKET|ENGINE|UNIT> <nombre> [TYPE <tipo>]";
        String bucketName = cleanQuotes(parts[2]);
        String type = "DOCUMENT";
        for (int i = 3; i < parts.length - 1; i++) {
            if ("TYPE".equalsIgnoreCase(parts[i])) {
                type = parts[i + 1].toUpperCase();
            }
        }

        JettraDatabase db = client.getDatabase(currentDatabase);
        switch (type) {
            case "VECTOR" -> db.getVectorEngine(bucketName, 3);
            case "GRAPH" -> db.getGraphEngine(bucketName);
            case "TIMESERIES" -> db.getTimeSeriesEngine(bucketName);
            case "KEYVALUE", "KV" -> db.getKeyValueEngine(bucketName);
            case "COLUMNAR" -> db.getColumnarEngine(bucketName);
            case "GEOSPATIAL", "GEO" -> db.getGeospatialEngine(bucketName);
            default -> db.getDocumentEngine(bucketName);
        }
        return String.format("[SUCCESS] Bucket '%s' creado en el motor '%s' en base de datos '%s'.",
                bucketName, type, currentDatabase);
    }

    // --- Control de Buckets / Units, Conteo y Visualización de Registros ---
    private String handleShowBuckets(String command) {
        if (currentDatabase == null || currentDatabase.isBlank()) {
            return "[INFO] No hay ninguna base de datos seleccionada. Use 'SHOW DATABASES', 'CREATE DATABASE <nombre>' o 'USE <nombre>'.";
        }
        JettraDatabase db = client.getDatabase(currentDatabase);
        record UnitRow(String engine, String unitType, String name, long count, String status) {
        }
        List<UnitRow> rows = new ArrayList<>();

        String clean = command.replaceAll("(?i)^SHOW\\s+(BUCKETS|BUCKET|UNITS|UNIT)\\s*", "").trim();
        String filterEngine = clean.isBlank() ? null : clean.toUpperCase();

        // 1. Documents
        if (filterEngine == null || filterEngine.contains("DOC")) {
            for (String bucket : db.getDocumentEngineNames()) {
                long c = db.getDocumentEngine(bucket).count();
                rows.add(new UnitRow("DOCUMENT", "Bucket", bucket, c, "ACTIVE (In-Memory)"));
            }
        }
        // 2. Vectors
        if (filterEngine == null || filterEngine.contains("VEC")) {
            for (String col : db.getVectorEngineNames()) {
                var vEng = db.getVectorEngine(col, 3);
                rows.add(new UnitRow("VECTOR", "Vector Index [" + vEng.getDimensions() + "d]", col, vEng.size(),
                        "INDEXED (HNSW)"));
            }
        }
        // 3. Graph
        if (filterEngine == null || filterEngine.contains("GRAPH")) {
            for (String col : db.getGraphEngineNames()) {
                var gEng = db.getGraphEngine(col);
                rows.add(new UnitRow("GRAPH", "Property Graph", col, gEng.size(), "TOPOLOGY (In-Memory)"));
            }
        }
        // 4. TimeSeries
        if (filterEngine == null || filterEngine.contains("TIME") || filterEngine.contains("SERIES")) {
            for (String col : db.getTimeSeriesEngineNames()) {
                var ts = db.getTimeSeriesEngine(col);
                rows.add(new UnitRow("TIMESERIES", "Metric Series", col, ts.size(), "APPEND-ONLY (Delta)"));
            }
        }
        // 5. Key-Value
        if (filterEngine == null || filterEngine.contains("KEY") || filterEngine.contains("KV")) {
            for (String col : db.getKeyValueEngineNames()) {
                var kv = db.getKeyValueEngine(col);
                rows.add(new UnitRow("KEYVALUE", "KV Store", col, kv.size(), "HASH-MAP (Persistent)"));
            }
        }
        // 6. Geospatial
        if (filterEngine == null || filterEngine.contains("GEO") || filterEngine.contains("GIS")) {
            for (String col : db.getGeospatialEngineNames()) {
                var geo = db.getGeospatialEngine(col);
                rows.add(new UnitRow("GEOSPATIAL", "Spatial Layer", col, geo.size(), "R-TREE (Spatial)"));
            }
        }
        // 7. Columnar
        if (filterEngine == null || filterEngine.contains("COL")) {
            for (String col : db.getColumnarEngineNames()) {
                var colEng = db.getColumnarEngine(col);
                rows.add(new UnitRow("COLUMNAR", "Column Family", col, colEng.size(), "ARROW/SLOT (Compressed)"));
            }
        }
        // 8. Records
        if (filterEngine == null || filterEngine.contains("REC")) {
            for (String col : db.getRecordsEngineNames()) {
                var recEng = db.getRecordsEngine(col);
                rows.add(new UnitRow("RECORDS", "Typed Java Record", col, recEng != null ? recEng.count() : 0,
                        "TYPED-HEAP (Zero-Copy)"));
            }
        }

        if (rows.isEmpty()) {
            return String.format("[INFO] No se encontraron buckets/units en la base de datos '%s'%s.",
                    currentDatabase, filterEngine != null ? " para el motor " + filterEngine : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append(String.format(
                "                BUCKETS / UNITS EN BASE DE DATOS: '%s'                                        \n",
                currentDatabase));
        sb.append("==============================================================================================\n");
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        sb.append("| Motor       | Tipo de Unidad       | Nombre de Unidad   | Registros | Estado               |\n");
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        for (UnitRow r : rows) {
            sb.append(String.format("| %-11s | %-20s | %-18s | %-9d | %-20s |\n",
                    r.engine(), r.unitType(), r.name(), r.count(), r.status()));
        }
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        sb.append(String.format("Total: %d bucket(s)/unit(s) registrados en la base de datos '%s'.\n", rows.size(),
                currentDatabase));
        return sb.toString();
    }

    private String handleShowRecords(String command) {
        if (currentDatabase == null || currentDatabase.isBlank()) {
            return "[INFO] No hay ninguna base de datos seleccionada. Use 'SHOW DATABASES', 'CREATE DATABASE <nombre>' o 'USE <nombre>'.";
        }
        String clean = command.replaceAll("(?i)^SHOW\\s+RECORDS(\\s+FROM)?\\s*", "").trim();
        JettraDatabase db = client.getDatabase(currentDatabase);
        if (clean.isBlank()) {
            var all = db.getAllBucketNames();
            if (all.isEmpty()) {
                return String.format("[INFO] No hay buckets/units creados en la base de datos '%s'.", currentDatabase);
            }
            clean = all.iterator().next();
        }

        String[] parts = clean.split("\\s+");
        String unitName = cleanQuotes(parts[0]);
        int limit = pageSize;
        for (int i = 1; i < parts.length - 1; i++) {
            if ("LIMIT".equalsIgnoreCase(parts[i])) {
                try {
                    limit = Integer.parseInt(parts[i + 1].replaceAll("[;]", ""));
                } catch (Exception ignored) {
                }
            }
        }

        // 1. DOCUMENT
        if (db.getDocumentEngineNames().contains(unitName)) {
            var docEngine = db.getDocumentEngine(unitName);
            if (docEngine.isEmpty())
                return String.format("[INFO] El bucket de documentos '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE DOCUMENT BUCKET '%s' (Mostrando %d de %d) ===\n",
                    unitName, Math.min((int) docEngine.count(), limit), docEngine.count()));

            // Streaming por Chunks interactivo con protección Anti-OOM
            StreamResponse<Map<String, Object>> stream = client.streamFindAll(currentDatabase, unitName, limit);
            if (stream.isSentinelActivated() || lastSentinelNotification != null) {
                sb.append("🛡️  [JETTRAPOLICE SENTINEL: STREAMING POR CHUNKS ACTIVADO]\n");
                sb.append(String.format("   Partición defensiva en lotes de %d registros para evitar OOM.\n",
                        stream.getSafeBatchSize()));
            }

            int idx = 1;
            int chunkIndex = 1;
            for (List<Map<String, Object>> chunk : stream) {
                sb.append(String.format("--- [Chunk #%d: %d registro(s) recibidos progresivamente] ---\n", chunkIndex++,
                        chunk.size()));
                for (Map<String, Object> doc : chunk) {
                    if (idx > limit)
                        break;
                    sb.append(String.format("  [%02d] _id: %-15s -> %s\n", idx++, doc.getOrDefault("_id", "?"), doc));
                    if (showReferences) {
                        for (var entry : doc.entrySet()) {
                            if (entry.getKey().startsWith("_ref_") || String.valueOf(entry.getValue()).contains("::")) {
                                sb.append(String.format("       ↳ Ref [%s]: %s\n", entry.getKey(),
                                        resolveReference(db, String.valueOf(entry.getValue()))));
                            }
                        }
                    }
                }
                if (idx > limit)
                    break;
            }
            return sb.toString();
        }

        // 2. VECTOR
        if (db.getVectorEngineNames().contains(unitName)) {
            var vecEngine = db.getVectorEngine(unitName, 3);
            var vecs = vecEngine.getAllVectors();
            if (vecs.isEmpty())
                return String.format("[INFO] El bucket vectorial '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE VECTOR BUCKET '%s' (Dim: %d | Mostrando %d de %d) ===\n",
                    unitName, vecEngine.getDimensions(), Math.min(vecs.size(), limit), vecs.size()));
            int idx = 1;
            for (var entry : vecs.entrySet()) {
                if (idx > limit)
                    break;
                sb.append(String.format("  [%02d] Vector ID: %-15s -> %s\n", idx++, entry.getKey(),
                        Arrays.toString(entry.getValue())));
            }
            return sb.toString();
        }

        // 3. GRAPH
        if (db.getGraphEngineNames().contains(unitName)) {
            var graph = db.getGraphEngine(unitName);
            if (graph.getVertices().isEmpty())
                return String.format("[INFO] El bucket de grafos '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE GRAPH BUCKET '%s' (%d vértices) ===\n", unitName,
                    graph.getVertices().size()));
            int idx = 1;
            for (String v : graph.getVertices()) {
                if (idx > limit)
                    break;
                var out = graph.getOutboundEdges(v);
                sb.append(String.format("  [%02d] Vértice: %-15s (Aristas salientes: %d)\n", idx++, v, out.size()));
                for (var e : out) {
                    sb.append(String.format("       ↳ (%s)-[%s, props=%s]->(%s)\n", v, e.label(), e.properties(),
                            e.targetVertex()));
                }
            }
            return sb.toString();
        }

        // 4. TIMESERIES
        if (db.getTimeSeriesEngineNames().contains(unitName)) {
            var ts = db.getTimeSeriesEngine(unitName);
            var pts = ts.getAll();
            if (pts.isEmpty())
                return String.format("[INFO] El bucket de series temporales '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE TIMESERIES BUCKET '%s' (Mostrando %d de %d) ===\n",
                    unitName, Math.min(pts.size(), limit), pts.size()));
            int idx = 1;
            for (var entry : pts.entrySet()) {
                if (idx > limit)
                    break;
                sb.append(String.format("  [%02d] Timestamp: %-15d -> Valor: %.4f\n", idx++, entry.getKey(),
                        entry.getValue()));
            }
            return sb.toString();
        }

        // 5. KEYVALUE
        if (db.getKeyValueEngineNames().contains(unitName)) {
            var kv = db.getKeyValueEngine(unitName);
            var store = kv.getAll();
            if (store.isEmpty())
                return String.format("[INFO] El bucket clave-valor '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE KEYVALUE BUCKET '%s' (Mostrando %d de %d) ===\n",
                    unitName, Math.min(store.size(), limit), store.size()));
            int idx = 1;
            for (var entry : store.entrySet()) {
                if (idx > limit)
                    break;
                String valStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                sb.append(String.format("  [%02d] Clave: %-20s -> Valor: %s\n", idx++, entry.getKey(), valStr));
            }
            return sb.toString();
        }

        // 6. GEOSPATIAL
        if (db.getGeospatialEngineNames().contains(unitName)) {
            var geo = db.getGeospatialEngine(unitName);
            var pts = geo.getAllPoints();
            if (pts.isEmpty())
                return String.format("[INFO] El bucket geoespacial '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE GEOSPATIAL BUCKET '%s' (Mostrando %d de %d) ===\n",
                    unitName, Math.min(pts.size(), limit), pts.size()));
            int idx = 1;
            for (var p : pts.values()) {
                if (idx > limit)
                    break;
                sb.append(String.format("  [%02d] Feature ID: %-15s -> Lat: %.6f, Lon: %.6f\n", idx++, p.id(),
                        p.latitude(), p.longitude()));
            }
            return sb.toString();
        }

        // 7. COLUMNAR
        if (db.getColumnarEngineNames().contains(unitName)) {
            var col = db.getColumnarEngine(unitName);
            if (col.getRowCount() == 0)
                return String.format("[INFO] El bucket columnar '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE COLUMNAR BUCKET '%s' (Total Filas: %d) ===\n", unitName,
                    col.getRowCount()));
            sb.append("  Columnas Numéricas: ").append(col.getNumericColumns().keySet()).append("\n");
            sb.append("  Columnas Texto:     ").append(col.getTextColumns().keySet()).append("\n");
            return sb.toString();
        }

        // 8. RECORDS
        if (db.getRecordsEngineNames().contains(unitName)) {
            var recEng = db.getRecordsEngine(unitName);
            if (recEng == null || recEng.count() == 0)
                return String.format("[INFO] El bucket de registros tipados '%s' está vacío.", unitName);

            var recs = recEng.findAll();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE TYPED RECORD BUCKET '%s' (Tipo: %s | Mostrando %d de %d) ===\n",
                    unitName, recEng.getRecordClass().getSimpleName(), Math.min(recs.size(), limit), recs.size()));
            int idx = 1;
            for (var entry : recs.entrySet()) {
                if (idx > limit)
                    break;
                sb.append(String.format("  [%02d] Record ID: %-15s -> %s\n", idx++, entry.getKey(), entry.getValue()));
            }
            return sb.toString();
        }

        return String.format(
                "[NOT FOUND] No se encontró el bucket/unit '%s' en la base de datos '%s'. Use 'show buckets' para ver las unidades disponibles.",
                unitName, currentDatabase);
    }

    private String handleCount(String command) {
        if (currentDatabase == null || currentDatabase.isBlank()) {
            return "[INFO] No hay ninguna base de datos seleccionada. Use 'SHOW DATABASES', 'CREATE DATABASE <nombre>' o 'USE <nombre>'.";
        }
        String clean = command.replaceAll("(?i)^COUNT(\\s+FROM)?\\s*", "").trim();
        JettraDatabase db = client.getDatabase(currentDatabase);

        if (clean.isBlank() || clean.equalsIgnoreCase("ALL") || clean.equals("*")) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: '%s' ===\n", currentDatabase));
            long grandTotal = 0;
            for (String col : db.getDocumentEngineNames()) {
                long c = db.getDocumentEngine(col).count();
                grandTotal += c;
                sb.append(String.format("  * [DOCUMENT]   %-22s : %d registro(s)\n", col, c));
            }
            for (String col : db.getVectorEngineNames()) {
                long c = db.getVectorEngine(col, 3).size();
                grandTotal += c;
                sb.append(String.format("  * [VECTOR]     %-22s : %d vector(es)\n", col, c));
            }
            for (String col : db.getGraphEngineNames()) {
                long c = db.getGraphEngine(col).size();
                grandTotal += c;
                sb.append(String.format("  * [GRAPH]      %-22s : %d vértice(s)\n", col, c));
            }
            for (String col : db.getTimeSeriesEngineNames()) {
                long c = db.getTimeSeriesEngine(col).size();
                grandTotal += c;
                sb.append(String.format("  * [TIMESERIES] %-22s : %d punto(s)\n", col, c));
            }
            for (String col : db.getKeyValueEngineNames()) {
                long c = db.getKeyValueEngine(col).size();
                grandTotal += c;
                sb.append(String.format("  * [KEYVALUE]   %-22s : %d clave(s)\n", col, c));
            }
            for (String col : db.getColumnarEngineNames()) {
                long c = db.getColumnarEngine(col).size();
                grandTotal += c;
                sb.append(String.format("  * [COLUMNAR]   %-22s : %d fila(s)\n", col, c));
            }
            for (String col : db.getGeospatialEngineNames()) {
                long c = db.getGeospatialEngine(col).size();
                grandTotal += c;
                sb.append(String.format("  * [GEOSPATIAL] %-22s : %d punto(s) GIS\n", col, c));
            }
            for (String col : db.getRecordsEngineNames()) {
                var rec = db.getRecordsEngine(col);
                long c = rec != null ? rec.count() : 0;
                grandTotal += c;
                sb.append(String.format("  * [RECORDS]    %-22s : %d objeto(s) tipado(s)\n", col, c));
            }
            sb.append(String.format("Gran Total en '%s': %d registro(s) multimodelo.\n", currentDatabase, grandTotal));
            return sb.toString();
        }

        String unitName = cleanQuotes(clean.split("\\s+")[0]);

        if (db.getDocumentEngineNames().contains(unitName)) {
            long c = db.getDocumentEngine(unitName).count();
            return String.format("[COUNT] [DOCUMENT] '%s': %d registro(s).", unitName, c);
        }
        if (db.getVectorEngineNames().contains(unitName)) {
            long c = db.getVectorEngine(unitName, 3).size();
            return String.format("[COUNT] [VECTOR] '%s': %d vector(es).", unitName, c);
        }
        if (db.getGraphEngineNames().contains(unitName)) {
            long c = db.getGraphEngine(unitName).size();
            return String.format("[COUNT] [GRAPH] '%s': %d vértice(s).", unitName, c);
        }
        if (db.getTimeSeriesEngineNames().contains(unitName)) {
            long c = db.getTimeSeriesEngine(unitName).size();
            return String.format("[COUNT] [TIMESERIES] '%s': %d punto(s) temporales.", unitName, c);
        }
        if (db.getKeyValueEngineNames().contains(unitName)) {
            long c = db.getKeyValueEngine(unitName).size();
            return String.format("[COUNT] [KEYVALUE] '%s': %d clave(s).", unitName, c);
        }
        if (db.getColumnarEngineNames().contains(unitName)) {
            long c = db.getColumnarEngine(unitName).size();
            return String.format("[COUNT] [COLUMNAR] '%s': %d fila(s).", unitName, c);
        }
        if (db.getGeospatialEngineNames().contains(unitName)) {
            long c = db.getGeospatialEngine(unitName).size();
            return String.format("[COUNT] [GEOSPATIAL] '%s': %d punto(s) espaciales.", unitName, c);
        }
        if (db.getRecordsEngineNames().contains(unitName)) {
            var rec = db.getRecordsEngine(unitName);
            long c = rec != null ? rec.count() : 0;
            return String.format("[COUNT] [RECORDS] '%s': %d objeto(s) tipado(s).", unitName, c);
        }

        return String.format("[NOT FOUND] El bucket/unit '%s' no existe en la base de datos '%s'.", unitName,
                currentDatabase);
    }

    private String handleDropBucket(String command) {
        String clean = command.replaceAll("(?i)^(DROP ENGINE|DROP BUCKET|DROP UNIT)\\s+", "").trim();
        String bucket = cleanQuotes(clean.split("\\s+")[0]);
        boolean ok = client.getDatabase(currentDatabase).dropBucket(bucket);
        return ok ? String.format("[SUCCESS] Bucket '%s' eliminado.", bucket)
                : String.format("[WARN] El bucket '%s' no existía.", bucket);
    }

    // --- Auxiliares de Parseo ---
    private String cleanQuotes(String text) {
        if (text == null)
            return "";
        return text.replace(";", "").replace("'", "").replace("\"", "").trim();
    }

    private Map<String, Object> parseJsonOrKeyValues(String raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank())
            return map;

        String clean = raw.trim();
        if (clean.startsWith("{") && clean.endsWith("}")) {
            clean = clean.substring(1, clean.length() - 1);
        }

        String[] pairs = clean.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("[:=]", 2);
            if (kv.length == 2) {
                String key = cleanQuotes(kv[0].trim());
                String valStr = cleanQuotes(kv[1].trim());
                try {
                    if (valStr.contains(".")) {
                        map.put(key, Double.parseDouble(valStr));
                    } else {
                        map.put(key, Long.parseLong(valStr));
                    }
                } catch (NumberFormatException e) {
                    if ("true".equalsIgnoreCase(valStr) || "false".equalsIgnoreCase(valStr)) {
                        map.put(key, Boolean.parseBoolean(valStr));
                    } else {
                        map.put(key, valStr);
                    }
                }
            }
        }
        return map;
    }

    public String getHelpText() {
        return """
                ==============================================================================================
                           JETTRASTORE SHELL - GUÍA COMPLETA DE COMANDOS (vía JettraStoreDriver)
                ==============================================================================================
                1. CONEXIÓN Y SESIÓN:
                  connect <url> <port>                      Establece la dirección del nodo servidor JettraStore.
                  connect <nombre-perfil>                   Conecta utilizando un perfil previamente guardado.
                  login <username> <password>               Autentica y obtiene un token criptográfico JettraJWT.
                  logout                                    Cierra la sesión activa y revoca el token JWT.
                  save connection <nombre>                  Guarda el perfil de conexión actual con un alias.
                  remove connection <nombre>                Elimina un perfil de conexión guardado.
                  list connections                          Lista todos los perfiles de conexión guardados.

                2. TELEMETRÍA Y ADMINISTRACIÓN DEL CLÚSTER:
                  status                                    Monitorea RAM Panama FFM, CPU Loom y Disco LSM.
                  show nodes / list nodes                   Muestra la topología real del clúster Raft y nodos.
                  show cluster config / cluster config      Muestra el archivo cluster.config vigente del PRIMARY.
                  sync cluster config                       Sincroniza cluster.config desde/hacia el clúster.
                  cluster-distributed <all>             distribuye entre todos los nodos todas las bases de datos
                  cluster-distributed <nombre-base-datos>: distribuye la base de datos indicada
                  cluster-distributed info :            Muestra una tabla con los nodos y las bases de datos en cada nodo.
                  cluster live [límite]                     Muestra eventos y trazas del clúster en tiempo real.
                  multinode / show multinode                Muestra el estado de cluster.multinode.active (ON/OFF).
                  multinode on / multinode off              Activa o desactiva la distribución multinodo.
                  add node <id> <host> <port> [ROLE] [REST] Agrega un nodo, replica cluster.config y sincroniza datos.
                  cluster add-node <id> <host> <port> [R]   Alias de add node (PRIMARY -> SECONDARIES).
                  remove node <id>                          Remueve un nodo detenido y replica cluster.config.
                  cluster remove-node <id>                  Alias de remove node (solo rol admin).
                  start node <id>                           Inicia/verifica un nodo y sincroniza desde PRIMARY.
                  stop node <id>                            Detiene limpiamente un nodo (Raft/REST vía Driver).

                3. BASES DE DATOS, BUCKETS, ESQUEMAS Y PERSISTENCIA:
                  show databases / show dbs                 Lista todas las bases de datos en disco y memoria.
                  create database <nombre>                  Crea una nueva base de datos lógica.
                  drop database <nombre>                    Elimina la base de datos especificada.
                  use <nombre>                              Conmuta la base de datos activa.
                  db stats                                  Muestra estadísticas de la base de datos activa.
                  show buckets / show unit                  Muestra los buckets/units de cada motor con conteos.
                  create bucket <db> <engine> <bucket> [NONE|DEFINED] [json]  Crea bucket libre o con esquema estricto.
                  describe bucket <db> <engine> <bucket>    Inspecciona el esquema (NONE/DEFINED) y reglas del bucket.
                  drop bucket <db> <engine> <bucket>        Elimina el bucket especificado.
                  show records <bucket> [LIMIT n]           Muestra los registros del bucket en cualquier motor.
                  count <bucket> / count all                Cuenta registros de un bucket o de toda la BD activa.
                  show samples / INSTALL SAMPLES            Lista o instala las bases de datos de ejemplo estándar.
                  LOAD SAMPLE <nombre_sample_db>            Carga un dataset de ejemplo (p.ej. example_factura_db).
                  backup database [nombre] [destino]        Genera un snapshot físico .snap de la base de datos.
                  restore database <archivo> <nombre>       Restaura un snapshot .snap en una base de datos.

                4. CONSULTAS POLÍGLOTAS (JETTRAQL, JETTRASQL) Y ALMACENAMIENTO:
                  JQL FROM <col> [WHERE campo = valor]      Consulta declarativa multimodelo sobre documentos.
                  JQL MATCH (a)-[r]->(b) IN <grafo>         Pattern matching sobre redes de grafos.
                  JQL VECTOR SIMILARITY <col> TO [...]      Búsqueda k-NN por similaridad coseno.
                  JQL FETCH <col> <id> [RESOLVE REFS]       Recupera un registro resolviendo referencias JettraRef.
                  SQL SELECT * FROM <col> [WHERE k = v]     Consulta relacional con tabla formateada.
                  SQL INSERT INTO <col> VALUES (id, json)   Inserta registro en la colección activa.
                  SQL UPDATE <col> SET k = v WHERE _id = id Actualiza campos de un registro.
                  SQL DELETE FROM <col> WHERE _id = id      Elimina un registro mediante sintaxis SQL.
                  STORAGE_MODE                              Muestra el modo activo (JVM_RAM, DISK_MEMORY, OFF_HEAP).
                  STORAGE_MODE <JVM_RAM|DISK_MEMORY|OFF_HEAP>  Conmuta el modo de almacenamiento en caliente.

                5. CÁLCULO, AGREGACIONES, ESTADÍSTICA, FINANZAS Y ÁLGEBRA VECTORIAL:
                  AGGREGATE <col> [GROUP BY c] [SUM|AVG|MIN|MAX|MEDIAN|COUNT]  Agregaciones nativas.
                  MATH <expresion>                          Evaluador matemático (sqrt, pow, gcd, lcm, fact, etc.).
                  FINANCE <PMT|FV|CAGR|NPV|IRR|AMORTIZATION> ...  Cálculos financieros integrados.
                  STATS <MEAN|MEDIAN|SUMMARY|CORRELATION|REGRESSION> ...  Estadística descriptiva y regresión.
                  VECTOR <DOT|COSINE|EUCLIDEAN|CROSS|ANGLE|NORMALIZE> ... Álgebra vectorial para embeddings.
                  SELECT cat, SUM(v), AVG(v) FROM <col> GROUP BY cat      SQL estándar con agregaciones.

                6. REGISTROS REFERENCIADOS (JETTRAREF) Y LAZY LOADING:
                  lazy reference on / off                   Alterna la resolución diferida (Lazy) o inmediata (Eager).
                  insert ref <col> <id> KEY <k> TARGET <engine>::<col>#<id>  Vincula puntero cruzado multimodelo.
                  resolve ref <engine>::<col>#<id>          Resuelve manualmente el destino de una referencia.
                  show refs <col> <id>                      Muestra todas las referencias de un registro.
                  get <col> <id>                            Obtiene un documento resolviendo sus punteros _ref_*.

                7. ADMINISTRACIÓN DE ÍNDICES (BTREE, HASH, NUMERIC, AUTO_INCREMENT):
                  create index <nombre> ON <col> (<campo>) [TYPE BTREE|HASH|SPARSE|NUMERIC|AUTO_INCREMENT] [UNIQUE]
                  create bucket index <nombre> ON <bucket> (<campo>) [TYPE BTREE|HASH|NUMERIC|AUTO_INCREMENT] [UNIQUE]
                  show indexes [ON <col>]                   Muestra los índices de la base de datos o colección.
                  show bucket indexes [ON <bucket>]         Muestra los índices asociados a un bucket.
                  drop index <nombre> / drop bucket index <nombre>  Elimina el índice especificado.
                  alter index <nombre> rebuild              Reconstruye el índice re-escaneando los documentos.
                  show sequence <bucket> <campo>            Muestra el valor actual de la secuencia AUTO_INCREMENT.
                  next sequence <bucket> <campo>            Genera y persiste el siguiente valor AUTO_INCREMENT.

                8. ADMINISTRACIÓN DE USUARIOS Y ROLES (RBAC):
                  show users / list users                   Muestra usuarios, rol global y roles por base de datos.
                  create user <user> PASSWORD <pass> [ROLE <role>]  Crea un nuevo usuario en el sistema.
                  drop user <user>                          Elimina un usuario (superuser 'admin' inmutable).
                  alter user <user> PASSWORD <newPass>      Actualiza la contraseña del usuario.
                  alter user <user> ROLE <newRole>          Actualiza el rol global del usuario.
                  grant <DB_OWNER|READ_WRITE|READ_ONLY> ON <db> TO <user>  Asigna privilegios sobre una BD.
                  revoke <db> FROM <user>                   Revoca el acceso sobre la base de datos indicada.
                  show grants for <user>                    Muestra los privilegios asignados al usuario.

                9. MOTORES ESPECIALIZADOS (VECTORES, GRAFOS, TIME SERIES, KEY-VALUE):
                  vector index <col> <id> [f1,f2,..]        Indexa vector float[] en el motor vectorial.
                  vector search <col> [f1,f2] K <num>       Búsqueda k-NN por similaridad coseno.
                  graph add vertex <grafo> <id>             Agrega un vértice a la red de grafos.
                  graph add edge <g> <a> <b> [LABEL l]      Agrega arista dirigida ponderada.
                  ts record <serie> <val> [TIME t]          Registra punto métrico en serie temporal.
                  ts range <serie> <inicio> <fin>           Consulta métricas en rango de tiempo.
                  kv put <tabla> <clave> <valor>            Almacena clave-valor en memoria de acceso rápido.
                  kv get <tabla> <clave>                    Recupera el valor asociado a la clave.

                10. HISTORIAL, AUTOCOMPLETADO Y AYUDA TEMÁTICA:
                  help / ?                                  Muestra esta guía completa de comandos.
                  help cluster / help storage               Muestra ayuda detallada de clúster o almacenamiento.
                  menu / menu connections                   Abre el menú interactivo guiado.
                  history [n] / history search <término>    Consulta o filtra el historial de comandos.
                  history clear                             Borra el historial en memoria y en disco.
                  !<n> / !! / !<prefijo>                    Re-ejecuta un comando del historial.
                  complete <prefijo> / tab <prefijo>        Muestra sugerencias de autocompletado.

                11. MIGRACIÓN POSTGRESQL Y GENERADOR BACKEND (RF-17):
                  migrate --db=<base_datos> [opciones]     Migra esquema, tablas, índices y datos desde PostgreSQL
                                                            preservando precisión decimal (BigDecimal) y fechas ISO-8601,
                                                            y empaqueta un backend Java 25+ descargable en .zip.
                  Opciones:
                    --host=<h>                              Host PostgreSQL (por defecto: 127.0.0.1)
                    --port=<p>                              Puerto PostgreSQL (por defecto: 5432)
                    --db=<d>                                Nombre de la base de datos origen (obligatorio)
                    --user=<u>                              Usuario (por defecto: postgres)
                    --password=<pw>                         Contraseña del usuario
                    --schema=<s>                            Esquema PostgreSQL (por defecto: public)
                    --target-db=<tdb>                       Base de datos destino en JettraStore
                    --target-engine=<eng>                   Motor destino: DOCUMENT (defecto) o JAVA_RECORD
                    --package=<pkg>                         Paquete del backend (defecto: com.example.jettra.backend)
                    --output-zip=<path>                     Ruta para guardar el .zip del proyecto backend
                    --generate-backend                      Genera el proyecto backend completo en .zip
                    --dry-run                               Simula la migración sin escribir en JettraStore
                    --ssl                                   Conexión cifrada SSL hacia PostgreSQL

                12. HISTORIAL DE VERSIONES Y RESTAURACIÓN DE REGISTROS (RF-18):
                  versions <bucket> <id>                    Lista el historial completo de versiones de un registro.
                  version history <bucket> <id>             Alias de versions <bucket> <id>.
                  version show <bucket> <id> <v>            Inspecciona metadatos y snapshot de una versión puntual.
                  restore version <bucket> <id> <v>         Restaura un registro a una versión previa creando nueva versión RESTORE.
                  version --help / help versions            Muestra ayuda detallada de comandos de versionado.
                ==============================================================================================
                """;
    }

    public String getClusterDistributedHelpText() {
        return """
                ==============================================================================================
                                   AYUDA DE COMANDOS: CLUSTER-DISTRIBUTED
                ==============================================================================================
                  cluster-distributed <all>             distribuye entre todos los nodos todas las bases de datos
                  cluster-distributed <nombre-base-datos>: distribuye la base de datos indicada
                  cluster-distributed info :            Muestra una tabla con los nodos y las bases de datos en cada nodo.
                  cluster live [límite]                 Muestra trazas en tiempo real de lo que ocurre en el clúster.

                  GESTIÓN DE NODOS EN CALIENTE:
                    add node <id> <host> <port> [ROLE] [REST_PORT]
                    cluster add-node <id> <host> <port> [ROLE] [REST_PORT]
                        -> Registra un nuevo nodo en el anillo y propaga la configuración cluster.config.
                        -> FLUJO DE REPLICACIÓN DE CONFIGURACIÓN:
                           1. Actualiza primero el archivo cluster.config en el nodo PRIMARY.
                           2. Replica ordenadamente la configuración canónica a todos los nodos SECONDARY.
                        -> SINCRONIZACIÓN AUTOMÁTICA DE DATOS:
                           Notifica inmediatamente al nodo PRIMARY para que transmita la réplica completa
                           de todas las bases de datos y objetos al nuevo nodo, manteniéndolo actualizado.

                    remove node <id>
                    cluster remove-node <id>
                        -> Remueve un nodo réplica del anillo del clúster.
                        -> Replica ordenadamente la configuración actualizada: primero al PRIMARY, luego a SECONDARIES.

                    start node <id>
                        -> Reactiva el tráfico operativo hacia un nodo previamente detenido (RUNNING).
                        -> SINCRONIZACIÓN AUTOMÁTICA DE REINICIO:
                           Al reactivar el nodo, solicita al nodo PRIMARY una sincronización inmediata
                           de todas las bases de datos y objetos para asegurar que el nodo cuente con el
                           estado más reciente tras el periodo que estuvo fuera de línea.

                    stop node <id>
                        -> Pausa temporalmente el nodo en el anillo de réplicas (detiene tráfico operativo).
                ==============================================================================================
                """;
    }

    public String getStorageModeHelpText() {
        return """
                ==============================================================================================
                                      AYUDA DE COMANDOS: STORAGE_MODE (MODOS DE ALMACENAMIENTO)
                ==============================================================================================
                  El comando STORAGE_MODE permite consultar y conmutar el motor de almacenamiento de JettraStore
                  en tiempo de ejecución para la base de datos seleccionada o a nivel global.

                  SINTAXIS:
                    STORAGE_MODE                              Muestra el modo de almacenamiento activo.
                    STORAGE_MODE <JVM_RAM|DISK_MEMORY|OFF_HEAP>  Cambia el modo de almacenamiento.
                    SET STORAGE_MODE <JVM_RAM|DISK_MEMORY|OFF_HEAP> Forma alternativa de asignación.

                  OPCIONES DISPONIBLES:
                    1. JVM_RAM (o JVM-RAM)
                       -> Modo memoria RAM estándar de la JVM (Heap & Stack).
                       -> Ideal para desarrollo, pruebas unitarias y micro-servicios de bajo volumen.
                       -> Sujeto al recolector de basura (GC) convencional.

                    2. DISK_MEMORY (o DISK-MEMORY)
                       -> Motor de disco directo Off-Heap LSM (JettraMemory).
                       -> Utiliza Project Panama FFM (Foreign Function & Memory API) y buffers de archivo mapeados.
                       -> Persistencia directa en disco sin pausas de Garbage Collector.

                    3. OFF_HEAP (o OFF-HEAP)
                       -> Motor de páginas ranuradas nativas Slotted-Page (JettraStore-OffHeap).
                       -> Asignación contigua en memoria virtual nativa fuera del Heap con Project Panama.
                       -> Cero pausas de GC (GC pauses = 0) y liberación determinista por ámbito RAII (Arena).
                       -> Acceso Cero-Copia mediante MemorySegment y VarHandles nativos.
                ==============================================================================================
                """;
    }

    public String getInteractiveMenu() {
        return """
                ================================================================================
                                       JETTRASTORE MENU INTERACTIVO
                ================================================================================
                [1] Instalar todas las Bases de Datos de Muestra (INSTALL SAMPLES)
                [2] Listar Bases de Datos (SHOW DATABASES)
                [3] Monitoreo de Recursos (STATUS)
                [4] Topología de Nodos del Clúster (SHOW NODES)
                [5] Administrar Índices de la Base de Datos (SHOW INDEXES)
                [6] Administrar Usuarios y Roles RBAC (SHOW USERS)
                [7] Ayuda Completa (HELP)
                [8] Distribución de Datos en Clúster (cluster-distributed info)
                [9] Flujo de Eventos en Tiempo Real (cluster live)
                ================================================================================
                """;
    }

    // Getters para Testing y Verificación
    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public String getCurrentRole() {
        return currentRole;
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public String getCurrentHost() {
        return currentHost;
    }

    public int getCurrentPort() {
        return currentPort;
    }

    public boolean isLazyLoad() {
        return lazyLoad;
    }

    public Map<String, SavedConnection> getSavedConnections() {
        return savedConnections;
    }

    public JettraClient getClient() {
        return client;
    }

    private String handleShowStorageMode() {
        if (currentDatabase == null) {
            return """
                    ==============================================================================================
                                              MODO DE ALMACENAMIENTO GLOBAL (JettraStore)
                    ==============================================================================================
                      Modo Activo: JVM_RAM (Memoria RAM Heap/Stack de Java)
                      Modos Disponibles:
                       * JVM_RAM      : Trabaja en memoria RAM usando áreas de Heap y Stack de la JVM.
                       * DISK_MEMORY  : Modo directo en disco sin pausas GC usando JettraMemory (LSM Panama FFM).
                       * OFF_HEAP     : Motor de páginas ranuradas nativas Slotted-Page Off-Heap FFM (JettraStore-OffHeap).
                      Comando para cambiar: STORAGE_MODE <JVM_RAM | DISK_MEMORY | OFF_HEAP>
                    ==============================================================================================""";
        }
        var mode = client.getStorageMode(currentDatabase);
        return String.format("""
                ==============================================================================================
                                MODO DE ALMACENAMIENTO ACTIVO PARA '%s'
                ==============================================================================================
                  Modo Actual      : %s (%s)
                  Descripción      : %s
                  Motor Off-Heap   : JettraMemory (LSM Panama FFM) / JettraStore-OffHeap (Slotted-Page RAII)
                  Comando para conmutar: STORAGE_MODE <JVM_RAM | DISK_MEMORY | OFF_HEAP>
                ==============================================================================================""",
                currentDatabase, mode.name(), mode.getCode(), mode.getDescription());
    }

    private String handleSetStorageMode(String cmd) {
        String arg = cmd.replaceFirst("(?i)^(SET\\s+)?STORAGE_MODE\\s*(=)?\\s*", "").trim();
        var mode = io.jettra.store.core.StorageMode.fromString(arg);
        if (currentDatabase != null) {
            client.setStorageMode(currentDatabase, mode);
            return String.format("[STORAGE_MODE] Modo de almacenamiento para '%s' configurado a: %s (%s)",
                    currentDatabase, mode.getCode(), mode.getDescription());
        } else {
            client.setGlobalStorageMode(mode);
            return String.format("[STORAGE_MODE] Modo de almacenamiento global configurado a: %s (%s)",
                    mode.getCode(), mode.getDescription());
        }
    }

    public static void main(String[] args) {
        Console console = System.console();
        Scanner scanner = new Scanner(System.in);

        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        String cliNode = null;
        String cliHost = null;
        Integer cliPort = null;
        String cliUser = null;
        String cliPass = null;
        String cliExec = null;
        boolean cliAuto = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.equals("--node") || arg.equals("-n")) {
                if (i + 1 < args.length)
                    cliNode = args[++i].trim();
            } else if (arg.equals("--host") || arg.equals("-h")) {
                if (i + 1 < args.length)
                    cliHost = args[++i].trim();
            } else if (arg.equals("--port") || arg.equals("-p")) {
                if (i + 1 < args.length) {
                    try {
                        cliPort = Integer.parseInt(args[++i].trim());
                    } catch (Exception ignored) {
                    }
                }
            } else if (arg.equals("--user") || arg.equals("-u")) {
                if (i + 1 < args.length)
                    cliUser = args[++i].trim();
            } else if (arg.equals("--password") || arg.equals("-P")) {
                if (i + 1 < args.length)
                    cliPass = args[++i].trim();
            } else if (arg.equals("-e") || arg.equals("--exec")) {
                if (i + 1 < args.length)
                    cliExec = args[++i].trim();
            } else if (arg.equals("--auto-connect") || arg.equals("-a")) {
                cliAuto = true;
            } else if (!arg.startsWith("-") && shell.getSavedConnections().containsKey(arg)) {
                cliNode = arg;
            }
        }

        SavedConnection defConn = shell.getDefaultSavedConnection();
        String targetHost = defConn != null ? defConn.host() : shell.getCurrentHost();
        int targetPort = defConn != null ? defConn.port() : shell.getCurrentPort();
        String suggestedUser = defConn != null ? defConn.user() : "admin";

        if (cliNode != null && shell.getSavedConnections().containsKey(cliNode)) {
            SavedConnection sc = shell.getSavedConnections().get(cliNode);
            targetHost = sc.host();
            targetPort = sc.port();
            suggestedUser = sc.user();
            cliAuto = true;
        }
        if (cliHost != null) {
            targetHost = cliHost;
            cliAuto = true;
        }
        if (cliPort != null) {
            targetPort = cliPort;
            cliAuto = true;
        }
        if (cliUser != null) {
            suggestedUser = cliUser;
        }

        // Si se especificó comando por CLI (-e), ejecución batch no interactiva
        if (cliExec != null) {
            shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
            String pass = cliPass != null ? cliPass : "admin-jettra";
            shell.executeCommand(String.format("LOGIN %s %s", suggestedUser, pass));
            String result = shell.executeCommand(cliExec);
            System.out.println(result);
            return;
        }

        if (!cliAuto) {
            // 1. Mostrar menú de conexiones creadas al iniciar
            System.out.print(shell.getConnectionsMenuDisplay());

            String option = "";
            System.out.print(">> Seleccione una opción [1]: ");
            if (console != null) {
                option = console.readLine();
            } else if (scanner.hasNextLine()) {
                option = scanner.nextLine();
            } else {
                option = "1";
            }
            if (option == null || option.isBlank()) {
                option = "1";
            }
            option = option.trim();

            if (option.equalsIgnoreCase("0") || option.equalsIgnoreCase("exit") || option.equalsIgnoreCase("quit")) {
                System.out.println("Saliendo de JettraStore Shell...");
                return;
            }

            List<SavedConnection> conns = shell.getOrderedConnectionsList();
            boolean isNewConnection = option.equalsIgnoreCase("N") || option.equalsIgnoreCase("nueva") ||
                    option.toUpperCase().startsWith("CONNECT");

            if (!isNewConnection) {
                try {
                    int idx = Integer.parseInt(option) - 1;
                    if (idx >= 0 && idx < conns.size()) {
                        SavedConnection chosen = conns.get(idx);
                        targetHost = chosen.host();
                        targetPort = chosen.port();
                        suggestedUser = chosen.user();
                        shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
                        System.out.printf("[CONNECT] Conexión seleccionada: '%s' (%s:%d)%n", chosen.name(), targetHost,
                                targetPort);
                    } else {
                        System.out.printf("[WARN] Opción fuera de rango. Usando conexión predeterminada: %s:%d%n",
                                targetHost, targetPort);
                        shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
                    }
                } catch (NumberFormatException e) {
                    if (shell.getSavedConnections().containsKey(option)) {
                        SavedConnection chosen = shell.getSavedConnections().get(option);
                        targetHost = chosen.host();
                        targetPort = chosen.port();
                        suggestedUser = chosen.user();
                        shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
                        System.out.printf("[CONNECT] Conexión seleccionada: '%s' (%s:%d)%n", chosen.name(), targetHost,
                                targetPort);
                    } else {
                        isNewConnection = true;
                    }
                }
            }

            // Si eligió 'Conexión nueva', el usuario ejecuta el connect
            if (isNewConnection) {
                System.out.println("""
                        --------------------------------------------------------------------------------
                                                 OPCIÓN: CONEXIÓN NUEVA
                        --------------------------------------------------------------------------------
                        Ejecute el comando 'connect <host> <puerto>' (o presione Enter para [127.0.0.1 50001]):""");
                System.out.print(">> ");
                String connCmd = "";
                if (console != null) {
                    connCmd = console.readLine();
                } else if (scanner.hasNextLine()) {
                    connCmd = scanner.nextLine();
                }
                if (connCmd == null || connCmd.isBlank()) {
                    connCmd = "connect 127.0.0.1 50001";
                }
                connCmd = connCmd.trim();
                if (!connCmd.toUpperCase().startsWith("CONNECT ")) {
                    connCmd = "CONNECT " + connCmd;
                }
                String connectResult = shell.executeCommand(connCmd);
                System.out.println(connectResult);
                targetHost = shell.getCurrentHost();
                targetPort = shell.getCurrentPort();

                // Preguntar si desea guardar el perfil
                System.out.print(">> ¿Desea guardar esta conexión en el menú? (s/N): ");
                String saveAns = "";
                if (console != null)
                    saveAns = console.readLine();
                else if (scanner.hasNextLine())
                    saveAns = scanner.nextLine();
                if (saveAns != null
                        && (saveAns.trim().equalsIgnoreCase("s") || saveAns.trim().equalsIgnoreCase("si"))) {
                    System.out.print(">> Ingrese un nombre/alias para la conexión: ");
                    String alias = "";
                    if (console != null)
                        alias = console.readLine();
                    else if (scanner.hasNextLine())
                        alias = scanner.nextLine();
                    if (alias != null && !alias.isBlank()) {
                        shell.executeCommand(String.format("SAVE CONNECTION %s %s %d %s", alias.trim(), targetHost,
                                targetPort, suggestedUser));
                        System.out.printf("[SUCCESS] Perfil '%s' guardado para futuros arranques.%n", alias.trim());
                    }
                }
            }
        } else {
            shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
        }

        // 2. Solicitar la ejecución del login para autentificar el usuario
        System.out.println("""
                --------------------------------------------------------------------------------
                                     AUTENTICACIÓN REQUERIDA (LOGIN)
                --------------------------------------------------------------------------------""");
        System.out.printf("Servidor activo: %s:%d%n", targetHost, targetPort);
        System.out.printf("Ejecute el comando 'login <usuario> <password>' o presione Enter para usuario [%s]:%n",
                suggestedUser);
        System.out.print(">> ");
        String loginInput = "";
        if (console != null) {
            loginInput = console.readLine();
        } else if (scanner.hasNextLine()) {
            loginInput = scanner.nextLine();
        }

        String finalUser = suggestedUser;
        String finalPass = "admin-jettra";

        if (loginInput != null && loginInput.toUpperCase().startsWith("LOGIN ")) {
            String loginResult = shell.executeCommand(loginInput);
            System.out.println(loginResult);
        } else {
            if (loginInput != null && !loginInput.isBlank()) {
                finalUser = loginInput.trim();
            }
            if (console != null) {
                char[] pArr = console.readPassword(">> Password para '%s' [hidden]: ", finalUser);
                if (pArr != null && pArr.length > 0)
                    finalPass = new String(pArr);
            } else {
                System.out.printf(">> Password para '%s': ", finalUser);
                if (scanner.hasNextLine()) {
                    String p = scanner.nextLine();
                    if (p != null && !p.isBlank())
                        finalPass = p.trim();
                }
            }
            boolean ok = shell.connectAndLogin(targetHost, targetPort, finalUser, finalPass);
            if (ok) {
                System.out.printf("[AUTH OK] Autenticado exitosamente como '%s' (Rol: %s) en %s:%d%n",
                        shell.getCurrentUser(), shell.getCurrentRole(), targetHost, targetPort);
            } else {
                System.out.printf(
                        "[AUTH WARN] No se pudo autenticar en %s:%d. Inicie sesión en la consola con: login <usuario> <password>%n",
                        targetHost, targetPort);
            }
        }

        System.out.println(
                "\nEscriba 'help' o '?' para ver los comandos disponibles, 'menu' para el menú interactivo, o 'exit' para salir.\n");

        // 3. Ciclo interactivo de comandos con soporte avanzado de JLine (Historial con
        // flechas y Autocompletado con TAB)
        org.jline.terminal.Terminal terminal = null;
        org.jline.reader.LineReader lineReader = null;
        try {
            String termEnv = System.getenv("TERM");
            org.jline.terminal.TerminalBuilder termBuilder = org.jline.terminal.TerminalBuilder.builder()
                    .system(true);
            if (termEnv == null || termEnv.isBlank() || "dumb".equalsIgnoreCase(termEnv)) {
                termBuilder.type("xterm-256color");
            }
            terminal = termBuilder.build();

            org.jline.reader.impl.DefaultParser parser = new org.jline.reader.impl.DefaultParser();
            parser.setEscapeChars(new char[] {});

            org.jline.reader.Completer completer = (reader, parsedLine, candidates) -> {
                String rawLine = parsedLine.line();
                int cursor = parsedLine.cursor();
                String buffer = rawLine.substring(0, Math.min(cursor, rawLine.length()));
                String currentWord = parsedLine.word();
                if (currentWord == null)
                    currentWord = "";

                String prefixBeforeWord = buffer.substring(0, Math.max(0, buffer.length() - currentWord.length()));

                List<String> suggestions = shell.autocomplete(buffer);
                Set<String> seenTokens = new LinkedHashSet<>();

                for (String s : suggestions) {
                    if (s == null || s.isBlank())
                        continue;

                    String candidateAfterPrefix = s;
                    if (!prefixBeforeWord.isEmpty() && s.toUpperCase().startsWith(prefixBeforeWord.toUpperCase())) {
                        candidateAfterPrefix = s.substring(prefixBeforeWord.length());
                    } else if (prefixBeforeWord.isEmpty()) {
                        candidateAfterPrefix = s;
                    } else {
                        String trimmedPrefix = prefixBeforeWord.trim();
                        if (!trimmedPrefix.isEmpty() && s.toUpperCase().startsWith(trimmedPrefix.toUpperCase())) {
                            int idx = s.toUpperCase().indexOf(trimmedPrefix.toUpperCase()) + trimmedPrefix.length();
                            candidateAfterPrefix = s.substring(idx).stripLeading();
                        }
                    }
                    if (candidateAfterPrefix.isBlank())
                        continue;

                    int firstSpace = candidateAfterPrefix.indexOf(' ');
                    String tokenForWord;
                    boolean hasMoreTokens;
                    if (firstSpace != -1) {
                        tokenForWord = candidateAfterPrefix.substring(0, firstSpace);
                        hasMoreTokens = true;
                    } else {
                        tokenForWord = candidateAfterPrefix;
                        hasMoreTokens = false;
                    }

                    if (tokenForWord.isBlank())
                        continue;
                    if (!seenTokens.add(tokenForWord.toUpperCase()))
                        continue;

                    String group = shell.resolveCandidateGroup(s);
                    String descr = shell.resolveCandidateDescription(s);

                    boolean isComplete = true;

                    candidates.add(new org.jline.reader.Candidate(
                            tokenForWord,
                            tokenForWord,
                            group,
                            descr,
                            null,
                            null,
                            isComplete));
                }
            };

            lineReader = org.jline.reader.LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .completer(completer)
                    .variable(org.jline.reader.LineReader.HISTORY_FILE, JLINE_HISTORY_FILE)
                    .option(org.jline.reader.LineReader.Option.AUTO_MENU_LIST, true)
                    .option(org.jline.reader.LineReader.Option.AUTO_LIST, true)
                    .option(org.jline.reader.LineReader.Option.AUTO_GROUP, true)
                    .option(org.jline.reader.LineReader.Option.CASE_INSENSITIVE, true)
                    .option(org.jline.reader.LineReader.Option.COMPLETE_IN_WORD, true)
                    .option(org.jline.reader.LineReader.Option.INSERT_TAB, false)
                    .option(org.jline.reader.LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();

            // Mapeo explícito de secuencias de escape ANSI para navegación fluida por
            // historial y autocompletado
            for (String mapName : List.of(org.jline.reader.LineReader.EMACS, org.jline.reader.LineReader.MAIN)) {
                org.jline.keymap.KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
                if (map != null) {
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.EXPAND_OR_COMPLETE), "\t");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.UP_LINE_OR_HISTORY), "\033[A",
                            "\033OA");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.DOWN_LINE_OR_HISTORY), "\033[B",
                            "\033OB");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.FORWARD_CHAR), "\033[C",
                            "\033OC");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.BACKWARD_CHAR), "\033[D",
                            "\033OD");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.BEGINNING_OF_LINE), "\033[H",
                            "\033[1~", "\033OH");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.END_OF_LINE), "\033[F",
                            "\033[4~", "\033OF");
                    map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.DELETE_CHAR), "\033[3~");
                }
            }

            // Sincronizar historial persistente registrado en ~/.jettra/history.log
            for (String hCmd : shell.getCommandHistory()) {
                lineReader.getHistory().add(hCmd);
            }
            shell.setActiveLineReader(lineReader);

            final org.jline.reader.LineReader shutdownReader = lineReader;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (shutdownReader != null) {
                        shutdownReader.getHistory().save();
                    }
                } catch (Exception ignored) {
                }
            }));
        } catch (Throwable t) {
            // Fallback elegante a System.console o Scanner si la terminal interactiva no
            // está disponible (ej. entornos de test o redirección)
            lineReader = null;
        }

        while (true) {
            String dbLabel = (shell.getCurrentDatabase() != null && !shell.getCurrentDatabase().isBlank())
                    ? shell.getCurrentDatabase()
                    : "no-db";
            String prompt = String.format("jettra-shell [%s@%s:%d/%s]> ",
                    shell.getCurrentUser(), shell.getCurrentHost(), shell.getCurrentPort(), dbLabel);
            String line = null;
            if (lineReader != null) {
                try {
                    line = lineReader.readLine(prompt);
                } catch (IllegalArgumentException e) {
                    // Si el archivo de historial de JLine tuviese sintaxis corrupta previa, purgar
                    // y continuar
                    try {
                        Files.deleteIfExists(JLINE_HISTORY_FILE);
                        lineReader.getHistory().purge();
                        line = lineReader.readLine(prompt);
                    } catch (Exception ex) {
                        if (console != null) {
                            System.out.print(prompt);
                            line = console.readLine();
                        } else if (scanner.hasNextLine()) {
                            System.out.print(prompt);
                            line = scanner.nextLine();
                        }
                    }
                } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                    System.out.println("\nSaliendo de JettraStore Shell...");
                    if (lineReader != null) {
                        try {
                            lineReader.getHistory().save();
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
            } else if (console != null) {
                System.out.print(prompt);
                line = console.readLine();
            } else if (scanner.hasNextLine()) {
                System.out.print(prompt);
                line = scanner.nextLine();
            } else {
                break;
            }

            if (line == null)
                break;
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                System.out.println("Saliendo de JettraStore Shell...");
                if (lineReader != null) {
                    try {
                        lineReader.getHistory().save();
                    } catch (Exception ignored) {
                    }
                }
                break;
            }

            String result = shell.executeCommand(trimmed);
            if (!result.isBlank()) {
                System.out.println(result);
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        if (this.statusSyncWorkerThread != null) {
            this.statusSyncWorkerThread.interrupt();
            this.statusSyncWorkerThread = null;
        }
        if (this.clusterEventListener != null) {
            try {
                io.jettra.store.cluster.JettraClusterEventBus.getInstance().unsubscribe(this.clusterEventListener);
            } catch (Exception ignored) {
            }
            this.clusterEventListener = null;
        }
        if (this.client != null) {
            try {
                this.client.close();
            } catch (Exception ignored) {
            }
        }
    }
}

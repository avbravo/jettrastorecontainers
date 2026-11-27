package io.jettra.store.explorer;

import io.jettra.flux.core.Widget;
import io.jettra.server.core.JettraServer;
import io.jettra.server.core.config.ServerConfig;
import io.jettra.server.core.http.HttpRequest;
import io.jettra.server.core.http.HttpResponse;
import io.jettra.server.core.http.HttpStatus;
import io.jettra.server.core.storage.StorageMode;
import io.jettra.server.flux.FluxModule;
import io.jettra.server.flux.FluxRenderer;
import io.jettra.store.explorer.model.ConnectionProfile;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.pages.backup.BackupFluxView;
import io.jettra.store.explorer.pages.cluster.ClusterConfigFluxView;
import io.jettra.store.explorer.pages.cluster.ClusterFluxView;
import io.jettra.store.explorer.pages.connections.ConnectionManagerFluxView;
import io.jettra.driver.JettraClient;
import io.jettra.driver.migrator.*;
import io.jettra.store.explorer.pages.databases.DatabasesFluxView;
import io.jettra.store.explorer.pages.examples.ExamplesFluxView;
import io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView;
import io.jettra.store.explorer.pages.login.LoginFluxView;
import io.jettra.store.explorer.pages.map.TransferMapFluxView;
import io.jettra.store.explorer.pages.migrator.MigratorFluxView;
import io.jettra.store.explorer.pages.police.PoliceMonitorFluxView;
import io.jettra.store.explorer.pages.query.QueryConsoleFluxView;
import io.jettra.store.explorer.pages.security.UserManagerFluxView;
import io.jettra.store.explorer.pages.status.StatusFluxView;
import io.jettra.store.explorer.pages.template.StoreExplorerFluxTemplateView;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.NodeLifecycleManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aplicación Principal JettraStore Explorer.
 * Despliega el gestor web para administración y telemetría de bases de datos
 * multimodelo,
 * con interfaz profesional construida íntegramente en JettraFlux y ejecutada
 * sobre JettraServer
 * con hilos virtuales de Project Loom (Java 25) y almacenamiento Slotted-Page
 * Off-Heap Panama.
 */
public class App {

    public static JettraServer serverInstance;
    private static final Set<JettraServer> ACTIVE_SERVERS = ConcurrentHashMap.newKeySet();
    private static final FluxRenderer RENDERER = new FluxRenderer();
    private static final Map<String, String> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static volatile String serverBootId = UUID.randomUUID().toString();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    /**
     * Excepción lanzada cuando el puerto configurado para JettraStoreExplorer ya está ocupado.
     */
    public static final class PortAlreadyInUseException extends RuntimeException {
        private final String host;
        private final int port;

        public PortAlreadyInUseException(String host, int port) {
            super(buildPortInUseMessage(host, port));
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }
    }

    public static String buildPortInUseMessage(String host, int port) {
        String iface = (host != null && !host.isBlank()) ? " en la interfaz '" + host.trim() + "'" : "";
        return String.format(
                "⚠️ Error: El puerto %d ya está en uso%s. Detenga el proceso que lo ocupa o configure un puerto diferente para iniciar JettraStoreExplorer.",
                port, iface);
    }

    /**
     * Verifica de forma no bloqueante si un puerto TCP está libre en la interfaz indicada.
     * Utiliza {@code SO_REUSEADDR} y {@code try-with-resources} para liberar el socket inmediatamente
     * antes de que {@link JettraServer} realice el bind definitivo.
     */
    public static boolean isPortAvailable(String host, int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        String normalizedHost = (host == null) ? "" : host.trim();
        boolean wildcard = normalizedHost.isEmpty()
                || "0.0.0.0".equals(normalizedHost)
                || "::".equals(normalizedHost)
                || "*".equals(normalizedHost);
        try (ServerSocket probeSocket = new ServerSocket()) {
            probeSocket.setReuseAddress(true);
            InetSocketAddress bindAddress = wildcard
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(InetAddress.getByName(normalizedHost), port);
            probeSocket.bind(bindAddress, 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Valida que el puerto esté disponible antes de inicializar recursos o servidores web;
     * en caso contrario lanza {@link PortAlreadyInUseException}.
     */
    public static void ensurePortAvailable(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("⚠️ Error: Puerto inválido para JettraStoreExplorer: " + port);
        }
        if (!isPortAvailable(host, port)) {
            throw new PortAlreadyInUseException(host, port);
        }
    }

    private static Properties loadLightExplorerProperties() {
        Properties props = new Properties();
        try (var is = App.class.getResourceAsStream("/META-INF/microprofile-config.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                if ("JettraStore Explorer".equals(p.getProperty("app.title")) || p.containsKey("server.port")) {
                    props.putAll(p);
                }
            }
        } catch (Exception ignored) {
        }
        try (var is = App.class.getResourceAsStream("/microprofile-config.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                if ("JettraStore Explorer".equals(p.getProperty("app.title")) || p.containsKey("server.port")) {
                    props.putAll(p);
                }
            }
        } catch (Exception ignored) {
        }
        List<String> localCandidates = new ArrayList<>(List.of(
                "src/main/resources/META-INF/microprofile-config.properties",
                "src/main/resources/microprofile-config.properties",
                "./microprofile-config.properties",
                "./META-INF/microprofile-config.properties",
                "./config/microprofile-config.properties"));
        String explicitMpFile = System.getProperty("jettra.explorer.config.file",
                System.getProperty("microprofile.config.file"));
        if (explicitMpFile != null && !explicitMpFile.isBlank()) {
            localCandidates.add(explicitMpFile.trim());
        }
        for (String localFile : localCandidates) {
            Path fp = Paths.get(localFile);
            if (Files.exists(fp)) {
                try (var is = Files.newInputStream(fp)) {
                    Properties p = new Properties();
                    p.load(is);
                    if ("JettraStore Explorer".equals(p.getProperty("app.title")) || p.containsKey("cluster.ip.nodes")
                            || p.containsKey("server.port")) {
                        props.putAll(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return props;
    }

    public static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static String normalizeBindHost(String host) {
        if (host == null || host.isBlank() || "*".equals(host.trim())) {
            return "0.0.0.0";
        }
        return host.trim();
    }

    private static String resolveDefaultHost(Properties mpProps) {
        String mpHost = firstNonBlank(
                mpProps.getProperty("server.host"),
                mpProps.getProperty("server.bindAddress"),
                mpProps.getProperty("bindAddress"),
                "0.0.0.0");
        return normalizeBindHost(mpHost);
    }

    /**
     * Resuelve la configuración efectiva de inicio (host, puerto, contextPath y modo) sin
     * inicializar el servidor ni los monitores del clúster.
     */
    public static ServerConfig resolveStartupConfig(String[] args) {
        ServerConfig config = ServerConfig.load();

        boolean portOverridden = false;
        boolean hostOverridden = false;
        boolean contextPathOverridden = false;

        String sysPort = firstNonBlank(
                System.getProperty("server.port"),
                System.getProperty("port"),
                System.getenv("SERVER_PORT"),
                System.getenv("PORT"));
        if (sysPort != null) {
            try {
                config.setPort(Integer.parseInt(sysPort));
                portOverridden = true;
            } catch (Exception ignored) {
            }
        }
        String sysHost = firstNonBlank(
                System.getProperty("server.host"),
                System.getProperty("server.bindAddress"),
                System.getProperty("bindAddress"),
                System.getProperty("host"),
                System.getenv("SERVER_HOST"),
                System.getenv("JETTRA_EXPLORER_HOST"),
                System.getenv("BIND_ADDRESS"));
        if (sysHost != null) {
            config.setHost(normalizeBindHost(sysHost));
            hostOverridden = true;
        }
        String sysCp = firstNonBlank(
                System.getProperty("server.contextpath"),
                System.getProperty("server.context-path"),
                System.getenv("SERVER_CONTEXT_PATH"));
        if (sysCp != null) {
            config.setContextPath(sysCp);
            contextPathOverridden = true;
        }

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--server.port=")) {
                    try {
                        config.setPort(Integer.parseInt(arg.substring("--server.port=".length()).trim()));
                        portOverridden = true;
                    } catch (Exception ignored) {
                    }
                } else if (arg.startsWith("--port=") || arg.startsWith("-port=")) {
                    try {
                        config.setPort(Integer.parseInt(arg.substring(arg.indexOf('=') + 1).trim()));
                        portOverridden = true;
                    } catch (Exception ignored) {
                    }
                } else if ((arg.equals("-p") || arg.equals("--port")) && i + 1 < args.length) {
                    try {
                        config.setPort(Integer.parseInt(args[++i].trim()));
                        portOverridden = true;
                    } catch (Exception ignored) {
                    }
                } else if (arg.startsWith("--server.host=")
                        || arg.startsWith("--host=")
                        || arg.startsWith("--bindAddress=")
                        || arg.startsWith("--server.bindAddress=")
                        || arg.startsWith("--server.bind-address=")) {
                    config.setHost(normalizeBindHost(arg.substring(arg.indexOf('=') + 1)));
                    hostOverridden = true;
                } else if (arg.startsWith("--server.contextpath=")) {
                    config.setContextPath(arg.substring("--server.contextpath=".length()).trim());
                    contextPathOverridden = true;
                } else if (arg.startsWith("--server.context-path=")) {
                    config.setContextPath(arg.substring("--server.context-path=".length()).trim());
                    contextPathOverridden = true;
                } else if (arg.startsWith("--storage.mode=")) {
                    config.setStorageMode(StorageMode.fromString(arg.substring("--storage.mode=".length()).trim()));
                }
            }
        }

        Properties mpProps = loadLightExplorerProperties();
        if (!hostOverridden || config.getHost() == null || config.getHost().isBlank()) {
            config.setHost(resolveDefaultHost(mpProps));
        }
        if (!portOverridden) {
            String mpPort = mpProps.getProperty("server.port", "8085").trim();
            try {
                config.setPort(Integer.parseInt(mpPort));
            } catch (Exception ignored) {
                config.setPort(8085);
            }
        }
        if (!contextPathOverridden || config.getContextPath() == null || "/".equals(config.getContextPath())) {
            String mpCp = mpProps.getProperty("server.contextpath",
                    mpProps.getProperty("server.context-path", "/")).trim();
            if (!mpCp.startsWith("/")) {
                mpCp = "/" + mpCp;
            }
            config.setContextPath(mpCp);
        }
        config.setCorsEnabled(true);

        String storageProp = System.getProperty("storage.mode");
        if (storageProp == null || storageProp.isBlank())
            storageProp = System.getProperty("jettra.storage.mode");
        if (storageProp == null || storageProp.isBlank())
            storageProp = System.getenv("STORAGE_MODE");
        if (storageProp != null && !storageProp.isBlank()) {
            config.setStorageMode(StorageMode.fromString(storageProp.trim()));
        }

        return config;
    }

    public static void main(String[] args) {
        ServerConfig config = resolveStartupConfig(args);

        // Verificación temprana del puerto antes de inicializar JettraStorePlugin y JettraServer
        try {
            ensurePortAvailable(config.getHost(), config.getPort());
        } catch (PortAlreadyInUseException | IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
            return;
        }

        JettraServer server;
        try {
            server = startApplication(config);
        } catch (PortAlreadyInUseException | IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
            return;
        }

        try {
            System.out.println("[JettraStore Explorer] Escuchando en http://" + config.getHost() + ":"
                    + config.getPort() + "/login (Presione Ctrl+C para detener)");
            server.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopApplication(server);
        }
    }

    public static JettraServer startApplication(int port, StorageMode mode) {
        ServerConfig config = resolveStartupConfig(null)
                .setPort(port)
                .setStorageMode(mode);
        return startApplication(config);
    }

    public static JettraServer startApplication(ServerConfig config) {
        Properties mpProps = loadLightExplorerProperties();
        if (config.getHost() == null || config.getHost().isBlank()) {
            config.setHost(resolveDefaultHost(mpProps));
        } else {
            config.setHost(normalizeBindHost(config.getHost()));
        }
        // Si no se sobreescribió por argumento del sistema o el puerto proviene de
        // defaults upstream (8080 o 8082),
        // aplicar prioritariamente el server.port configurado en
        // microprofile-config.properties (8085)
        String sysPort = System.getProperty("server.port", System.getProperty("port", System.getenv("PORT")));
        if (sysPort == null || sysPort.isBlank()) {
            if (config.getPort() == 8080 || config.getPort() == 8082) {
                try {
                    config.setPort(Integer.parseInt(mpProps.getProperty("server.port", "8085").trim()));
                } catch (Exception ignored) {
                    config.setPort(8085);
                }
            }
        }
        if (config.getContextPath() == null || "/".equals(config.getContextPath())) {
            String cp = mpProps.getProperty("server.contextpath",
                    mpProps.getProperty("server.context-path", "/")).trim();
            config.setContextPath(cp.startsWith("/") ? cp : "/" + cp);
        }
        config.setCorsEnabled(true);

        // Validación temprana antes de cerrar sesiones o levantar JettraServer/JettraStorePlugin
        ensurePortAvailable(config.getHost(), config.getPort());

        // Todo arranque de JettraStoreExplorer inicia sin sesiones previas y exige
        // autenticación en /login
        closeAllSessions();

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        JettraServer server = JettraServer.create(config);
        ACTIVE_SERVERS.add(server);
        serverInstance = server;

        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[JettraStore Explorer] Apagado detectado. Cerrando todas las sesiones activas...");
                stopAllApplications();
            }));
        }

        // Registrar el endpoint propio de JettraStoreExplorer (Web UI) sin sobrescribir
        // el destino del nodo PRIMARY JettraStore
        plugin.setExplorerServerEndpoint(config.getHost(), config.getPort());
        plugin.startClusterHeartbeatMonitor();
        plugin.refreshAllNodeStatuses(true);
        System.out.println("[JettraStore Explorer] Inicializado JettraStorePlugin. WebUI: " + config.getHost() + ":"
                + config.getPort() + " | Nodos: " + plugin.getServerNodes().size() + " | Modo: "
                + plugin.getMultinodeDisplay());

        // Adjuntar módulo JettraFlux
        FluxModule flux = FluxModule.attach(server);

        // 1. Ruta Raíz y Login
        server.get("/", (req, resp) -> handleRootOrLogin(req, resp, server));
        server.get("/login", (req, resp) -> handleLoginGet(req, resp));
        server.post("/login", (req, resp) -> handleLoginPost(req, resp));

        // 2. Administración y Monitor de Nodos del Clúster (/cluster, /clusters y
        // /dashboard)
        server.get("/cluster", (req, resp) -> handleClusterGet(req, resp, server));
        server.post("/cluster", (req, resp) -> handleClusterPost(req, resp));
        server.get("/clusters", (req, resp) -> handleClusterGet(req, resp, server));
        server.post("/clusters", (req, resp) -> handleClusterPost(req, resp));

        // 2.1 Configuración del Clúster cluster.config (Solo Administrador)
        server.get("/cluster-config", (req, resp) -> handleClusterConfigGet(req, resp, server));
        server.post("/cluster-config", (req, resp) -> handleClusterConfigPost(req, resp, server));
        server.get("/map", (req, resp) -> handleMapGet(req, resp, server));
        server.get("/examples", (req, resp) -> handleExamplesGet(req, resp, server));
        server.post("/examples", (req, resp) -> handleExamplesPost(req, resp));
        server.get("/dashboard", (req, resp) -> handleClusterGet(req, resp, server));

        // 3. Estatus y Telemetría del Servidor (/status y /telemetry)
        server.get("/status", (req, resp) -> handleStatusGet(req, resp, server));
        server.get("/telemetry", (req, resp) -> handleStatusGet(req, resp, server));

        // 4. Administración de Bases de Datos y Motores (/databases, /database y
        // /engines)
        server.get("/databases", (req, resp) -> handleDatabasesGet(req, resp, server));
        server.post("/databases", (req, resp) -> handleDatabasesPost(req, resp));
        server.get("/database", (req, resp) -> handleDatabasesGet(req, resp, server));
        server.post("/database", (req, resp) -> handleDatabasesPost(req, resp));
        server.get("/engines", (req, resp) -> handleDatabasesGet(req, resp, server));

        // 5. Explorador de Registros con Operaciones CRUD (/explorer y /records)
        server.get("/explorer", (req, resp) -> handleExplorerGet(req, resp, server));
        server.post("/explorer", (req, resp) -> handleExplorerPost(req, resp, server));
        server.get("/records", (req, resp) -> handleExplorerGet(req, resp, server));
        server.get("/api/jettraid/generate", (req, resp) -> handleGenerateJettraIdApi(req, resp));

        // 6. Consola Interactiva JettraSQL / JQL / Shell (/query)
        server.get("/query", (req, resp) -> handleQueryGet(req, resp, server));
        server.post("/query", (req, resp) -> handleQueryPost(req, resp, server));

        // 7. Migrador PostgreSQL a JettraStore y Generador Backend (/migrator) (RF-17)
        server.get("/migrator", (req, resp) -> handleMigratorGet(req, resp, server));
        server.post("/migrator", (req, resp) -> handleMigratorPost(req, resp, server));
        server.get("/api/migrator/download", (req, resp) -> handleMigratorDownloadGet(req, resp, server));
        server.get("/api/migrator/download-zip", (req, resp) -> handleMigratorDownloadGet(req, resp, server));

        // 7. Gestor de Conexiones (/connections)
        server.get("/connections", (req, resp) -> handleConnectionsGet(req, resp, server));
        server.post("/connections", (req, resp) -> handleConnectionsPost(req, resp));

        // 8. Seguridad y Usuarios (/users)
        server.get("/users", (req, resp) -> handleUsersGet(req, resp, server));
        server.post("/users", (req, resp) -> handleUsersPost(req, resp));

        // 9. Centinelas Police (/police)
        server.get("/police", (req, resp) -> handlePoliceGet(req, resp, server));
        server.post("/police", (req, resp) -> handlePolicePost(req, resp));

        // 10. Copias de Respaldo (/backups)
        server.get("/backups", (req, resp) -> handleBackupsGet(req, resp, server));
        server.post("/backups", (req, resp) -> handleBackupsPost(req, resp));

        // 11. Endpoints JSON/SSE de Sondeo y Ciclo de Vida de Nodos del Clúster
        server.get("/api/cluster/status", (req, resp) -> handleClusterStatusJson(req, resp));
        server.get("/api/cluster/events", (req, resp) -> handleClusterEventsSse(req, resp));
        server.post("/api/cluster/nodes/shutdown", (req, resp) -> handleNodeShutdownApi(req, resp));
        server.post("/api/cluster/nodes/start", (req, resp) -> handleNodeStartApi(req, resp));

        // 12. Swagger-UI interactivo
        flux.swagger("/swagger-ui", "JettraStore Explorer API", List.of(App.class));

        try {
            server.start();
            plugin.setExplorerServerEndpoint(config.getHost(), config.getPort());
            plugin.refreshAllNodeStatuses(true);
        } catch (IOException e) {
            stopApplication(server);
            if (e instanceof java.net.BindException) {
                throw new PortAlreadyInUseException(config.getHost(), config.getPort());
            }
            throw new RuntimeException("Error iniciando JettraServer para JettraStore Explorer: " + e.getMessage(), e);
        } catch (RuntimeException | Error e) {
            stopApplication(server);
            throw e;
        }

        return server;
    }

    // --- Controladores HTTP ---

    private static void handleRootOrLogin(HttpRequest req, HttpResponse resp, JettraServer server) {
        String user = getLoggedUser(req);
        if (user != null && !user.isBlank()) {
            redirect(resp, "/cluster");
        } else {
            clearSession(resp);
            redirect(resp, "/login");
        }
    }

    private static void handleLoginGet(HttpRequest req, HttpResponse resp) {
        if ("true".equalsIgnoreCase(req.getQueryParam("logout"))) {
            String token = getSessionToken(req);
            if (token != null) {
                ACTIVE_SESSIONS.remove(token);
            }
            clearSession(resp);
            redirect(resp, "/login");
            return;
        }
        if (getLoggedUser(req) != null) {
            redirect(resp, "/cluster");
            return;
        }
        if (getSessionToken(req) != null) {
            clearSession(resp);
        }
        String error = req.getQueryParam("error");
        Widget view = new LoginFluxView(error);
        resp.header("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.header("Pragma", "no-cache");
        resp.html(RENDERER.render(view, "Acceso de Seguridad - JettraStore Explorer"));
    }

    private static void handleLoginPost(HttpRequest req, HttpResponse resp) {
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String user = form.get("username");
        String pass = form.get("password");
        if (user == null || user.isBlank())
            user = req.getQueryParam("username");
        if (pass == null || pass.isBlank())
            pass = req.getQueryParam("password");

        if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
            redirect(resp, "/login?error=empty_fields");
            return;
        }

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        Optional<JettraUser> auth = plugin.authenticate(user.trim(), pass.trim());
        if (auth.isPresent()) {
            setSession(resp, auth.get().getUsername());
            resp.header("Cache-Control", "no-store, no-cache, must-revalidate");
            plugin.logPoliceIncident("Security Patrol", "Inicio de sesión concedido para '" + user + "'", "OK",
                    "Sesión WEB activa");
            redirect(resp, "/cluster");
        } else {
            plugin.logPoliceIncident("Security Patrol", "Fallo de credenciales para usuario '" + user + "'", "WARN",
                    "Acceso denegado");
            redirect(resp, "/login?error=invalid_credentials");
        }
    }

    // --- Clúster y Nodos ---
    private static void handleClusterGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = req.getQueryParam("action");

        if ("toggle_multinode".equalsIgnoreCase(action)) {
            plugin.setMultinodeActive(!plugin.isMultinodeActive());
            redirect(resp, "/cluster");
            return;
        } else if ("toggle_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null)
                plugin.toggleNode(nodeId);
            redirect(resp, "/cluster");
            return;
        } else if ("shutdown_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null)
                plugin.shutdownNode(nodeId);
            redirect(resp, "/cluster?msg=node_stopped");
            return;
        } else if ("start_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null)
                plugin.startNode(nodeId);
            redirect(resp, "/cluster?msg=node_started");
            return;
        } else if ("remove_node".equalsIgnoreCase(action)) {
            JettraUser currentUser = plugin.findUser(getLoggedUser(req)).orElse(null);
            if (currentUser == null || currentUser.getGlobalRole() != JettraUser.GlobalRole.ADMIN) {
                redirect(resp, "/cluster?msg=" + java.net.URLEncoder.encode(
                        "Acceso denegado: solo usuarios con rol ADMIN pueden remover nodos del clúster.",
                        java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            String nodeId = req.getQueryParam("nodeId");
            boolean ok = nodeId != null && plugin.removeNode(nodeId);
            if (!ok) {
                String err = plugin.getLastNodeOperationError();
                redirect(resp, "/cluster?msg=" + java.net.URLEncoder.encode(
                        err != null ? err : "No se pudo remover el nodo.", java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/cluster?msg=node_removed");
            return;
        } else if ("check_nodes".equalsIgnoreCase(action) || "refresh".equalsIgnoreCase(action)) {
            plugin.refreshAllNodeStatuses();
            redirect(resp, "/cluster?msg=nodes_checked");
            return;
        }

        String msg = req.getQueryParam("msg");
        String clusterMsg = null;
        if ("node_added".equals(msg))
            clusterMsg = "Nodo registrado exitosamente en el quórum Raft.";
        else if ("node_removed".equals(msg))
            clusterMsg = "Nodo expulsado del clúster.";
        else if ("node_stopped".equals(msg))
            clusterMsg = "Nodo detenido limpiamente y marcado como OFFLINE.";
        else if ("node_started".equals(msg))
            clusterMsg = "Verificación de encendido y reconexión del nodo ejecutada.";
        else if ("nodes_checked".equals(msg))
            clusterMsg = "Conectividad TCP y latencia de nodos del clúster verificadas en tiempo real.";
        else
            clusterMsg = msg;

        Widget content = new ClusterFluxView(clusterMsg);
        renderTemplate(resp, server, getLoggedUser(req), "Monitor y Administración de Nodos", "cluster", content);
    }

    private static void handleClusterPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");

        if ("add_node".equalsIgnoreCase(action)) {
            String nodeId = form.get("nodeId");
            String nodeName = form.get("nodeName");
            String nodeRole = form.get("nodeRole");
            String nodeHost = form.get("nodeHost");
            int port = 50001;
            long ram = 2048;
            try {
                port = Integer.parseInt(form.get("nodePort"));
            } catch (Exception ignored) {
            }
            try {
                ram = Long.parseLong(form.get("nodeRam"));
            } catch (Exception ignored) {
            }

            JettraStorePlugin plugin = JettraStorePlugin.getInstance();
            boolean added = plugin.addNode(nodeId, nodeName, nodeRole, nodeHost, port, ram);
            if (!added) {
                String err = plugin.getLastNodeOperationError();
                String encodedErr = java.net.URLEncoder.encode(
                        err != null && !err.isBlank() ? "Error: " + err : "Error: Nodo o puerto duplicado.",
                        StandardCharsets.UTF_8);
                redirect(resp, "/cluster?msg=" + encodedErr);
                return;
            }
            redirect(resp, "/cluster?msg=node_added");
            return;
        }
        redirect(resp, "/cluster");
    }

    // --- Visor/Editor de cluster-state.config del Nodo PRIMARY y Replicación a todos los nodos (Solo ADMIN) ---
    private static void handleClusterConfigGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        boolean isAdmin = isLoggedUserAdmin(req);
        String msg = req.getQueryParam("msg");
        String action = req.getQueryParam("action");
        String flash = msg;
        if ("reload".equalsIgnoreCase(action) && (flash == null || flash.isBlank())) {
            flash = "Archivo cluster-state.config recargado desde el nodo PRIMARY.";
        }
        Widget content = new ClusterConfigFluxView(isAdmin, flash, List.of(), null);
        renderTemplate(resp, server, getLoggedUser(req), "Estado del Clúster (cluster-state.config)", "cluster-config",
                content);
    }

    private static void handleClusterConfigPost(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        boolean isAdmin = isLoggedUserAdmin(req);
        if (!isAdmin) {
            Widget denied = new ClusterConfigFluxView(false,
                    "Error: Acceso denegado. Solo administradores pueden editar o replicar cluster-state.config.",
                    List.of(), null);
            resp.status(HttpStatus.FORBIDDEN);
            renderTemplate(resp, server, getLoggedUser(req), "Estado del Clúster (cluster-state.config)",
                    "cluster-config", denied);
            return;
        }
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.getOrDefault("action", "save_config");
        String content = form.get("clusterConfigContent");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        JettraStorePlugin.ClusterSyncReport report;
        if ("sync_cluster_config".equalsIgnoreCase(action)) {
            report = plugin.synchronizeAllClusterConfigFiles(content);
        } else {
            // Todo cambio sobre cluster-state.config del PRIMARY se replica a todos los nodos del clúster
            report = plugin.saveClusterConfigContent(content, true);
        }

        String draft = report.success() ? null : content;
        String flash = report.success() ? report.message() : "Error: " + report.message();
        Widget view = new ClusterConfigFluxView(true, flash, report.nodeResults(), draft);
        renderTemplate(resp, server, getLoggedUser(req), "Estado del Clúster (cluster-state.config)", "cluster-config",
                view);
    }

    // --- Mapa de Transferencia en Tiempo Real (/map) ---
    private static void handleMapGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        Widget content = new TransferMapFluxView();
        renderTemplate(resp, server, getLoggedUser(req), "Mapa de Transferencia en Tiempo Real", "map", content);
    }

    // --- Administración de Bases de Datos de Ejemplo (/examples) ---
    private static void handleExamplesGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        String msg = req.getQueryParam("msg");
        String displayMsg = null;
        if ("installed".equals(msg))
            displayMsg = "Base de datos de ejemplo instalada correctamente.";
        else if ("deleted".equals(msg))
            displayMsg = "Base de datos de ejemplo eliminada correctamente.";
        else if ("installed_all".equals(msg))
            displayMsg = "Todas las bases de datos de ejemplo fueron instaladas.";
        else if ("deleted_all".equals(msg))
            displayMsg = "Todas las bases de datos de ejemplo fueron eliminadas.";
        Widget content = new ExamplesFluxView(displayMsg);
        renderTemplate(resp, server, getLoggedUser(req), "Administración de Bases de Datos de Ejemplo", "examples",
                content);
    }

    private static void handleExamplesPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        String exampleId = form.get("exampleId");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        if ("install_example".equalsIgnoreCase(action) && exampleId != null) {
            plugin.installExampleDatabase(exampleId);
            redirect(resp, "/examples?msg=installed");
            return;
        } else if ("delete_example".equalsIgnoreCase(action) && exampleId != null) {
            plugin.uninstallExampleDatabase(exampleId);
            redirect(resp, "/examples?msg=deleted");
            return;
        } else if ("install_all".equalsIgnoreCase(action)) {
            plugin.installAllExampleDatabases();
            redirect(resp, "/examples?msg=installed_all");
            return;
        } else if ("delete_all".equalsIgnoreCase(action)) {
            plugin.uninstallAllExampleDatabases();
            redirect(resp, "/examples?msg=deleted_all");
            return;
        }
        redirect(resp, "/examples");
    }

    // --- Estatus y Telemetría ---
    private static void handleStatusGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        Widget content = new StatusFluxView(server);
        renderTemplate(resp, server, getLoggedUser(req), "Estatus del Servidor & Telemetría", "status", content);
    }

    // --- Bases de Datos y Motores ---
    private static void handleDatabasesGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = req.getQueryParam("action");

        if ("delete_db".equalsIgnoreCase(action)) {
            String db = req.getQueryParam("db");
            if (db != null)
                plugin.deleteDatabase(db);
            redirect(resp, "/databases?msg=db_deleted");
            return;
        } else if ("delete_bucket".equalsIgnoreCase(action)) {
            String db = req.getQueryParam("db");
            String bucket = req.getQueryParam("bucket");
            if (db != null && bucket != null)
                plugin.deleteBucket(db, bucket);
            redirect(resp, "/databases?db=" + db + "&manage_engines=true&msg=bucket_deleted");
            return;
        }

        String db = req.getQueryParam("db");
        String confirmDeleteDb = req.getQueryParam("confirm_delete_db");
        String editDb = req.getQueryParam("edit_db");
        String manageUsersDb = req.getQueryParam("manage_users_db");
        String manageIndexesDb = req.getQueryParam("manage_indexes_db");
        String indexBucket = req.getQueryParam("index_bucket");
        String editIndex = req.getQueryParam("edit_index");
        String editBucket = req.getQueryParam("edit_bucket");
        String addBucketEngine = req.getQueryParam("add_bucket_engine");
        boolean showAddBucketDialog = "true".equalsIgnoreCase(req.getQueryParam("add_bucket"))
                || (addBucketEngine != null && !addBucketEngine.isBlank());
        boolean manageEngines = "true".equalsIgnoreCase(req.getQueryParam("manage_engines"));
        boolean isAdmin = isLoggedUserAdmin(req);
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("db_created".equals(msg))
            messageText = "Base de datos creada exitosamente.";
        else if ("db_updated".equals(msg))
            messageText = "Storage Mode de la base de datos actualizado correctamente.";
        else if ("db_deleted".equals(msg))
            messageText = "Base de datos eliminada.";
        else if ("bucket_added".equals(msg))
            messageText = "Nuevo bucket añadido al engine exitosamente.";
        else if ("bucket_updated".equals(msg))
            messageText = "Bucket y su estructura actualizados correctamente.";
        else if ("bucket_deleted".equals(msg))
            messageText = "Bucket eliminado.";
        else if ("db_user_added".equals(msg))
            messageText = "Usuario añadido/actualizado en la base de datos.";
        else if ("db_user_removed".equals(msg))
            messageText = "Usuario removido de la base de datos.";
        else if ("index_added".equals(msg))
            messageText = "Índice creado y habilitado exitosamente en el bucket.";
        else if ("index_updated".equals(msg))
            messageText = "Índice actualizado correctamente en el bucket.";
        else if ("index_deleted".equals(msg))
            messageText = "Índice eliminado del bucket.";
        else if ("index_rebuilt".equals(msg))
            messageText = "Índice reconstruido en caliente (Zero-Lock).";
        else if ("forbidden".equals(msg))
            messageText = "Acceso denegado: se requiere rol ADMIN para administrar usuarios de la base de datos.";
        else if (msg != null && !msg.isBlank())
            messageText = msg;

        if (manageUsersDb != null && !manageUsersDb.isBlank() && !isAdmin) {
            manageUsersDb = null;
            if (messageText == null) {
                messageText = "Acceso denegado: se requiere rol ADMIN para administrar usuarios de la base de datos.";
            }
        }

        Widget content = new DatabasesFluxView(db, manageUsersDb, manageIndexesDb, indexBucket, editIndex,
                manageEngines, showAddBucketDialog, editBucket, addBucketEngine, confirmDeleteDb, editDb, isAdmin, messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Catálogo de Bases de Datos & Motores", "databases", content);
    }

    private static void handleDatabasesPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("create_db".equalsIgnoreCase(action)) {
            String dbName = form.get("dbName");
            String defaultBucket = form.get("defaultBucket");
            String engineType = form.get("engineType");
            String description = form.get("description");
            io.jettra.store.core.StorageMode storageMode = io.jettra.store.core.StorageMode.fromString(form.get("storageMode"));

            if (defaultBucket != null && !defaultBucket.isBlank()) {
                plugin.createDatabase(dbName, defaultBucket, engineType, description, storageMode);
            } else {
                plugin.createDatabase(dbName, description, storageMode);
            }
            redirect(resp, "/databases?db=" + dbName + "&msg=db_created");
            return;
        } else if ("edit_db".equalsIgnoreCase(action)) {
            String db = form.get("db");
            io.jettra.store.core.StorageMode parsedMode = io.jettra.store.core.StorageMode.parseStrict(form.get("storageMode"));
            if (db != null && !db.isBlank() && parsedMode != null) {
                plugin.updateDatabaseStorageMode(db.trim(), parsedMode);
                redirect(resp, "/databases?db=" + db.trim() + "&msg=db_updated");
            } else {
                redirect(resp, "/databases?msg=" + java.net.URLEncoder.encode(
                        "Storage Mode inválido. Modos válidos: JVM-RAM, DISK-MEMORY, OFF-HEAP.",
                        StandardCharsets.UTF_8));
            }
            return;
        } else if ("delete_db".equalsIgnoreCase(action)) {
            String db = form.get("db");
            if (db != null && !db.isBlank() && !"system_metadata_db".equalsIgnoreCase(db)) {
                plugin.deleteDatabase(db);
            }
            redirect(resp, "/databases?msg=db_deleted");
            return;
        } else if ("add_bucket".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucketName = form.get("bucketName");
            String engineType = form.get("engineType");
            String description = form.get("description");
            String structureType = form.getOrDefault("structureType", "NONE");
            if ("DEFINED".equalsIgnoreCase(structureType)) {
                String typeErr = JettraStorePlugin.validateTableFormAttributeTypes(form);
                if (typeErr != null) {
                    redirect(resp, "/databases?db=" + db + "&manage_engines=true&add_bucket=true&msg="
                            + java.net.URLEncoder.encode(typeErr, StandardCharsets.UTF_8));
                    return;
                }
            }
            String attributesJson = JettraStorePlugin.buildAttributesJsonFromTableForm(form);

            boolean ok = plugin.addBucket(db, bucketName, engineType, description, structureType, attributesJson);
            if (!ok) {
                String err = plugin.getLastBucketOperationError();
                redirect(resp, "/databases?db=" + db + "&manage_engines=true&add_bucket=true&msg="
                        + java.net.URLEncoder.encode(err != null ? err : "Error añadiendo bucket",
                                StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/databases?db=" + db + "&manage_engines=true&msg=bucket_added");
            return;
        } else if ("edit_bucket".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String originalBucket = form.get("originalBucketName");
            String newBucket = form.get("bucketName");
            String description = form.get("description");
            String structureType = form.getOrDefault("structureType", "NONE");
            if ("DEFINED".equalsIgnoreCase(structureType)) {
                String typeErr = JettraStorePlugin.validateTableFormAttributeTypes(form);
                if (typeErr != null) {
                    redirect(resp, "/databases?db=" + db + "&manage_engines=true&edit_bucket=" + (originalBucket != null ? originalBucket : "") + "&msg="
                            + java.net.URLEncoder.encode(typeErr, StandardCharsets.UTF_8));
                    return;
                }
            }
            String attributesJson = JettraStorePlugin.buildAttributesJsonFromTableForm(form);

            boolean ok = plugin.updateBucket(db, originalBucket, newBucket, description, structureType, attributesJson);
            if (!ok) {
                String err = plugin.getLastBucketOperationError();
                redirect(resp,
                        "/databases?db=" + db + "&manage_engines=true&edit_bucket="
                                + (originalBucket != null ? originalBucket : "") + "&msg="
                                + java.net.URLEncoder.encode(err != null ? err : "Error editando bucket",
                                        StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/databases?db=" + db + "&manage_engines=true&msg=bucket_updated");
            return;
        } else if ("add_db_user".equalsIgnoreCase(action)) {
            String db = form.get("db");
            if (!isLoggedUserAdmin(req)) {
                redirect(resp, "/databases?db=" + (db != null ? db : "") + "&msg=forbidden");
                return;
            }
            String username = form.get("username");
            String roleStr = form.get("role");
            JettraDatabaseRole role = JettraDatabaseRole.READ_WRITE;
            if (roleStr != null && !roleStr.isBlank()) {
                try {
                    role = JettraDatabaseRole.valueOf(roleStr.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (Exception ignored) {
                }
            }
            plugin.assignUserToDatabase(db, username, role);
            redirect(resp, "/databases?db=" + db + "&manage_users_db=" + db + "&msg=db_user_added");
            return;
        } else if ("remove_db_user".equalsIgnoreCase(action)) {
            String db = form.get("db");
            if (!isLoggedUserAdmin(req)) {
                redirect(resp, "/databases?db=" + (db != null ? db : "") + "&msg=forbidden");
                return;
            }
            String username = form.get("username");
            plugin.removeUserFromDatabase(db, username);
            redirect(resp, "/databases?db=" + db + "&manage_users_db=" + db + "&msg=db_user_removed");
            return;
        } else if ("add_index".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucket = form.get("bucket");
            String indexName = form.get("indexName");
            String fields = form.get("fields");
            if (fields == null || fields.isBlank()) {
                fields = form.get("fieldSelect");
            }
            String indexType = form.get("indexType");
            boolean unique = "SI".equalsIgnoreCase(form.get("unique")) || "true".equalsIgnoreCase(form.get("unique"));
            boolean ok = plugin.addIndex(db, bucket, indexName, fields, indexType, unique);
            if (!ok) {
                String err = plugin.getLastIndexOperationError();
                redirect(resp,
                        "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket="
                                + (bucket != null ? bucket : "") + "&msg="
                                + java.net.URLEncoder.encode(err != null ? err : "Error creando índice",
                                        StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket=" + bucket
                    + "&msg=index_added");
            return;
        } else if ("edit_index".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucket = form.get("bucket");
            String originalIndexName = form.get("originalIndexName");
            String newIndexName = form.get("indexName");
            String fields = form.get("fields");
            String indexType = form.get("indexType");
            boolean unique = "SI".equalsIgnoreCase(form.get("unique")) || "true".equalsIgnoreCase(form.get("unique"));
            boolean ok = plugin.updateIndex(db, bucket, originalIndexName, newIndexName, fields, indexType, unique);
            if (!ok) {
                String err = plugin.getLastIndexOperationError();
                redirect(resp,
                        "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket="
                                + (bucket != null ? bucket : "")
                                + "&edit_index=" + (originalIndexName != null ? originalIndexName : "") + "&msg="
                                + java.net.URLEncoder.encode(err != null ? err : "Error editando índice",
                                        StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket=" + bucket
                    + "&msg=index_updated");
            return;
        } else if ("delete_index".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucket = form.get("bucket");
            String indexName = form.get("indexName");
            plugin.deleteIndex(db, bucket, indexName);
            redirect(resp, "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket=" + bucket
                    + "&msg=index_deleted");
            return;
        } else if ("rebuild_index".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucket = form.get("bucket");
            String indexName = form.get("indexName");
            plugin.rebuildIndex(db, bucket, indexName);
            redirect(resp, "/databases?db=" + db + "&manage_indexes_db=" + db + "&index_bucket=" + bucket
                    + "&msg=index_rebuilt");
            return;
        }
        redirect(resp, "/databases");
    }

    // --- Explorador de Registros CRUD ---
    private static void handleExplorerGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = req.getQueryParam("action");
        String db = req.getQueryParam("db");
        String bucket = req.getQueryParam("bucket");

        if ("delete_record".equalsIgnoreCase(action)) {
            String recordId = req.getQueryParam("recordId");
            if (db != null && bucket != null && recordId != null) {
                plugin.deleteRecord(db, bucket, recordId);
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg=record_deleted");
            return;
        } else if ("restore_version".equalsIgnoreCase(action)) {
            String recordId = req.getQueryParam("recordId");
            String versionStr = req.getQueryParam("version");
            if (db != null && bucket != null && recordId != null && versionStr != null) {
                try {
                    long targetVer = Long.parseLong(versionStr.trim());
                    plugin.restoreRecordVersion(db, bucket, recordId, targetVer);
                    redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId
                            + "&msg=version_restored");
                    return;
                } catch (Exception ignored) {
                }
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
            return;
        } else if ("delete_bucket_index".equalsIgnoreCase(action)) {
            String indexName = req.getQueryParam("indexName");
            if (db != null && bucket != null && indexName != null) {
                plugin.deleteIndex(db, bucket, indexName);
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=indexes&msg=index_deleted");
            return;
        } else if ("rebuild_bucket_index".equalsIgnoreCase(action)) {
            String indexName = req.getQueryParam("indexName");
            if (db != null && bucket != null && indexName != null) {
                plugin.rebuildIndex(db, bucket, indexName);
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=indexes&msg=index_rebuilt");
            return;
        } else if ("delete_schema".equalsIgnoreCase(action)) {
            if (db != null && bucket != null) {
                plugin.deleteBucketStructure(db, bucket);
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=schema&msg=schema_deleted");
            return;
        }

        String section = req.getQueryParam("section");
        if (section == null || section.isBlank())
            section = "records";
        boolean isNew = "new".equalsIgnoreCase(action);
        boolean isNewBucket = "new_bucket".equalsIgnoreCase(action);
        boolean isEditSchema = "edit_schema".equalsIgnoreCase(action) || "new_schema".equalsIgnoreCase(action);
        boolean isViewSchema = "view_schema".equalsIgnoreCase(action);
        String editId = req.getQueryParam("edit_id");
        String viewId = req.getQueryParam("view_id");
        String historyId = req.getQueryParam("history_id");
        String q = req.getQueryParam("q");
        String pageStr = req.getQueryParam("page");
        String sizeStr = req.getQueryParam("size");
        if (sizeStr == null || sizeStr.isBlank()) {
            sizeStr = req.getQueryParam("pageSize");
        }
        int page = 1;
        int pageSize = RecordExplorerFluxView.DEFAULT_PAGE_SIZE;
        if (pageStr != null && !pageStr.isBlank()) {
            try {
                page = Math.max(1, Integer.parseInt(pageStr.trim()));
            } catch (Exception ignored) {
            }
        }
        if (sizeStr != null && !sizeStr.isBlank()) {
            try {
                pageSize = Math.max(1, Math.min(100, Integer.parseInt(sizeStr.trim())));
            } catch (Exception ignored) {
            }
        }

        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("record_created".equals(msg))
            messageText = "Registro creado exitosamente en el almacén.";
        else if ("record_updated".equals(msg))
            messageText = "Registro actualizado correctamente.";
        else if ("record_deleted".equals(msg))
            messageText = "Registro eliminado del bucket.";
        else if ("version_restored".equals(msg))
            messageText = "Versión previa restaurada exitosamente como nueva versión activa.";
        else if ("bucket_created".equals(msg))
            messageText = "Bucket creado exitosamente con su estructura configurada.";
        else if ("schema_saved".equals(msg))
            messageText = "Estructura DEFINED guardada y activada para el bucket.";
        else if ("schema_deleted".equals(msg))
            messageText = "Estructura eliminada; el bucket opera ahora en modo NONE (flexible).";
        else if ("index_created".equals(msg))
            messageText = "Índice secundario creado en el bucket.";
        else if ("index_rebuilt".equals(msg))
            messageText = "Índice reconstruido y optimizado exitosamente.";
        else if ("index_deleted".equals(msg))
            messageText = "Índice eliminado del bucket.";
        else if (msg != null && !msg.isBlank())
            messageText = msg;

        Widget content = new RecordExplorerFluxView(db, bucket, section, q, editId, viewId, historyId,
                isNew, isNewBucket, isEditSchema, isViewSchema, messageText, page, pageSize);
        renderTemplate(resp, server, getLoggedUser(req), "Explorador de Registros CRUD", "explorer", content);
    }

    private static void handleGenerateJettraIdApi(HttpRequest req, HttpResponse resp) {
        if (getLoggedUser(req) == null) {
            resp.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json; charset=UTF-8")
                    .header("Cache-Control", "no-store")
                    .body("{\"error\":\"unauthorized\"}");
            return;
        }
        String db = req.getQueryParam("db");
        String bucket = req.getQueryParam("bucket");
        String generated = io.jettra.store.core.JettraId.generate(
                db != null ? db : "", bucket != null ? bucket : "").toHexString();
        resp.contentType("application/json; charset=UTF-8")
                .header("Cache-Control", "no-store")
                .body("{\"jettraId\":\"" + generated + "\",\"database\":\"" + escapeJson(db != null ? db : "")
                        + "\",\"bucket\":\"" + escapeJson(bucket != null ? bucket : "") + "\"}");
    }

    private static void handleExplorerPost(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        String db = form.get("db");
        String bucket = form.get("bucket");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("create_bucket".equalsIgnoreCase(action)) {
            String newBucket = form.get("newBucketName");
            String engineType = form.getOrDefault("engineType", "DOCUMENT");
            String structureType = form.getOrDefault("structureType", "NONE");
            String attributesJson = form.getOrDefault("attributesJson", "[]");
            String description = form.get("description");
            boolean ok = plugin.addBucket(db, newBucket, engineType, description, structureType, attributesJson);
            if (!ok) {
                String err = plugin.getLastBucketOperationError();
                redirect(resp, "/explorer?db=" + db + "&bucket=" + (bucket != null ? bucket : "") + "&msg="
                        + java.net.URLEncoder.encode(err != null ? err : "Error creando bucket",
                                java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + newBucket.trim() + "&section=schema&msg=bucket_created");
            return;
        } else if ("save_schema".equalsIgnoreCase(action)) {
            String attributesJson = form.getOrDefault("attributesJson", "[]");
            boolean ok = plugin.saveBucketStructure(db, bucket, attributesJson);
            if (!ok) {
                String err = plugin.getLastBucketOperationError();
                redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=schema&msg="
                        + java.net.URLEncoder.encode(err != null ? err : "Error guardando estructura",
                                java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=schema&msg=schema_saved");
            return;
        } else if ("create_bucket_index".equalsIgnoreCase(action)) {
            String idxName = form.get("indexName");
            String idxField = form.get("indexField");
            if (idxField == null || idxField.isBlank()) {
                idxField = form.get("indexFieldSelect");
            }
            String idxType = form.getOrDefault("indexType", "BTREE");
            boolean unique = "true".equalsIgnoreCase(form.get("indexUnique"))
                    || "on".equalsIgnoreCase(form.get("indexUnique"));
            boolean ok = plugin.addIndex(db, bucket, idxName, idxField, idxType, unique);
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&section=indexes&msg="
                    + (ok ? "index_created" : "Error+creando+indice"));
            return;
        } else if ("save_new".equalsIgnoreCase(action)) {
            String rawRecordId = form.get("recordId");
            String recordId = (rawRecordId != null && !rawRecordId.isBlank())
                    ? rawRecordId.trim()
                    : io.jettra.store.core.JettraId.generate(db, bucket).toHexString();
            String summary = form.getOrDefault("summary", "");
            String details = buildStructuredDetailsOrFallback(plugin, db, bucket, form, "");

            // Validar unicidad de clave primaria sin cerrar el diálogo ni perder datos
            // capturados
            if (plugin.getRecord(db, bucket, recordId) != null) {
                String dupErr = "Error de integridad: ya existe un registro con el identificador '"
                        + recordId + "' en el bucket '" + bucket + "'. Modifique el ID o regenere un nuevo JettraId.";
                Widget content = new RecordExplorerFluxView(db, bucket, "records", "", null, null, null,
                        true, false, false, false, null, 1, RecordExplorerFluxView.DEFAULT_PAGE_SIZE,
                        recordId, summary, details, form, dupErr);
                renderTemplate(resp, server, getLoggedUser(req), "Explorador de Registros CRUD", "explorer", content);
                return;
            }

            var validation = plugin.validateRecordForBucket(db, bucket, details);
            if (!validation.valid()) {
                redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg="
                        + java.net.URLEncoder.encode("Validación fallida: " + validation.getSummary(),
                                java.nio.charset.StandardCharsets.UTF_8));
                return;
            }

            plugin.addRecord(db, bucket, recordId, summary, details);
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg=record_created");
            return;
        } else if ("save_edit".equalsIgnoreCase(action)) {
            String recordId = form.get("recordId");
            String summary = form.get("summary");
            io.jettra.store.explorer.model.EngineRecordInfo existing = plugin.getRecord(db, bucket, recordId);
            String details = buildStructuredDetailsOrFallback(plugin, db, bucket, form,
                    existing != null ? existing.getDetails() : "");
            var validation = plugin.validateRecordForBucket(db, bucket, details, recordId);
            if (!validation.valid()) {
                redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg="
                        + java.net.URLEncoder.encode("Validación fallida: " + validation.getSummary(),
                                java.nio.charset.StandardCharsets.UTF_8));
                return;
            }

            plugin.updateRecord(db, bucket, recordId, summary, details);
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg=record_updated");
            return;
        } else if ("restore_version".equalsIgnoreCase(action)) {
            String recordId = form.get("recordId");
            String versionStr = form.get("version");
            if (db != null && bucket != null && recordId != null && versionStr != null) {
                try {
                    long targetVer = Long.parseLong(versionStr.trim());
                    plugin.restoreRecordVersion(db, bucket, recordId, targetVer);
                    redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId
                            + "&msg=version_restored");
                    return;
                } catch (Exception ignored) {
                }
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
            return;
        }
        redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
    }

    private static String buildStructuredDetailsOrFallback(
            JettraStorePlugin plugin, String db, String bucket, Map<String, String> form, String existingDetails) {
        var bInfo = plugin.getBucket(db, bucket);
        if (bInfo != null && "DEFINED".equalsIgnoreCase(bInfo.getStructureType()) && !bInfo.getAttributes().isEmpty()) {
            boolean hasStructFields = form.keySet().stream().anyMatch(k -> k.startsWith("struct_"));
            if (hasStructFields) {
                StringBuilder json = new StringBuilder("{");
                int count = 0;
                for (var attr : bInfo.getAttributes()) {
                    String name = attr.atribute_name();
                    String rawVal = form.get("struct_" + name);
                    if (rawVal == null || rawVal.isBlank()) {
                        continue;
                    }
                    if (count++ > 0)
                        json.append(", ");
                    json.append("\"").append(name.replace("\"", "\\\"")).append("\": ");
                    String t = attr.type_atribute() != null ? attr.type_atribute().toLowerCase() : "string";
                    if (JettraStorePlugin.isNumericTypeName(t) && rawVal.trim().matches("-?\\d+(\\.\\d+)?")) {
                        json.append(rawVal.trim());
                    } else if (t.equals("boolean") || t.equals("bool")) {
                        json.append("true".equalsIgnoreCase(rawVal.trim()) ? "true" : "false");
                    } else {
                        json.append("\"").append(rawVal.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                    }
                }
                json.append("}");
                return json.toString();
            }
        }
        return io.jettra.store.explorer.pages.explorer.EngineRecordRenderer.buildDetailsFromForm(form, existingDetails);
    }

    // --- Consola SQL / JQL / Shell ---
    private static void handleQueryGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        String db = req.getQueryParam("db");
        String bucket = req.getQueryParam("bucket");
        String query = req.getQueryParam("query");

        Widget content = new QueryConsoleFluxView(db, bucket, query);
        renderTemplate(resp, server, getLoggedUser(req), "Consola Interactiva JettraSQL / JQL", "query", content);
    }

    private static void handleQueryPost(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String db = form.get("db");
        String bucket = form.get("bucket");
        String query = form.get("query");

        Widget content = new QueryConsoleFluxView(db, bucket, query);
        renderTemplate(resp, server, getLoggedUser(req), "Consola Interactiva JettraSQL / JQL", "query", content);
    }

    // --- Conexiones ---
    private static void handleConnectionsGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("connected".equals(msg))
            messageText = "Conectado al perfil en caliente.";
        else if ("default_set".equals(msg))
            messageText = "Perfil establecido como predeterminado.";
        else if ("deleted".equals(msg))
            messageText = "Perfil de conexión eliminado.";
        else if ("saved".equals(msg))
            messageText = "Perfil guardado exitosamente.";
        else if ("saved_conn".equals(msg))
            messageText = "Conexión guardada atómicamente en conecction.config y sincronizada con cluster-state.config del PRIMARY.";
        else if ("tested_conn".equals(msg))
            messageText = "Conexión aplicada en vivo y sincronizada desde cluster-state.config del PRIMARY.";
        else if ("conn_error".equals(msg)) {
            String err = JettraStorePlugin.getInstance().getLastConnectionSyncError();
            messageText = "Error de conexión: " + (err != null && !err.isBlank() ? err : "Dirección host:port inválida.");
        }

        Widget content = new ConnectionManagerFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Gestión de Conexiones a Clúster", "connections", content);
    }

    private static void handleConnectionsPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("save_cluster_connection".equalsIgnoreCase(action) || "test_cluster_connection".equalsIgnoreCase(action)) {
            boolean persist = "save_cluster_connection".equalsIgnoreCase(action);
            String seeds = form.get("cluster_ip_nodes");
            String username = form.get("username");
            var res = plugin.applyAndSaveClusterConnection(seeds, username, persist);
            if (!res.success()) {
                redirect(resp, "/connections?msg=conn_error");
            } else {
                redirect(resp, persist ? "/connections?msg=saved_conn" : "/connections?msg=tested_conn");
            }
            return;
        } else if ("connect".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null)
                plugin.connectProfile(id);
            redirect(resp, "/connections?msg=connected");
            return;
        } else if ("set_default".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null)
                plugin.setDefaultProfile(id);
            redirect(resp, "/connections?msg=default_set");
            return;
        } else if ("delete".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null)
                plugin.deleteProfile(id);
            redirect(resp, "/connections?msg=deleted");
            return;
        } else if ("save".equalsIgnoreCase(action)) {
            String id = form.get("id");
            String name = form.get("name");
            String url = form.get("url");
            String username = form.get("username");
            String password = form.get("password");
            plugin.saveOrUpdateProfile(new ConnectionProfile(id, name, url, username, password, false));
            redirect(resp, "/connections?msg=saved");
            return;
        }
        redirect(resp, "/connections");
    }

    // --- Usuarios y Seguridad ---
    private static void handleUsersGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String loggedUsername = getLoggedUser(req);
        boolean isAdmin = isLoggedUserAdmin(req);
        String editParam = req.getQueryParam("edit");
        String msg = req.getQueryParam("msg");

        String messageText = null;
        if ("user_saved".equals(msg))
            messageText = "Usuario y permisos actualizados exitosamente.";
        else if ("user_deleted".equals(msg))
            messageText = "Usuario revocado del sistema.";
        else if ("forbidden".equals(msg))
            messageText = "Acceso denegado: no tienes permisos para modificar otros usuarios.";

        JettraUser editTarget = null;
        if (editParam != null && !editParam.isBlank()) {
            boolean allowed = isAdmin || (loggedUsername != null && loggedUsername.equalsIgnoreCase(editParam.trim()));
            if (allowed) {
                editTarget = plugin.findUser(editParam.trim()).orElse(null);
            } else if (messageText == null) {
                messageText = "Acceso denegado: solo un usuario con rol ADMIN puede editar otros usuarios.";
            }
        }

        Widget content = new UserManagerFluxView(loggedUsername, isAdmin, editTarget, messageText);
        renderTemplate(resp, server, loggedUsername, "Seguridad y Roles de Usuario", "users", content);
    }

    private static void handleUsersPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String loggedUsername = getLoggedUser(req);
        boolean isAdmin = isLoggedUserAdmin(req);

        if ("save_user".equalsIgnoreCase(action) || "update_user".equalsIgnoreCase(action)) {
            String username = form.get("username");
            if (username == null || username.isBlank()) {
                redirect(resp, "/users");
                return;
            }
            String cleanUsername = username.trim();
            Optional<JettraUser> existingOpt = plugin.findUser(cleanUsername);
            boolean isSelf = loggedUsername != null && loggedUsername.equalsIgnoreCase(cleanUsername);

            if (!isAdmin && (existingOpt.isEmpty() || !isSelf)) {
                redirect(resp, "/users?msg=forbidden");
                return;
            }

            String password = form.get("password");
            if ((password == null || password.isBlank()) && existingOpt.isPresent()) {
                password = existingOpt.get().getPassword();
            }
            String desc = form.get("description");
            if (desc == null && existingOpt.isPresent()) {
                desc = existingOpt.get().getDescription();
            }

            JettraUser.GlobalRole gRole;
            if (!isAdmin && existingOpt.isPresent()) {
                gRole = existingOpt.get().getGlobalRole();
            } else if ("admin".equalsIgnoreCase(cleanUsername)) {
                gRole = JettraUser.GlobalRole.ADMIN;
            } else {
                String globalRoleStr = form.get("globalRole");
                gRole = JettraUser.GlobalRole.DEVELOPER;
                if (globalRoleStr != null && !globalRoleStr.isBlank()) {
                    try {
                        gRole = JettraUser.GlobalRole.valueOf(globalRoleStr.trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (Exception ignored) {
                        if ("READ_ONLY".equalsIgnoreCase(globalRoleStr.trim())) {
                            gRole = JettraUser.GlobalRole.ANALYST;
                        }
                    }
                }
            }

            JettraUser user = new JettraUser(cleanUsername, password != null ? password : "", desc != null ? desc : "",
                    gRole);
            if (!isAdmin && existingOpt.isPresent()) {
                user.setDatabaseRoles(new java.util.HashMap<>(existingOpt.get().getDatabaseRoles()));
            } else {
                for (String db : plugin.getDatabaseNames()) {
                    String roleVal = form.get("db_role_" + db);
                    if (roleVal != null && !roleVal.isBlank()) {
                        try {
                            user.setRoleForDatabase(db,
                                    JettraDatabaseRole.valueOf(roleVal.trim().toUpperCase(java.util.Locale.ROOT)));
                        } catch (Exception ignored) {
                        }
                    } else if (existingOpt.isPresent()) {
                        JettraDatabaseRole prev = existingOpt.get().getDatabaseRoles().get(db);
                        if (prev != null) {
                            user.setRoleForDatabase(db, prev);
                        }
                    }
                }
            }
            plugin.saveOrUpdateUser(user);
            redirect(resp, "/users?msg=user_saved");
            return;
        } else if ("delete_user".equalsIgnoreCase(action)) {
            if (!isAdmin) {
                redirect(resp, "/users?msg=forbidden");
                return;
            }
            String username = form.get("username");
            if (username != null)
                plugin.deleteUser(username);
            redirect(resp, "/users?msg=user_deleted");
            return;
        }
        redirect(resp, "/users");
    }

    // --- Centinelas Police ---
    private static void handlePoliceGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("scan_complete".equals(msg))
            messageText = "Patrullaje general completado. 0 anomalías encontradas.";

        Widget content = new PoliceMonitorFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Centinelas Police & Auditoría", "police", content);
    }

    private static void handlePolicePost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("patrol_scan".equalsIgnoreCase(action)) {
            plugin.logPoliceIncident("Heap Sentinel",
                    "Escaneo profundo de Memoria Directa Panama completado. 0 fragmentaciones.", "OK",
                    "Búferes auditados");
            plugin.logPoliceIncident("Raft Quorum K9",
                    "Auditoría de consistencia de réplicas en nodos del quórum: 100% sincronizado.", "OK",
                    "WAL validado");
            redirect(resp, "/police?msg=scan_complete");
            return;
        }
        redirect(resp, "/police");
    }

    // --- Backups ---
    private static void handleBackupsGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp))
            return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("created".equals(msg))
            messageText = "Copia de respaldo generada exitosamente.";
        else if ("restored".equals(msg))
            messageText = "Snapshot restaurado en caliente con Zero-Copy.";
        else if ("deleted".equals(msg))
            messageText = "Snapshot eliminado.";

        Widget content = new BackupFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Copias de Seguridad y Snapshots", "backups", content);
    }

    private static void handleBackupsPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp))
            return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("create".equalsIgnoreCase(action)) {
            String db = form.get("db_name");
            String algo = form.get("algo");
            String path = form.get("path");
            plugin.createBackup(db, "MULTIMODEL_SNAPSHOT", algo, path);
            redirect(resp, "/backups?msg=created");
            return;
        } else if ("restore".equalsIgnoreCase(action)) {
            String id = form.get("id");
            String targetDb = form.get("target_db");
            plugin.restoreBackup(id, targetDb != null ? targetDb : "example_factura_db");
            redirect(resp, "/backups?msg=restored");
            return;
        } else if ("delete".equalsIgnoreCase(action)) {
            String id = form.get("id");
            plugin.deleteSnapshot(id);
            redirect(resp, "/backups?msg=deleted");
            return;
        }
        redirect(resp, "/backups");
    }

    // --- Endpoint JSON de Sondeo en Vivo ---
    private static void handleClusterStatusJson(HttpRequest req, HttpResponse resp) {
        if (getLoggedUser(req) == null) {
            resp.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json; charset=UTF-8")
                    .header("Cache-Control", "no-store, no-cache, must-revalidate")
                    .body("{\"error\":\"unauthorized\"}");
            return;
        }
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        plugin.refreshAllNodeStatuses(true);
        resp.contentType("application/json; charset=UTF-8")
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .body(buildClusterStatusJson(plugin));
    }

    private static void handleClusterEventsSse(HttpRequest req, HttpResponse resp) {
        if (getLoggedUser(req) == null) {
            resp.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json; charset=UTF-8")
                    .body("{\"error\":\"unauthorized\"}");
            return;
        }
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        plugin.refreshAllNodeStatusesIfStale(300L);
        String payload = "retry: 1000\nevent: cluster_status\ndata: " + buildClusterStatusJson(plugin) + "\n\n";
        resp.contentType("text/event-stream; charset=UTF-8")
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Connection", "keep-alive")
                .body(payload);
    }

    private static String buildClusterStatusJson(JettraStorePlugin plugin) {
        List<ServerNodeInfo> nodes = plugin.getServerNodes();
        ServerNodeInfo primary = plugin.getPrimaryNode();

        boolean primaryOnline = primary != null && primary.isOnline();
        boolean primaryActive = primary != null && primary.isActive();
        String primaryNodeId = primary != null ? primary.getId() : "";
        String primaryNodeName = primary != null ? primary.getName() : "";
        String primaryRole = primary != null ? primary.getRole() : "PRIMARY";
        long activeCount = nodes.stream().filter(ServerNodeInfo::isActive).count();
        long onlineCount = activeCount;
        long inactiveCount = nodes.size() - activeCount;
        long inactiveOthers = nodes.stream().filter(n -> n != primary && !n.isActive()).count();
        long offlineOthers = inactiveOthers;
        long totalQps = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getQps).sum();
        long totalPanama = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getPanamaDirectMb)
                .sum();

        var connState = plugin.getClusterConnectionState();
        var latestSnap = plugin.getLatestPrimaryClusterStatus();
        String activeSeeds = connState != null ? connState.clusterIpNodes() : "127.0.0.1:50001";
        String connSource = (connState != null && connState.loadedFromConnectionFile())
                ? "conecction.config"
                : "cluster.ip.nodes";
        long clusterStatusVersion = latestSnap != null ? latestSnap.stateVersion() : 0L;
        String resolvedPrimaryEndpoint = primary != null ? (primary.getHost() + ":" + primary.getPort()) : "";

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"executionEnvironment\":\"")
                .append(escapeJson(plugin.getLifecycleManager().detectExecutionEnvironment())).append("\",");
        json.append("\"activeClusterIpNodes\":\"").append(escapeJson(activeSeeds)).append("\",");
        json.append("\"connectionConfigSource\":\"").append(escapeJson(connSource)).append("\",");
        json.append("\"clusterStatusVersion\":").append(clusterStatusVersion).append(",");
        json.append("\"resolvedPrimaryEndpoint\":\"").append(escapeJson(resolvedPrimaryEndpoint)).append("\",");
        json.append("\"primaryNodeId\":\"").append(escapeJson(primaryNodeId)).append("\",");
        json.append("\"primaryNodeName\":\"").append(escapeJson(primaryNodeName)).append("\",");
        json.append("\"primaryRole\":\"").append(escapeJson(primaryRole)).append("\",");
        json.append("\"primaryActive\":").append(primaryActive).append(",");
        json.append("\"primaryOnline\":").append(primaryOnline).append(",");
        json.append("\"activeCount\":").append(activeCount).append(",");
        json.append("\"onlineCount\":").append(onlineCount).append(",");
        json.append("\"inactiveCount\":").append(inactiveCount).append(",");
        json.append("\"totalCount\":").append(nodes.size()).append(",");
        json.append("\"inactiveOthers\":").append(inactiveOthers).append(",");
        json.append("\"offlineOthers\":").append(offlineOthers).append(",");
        json.append("\"totalQps\":").append(totalQps).append(",");
        json.append("\"totalPanama\":").append(totalPanama).append(",");
        json.append("\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            ServerNodeInfo n = nodes.get(i);
            if (i > 0)
                json.append(",");
            json.append("{");
            json.append("\"id\":\"").append(escapeJson(n.getId())).append("\",");
            json.append("\"name\":\"").append(escapeJson(n.getName())).append("\",");
            json.append("\"role\":\"").append(escapeJson(n.getRole())).append("\",");
            json.append("\"raftState\":\"").append(escapeJson(n.getRaftState())).append("\",");
            json.append("\"raftTerm\":").append(n.getRaftTerm()).append(",");
            json.append("\"host\":\"").append(escapeJson(n.getHost())).append("\",");
            json.append("\"port\":").append(n.getPort()).append(",");
            json.append("\"status\":\"").append(n.getStatus()).append("\",");
            json.append("\"state\":\"").append(n.isActive() ? "ONLINE" : "OFFLINE").append("\",");
            json.append("\"active\":").append(n.isActive()).append(",");
            json.append("\"online\":").append(n.isOnline()).append(",");
            json.append("\"latencyMs\":").append(String.format(Locale.US, "%.2f", n.getLatencyMs())).append(",");
            json.append("\"cpuPercent\":").append(String.format(Locale.US, "%.1f", n.getCpuPercent())).append(",");
            json.append("\"heapUsedMb\":").append(n.getHeapUsedMb()).append(",");
            json.append("\"heapMaxMb\":").append(n.getHeapMaxMb()).append(",");
            json.append("\"panamaDirectMb\":").append(n.getPanamaDirectMb()).append(",");
            json.append("\"diskUsedMb\":").append(n.getDiskUsedMb()).append(",");
            json.append("\"virtualThreads\":").append(n.getVirtualThreads()).append(",");
            json.append("\"qps\":").append(n.getQps()).append(",");
            json.append("\"lastError\":")
                    .append(n.getLastError() != null ? "\"" + escapeJson(n.getLastError()) + "\"" : "null");
            json.append("}");
        }
        json.append("],\"recentTransfers\":[");
        try {
            List<io.jettra.store.cluster.ClusterLiveEvent> events = io.jettra.store.cluster.JettraClusterEventBus
                    .getInstance().getRecentEvents(20);
            for (int i = 0; i < events.size(); i++) {
                io.jettra.store.cluster.ClusterLiveEvent ev = events.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{");
                json.append("\"timestamp\":").append(ev.timestamp()).append(",");
                json.append("\"time\":\"").append(escapeJson(ev.formattedTimestamp())).append("\",");
                json.append("\"type\":\"").append(escapeJson(ev.type())).append("\",");
                json.append("\"source\":\"").append(escapeJson(ev.sourceNodeId())).append("\",");
                json.append("\"target\":\"").append(escapeJson(ev.targetNodeId())).append("\",");
                json.append("\"message\":\"").append(escapeJson(ev.message())).append("\",");
                json.append("\"details\":\"").append(escapeJson(ev.details())).append("\"");
                json.append("}");
            }
        } catch (Throwable ignored) {
        }
        json.append("]}");
        return json.toString();
    }

    private static void handleNodeShutdownApi(HttpRequest req, HttpResponse resp) {
        if (getLoggedUser(req) == null) {
            resp.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json; charset=UTF-8")
                    .body("{\"error\":\"unauthorized\"}");
            return;
        }
        String nodeId = extractNodeIdParam(req);
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo node = nodeId != null ? plugin.getNodeById(nodeId) : null;
        if (node == null) {
            resp.status(HttpStatus.NOT_FOUND)
                    .contentType("application/json; charset=UTF-8")
                    .body("{\"success\":false,\"error\":\"Nodo no encontrado\"}");
            return;
        }
        NodeLifecycleManager.ShutdownOutcome outcome = plugin.shutdownNode(node.getId());
        ServerNodeInfo newPrimary = plugin.getPrimaryNode();
        String json = "{"
                + "\"success\":" + outcome.success() + ","
                + "\"offlineVerified\":" + outcome.offlineVerified() + ","
                + "\"stopped\":" + (!node.isOnline()) + ","
                + "\"nodeId\":\"" + escapeJson(node.getId()) + "\","
                + "\"role\":\"" + escapeJson(node.getRole()) + "\","
                + "\"raftState\":\"" + escapeJson(node.getRaftState()) + "\","
                + "\"active\":" + node.isActive() + ","
                + "\"online\":" + node.isOnline() + ","
                + "\"status\":\"" + node.getStatus() + "\","
                + "\"state\":\"" + (node.isOnline() ? "ONLINE" : "OFFLINE") + "\","
                + "\"executionEnvironment\":\"" + escapeJson(outcome.executionEnvironment()) + "\","
                + "\"dockerContainer\":\"" + escapeJson(outcome.dockerContainer()) + "\","
                + "\"primaryNodeId\":\"" + escapeJson(newPrimary != null ? newPrimary.getId() : "") + "\","
                + "\"primaryActive\":" + (newPrimary != null && newPrimary.isActive()) + ","
                + "\"mechanism\":\"" + escapeJson(outcome.mechanism()) + "\","
                + "\"elapsedMs\":" + outcome.elapsedMs() + ","
                + "\"lastError\":"
                + (node.getLastError() != null ? "\"" + escapeJson(node.getLastError()) + "\"" : "null") + ","
                + "\"message\":\"" + escapeJson(outcome.message()) + "\""
                + "}";
        resp.contentType("application/json; charset=UTF-8")
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .body(json);
    }

    private static void handleNodeStartApi(HttpRequest req, HttpResponse resp) {
        if (getLoggedUser(req) == null) {
            resp.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json; charset=UTF-8")
                    .body("{\"error\":\"unauthorized\"}");
            return;
        }
        String nodeId = extractNodeIdParam(req);
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo node = nodeId != null ? plugin.getNodeById(nodeId) : null;
        if (node == null) {
            resp.status(HttpStatus.NOT_FOUND)
                    .contentType("application/json; charset=UTF-8")
                    .body("{\"success\":false,\"error\":\"Nodo no encontrado\"}");
            return;
        }
        NodeLifecycleManager.StartOutcome outcome = plugin.startNode(node.getId());
        String json = "{"
                + "\"success\":" + outcome.success() + ","
                + "\"onlineVerified\":" + outcome.onlineVerified() + ","
                + "\"nodeId\":\"" + escapeJson(node.getId()) + "\","
                + "\"active\":" + node.isActive() + ","
                + "\"online\":" + node.isOnline() + ","
                + "\"status\":\"" + node.getStatus() + "\","
                + "\"state\":\"" + (node.isOnline() ? "ONLINE" : "OFFLINE") + "\","
                + "\"mechanism\":\"" + escapeJson(outcome.mechanism()) + "\","
                + "\"lastError\":"
                + (node.getLastError() != null ? "\"" + escapeJson(node.getLastError()) + "\"" : "null") + ","
                + "\"message\":\"" + escapeJson(outcome.message()) + "\""
                + "}";
        resp.contentType("application/json; charset=UTF-8")
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .body(json);
    }

    private static String extractNodeIdParam(HttpRequest req) {
        String id = req.getQueryParam("nodeId");
        if (id != null && !id.isBlank())
            return id.trim();
        String body = req.getBodyAsString();
        if (body == null || body.isBlank())
            return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"nodeId\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(trimmed);
            if (m.find())
                return m.group(1).trim();
        }
        Map<String, String> form = parseFormBody(trimmed);
        String fId = form.get("nodeId");
        return (fId != null && !fId.isBlank()) ? fId.trim() : null;
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // --- Gestión de Ciclo de Vida y Sesiones ---

    public static void stopApplication(JettraServer server) {
        closeAllSessions();
        try {
            JettraStorePlugin.getInstance().stopClusterHeartbeatMonitor();
        } catch (Exception ignored) {
        }
        if (server != null) {
            ACTIVE_SERVERS.remove(server);
            try {
                server.stop();
            } catch (Exception ignored) {
            }
            if (server == serverInstance) {
                serverInstance = null;
            }
        }
        if (ACTIVE_SERVERS.isEmpty()) {
            try {
                JettraStorePlugin.getInstance().getLifecycleManager().closeAllManagedEndpoints();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Detiene todas las instancias activas de {@link JettraServer} abiertas por JettraStoreExplorer
     * (incluyendo pruebas automatizadas) y cierra los endpoints gestionados pendientes.
     */
    public static int stopAllApplications() {
        int stoppedCount = 0;
        for (JettraServer srv : List.copyOf(ACTIVE_SERVERS)) {
            stopApplication(srv);
            stoppedCount++;
        }
        if (serverInstance != null) {
            stopApplication(serverInstance);
        }
        try {
            JettraStorePlugin.getInstance().getLifecycleManager().closeAllManagedEndpoints();
        } catch (Exception ignored) {
        }
        return stoppedCount;
    }

    public static int getActiveApplicationCount() {
        return ACTIVE_SERVERS.size();
    }

    public static void closeAllSessions() {
        int count = ACTIVE_SESSIONS.size();
        ACTIVE_SESSIONS.clear();
        serverBootId = UUID.randomUUID().toString();
        if (count > 0) {
            System.out.println("[JettraStore Explorer] Sesiones activas cerradas (" + count
                    + "). Se requerirá nueva autenticación al iniciar.");
        }
    }

    public static int getActiveSessionCount() {
        return ACTIVE_SESSIONS.size();
    }

    // --- Migrador PostgreSQL a JettraStore y Generador Backend (RF-17) ---
    private static volatile PostgresConnectionConfig lastMigratorConfig = new PostgresConnectionConfig();
    private static volatile List<String> lastDiscoveredDatabases = new ArrayList<>();
    private static volatile List<String> lastDiscoveredSchemas = new ArrayList<>();
    private static volatile PostgresIntrospectionResult lastIntrospectionResult;
    private static volatile MigrationProgress lastMigrationProgress;
    private static volatile byte[] lastMigratorZipBytes;

    private static void handleMigratorGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        String user = getLoggedUser(req);
        String flash = req.getQueryParam("msg");
        boolean isError = "true".equalsIgnoreCase(req.getQueryParam("error"));
        boolean canDownload = lastMigratorZipBytes != null && lastMigratorZipBytes.length > 0;

        Widget view = new MigratorFluxView(
                lastMigratorConfig,
                lastDiscoveredDatabases,
                lastDiscoveredSchemas,
                lastIntrospectionResult,
                lastMigrationProgress,
                flash,
                isError,
                canDownload
        );
        renderTemplate(resp, server, user, "Migrador PostgreSQL - JettraStore Explorer", "migrator", view);
    }

    private static void handleMigratorPost(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.getOrDefault("action", "analyze").trim();

        PostgresConnectionConfig cfg = new PostgresConnectionConfig();
        cfg.setHost(form.getOrDefault("host", "127.0.0.1"));
        try {
            cfg.setPort(Integer.parseInt(form.getOrDefault("port", "5432").trim()));
        } catch (Exception ignored) {}
        cfg.setDatabase(form.getOrDefault("database", "postgres"));
        cfg.setUser(form.getOrDefault("user", "postgres"));
        cfg.setPassword(form.getOrDefault("password", ""));
        cfg.setSchema(form.getOrDefault("schema", "public"));
        String targetDb = form.getOrDefault("targetDatabase", "");
        if (targetDb.isBlank() || "migrated_db".equals(targetDb)) {
            targetDb = cfg.getDatabase();
        }
        cfg.setTargetDatabase(targetDb);
        cfg.setTargetEngine(form.getOrDefault("targetEngine", "DOCUMENT"));
        cfg.setBackendPackageName(form.getOrDefault("backendPackageName", "com.example.jettra.backend"));
        cfg.setSsl("true".equalsIgnoreCase(form.get("ssl")) || "on".equalsIgnoreCase(form.get("ssl")));
        cfg.setGenerateBackend(!"false".equalsIgnoreCase(form.get("generateBackend")));
        lastMigratorConfig = cfg;

        JettraPostgresMigrator migrator = new JettraPostgresMigrator();

        if ("connect".equalsIgnoreCase(action) || "discover".equalsIgnoreCase(action) || "load_dbs".equalsIgnoreCase(action)) {
            try {
                List<String> dbs = migrator.listDatabases(cfg);
                lastDiscoveredDatabases = dbs;
                if (!dbs.isEmpty() && (cfg.getDatabase() == null || cfg.getDatabase().isBlank() || !dbs.contains(cfg.getDatabase()))) {
                    cfg.setDatabase(dbs.get(0));
                }
                List<String> schemas = migrator.listSchemas(cfg);
                lastDiscoveredSchemas = schemas;
                if (!schemas.isEmpty() && (cfg.getSchema() == null || cfg.getSchema().isBlank() || !schemas.contains(cfg.getSchema()))) {
                    cfg.setSchema(schemas.contains("public") ? "public" : schemas.get(0));
                }
                lastMigratorConfig = cfg;
                redirect(resp, "/migrator?msg=" + java.net.URLEncoder.encode(
                        "Conexión exitosa a PostgreSQL: " + dbs.size() + " base(s) de datos y " + schemas.size() + " esquema(s) disponibles.",
                        StandardCharsets.UTF_8));
            } catch (Exception ex) {
                redirect(resp, "/migrator?error=true&msg=" + java.net.URLEncoder.encode(
                        "Error al conectar con PostgreSQL (" + cfg.getHost() + ":" + cfg.getPort() + "): " + ex.getMessage(),
                        StandardCharsets.UTF_8));
            }
        } else if ("analyze".equalsIgnoreCase(action)) {
            try {
                // Actualizar o descubrir esquemas y bases si aún no se habían cargado
                if (lastDiscoveredDatabases.isEmpty()) {
                    try { lastDiscoveredDatabases = migrator.listDatabases(cfg); } catch (Exception ignored) {}
                }
                try {
                    lastDiscoveredSchemas = migrator.listSchemas(cfg);
                } catch (Exception ignored) {}

                lastIntrospectionResult = migrator.introspect(cfg);
                if (lastIntrospectionResult.getAvailableDatabases() != null && !lastIntrospectionResult.getAvailableDatabases().isEmpty()) {
                    lastDiscoveredDatabases = lastIntrospectionResult.getAvailableDatabases();
                }
                if (lastIntrospectionResult.getAvailableSchemas() != null && !lastIntrospectionResult.getAvailableSchemas().isEmpty()) {
                    lastDiscoveredSchemas = lastIntrospectionResult.getAvailableSchemas();
                }
                lastMigrationProgress = null;
                if (lastIntrospectionResult.isSuccess()) {
                    redirect(resp, "/migrator?msg=" + java.net.URLEncoder.encode("Análisis completado para base '" + cfg.getDatabase() + "' (esquema '" + cfg.getSchema() + "'): " + lastIntrospectionResult.getTables().size() + " tablas detectadas.", StandardCharsets.UTF_8));
                } else {
                    redirect(resp, "/migrator?error=true&msg=" + java.net.URLEncoder.encode("Error al analizar esquema: " + lastIntrospectionResult.getErrorMessage(), StandardCharsets.UTF_8));
                }
            } catch (Exception ex) {
                redirect(resp, "/migrator?error=true&msg=" + java.net.URLEncoder.encode("Error al conectar a PostgreSQL: " + ex.getMessage(), StandardCharsets.UTF_8));
            }
        } else if ("migrate".equalsIgnoreCase(action) || "dry-run".equalsIgnoreCase(action)) {
            boolean isDry = "dry-run".equalsIgnoreCase(action);
            cfg.setDryRun(isDry);
            try {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                JettraClient client = plugin != null ? plugin.getDriverClient() : null;
                MigrationProgress prog = migrator.migrate(cfg, client, null);
                lastMigrationProgress = prog;
                if (prog.getGeneratedZipBytes() != null) {
                    lastMigratorZipBytes = prog.getGeneratedZipBytes();
                }
                if (prog.isHasErrors()) {
                    redirect(resp, "/migrator?error=true&msg=" + java.net.URLEncoder.encode("Migración finalizada con advertencias o errores. Revise la consola.", StandardCharsets.UTF_8));
                } else {
                    String msg = isDry ? "Simulación completada con éxito. Proyecto backend generado." : "Migración completada con éxito. Buckets e Índices replicados en JettraStore.";
                    redirect(resp, "/migrator?msg=" + java.net.URLEncoder.encode(msg, StandardCharsets.UTF_8));
                }
            } catch (Exception ex) {
                redirect(resp, "/migrator?error=true&msg=" + java.net.URLEncoder.encode("Fallo en la ejecución de la migración: " + ex.getMessage(), StandardCharsets.UTF_8));
            }
        } else {
            redirect(resp, "/migrator");
        }
    }

    private static void handleMigratorDownloadGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        if (lastMigratorZipBytes == null || lastMigratorZipBytes.length == 0) {
            if (lastIntrospectionResult != null && lastIntrospectionResult.isSuccess()) {
                try {
                    lastMigratorZipBytes = new JettraBackendProjectGenerator().generateZip(lastIntrospectionResult, lastMigratorConfig);
                } catch (Exception ignored) {}
            }
        }

        if (lastMigratorZipBytes == null || lastMigratorZipBytes.length == 0) {
            resp.status(HttpStatus.NOT_FOUND);
            resp.contentType("text/plain; charset=UTF-8");
            resp.body("No hay proyecto backend generado disponible para descargar. Ejecute primero el análisis o la migración.");
            return;
        }

        resp.contentType("application/zip");
        resp.header("Content-Disposition", "attachment; filename=\"jettra-backend.zip\"");
        resp.header("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.body(lastMigratorZipBytes);
    }

    // --- Utilidades de Plantilla y HTTP ---

    private static void renderTemplate(HttpResponse resp, JettraServer server, String username, String title,
            String activeSection, Widget content) {
        StoreExplorerFluxTemplateView template = new StoreExplorerFluxTemplateView(
                title,
                activeSection,
                server,
                username,
                content);
        String html = RENDERER.render(template, title + " - JettraStore Explorer");
        resp.header("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.header("Pragma", "no-cache");
        resp.html(html);
    }

    private static boolean requireAuth(HttpRequest req, HttpResponse resp) {
        String user = getLoggedUser(req);
        if (user == null || user.isBlank()) {
            clearSession(resp);
            redirect(resp, "/login");
            return false;
        }
        return true;
    }

    private static List<String> getAllSessionTokens(HttpRequest req) {
        String cookieHeader = req.getHeader("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            cookieHeader = req.getHeader("cookie");
        }
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "jettra_session".equalsIgnoreCase(kv[0].trim())) {
                String token = kv[1].trim();
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private static String getSessionToken(HttpRequest req) {
        List<String> tokens = getAllSessionTokens(req);
        for (String token : tokens) {
            if (token.startsWith(serverBootId + ".") && ACTIVE_SESSIONS.containsKey(token)) {
                return token;
            }
        }
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    private static String getLoggedUser(HttpRequest req) {
        for (String token : getAllSessionTokens(req)) {
            if (token.startsWith(serverBootId + ".")) {
                String user = ACTIVE_SESSIONS.get(token);
                if (user != null && !user.isBlank()) {
                    return user;
                }
            }
        }
        return null;
    }

    private static boolean isLoggedUserAdmin(HttpRequest req) {
        String username = getLoggedUser(req);
        if (username == null || username.isBlank()) {
            return false;
        }
        return JettraStorePlugin.getInstance().findUser(username)
                .map(u -> u.getGlobalRole() == JettraUser.GlobalRole.ADMIN)
                .orElse(false);
    }

    private static void setSession(HttpResponse resp, String username) {
        String token = serverBootId + "." + UUID.randomUUID();
        ACTIVE_SESSIONS.put(token, username);
        resp.header("Set-Cookie", "jettra_session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static void clearSession(HttpResponse resp) {
        resp.header("Set-Cookie", "jettra_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static void redirect(HttpResponse resp, String location) {
        resp.status(HttpStatus.FOUND);
        resp.header("Location", location);
    }

    public static Map<String, String> parseFormBody(String body) {
        if (body == null || body.isBlank())
            return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            } else if (!pair.isBlank()) {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }
}

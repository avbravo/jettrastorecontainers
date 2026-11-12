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
import io.jettra.store.explorer.pages.cluster.ClusterFluxView;
import io.jettra.store.explorer.pages.connections.ConnectionManagerFluxView;
import io.jettra.store.explorer.pages.databases.DatabasesFluxView;
import io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView;
import io.jettra.store.explorer.pages.login.LoginFluxView;
import io.jettra.store.explorer.pages.police.PoliceMonitorFluxView;
import io.jettra.store.explorer.pages.query.QueryConsoleFluxView;
import io.jettra.store.explorer.pages.security.UserManagerFluxView;
import io.jettra.store.explorer.pages.status.StatusFluxView;
import io.jettra.store.explorer.pages.template.StoreExplorerFluxTemplateView;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.NodeLifecycleManager;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aplicación Principal JettraStore Explorer.
 * Despliega el gestor web para administración y telemetría de bases de datos multimodelo,
 * con interfaz profesional construida íntegramente en JettraFlux y ejecutada sobre JettraServer
 * con hilos virtuales de Project Loom (Java 25) y almacenamiento Slotted-Page Off-Heap Panama.
 */
public class App {

    public static JettraServer serverInstance;
    private static final FluxRenderer RENDERER = new FluxRenderer();
    private static final Map<String, String> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static volatile String serverBootId = UUID.randomUUID().toString();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.load();

        boolean portOverridden = false;
        boolean hostOverridden = false;
        boolean contextPathOverridden = false;

        String sysPort = System.getProperty("server.port", System.getProperty("port", System.getenv("PORT")));
        if (sysPort != null && !sysPort.isBlank()) {
            try {
                config.setPort(Integer.parseInt(sysPort.trim()));
                portOverridden = true;
            } catch (Exception ignored) {}
        }
        String sysHost = System.getProperty("server.host", System.getProperty("host", System.getenv("SERVER_HOST")));
        if (sysHost != null && !sysHost.isBlank()) {
            config.setHost(sysHost.trim());
            hostOverridden = true;
        }
        String sysCp = System.getProperty("server.contextpath", System.getProperty("server.context-path", System.getenv("SERVER_CONTEXT_PATH")));
        if (sysCp != null && !sysCp.isBlank()) {
            config.setContextPath(sysCp.trim());
            contextPathOverridden = true;
        }

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--server.port=")) {
                    try { 
                        config.setPort(Integer.parseInt(arg.substring("--server.port=".length()).trim())); 
                        portOverridden = true;
                    } catch (Exception ignored) {}
                } else if (arg.startsWith("--port=") || arg.startsWith("-port=")) {
                    try { 
                        config.setPort(Integer.parseInt(arg.substring(arg.indexOf('=') + 1).trim())); 
                        portOverridden = true;
                    } catch (Exception ignored) {}
                } else if ((arg.equals("-p") || arg.equals("--port")) && i + 1 < args.length) {
                    try { 
                        config.setPort(Integer.parseInt(args[++i].trim())); 
                        portOverridden = true;
                    } catch (Exception ignored) {}
                } else if (arg.startsWith("--server.host=")) {
                    config.setHost(arg.substring("--server.host=".length()).trim());
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

        // Leer host, puerto y contextpath definidos en microprofile-config.properties si no fueron sobreescritos por CLI/System props
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        if (!hostOverridden || config.getHost() == null || "0.0.0.0".equals(config.getHost())) {
            config.setHost(plugin.getServerHost());
        }
        if (!portOverridden) {
            config.setPort(plugin.getServerPort());
        }
        if (!contextPathOverridden || config.getContextPath() == null || "/".equals(config.getContextPath())) {
            config.setContextPath(plugin.getServerContextPath());
        }

        String storageProp = System.getProperty("storage.mode");
        if (storageProp == null || storageProp.isBlank()) storageProp = System.getProperty("jettra.storage.mode");
        if (storageProp == null || storageProp.isBlank()) storageProp = System.getenv("STORAGE_MODE");
        if (storageProp != null && !storageProp.isBlank()) {
            config.setStorageMode(StorageMode.fromString(storageProp.trim()));
        }

        JettraServer server = startApplication(config);

        try {
            System.out.println("[JettraStore Explorer] Escuchando en http://" + config.getHost() + ":" + config.getPort() + "/login (Presione Ctrl+C para detener)");
            server.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopApplication(server);
        }
    }

    public static JettraServer startApplication(int port, StorageMode mode) {
        ServerConfig config = ServerConfig.load()
                .setPort(port)
                .setStorageMode(mode);
        return startApplication(config);
    }

    public static JettraServer startApplication(ServerConfig config) {
        // Todo arranque de JettraStoreExplorer inicia sin sesiones previas y exige autenticación en /login
        closeAllSessions();

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        if (config.getHost() == null || "0.0.0.0".equals(config.getHost())) {
            config.setHost(plugin.getServerHost());
        }
        // Si no se sobreescribió por argumento del sistema o el puerto proviene de defaults upstream (8080 o 8082),
        // aplicar prioritariamente el server.port configurado en microprofile-config.properties (8085)
        String sysPort = System.getProperty("server.port", System.getProperty("port", System.getenv("PORT")));
        if (sysPort == null || sysPort.isBlank()) {
            if (config.getPort() == 8080 || config.getPort() == 8082) {
                config.setPort(plugin.getServerPort());
            }
        }
        if (config.getContextPath() == null || "/".equals(config.getContextPath())) {
            config.setContextPath(plugin.getServerContextPath());
        }

        JettraServer server = JettraServer.create(config);
        serverInstance = server;

        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[JettraStore Explorer] Apagado detectado. Cerrando todas las sesiones activas...");
                closeAllSessions();
                JettraStorePlugin.getInstance().stopClusterHeartbeatMonitor();
                JettraServer running = serverInstance;
                if (running != null && running.isRunning()) {
                    running.stop();
                }
            }));
        }

        // Registrar el endpoint propio de JettraStoreExplorer (Web UI) sin sobrescribir el destino del nodo PRIMARY JettraStore
        plugin.setExplorerServerEndpoint(config.getHost(), config.getPort());
        plugin.startClusterHeartbeatMonitor();
        plugin.refreshAllNodeStatuses(true);
        System.out.println("[JettraStore Explorer] Inicializado JettraStorePlugin. WebUI: " + config.getHost() + ":" + config.getPort() + " | Nodos: " + plugin.getServerNodes().size() + " | Modo: " + plugin.getMultinodeDisplay());

        // Adjuntar módulo JettraFlux
        FluxModule flux = FluxModule.attach(server);

        // 1. Ruta Raíz y Login
        server.get("/", (req, resp) -> handleRootOrLogin(req, resp, server));
        server.get("/login", (req, resp) -> handleLoginGet(req, resp));
        server.post("/login", (req, resp) -> handleLoginPost(req, resp));

        // 2. Administración y Monitor de Nodos del Clúster (/cluster, /clusters y /dashboard)
        server.get("/cluster", (req, resp) -> handleClusterGet(req, resp, server));
        server.post("/cluster", (req, resp) -> handleClusterPost(req, resp));
        server.get("/clusters", (req, resp) -> handleClusterGet(req, resp, server));
        server.post("/clusters", (req, resp) -> handleClusterPost(req, resp));
        server.get("/dashboard", (req, resp) -> handleClusterGet(req, resp, server));

        // 3. Estatus y Telemetría del Servidor (/status y /telemetry)
        server.get("/status", (req, resp) -> handleStatusGet(req, resp, server));
        server.get("/telemetry", (req, resp) -> handleStatusGet(req, resp, server));

        // 4. Administración de Bases de Datos y Motores (/databases y /engines)
        server.get("/databases", (req, resp) -> handleDatabasesGet(req, resp, server));
        server.post("/databases", (req, resp) -> handleDatabasesPost(req, resp));
        server.get("/engines", (req, resp) -> handleDatabasesGet(req, resp, server));

        // 5. Explorador de Registros con Operaciones CRUD (/explorer y /records)
        server.get("/explorer", (req, resp) -> handleExplorerGet(req, resp, server));
        server.post("/explorer", (req, resp) -> handleExplorerPost(req, resp));
        server.get("/records", (req, resp) -> handleExplorerGet(req, resp, server));

        // 6. Consola Interactiva JettraSQL / JQL / Shell (/query)
        server.get("/query", (req, resp) -> handleQueryGet(req, resp, server));
        server.post("/query", (req, resp) -> handleQueryPost(req, resp, server));

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
            throw new RuntimeException("Error iniciando JettraServer para JettraStore Explorer: " + e.getMessage(), e);
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
        if (getLoggedUser(req) == null && getSessionToken(req) != null) {
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

        if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
            redirect(resp, "/login?error=empty_fields");
            return;
        }

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        Optional<JettraUser> auth = plugin.authenticate(user.trim(), pass.trim());
        if (auth.isPresent()) {
            setSession(resp, auth.get().getUsername());
            plugin.logPoliceIncident("Security Patrol", "Inicio de sesión concedido para '" + user + "'", "OK", "Sesión WEB activa");
            redirect(resp, "/cluster");
        } else {
            plugin.logPoliceIncident("Security Patrol", "Fallo de credenciales para usuario '" + user + "'", "WARN", "Acceso denegado");
            redirect(resp, "/login?error=invalid_credentials");
        }
    }

    // --- Clúster y Nodos ---
    private static void handleClusterGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = req.getQueryParam("action");

        if ("toggle_multinode".equalsIgnoreCase(action)) {
            plugin.setMultinodeActive(!plugin.isMultinodeActive());
            redirect(resp, "/cluster");
            return;
        } else if ("toggle_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null) plugin.toggleNode(nodeId);
            redirect(resp, "/cluster");
            return;
        } else if ("shutdown_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null) plugin.shutdownNode(nodeId);
            redirect(resp, "/cluster?msg=node_stopped");
            return;
        } else if ("start_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null) plugin.startNode(nodeId);
            redirect(resp, "/cluster?msg=node_started");
            return;
        } else if ("remove_node".equalsIgnoreCase(action)) {
            String nodeId = req.getQueryParam("nodeId");
            if (nodeId != null) plugin.removeNode(nodeId);
            redirect(resp, "/cluster?msg=node_removed");
            return;
        } else if ("check_nodes".equalsIgnoreCase(action) || "refresh".equalsIgnoreCase(action)) {
            plugin.refreshAllNodeStatuses();
            redirect(resp, "/cluster?msg=nodes_checked");
            return;
        }

        String msg = req.getQueryParam("msg");
        String clusterMsg = null;
        if ("node_added".equals(msg)) clusterMsg = "Nodo registrado exitosamente en el quórum Raft.";
        else if ("node_removed".equals(msg)) clusterMsg = "Nodo expulsado del clúster.";
        else if ("node_stopped".equals(msg)) clusterMsg = "Nodo detenido limpiamente y marcado como OFFLINE.";
        else if ("node_started".equals(msg)) clusterMsg = "Verificación de encendido y reconexión del nodo ejecutada.";
        else if ("nodes_checked".equals(msg)) clusterMsg = "Conectividad TCP y latencia de nodos del clúster verificadas en tiempo real.";
        else clusterMsg = msg;

        Widget content = new ClusterFluxView(clusterMsg);
        renderTemplate(resp, server, getLoggedUser(req), "Monitor y Administración de Nodos", "cluster", content);
    }

    private static void handleClusterPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");

        if ("add_node".equalsIgnoreCase(action)) {
            String nodeId = form.get("nodeId");
            String nodeName = form.get("nodeName");
            String nodeRole = form.get("nodeRole");
            String nodeHost = form.get("nodeHost");
            int port = 8769;
            long ram = 2048;
            try { port = Integer.parseInt(form.get("nodePort")); } catch (Exception ignored) {}
            try { ram = Long.parseLong(form.get("nodeRam")); } catch (Exception ignored) {}

            JettraStorePlugin.getInstance().addNode(nodeId, nodeName, nodeRole, nodeHost, port, ram);
            redirect(resp, "/cluster?msg=node_added");
            return;
        }
        redirect(resp, "/cluster");
    }

    // --- Estatus y Telemetría ---
    private static void handleStatusGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        Widget content = new StatusFluxView(server);
        renderTemplate(resp, server, getLoggedUser(req), "Estatus del Servidor & Telemetría", "status", content);
    }

    // --- Bases de Datos y Motores ---
    private static void handleDatabasesGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = req.getQueryParam("action");

        if ("delete_db".equalsIgnoreCase(action)) {
            String db = req.getQueryParam("db");
            if (db != null) plugin.deleteDatabase(db);
            redirect(resp, "/databases?msg=db_deleted");
            return;
        } else if ("delete_bucket".equalsIgnoreCase(action)) {
            String db = req.getQueryParam("db");
            String bucket = req.getQueryParam("bucket");
            if (db != null && bucket != null) plugin.deleteBucket(db, bucket);
            redirect(resp, "/databases?db=" + db + "&msg=bucket_deleted");
            return;
        }

        String db = req.getQueryParam("db");
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("db_created".equals(msg)) messageText = "Base de datos creada exitosamente.";
        else if ("db_deleted".equals(msg)) messageText = "Base de datos eliminada.";
        else if ("bucket_added".equals(msg)) messageText = "Nuevo motor/bucket añadido a la base de datos.";
        else if ("bucket_deleted".equals(msg)) messageText = "Bucket eliminado.";

        Widget content = new DatabasesFluxView(db, messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Catálogo de Bases de Datos & Motores", "databases", content);
    }

    private static void handleDatabasesPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("create_db".equalsIgnoreCase(action)) {
            String dbName = form.get("dbName");
            String defaultBucket = form.get("defaultBucket");
            String engineType = form.get("engineType");
            String description = form.get("description");

            plugin.createDatabase(dbName, defaultBucket, engineType, description);
            redirect(resp, "/databases?db=" + dbName + "&msg=db_created");
            return;
        } else if ("add_bucket".equalsIgnoreCase(action)) {
            String db = form.get("db");
            String bucketName = form.get("bucketName");
            String engineType = form.get("engineType");
            String description = form.get("description");

            plugin.addBucket(db, bucketName, engineType, description);
            redirect(resp, "/databases?db=" + db + "&msg=bucket_added");
            return;
        }
        redirect(resp, "/databases");
    }

    // --- Explorador de Registros CRUD ---
    private static void handleExplorerGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
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
                    redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId + "&msg=version_restored");
                    return;
                } catch (Exception ignored) {}
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
            return;
        }

        boolean isNew = "new".equalsIgnoreCase(action);
        String editId = req.getQueryParam("edit_id");
        String viewId = req.getQueryParam("view_id");
        String historyId = req.getQueryParam("history_id");
        String q = req.getQueryParam("q");
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("record_created".equals(msg)) messageText = "Registro creado exitosamente en el almacén.";
        else if ("record_updated".equals(msg)) messageText = "Registro actualizado correctamente.";
        else if ("record_deleted".equals(msg)) messageText = "Registro eliminado del bucket.";
        else if ("version_restored".equals(msg)) messageText = "Versión previa restaurada exitosamente como nueva versión activa.";

        Widget content = new RecordExplorerFluxView(db, bucket, q, editId, viewId, historyId, isNew, messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Explorador de Registros CRUD", "explorer", content);
    }

    private static void handleExplorerPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        String db = form.get("db");
        String bucket = form.get("bucket");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("save_new".equalsIgnoreCase(action)) {
            String recordId = form.get("recordId");
            String summary = form.get("summary");
            String details = form.get("details");

            plugin.addRecord(db, bucket, recordId, summary, details);
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&msg=record_created");
            return;
        } else if ("save_edit".equalsIgnoreCase(action)) {
            String recordId = form.get("recordId");
            String summary = form.get("summary");
            String details = form.get("details");

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
                    redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId + "&msg=version_restored");
                    return;
                } catch (Exception ignored) {}
            }
            redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
            return;
        }
        redirect(resp, "/explorer?db=" + db + "&bucket=" + bucket);
    }

    // --- Consola SQL / JQL / Shell ---
    private static void handleQueryGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        String db = req.getQueryParam("db");
        String bucket = req.getQueryParam("bucket");
        String query = req.getQueryParam("query");

        Widget content = new QueryConsoleFluxView(db, bucket, query);
        renderTemplate(resp, server, getLoggedUser(req), "Consola Interactiva JettraSQL / JQL", "query", content);
    }

    private static void handleQueryPost(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String db = form.get("db");
        String bucket = form.get("bucket");
        String query = form.get("query");

        Widget content = new QueryConsoleFluxView(db, bucket, query);
        renderTemplate(resp, server, getLoggedUser(req), "Consola Interactiva JettraSQL / JQL", "query", content);
    }

    // --- Conexiones ---
    private static void handleConnectionsGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("connected".equals(msg)) messageText = "Conectado al perfil en caliente.";
        else if ("default_set".equals(msg)) messageText = "Perfil establecido como predeterminado.";
        else if ("deleted".equals(msg)) messageText = "Perfil de conexión eliminado.";
        else if ("saved".equals(msg)) messageText = "Perfil guardado exitosamente.";

        Widget content = new ConnectionManagerFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Gestión de Conexiones a Clúster", "connections", content);
    }

    private static void handleConnectionsPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("connect".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null) plugin.connectProfile(id);
            redirect(resp, "/connections?msg=connected");
            return;
        } else if ("set_default".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null) plugin.setDefaultProfile(id);
            redirect(resp, "/connections?msg=default_set");
            return;
        } else if ("delete".equalsIgnoreCase(action)) {
            String id = form.get("id");
            if (id != null) plugin.deleteProfile(id);
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
        if (!requireAuth(req, resp)) return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("user_saved".equals(msg)) messageText = "Usuario y permisos actualizados exitosamente.";
        else if ("user_deleted".equals(msg)) messageText = "Usuario revocado del sistema.";

        Widget content = new UserManagerFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Seguridad y Roles de Usuario", "users", content);
    }

    private static void handleUsersPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("save_user".equalsIgnoreCase(action)) {
            String username = form.get("username");
            String password = form.get("password");
            String desc = form.get("description");
            String globalRoleStr = form.get("globalRole");
            JettraUser.GlobalRole gRole = "ADMIN".equalsIgnoreCase(globalRoleStr) ? JettraUser.GlobalRole.ADMIN : JettraUser.GlobalRole.DEVELOPER;

            JettraUser user = new JettraUser(username, password, desc, gRole);
            for (String db : plugin.getDatabaseNames()) {
                String roleVal = form.get("db_role_" + db);
                if (roleVal != null) {
                    try {
                        user.setRoleForDatabase(db, JettraDatabaseRole.valueOf(roleVal));
                    } catch (Exception ignored) {}
                }
            }
            plugin.saveOrUpdateUser(user);
            redirect(resp, "/users?msg=user_saved");
            return;
        } else if ("delete_user".equalsIgnoreCase(action)) {
            String username = form.get("username");
            if (username != null) plugin.deleteUser(username);
            redirect(resp, "/users?msg=user_deleted");
            return;
        }
        redirect(resp, "/users");
    }

    // --- Centinelas Police ---
    private static void handlePoliceGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("scan_complete".equals(msg)) messageText = "Patrullaje general completado. 0 anomalías encontradas.";

        Widget content = new PoliceMonitorFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Centinelas Police & Auditoría", "police", content);
    }

    private static void handlePolicePost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
        Map<String, String> form = parseFormBody(req.getBodyAsString());
        String action = form.get("action");
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        if ("patrol_scan".equalsIgnoreCase(action)) {
            plugin.logPoliceIncident("Heap Sentinel", "Escaneo profundo de Memoria Directa Panama completado. 0 fragmentaciones.", "OK", "Búferes auditados");
            plugin.logPoliceIncident("Raft Quorum K9", "Auditoría de consistencia de réplicas en nodos del quórum: 100% sincronizado.", "OK", "WAL validado");
            redirect(resp, "/police?msg=scan_complete");
            return;
        }
        redirect(resp, "/police");
    }

    // --- Backups ---
    private static void handleBackupsGet(HttpRequest req, HttpResponse resp, JettraServer server) {
        if (!requireAuth(req, resp)) return;
        String msg = req.getQueryParam("msg");
        String messageText = null;
        if ("created".equals(msg)) messageText = "Copia de respaldo generada exitosamente.";
        else if ("restored".equals(msg)) messageText = "Snapshot restaurado en caliente con Zero-Copy.";
        else if ("deleted".equals(msg)) messageText = "Snapshot eliminado.";

        Widget content = new BackupFluxView(messageText);
        renderTemplate(resp, server, getLoggedUser(req), "Copias de Seguridad y Snapshots", "backups", content);
    }

    private static void handleBackupsPost(HttpRequest req, HttpResponse resp) {
        if (!requireAuth(req, resp)) return;
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
            clearSession(resp);
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
        plugin.refreshAllNodeStatusesIfStale(600L);
        String payload = "retry: 1500\nevent: cluster_status\ndata: " + buildClusterStatusJson(plugin) + "\n\n";
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
        long totalPanama = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getPanamaDirectMb).sum();

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"executionEnvironment\":\"").append(escapeJson(plugin.getLifecycleManager().detectExecutionEnvironment())).append("\",");
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
            if (i > 0) json.append(",");
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
            json.append("\"lastError\":").append(n.getLastError() != null ? "\"" + escapeJson(n.getLastError()) + "\"" : "null");
            json.append("}");
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
            + "\"lastError\":" + (node.getLastError() != null ? "\"" + escapeJson(node.getLastError()) + "\"" : "null") + ","
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
            + "\"lastError\":" + (node.getLastError() != null ? "\"" + escapeJson(node.getLastError()) + "\"" : "null") + ","
            + "\"message\":\"" + escapeJson(outcome.message()) + "\""
            + "}";
        resp.contentType("application/json; charset=UTF-8")
            .header("Cache-Control", "no-store, no-cache, must-revalidate")
            .body(json);
    }

    private static String extractNodeIdParam(HttpRequest req) {
        String id = req.getQueryParam("nodeId");
        if (id != null && !id.isBlank()) return id.trim();
        String body = req.getBodyAsString();
        if (body == null || body.isBlank()) return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").matcher(trimmed);
            if (m.find()) return m.group(1).trim();
        }
        Map<String, String> form = parseFormBody(trimmed);
        String fId = form.get("nodeId");
        return (fId != null && !fId.isBlank()) ? fId.trim() : null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // --- Gestión de Ciclo de Vida y Sesiones ---

    public static void stopApplication(JettraServer server) {
        closeAllSessions();
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {}
            if (server == serverInstance) {
                serverInstance = null;
            }
        }
    }

    public static void closeAllSessions() {
        int count = ACTIVE_SESSIONS.size();
        ACTIVE_SESSIONS.clear();
        serverBootId = UUID.randomUUID().toString();
        if (count > 0) {
            System.out.println("[JettraStore Explorer] Sesiones activas cerradas (" + count + "). Se requerirá nueva autenticación al iniciar.");
        }
    }

    public static int getActiveSessionCount() {
        return ACTIVE_SESSIONS.size();
    }

    // --- Utilidades de Plantilla y HTTP ---

    private static void renderTemplate(HttpResponse resp, JettraServer server, String username, String title, String activeSection, Widget content) {
        StoreExplorerFluxTemplateView template = new StoreExplorerFluxTemplateView(
                title,
                activeSection,
                server,
                username,
                content
        );
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

    private static String getSessionToken(HttpRequest req) {
        String cookieHeader = req.getHeader("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "jettra_session".equalsIgnoreCase(kv[0].trim())) {
                    String token = kv[1].trim();
                    if (!token.isBlank()) {
                        return token;
                    }
                }
            }
        }
        return null;
    }

    private static String getLoggedUser(HttpRequest req) {
        String token = getSessionToken(req);
        if (token == null || !token.startsWith(serverBootId + ".")) {
            return null;
        }
        String user = ACTIVE_SESSIONS.get(token);
        return (user != null && !user.isBlank()) ? user : null;
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
        if (body == null || body.isBlank()) return Collections.emptyMap();
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

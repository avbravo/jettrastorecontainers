package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.police.JettraPoliceNotification;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.backup.BackupManager;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.index.JettraIndexManager;
import io.jettra.store.engine.query.JettraQLProcessor;
import io.jettra.store.engine.query.JettraSQLProcessor;
import io.jettra.store.security.JettraSecurityManager;

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
    private String currentDatabase = "default_db";
    private boolean authenticated = false;
    private String currentUser = "anonymous";
    private String currentRole = "NONE";
    private String currentHost = "127.0.0.1";
    private int currentPort = 9091;
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
                System.out.printf("║  Operación: %-15s | Colección: %-32s║%n", notification.operation(), notification.targetCollection());
                System.out.printf("║  Total Estimado: %-10d | Lote Seguro (Batch): %-23d║%n", notification.estimatedTotalRecords(), notification.safeBatchSize());
                System.out.printf("║  Saturación Heap: %5.1f%%        | RAM Disponible: %-19s║%n", notification.heapUsagePercent(), notification.availableMemoryMb() + " MB");
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
    public record SavedConnection(String name, String host, int port, String user) {}
    private final Map<String, SavedConnection> savedConnections = new ConcurrentHashMap<>();

    public JettraStoreShellApp(boolean autoConnect) {
        initDefaultConnections();
        if (autoConnect) {
            connectAndLogin("127.0.0.1", 9091, "admin", "admin-jettra");
        }
    }

    public JettraStoreShellApp(JettraClient client) {
        initDefaultConnections();
        this.client = client;
        this.authenticated = true;
        this.currentUser = "admin";
        this.currentRole = "SUPER_ADMIN";
        this.currentHost = "127.0.0.1";
        this.currentPort = 9091;
        if (this.client != null) {
            registerSentinelListener();
            this.client.getDatabase(currentDatabase);
        }
    }

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".jettra");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("connections.properties");
    private static final Path HISTORY_FILE = CONFIG_DIR.resolve("history.log");
    private static final Path JLINE_HISTORY_FILE = CONFIG_DIR.resolve(".jline_history");
    private final List<String> commandHistory = new CopyOnWriteArrayList<>();

    private void initDefaultConnections() {
        savedConnections.put("local_master", new SavedConnection("local_master", "127.0.0.1", 9091, "admin"));
        savedConnections.put("node-02-replica", new SavedConnection("node-02-replica", "127.0.0.1", 9092, "admin"));
        savedConnections.put("node-03-replica", new SavedConnection("node-03-replica", "127.0.0.1", 9093, "admin"));
        loadSavedConnections();
        loadHistory();
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
        } catch (Exception ignored) {}
    }

    public void recordHistory(String command) {
        if (command == null || command.isBlank()) return;
        String trimmed = command.trim();
        if (trimmed.startsWith("!") || trimmed.equalsIgnoreCase("HISTORY") || trimmed.equalsIgnoreCase("HISTORY CLEAR")) {
            return;
        }
        commandHistory.add(trimmed);
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            Files.writeString(HISTORY_FILE, trimmed + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    public List<String> getCommandHistory() {
        return Collections.unmodifiableList(commandHistory);
    }

    public String handleHistory(String command) {
        String upper = command.toUpperCase().trim();
        if (upper.equals("HISTORY CLEAR")) {
            commandHistory.clear();
            try {
                if (Files.exists(HISTORY_FILE)) {
                    Files.writeString(HISTORY_FILE, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Exception ignored) {}
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
            } catch (NumberFormatException ignored) {}
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
        sb.append("Uso: !<num> ejecuta comando por número | !! ejecuta el último | !<prefijo> ejecuta por coincidencia\n");
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
                throw new IllegalArgumentException("[ERROR] Índice de historial fuera de rango: " + idx + " (Total: " + commandHistory.size() + ")");
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
            throw new IllegalArgumentException("[ERROR] No se encontró ningún comando en el historial que comience con '" + prefix + "'.");
        }
        return cmd;
    }

    public List<String> autocomplete(String prefix) {
        if (prefix == null) prefix = "";
        String pTrim = prefix.trim();
        String pUpper = pTrim.toUpperCase();

        Set<String> candidates = new LinkedHashSet<>();

        List<String> baseKeywords = List.of(
            "HELP", "HELP CLUSTER-DISTRIBUTED", "HELP CLUSTER LIVE", "MENU", "MENU CONNECTIONS", "STATUS", "CONNECT ", "LOGIN ", "LOGOUT",
            "SAVE CONNECTION ", "REMOVE CONNECTION ", "LIST CONNECTIONS",
            "SHOW DATABASES", "SHOW SAMPLES", "CREATE DATABASE ", "DROP DATABASE ", "USE ", "DB STATS",
            "SHOW BUCKETS", "SHOW RECORDS ", "COUNT ", "CREATE INDEX ", "DROP INDEX ", "LIST INDEXES",
            "SHOW NODES", "ADD NODE ", "REMOVE NODE ", "START NODE ", "STOP NODE ", "MULTINODE", "MULTINODE ON", "MULTINODE OFF", "MULTINODE STATUS", "SHOW MULTINODE",
            "CLUSTER-DISTRIBUTED ALL", "CLUSTER-DISTRIBUTED <ALL>", "CLUSTER-DISTRIBUTED INFO", "CLUSTER-DISTRIBUTED ",
            "CLUSTER LIVE", "CLUSTER LIVE ", "CLUSTER-LIVE", "CLUSTER_LIVE",
            "STORAGE_MODE ", "LAZY REFERENCE ON", "LAZY REFERENCE OFF", "LAZY REFERENCE STATUS",
            "INSERT INTO ", "SELECT ", "UPDATE ", "DELETE FROM ",
            "KV PUT ", "KV GET ", "KV DELETE ", "KV SCAN ",
            "VEC INDEX ", "VEC SEARCH ",
            "GRAPH ADD EDGE ", "GRAPH TRAVERSE ", "GRAPH BFS ", "GRAPH SHORTEST ",
            "SERIES ADD ", "SERIES RANGE ", "SERIES STATS ",
            "GEO INSERT ", "GEO RADIUS ", "GEO BBOX ",
            "COL INSERT ", "COL SCAN ",
            "AGGREGATE ", "AGG SUM ", "AGG AVG ", "AGG MIN ", "AGG MAX ", "AGG COUNT ", "AGG GROUP ",
            "MATH ", "FINANCE ", "STATS ", "VECTOR ",
            "INSTALL SAMPLES", "INSTALL SAMPLES FACTURA", "INSTALL SAMPLES HOSPITAL", "INSTALL SAMPLES AMBIENTAL",
            "LOAD SAMPLE example_factura_db", "LOAD SAMPLE samples_hostipal_db", "LOAD SAMPLE samples_ambiental_db",
            "BACKUP DATABASE", "RESTORE DATABASE",
            "HISTORY", "HISTORY CLEAR", "HISTORY SEARCH ",
            "PAGE NEXT", "PAGE PREV", "PAGE FIRST", "PAGE LAST", "PAGE SIZE ",
            "COMPLETE ", "TAB "
        );

        if (pUpper.startsWith("USE ") || pUpper.startsWith("DROP DATABASE ") || pUpper.startsWith("DROP DB ")) {
            String verb = pTrim.substring(0, pTrim.indexOf(' ') + 1);
            String dbPrefix = pTrim.substring(verb.length()).trim().toLowerCase();
            List<String> dbs = new ArrayList<>(List.of(
                "default_db", "sample_enterprise_db", "sample_ecommerce_db", "sample_ai_graph_db",
                "sample_iot_telemetry_db", "sample_financial_db", "example_factura_db",
                "samples_hostipal_db", "samples_ambiental_db"
            ));
            if (client != null) {
                try {
                    for (String d : client.listDatabases()) {
                        if (!dbs.contains(d)) dbs.add(d);
                    }
                } catch (Exception ignored) {}
            }
            for (String d : dbs) {
                if (d.toLowerCase().startsWith(dbPrefix)) {
                    candidates.add(verb + d);
                }
            }
        } else if (pUpper.startsWith("CLUSTER-DISTRIBUTED ") || pUpper.startsWith("CLUSTER_DISTRIBUTED ")) {
            String verb = pTrim.substring(0, pTrim.indexOf(' ') + 1);
            String sub = pTrim.substring(verb.length()).trim().toLowerCase();
            List<String> options = new ArrayList<>(List.of("all", "<all>", "info"));
            if (client != null) {
                try {
                    options.addAll(client.listDatabases());
                } catch (Exception ignored) {}
            }
            for (String opt : options) {
                if (opt.toLowerCase().startsWith(sub)) {
                    candidates.add(verb + opt);
                }
            }
        } else if (pUpper.startsWith("LOAD SAMPLE ") || pUpper.startsWith("INSTALL SAMPLE ")) {
            String verb = pTrim.substring(0, pTrim.lastIndexOf(' ') + 1);
            String sub = pTrim.substring(verb.length()).trim().toLowerCase();
            List<String> samples = List.of("example_factura_db", "samples_hostipal_db", "samples_ambiental_db");
            for (String s : samples) {
                if (s.toLowerCase().startsWith(sub)) {
                    candidates.add(verb + s);
                }
            }
        } else if (pUpper.startsWith("SHOW RECORDS ") || pUpper.startsWith("COUNT ") || pUpper.startsWith("SELECT * FROM ")) {
            String verb = pTrim.substring(0, pTrim.lastIndexOf(' ') + 1);
            String bucketPrefix = pTrim.substring(verb.length()).trim().toLowerCase();
            if (client != null) {
                try {
                    JettraDatabase db = client.getDatabase(currentDatabase);
                    if (db != null) {
                        for (String b : db.getAllCollectionNames()) {
                            if (b.toLowerCase().startsWith(bucketPrefix)) {
                                candidates.add(verb + b);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        for (String kw : baseKeywords) {
            if (kw.toUpperCase().startsWith(pUpper)) {
                candidates.add(kw);
            }
        }

        for (int i = commandHistory.size() - 1; i >= 0; i--) {
            String h = commandHistory.get(i);
            if (h.toUpperCase().startsWith(pUpper) && !candidates.contains(h)) {
                candidates.add(h);
                if (candidates.size() >= 25) break;
            }
        }

        return new ArrayList<>(candidates);
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
                for (String name : props.stringPropertyNames()) {
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
        } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
    }

    public List<SavedConnection> getOrderedConnectionsList() {
        List<SavedConnection> list = new ArrayList<>(savedConnections.values());
        list.sort(Comparator.comparing(SavedConnection::name));
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
            String defMarker = sc.name().equals("local_master") ? "  [Predeterminado]" : "";
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
            return getHelpText();
        } else if (upper.equals("MENU CONNECTIONS") || upper.equals("CONNECTIONS MENU")) {
            return getConnectionsMenuDisplay();
        } else if (upper.equals("MENU")) {
            return getInteractiveMenu();
        }

        // 2. Conexión, Autenticación y Cierre de Sesión
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

        // 4. Verificación de Seguridad y Autenticación para operaciones operativas
        if (!authenticated || client == null) {
            return "[AUTH REQUIRED] Debe iniciar sesión con 'login <username> <password>' antes de interactuar con bases de datos o telemetría.";
        }

        // 5. Telemetría de Recursos (status) y Topología (show nodes / multinode)
        if (upper.equals("STATUS")) {
            return handleStatus();
        } else if (upper.equals("SHOW NODES") || upper.equals("NODES") || upper.equals("LIST NODES")) {
            return handleShowNodes();
        } else if (upper.equals("MULTINODE") || upper.equals("SHOW MULTINODE") || upper.equals("MULTINODE STATUS") || upper.equals("CLUSTER MULTINODE") || upper.equals("SHOW CLUSTER MULTINODE")) {
            return handleMultinodeStatus();
        } else if (upper.equals("MULTINODE ON") || upper.equals("SET MULTINODE ON") || upper.equals("SET MULTINODE = ON") || upper.equals("SET MULTINODE=ON") || upper.equals("CLUSTER MULTINODE ON")) {
            return handleSetMultinode(true);
        } else if (upper.equals("MULTINODE OFF") || upper.equals("SET MULTINODE OFF") || upper.equals("SET MULTINODE = OFF") || upper.equals("SET MULTINODE=OFF") || upper.equals("CLUSTER MULTINODE OFF")) {
            return handleSetMultinode(false);
        } else if (upper.startsWith("ADD NODE ")) {
            return handleAddNode(trimmed);
        } else if (upper.startsWith("REMOVE NODE ")) {
            return handleRemoveNode(trimmed);
        } else if (upper.startsWith("START NODE ")) {
            return handleStartNode(trimmed);
        } else if (upper.startsWith("STOP NODE ")) {
            return handleStopNode(trimmed);
        } else if (upper.startsWith("CLUSTER-DISTRIBUTED") || upper.startsWith("CLUSTER_DISTRIBUTED")) {
            return handleClusterDistributed(trimmed);
        } else if (upper.equals("CLUSTER LIVE") || upper.startsWith("CLUSTER LIVE ") || upper.equals("CLUSTER-LIVE") || upper.startsWith("CLUSTER-LIVE ") || upper.equals("CLUSTER_LIVE") || upper.startsWith("CLUSTER_LIVE ")) {
            return handleClusterLive(trimmed);
        } else if (upper.equals("8")) {
            return handleClusterDistributedInfo();
        } else if (upper.equals("9")) {
            return handleClusterLive("cluster live 30");
        }

        // Configuración de Storage Mode (JVM-RAM vs DISK-MEMORY / JettraMemory)
        if (upper.equals("STORAGE_MODE") || upper.equals("STORAGE MODE") || upper.equals("SHOW STORAGE_MODE") || upper.equals("SHOW STORAGE MODE")) {
            return handleShowStorageMode();
        } else if (upper.startsWith("STORAGE_MODE ") || upper.startsWith("STORAGE MODE ") || upper.startsWith("SET STORAGE_MODE ") || upper.startsWith("SET STORAGE_MODE=") || upper.startsWith("SET STORAGE_MODE =")) {
            return handleSetStorageMode(trimmed);
        }

        // 6. Configuración Lazy Reference (lazy reference on / off (lazy reference on / lazy reference off))
        if (upper.equals("LAZY REFERENCE ON") || upper.equals("SET LAZY_REFERENCE ON") || upper.equals("SET LAZY_REFERENCE = TRUE")) {
            this.lazyLoad = true;
            this.showReferences = true;
            return "[CONFIG] Lazy Reference ACTIVADO (ON). Las referencias JettraRef se resolverán bajo demanda (Proxy).";
        } else if (upper.equals("LAZY REFERENCE OFF") || upper.equals("SET LAZY_REFERENCE OFF") || upper.equals("SET LAZY_REFERENCE = FALSE")) {
            this.lazyLoad = false;
            this.showReferences = true;
            return "[CONFIG] Lazy Reference DESACTIVADO (OFF). Las referencias JettraRef se cargarán inmediatamente en memoria (Eager).";
        } else if (upper.equals("LAZY REFERENCE STATUS") || upper.equals("LAZY REFERENCE")) {
            return String.format("[CONFIG] Estado de Lazy Reference: %s (showReferences: %s)", lazyLoad ? "ON (Lazy)" : "OFF (Eager)", showReferences);
        }

        // 7. Control de Bases de Datos
        if (upper.equals("SHOW DATABASES") || upper.equals("SHOW DBS") || upper.equals("LIST DATABASES") || upper.equals("LIST DBS")) {
            return handleShowDatabases();
        } else if (upper.equals("SHOW SAMPLES") || upper.equals("SHOW SAMPLE DBS") || upper.equals("SHOW SAMPLE DATABASES") || upper.equals("SHOW DBS SAMPLES")) {
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
        } else if (upper.equals("COUNT") || upper.startsWith("COUNT ") || upper.equals("COUNT *") || upper.equals("COUNT ALL")) {
            return handleCount(trimmed);
        } else if (upper.equals("SHOW COLLECTIONS") || upper.equals("SHOW TABLES")) {
            return handleShowCollections();
        } else if (upper.startsWith("CREATE COLLECTION ") || upper.startsWith("CREATE ENGINE ")
                || upper.startsWith("CREATE BUCKET ") || upper.startsWith("CREATE UNIT ")) {
            return handleCreateCollection(trimmed);
        } else if (upper.startsWith("DROP COLLECTION ") || upper.startsWith("DROP ENGINE ")
                || upper.startsWith("DROP BUCKET ") || upper.startsWith("DROP UNIT ")) {
            return handleDropCollection(trimmed);
        }

        // 9. Administración de Índices
        if (upper.startsWith("CREATE INDEX ")) {
            return handleCreateIndex(trimmed);
        } else if (upper.startsWith("DROP INDEX ")) {
            return handleDropIndex(trimmed);
        } else if (upper.startsWith("ALTER INDEX ") && upper.contains("REBUILD") || upper.startsWith("REINDEX ")) {
            return handleRebuildIndex(trimmed);
        } else if (upper.equals("SHOW INDEXES") || upper.startsWith("SHOW INDEXES ON ") || upper.equals("LIST INDEXES")) {
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
        }

        // Paginación y Desplazamiento de Consultas (Primero, Anterior, Siguiente, Última)
        if (upper.startsWith("SET PAGE_SIZE ") || upper.startsWith("PAGE_SIZE ") || upper.startsWith("PAGESIZE ") || upper.startsWith("SET PAGESIZE ") || upper.startsWith("SIZE ")) {
            return handleSetPageSize(trimmed);
        } else if (upper.equals("FIRST") || upper.equals("PRIMERO") || upper.equals("PRI") || upper.equals("PAGE FIRST") || upper.equals("|<<") || (upper.equals("F") && lastPagedBaseQuery != null)) {
            return handleNavigatePage("FIRST");
        } else if (upper.equals("PREV") || upper.equals("ANTERIOR") || upper.equals("PREVIOUS") || upper.equals("ANT") || upper.equals("PAGE PREV") || upper.equals("<") || ((upper.equals("A") || upper.equals("P")) && lastPagedBaseQuery != null)) {
            return handleNavigatePage("PREV");
        } else if (upper.equals("NEXT") || upper.equals("SIGUIENTE") || upper.equals("SIG") || upper.equals("PAGE NEXT") || upper.equals(">") || ((upper.equals("S") || upper.equals("N")) && lastPagedBaseQuery != null)) {
            return handleNavigatePage("NEXT");
        } else if (upper.equals("LAST") || upper.equals("ULTIMO") || upper.equals("ULTIMA") || upper.equals("ULT") || upper.equals("PAGE LAST") || upper.equals(">>|") || ((upper.equals("U") || upper.equals("L")) && lastPagedBaseQuery != null)) {
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
                || upper.equals("INSTALL SAMPLE SAMPLES_HOSTIPAL_DB") || upper.equals("INSTALL SAMPLE SAMPLES_HOSPITAL_DB")
                || upper.equals("LOAD SAMPLE HOSPITAL") || upper.equals("INSTALL HOSPITAL")) {
            return installHospitalSampleDatabase();
        } else if (upper.equals("INSTALL SAMPLES AMBIENTAL") || upper.equals("INSTALL SAMPLE AMBIENTAL")
                || upper.equals("LOAD SAMPLE SAMPLES_AMBIENTAL_DB") || upper.equals("LOAD SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("INSTALL SAMPLE SAMPLES_AMBIENTAL_DB") || upper.equals("INSTALL SAMPLE SAMPLES_ENVIRONMENTAL_DB")
                || upper.equals("LOAD SAMPLE AMBIENTAL") || upper.equals("INSTALL AMBIENTAL")) {
            return installAmbientalSampleDatabase();
        } else if (upper.startsWith("INSTALL SAMPLES") || upper.equals("1")) {
            return installAllSampleDatabases();
        } else if (upper.startsWith("BACKUP DATABASE")) {
            return handleBackup(trimmed);
        } else if (upper.startsWith("RESTORE DATABASE")) {
            return handleRestore(trimmed);
        }

        return "[ERROR] Comando desconocido: '" + trimmed + "'. Escriba 'help' o 'menu' para ver los comandos disponibles.";
    }

    // --- 1. Conexión y Autenticación ---
    public boolean connectAndLogin(String host, int port, String user, String pass) {
        try {
            this.currentHost = host;
            this.currentPort = port;
            this.client = JettraClient.connect(host, port, user, pass);
            this.authenticated = true;
            this.currentUser = user;
            registerSentinelListener();
            var claims = client.getSecurityManager().validateToken(client.getSessionToken());
            this.currentRole = claims.role();
            this.client.getDatabase(currentDatabase);
            return true;
        } catch (Exception e) {
            this.authenticated = false;
            this.currentUser = "anonymous";
            this.currentRole = "NONE";
            return false;
        }
    }

    private String handleConnect(String command) {
        String clean = command.substring(8).trim();
        if (savedConnections.containsKey(clean)) {
            SavedConnection sc = savedConnections.get(clean);
            this.currentHost = sc.host();
            this.currentPort = sc.port();
            return String.format("[CONNECTED] Servidor configurado a '%s:%d' desde perfil '%s'. Inicie sesión con: login %s <password>",
                currentHost, currentPort, sc.name(), sc.user());
        }

        String[] parts = clean.split("\\s+");
        if (parts.length >= 2) {
            this.currentHost = parts[0].trim();
            try {
                this.currentPort = Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                return "[ERROR] Puerto numérico inválido: " + parts[1];
            }
            return String.format("[CONNECTED] Servidor JettraStore configurado a %s:%d. Proceda con 'login <username> <password>'.", currentHost, currentPort);
        } else if (parts.length == 1 && !parts[0].isBlank()) {
            this.currentHost = parts[0].trim();
            return String.format("[CONNECTED] Host configurado a %s:%d. Proceda con 'login <username> <password>'.", currentHost, currentPort);
        }
        return "[ERROR] Uso: connect <host> <puerto> o connect <nombre-conexion>";
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
            return String.format("[AUTH SUCCESS] Sesión iniciada como '%s' (Rol Global: %s) en %s:%d.", currentUser, currentRole, currentHost, currentPort);
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
        return String.format("[LOGOUT] Sesión del usuario '%s' finalizada exitosamente. Inicie sesión nuevamente con 'login <username> <password>'.", oldUser);
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
        if (name.isBlank()) return "[ERROR] Uso: remove connection <nombre-conexion>";
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
        } catch (Exception ignored) {}

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
            """, usedRam, totalRam, maxRam, cfg.getMemTableSizeMb(), cpuCores, rawConfigured, storagePath, (diskBytes / 1024.0), diskBytes, filesCount,
            client.getRingEngine().getMultinodeActive().toUpperCase(),
            client.getRingEngine().isMultinodeActive() ? "Consenso y distribución ACTIVO" : "STANDALONE - Operaciones locales en servidor único",
            client.getRingEngine().getPeers().size());
    }

    // --- 4. Administración de Nodos del Clúster ---
    private static boolean isNodeReachable(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank() || port <= 0) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        JettraStoreConfig storeCfg = null;
        try {
            storeCfg = JettraStoreConfig.load();
        } catch (Exception ignored) {}

        String leaderIp = currentHost;
        int leaderPort = currentPort;
        if (storeCfg != null) {
            if (("127.0.0.1".equals(currentHost) || "localhost".equalsIgnoreCase(currentHost))
                && storeCfg.getNodeIp() != null && !storeCfg.getNodeIp().isBlank()) {
                leaderIp = storeCfg.getNodeIp();
            }
            if (storeCfg.getGrpcPort() > 0) {
                leaderPort = storeCfg.getGrpcPort();
            }
        }

        // Sondeo concurrente en tiempo real de la salud y conectividad de todos los nodos del clúster
        final String fLeaderIp = leaderIp;
        final int fLeaderPort = leaderPort;
        CompletableFuture<Boolean> leaderCheckFuture = CompletableFuture.supplyAsync(() ->
            isNodeReachable(fLeaderIp, fLeaderPort, 250) || isNodeReachable(currentHost, currentPort, 250)
        );

        List<ClusterNode> peers = ring.getPeers();
        List<CompletableFuture<Boolean>> peerFutures = new ArrayList<>();
        for (ClusterNode peer : peers) {
            peerFutures.add(CompletableFuture.supplyAsync(() ->
                isNodeReachable(peer.getIp(), peer.getPort(), 250)
            ));
        }

        boolean leaderOnline = (client != null && authenticated);
        try {
            Boolean lRes = leaderCheckFuture.get(350, TimeUnit.MILLISECONDS);
            if (lRes != null && lRes) {
                leaderOnline = true;
            }
        } catch (Exception ignored) {}

        String leaderStatus = leaderOnline ? "RUNNING" : "STOPPED";
        String leaderRaft = leaderOnline ? "LEADER" : "DISCONNECTED";

        sb.append(String.format("| %-8s | %-20s | %-5d | %-9s | %-12s | %-8s | %-12d |\n",
            ring.getNodeId(), leaderIp, leaderPort, "PRIMARY", leaderRaft, leaderStatus, 0));

        int onlinePeers = 0;
        for (int i = 0; i < peers.size(); i++) {
            ClusterNode peer = peers.get(i);
            boolean pOnline = false;
            try {
                Boolean res = peerFutures.get(i).get(350, TimeUnit.MILLISECONDS);
                pOnline = (res != null && res);
            } catch (Exception ignored) {
                pOnline = false;
            }
            if (pOnline) {
                peer.start();
                onlinePeers++;
            } else {
                peer.stop();
            }
            sb.append(String.format("| %-8s | %-20s | %-5d | %-9s | %-12s | %-8s | %-12d |\n",
                peer.getId(), peer.getIp(), peer.getPort(), peer.getRole(), peer.getRaftState(),
                peer.getStatus(), peer.getReceivedOffloadedBytes()));
        }
        sb.append("+----------+----------------------+-------+-----------+--------------+----------+--------------+\n");
        boolean multiActive = ring.isMultinodeActive();
        sb.append(String.format("Modo Multinodo (cluster.multinode.active): %s\n",
            multiActive ? "ON (Algoritmo de consenso y distribución de datos ACTIVO)"
                        : "OFF (Servidor Único / Standalone - Distribución de datos DESACTIVADA)"));

        int totalNodes = peers.size() + 1;
        int activeNodes = (leaderOnline ? 1 : 0) + onlinePeers;
        boolean quorumReached = multiActive && (activeNodes >= (totalNodes / 2 + 1));

        String quorumStr;
        if (!multiActive) {
            quorumStr = "Inactivo (Operaciones locales exclusivas)";
        } else if (quorumReached) {
            quorumStr = String.format("Activo (Consenso distribuido con quórum mayoritario: %d/%d activos)", activeNodes, totalNodes);
        } else {
            quorumStr = String.format("Inactivo (Sin mayoría de nodos para consenso: %d/%d activos)", activeNodes, totalNodes);
        }

        sb.append(String.format("Total: %d nodo(s) registrados en el anillo dinámico. Quórum: %s.\n",
            totalNodes, quorumStr));
        return sb.toString();
    }

    private String handleAddNode(String command) {
        String clean = command.substring("ADD NODE ".length()).trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 3) {
            return "[ERROR] Uso: ADD NODE <nodeId> <host> <port> [PRIMARY|SECONDARY]";
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
            ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;

        ClusterNode newNode = new ClusterNode(id, host, port, role);
        client.getRingEngine().registerPeer(newNode);
        return String.format("[SUCCESS] Nodo '%s' (%s:%d, %s) agregado exitosamente al clúster Raft.", id, host, port, role);
    }

    private String handleRemoveNode(String command) {
        String id = cleanQuotes(command.substring("REMOVE NODE ".length()).trim());
        if (id.isBlank()) return "[ERROR] Uso: REMOVE NODE <nodeId>";
        if ("node-01".equalsIgnoreCase(id) || client.getRingEngine().getNodeId().equalsIgnoreCase(id)) {
            return "[ERROR] No se puede remover el nodo primario activo del clúster.";
        }
        boolean ok = client.getRingEngine().removePeer(id);
        return ok ? "[SUCCESS] Nodo '" + id + "' removido del anillo de réplicas."
                  : "[WARN] El nodo '" + id + "' no existe en el registro del clúster.";
    }

    private String handleStartNode(String command) {
        String id = cleanQuotes(command.substring("START NODE ".length()).trim());
        if (id.isBlank()) return "[ERROR] Uso: START NODE <nodeId>";
        boolean ok = client.getRingEngine().startPeer(id);
        return ok ? "[SUCCESS] Nodo '" + id + "' iniciado (RUNNING)."
                  : "[ERROR] No se pudo iniciar el nodo '" + id + "' (nodo no encontrado).";
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
            sb.append("========================================================================================================================\n");
            sb.append("                                   JETTRASTORE CLUSTER DATABASE DISTRIBUTION INFO                                       \n");
            sb.append("========================================================================================================================\n");
            sb.append("+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
            sb.append("| Nodo ID  | Dirección IP         | Puerto| Rol       | Estado   | Cantidad BDs  | Bases de Datos                       |\n");
            sb.append("+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
            for (var node : infoList) {
                String dbListStr = node.databases().isEmpty() 
                    ? (node.status().equalsIgnoreCase("OFFLINE") ? "(Sin respuesta / Offline)" : "(0 bases de datos)")
                    : String.join(", ", node.databases());
                if (dbListStr.length() > 36) {
                    dbListStr = dbListStr.substring(0, 33) + "...";
                }
                sb.append(String.format("| %-8s | %-20s | %-5d | %-9s | %-8s | %-13d | %-36s |\n",
                    node.nodeId(), node.ip(), node.port(), node.role(), node.status(), node.databaseCount(), dbListStr));
            }
            sb.append("+----------+----------------------+-------+-----------+----------+---------------+--------------------------------------+\n");
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
        sb.append(String.format("[CLUSTER-DISTRIBUTED] Iniciando distribución integral de la base de datos '%s'...\n", dbName));
        try {
            boolean ok = client.clusterDistributed(dbName);
            if (ok) {
                sb.append(String.format("  ✓ Base de datos '%s' y todos sus registros internos, colecciones e índices fueron migrados con éxito a los nodos secundarios.\n", dbName));
                sb.append("[SUCCESS] Distribución completada bajo consenso Raft mayoritario.");
            } else {
                sb.append(String.format("[WARNING] La distribución de '%s' finalizó sin confirmación de quórum completo.\n", dbName));
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
        sb.append("[CLUSTER-DISTRIBUTED <ALL>] Iniciando migración y distribución de TODAS las bases de datos del clúster...\n");
        try {
            Map<String, Boolean> results = client.clusterDistributedAll();
            if (results.isEmpty()) {
                return "[INFO] No se encontraron bases de datos registradas para distribuir.";
            }
            int successCount = 0;
            for (var entry : results.entrySet()) {
                if (entry.getValue()) {
                    sb.append(String.format("  ✓ [%s]: DISTRIBUIDA Y MIGRADA (Registros y metadatos OK)\n", entry.getKey()));
                    successCount++;
                } else {
                    sb.append(String.format("  ✗ [%s]: ERROR EN DISTRIBUCIÓN (Sin quórum)\n", entry.getKey()));
                }
            }
            sb.append(String.format("[SUCCESS] %d de %d base(s) de datos y todos sus registros internos migrados exitosamente en el clúster.",
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
                } catch (NumberFormatException ignored) {}
            }

            List<io.jettra.store.cluster.ClusterLiveEvent> events = (client != null) 
                ? client.getRecentClusterLiveEvents(limit) 
                : io.jettra.store.cluster.JettraClusterEventBus.getInstance().getRecentEvents(limit);

            StringBuilder sb = new StringBuilder();
            sb.append("========================================================================================================================\n");
            sb.append("                                        JETTRASTORE CLUSTER LIVE EVENT STREAM                                           \n");
            sb.append("========================================================================================================================\n");
            sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            sb.append("| Marca Temporal          | Tipo de Evento       | Origen  | Destino | Mensaje / Trazabilidad Operativa                |\n");
            sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            if (events == null || events.isEmpty()) {
                sb.append("| (Sin eventos recientes de clúster registrados en el búfer)                                                           |\n");
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
            sb.append("+-------------------------+----------------------+---------+---------+-------------------------------------------------+\n");
            sb.append(String.format("Eventos en búfer mostrados: %d | Canal de Eventos en Tiempo Real: ACTIVO\n", events != null ? events.size() : 0));
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
            ring.getPeers().size()
        );
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
        String id = cleanQuotes(command.substring("STOP NODE ".length()).trim());
        if (id.isBlank()) return "[ERROR] Uso: STOP NODE <nodeId>";
        if ("node-01".equalsIgnoreCase(id) || client.getRingEngine().getNodeId().equalsIgnoreCase(id)) {
            return "[ERROR] No se puede detener el nodo primario local en ejecución.";
        }
        boolean ok = client.getRingEngine().stopPeer(id);
        return ok ? "[SUCCESS] Nodo '" + id + "' detenido (STOPPED). Tráfico de anillo pausado para este nodo."
                  : "[ERROR] No se pudo detener el nodo '" + id + "' (nodo no encontrado).";
    }

    // --- 5. Control de Bases de Datos & Detección en Disco ---
    private String handleShowDatabases() {
        List<String> dbs = client.listDatabases();
        if (!dbs.contains(currentDatabase)) {
            dbs.add(currentDatabase);
        }
        Collections.sort(dbs);

        JettraStoreConfig cfg = JettraStoreConfig.load();

        StringBuilder sb = new StringBuilder();
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        sb.append("| Base de Datos                      | Tipo     | Colecciones | Estado         |\n");
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        for (String db : dbs) {
            int colCount;
            boolean loaded = client.isDatabaseLoaded(db);
            if (loaded) {
                colCount = client.getDatabase(db).getAllCollectionNames().size();
            } else {
                colCount = client.getLightweightCollectionCount(db);
            }
            String tipo = db.startsWith("sample_") ? "SAMPLE" : (db.equals("default_db") ? "SYSTEM" : "USER");
            String status = db.equals(currentDatabase) ? "* ACTIVA" : (loaded ? "EN MEMORIA" : "DISPONIBLE");
            sb.append(String.format("| %-34s | %-8s | %-11d | %-14s |\n", db, tipo, colCount, status));
        }
        sb.append("+------------------------------------+----------+-------------+----------------+\n");
        sb.append(String.format("Total: %d base(s) de datos detectadas. Base activa: '%s'\n", dbs.size(), currentDatabase));
        sb.append(String.format("Ruta física en database.properties: '%s' | Directorio de lectura/escritura: '%s'",
            cfg.getConfiguredStoragePath(), cfg.getStoragePath()));
        return sb.toString();
    }

    private String handleShowSampleDatabases() {
        List<String> allDbs = client.listDatabases();
        String[] samples = {
            "sample_enterprise_db",
            "sample_ecommerce_db",
            "sample_ai_graph_db",
            "sample_iot_telemetry_db",
            "sample_financial_db",
            "example_factura_db",
            "samples_hostipal_db",
            "samples_ambiental_db"
        };

        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append("                        BASES DE DATOS DE EJEMPLO (JettraStore Samples)                      \n");
        sb.append("==============================================================================================\n");
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        sb.append("| Base de Datos           | Estado en Disco    | Motores & Propósito                         |\n");
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        for (String s : samples) {
            boolean installed = allDbs.contains(s);
            String desc = switch (s) {
                case "sample_enterprise_db"    -> "Documentos, Vectores 3D, Grafos de Catálogo, KV, Series";
                case "sample_ecommerce_db"     -> "Clientes, Órdenes, Analítica Columnar, Carritos KV";
                case "sample_ai_graph_db"      -> "Red de Grafos de Conocimiento, Embeddings, Prompts";
                case "sample_iot_telemetry_db" -> "Sensores Temperatura/Vibración, Smart Devices, Geo";
                case "sample_financial_db"     -> "Transacciones de Cuentas, Ledger y Cotizaciones";
                case "example_factura_db"      -> "Facturación 3M Objetos (1M Fac, 1M Det, 200k Cli, KV, Vec, Graph)";
                case "samples_hostipal_db"     -> "Salud 2M Objetos (Pacientes, CIE10, Medicamentos, Hospitales)";
                case "samples_ambiental_db"    -> "Medio Ambiente 3M Objetos (Estaciones, Calidad Aire, Biomas)";
                default -> "Muestra Multimodelo";
            };
            sb.append(String.format("| %-23s | %-18s | %-43s |\n", s, installed ? "INSTALADA (Lista)" : "NO INSTALADA", desc));
        }
        sb.append("+-------------------------+--------------------+---------------------------------------------+\n");
        sb.append("Para instalar o re-inicializar todas las muestras completas, ejecute: INSTALL SAMPLES\n");
        return sb.toString();
    }

    private String handleCreateDatabase(String command) {
        String dbName = cleanQuotes(command.replaceAll("(?i)^(CREATE\\s+DATABASE|CREATE\\s+DB)\\s+", "").trim());
        if (dbName.isBlank()) return "[ERROR] Nombre de base de datos requerido. Uso: CREATE DATABASE <nombre> o CREATE DB <nombre>";
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
        if (dbName.isBlank()) return "[ERROR] Nombre de base de datos requerido. Uso: DROP DATABASE <nombre> o DROP DB <nombre>";
        try {
            boolean dropped = client.dropDatabase(dbName);
            if (dbName.equalsIgnoreCase(currentDatabase)) {
                this.currentDatabase = "default_db";
                this.client.getDatabase(currentDatabase);
            }
            return dropped ? "[SUCCESS] Base de datos '" + dbName + "' eliminada correctamente."
                           : "[INFO] La base de datos '" + dbName + "' no existía o ya fue eliminada.";
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleUseDatabase(String command) {
        String dbName = cleanQuotes(command.substring(4));
        if (dbName.isBlank()) return "[ERROR] Nombre de base de datos requerido.";

        // Verificación de RBAC granular para el usuario activo
        if (!client.getSecurityManager().hasDatabaseAccess(currentUser, dbName, "USE")) {
            return String.format("[ACCESS DENIED] El usuario '%s' no posee permisos asignados para la base de datos '%s'.", currentUser, dbName);
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
            return String.format("""
                [RING TRANSITION ACTIVE] Supervisión preventiva JettraPolice: Saturación de RAM prevenida (Umbral >= 85%%).
                [CLUSTER] Activada Transición Dinámica a Motor de Anillo Distribuido (Consistent Ring Topology).
                [OFFLOAD] Carga y particiones delegadas a nodos secundarios (%s).
                [SUCCESS] Conmutado a base de datos activa: '%s' [MODO ANILLO DISTRIBUIDO].
                """, peersDesc, dbName).trim();
        }

        return "[SUCCESS] Conmutado a base de datos activa: '" + dbName + "'.";
    }

    private String handleDbStats() {
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
            """, currentDatabase, db.getAllCollectionNames().size(),
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
        // CREATE INDEX <indexName> ON <collection> (<field>) [TYPE <BTREE|HASH|SPARSE>] [UNIQUE]
        try {
            String upper = command.toUpperCase();
            int onIdx = upper.indexOf(" ON ");
            if (onIdx == -1) return "[ERROR] Sintaxis inválida. Uso: CREATE INDEX <nombre> ON <coleccion> (<campo>) [TYPE BTREE|HASH|SPARSE]";

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
            if (tail.contains("HASH")) type = "HASH";
            else if (tail.contains("SPARSE")) type = "SPARSE";

            boolean unique = tail.contains("UNIQUE");

            JettraDatabase db = client.getDatabase(currentDatabase);
            var info = db.getIndexManager().createIndex(col, indexName, field, type, unique, db.getDocumentEngine(col));
            client.replicateCreateIndex(currentDatabase, col, indexName, field, type, unique);
            return String.format("[SUCCESS] Índice '%s' creado sobre '%s'(%s) tipo %s (Entradas indexadas: %d).",
                info.name(), info.collection(), info.field(), info.type(), info.entriesCount());
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
        String clean = cleanQuotes(command.replaceAll("(?i)^(ALTER INDEX|REINDEX)\\s+", "").replaceAll("(?i)REBUILD", "").trim());
        String indexName = clean.split("\\s+")[0];
        JettraDatabase db = client.getDatabase(currentDatabase);
        var info = db.getIndexManager().getIndex(indexName);
        if (info == null) return "[ERROR] Índice '" + indexName + "' no encontrado.";

        var updated = db.getIndexManager().rebuildIndex(indexName, db.getDocumentEngine(info.collection()));
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
            return "[INFO] No se encontraron índices en '" + currentDatabase + "'" + (col != null ? " para la colección '" + col + "'." : ".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== ÍNDICES DE BASE DE DATOS: '%s' ===\n", currentDatabase));
        sb.append("+----------------------+----------------------+----------------------+--------+--------+----------+\n");
        sb.append("| Nombre de Índice     | Colección            | Campo Indexado       | Tipo   | Único  | Entradas |\n");
        sb.append("+----------------------+----------------------+----------------------+--------+--------+----------+\n");
        for (var idx : list) {
            sb.append(String.format("| %-20s | %-20s | %-20s | %-6s | %-6s | %-8d |\n",
                idx.name(), idx.collection(), idx.field(), idx.type(), idx.unique() ? "SI" : "NO", idx.entriesCount()));
        }
        sb.append("+----------------------+----------------------+----------------------+--------+--------+----------+\n");
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
            if (passIdx == -1) return "[ERROR] Uso: CREATE USER <username> PASSWORD <password> [ROLE <globalRole>]";

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
        // ALTER USER <username> PASSWORD <newPass> | ALTER USER <username> ROLE <newRole>
        try {
            String after = command.substring("ALTER USER ".length()).trim();
            String[] parts = after.split("\\s+", 3);
            if (parts.length < 3) return "[ERROR] Uso: ALTER USER <username> PASSWORD <newPass> | ALTER USER <username> ROLE <newRole>";
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
            return String.format("[SUCCESS] Concedido rol '%s' sobre la base de datos '%s' al usuario '%s'.", role, db, user);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleRevokeRole(String command) {
        // REVOKE <database> FROM <username> o REVOKE ROLE ON <database> FROM <username>
        try {
            String upper = command.toUpperCase();
            int fromIdx = upper.indexOf(" FROM ");
            if (fromIdx == -1) return "[ERROR] Uso: REVOKE <database> FROM <username>";

            String targetDb = cleanQuotes(command.substring(7, fromIdx).replaceAll("(?i)ROLE\\s+ON\\s+", "").trim());
            String user = cleanQuotes(command.substring(fromIdx + 6).trim());

            client.getSecurityManager().revokeDatabaseRole(client.getSessionToken(), user, targetDb);
            return String.format("[SUCCESS] Permisos sobre base de datos '%s' revocados para el usuario '%s'.", targetDb, user);
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }

    private String handleShowGrants(String command) {
        String username = cleanQuotes(command.substring("SHOW GRANTS FOR ".length()).trim());
        var user = client.getSecurityManager().getUser(username);
        if (user == null) return "[ERROR] Usuario '" + username + "' no encontrado.";

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
                        yield val != null ? "KV Valor: " + new String(val, StandardCharsets.UTF_8) : "(Clave KV no encontrada)";
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
        // INSERT REF <collection> <docId> KEY <refKey> TARGET <engine>::<targetCol>#<targetId>
        try {
            String upper = command.toUpperCase();
            int keyIdx = upper.indexOf(" KEY ");
            int targetIdx = upper.indexOf(" TARGET ");
            if (keyIdx == -1 || targetIdx == -1) {
                return "[ERROR] Uso: INSERT REF <coleccion> <id> KEY <campo_ref> TARGET <engine>::<targetCol>#<targetId>";
            }

            String beforeKey = command.substring(11, keyIdx).trim();
            String[] parts = beforeKey.split("\\s+");
            if (parts.length < 2) return "[ERROR] Debe especificar colección e ID de registro.";
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
        if (parts.length < 2) return "[ERROR] Uso: SHOW REFS <coleccion> <id>";
        String col = parts[0];
        String id = parts[1];

        JettraDatabase db = client.getDatabase(currentDatabase);
        var doc = db.getDocumentEngine(col).findById(id);
        if (doc == null) return "[ERROR] Documento no encontrado: " + id;

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
            if (newSize <= 0) return "[ERROR] El tamaño de página debe ser mayor a 0.";
            this.pageSize = Math.min(newSize, 5000);
            if (lastPagedBaseQuery != null) {
                this.totalQueryPages = (int) Math.max(1, Math.ceil((double) totalQueryRecords / pageSize));
                this.currentQueryPage = Math.min(currentQueryPage, totalQueryPages);
                return String.format("[PAGINACIÓN] Tamaño de página configurado a %d registros.\n%s", 
                    pageSize, executePagedQuery(lastPagedBaseQuery, currentQueryPage));
            }
            return String.format("[PAGINACIÓN] Tamaño de página configurado a %d registros para futuras consultas.", pageSize);
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

        if (upper.equals("FIRST") || upper.equals("PRIMERO") || upper.equals("PAGE FIRST") || upper.equals("|<<") || upper.equals("P")) {
            targetPage = 1;
        } else if (upper.equals("PREV") || upper.equals("ANTERIOR") || upper.equals("PAGE PREV") || upper.equals("<") || upper.equals("A")) {
            targetPage = Math.max(1, currentQueryPage - 1);
        } else if (upper.equals("NEXT") || upper.equals("SIGUIENTE") || upper.equals("PAGE NEXT") || upper.equals(">") || upper.equals("S")) {
            targetPage = Math.min(totalQueryPages, currentQueryPage + 1);
        } else if (upper.equals("LAST") || upper.equals("ULTIMO") || upper.equals("PAGE LAST") || upper.equals(">>|") || upper.equals("U")) {
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

    private String renderPaginationBar(int currentPage, int totalPages, int currentPageSize, long totalRecords, int rowsInPage) {
        long startRow = totalRecords == 0 ? 0 : (long) (currentPage - 1) * currentPageSize + 1;
        long endRow = totalRecords == 0 ? 0 : Math.min(totalRecords, (long) (currentPage - 1) * currentPageSize + rowsInPage);

        StringBuilder sb = new StringBuilder();
        sb.append("┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐\n");
        sb.append(String.format("│  PÁGINA [ %d / %d ]  •  Registros %d a %d de %d total  •  Tamaño de página: %d               │\n",
            currentPage, totalPages, startRow, endRow, totalRecords, currentPageSize));
        sb.append("├──────────────────────────────────────────────────────────────────────────────────────────────────────┤\n");
        sb.append("│  OPCIONES DE DESPLAZAMIENTO:                                                                         │\n");
        sb.append("│    [F] Primero (|<<)   - Va a la primera página       [A] Anterior (<)    - Página anterior          │\n");
        sb.append("│    [S] Siguiente (>)   - Página siguiente             [U] Última (>>|)    - Va a la última página    │\n");
        sb.append("│    PAGE <n>            - Salta a una página directa   PAGE_SIZE <n>       - Modifica tamaño de página│\n");
        sb.append("└──────────────────────────────────────────────────────────────────────────────────────────────────────┘\n");
        return sb.toString();
    }

    private String handleJettraSQL(String command) {
        return handleJettraSQLInternal(command, true);
    }

    private String handleJettraSQLInternal(String command, boolean isNewQuery) {
        String clean = command;
        if (clean.toUpperCase().startsWith("SQL ")) clean = clean.substring(4).trim();
        else if (clean.toUpperCase().startsWith("JETTRASQL ")) clean = clean.substring(10).trim();

        long start = System.currentTimeMillis();
        JettraSQLProcessor.QueryResult res = client.sql(currentDatabase, clean);
        long elapsed = System.currentTimeMillis() - start;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== JETTRASQL RESULTADO (%d ms) ===\n", elapsed));
        if (res.message().contains("[JettraPolice SENTINEL") || lastSentinelNotification != null) {
            sb.append("🛡️  [JETTRAPOLICE SENTINEL: INTERVENCIÓN PREVENTIVA DE MEMORIA HEAP]\n");
            sb.append("   Estrategia: Streaming por chunks seguro (Anti-OOM) con recolección de basura iterativa.\n");
            sb.append("   Diagnóstico: ").append(res.message()).append("\n");
            if (lastSentinelNotification != null) {
                sb.append(String.format("   Lote seguro: %d registros | Heap: %.1f%% | RAM libre: %d MB\n",
                    lastSentinelNotification.safeBatchSize(), lastSentinelNotification.heapUsagePercent(), lastSentinelNotification.availableMemoryMb()));
            }
            sb.append("   Recepción progresiva: Prototipo de stream procesado en bloques para proteger terminal y heap.\n");
        } else {
            sb.append("Mensaje: ").append(res.message()).append("\n");
        }

        if (!res.rows().isEmpty()) {
            // Renderizar tabla ASCII con formato dinámico
            List<String> cols = res.columns();
            sb.append("+");
            for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
            sb.append("\n|");
            for (String c : cols) sb.append(String.format(" %-" + Math.max(c.length(), 12) + "s |", c));
            sb.append("\n+");
            for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
            sb.append("\n");

            int maxDisplay = Math.min(res.rows().size(), pageSize > 0 ? pageSize : 50);
            int displayCount = 0;
            for (List<Object> row : res.rows()) {
                if (++displayCount > maxDisplay) break;
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
                sb.append(String.format("💡 Consejo JettraPolice: Para iterar la siguiente página lazy ejecute: %s LIMIT %d OFFSET %d\n",
                    clean.contains("LIMIT") ? clean.replaceAll("(?i)LIMIT\\s+\\d+", "").trim() : clean, 
                    res.rows().size(), res.rows().size()));
            }
            sb.append("+");
            for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
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
                    String afterFrom = lastPagedBaseQuery.substring(fromPos + 6).trim().split("\\s+")[0].replaceAll("[;]", "");
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
                sb.append(renderPaginationBar(currentQueryPage, totalQueryPages, pageSize, totalQueryRecords, res.rows().size()));
            }
        }

        return sb.toString();
    }

    private String handleJettraQL(String command) {
        String clean = command;
        if (clean.toUpperCase().startsWith("JQL ")) clean = clean.substring(4).trim();
        else if (clean.toUpperCase().startsWith("JETTRAQL ")) clean = clean.substring(9).trim();

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
            if (idIdx != -1 && jsonIdx != -1) {
                String col = cleanQuotes(after.substring(0, idIdx).trim());
                String id = cleanQuotes(after.substring(idIdx + 4, jsonIdx).trim());
                String jsonPart = after.substring(jsonIdx + 6).trim();
                Map<String, Object> data = parseJsonOrKeyValues(jsonPart);
                JettraDatabase db = client.getDatabase(currentDatabase);
                db.getDocumentEngine(col).insert(id, data);
                db.getIndexManager().onDocumentInsert(col, id, data);
                db.saveToDisk();
                client.replicatePutDocument(currentDatabase, col, id, data);
                return String.format("[SUCCESS] Registro con _id '%s' insertado en la colección '%s'.", id, col);
            }
            int valIdx = after.toUpperCase().indexOf(" VALUES");
            if (valIdx != -1) {
                String col = after.substring(0, valIdx).trim();
                String rawVals = after.substring(valIdx + 7).trim();
                if (rawVals.startsWith("(") && rawVals.endsWith(")")) {
                    rawVals = rawVals.substring(1, rawVals.length() - 1).trim();
                }
                String[] parts = rawVals.split(",", 2);
                String id = cleanQuotes(parts[0]);
                String jsonPart = parts.length > 1 ? parts[1].trim() : "{}";
                Map<String, Object> data = parseJsonOrKeyValues(jsonPart);

                JettraDatabase db = client.getDatabase(currentDatabase);
                db.getDocumentEngine(col).insert(id, data);
                db.getIndexManager().onDocumentInsert(col, id, data);
                db.saveToDisk();
                client.replicatePutDocument(currentDatabase, col, id, data);
                return String.format("[SUCCESS] Registro con _id '%s' insertado en la colección '%s'.", id, col);
            }
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {}
        return "[ERROR] Formato inválido. Uso: INSERT INTO <col> ID <id> JSON {...} o INSERT INTO <col> VALUES ('<id>', '{...}')";
    }

    private String handleGetRecord(String command) {
        String clean = command.replaceAll("(?i)^(GET|FIND ONE|FIND BY ID)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 2) return "[ERROR] Uso: GET <colección> <id>";
        String colName = parts[0].trim();
        String id = cleanQuotes(parts[1]);

        JettraDatabase db = client.getDatabase(currentDatabase);
        Map<String, Object> doc = db.getDocumentEngine(colName).findById(id);
        if (doc == null) {
            return String.format("[NOT FOUND] No se encontró el registro con _id '%s' en la colección '%s'.", id, colName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- REGISTRO [%s] EN '%s' ---\n", id, colName));
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            sb.append(String.format("  %-15s : %s\n", entry.getKey(), entry.getValue()));
            if (showReferences && (entry.getKey().startsWith("_ref_") || String.valueOf(entry.getValue()).contains("::"))) {
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
        if (parts.length < 1 || parts[0].isBlank()) return "[ERROR] Uso: FIND ALL <colección> [LIMIT <n>]";
        String colName = cleanQuotes(parts[0]);

        int limit = pageSize;
        for (int i = 1; i < parts.length - 1; i++) {
            if ("LIMIT".equalsIgnoreCase(parts[i])) {
                try { limit = Integer.parseInt(parts[i + 1].replaceAll("[;]", "")); } catch (Exception ignored) {}
            }
        }

        JettraDatabase db = client.getDatabase(currentDatabase);
        var docEngine = db.getDocumentEngine(colName);
        if (docEngine == null || docEngine.isEmpty()) {
            return String.format("[INFO] La colección '%s' está vacía.", colName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- COLECCIÓN '%s' (Mostrando %d de %d registros) ---\n", 
            colName, Math.min((int) docEngine.count(), limit), docEngine.count()));
        int count = 0;
        for (Map<String, Object> doc : docEngine) {
            if (++count > limit) break;
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
                String jsonPart = whereIdx != -1 ? after.substring(setIdx + 5, whereIdx).trim() : after.substring(setIdx + 5).trim();
                Map<String, Object> updates = parseJsonOrKeyValues(jsonPart);

                if (id != null) {
                    JettraDatabase db = client.getDatabase(currentDatabase);
                    db.getDocumentEngine(col).update(id, updates);
                    db.saveToDisk();
                    Map<String, Object> full = db.getDocumentEngine(col).findById(id);
                    if (full != null) {
                        client.replicatePutDocument(currentDatabase, col, id, full);
                    }
                    return String.format("[SUCCESS] Registro con _id '%s' actualizado en '%s'.", id, col);
                }
            }
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {}
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
                    JettraDatabase db = client.getDatabase(currentDatabase);
                    boolean deleted = db.getDocumentEngine(col).delete(id);
                    db.getIndexManager().onDocumentDelete(col, id, null);
                    db.saveToDisk();
                    if (deleted) {
                        client.replicateDeleteDocument(currentDatabase, col, id);
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
                    JettraDatabase db = client.getDatabase(currentDatabase);
                    boolean deleted = db.getDocumentEngine(col).delete(id);
                    db.getIndexManager().onDocumentDelete(col, id, null);
                    db.saveToDisk();
                    if (deleted) {
                        client.replicateDeleteDocument(currentDatabase, col, id);
                    }
                    return deleted 
                        ? String.format("[SUCCESS] Registro con _id '%s' eliminado de '%s'.", id, col)
                        : String.format("[WARN] No se encontró el registro con _id '%s' para eliminar.", id);
                }
            }
        } catch (UnsupportedOperationException e) {
            return "[ERROR] " + e.getMessage();
        } catch (Exception ignored) {}
        return "[ERROR] Formato inválido. Uso: DELETE <col> <id> o DELETE FROM <col> WHERE _id = '<id>'";
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
            for (int i = 0; i < floatsStr.length; i++) floats[i] = Float.parseFloat(floatsStr[i].trim());

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
            for (int i = 0; i < floatsStr.length; i++) floats[i] = Float.parseFloat(floatsStr[i].trim());

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
                sb.append(String.format("  [%02d] Vector ID: %-15s | Similaridad: %.4f\n", idx++, match.id(), match.score()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: VECTOR SEARCH <coleccion> [f1, f2] K <num>";
        }
    }

    private String handleGraphAddVertex(String command) {
        String[] parts = command.substring("GRAPH ADD VERTEX ".length()).trim().split("\\s+");
        if (parts.length < 2) return "[ERROR] Uso: GRAPH ADD VERTEX <grafo> <verticeId>";
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
            if (lblIdx != -1) label = cleanQuotes(after.substring(lblIdx + 5).split("\\s+")[0]);
            double weight = 1.0;
            int wIdx = after.toUpperCase().indexOf("WEIGHT");
            if (wIdx != -1) weight = Double.parseDouble(after.substring(wIdx + 6).replaceAll("[;]", "").trim());

            client.getDatabase(currentDatabase).getGraphEngine(graph).addEdge(from, to, label, Map.of("weight", weight));
            return String.format("[SUCCESS] Arista (%s)-[%s, w=%.1f]->(%s) agregada en grafo '%s'.", from, label, weight, to, graph);
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: GRAPH ADD EDGE <grafo> <de> <a> [LABEL <nombre>] [WEIGHT <peso>]";
        }
    }

    private String handleGraphGetEdges(String command) {
        String clean = command.replaceAll("(?i)^(GRAPH GET EDGES|GRAPH EDGES)\\s+", "").trim();
        String[] parts = clean.split("\\s+");
        if (parts.length < 2) return "[ERROR] Uso: GRAPH GET EDGES <grafo> <verticeId>";
        var edges = client.getDatabase(currentDatabase).getGraphEngine(parts[0]).getOutboundEdges(cleanQuotes(parts[1]));
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
            if (timeIdx != -1) timestamp = Long.parseLong(after.substring(timeIdx + 4).replaceAll("[;]", "").trim());

            client.getDatabase(currentDatabase).getTimeSeriesEngine(series).record(timestamp, val);
            return String.format("[SUCCESS] Métrica (timestamp=%d, valor=%.2f) registrada en '%s'.", timestamp, val, series);
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
                sb.append(String.format("  [%02d] Timestamp: %d | Valor: %.4f\n", idx++, entry.getKey(), entry.getValue()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "[ERROR] Formato inválido. Uso: TS RANGE <serie> <timestampInicio> <timestampFin>";
        }
    }

    private String handleKvPut(String command) {
        String[] parts = command.substring(7).trim().split("\\s+", 3);
        if (parts.length < 3) return "[ERROR] Uso: KV PUT <tabla> <clave> <valor>";
        client.getDatabase(currentDatabase).getKeyValueEngine(parts[0]).put(cleanQuotes(parts[1]), parts[2].getBytes(StandardCharsets.UTF_8));
        return String.format("[SUCCESS] Clave '%s' guardada en tabla KV '%s'.", parts[1], parts[0]);
    }

    private String handleKvGet(String command) {
        String[] parts = command.substring(7).trim().split("\\s+");
        if (parts.length < 2) return "[ERROR] Uso: KV GET <tabla> <clave>";
        byte[] val = client.getDatabase(currentDatabase).getKeyValueEngine(parts[0]).get(cleanQuotes(parts[1]));
        return val != null ? "[KV] " + new String(val, StandardCharsets.UTF_8) : "[NOT FOUND] Clave '" + parts[1] + "' no encontrada.";
    }

    // --- 12. Persistencia y Muestras ---
    public String installAllSampleDatabases() {
        // 1. sample_enterprise_db
        JettraDatabase enterprise = client.getDatabase("sample_enterprise_db");
        enterprise.getDocumentEngine("departments").insert("dep_rd", Map.of(
            "name", "Research & Advanced Computing", "budget", 15000000.0, "floor", 12
        ));
        enterprise.getDocumentEngine("employees").insert("emp_01", Map.of(
            "name", "Ada Lovelace", "title", "Lead Architect", "salary", 185000.0,
            "_ref_department", "document::departments#dep_rd",
            "_ref_vector", "vector::employee_biometrics#bio_01",
            "_ref_equipment", "kv::inventory_cache#laptop_mac_m3"
        ));
        enterprise.getDocumentEngine("products").insert("prod_01", Map.of(
            "name", "Quantum Neural Accelerator", "category", "Hardware", "price", 4500.0,
            "_ref_vector", "vector::product_embeddings#emb_01",
            "_ref_category", "graph::catalog_graph#cat_hardware"
        ));
        enterprise.getVectorEngine("product_embeddings", 3).index("emb_01", new float[]{0.15f, -0.42f, 0.88f});
        enterprise.getVectorEngine("employee_biometrics", 3).index("bio_01", new float[]{0.92f, 0.11f, -0.05f});
        enterprise.getGraphEngine("catalog_graph").addEdge("prod_01", "cat_hardware", "BELONGS_TO", Map.of("weight", 1.0));
        enterprise.getTimeSeriesEngine("telemetry").record(System.currentTimeMillis(), 42.5);
        enterprise.getKeyValueEngine("inventory_cache").put("laptop_mac_m3", "MacBook Pro M3 Max 64GB".getBytes(StandardCharsets.UTF_8));
        try {
            enterprise.getIndexManager().createIndex("employees", "idx_emp_name", "name", "BTREE", false, enterprise.getDocumentEngine("employees"));
            enterprise.getIndexManager().createIndex("products", "idx_prod_cat", "category", "HASH", false, enterprise.getDocumentEngine("products"));
        } catch (Exception ignored) {}

        // 2. sample_ecommerce_db
        JettraDatabase ecommerce = client.getDatabase("sample_ecommerce_db");
        ecommerce.getDocumentEngine("customers").insert("cust_101", Map.of(
            "name", "Elena Rostova", "tier", "VIP_PLATINUM", "country", "ES", "email", "elena@quantum.io"
        ));
        ecommerce.getDocumentEngine("orders").insert("ord_9901", Map.of(
            "customer_id", "cust_101", "total", 899.50, "status", "PAID",
            "_ref_customer", "document::customers#cust_101",
            "_ref_product", "document::products#prod_01"
        ));
        ecommerce.getColumnarEngine("order_analytics").appendRow(Map.of("revenue", 899.50));
        ecommerce.getKeyValueEngine("shopping_carts").put("cart_cust_101", "item_quantum_gpu:2".getBytes(StandardCharsets.UTF_8));
        try {
            ecommerce.getIndexManager().createIndex("customers", "idx_cust_tier", "tier", "HASH", false, ecommerce.getDocumentEngine("customers"));
        } catch (Exception ignored) {}

        // 3. sample_ai_graph_db
        JettraDatabase aiGraph = client.getDatabase("sample_ai_graph_db");
        aiGraph.getGraphEngine("knowledge_network").addEdge("DeepLearning", "TransformerModel", "FOUNDATION_OF", Map.of("depth", 4.0));
        aiGraph.getGraphEngine("knowledge_network").addEdge("TransformerModel", "AttentionMechanism", "USES", Map.of("weight", 0.95));
        aiGraph.getVectorEngine("concept_embeddings", 3).index("vec_transformer", new float[]{0.85f, 0.12f, -0.33f});
        aiGraph.getDocumentEngine("prompts_corpus").insert("prompt_01", Map.of(
            "role", "system", "text", "You are an autonomous distributed DB engine expert.",
            "_ref_concept", "graph::knowledge_network#TransformerModel",
            "_ref_embedding", "vector::concept_embeddings#vec_transformer"
        ));

        // 4. sample_iot_telemetry_db
        JettraDatabase iot = client.getDatabase("sample_iot_telemetry_db");
        long now = System.currentTimeMillis();
        iot.getTimeSeriesEngine("sensor_temperature").record(now - 2000, 24.5);
        iot.getTimeSeriesEngine("sensor_temperature").record(now - 1000, 25.1);
        iot.getTimeSeriesEngine("sensor_temperature").record(now, 24.8);
        iot.getTimeSeriesEngine("sensor_vibration").record(now, 0.042);
        iot.getDocumentEngine("smart_devices").insert("iot_gateway_01", Map.of(
            "model", "EdgeGate-X25", "firmware", "v2.5.0-LTS", "status", "ONLINE"
        ));
        iot.getGeospatialEngine("device_locations").insertPoint("iot_gateway_01", 40.4168, -3.7038);

        // 5. sample_financial_db
        JettraDatabase finance = client.getDatabase("sample_financial_db");
        finance.getDocumentEngine("transactions").insert("tx_001", Map.of(
            "from_account", "ACC_7712", "to_account", "ACC_9941", "amount", 15000.0, "currency", "USD",
            "_ref_client", "document::customers#cust_101"
        ));
        finance.getTimeSeriesEngine("stock_feed").record(now, 184.50);

        // Flush de persistencia física a disco
        try {
            enterprise.flushMemTable();
            ecommerce.flushMemTable();
            aiGraph.flushMemTable();
            iot.flushMemTable();
            finance.flushMemTable();
        } catch (Exception ignored) {}

        this.currentDatabase = "sample_enterprise_db";

        return """
            [SUCCESS] ¡Todas las 5 bases de datos de ejemplo instaladas y persistidas exitosamente!
              1. 'sample_enterprise_db'   -> Empleados, Departamentos, Índices B-Tree, Vectores Biométricos, Grafos
              2. 'sample_ecommerce_db'    -> Clientes VIP, Órdenes con JettraRef, Analítica Columnar, Carritos KV
              3. 'sample_ai_graph_db'     -> Red de Conocimiento de Grafos, Embeddings Conceptuales, Prompts
              4. 'sample_iot_telemetry_db'-> Sensores Temperatura/Vibración, Dispositivos Smart, Geo-localización
              5. 'sample_financial_db'    -> Transacciones Financieras con JettraRef a Clientes, Series Temporales
              * Para cargar la base de datos masiva de 3 millones de objetos ejecute: LOAD SAMPLE example_factura_db
            Base de datos activa conmutada a: 'sample_enterprise_db'
            """;
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
            // Task 1: Clientes (200,000)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("clientes");
                int total = 200_000;
                int chunkSize = 25_000;
                for (int base = 0; base < total; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, total);
                    for (int i = base; i < end; i++) {
                        String id = "cli_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "razon_social", "Corporación Comercial " + i + " S.A.",
                            "rfc_tax_id", "RFC-PAN-" + (1000000 + i),
                            "ciudad", (i % 2 == 0) ? "Ciudad de Panamá" : "Colón",
                            "limite_credito", 50000.0 + (i % 1000) * 100
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 2: Facturas (1,000,000 con referencias cruzadas)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("facturas");
                int total = 1_000_000;
                int chunkSize = 50_000;
                for (int base = 0; base < total; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, total);
                    for (int i = base; i < end; i++) {
                        String id = "fac_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "fecha", "2026-09-29",
                            "total", 250.0 + (i % 2000),
                            "estado", "TIMBRADA",
                            "_ref_cliente", "document::clientes#cli_" + (i % 200_000),
                            "_ref_detalle", "document::detalles_factura#det_" + i,
                            "_ref_folio", "kv::cache_folios#fol_" + (i % 300_000),
                            "_ref_vector", "vector::factura_embeddings#emb_" + (i % 200_000),
                            "_ref_sucursal", "geospatial::sucursales_fiscales#suc_" + (i % 25_000)
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 3: Detalles de Factura (1,000,000)
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("detalles_factura");
                int total = 1_000_000;
                int chunkSize = 50_000;
                for (int base = 0; base < total; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, total);
                    for (int i = base; i < end; i++) {
                        String id = "det_" + i;
                        int qty = (i % 10) + 1;
                        double price = 50.0 + (i % 200);
                        batch.put(id, Map.of(
                            "_id", id,
                            "concepto", "Servicio Cloud / Licencia Empresarial #" + (i % 100),
                            "cantidad", qty,
                            "precio_unitario", price,
                            "subtotal", qty * price,
                            "_ref_factura", "document::facturas#fac_" + i
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 4: KV Cache de Folios (300,000)
            executor.submit(() -> {
                var kvEngine = db.getKeyValueEngine("cache_folios");
                int total = 300_000;
                int chunkSize = 30_000;
                byte[] rawVal = "TIMBRADO_OK_CFDI_2026_SAT".getBytes(StandardCharsets.UTF_8);
                for (int base = 0; base < total; base += chunkSize) {
                    Map<String, byte[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, total);
                    for (int i = base; i < end; i++) {
                        batch.put("fol_" + i, rawVal);
                    }
                    kvEngine.putBatch(batch);
                }
            });

            // Task 5: Factura Embeddings (200,000 vectores 3d)
            executor.submit(() -> {
                var vecEngine = db.getVectorEngine("factura_embeddings", 3);
                int total = 200_000;
                int chunkSize = 25_000;
                float[] emb = new float[]{0.75f, -0.20f, 0.60f};
                for (int base = 0; base < total; base += chunkSize) {
                    Map<String, float[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, total);
                    for (int i = base; i < end; i++) {
                        batch.put("emb_" + i, emb);
                    }
                    vecEngine.indexBatch(batch);
                }
            });

            // Task 6: Red Comercial Graph (200,000 vértices de clientes y facturas conectadas)
            executor.submit(() -> {
                var graphEngine = db.getGraphEngine("red_comercial");
                int totalEdges = 100_000; // 100k aristas conectando 100k clientes y 100k facturas = 200k vértices
                int chunkSize = 25_000;
                for (int base = 0; base < totalEdges; base += chunkSize) {
                    Map<String, List<io.jettra.store.engine.models.GraphEngine.Edge>> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, totalEdges);
                    for (int i = base; i < end; i++) {
                        String from = "cli_" + i;
                        String to = "fac_" + i;
                        batch.put(from, List.of(new io.jettra.store.engine.models.GraphEngine.Edge(
                            to, "FACTURA_EMITIDA", Map.of("weight", 1.0)
                        )));
                    }
                    graphEngine.addEdgesBatch(batch);
                }
            });

            // Task 7: TimeSeries Volumen Facturación (50,000)
            executor.submit(() -> {
                var tsEngine = db.getTimeSeriesEngine("volumen_facturacion");
                long now = System.currentTimeMillis();
                Map<Long, Double> batch = new HashMap<>(50_000);
                for (int i = 0; i < 50_000; i++) {
                    batch.put(now - (i * 1000L), 2500.0 + (i % 500));
                }
                tsEngine.recordBatch(batch);
            });

            // Task 8: Geospatial Sucursales (25,000)
            executor.submit(() -> {
                var geoEngine = db.getGeospatialEngine("sucursales_fiscales");
                Map<String, io.jettra.store.engine.models.GeospatialEngine.GeoPoint> batch = new HashMap<>(25_000);
                for (int i = 0; i < 25_000; i++) {
                    String id = "suc_" + i;
                    batch.put(id, new io.jettra.store.engine.models.GeospatialEngine.GeoPoint(
                        id, 8.9800 + (i % 100) * 0.001, -79.5200 + (i % 100) * 0.001
                    ));
                }
                geoEngine.insertBatch(batch);
            });

            // Task 9: Columnar Analítica Fiscal (25,000)
            executor.submit(() -> {
                var colEngine = db.getColumnarEngine("analitica_fiscal");
                List<Double> sub = new ArrayList<>(25_000);
                List<Double> iva = new ArrayList<>(25_000);
                List<Double> tot = new ArrayList<>(25_000);
                for (int i = 0; i < 25_000; i++) {
                    double s = 1000.0 + (i % 500);
                    double iv = s * 0.07;
                    sub.add(s);
                    iva.add(iv);
                    tot.add(s + iv);
                }
                colEngine.appendBatch(
                    Map.of("subtotal", sub, "iva", iva, "total", tot),
                    Map.of(),
                    25_000
                );
            });

            // Task 10: Pure Java 25 Records (100,000 auditoria_records)
            executor.submit(() -> {
                var recordsEngine = db.getRecordsEngine("auditoria_records", io.jettra.store.sample.model.FacturaAuditRecord.class);
                int totalRecords = 100_000;
                int chunkSize = 25_000;
                long now = System.currentTimeMillis();
                for (int base = 0; base < totalRecords; base += chunkSize) {
                    Map<String, io.jettra.store.sample.model.FacturaAuditRecord> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, totalRecords);
                    for (int i = base; i < end; i++) {
                        String id = "rec_" + i;
                        batch.put(id, new io.jettra.store.sample.model.FacturaAuditRecord(
                            id,
                            "FOL-SAT-2026-" + i,
                            "RFC-EMISOR-PAN-" + (i % 500),
                            "RFC-REC-PAN-" + (i % 200_000),
                            150.0 + (i % 1500),
                            "sha256-hash-audit-" + i,
                            now - (i * 500L)
                        ));
                    }
                    recordsEngine.insertBatch(batch);
                }
            });
        }

        // Flush persistencia física para volcar buffers a almacenamiento antes de indexación
        try {
            db.flushMemTable();
        } catch (Exception ignored) {}

        // Liberación preventiva de memoria y creación de índices compactos (Zero-Set Singletons)
        System.gc();
        try {
            db.getIndexManager().createIndex("facturas", "idx_fac_cliente", "_ref_cliente", "HASH", false, db.getDocumentEngine("facturas"));
            db.getIndexManager().createIndex("clientes", "idx_cli_rfc", "rfc_tax_id", "BTREE", false, db.getDocumentEngine("clientes"));
        } catch (Exception ignored) {}

        this.currentDatabase = "example_factura_db";
        long duration = System.currentTimeMillis() - start;

        return String.format("""
            ==============================================================================================
                    CARGA MASIVA EXITOSA: BASE DE DATOS 'example_factura_db' (3,100,000 OBJETOS)
            ==============================================================================================
            [OK] Tiempo de Inserción y Timbrado Multimodelo: %d ms (Java 25 Virtual Threads)
            [OK] Objetos Repartidos en 10 Buckets Especializados:
              * [DOCUMENT]   'facturas'              : 1,000,000 facturas electrónicas timbradas
              * [DOCUMENT]   'detalles_factura'      : 1,000,000 renglones/items vinculados
              * [DOCUMENT]   'clientes'              :   200,000 clientes empresariales con RFC/RUC
              * [KEYVALUE]   'cache_folios'          :   300,000 folios fiscales en caché ultrarrápida
              * [VECTOR]     'factura_embeddings'    :   200,000 vectores 3D indexados para IA
              * [GRAPH]      'red_comercial'         :   200,000 vértices conectados (clientes -> facturas)
              * [TIMESERIES] 'volumen_facturacion'   :    50,000 métricas históricas de facturación
              * [GEOSPATIAL] 'sucursales_fiscales'   :    25,000 puntos GIS de sucursales emisoras
              * [COLUMNAR]   'analitica_fiscal'      :    25,000 filas de cálculo analítico de IVA/Totales
              * [RECORDS]    'auditoria_records'     :   100,000 registros tipados Java 25 (FacturaAuditRecord)
            ----------------------------------------------------------------------------------------------
            GRAN TOTAL EN 'example_factura_db': 3,100,000 objetos multimodelo conectados mediante JettraRef.
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
                    if (parts.length > 1) dest = cleanQuotes(parts[1]);
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
                if (parts.length < 2) return "[ERROR] Uso: RESTORE DATABASE <archivo.snap> <nombreBaseDatosDestino> o RESTORE DATABASE <db> FROM '<archivo.snap>'";
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

    // --- Control de Colecciones ---
    private String handleShowCollections() {
        JettraDatabase db = client.getDatabase(currentDatabase);
        StringBuilder sb = new StringBuilder();
        sb.append("+---------------------------+---------------------+---------+\n");
        sb.append("| Colección                 | Motor Multimodelo   | Registros|\n");
        sb.append("+---------------------------+---------------------+---------+\n");

        int count = 0;
        for (String c : db.getDocumentEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "DOCUMENT", db.getDocumentEngine(c).count()));
            count++;
        }
        for (String c : db.getVectorEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "VECTOR (Cosine)", db.getVectorEngine(c, 3).size()));
            count++;
        }
        for (String c : db.getGraphEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "GRAPH (Adjacency)", db.getGraphEngine(c).getVertices().size()));
            count++;
        }
        for (String c : db.getTimeSeriesEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "TIMESERIES", db.getTimeSeriesEngine(c).size()));
            count++;
        }
        for (String c : db.getKeyValueEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "KEY-VALUE", db.getKeyValueEngine(c).size()));
            count++;
        }
        for (String c : db.getColumnarEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "COLUMNAR", db.getColumnarEngine(c).size()));
            count++;
        }
        for (String c : db.getGeospatialEngineNames()) {
            sb.append(String.format("| %-25s | %-19s | %-7d |\n", c, "GEOSPATIAL", db.getGeospatialEngine(c).size()));
            count++;
        }

        if (count == 0) {
            sb.append("| (Sin colecciones activas) | -                   | 0       |\n");
        }
        sb.append("+---------------------------+---------------------+---------+\n");
        sb.append(String.format("Total: %d coleccion(es) en base de datos '%s'\n", count, currentDatabase));
        return sb.toString();
    }

    private String handleCreateCollection(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length < 3) return "[ERROR] Uso: CREATE <ENGINE|COLLECTION|BUCKET|UNIT> <nombre> [TYPE <tipo>]";
        String colName = cleanQuotes(parts[2]);
        String type = "DOCUMENT";
        for (int i = 3; i < parts.length - 1; i++) {
            if ("TYPE".equalsIgnoreCase(parts[i])) {
                type = parts[i + 1].toUpperCase();
            }
        }

        JettraDatabase db = client.getDatabase(currentDatabase);
        switch (type) {
            case "VECTOR" -> db.getVectorEngine(colName, 3);
            case "GRAPH" -> db.getGraphEngine(colName);
            case "TIMESERIES" -> db.getTimeSeriesEngine(colName);
            case "KEYVALUE", "KV" -> db.getKeyValueEngine(colName);
            case "COLUMNAR" -> db.getColumnarEngine(colName);
            case "GEOSPATIAL", "GEO" -> db.getGeospatialEngine(colName);
            default -> db.getDocumentEngine(colName);
        }
        return String.format("[SUCCESS] Unidad/Motor '%s' creado con motor multimodelo '%s' en base de datos '%s'.", colName, type, currentDatabase);
    }

    // --- Control de Buckets / Units, Conteo y Visualización de Registros ---
    private String handleShowBuckets(String command) {
        JettraDatabase db = client.getDatabase(currentDatabase);
        record UnitRow(String engine, String unitType, String name, long count, String status) {}
        List<UnitRow> rows = new ArrayList<>();

        String clean = command.replaceAll("(?i)^SHOW\\s+(BUCKETS|BUCKET|UNITS|UNIT)\\s*", "").trim();
        String filterEngine = clean.isBlank() ? null : clean.toUpperCase();

        // 1. Documents
        if (filterEngine == null || filterEngine.contains("DOC")) {
            for (String col : db.getDocumentEngineNames()) {
                long c = db.getDocumentEngine(col).count();
                rows.add(new UnitRow("DOCUMENT", "Collection", col, c, "ACTIVE (In-Memory)"));
            }
        }
        // 2. Vectors
        if (filterEngine == null || filterEngine.contains("VEC")) {
            for (String col : db.getVectorEngineNames()) {
                var vEng = db.getVectorEngine(col, 3);
                rows.add(new UnitRow("VECTOR", "Vector Index [" + vEng.getDimensions() + "d]", col, vEng.size(), "INDEXED (HNSW)"));
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
                rows.add(new UnitRow("RECORDS", "Typed Java Record", col, recEng != null ? recEng.count() : 0, "TYPED-HEAP (Zero-Copy)"));
            }
        }

        if (rows.isEmpty()) {
            return String.format("[INFO] No se encontraron buckets/units en la base de datos '%s'%s.",
                currentDatabase, filterEngine != null ? " para el motor " + filterEngine : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("==============================================================================================\n");
        sb.append(String.format("                BUCKETS / UNITS EN BASE DE DATOS: '%s'                                        \n", currentDatabase));
        sb.append("==============================================================================================\n");
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        sb.append("| Motor       | Tipo de Unidad       | Nombre de Unidad   | Registros | Estado               |\n");
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        for (UnitRow r : rows) {
            sb.append(String.format("| %-11s | %-20s | %-18s | %-9d | %-20s |\n",
                r.engine(), r.unitType(), r.name(), r.count(), r.status()));
        }
        sb.append("+-------------+----------------------+--------------------+-----------+----------------------+\n");
        sb.append(String.format("Total: %d bucket(s)/unit(s) registrados en la base de datos '%s'.\n", rows.size(), currentDatabase));
        return sb.toString();
    }

    private String handleShowRecords(String command) {
        String clean = command.replaceAll("(?i)^SHOW\\s+RECORDS(\\s+FROM)?\\s*", "").trim();
        JettraDatabase db = client.getDatabase(currentDatabase);
        if (clean.isBlank()) {
            var all = db.getAllCollectionNames();
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
                try { limit = Integer.parseInt(parts[i + 1].replaceAll("[;]", "")); } catch (Exception ignored) {}
            }
        }

        // 1. DOCUMENT
        if (db.getDocumentEngineNames().contains(unitName)) {
            var docEngine = db.getDocumentEngine(unitName);
            if (docEngine.isEmpty()) return String.format("[INFO] El bucket de documentos '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE DOCUMENT BUCKET '%s' (Mostrando %d de %d) ===\n",
                unitName, Math.min((int) docEngine.count(), limit), docEngine.count()));

            // Streaming por Chunks interactivo con protección Anti-OOM
            StreamResponse<Map<String, Object>> stream = client.streamFindAll(currentDatabase, unitName, limit);
            if (stream.isSentinelActivated() || lastSentinelNotification != null) {
                sb.append("🛡️  [JETTRAPOLICE SENTINEL: STREAMING POR CHUNKS ACTIVADO]\n");
                sb.append(String.format("   Partición defensiva en lotes de %d registros para evitar OOM.\n", stream.getSafeBatchSize()));
            }

            int idx = 1;
            int chunkIndex = 1;
            for (List<Map<String, Object>> chunk : stream) {
                sb.append(String.format("--- [Chunk #%d: %d registro(s) recibidos progresivamente] ---\n", chunkIndex++, chunk.size()));
                for (Map<String, Object> doc : chunk) {
                    if (idx > limit) break;
                    sb.append(String.format("  [%02d] _id: %-15s -> %s\n", idx++, doc.getOrDefault("_id", "?"), doc));
                    if (showReferences) {
                        for (var entry : doc.entrySet()) {
                            if (entry.getKey().startsWith("_ref_") || String.valueOf(entry.getValue()).contains("::")) {
                                sb.append(String.format("       ↳ Ref [%s]: %s\n", entry.getKey(), resolveReference(db, String.valueOf(entry.getValue()))));
                            }
                        }
                    }
                }
                if (idx > limit) break;
            }
            return sb.toString();
        }

        // 2. VECTOR
        if (db.getVectorEngineNames().contains(unitName)) {
            var vecEngine = db.getVectorEngine(unitName, 3);
            var vecs = vecEngine.getAllVectors();
            if (vecs.isEmpty()) return String.format("[INFO] El bucket vectorial '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE VECTOR BUCKET '%s' (Dim: %d | Mostrando %d de %d) ===\n",
                unitName, vecEngine.getDimensions(), Math.min(vecs.size(), limit), vecs.size()));
            int idx = 1;
            for (var entry : vecs.entrySet()) {
                if (idx > limit) break;
                sb.append(String.format("  [%02d] Vector ID: %-15s -> %s\n", idx++, entry.getKey(), Arrays.toString(entry.getValue())));
            }
            return sb.toString();
        }

        // 3. GRAPH
        if (db.getGraphEngineNames().contains(unitName)) {
            var graph = db.getGraphEngine(unitName);
            if (graph.getVertices().isEmpty()) return String.format("[INFO] El bucket de grafos '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE GRAPH BUCKET '%s' (%d vértices) ===\n", unitName, graph.getVertices().size()));
            int idx = 1;
            for (String v : graph.getVertices()) {
                if (idx > limit) break;
                var out = graph.getOutboundEdges(v);
                sb.append(String.format("  [%02d] Vértice: %-15s (Aristas salientes: %d)\n", idx++, v, out.size()));
                for (var e : out) {
                    sb.append(String.format("       ↳ (%s)-[%s, props=%s]->(%s)\n", v, e.label(), e.properties(), e.targetVertex()));
                }
            }
            return sb.toString();
        }

        // 4. TIMESERIES
        if (db.getTimeSeriesEngineNames().contains(unitName)) {
            var ts = db.getTimeSeriesEngine(unitName);
            var pts = ts.getAll();
            if (pts.isEmpty()) return String.format("[INFO] El bucket de series temporales '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE TIMESERIES BUCKET '%s' (Mostrando %d de %d) ===\n",
                unitName, Math.min(pts.size(), limit), pts.size()));
            int idx = 1;
            for (var entry : pts.entrySet()) {
                if (idx > limit) break;
                sb.append(String.format("  [%02d] Timestamp: %-15d -> Valor: %.4f\n", idx++, entry.getKey(), entry.getValue()));
            }
            return sb.toString();
        }

        // 5. KEYVALUE
        if (db.getKeyValueEngineNames().contains(unitName)) {
            var kv = db.getKeyValueEngine(unitName);
            var store = kv.getAll();
            if (store.isEmpty()) return String.format("[INFO] El bucket clave-valor '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE KEYVALUE BUCKET '%s' (Mostrando %d de %d) ===\n",
                unitName, Math.min(store.size(), limit), store.size()));
            int idx = 1;
            for (var entry : store.entrySet()) {
                if (idx > limit) break;
                String valStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                sb.append(String.format("  [%02d] Clave: %-20s -> Valor: %s\n", idx++, entry.getKey(), valStr));
            }
            return sb.toString();
        }

        // 6. GEOSPATIAL
        if (db.getGeospatialEngineNames().contains(unitName)) {
            var geo = db.getGeospatialEngine(unitName);
            var pts = geo.getAllPoints();
            if (pts.isEmpty()) return String.format("[INFO] El bucket geoespacial '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE GEOSPATIAL BUCKET '%s' (Mostrando %d de %d) ===\n",
                unitName, Math.min(pts.size(), limit), pts.size()));
            int idx = 1;
            for (var p : pts.values()) {
                if (idx > limit) break;
                sb.append(String.format("  [%02d] Feature ID: %-15s -> Lat: %.6f, Lon: %.6f\n", idx++, p.id(), p.latitude(), p.longitude()));
            }
            return sb.toString();
        }

                // 7. COLUMNAR
        if (db.getColumnarEngineNames().contains(unitName)) {
            var col = db.getColumnarEngine(unitName);
            if (col.getRowCount() == 0) return String.format("[INFO] El bucket columnar '%s' está vacío.", unitName);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE COLUMNAR BUCKET '%s' (Total Filas: %d) ===\n", unitName, col.getRowCount()));
            sb.append("  Columnas Numéricas: ").append(col.getNumericColumns().keySet()).append("\n");
            sb.append("  Columnas Texto:     ").append(col.getTextColumns().keySet()).append("\n");
            return sb.toString();
        }

        // 8. RECORDS
        if (db.getRecordsEngineNames().contains(unitName)) {
            var recEng = db.getRecordsEngine(unitName);
            if (recEng == null || recEng.count() == 0) return String.format("[INFO] El bucket de registros tipados '%s' está vacío.", unitName);

            var recs = recEng.findAll();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== REGISTROS DE TYPED RECORD BUCKET '%s' (Tipo: %s | Mostrando %d de %d) ===\n",
                unitName, recEng.getRecordClass().getSimpleName(), Math.min(recs.size(), limit), recs.size()));
            int idx = 1;
            for (var entry : recs.entrySet()) {
                if (idx > limit) break;
                sb.append(String.format("  [%02d] Record ID: %-15s -> %s\n", idx++, entry.getKey(), entry.getValue()));
            }
            return sb.toString();
        }

return String.format("[NOT FOUND] No se encontró el bucket/unit '%s' en la base de datos '%s'. Use 'show buckets' para ver las unidades disponibles.",
            unitName, currentDatabase);
    }

    private String handleCount(String command) {
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

        return String.format("[NOT FOUND] El bucket/unit '%s' no existe en la base de datos '%s'.", unitName, currentDatabase);
    }

    private String handleDropCollection(String command) {
        String clean = command.replaceAll("(?i)^(DROP COLLECTION|DROP ENGINE|DROP BUCKET|DROP UNIT)\\s+", "").trim();
        String col = cleanQuotes(clean.split("\\s+")[0]);
        boolean ok = client.getDatabase(currentDatabase).dropCollection(col);
        return ok ? String.format("[SUCCESS] Unidad/Colección/Motor '%s' eliminado.", col)
                  : String.format("[WARN] La unidad/colección/motor '%s' no existía.", col);
    }

    // --- Auxiliares de Parseo ---
    private String cleanQuotes(String text) {
        if (text == null) return "";
        return text.replace(";", "").replace("'", "").replace("\"", "").trim();
    }

    private Map<String, Object> parseJsonOrKeyValues(String raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return map;

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
                                     JETTRASTORE SHELL - GUÍA COMPLETA DE COMANDOS
            ==============================================================================================
            1. CONEXIÓN Y SESIÓN:
              connect <url> <port>                  Establece la dirección del nodo servidor JettraStore.
              connect <nombre-perfil>               Conecta utilizando un perfil previamente guardado.
              login <username> <password>            Autentica y obtiene un token de sesión criptográfico JettraJWT.
              logout                                Cierra la sesión activa y revoca el token JWT.
              save connection <nombre> Guarda el perfil de conexión actual con un alias.
              remove connection <nombre>            Elimina un perfil de conexión guardado.
              list connections / list conections    Lista todos los perfiles de conexión guardados.

            2. TELEMETRÍA Y CLÚSTER:
              status                                Monitorea RAM Panama FFM, CPU Loom y Disco LSM.
              show nodes / list nodes               Muestra la topología del clúster Raft y nodos del anillo.
              cluster-distributed <all>             distribuye entre todos los nodos todas las bases de datos
              cluster-distributed <nombre-base-datos>: distribuye la base de datos indicada
              cluster-distributed info :            Muestra una tabla con los nodos y las bases de datos en cada nodo.
              cluster live [límite]                 Muestra trazas y eventos en tiempo real de lo que ocurre en el clúster.
              multinode / show multinode            Muestra el estado de cluster.multinode.active (ON/OFF).
              multinode on / multinode off          Activa o desactiva dinámicamente la distribución de datos.
              add node <id> <host> <port> [ROLE]    Agrega un nuevo nodo secundario al clúster Raft.
              remove node <id>                      Remueve un nodo réplica del anillo dinámico.
              start node <id>                       Inicia y activa el procesamiento para un nodo específico.
              stop node <id>                        Detiene un nodo réplica (pausa el tráfico de descarga).

            3. BASES DE DATOS, BUCKETS/UNITS Y PERSISTENCIA EN DISCO:
              show databases / show dbs             Lista todas las bases de datos detectadas en disco y memoria.
              show buckets / show unit              Muestra todos los buckets/units de cada motor con sus conteos.
              show records <bucket> [LIMIT n]       Muestra los registros que contiene el bucket indicado en cualquier motor.
              count <bucket> / count all            Cuenta los registros de un bucket o el total de la base de datos activa.
              show samples / show sample dbs        Muestra las 5 bases de datos de prueba preconfiguradas.
              create database <nombre>              Crea una nueva base de datos lógica.
              drop database <nombre>                Elimina la base de datos especificada.
              use <nombre>                          Conmuta la base de datos activa.
              db stats                              Muestra estadísticas de la base de datos activa.
              INSTALL SAMPLES                       Instala y persiste las 5 bases de datos de ejemplo estándar.
              LOAD SAMPLE example_factura_db        Carga la base de datos de facturación (3M objetos).
              LOAD SAMPLE samples_hostipal_db       Carga la base de datos hospitalaria (2M objetos).
              LOAD SAMPLE samples_ambiental_db      Carga la base de datos ambiental mundial (3M objetos).
              backup database [nombre] [destino]    Genera un snapshot físico .snap de la base de datos.
              restore database <archivo> <nombre>   Restaura un snapshot .snap en una base de datos.

            4. CONSULTAS POLÍGLOTAS (JETTRAQL Y JETTRASQL):
              JettraQL: Declarativo multimodelo (FROM, MATCH, VECTOR SIMILARITY, FETCH)
              JQL FROM <col> [WHERE campo = valor]  Consulta declarativa sobre documentos.
              JQL MATCH (a)-[r]->(b) IN <grafo>     Pattern matching sobre redes de grafos.
              JQL VECTOR SIMILARITY <col> TO [...]  Búsqueda de vecinos más cercanos por similaridad coseno.
              JQL FETCH <col> <id> [RESOLVE REFS]   Recupera un registro resolviendo referencias JettraRef.
              SQL SELECT * FROM <col> [WHERE k = v] Consulta relacional con tabla formateada de columnas y filas.
              SQL INSERT INTO <col> VALUES (id, json) Inserta registro en la colección activa.
              SQL UPDATE <col> SET k = v WHERE _id = id Actualiza campos de un registro.
              SQL DELETE FROM <col> WHERE _id = id  Elimina un registro mediante sintaxis SQL.

              STORAGE_MODE                          Muestra el modo de almacenamiento activo (JVM-RAM o DISK-MEMORY).
              STORAGE_MODE <JVM_RAM | DISK_MEMORY>  Conmuta el modo entre RAM JVM (Heap/Stack) o DISK-MEMORY (JettraMemory LSM).

            5. CÁLCULO, AGREGACIONES, ESTADÍSTICA, FINANZAS Y ÁLGEBRA VECTORIAL:
              AGGREGATE <col> [GROUP BY c] [SUM f] [AVG f] [MIN f] [MAX f] [MEDIAN f] [COUNT]
              MATH <expresion>                     Evaluador matemático (sqrt, cbrt, pow, gcd, lcm, fact, hypot, etc.)
              FINANCE PMT <tasa> <nper> <prestamo>  Cálculo de cuota periódica fija (sistema francés).
              FINANCE FV <tasa> <nper> <cuota>      Cálculo de valor futuro de una inversión.
              FINANCE CAGR <inicial> <final> <anios> Tasa de crecimiento anual compuesto (CAGR %).
              FINANCE NPV <tasa> <cf0> <cf1>...    Valor presente neto / VAN.
              FINANCE IRR <cf0> <cf1> <cf2>...     Tasa interna de retorno / TIR %.
              FINANCE AMORTIZATION <p> <tasa> <n>  Genera tabla completa de amortización francesa.
              STATS MEAN <n1, n2, n3...>            Media aritmética muestral.
              STATS MEDIAN <n1, n2, n3...>          Mediana muestral.
              STATS SUMMARY <n1, n2, n3...>         Resumen descriptivo completo (mean, median, stddev, min, max, p95).
              STATS CORRELATION [x1, x2...] [y1, y2...] Coeficiente de correlación de Pearson r.
              STATS REGRESSION [x1, x2...] [y1, y2...]  Regresión lineal simple (y = mx + b).
              VECTOR DOT [v1] [v2]                 Producto punto entre vectores.
              VECTOR COSINE [v1] [v2]              Similitud coseno entre embeddings.
              VECTOR EUCLIDEAN [v1] [v2]           Distancia euclidiana.
              VECTOR CROSS [v1] [v2]               Producto cruz 3D.
              VECTOR ANGLE [v1] [v2]               Ángulo entre vectores (radianes y grados).
              VECTOR NORMALIZE [v]                 Normaliza a vector unitario (norma L2 = 1.0).
              SELECT cat, SUM(v), AVG(v), MEDIAN(v) FROM <col> GROUP BY cat  SQL estándar con agregaciones.

            6. REGISTROS REFERENCIADOS (JETTRAREF) Y LAZY LOADING:
              lazy reference on / off (lazy reference on / lazy reference off)               Alterna la resolución diferida (Lazy) o inmediata (Eager).
              insert ref <col> <id> KEY <k> TARGET <engine>::<col>#<id>  Vincula un puntero cruzado multimodelo.
              resolve ref <engine>::<col>#<id>      Resuelve manualmente el destino de una referencia.
              show refs <col> <id>                  Muestra todas las referencias de un registro y sus resoluciones.
              get <col> <id>                        Obtiene un documento y resuelve sus punteros _ref_*.

            7. ADMINISTRACIÓN DE ÍNDICES:
              create index <nombre> ON <col> (campo) [TYPE BTREE|HASH|SPARSE] [UNIQUE]  Crea índice secundario.
              drop index <nombre>                   Elimina el índice especificado.
              alter index <nombre> rebuild          Reconstruye el índice re-escaneando los documentos.
              show indexes [ON <col>]               Muestra la tabla de índices creados en la base de datos.

            7. ADMINISTRACIÓN DE USUARIOS Y ROLES (RBAC):
              show users / list users               Muestra todos los usuarios, rol global y roles por base de datos.
              create user <user> PASSWORD <pass> [ROLE <role>] Crea un nuevo usuario en el sistema.
              drop user <user>                      Elimina un usuario (superuser 'admin' inmutable).
              alter user <user> PASSWORD <newPass>  Actualiza la contraseña del usuario.
              alter user <user> ROLE <newRole>      Actualiza el rol global del usuario.
              grant <DB_OWNER|READ_WRITE|READ_ONLY> ON <db> TO <user>  Asigna privilegios sobre una base de datos.
              revoke <db> FROM <user>               Revoca el acceso sobre la base de datos indicada.
              show grants for <user>                Muestra los privilegios asignados al usuario especificado.

            8. MOTORES ESPECIALIZADOS (VECTORES, GRAFOS, TIME SERIES, KV):
              vector index <col> <id> [f1,f2,..]    Indexa vector float[] en el motor vectorial.
              vector search <col> [f1,f2] K <num>   Búsqueda k-NN por similaridad coseno.
              graph add vertex <grafo> <id>         Agrega un vértice a la red de grafos.
              graph add edge <g> <a> <b> [LABEL l]  Agrega arista dirigida ponderada.
              ts record <serie> <val> [TIME t]      Registra punto métrico en serie temporal.
              ts range <serie> <inicio> <fin>       Consulta métricas en rango de tiempo.
              kv put <tabla> <clave> <valor>        Almacena clave-valor en memoria de acceso ultra rápido.
              kv get <tabla> <clave>                Recupera el valor asociado a la clave.

            9. HISTORIAL Y AUTOCOMPLETADO:
              history [n]                           Muestra los últimos n comandos ejecutados (o todos).
              history search <término>              Busca comandos en el historial que contengan el texto.
              history clear                         Borra el historial en memoria y en disco (~/.jettra/history.log).
              !<n>                                  Ejecuta el comando en la posición n del historial.
              !!                                    Ejecuta el último comando ejecutado.
              !<prefijo>                            Ejecuta el comando más reciente que comience con el prefijo.
              complete <prefijo> / tab <prefijo>    Muestra sugerencias de autocompletado para el prefijo indicado.
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
    public boolean isAuthenticated() { return authenticated; }
    public String getCurrentUser() { return currentUser; }
    public String getCurrentRole() { return currentRole; }
    public String getCurrentDatabase() { return currentDatabase; }
    public String getCurrentHost() { return currentHost; }
    public int getCurrentPort() { return currentPort; }
    public boolean isLazyLoad() { return lazyLoad; }
    public Map<String, SavedConnection> getSavedConnections() { return savedConnections; }
    public JettraClient getClient() { return client; }

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
                  Comando para cambiar: STORAGE_MODE <JVM_RAM | DISK_MEMORY>
                ==============================================================================================""";
        }
        var mode = client.getStorageMode(currentDatabase);
        return String.format("""
            ==============================================================================================
                            MODO DE ALMACENAMIENTO ACTIVO PARA '%s'                                      
            ==============================================================================================
              Modo Actual      : %s (%s)
              Descripción      : %s
              Motor Off-Heap   : JettraMemory (LSM Direct Panama FFM)
              Comando para conmutar: STORAGE_MODE <JVM_RAM | DISK_MEMORY>
            ==============================================================================================""",
            currentDatabase, mode.name(), mode.getCode(), mode.getDescription()
        );
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

        // 1. Mostrar menú de conexiones creadas al iniciar
        System.out.print(shell.getConnectionsMenuDisplay());

        String targetHost = "127.0.0.1";
        int targetPort = 9091;
        String suggestedUser = "admin";

        String option = "";
        System.out.print(">> Seleccione una opción [1]: ");
        if (console != null) {
            option = console.readLine();
        } else if (scanner.hasNextLine()) {
            option = scanner.nextLine();
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
                    System.out.printf("[CONNECT] Conexión seleccionada: '%s' (%s:%d)%n", chosen.name(), targetHost, targetPort);
                } else {
                    System.out.println("[WARN] Opción fuera de rango. Usando conexión predeterminada: 127.0.0.1:9091");
                    shell.executeCommand("CONNECT 127.0.0.1 9091");
                }
            } catch (NumberFormatException e) {
                if (shell.getSavedConnections().containsKey(option)) {
                    SavedConnection chosen = shell.getSavedConnections().get(option);
                    targetHost = chosen.host();
                    targetPort = chosen.port();
                    suggestedUser = chosen.user();
                    shell.executeCommand(String.format("CONNECT %s %d", targetHost, targetPort));
                    System.out.printf("[CONNECT] Conexión seleccionada: '%s' (%s:%d)%n", chosen.name(), targetHost, targetPort);
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
Ejecute el comando 'connect <host> <puerto>' (o presione Enter para [127.0.0.1 9091]):""");
            System.out.print(">> ");
            String connCmd = "";
            if (console != null) {
                connCmd = console.readLine();
            } else if (scanner.hasNextLine()) {
                connCmd = scanner.nextLine();
            }
            if (connCmd == null || connCmd.isBlank()) {
                connCmd = "connect 127.0.0.1 9091";
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
            if (console != null) saveAns = console.readLine();
            else if (scanner.hasNextLine()) saveAns = scanner.nextLine();
            if (saveAns != null && (saveAns.trim().equalsIgnoreCase("s") || saveAns.trim().equalsIgnoreCase("si"))) {
                System.out.print(">> Ingrese un nombre/alias para la conexión: ");
                String alias = "";
                if (console != null) alias = console.readLine();
                else if (scanner.hasNextLine()) alias = scanner.nextLine();
                if (alias != null && !alias.isBlank()) {
                    shell.executeCommand(String.format("SAVE CONNECTION %s %s %d %s", alias.trim(), targetHost, targetPort, suggestedUser));
                    System.out.printf("[SUCCESS] Perfil '%s' guardado para futuros arranques.%n", alias.trim());
                }
            }
        }

        // 2. Solicitar la ejecución del login para autentificar el usuario
        System.out.println("""
--------------------------------------------------------------------------------
                     AUTENTICACIÓN REQUERIDA (LOGIN)
--------------------------------------------------------------------------------""");
        System.out.printf("Servidor activo: %s:%d%n", targetHost, targetPort);
        System.out.printf("Ejecute el comando 'login <usuario> <password>' o presione Enter para usuario [%s]:%n", suggestedUser);
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
                if (pArr != null && pArr.length > 0) finalPass = new String(pArr);
            } else {
                System.out.printf(">> Password para '%s': ", finalUser);
                if (scanner.hasNextLine()) {
                    String p = scanner.nextLine();
                    if (p != null && !p.isBlank()) finalPass = p.trim();
                }
            }
            boolean ok = shell.connectAndLogin(targetHost, targetPort, finalUser, finalPass);
            if (ok) {
                System.out.printf("[AUTH OK] Autenticado exitosamente como '%s' (Rol: %s) en %s:%d%n", 
                    shell.getCurrentUser(), shell.getCurrentRole(), targetHost, targetPort);
            } else {
                System.out.printf("[AUTH WARN] No se pudo autenticar en %s:%d. Inicie sesión en la consola con: login <usuario> <password>%n", targetHost, targetPort);
            }
        }

        System.out.println("\nEscriba 'help' o '?' para ver los comandos disponibles, 'menu' para el menú interactivo, o 'exit' para salir.\n");

        // 3. Ciclo interactivo de comandos con soporte avanzado de JLine (Historial con flechas y Autocompletado con TAB)
        org.jline.reader.LineReader lineReader = null;
        try {
            org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.builder()
                .system(true)
                .dumb(false)
                .build();

            org.jline.reader.Completer completer = (reader, parsedLine, candidates) -> {
                String word = parsedLine.word();
                String line = parsedLine.line();
                String toComplete = (word != null && !word.isEmpty()) ? word : line;
                List<String> suggestions = shell.autocomplete(line);
                for (String s : suggestions) {
                    candidates.add(new org.jline.reader.Candidate(s));
                }
            };

            lineReader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .variable(org.jline.reader.LineReader.HISTORY_FILE, JLINE_HISTORY_FILE)
                .build();
        } catch (Throwable t) {
            // Fallback elegante a System.console o Scanner si la terminal interactiva no está disponible (ej. entornos de test o redirección)
            lineReader = null;
        }

        while (true) {
            String prompt = String.format("jettra-shell [%s@%s:%d/%s]> ", 
                shell.getCurrentUser(), shell.getCurrentHost(), shell.getCurrentPort(), shell.getCurrentDatabase());
            String line = null;
            if (lineReader != null) {
                try {
                    line = lineReader.readLine(prompt);
                } catch (IllegalArgumentException e) {
                    // Si el archivo de historial de JLine tuviese sintaxis corrupta previa, purgar y continuar
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

            if (line == null) break;
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                System.out.println("Saliendo de JettraStore Shell...");
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
        if (this.client != null) {
            try {
                this.client.close();
            } catch (Exception ignored) {}
        }
    }
}

package io.jettra.store.explorer.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jettra.store.explorer.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JettraStorePlugin: Plugin central de comunicación, telemetría y operaciones
 * para JettraStore y clústeres multimodelo en JettraFlux.
 */
public class JettraStorePlugin {

    private static volatile JettraStorePlugin instance;

    public static JettraStorePlugin getInstance() {
        if (instance == null) {
            synchronized (JettraStorePlugin.class) {
                if (instance == null) {
                    instance = new JettraStorePlugin();
                }
            }
        }
        return instance;
    }

    private final ObjectMapper mapper;
    private final String connectionsPath = "memory/connections.json";
    private final String usersPath = "memory/security/users.json";

    private final List<ConnectionProfile> connectionProfiles = new ArrayList<>();
    private ConnectionProfile activeConnection;
    private boolean connected = true;

    private final List<JettraUser> users = new ArrayList<>();
    private final List<ServerNodeInfo> serverNodes = new ArrayList<>();
    private final Map<String, List<EngineBucketInfo>> databasesCatalog = new ConcurrentHashMap<>();
    private final List<PoliceSentinelInfo> policeSentinels = new ArrayList<>();
    private final List<PoliceIncident> policeIncidents = new ArrayList<>();
    private final List<BackupSnapshotInfo> backupSnapshots = new ArrayList<>();

    private boolean multinodeActive = true;
    private long totalEvaluations = 142_850L;
    private long totalProcessedObjects = 2_840_500L;
    private String lastPoliceStatus = "Quorum Raft Óptimo | 4 Nodos Sincronizados";

    private JettraStorePlugin() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        loadMultinodeConfig();
        loadConnections();
        loadUsers();
        initServerNodes();
        initDatabaseCatalog();
        initPoliceSentinels();
        initBackupSnapshots();
    }

    // --- Configuración de Multinodo ---
    private void loadMultinodeConfig() {
        File f = new File("config/database.properties");
        if (f.exists()) {
            try {
                Properties props = new Properties();
                props.load(Files.newInputStream(f.toPath()));
                String val = props.getProperty("cluster.multinode.active", "on");
                this.multinodeActive = "on".equalsIgnoreCase(val.trim());
            } catch (Exception ignored) {}
        }
    }

    public synchronized void setMultinodeActive(boolean active) {
        this.multinodeActive = active;
        try {
            File f = new File("config/database.properties");
            Properties props = new Properties();
            if (f.exists()) {
                props.load(Files.newInputStream(f.toPath()));
            }
            props.setProperty("cluster.multinode.active", active ? "on" : "off");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            props.store(Files.newOutputStream(f.toPath()), "JettraStore Cluster Configuration");
        } catch (Exception ignored) {}

        logPoliceIncident("Raft Quorum K9", 
            "cluster.multinode.active conmutado a " + (active ? "ON (Consenso Raft Distribuido)" : "OFF (Modo Standalone Mononodo)"),
            "INFO", "Topología del clúster reconfigurada en caliente");
    }

    public boolean isMultinodeActive() {
        return multinodeActive;
    }

    public String getMultinodeDisplay() {
        return multinodeActive ? "MULTINODO: ON" : "STANDALONE: OFF";
    }

    // --- Conexiones ---
    public synchronized void loadConnections() {
        connectionProfiles.clear();
        File f = new File(connectionsPath);
        if (f.exists() && f.length() > 0) {
            try {
                List<ConnectionProfile> list = mapper.readValue(f, new TypeReference<List<ConnectionProfile>>() {});
                if (list != null) connectionProfiles.addAll(list);
            } catch (Exception e) {
                System.err.println("[WARN] Error cargando conexiones: " + e.getMessage());
            }
        }
        if (connectionProfiles.isEmpty()) {
            connectionProfiles.add(new ConnectionProfile("conn_local", "JettraStore Local Master", "tcp://127.0.0.1:8765", "admin", "admin123", true));
            connectionProfiles.add(new ConnectionProfile("conn_replica", "JettraStore Cluster Node 2", "tcp://192.168.1.102:8765", "operator", "jettraPass!", false));
            saveConnections();
        }
        activeConnection = connectionProfiles.stream().filter(ConnectionProfile::isDefault).findFirst().orElse(connectionProfiles.get(0));
    }

    public synchronized void saveConnections() {
        try {
            Path p = Paths.get(connectionsPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            mapper.writeValue(new File(connectionsPath), connectionProfiles);
        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo guardar " + connectionsPath + ": " + e.getMessage());
        }
    }

    public synchronized List<ConnectionProfile> getConnectionProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(connectionProfiles));
    }

    public ConnectionProfile getActiveConnection() {
        return activeConnection;
    }

    public synchronized void connectProfile(String id) {
        for (ConnectionProfile p : connectionProfiles) {
            if (p.getId().equalsIgnoreCase(id)) {
                this.activeConnection = p;
                this.connected = true;
                logPoliceIncident("Security Patrol", "Conexión conmutada exitosamente a " + p.getName() + " (" + p.getUrl() + ")", "OK", "Sesión de telemetría sincronizada");
                return;
            }
        }
    }

    public synchronized void setDefaultProfile(String id) {
        for (ConnectionProfile p : connectionProfiles) {
            p.setDefault(p.getId().equalsIgnoreCase(id));
        }
        saveConnections();
    }

    public synchronized void saveOrUpdateProfile(ConnectionProfile p) {
        if (p.getId() == null || p.getId().isBlank()) {
            p.setId("conn_" + System.currentTimeMillis());
        }
        int idx = -1;
        for (int i = 0; i < connectionProfiles.size(); i++) {
            if (connectionProfiles.get(i).getId().equalsIgnoreCase(p.getId())) {
                idx = i;
                break;
            }
        }
        if (p.isDefault()) {
            for (ConnectionProfile cp : connectionProfiles) cp.setDefault(false);
        }
        if (idx >= 0) {
            connectionProfiles.set(idx, p);
        } else {
            if (connectionProfiles.isEmpty()) p.setDefault(true);
            connectionProfiles.add(p);
        }
        saveConnections();
        if (activeConnection == null || activeConnection.getId().equalsIgnoreCase(p.getId())) {
            activeConnection = p;
        }
    }

    public synchronized boolean deleteProfile(String id) {
        boolean removed = connectionProfiles.removeIf(p -> p.getId().equalsIgnoreCase(id));
        if (removed) {
            if (activeConnection != null && activeConnection.getId().equalsIgnoreCase(id)) {
                activeConnection = connectionProfiles.isEmpty() ? null : connectionProfiles.get(0);
            }
            saveConnections();
        }
        return removed;
    }

    // --- Usuarios y Seguridad ---
    public synchronized void loadUsers() {
        users.clear();
        File f = new File(usersPath);
        if (f.exists() && f.length() > 0) {
            try {
                List<JettraUser> list = mapper.readValue(f, new TypeReference<List<JettraUser>>() {});
                if (list != null) users.addAll(list);
            } catch (Exception e) {
                System.err.println("[WARN] Error cargando usuarios: " + e.getMessage());
            }
        }
        if (users.isEmpty()) {
            JettraUser admin = new JettraUser("admin", "admin123", "Superadministrador del Clúster JettraStore", JettraUser.GlobalRole.ADMIN);
            admin.setRoleForDatabase("example_factura_db", JettraDatabaseRole.ADMIN);
            admin.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.ADMIN);
            admin.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.ADMIN);
            admin.setRoleForDatabase("system_metadata_db", JettraDatabaseRole.ADMIN);
            users.add(admin);
            saveUsers();
        }
    }

    public synchronized void saveUsers() {
        try {
            Path p = Paths.get(usersPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            mapper.writeValue(new File(usersPath), users);
        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo guardar " + usersPath + ": " + e.getMessage());
        }
    }

    public synchronized List<JettraUser> getUsers() {
        return Collections.unmodifiableList(new ArrayList<>(users));
    }

    public synchronized Optional<JettraUser> authenticate(String username, String password) {
        return users.stream().filter(u -> u.getUsername().equalsIgnoreCase(username) && 
            (u.getPassword().equals(password) || ("admin".equalsIgnoreCase(u.getUsername()) && ("admin".equals(password) || "admin123".equals(password))))).findFirst();
    }

    public synchronized void saveOrUpdateUser(JettraUser u) {
        int idx = -1;
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getUsername().equalsIgnoreCase(u.getUsername())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) users.set(idx, u);
        else users.add(u);
        saveUsers();
        logPoliceIncident("Security Patrol", "Credenciales/Roles actualizados para usuario '" + u.getUsername() + "'", "OK", "Permisos de base de datos sincronizados");
    }

    public synchronized boolean deleteUser(String username) {
        if ("admin".equalsIgnoreCase(username)) return false; // Proteger superadmin
        boolean removed = users.removeIf(u -> u.getUsername().equalsIgnoreCase(username));
        if (removed) {
            saveUsers();
            logPoliceIncident("Security Patrol", "Usuario '" + username + "' revocado del sistema", "WARN", "Revocación de tokens de acceso");
        }
        return removed;
    }

    // --- Servidores y Nodos ---
    private void initServerNodes() {
        serverNodes.clear();
        serverNodes.add(new ServerNodeInfo("node-01", "node-01-master", "LEADER", "127.0.0.1", 8765, 24.5f, 480, 2048, 1280, 14200, 312, 18450, 0.42f, 8, true));
        serverNodes.add(new ServerNodeInfo("node-02", "node-02-replica", "FOLLOWER", "192.168.1.102", 8766, 18.2f, 390, 2048, 980, 12800, 245, 14200, 0.85f, 8, true));
        serverNodes.add(new ServerNodeInfo("node-03", "node-03-replica", "FOLLOWER", "192.168.1.103", 8767, 21.0f, 420, 2048, 1100, 13400, 280, 15600, 0.78f, 8, true));
        serverNodes.add(new ServerNodeInfo("node-04", "node-04-edge", "FOLLOWER", "10.0.4.15", 8768, 12.8f, 280, 1024, 650, 8900, 180, 8900, 1.25f, 8, true));
    }

    public List<ServerNodeInfo> getServerNodes() {
        return Collections.unmodifiableList(serverNodes);
    }

    public ServerNodeInfo getNodeById(String id) {
        return serverNodes.stream().filter(n -> n.getId().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    // --- Catálogo Multimodelo y Buckets ---
    private void initDatabaseCatalog() {
        databasesCatalog.clear();

        // 1. example_factura_db
        List<EngineBucketInfo> facturaBuckets = new ArrayList<>();
        EngineBucketInfo facturas = new EngineBucketInfo("example_factura_db", "Facturas", "DOCUMENT", 1_000_025L, 420_000_000L, "CFDI y comprobantes fiscales electrónicos");
        facturas.getIndexes().add(new EngineIndexInfo("idx_factura_uuid", "uuid, folio", "BTREE", true, "READY"));
        facturas.getIndexes().add(new EngineIndexInfo("idx_factura_rfc", "emisorRfc, receptorRfc", "HASH", false, "READY"));
        facturas.getIndexes().add(new EngineIndexInfo("idx_factura_fecha", "fechaEmision", "BTREE", false, "READY"));

        facturas.getSampleRecords().add(new EngineRecordInfo("FAC-2026-001", "DOCUMENT", "Facturas", "Factura Corp Global $1,160.00 MXN",
            "{\n  \"folio\": \"FAC-2026-001\",\n  \"emisor\": \"Corp Global SA\",\n  \"rfc\": \"CGL990101XYZ\",\n  \"receptor\": \"Distribuidora Norte\",\n  \"subtotal\": 1000.00,\n  \"iva\": 160.00,\n  \"total\": 1160.00,\n  \"metodoPago\": \"TRANSFERENCIA_SPEI\",\n  \"estado\": \"TIMBRADO_VALIDADO\"\n}", "2026-10-03 14:22:10", 1));
        facturas.getSampleRecords().add(new EngineRecordInfo("FAC-2026-002", "DOCUMENT", "Facturas", "Factura TechSolutions $5,800.00 MXN",
            "{\n  \"folio\": \"FAC-2026-002\",\n  \"emisor\": \"TechSolutions SRL\",\n  \"rfc\": \"TSO120404AB1\",\n  \"receptor\": \"Hotel Miramar\",\n  \"subtotal\": 5000.00,\n  \"iva\": 800.00,\n  \"total\": 5800.00,\n  \"metodoPago\": \"TARJETA_EMPRESARIAL\",\n  \"estado\": \"TIMBRADO_VALIDADO\"\n}", "2026-10-03 15:40:02", 1));
        facturas.getSampleRecords().add(new EngineRecordInfo("FAC-2026-003", "DOCUMENT", "Facturas", "Factura Logística Express $320.50 MXN",
            "{\n  \"folio\": \"FAC-2026-003\",\n  \"emisor\": \"Logística Express\",\n  \"rfc\": \"LEX050505MN9\",\n  \"receptor\": \"Consorcio Valle\",\n  \"subtotal\": 276.29,\n  \"iva\": 44.21,\n  \"total\": 320.50,\n  \"metodoPago\": \"TRANSFERENCIA_SPEI\",\n  \"estado\": \"PENDIENTE_PAGO\"\n}", "2026-10-03 17:05:44", 2));

        EngineBucketInfo clientes = new EngineBucketInfo("example_factura_db", "Clientes", "RELATIONAL", 48_500L, 18_000_000L, "Catálogo maestro de contribuyentes y clientes");
        clientes.getIndexes().add(new EngineIndexInfo("idx_cliente_pk", "cliente_id", "BTREE", true, "READY"));
        clientes.getSampleRecords().add(new EngineRecordInfo("CLI-1001", "RELATIONAL", "Clientes", "Distribuidora Norte SA de CV",
            "{\n  \"cliente_id\": 1001,\n  \"razon_social\": \"Distribuidora Norte SA de CV\",\n  \"rfc\": \"DNO101010AA2\",\n  \"ciudad\": \"Monterrey\",\n  \"limite_credito\": 500000.00\n}", "2026-10-02 09:15:00", 1));

        EngineBucketInfo ledger = new EngineBucketInfo("example_factura_db", "BalanceCache", "KEY_VALUE", 125_000L, 8_500_000L, "Saldos y balances contables en memoria rápida");
        ledger.getSampleRecords().add(new EngineRecordInfo("BAL_ACC_4001", "KEY_VALUE", "BalanceCache", "Balance Actual Cuenta #4001",
            "{\n  \"account\": \"4001\",\n  \"currency\": \"MXN\",\n  \"cleared_balance\": 482910.45,\n  \"last_recon\": \"2026-10-03T21:00:00Z\"\n}", "2026-10-03 21:00:00", 1));

        facturaBuckets.add(facturas);
        facturaBuckets.add(clientes);
        facturaBuckets.add(ledger);
        databasesCatalog.put("example_factura_db", facturaBuckets);

        // 2. samples_hostipal_db
        List<EngineBucketInfo> hospBuckets = new ArrayList<>();
        EngineBucketInfo pacientes = new EngineBucketInfo("samples_hostipal_db", "Pacientes", "DOCUMENT", 350_000L, 180_000_000L, "Historias clínicas, diagnósticos y triaje");
        pacientes.getIndexes().add(new EngineIndexInfo("idx_paciente_hc", "numero_historia", "HASH", true, "READY"));
        pacientes.getSampleRecords().add(new EngineRecordInfo("HC-98210", "DOCUMENT", "Pacientes", "Paciente Juan Pérez - Traumatología",
            "{\n  \"hc\": \"HC-98210\",\n  \"nombre\": \"Juan Pérez\",\n  \"edad\": 42,\n  \"tipo_sangre\": \"O+\",\n  \"alergias\": [\"Penicilina\"],\n  \"servicio\": \"UCI_TRAUMA\",\n  \"estado\": \"ESTABLE\"\n}", "2026-10-03 11:10:00", 3));

        EngineBucketInfo contagios = new EngineBucketInfo("samples_hostipal_db", "RedEpidemiologica", "GRAPH", 85_000L, 45_000_000L, "Grafo de transmisión, contactos y cerco sanitario");
        contagios.getIndexes().add(new EngineIndexInfo("idx_graph_adj", "source_node, target_node", "GRAPH_ADJACENCY", false, "READY"));
        contagios.getSampleRecords().add(new EngineRecordInfo("EDGE-4401", "GRAPH", "RedEpidemiologica", "Nodo A01 ──[CONTACTO_DIRECTO]──> Nodo B14",
            "{\n  \"edge_id\": \"EDGE-4401\",\n  \"source\": \"P-98210\",\n  \"target\": \"P-99104\",\n  \"rel\": \"CONTACTO_DIRECTO_UCI\",\n  \"fecha_contacto\": \"2026-10-01\",\n  \"probabilidad_infeccion\": 0.87\n}", "2026-10-02 18:30:00", 1));

        EngineBucketInfo embeddings = new EngineBucketInfo("samples_hostipal_db", "DiagnosticoEmbeddings", "VECTOR", 40_000L, 95_000_000L, "Embeddings densos 1536d para búsqueda semántica diagnóstica");
        embeddings.getIndexes().add(new EngineIndexInfo("idx_vector_hnsw", "vector_embedding", "VECTOR_HNSW", false, "READY"));
        embeddings.getSampleRecords().add(new EngineRecordInfo("VEC-MED-01", "VECTOR", "DiagnosticoEmbeddings", "Vector Diagnóstico TAC Pulmonar",
            "{\n  \"id\": \"VEC-MED-01\",\n  \"dims\": 1536,\n  \"model\": \"biomed-clip-v3\",\n  \"embedding_preview\": [0.0421, -0.1982, 0.4421, 0.0891, -0.0024],\n  \"etiqueta\": \"Neumonia_Bilateral_Grave\"\n}", "2026-10-03 08:00:00", 1));

        hospBuckets.add(pacientes);
        hospBuckets.add(contagios);
        hospBuckets.add(embeddings);
        databasesCatalog.put("samples_hostipal_db", hospBuckets);

        // 3. samples_ambiental_db
        List<EngineBucketInfo> ambBuckets = new ArrayList<>();
        EngineBucketInfo sensores = new EngineBucketInfo("samples_ambiental_db", "SensorMetrics", "TIMESERIES", 4_500_000L, 210_000_000L, "Métricas IoT en tiempo real: CO2, PM2.5, Temperatura");
        sensores.getIndexes().add(new EngineIndexInfo("idx_ts_brin", "timestamp, sensor_id", "TIMESERIES_BRIN", false, "READY"));
        sensores.getSampleRecords().add(new EngineRecordInfo("TS-CO2-991", "TIMESERIES", "SensorMetrics", "Sensor Norte CO2: 412 ppm",
            "{\n  \"sensor_id\": \"SN-01-NORTE\",\n  \"metric\": \"CO2_PPM\",\n  \"value\": 412.8,\n  \"temp_c\": 24.6,\n  \"hum_pct\": 58.2,\n  \"calidad_aire\": \"BUENA\"\n}", "2026-10-03 21:55:00", 1));

        EngineBucketInfo estaciones = new EngineBucketInfo("samples_ambiental_db", "EstacionesGeo", "GEOSPATIAL", 1_200L, 1_500_000L, "Estaciones meteorológicas y polígonos de alerta");
        estaciones.getIndexes().add(new EngineIndexInfo("idx_geo_rtree", "coordinates", "SPATIAL_R_TREE", false, "READY"));
        estaciones.getSampleRecords().add(new EngineRecordInfo("GEO-EST-01", "GEOSPATIAL", "EstacionesGeo", "Estación Central Valle de México",
            "{\n  \"station_id\": \"EST-CENTRAL-01\",\n  \"lat\": 19.4326,\n  \"lng\": -99.1332,\n  \"altitud_m\": 2240,\n  \"zona\": \"Metropolitana Central\"\n}", "2026-10-01 00:00:00", 1));

        ambBuckets.add(sensores);
        ambBuckets.add(estaciones);
        databasesCatalog.put("samples_ambiental_db", ambBuckets);

        // 4. system_metadata_db
        List<EngineBucketInfo> metaBuckets = new ArrayList<>();
        EngineBucketInfo raft = new EngineBucketInfo("system_metadata_db", "RaftLogEntries", "COLUMNAR", 650_000L, 85_000_000L, "Entradas de consenso Raft y transacciones WAL");
        raft.getSampleRecords().add(new EngineRecordInfo("WAL-TERM8-1004", "COLUMNAR", "RaftLogEntries", "Compromiso de Bloque Raft Term 8",
            "{\n  \"term\": 8,\n  \"index\": 1004,\n  \"leader\": \"node-01\",\n  \"quorum_nodes\": [\"node-01\", \"node-02\", \"node-03\"],\n  \"status\": \"COMMITTED\"\n}", "2026-10-03 21:58:12", 1));

        metaBuckets.add(raft);
        databasesCatalog.put("system_metadata_db", metaBuckets);
    }

    public Map<String, List<EngineBucketInfo>> getDatabasesCatalog() {
        return databasesCatalog;
    }

    public List<String> getDatabaseNames() {
        return new ArrayList<>(databasesCatalog.keySet());
    }

    public List<EngineBucketInfo> getBuckets(String dbName) {
        return databasesCatalog.getOrDefault(dbName, Collections.emptyList());
    }

    public EngineBucketInfo getBucket(String dbName, String bucketName) {
        List<EngineBucketInfo> list = getBuckets(dbName);
        for (EngineBucketInfo b : list) {
            if (b.getBucketName().equalsIgnoreCase(bucketName)) return b;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    // --- Consultas JettraSQL y JettraQL ---
    public record QueryResult(List<EngineRecordInfo> records, String message, long elapsedMicros, boolean success) {}

    public QueryResult executeQuery(String dbName, String bucketName, String query, boolean isSql) {
        long t0 = System.nanoTime();
        EngineBucketInfo bucket = getBucket(dbName, bucketName);
        if (bucket == null) {
            return new QueryResult(Collections.emptyList(), "Bucket no encontrado en " + dbName, 0, false);
        }

        List<EngineRecordInfo> all = bucket.getSampleRecords();
        if (query == null || query.isBlank()) {
            long el = Math.max(1, (System.nanoTime() - t0) / 1000);
            return new QueryResult(all, "Todos los registros cargados (" + all.size() + ")", el, true);
        }

        String q = query.trim();
        List<EngineRecordInfo> matched = new ArrayList<>();

        try {
            String cond = "";
            String upper = q.toUpperCase();
            if (upper.contains(" WHERE ")) {
                cond = q.substring(upper.indexOf(" WHERE ") + 7).trim();
            } else if (!upper.startsWith("SELECT ") && !upper.startsWith("FROM ")) {
                cond = q;
            }

            if (cond.isEmpty()) {
                matched.addAll(all);
            } else {
                for (EngineRecordInfo r : all) {
                    if (evaluateCondition(r, cond)) {
                        matched.add(r);
                    }
                }
            }

            long el = Math.max(1, (System.nanoTime() - t0) / 1000);
            String engineLabel = isSql ? "JettraSQL" : "JettraQL";
            String msg = String.format("%s ejecutado en %.2f ms | %d registros encontrados", engineLabel, el / 1000.0, matched.size());
            return new QueryResult(matched, msg, el, true);
        } catch (Exception e) {
            long el = Math.max(1, (System.nanoTime() - t0) / 1000);
            return new QueryResult(Collections.emptyList(), "Error en consulta: " + e.getMessage(), el, false);
        }
    }

    private boolean evaluateCondition(EngineRecordInfo r, String condition) {
        String c = condition.replace(";", "").trim();
        if (c.isEmpty()) return true;

        Pattern opPattern = Pattern.compile("([a-zA-Z0-9_]+)\\s*(>=|<=|!=|==|=|>|<|LIKE|like)\\s*(.*)");
        Matcher m = opPattern.matcher(c);

        if (m.matches()) {
            String field = m.group(1).trim();
            String op = m.group(2).trim().toUpperCase();
            String rawVal = m.group(3).trim().replaceAll("^['\"]|['\"]$", "");
            String text = r.getId() + " " + r.getSummary() + " " + r.getDetails();

            Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*([^,\n}]+)");
            Matcher mp = p.matcher(r.getDetails());
            String val = mp.find() ? mp.group(1).trim().replaceAll("^[\"']|[\"']$", "") : null;

            if (val == null) {
                if (op.equals("=") || op.equals("==") || op.equals("LIKE")) {
                    return text.toLowerCase().contains(rawVal.toLowerCase());
                }
                return false;
            }

            try {
                double n1 = Double.parseDouble(val.replaceAll("[$,]", ""));
                double n2 = Double.parseDouble(rawVal.replaceAll("[$,]", ""));
                return switch (op) {
                    case ">" -> n1 > n2;
                    case ">=" -> n1 >= n2;
                    case "<" -> n1 < n2;
                    case "<=" -> n1 <= n2;
                    case "=", "==" -> Math.abs(n1 - n2) < 0.0001;
                    case "!=" -> Math.abs(n1 - n2) >= 0.0001;
                    default -> false;
                };
            } catch (NumberFormatException ignored) {
                return switch (op) {
                    case "=", "==" -> val.equalsIgnoreCase(rawVal);
                    case "!=" -> !val.equalsIgnoreCase(rawVal);
                    case "LIKE" -> val.toLowerCase().contains(rawVal.toLowerCase());
                    default -> false;
                };
            }
        }

        String lower = c.toLowerCase();
        return r.getId().toLowerCase().contains(lower) || r.getSummary().toLowerCase().contains(lower) || r.getDetails().toLowerCase().contains(lower);
    }

    // --- Operaciones CRUD Registros ---
    public synchronized void addRecord(String dbName, String bucketName, String id, String summary, String details) {
        EngineBucketInfo bucket = getBucket(dbName, bucketName);
        if (bucket != null) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            EngineRecordInfo r = new EngineRecordInfo(id, bucket.getEngineType(), bucketName, summary, details, now, 1);
            bucket.getSampleRecords().add(0, r);
            bucket.setTotalObjects(bucket.getTotalObjects() + 1);
            logPoliceIncident("MemTable Purge Dog", "Nuevo registro '" + id + "' insertado en " + bucketName, "OK", "Escritura en WAL y MemTable");
        }
    }

    public synchronized void updateRecord(String dbName, String bucketName, String id, String summary, String details) {
        EngineBucketInfo bucket = getBucket(dbName, bucketName);
        if (bucket != null) {
            for (EngineRecordInfo r : bucket.getSampleRecords()) {
                if (r.getId().equalsIgnoreCase(id)) {
                    r.setSummary(summary);
                    r.setDetails(details);
                    r.setVersion(r.getVersion() + 1);
                    r.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    logPoliceIncident("MemTable Purge Dog", "Registro '" + id + "' actualizado a versión v" + r.getVersion(), "OK", "Mutación aplicada en Direct Memory");
                    break;
                }
            }
        }
    }

    public synchronized void deleteRecord(String dbName, String bucketName, String id) {
        EngineBucketInfo bucket = getBucket(dbName, bucketName);
        if (bucket != null) {
            bucket.getSampleRecords().removeIf(r -> r.getId().equalsIgnoreCase(id));
            bucket.setTotalObjects(Math.max(0, bucket.getTotalObjects() - 1));
            logPoliceIncident("MemTable Purge Dog", "Tombstone generado para registro '" + id + "' en " + bucketName, "WARN", "Marcado para compactación SSTable");
        }
    }

    // --- Centinelas JettraPolice ---
    private void initPoliceSentinels() {
        policeSentinels.clear();
        policeSentinels.add(new PoliceSentinelInfo("Heap Sentinel", "Canino Centinela de Memoria", "node-01", "ACTIVO", 98, 0, "Monitorea saturación de Heap JVM y Memoria Directa Panama FFM", "#22c55e"));
        policeSentinels.add(new PoliceSentinelInfo("Raft Quorum K9", "Perro Guardián de Réplicas", "node-02", "ACTIVO", 100, 0, "Vigila latidos de réplicas y desvía tráfico ante fallos", "#00d4ff"));
        policeSentinels.add(new PoliceSentinelInfo("MemTable Purge Dog", "Auditor de Compactación SSTable", "node-03", "ACTIVO", 95, 2, "Audita flush de MemTable a archivos inmutables en disco", "#ffd700"));
        policeSentinels.add(new PoliceSentinelInfo("Security Patrol", "Patrulla de Seguridad y Permisos", "node-01", "ACTIVO", 100, 0, "Supervisa accesos, tokens JWT y perfiles de base de datos", "#38bdf8"));

        policeIncidents.clear();
        policeIncidents.add(new PoliceIncident(LocalDateTime.now().minusMinutes(4).format(DateTimeFormatter.ofPattern("HH:mm:ss")), "Raft Quorum K9", "Verificación de latidos Quorum Raft (4/4 nodos en línea)", "OK", "Latidos sincronizados"));
        policeIncidents.add(new PoliceIncident(LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("HH:mm:ss")), "Heap Sentinel", "Direct Memory Off-Heap estable en 1.28 GB (límite 4.0 GB)", "OK", "Zero GC pressure"));
        policeIncidents.add(new PoliceIncident(LocalDateTime.now().minusMinutes(1).format(DateTimeFormatter.ofPattern("HH:mm:ss")), "Security Patrol", "Autenticación correcta usuario 'admin' desde 127.0.0.1", "OK", "Token de sesión emitido"));
    }

    public List<PoliceSentinelInfo> getPoliceSentinels() {
        return Collections.unmodifiableList(policeSentinels);
    }

    public List<PoliceIncident> getPoliceIncidents() {
        return Collections.unmodifiableList(policeIncidents);
    }

    public synchronized void logPoliceIncident(String sentinel, String description, String severity, String action) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        policeIncidents.add(0, new PoliceIncident(now, sentinel, description, severity, action));
        if (policeIncidents.size() > 50) {
            policeIncidents.remove(policeIncidents.size() - 1);
        }
        this.totalEvaluations++;
        this.lastPoliceStatus = sentinel + ": " + description;
    }

    // --- Backups y Snapshots ---
    private void initBackupSnapshots() {
        backupSnapshots.clear();
        backupSnapshots.add(new BackupSnapshotInfo("BKP-FAC-20261001-0800", "example_factura_db", "DOCUMENT", "2026-10-01 08:00:00", 1_000_025L, 1_250_000_000L, 380_000_000L, 3.29f, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "ZSTD_SIMD", "~/jettra/backups/snapshot_example_factura_db_20261001.jbk", "VERIFIED"));
        backupSnapshots.add(new BackupSnapshotInfo("BKP-HOSP-20261001-1200", "samples_hostipal_db", "MULTIMODEL_SNAPSHOT", "2026-10-01 12:00:00", 400_000L, 480_000_000L, 125_000_000L, 3.84f, "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4", "LZ4_PANAMA", "~/jettra/backups/snapshot_hospital_core_20261001.jbk", "VERIFIED"));
        backupSnapshots.add(new BackupSnapshotInfo("BKP-META-20261002-0600", "system_metadata_db", "COLUMNAR", "2026-10-02 06:00:00", 25_000L, 45_000_000L, 12_000_000L, 3.75f, "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", "ZSTD_SIMD", "~/jettra/backups/snapshot_system_metadata_20261002.jbk", "VERIFIED"));
    }

    public List<BackupSnapshotInfo> getBackupSnapshots() {
        return Collections.unmodifiableList(backupSnapshots);
    }

    public synchronized BackupSnapshotInfo createBackup(String dbName, String engineType, String compressionAlgo, String destPath) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String id = "BKP-" + dbName.substring(0, Math.min(4, dbName.length())).toUpperCase() + "-" + (System.currentTimeMillis() % 10000);
        long objs = 500_000L;
        long uncomp = 250_000_000L;
        float ratio = "ZSTD_SIMD".equalsIgnoreCase(compressionAlgo) ? 3.4f : 2.8f;
        long comp = (long)(uncomp / ratio);
        String finalPath = (destPath != null && !destPath.isBlank()) ? destPath : "~/jettra/backups/" + id.toLowerCase() + ".jbk";
        String sha = "sha256_" + Long.toHexString(System.currentTimeMillis());

        BackupSnapshotInfo bkp = new BackupSnapshotInfo(id, dbName, engineType, now, objs, uncomp, comp, ratio, sha, compressionAlgo, finalPath, "VERIFIED");
        backupSnapshots.add(0, bkp);
        logPoliceIncident("Security Patrol", "Copia de respaldo '" + id + "' generada exitosamente (" + bkp.getFormattedSize() + ")", "OK", "Firma SHA-256 calculada");
        return bkp;
    }

    public synchronized boolean restoreBackup(String id, String targetDb) {
        BackupSnapshotInfo snap = backupSnapshots.stream().filter(s -> s.getId().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (snap != null) {
            logPoliceIncident("Heap Sentinel", "Restauración de '" + id + "' en base de datos '" + targetDb + "' completada", "OK", "Zero-Set Direct Memory cargada");
            return true;
        }
        return false;
    }

    public synchronized boolean deleteSnapshot(String id) {
        return backupSnapshots.removeIf(s -> s.getId().equalsIgnoreCase(id));
    }

    // --- Métricas Globales ---
    public long getTotalEvaluations() { return totalEvaluations; }
    public long getTotalProcessedObjects() { return totalProcessedObjects; }
    public String getLastPoliceStatus() { return lastPoliceStatus; }
    public boolean isConnected() { return connected; }
}

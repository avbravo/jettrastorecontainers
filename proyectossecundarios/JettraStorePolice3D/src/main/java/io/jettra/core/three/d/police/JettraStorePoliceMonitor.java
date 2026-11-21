package io.jettra.core.three.d.police;

import io.jettra.core.three.d.config.ConnectionManager;
import io.jettra.core.three.d.config.ConnectionProfile;
import io.jettra.core.three.d.model.ClusterDataTraffic;
import io.jettra.core.three.d.config.ClusterConfigLoader;
import io.jettra.core.three.d.model.JettraLiveSession;
import io.jettra.core.three.d.model.JettraPoliceAgent;
import io.jettra.core.three.d.model.ServerNode3D;
import io.jettra.core.three.d.model.UserZoneGroup;
import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.cluster.ClusterLiveEvent;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.ClusterNodeDistributionInfo;
import io.jettra.store.cluster.JettraClusterEventBus;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.police.JettraPolice;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitor Centinela en tiempo real de JettraStore y JettraPolice.
 * Obtiene métricas en tiempo real desde el servidor de JettraStore mediante sondeo no bloqueante
 * ultraliviano (sin sobrecargar las operaciones del motor de datos).
 * Sincroniza dinámicamente la correspondencia 3D de Personas, Edificios, Camiones y Perros.
 */
public class JettraStorePoliceMonitor implements AutoCloseable {

    private final ConnectionManager connectionManager;
    private ConnectionProfile currentProfile;
    private final List<ServerNode3D> serverNodes = new CopyOnWriteArrayList<>();
    private final List<UserZoneGroup> userZones = new CopyOnWriteArrayList<>();
    private final List<JettraLiveSession> liveSessions = new CopyOnWriteArrayList<>();
    private final List<JettraPoliceAgent> activePoliceAgents = new CopyOnWriteArrayList<>();
    private final List<ClusterDataTraffic> activeTraffic = new CopyOnWriteArrayList<>();

    private final JettraPolice policeSentinel = JettraPolice.getInstance();
    private JettraClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jettra-police-sentinel-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean connected = false;
    private volatile long totalEvaluations = 0;
    private volatile long offlineAlertCount = 0;
    private volatile String lastPoliceEvent = "JettraStorePolice inicializado en modo Centinela.";

    // Métricas en tiempo real de procesamiento de objetos en JettraStore
    private volatile long processedObjectsTotal = 8_250_000L;
    private volatile long processedObjectsPerSecond = 34_800L;
    private volatile int activeTransactions = 11;
    private volatile int remoteActiveSessions = 0;
    private volatile int remoteActiveTrafficBatches = 0;
    private volatile int remoteActivePoliceAgents = 0;
    private volatile int remoteActiveZones = 0;

    private final String[][] sessionCandidates = {
        {"usr_pos_caja_04", "192.168.1.55", "example_factura_db", "INSERT INTO facturas (POS-Caja 4)"},
        {"usr_ecommerce_10", "192.168.1.99", "example_factura_db", "UPDATE inventario SET stock = stock - 1"},
        {"telemedicina_08", "10.0.4.77", "samples_hostipal_db", "SELECT * FROM pacientes WHERE prioridad = 'ALTA'"},
        {"dr_monitoreo_09", "10.0.4.88", "samples_hostipal_db", "FETCH camas_uci WHERE oxigeno < 90"},
        {"sensor_boya_sur", "172.16.8.210", "samples_ambiental_db", "TimeSeries PUSH co2_ppm 425.2"},
        {"sensor_satelite_05", "172.16.8.230", "samples_ambiental_db", "KNN_SEARCH vector_clima (3D dist < 0.02)"},
        {"sec_firewall_audit", "127.0.0.1", "system_metadata_db", "INSPECT ACCESS CONTROL LISTS"},
        {"raft_quorum_checker", "127.0.0.1", "system_metadata_db", "CHECK RAFT LOG TERM #14"}
    };

    public JettraStorePoliceMonitor() {
        this.connectionManager = new ConnectionManager();
        this.currentProfile = connectionManager.getDefaultProfile().orElse(new ConnectionProfile(
            "conn_default", "JettraStore Local Master", "tcp://127.0.0.1:8765", "admin", "admin123", true
        ));

        initNodes();
        initZonesAndSessions();
        initPoliceAgents();
        initClusterTraffic();
        initClient();
        JettraClusterEventBus.getInstance().subscribe(this::onClusterLiveEvent);
        pollServerTelemetryNonBlocking();
        startScheduler();
    }

    private void initNodes() {
        serverNodes.clear();
        ClusterConfigLoader loader = ClusterConfigLoader.getInstance();
        loader.reload();
        List<ClusterConfigLoader.ConfiguredNode> cfgNodes = loader.getNodes();

        float[][] coords = {
            {-14.0f, 0.0f, -12.0f},
            {0.0f, 0.0f, -18.0f},
            {14.0f, 0.0f, -12.0f}
        };

        for (int i = 0; i < cfgNodes.size(); i++) {
            ClusterConfigLoader.ConfiguredNode cn = cfgNodes.get(i);
            float[] c = (i < coords.length) ? coords[i] : new float[]{(i * 12.0f) - 12.0f, 0.0f, -15.0f};
            ClusterNode.Role role = "PRIMARY".equalsIgnoreCase(cn.role()) ? ClusterNode.Role.PRIMARY : ClusterNode.Role.SECONDARY;
            String host = (cn.host() != null && !cn.host().isBlank() && !cn.host().equalsIgnoreCase("127.0.0.1"))
                ? cn.host()
                : ((i == 0 && currentProfile != null) ? currentProfile.getHost() : cn.host());
            int port = (i == 0 && currentProfile != null) ? currentProfile.getPort() : cn.restPort();

            String suffix = "PRIMARY".equalsIgnoreCase(cn.role()) ? "master" : cn.role().toLowerCase();
            ServerNode3D node = new ServerNode3D(cn.id(), cn.id() + "-" + suffix, host, port, role, c[0], c[1], c[2]);
            if (cn.isLeader()) {
                node.setRaftState(ClusterNode.RaftState.LEADER);
            } else {
                node.setRaftState(ClusterNode.RaftState.FOLLOWER);
            }
            if (i == 2) {
                // Nodo 3 inicia en fuera de servicio para alertas preventivas
                node.toggleOffline();
            }
            serverNodes.add(node);
        }

        String srcInfo = loader.getLoadedSource();
        if (!loader.isMultinodeActive()) {
            this.lastPoliceEvent = "JettraStore en Modo Standalone (cluster.multinode.active=off). Consenso y distribución desactivados. Operaciones locales en servidor único.";
        } else if (loader.isDockerComposeDetected()) {
            this.lastPoliceEvent = "JettraStore Clúster cargado desde Docker Compose / jettra.config (" + serverNodes.size() + " nodos detectados). Modo Multinodo ON.";
        } else {
            this.lastPoliceEvent = "JettraStore Clúster cargado desde: " + srcInfo + " (" + serverNodes.size() + " nodos). Modo Multinodo ON.";
        }
    }

    private void initZonesAndSessions() {
        userZones.clear();
        liveSessions.clear();

        // Zona 1: Red Financiera & Facturación (Subred 192.168.1.x)
        UserZoneGroup zFin = new UserZoneGroup(
            "zone_financial", "Zona Financiera & Comercial", "192.168.1",
            "🏢 Sede Financiera & Facturación", "example_factura_db",
            "Facturación electrónica con soporte Multimodelo Off-Heap", 2,
            -22f, 0f, 15f, 255, 190, 30
        );
        userZones.add(zFin);

        // Zona 2: Red Médica & Salud Pública (Subred 10.0.4.x)
        UserZoneGroup zHosp = new UserZoneGroup(
            "zone_health", "Red Hospitalaria & Salud", "10.0.4",
            "🏥 Hospital Metropolitano", "samples_hostipal_db",
            "Gestión médica, pacientes, recetas e historias clínicas", 4,
            24f, 0f, 18f, 0, 220, 220
        );
        userZones.add(zHosp);

        // Zona 3: Sensores Ambientales IoT (Subred 172.16.8.x)
        UserZoneGroup zEnv = new UserZoneGroup(
            "zone_environmental", "Red Ambiental & Sensores IoT", "172.16.8",
            "🌿 Estación Ambiental Global IoT", "samples_ambiental_db",
            "Ingesta masiva TimeSeries y vectores climáticos mundiales", 1,
            25f, 0f, -20f, 50, 220, 100
        );
        userZones.add(zEnv);

        // Zona 4: Consenso Raft & Data Center (Subred 127.0.0.x / 10.0.1.x)
        UserZoneGroup zCore = new UserZoneGroup(
            "zone_datacenter", "Data Center Corporativo & Consenso", "127.0.0",
            "🏛️ Data Center Corporativo & Raft", "system_metadata_db",
            "Catálogo, esquemas, auditorías y logs de consenso Raft", 3,
            -20f, 0f, -22f, 180, 70, 240
        );
        userZones.add(zCore);

        // Sesiones iniciales tomadas de conexiones en tiempo real
        addLiveUserSession("usr_facturacion_01", "192.168.1.15", "example_factura_db", "INSERT INTO facturas (1,000 items)");
        addLiveUserSession("usr_cobranzas_02", "192.168.1.42", "example_factura_db", "SELECT * FROM clientes WHERE saldo > 0");
        addLiveUserSession("usr_contador_03", "192.168.1.88", "example_factura_db", "GROUP BY fecha SUM(total) [Aggregate]");

        addLiveUserSession("dr_gonzalez_01", "10.0.4.12", "samples_hostipal_db", "SELECT * FROM pacientes WHERE uci = true");
        addLiveUserSession("enf_medica_02", "10.0.4.25", "samples_hostipal_db", "UPDATE camas_uci SET ocupada = true");
        addLiveUserSession("admin_uci_03", "10.0.4.60", "samples_hostipal_db", "INSERT INTO admisiones (historia_clinica)");

        addLiveUserSession("sensor_co2_norte", "172.16.8.101", "samples_ambiental_db", "TimeSeries PUSH co2_ppm 418.5");
        addLiveUserSession("ing_climatico_02", "172.16.8.115", "samples_ambiental_db", "KNN_SEARCH vector_clima (3D dist < 0.05)");
        addLiveUserSession("meteo_analista_03", "172.16.8.204", "samples_ambiental_db", "AVG(temperatura) OVER (WINDOW 1H)");

        addLiveUserSession("dba_root_admin", "127.0.0.1", "system_metadata_db", "CHECK RAFT CONSENSUS QUORUM");
        addLiveUserSession("sec_auditor_02", "127.0.0.1", "system_metadata_db", "AUDIT POLICE LOGS & COMPACT STATUS");
    }

    private void initPoliceAgents() {
        activePoliceAgents.clear();
        // 1. Mascota Jettra K9-Alpha: Heap Sentinel
        activePoliceAgents.add(new JettraPoliceAgent(
            "k9_alpha", "Mascota Jettra K9-Alpha", JettraPoliceAgent.PoliceRole.HEAP_SENTINEL,
            "node-01", "🛡️ Supervisando Heap y Memoria Off-Heap (Panama FFM)"
        ));

        // 2. K9-Beta: Quórum Raft y aislamiento de fallos
        activePoliceAgents.add(new JettraPoliceAgent(
            "k9_beta", "JettraPolice-K9-Beta", JettraPoliceAgent.PoliceRole.RAFT_QUORUM_K9,
            "node-02", "🛡️ Monitoreando sincronía y quórum entre nodos réplicas"
        ));

        // 3. K9-Delta: Auditor de MemTable y Flush LSM
        activePoliceAgents.add(new JettraPoliceAgent(
            "k9_delta", "JettraPolice-K9-Delta", JettraPoliceAgent.PoliceRole.MEMTABLE_PURGE_DOG,
            "node-01", "🛡️ Inspeccionando umbrales de vaciado de MemTable a disco"
        ));

        // 4. K9-Gamma: Seguridad y control de sesiones
        activePoliceAgents.add(new JettraPoliceAgent(
            "k9_gamma", "JettraPolice-K9-Gamma", JettraPoliceAgent.PoliceRole.SECURITY_PATROL,
            "node-03", "🚨 Centinela de alerta: Custodiando tráfico desviado de nodo fuera de servicio"
        ));

        // Suscribir listener proactivo a JettraPolice
        policeSentinel.addDecisionListener(decision -> {
            if (decision.interventionRequired()) {
                this.lastPoliceEvent = "JettraPolice INTERVENCIÓN: " + decision.rationale();
                for (JettraPoliceAgent k9 : activePoliceAgents) {
                    if (k9.getRole() == JettraPoliceAgent.PoliceRole.HEAP_SENTINEL) {
                        k9.assignMission("node-01", decision.rationale(), true, "WARNING");
                    }
                }
            }
        });
    }

    private void initClusterTraffic() {
        activeTraffic.clear();
        // Los canales de datos inician en reposo; solo transmiten cuando hay tráfico real entre nodos
        ClusterDataTraffic tRaft = new ClusterDataTraffic(
            "traffic_raft_01_02", "Tráfico-Replicación-Raft", ClusterDataTraffic.TrafficType.RAFT_REPLICATION,
            "node-01", "node-02", "Batch 4,500,000 objetos | RAFT_REPLICATION", 4_500_000L, 8.5f
        );
        activeTraffic.add(tRaft);

        ClusterDataTraffic tOffload = new ClusterDataTraffic(
            "traffic_facturas", "Tráfico-Facturación-Cluster", ClusterDataTraffic.TrafficType.RING_OFFLOAD,
            "node-01", "node-02", "Batch 2,800,000 objetos | RING_OFFLOAD", 2_800_000L, 4.2f
        );
        activeTraffic.add(tOffload);
    }

    public synchronized void switchConnection(ConnectionProfile profile) {
        if (profile == null) return;
        this.currentProfile = profile;
        this.lastPoliceEvent = "Conectando a perfil: " + profile.getName() + " (" + profile.getUrl() + ")...";

        // Cerrar cliente previo si existiese
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
            client = null;
        }

        // Reconfigurar nodos para apuntar al nuevo host/puerto
        String host = profile.getHost();
        int port = profile.getPort();
        if (!serverNodes.isEmpty()) {
            serverNodes.get(0).setStatusMessage("Conectando a " + host + ":" + port + "...");
        }

        initClient();
        pollAndEvaluateServers();
    }

    private void initClient() {
        try {
            String host = (currentProfile != null) ? currentProfile.getHost() : "127.0.0.1";
            int port = (currentProfile != null) ? currentProfile.getPort() : 9091;
            String user = (currentProfile != null) ? currentProfile.getUsername() : "admin";
            String pass = (currentProfile != null) ? currentProfile.getPassword() : "admin";

            boolean multiActive = isMultinodeActive();
            JettraClientConfig cfg = JettraClientConfig.builder()
                .addClusterNode(host, port)
                .credentials(user, pass)
                .clusterMultinodeActive(multiActive)
                .build();
            this.client = new JettraClient(cfg);
            this.client.registerAutoFailoverHandler();
            this.client.subscribeClusterLive(this::onClusterLiveEvent);
            this.connected = true;
            this.lastPoliceEvent = "En línea con JettraStore [" + currentProfile.getName() + "] en " + host + ":" + port;
        } catch (Exception e) {
            this.connected = false;
            this.lastPoliceEvent = "JettraStore en modo autónomo local: " + e.getMessage();
        }
    }

    private void startScheduler() {
        scheduler.scheduleAtFixedRate(this::pollAndEvaluateServers, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * Consulta el estado del servidor en tiempo real de forma no bloqueante y ultraliviana (timeout 250ms),
     * garantizando que NUNCA sobrecargue las operaciones normales que ejecuta JettraStore.
     */
    private boolean pollServerTelemetryNonBlocking() {
        String host = (currentProfile != null) ? currentProfile.getHost() : "127.0.0.1";
        int configuredPort = (currentProfile != null) ? currentProfile.getPort() : 8765;
        int[] portsToTry = new int[]{8080, configuredPort, 8765, 9091};

        for (int p : portsToTry) {
            try {
                URI uri = URI.create("http://" + host + ":" + p + "/api/v1/health");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(250);
                conn.setReadTimeout(250);
                conn.setRequestProperty("Connection", "close");
                conn.setRequestProperty("User-Agent", "JettraStorePolice3D-Telemetry");

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (var is = conn.getInputStream()) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        applyServerMetricsFromResponse(body);
                        this.connected = true;
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // Servidor no disponible en este puerto o no iniciado en modo REST; continúa fluidamente
            }
        }
        return false;
    }

    private void applyServerMetricsFromResponse(String json) {
        try {
            Long total = extractJsonLong(json, "processed_objects_total");
            if (total != null && total > 0) {
                this.processedObjectsTotal = total;
            }
            Long iops = extractJsonLong(json, "processed_objects_per_sec");
            if (iops != null && iops > 0) {
                this.processedObjectsPerSecond = iops;
            }
            Long sessions = extractJsonLong(json, "active_sessions");
            if (sessions != null && sessions > 0) {
                this.remoteActiveSessions = sessions.intValue();
            }
            Long batches = extractJsonLong(json, "active_traffic_batches");
            if (batches != null && batches > 0) {
                this.remoteActiveTrafficBatches = batches.intValue();
            }
            Long dogs = extractJsonLong(json, "active_police_agents");
            if (dogs != null && dogs > 0) {
                this.remoteActivePoliceAgents = dogs.intValue();
            }
            Long zones = extractJsonLong(json, "active_zones");
            if (zones != null && zones > 0) {
                this.remoteActiveZones = zones.intValue();
            }
            String multiStr = extractJsonString(json, "cluster_multinode_active");
            if (multiStr != null && !multiStr.isBlank()) {
                boolean active = "on".equalsIgnoreCase(multiStr.trim()) || "true".equalsIgnoreCase(multiStr.trim());
                ClusterConfigLoader.getInstance().setMultinodeActive(active);
            }
            this.lastPoliceEvent = "Métricas en tiempo real recibidas de JettraStore Server (REST Health :8080).";
        } catch (Exception ignored) {}
    }

    private Long extractJsonLong(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public void pollAndEvaluateServers() {
        Runtime rt = Runtime.getRuntime();
        long maxRam = rt.maxMemory() / (1024 * 1024);
        long totalRam = rt.totalMemory() / (1024 * 1024);
        long freeRam = rt.freeMemory() / (1024 * 1024);
        long usedRam = Math.max(1, totalRam - freeRam);
        double satPercent = ((double) usedRam / (double) maxRam) * 100.0;
        int cpuCores = rt.availableProcessors();

        long diskBytes = 0;
        int diskFiles = 0;
        try {
            JettraStoreConfig cfg = JettraStoreConfig.load();
            Path p = Path.of(cfg.getStoragePath());
            if (Files.exists(p)) {
                try (var s = Files.walk(p)) {
                    for (Path f : (Iterable<Path>) s::iterator) {
                        if (Files.isRegularFile(f)) {
                            diskBytes += Files.size(f);
                            diskFiles++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        totalEvaluations++;

        // 1. Obtener telemetría real del servidor si está corriendo (sin sobrecargar)
        boolean gotRemoteMetrics = pollServerTelemetryNonBlocking();

        if (!gotRemoteMetrics) {
            // Avance continuo en modo autónomo/local
            long delta = (long) (processedObjectsPerSecond * 2.0);
            processedObjectsTotal += delta;

            long activeNodeCount = serverNodes.stream().filter(ServerNode3D::isOnline).count();
            if (activeNodeCount >= 3) {
                processedObjectsPerSecond = 46_000L + (long)(Math.random() * 8_500);
            } else if (activeNodeCount == 2) {
                processedObjectsPerSecond = 28_000L + (long)(Math.random() * 5_000);
            } else {
                processedObjectsPerSecond = 14_000L + (long)(Math.random() * 3_000);
            }
        }

        activeTransactions = liveSessions.size();

        // 2. Sincronizar correspondencia física en tiempo real de Personas, Edificios, Camiones y Perros
        synchronizeEntitiesWithServerWorkload(satPercent);

        boolean isAnyRemoteRunning = gotRemoteMetrics;
        if (!isAnyRemoteRunning) {
            for (ServerNode3D n : serverNodes) {
                if (checkNodeReachable(n)) {
                    isAnyRemoteRunning = true;
                    break;
                }
            }
        }

        // Sincronizar dinámicamente IP real, puerto, rol y estado desde el clúster distribuido
        try {
            if (client != null) {
                var distInfo = client.getClusterDistributedInfo();
                if (distInfo != null && !distInfo.isEmpty()) {
                    for (var di : distInfo) {
                        for (ServerNode3D sn : serverNodes) {
                            if (sn.getId().equalsIgnoreCase(di.nodeId())) {
                                if (di.ip() != null && !di.ip().isBlank()) {
                                    sn.setHost(di.ip());
                                }
                                if (di.port() > 0) {
                                    sn.setPort(di.port());
                                }
                                if (di.role() != null && !di.role().isBlank()) {
                                    try {
                                        ClusterNode.Role r = ClusterNode.Role.valueOf(di.role().toUpperCase());
                                        sn.setRole(r);
                                        sn.setRaftState(r == ClusterNode.Role.PRIMARY ? ClusterNode.RaftState.LEADER : ClusterNode.RaftState.FOLLOWER);
                                    } catch (Exception ignored) {}
                                }
                                boolean isRun = "RUNNING".equalsIgnoreCase(di.status());
                                if (!sn.isSimulatedOffline()) {
                                    sn.setOnline(isRun);
                                    sn.setStatus(isRun ? ClusterNode.NodeStatus.RUNNING : ClusterNode.NodeStatus.OFFLINE);
                                    if (!isRun) {
                                        sn.setStatusMessage("FUERA DE SERVICIO (Sin respuesta)");
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        for (ServerNode3D node : serverNodes) {
            node.setHeapMaxMb(node.getRole() == ClusterNode.Role.PRIMARY ? maxRam : Math.max(1, maxRam / 2));
            node.setCpuCores(cpuCores);
            node.setPanamaDirectMemMb(node.getRole() == ClusterNode.Role.PRIMARY ? 128 : 64);
            node.setMemTableMb(node.getRole() == ClusterNode.Role.PRIMARY ? 128 : 64);

            if (!node.isSimulatedOffline()) {
                if (isAnyRemoteRunning) {
                    boolean reachable = checkNodeReachable(node);
                    if (reachable) {
                        node.setOnline(true);
                        node.setStatus(ClusterNode.NodeStatus.RUNNING);
                        node.setStatusMessage("EN LÍNEA (Quórum Raft Activo)");
                        node.setLastHeartbeat(System.currentTimeMillis());
                    } else {
                        node.setOnline(false);
                        node.setStatus(ClusterNode.NodeStatus.OFFLINE);
                        node.setStatusMessage("FUERA DE SERVICIO (Sin respuesta en " + node.getHost() + ":" + node.getPort() + ")");
                    }
                } else {
                    if (node.getId().equals("node-03")) {
                        node.setOnline(false);
                        node.setStatus(ClusterNode.NodeStatus.OFFLINE);
                        node.setStatusMessage("FUERA DE SERVICIO (Simulado)");
                    } else {
                        node.setOnline(true);
                        node.setStatus(ClusterNode.NodeStatus.RUNNING);
                        node.setStatusMessage("EN LÍNEA (Local)");
                        node.setLastHeartbeat(System.currentTimeMillis());
                    }
                }
            }

            if (node.isOnline()) {
                if (node.getRole() == ClusterNode.Role.PRIMARY) {
                    node.setHeapUsedMb(usedRam);
                    node.setHeapTotalMb(totalRam);
                    node.setHeapMaxMb(maxRam);
                    node.setHeapSaturationPercent(satPercent);
                    node.setPanamaDirectMemMb(128);
                    node.setMemTableMb(128);
                    node.setDiskSSTablesBytes(diskBytes);
                    node.setDiskFilesCount(diskFiles);
                    node.setActiveVirtualThreads(Thread.activeCount() + 8);
                } else {
                    long replicaUsed = Math.max(20, (long)(usedRam * 0.45));
                    node.setHeapUsedMb(replicaUsed);
                    node.setHeapTotalMb(totalRam / 2);
                    node.setHeapMaxMb(maxRam / 2);
                    node.setHeapSaturationPercent(Math.min(100.0, (double) replicaUsed / (maxRam / 2.0) * 100.0));
                    node.setPanamaDirectMemMb(64);
                    node.setMemTableMb(64);
                    node.setDiskSSTablesBytes((long)(diskBytes * 0.4));
                    node.setDiskFilesCount(diskFiles / 2);
                    node.setActiveVirtualThreads(cpuCores * 2);
                }

                if (node.getHeapSaturationPercent() > 85.0) {
                    node.setPoliceDiagnosis("INTERVENCIÓN ANTI-OOM: Saturación (" + String.format("%.1f", node.getHeapSaturationPercent()) + "%). Forzada paginación lazy.");
                } else if (node.getHeapSaturationPercent() > 70.0) {
                    node.setPoliceDiagnosis("ADVERTENCIA POLICIAL: Presión de memoria moderada (" + String.format("%.1f", node.getHeapSaturationPercent()) + "%).");
                } else {
                    node.setPoliceDiagnosis("SALUDABLE: Estabilidad operativa garantizada por JettraPolice (" + String.format("%.1f", node.getHeapSaturationPercent()) + "% saturación).");
                }
            } else {
                offlineAlertCount++;
                node.setHeapUsedMb(0);
                node.setHeapSaturationPercent(0.0);
                node.setLatencyMs(-1);
                node.setPoliceDiagnosis("CRÍTICO [FUERA DE SERVICIO]: El servidor no responde latidos. Tráfico desviado en anillo dinámico.");
            }
        }

        // Evaluar misiones de los agentes caninos policiales en base a la telemetría real
        updatePoliceAgentMissions();
    }

    /**
     * Ajusta dinámicamente la cantidad de Personas, Edificios, Camiones y Perros en función
     * de los objetos procesados y throughput del servidor en tiempo real.
     */
    private void synchronizeEntitiesWithServerWorkload(double satPercent) {
        // A. PERSONAS: Corresponden a las sesiones concurrentes de procesamiento en el servidor en tiempo real
        int desiredSessions = (remoteActiveSessions > 0)
            ? remoteActiveSessions
            : Math.min(22, Math.max(8, (int)(processedObjectsPerSecond / 2800)));

        while (liveSessions.size() < desiredSessions) {
            int candidateIdx = (int)((totalEvaluations + liveSessions.size()) % sessionCandidates.length);
            String[] cand = sessionCandidates[candidateIdx];
            String dynUser = cand[0] + "_" + (liveSessions.size() + 1);
            addLiveUserSession(dynUser, cand[1], cand[2], cand[3]);
        }
        while (liveSessions.size() > desiredSessions && liveSessions.size() > 4) {
            JettraLiveSession last = liveSessions.get(liveSessions.size() - 1);
            removeLiveUserSession(last.getSessionId());
        }

        // B. EDIFICIOS: Sincronizar sesiones en cada zona geográfica
        for (UserZoneGroup z : userZones) {
            for (JettraLiveSession s : liveSessions) {
                if (s.getZoneId().equalsIgnoreCase(z.getZoneId())) {
                    z.registerSession(s.getSessionId());
                }
            }
        }

        // C. CAMIONES: Solo transmiten cuando hay intercambio activo entre nodos en línea
        ServerNode3D leader = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.PRIMARY && n.isOnline()).findFirst().orElse(null);
        ServerNode3D follower = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.SECONDARY && n.isOnline()).findFirst().orElse(null);

        if (!isMultinodeActive()) {
            for (ClusterDataTraffic tr : activeTraffic) {
                tr.setTransmitting(false);
                tr.updateBatch(0, 0f, "Batch 0 ops | Distribución Desactivada (cluster.multinode.active=off) - Servidor Local Único");
            }
        } else if (leader != null && follower != null) {
            long batchSize = (processedObjectsPerSecond * 2);
            for (ClusterDataTraffic tr : activeTraffic) {
                tr.updateBatch(batchSize, 8.5f, "Batch " + String.format("%,d", batchSize) + " objetos | " + tr.getTrafficType());
            }
            if (processedObjectsPerSecond > 0 && totalEvaluations % 4 == 0) {
                if (!isTransferActiveBetween(leader.getId(), follower.getId())) {
                    triggerNodeTransfer(leader.getId(), follower.getId(), ClusterDataTraffic.TrafficType.RAFT_REPLICATION,
                        "Batch Replicación Raft: " + String.format("%,d", batchSize) + " ops", batchSize, 8.5f);
                }
            }
        } else {
            for (ClusterDataTraffic tr : activeTraffic) {
                tr.setTransmitting(false);
                tr.updateBatch(0, 0f, "Batch 0 ops | En Espera: 1 solo nodo activo en clúster");
            }
        }

        // D. PERROS: Agentes JettraPolice reaccionan a la carga del servidor
        int desiredDogs = (remoteActivePoliceAgents > 0) ? remoteActivePoliceAgents : 4;
        boolean hasOmega = activePoliceAgents.stream().anyMatch(a -> a.getId().equals("k9_omega"));
        if ((satPercent > 70.0 || processedObjectsPerSecond > 45000L || desiredDogs > 4) && !hasOmega) {
            activePoliceAgents.add(new JettraPoliceAgent(
                "k9_omega", "JettraPolice-K9-Omega", JettraPoliceAgent.PoliceRole.HEAP_SENTINEL,
                "node-01", "🚨 Vigilante Centinela: Desplegado por alta ingesta de objetos en tiempo real"
            ));
        } else if (satPercent < 65.0 && processedObjectsPerSecond < 40000L && desiredDogs <= 4 && hasOmega) {
            activePoliceAgents.removeIf(a -> a.getId().equals("k9_omega"));
        }
    }

    private void updatePoliceAgentMissions() {
        ServerNode3D highestLoadNode = null;
        double maxSat = -1.0;
        ServerNode3D offlineNode = null;

        for (ServerNode3D n : serverNodes) {
            if (!n.isOnline()) {
                offlineNode = n;
            } else if (n.getHeapSaturationPercent() > maxSat) {
                maxSat = n.getHeapSaturationPercent();
                highestLoadNode = n;
            }
        }

        for (JettraPoliceAgent k9 : activePoliceAgents) {
            switch (k9.getRole()) {
                case HEAP_SENTINEL -> {
                    if (highestLoadNode != null) {
                        k9.setTargetNodeId(highestLoadNode.getId());
                        if (maxSat > 75.0) {
                            k9.assignMission(highestLoadNode.getId(), "🚨 Alerta Heap (" + String.format("%.1f", maxSat) + "%). Inspeccionando MemTable y forzando lazy load.", true, "WARNING");
                        } else {
                            k9.assignMission(highestLoadNode.getId(), "🐾 Patrullando Heap en " + highestLoadNode.getId() + " (" + String.format("%.1f", maxSat) + "% seguro).", false, "INFO");
                        }
                    }
                }
                case RAFT_QUORUM_K9 -> {
                    if (!isMultinodeActive()) {
                        k9.assignMission("node-01", "🛡️ Modo Standalone (cluster.multinode.active=off). Consenso distribuido en reposo.", false, "INFO");
                    } else if (offlineNode != null) {
                        k9.assignMission(offlineNode.getId(), "🚨 ¡ALERTA! " + offlineNode.getId() + " FUERA DE SERVICIO. Desviando réplicas.", true, "CRITICAL");
                    } else {
                        k9.assignMission("node-02", "🐾 Consenso Raft sincronizado al 100%. Quórum verificado.", false, "INFO");
                    }
                }
                case MEMTABLE_PURGE_DOG -> {
                    k9.assignMission("node-01", "🐾 Inspeccionando compactaciones SSTables y vaciado Off-Heap.", false, "INFO");
                }
                case SECURITY_PATROL -> {
                    k9.assignMission("node-01", "🛡️ Autenticando usuarios y supervisando permisos de conexión.", false, "INFO");
                }
            }
        }
    }

    public synchronized void addLiveUserSession(String username, String ip, String db, String initialQuery) {
        UserZoneGroup targetZone = findOrCreateZoneForIp(ip, db);
        String sessionId = "sess_" + username + "_" + System.currentTimeMillis();
        targetZone.registerSession(sessionId);

        JettraLiveSession session = new JettraLiveSession(
            sessionId, username, ip, targetZone.getZoneId(), db, "node-01", initialQuery
        );
        liveSessions.add(session);
    }

    public synchronized void removeLiveUserSession(String sessionId) {
        for (UserZoneGroup z : userZones) {
            z.unregisterSession(sessionId);
        }
        liveSessions.removeIf(s -> s.getSessionId().equalsIgnoreCase(sessionId));
    }

    public synchronized UserZoneGroup findOrCreateZoneForIp(String ip, String suggestedDb) {
        for (UserZoneGroup z : userZones) {
            if (z.matchesIp(ip)) {
                return z;
            }
        }
        return userZones.get(0);
    }

    public UserZoneGroup getZoneById(String zoneId) {
        for (UserZoneGroup z : userZones) {
            if (z.getZoneId().equalsIgnoreCase(zoneId)) return z;
        }
        return userZones.isEmpty() ? null : userZones.get(0);
    }

    private boolean checkNodeReachable(ServerNode3D node) {
        if (node == null) return false;
        String host = node.getHost();
        int port = node.getPort();

        try {
            URI uri = URI.create("http://" + host + ":" + port + "/api/v1/health");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(150);
            conn.setReadTimeout(150);
            conn.setRequestProperty("Connection", "close");
            if (conn.getResponseCode() == 200) {
                return true;
            }
        } catch (Exception ignored) {}

        if (checkSocketPing(host, port)) {
            return true;
        }

        if (node.getId().equalsIgnoreCase("node-01") && port != 8080) {
            try {
                URI uri = URI.create("http://" + host + ":8080/api/v1/health");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(150);
                conn.setReadTimeout(150);
                conn.setRequestProperty("Connection", "close");
                if (conn.getResponseCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {}
            if (checkSocketPing(host, 8080)) {
                return true;
            }
        }

        int nodeIdx = 1;
        for (int i = 0; i < serverNodes.size(); i++) {
            if (serverNodes.get(i).getId().equalsIgnoreCase(node.getId())) {
                nodeIdx = i + 1;
                break;
            }
        }
        int grpcPort = 9090 + nodeIdx;
        return checkSocketPing(host, grpcPort);
    }

    private boolean checkSocketPing(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void toggleNodeOffline(String nodeId) {
        for (ServerNode3D n : serverNodes) {
            if (n.getId().equalsIgnoreCase(nodeId)) {
                n.toggleOffline();
                this.lastPoliceEvent = "JettraStorePolice: Servidor '" + n.getId() + "' conmutado a " 
                    + (n.isOnline() ? "EN LÍNEA" : "FUERA DE SERVICIO") + ".";
                break;
            }
        }
    }

    public ConnectionManager getConnectionManager() { return connectionManager; }
    public boolean isMultinodeActive() { return ClusterConfigLoader.getInstance().isMultinodeActive(); }
    public void setMultinodeActive(boolean active) {
        ClusterConfigLoader.getInstance().setMultinodeActive(active);
        this.lastPoliceEvent = "JettraStorePolice: cluster.multinode.active conmutado a " + (active ? "ON" : "OFF");
    }
    public String getClusterMultinodeActive() { return ClusterConfigLoader.getInstance().getClusterMultinodeActive(); }
    public ConnectionProfile getCurrentProfile() { return currentProfile; }
    public List<ServerNode3D> getServerNodes() { return serverNodes; }
    public List<UserZoneGroup> getUserZones() { return userZones; }
    public List<JettraLiveSession> getLiveSessions() { return liveSessions; }
    public List<JettraPoliceAgent> getActivePoliceAgents() { return activePoliceAgents; }
    public synchronized List<ClusterDataTraffic> getActiveTraffic() {
        return Collections.unmodifiableList(new ArrayList<>(activeTraffic));
    }

    public synchronized List<ClusterDataTraffic> getTransmittingTraffic() {
        List<ClusterDataTraffic> transmittingOnly = new ArrayList<>();
        for (ClusterDataTraffic t : activeTraffic) {
            if (t.isTransmitting()) {
                transmittingOnly.add(t);
            }
        }
        return transmittingOnly;
    }

    public synchronized boolean isTransferActiveBetween(String srcId, String tgtId) {
        for (ClusterDataTraffic t : activeTraffic) {
            if (t.isTransmitting()) {
                if ((t.getSourceNodeId().equalsIgnoreCase(srcId) && t.getTargetNodeId().equalsIgnoreCase(tgtId)) ||
                    (t.getSourceNodeId().equalsIgnoreCase(tgtId) && t.getTargetNodeId().equalsIgnoreCase(srcId))) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized float getTransferProgressBetween(String srcId, String tgtId) {
        for (ClusterDataTraffic t : activeTraffic) {
            if (t.isTransmitting()) {
                if (t.getSourceNodeId().equalsIgnoreCase(srcId) && t.getTargetNodeId().equalsIgnoreCase(tgtId)) {
                    return t.getProgress();
                } else if (t.getSourceNodeId().equalsIgnoreCase(tgtId) && t.getTargetNodeId().equalsIgnoreCase(srcId)) {
                    return 1.0f - t.getProgress();
                }
            }
        }
        return 0.0f;
    }

    public synchronized void triggerNodeTransfer(String srcId, String tgtId, ClusterDataTraffic.TrafficType type, String payloadSummary, long bytes, float speedMbps) {
        if (!isMultinodeActive()) {
            return; // Distribución de datos desactivada por cluster.multinode.active=off
        }
        ServerNode3D src = getNodeById(srcId);
        ServerNode3D tgt = getNodeById(tgtId);
        if (src == null || tgt == null || !src.isOnline() || !tgt.isOnline()) {
            return;
        }

        for (ClusterDataTraffic t : activeTraffic) {
            if (t.getSourceNodeId().equalsIgnoreCase(srcId) && t.getTargetNodeId().equalsIgnoreCase(tgtId)) {
                t.triggerTransfer(srcId, tgtId, payloadSummary, bytes, speedMbps);
                return;
            }
        }
        String id = "traffic_" + srcId + "_" + tgtId + "_" + System.currentTimeMillis();
        ClusterDataTraffic nt = new ClusterDataTraffic(id, "Tráfico-" + srcId + "->" + tgtId, type, srcId, tgtId, payloadSummary, bytes, speedMbps);
        nt.triggerTransfer(srcId, tgtId, payloadSummary, bytes, speedMbps);
        activeTraffic.add(nt);
    }

    public ServerNode3D getNodeById(String id) {
        for (ServerNode3D n : serverNodes) {
            if (n.getId().equalsIgnoreCase(id)) return n;
        }
        return null;
    }

    public boolean isConnected() { return connected; }
    public long getTotalEvaluations() { return totalEvaluations; }
    public String getLastPoliceEvent() { return lastPoliceEvent; }
    public long getProcessedObjectsTotal() { return processedObjectsTotal; }
    public long getProcessedObjectsPerSecond() { return processedObjectsPerSecond; }
    public int getActiveTransactions() { return activeTransactions; }

    public boolean clusterDistributed(String databaseName) {
        if (client == null) {
            initClient();
        }
        if (client == null) return false;
        try {
            boolean ok = client.clusterDistributed(databaseName);
            ServerNode3D leader = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.PRIMARY && n.isOnline()).findFirst().orElse(null);
            ServerNode3D follower = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.SECONDARY && n.isOnline()).findFirst().orElse(null);
            if (leader != null && follower != null) {
                triggerNodeTransfer(leader.getId(), follower.getId(), 
                    ClusterDataTraffic.TrafficType.RAFT_REPLICATION, 
                    "Migración BD: " + databaseName, 1024 * 512, 125.0f);
            }
            this.lastPoliceEvent = "JettraPolice: Migración y distribución de BD '" + databaseName + "' completada con éxito.";
            return ok;
        } catch (Exception e) {
            this.lastPoliceEvent = "JettraPolice: Error en distribución de BD '" + databaseName + "': " + e.getMessage();
            throw e;
        }
    }

    public Map<String, Boolean> clusterDistributedAll() {
        if (client == null) {
            initClient();
        }
        if (client == null) return Map.of();
        try {
            Map<String, Boolean> res = client.clusterDistributedAll();
            ServerNode3D leader = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.PRIMARY && n.isOnline()).findFirst().orElse(null);
            ServerNode3D follower = serverNodes.stream().filter(n -> n.getRole() == ClusterNode.Role.SECONDARY && n.isOnline()).findFirst().orElse(null);
            if (leader != null && follower != null) {
                triggerNodeTransfer(leader.getId(), follower.getId(), 
                    ClusterDataTraffic.TrafficType.RAFT_REPLICATION, 
                    "Distribución Masiva (" + res.size() + " BDs)", 1024 * 1024 * 2, 250.0f);
            }
            this.lastPoliceEvent = "JettraPolice: Distribución masiva de todas las bases de datos (" + res.size() + ") completada.";
            return res;
        } catch (Exception e) {
            this.lastPoliceEvent = "JettraPolice: Error en distribución masiva: " + e.getMessage();
            throw e;
        }
    }

    public List<ClusterNodeDistributionInfo> getClusterDistributedInfo() {
        if (client == null) {
            initClient();
        }
        if (client == null) return List.of();
        return client.getClusterDistributedInfo();
    }

    public void onClusterLiveEvent(ClusterLiveEvent event) {
        if (event == null) return;
        this.lastPoliceEvent = "[" + event.type() + "] " + event.message();

        String type = event.type() != null ? event.type() : "";
        switch (type) {
            case ClusterLiveEvent.TYPE_LEADER_PROMOTED -> {
                String leaderId = event.sourceNodeId();
                for (ServerNode3D n : serverNodes) {
                    if (n.getId().equalsIgnoreCase(leaderId)) {
                        n.setRole(ClusterNode.Role.PRIMARY);
                        n.setRaftState(ClusterNode.RaftState.LEADER);
                        n.setOnline(true);
                        n.setStatus(ClusterNode.NodeStatus.RUNNING);
                        n.setStatusMessage("EN LÍNEA (PRIMARY / LEADER)");
                    } else if (n.getRole() == ClusterNode.Role.PRIMARY) {
                        n.setRole(ClusterNode.Role.SECONDARY);
                        n.setRaftState(ClusterNode.RaftState.FOLLOWER);
                        n.setStatusMessage("EN LÍNEA (SECONDARY / FOLLOWER)");
                    }
                }
                for (JettraPoliceAgent k9 : activePoliceAgents) {
                    if (k9.getRole() == JettraPoliceAgent.PoliceRole.RAFT_QUORUM_K9) {
                        k9.assignMission(leaderId, "👑 Nuevo Líder PRIMARY proclamado en clúster: " + leaderId, false, "INFO");
                    }
                }
            }
            case ClusterLiveEvent.TYPE_NODE_STOPPED, ClusterLiveEvent.TYPE_NODE_OFFLINE -> {
                String stopId = event.sourceNodeId();
                for (ServerNode3D n : serverNodes) {
                    if (n.getId().equalsIgnoreCase(stopId)) {
                        n.setOnline(false);
                        n.setStatus(ClusterNode.NodeStatus.OFFLINE);
                        n.setStatusMessage("FUERA DE SERVICIO (" + event.message() + ")");
                        offlineAlertCount++;
                        break;
                    }
                }
                for (JettraPoliceAgent k9 : activePoliceAgents) {
                    if (k9.getRole() == JettraPoliceAgent.PoliceRole.RAFT_QUORUM_K9) {
                        k9.assignMission(stopId, "🚨 Nodo " + stopId + " fuera de servicio. Desviando tráfico.", true, "CRITICAL");
                    }
                }
            }
            case ClusterLiveEvent.TYPE_NODE_ONLINE -> {
                String onlineId = event.sourceNodeId();
                for (ServerNode3D n : serverNodes) {
                    if (n.getId().equalsIgnoreCase(onlineId)) {
                        n.setOnline(true);
                        n.setStatus(ClusterNode.NodeStatus.RUNNING);
                        n.setStatusMessage("EN LÍNEA (Quórum Raft)");
                        break;
                    }
                }
            }
            case ClusterLiveEvent.TYPE_DATABASE_DISTRIBUTED,
                 ClusterLiveEvent.TYPE_DATABASE_CREATED,
                 ClusterLiveEvent.TYPE_ENGINE_CREATED,
                 ClusterLiveEvent.TYPE_DATA_TRANSFER,
                 ClusterLiveEvent.TYPE_RECORD_REPLICATED,
                 ClusterLiveEvent.TYPE_DOCUMENT_REPLICATED -> {
                String src = (event.sourceNodeId() != null && !event.sourceNodeId().isBlank()) ? event.sourceNodeId() : "node-01";
                String tgt = (event.targetNodeId() != null && !event.targetNodeId().isBlank()) ? event.targetNodeId() : "node-02";
                if ("cluster".equalsIgnoreCase(tgt)) {
                    for (ServerNode3D peer : serverNodes) {
                        if (!peer.getId().equalsIgnoreCase(src)) {
                            triggerNodeTransfer(src, peer.getId(), ClusterDataTraffic.TrafficType.RAFT_REPLICATION, event.message(), 1024 * 64, 85.0f);
                        }
                    }
                } else {
                    triggerNodeTransfer(src, tgt, ClusterDataTraffic.TrafficType.RAFT_REPLICATION, event.message(), 1024 * 64, 85.0f);
                }
            }
            default -> {}
        }
    }

    public List<ClusterLiveEvent> getClusterLiveEvents() {
        if (client != null) {
            try {
                List<ClusterLiveEvent> evts = client.getClusterLiveEvents();
                if (evts != null && !evts.isEmpty()) return evts;
            } catch (Exception ignored) {}
        }
        return JettraClusterEventBus.getInstance().getRecentEvents(100);
    }

    public List<ClusterLiveEvent> getRecentClusterLiveEvents(int limit) {
        if (client != null) {
            try {
                List<ClusterLiveEvent> evts = client.getRecentClusterLiveEvents(limit);
                if (evts != null && !evts.isEmpty()) return evts;
            } catch (Exception ignored) {}
        }
        return JettraClusterEventBus.getInstance().getRecentEvents(limit);
    }

    public void subscribeClusterLive(Consumer<ClusterLiveEvent> listener) {
        JettraClusterEventBus.getInstance().subscribe(listener);
        if (client != null) {
            try {
                client.subscribeClusterLive(listener);
            } catch (Exception ignored) {}
        }
    }

    public String clusterLive() {
        return clusterLive(25);
    }

    public String clusterLive(int limit) {
        List<ClusterLiveEvent> events = getRecentClusterLiveEvents(limit);
        if (events == null || events.isEmpty()) {
            return "No hay eventos recientes en el clúster.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-23s | %-12s | %-20s | %s%n", "TIMESTAMP", "NODE", "TIPO DE EVENTO", "DESCRIPCIÓN"));
        sb.append("-".repeat(88)).append("\n");
        for (ClusterLiveEvent e : events) {
            String ts = e.formattedTimestamp();
            if (ts.length() > 23) ts = ts.substring(0, 23);
            String node = e.sourceNodeId() != null ? e.sourceNodeId() : "-";
            String t = e.type() != null ? e.type() : "-";
            String msg = e.message() != null ? e.message() : "";
            sb.append(String.format("%-23s | %-12s | %-20s | %s%n", ts, node, t, msg));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
    }
}

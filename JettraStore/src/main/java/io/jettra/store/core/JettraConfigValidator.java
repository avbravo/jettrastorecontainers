package io.jettra.store.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validador y gestor del ciclo de arranque de configuración para JettraStore.
 * 
 * Funcionalidades:
 * 1. Lee 'cluster.node.id' y 'cluster.node.role' desde microprofile-config.properties para identificar
 *    la sección 'cluster.node.<N>.*' correspondiente dentro de cluster.config.
 * 2. Obtiene del nodo correspondiente en cluster.config:
 *    - cluster.node.<N>.role
 *    - cluster.node.<N>.ip
 *    - cluster.node.<N>.grpc.port (reemplaza a jettra.network.grpc.port)
 *    - cluster.node.<N>.rest.port (reemplaza a jettra.network.rest.port)
 *    - cluster.node.<N>.storage.path (reemplaza a jettra.storage.path, storage.path, data.dir
 *      y sirve de base '<storage.path>/indexes' para jettra.index.storage.path).
 * 3. Si 'cluster.node.id' indicado en microprofile-config.properties no existe en cluster.config,
 *    emite un mensaje de advertencia y error crítico e impide la ejecución de JettraStore.
 * 4. Permite registrar dinámicamente nuevos nodos en cluster.config.
 */
public final class JettraConfigValidator {

    public static final String DEFAULT_CONFIG_DIR = "config";
    public static final String DEFAULT_MICROPROFILE_CONFIG_PATH = "config/microprofile-config.properties";
    public static final String DEFAULT_CLUSTER_CONFIG_PATH = "config/cluster.config";
    public static final String DEFAULT_DATABASE_PROPERTIES_PATH = DEFAULT_MICROPROFILE_CONFIG_PATH;
    public static final String DEFAULT_JETTRA_CONFIG_PATH = DEFAULT_CLUSTER_CONFIG_PATH;
    public static final String DEFAULT_JETTRASERVER_CONFIG_PATH = DEFAULT_MICROPROFILE_CONFIG_PATH;

    public record ClusterNodeInfo(
        String id,
        String role,
        String ip,
        int grpcPort,
        int restPort,
        String storagePath,
        String nodeNumber
    ) {
        public ClusterNodeInfo(String id, String role, String ip, int grpcPort, int restPort, String storagePath) {
            this(id, role, ip, grpcPort, restPort, storagePath, extractNodeNumber(id));
        }
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        private final String notification;

        public ValidationResult(boolean valid, List<String> errors, String notification) {
            this(valid, errors, List.of(), notification);
        }

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings, String notification) {
            this.valid = valid;
            this.errors = errors != null ? Collections.unmodifiableList(errors) : List.of();
            this.warnings = warnings != null ? Collections.unmodifiableList(warnings) : List.of();
            this.notification = notification != null ? notification : "";
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        public String getNotification() { return notification; }
    }

    public static String extractNodeNumber(String nodeId) {
        String num = extractNodeNumberOrNull(nodeId);
        return num != null ? num : "1";
    }

    public static String extractNodeNumberOrNull(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        var m = Pattern.compile("\\d+").matcher(nodeId.trim());
        if (m.find()) {
            try {
                return String.valueOf(Integer.parseInt(m.group()));
            } catch (Exception e) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * Busca un nodo en la lista de nodos de cluster.config que coincida con el identificador configurado
     * en microprofile-config.properties (ej. 'node-01', 'node-1', '01' o '1').
     */
    public static ClusterNodeInfo findNodeInCluster(List<ClusterNodeInfo> nodes, String configuredNodeId) {
        if (nodes == null || nodes.isEmpty() || configuredNodeId == null || configuredNodeId.isBlank()) {
            return null;
        }
        String trimmed = configuredNodeId.trim();

        // 1. Coincidencia exacta por ID (ej. "node-01")
        for (ClusterNodeInfo node : nodes) {
            if (node.id() != null && node.id().equalsIgnoreCase(trimmed)) {
                return node;
            }
        }

        // 2. Coincidencia por número de nodo cuando se especifica como número ("01", "1") o formato "node-<N>"
        String extractedNum = extractNodeNumberOrNull(trimmed);
        if (extractedNum != null && (trimmed.matches("\\d+") || trimmed.toLowerCase(Locale.ROOT).startsWith("node"))) {
            for (ClusterNodeInfo node : nodes) {
                if (extractedNum.equals(node.nodeNumber())) {
                    return node;
                }
                String nodeIdNum = extractNodeNumberOrNull(node.id());
                if (extractedNum.equals(nodeIdNum)) {
                    return node;
                }
            }
        }

        return null;
    }

    private JettraConfigValidator() {}

    /**
     * Comprueba si el proceso actual se ejecuta dentro de un entorno Docker / Docker Compose.
     */
    public static boolean isDockerEnvironment() {
        String dc = System.getenv("JETTRA_DOCKER_COMPOSE");
        if ("true".equalsIgnoreCase(dc) || "1".equals(dc)) return true;

        String docker = System.getenv("JETTRA_DOCKER");
        if ("true".equalsIgnoreCase(docker) || "1".equals(docker)) return true;

        if (Files.exists(Path.of("/.dockerenv"))) return true;

        Path cgroup = Path.of("/proc/1/cgroup");
        if (Files.exists(cgroup)) {
            try {
                String c = Files.readString(cgroup);
                if (c.contains("docker") || c.contains("containerd") || c.contains("kubepods")) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        if (System.getenv("JETTRA_CLUSTER_PEERS") != null && "/jettra/data".equals(System.getenv("JETTRA_STORAGE_PATH"))) {
            return true;
        }

        return false;
    }

    /**
     * Localiza el archivo de configuración MicroProfile (microprofile-config.properties).
     */
    public static Path locateMicroProfileConfigFile() {
        String[] sysKeys = {
            "microprofile.config.file",
            "mp.config.file",
            "microprofile-config.file",
            "microprofile-config.properties",
            "microprofile.config.path",
            "database.properties.path",
            "jettra.database.properties.path",
            "jettra.database.properties",
            "database.properties",
            "jettra.database.path",
            "jettraserver.config.path",
            "jettraserver.config"
        };
        for (String k : sysKeys) {
            String sysProp = System.getProperty(k);
            if (sysProp != null && !sysProp.isBlank()) {
                Path p = Path.of(sysProp.trim());
                if (Files.exists(p)) return p;
            }
        }

        String[] envKeys = {
            "MP_CONFIG_FILE",
            "MICROPROFILE_CONFIG_FILE",
            "DATABASE_PROPERTIES_PATH",
            "JETTRA_DATABASE_PROPERTIES_PATH",
            "DATABASE_PROPERTIES",
            "JETTRA_DATABASE_PROPERTIES",
            "JETTRASERVER_CONFIG_PATH",
            "JETTRASERVER_CONFIG"
        };
        for (String k : envKeys) {
            String env = System.getenv(k);
            if (env != null && !env.isBlank()) {
                Path p = Path.of(env.trim());
                if (Files.exists(p)) return p;
            }
        }

        // Si se especificó cluster.config.path o jettra.config.path, buscar en la misma carpeta
        String cfgPathStr = System.getProperty("cluster.config.path");
        if (cfgPathStr == null || cfgPathStr.isBlank()) cfgPathStr = System.getProperty("cluster.config");
        if (cfgPathStr == null || cfgPathStr.isBlank()) cfgPathStr = System.getProperty("jettra.config.path");
        if (cfgPathStr == null || cfgPathStr.isBlank()) cfgPathStr = System.getProperty("jettra.config");
        if (cfgPathStr == null || cfgPathStr.isBlank()) cfgPathStr = System.getenv("CLUSTER_CONFIG_PATH");
        if (cfgPathStr == null || cfgPathStr.isBlank()) cfgPathStr = System.getenv("JETTRA_CONFIG_PATH");

        if (cfgPathStr != null && !cfgPathStr.isBlank()) {
            try {
                Path cfgPath = Path.of(cfgPathStr.trim()).toAbsolutePath().normalize();
                Path parent = cfgPath.getParent();
                if (parent != null) {
                    Path siblingMp = parent.resolve("microprofile-config.properties");
                    if (Files.exists(siblingMp)) return siblingMp;
                    Path siblingCfgMp = parent.resolve("config/microprofile-config.properties");
                    if (Files.exists(siblingCfgMp)) return siblingCfgMp;
                    Path siblingMetaMp = parent.resolve("META-INF/microprofile-config.properties");
                    if (Files.exists(siblingMetaMp)) return siblingMetaMp;
                }
            } catch (Exception ignored) {}
        }

        Path[] candidates = {
            Path.of(DEFAULT_MICROPROFILE_CONFIG_PATH),
            Path.of("microprofile-config.properties"),
            Path.of("src/main/resources/META-INF/microprofile-config.properties"),
            Path.of("src/main/resources/microprofile-config.properties"),
            Path.of("../config/microprofile-config.properties"),
            Path.of("../microprofile-config.properties"),
            Path.of("config/jettraserver.config"),
            Path.of("jettraserver.config"),
            Path.of("../config/jettraserver.config"),
            Path.of("../jettraserver.config")
        };
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }

        return null;
    }

    /**
     * Localiza el archivo microprofile-config.properties (compatibilidad hacia atrás).
     */
    public static Path locateDatabasePropertiesFile() {
        return locateMicroProfileConfigFile();
    }

    /**
     * Localiza el archivo cluster.config (o legacy jettra.config) en las rutas estándar o configuradas.
     */
    public static Path locateClusterConfigFile() {
        String[] sysKeys = {
            "cluster.config.path",
            "cluster.config",
            "jettra.cluster.config.path",
            "jettra.cluster.config",
            "jettra.config.path",
            "jettra.config"
        };
        for (String k : sysKeys) {
            String sysProp = System.getProperty(k);
            if (sysProp != null && !sysProp.isBlank()) {
                Path p = Path.of(sysProp.trim());
                if (Files.exists(p)) return p;
            }
        }

        String[] envKeys = {
            "CLUSTER_CONFIG_PATH",
            "CLUSTER_CONFIG",
            "JETTRA_CLUSTER_CONFIG_PATH",
            "JETTRA_CLUSTER_CONFIG",
            "JETTRA_CONFIG_PATH",
            "JETTRA_CONFIG"
        };
        for (String k : envKeys) {
            String env = System.getenv(k);
            if (env != null && !env.isBlank()) {
                Path p = Path.of(env.trim());
                if (Files.exists(p)) return p;
            }
        }

        // Si se especificó microprofile.config.file o database.properties.path, buscar cluster.config en la misma carpeta
        String dbPathStr = System.getProperty("microprofile.config.file");
        if (dbPathStr == null || dbPathStr.isBlank()) dbPathStr = System.getProperty("database.properties.path");
        if (dbPathStr == null || dbPathStr.isBlank()) dbPathStr = System.getProperty("jettra.database.properties.path");
        if (dbPathStr != null && !dbPathStr.isBlank()) {
            try {
                Path dbPath = Path.of(dbPathStr.trim()).toAbsolutePath().normalize();
                Path parent = dbPath.getParent();
                if (parent != null) {
                    Path siblingCluster = parent.resolve("cluster.config");
                    if (Files.exists(siblingCluster)) return siblingCluster;
                    Path siblingCfgCluster = parent.resolve("config/cluster.config");
                    if (Files.exists(siblingCfgCluster)) return siblingCfgCluster;

                    Path sibling = parent.resolve("jettra.config");
                    if (Files.exists(sibling)) return sibling;
                    Path siblingCfg = parent.resolve("config/jettra.config");
                    if (Files.exists(siblingCfg)) return siblingCfg;
                }
            } catch (Exception ignored) {}
        }

        Path[] candidates = {
            Path.of(DEFAULT_CLUSTER_CONFIG_PATH),
            Path.of("cluster.config"),
            Path.of("../config/cluster.config"),
            Path.of("../cluster.config"),
            Path.of("config/jettra.config"),
            Path.of("jettra.config"),
            Path.of("../config/jettra.config"),
            Path.of("../jettra.config")
        };
        for (Path c : candidates) {
            if (Files.exists(c)) return c;
        }

        return null;
    }

    /**
     * Localiza el archivo de topología de clúster (compatibilidad hacia atrás).
     */
    public static Path locateJettraConfigFile() {
        return locateClusterConfigFile();
    }

    /**
     * Localiza el archivo de servidor (compatibilidad hacia atrás con JettraServer).
     */
    public static Path locateJettraServerConfigFile() {
        return locateMicroProfileConfigFile();
    }

    /**
     * Asegura que los archivos de configuración existan en disco. Si no existen, los genera
     * de manera automática en la carpeta 'config/' con los valores y la sintaxis recomendada.
     */
    public static void ensureConfigFilesExist() {
        Path dbPath = locateMicroProfileConfigFile();
        if (dbPath == null) {
            Path target = Path.of(DEFAULT_MICROPROFILE_CONFIG_PATH);
            generateDefaultMicroProfileConfig(target);
        }

        Path clusterPath = locateClusterConfigFile();
        if (clusterPath == null) {
            Path target = Path.of(DEFAULT_CLUSTER_CONFIG_PATH);
            generateDefaultClusterConfig(target);
        }
    }

    /**
     * Genera automáticamente el archivo microprofile-config.properties con la configuración predeterminada y recomendada
     * (sin duplicidad de parámetros con cluster.config).
     */
    public static void generateDefaultMicroProfileConfig(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            String content = """
# ==============================================================================
# ECLIPSE MICROPROFILE CONFIGURATION (MicroProfile Config 3.1)
# JettraStore Multi-Model Distributed Database (Java 25+ & Project Loom)
# ==============================================================================

# Perfil activo de configuración MicroProfile
mp.config.profile=production

# Identificador y Rol del Nodo Local en el Clúster (enlaza con cluster.config)
cluster.node.id=node-01
cluster.node.role=PRIMARY

# Topología Multinodo y Consenso
cluster.multinode.active=on

# Modo de Almacenamiento (OFF_HEAP, DISK_MEMORY, JVM_RAM)
jettra.storage.mode=OFF_HEAP
storage.mode=OFF_HEAP
jettra.storage.file.extension=.jettra

# Red y Virtual Threads (IP, puertos gRPC/REST y storage.path se obtienen de cluster.config)
contextpath=/
server.contextpath=/
jettra.network.virtualthreads.enabled=true
server.virtual-threads=true

# MemTable y Memoria Off-Heap (Panama FFM)
jettra.storage.memtable.size.mb=128
jettra.storage.ram.global.limit.mb=2048
jettra.storage.offheap.direct=true
storage.offheap.page-size=65536

# Umbrales del Motor de Anillo Distribuido
jettra.ring.saturation.threshold.percent=85
jettra.ring.release.target.percent=45

# JettraPolice (Supervisión Preventiva Anti-OOM)
jettrapolice.active=true
jettrapolice.interval.ms=500
jettrapolice.ram.warning.threshold=75
jettrapolice.disk.warning.threshold=90
jettrapolice.ram.critical.threshold=85
jettrapolice.auto.pagination.enabled=true
jettrapolice.max.safe.batch.size=100

# Índices y Optimización de Consultas
jettra.index.initial.capacity=65536
jettra.index.max.inmemory.keys=100000
jettra.index.compact.storage=true
jettra.storage.autoflush.batch.size=50000
jettra.query.default.limit=50
jettra.query.max.limit=5000
jettra.query.pagesize=50

# Seguridad Criptográfica y JWT
jettra.security.jwt.algorithm=Ed25519
jettra.security.jwt.expiration.seconds=86400
jettra.security.jwt.issuer=jettra-store-authority
jwt.secret=jettra_store_super_secure_jwt_key_2026
jwt.expiration-millis=86400000

# Superusuario por Defecto
jettra.security.default.admin.username=admin
jettra.security.default.admin.password=admin-jettra

# Métricas y MicroProfile
jmh.metrics.active=false
mp.health.default.readiness.empty.response=UP
mp.metrics.appName=JettraStore
mp.metrics.tags=platform=JettraStore,runtime=Java25,engine=PanamaFFM
cors.enabled=true
""";
            Files.writeString(target, content, StandardCharsets.UTF_8);
            System.out.printf("[JettraStore] Archivo de configuración '%s' generado automáticamente con éxito.%n", target);
        } catch (IOException e) {
            System.err.printf("[JettraStore] Error al generar archivo '%s': %s%n", target, e.getMessage());
        }
    }

    /**
     * Genera automáticamente el archivo cluster.config con la topología de clúster recomendada.
     */
    public static void generateDefaultClusterConfig(Path target) {
        generateDefaultJettraConfig(target);
    }

    /**
     * Genera automáticamente el archivo jettraserver.config (compatibilidad).
     */
    public static void generateDefaultJettraServerConfig(Path target) {
        generateDefaultMicroProfileConfig(target);
    }

    /**
     * @deprecated Usar {@link #generateDefaultMicroProfileConfig(Path)}.
     */
    @Deprecated
    public static void generateDefaultDatabaseProperties(Path target) {
        generateDefaultMicroProfileConfig(target);
    }

    /**
     * Genera automáticamente el archivo cluster.config / jettra.config con la topología de clúster recomendada.
     */
    public static void generateDefaultJettraConfig(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            String content = """
################################################################################
# JettraStore Cluster Topology Configuration (cluster.config)
# Topología de Nodos para Consenso Raft y Dynamic Ring Discovery
################################################################################

cluster.name = jettra-production-cluster
cluster.consensus.protocol = RAFT
cluster.ring.enabled = true
cluster.heartbeat.interval.ms = 150
cluster.election.timeout.ms = 300

# ==============================================================================
# NODO 1: NODO PRINCIPAL / LÍDER (Primary)
# ==============================================================================
cluster.node.1.id = node-01
cluster.node.1.role = PRIMARY
cluster.node.1.ip = 127.0.0.1
cluster.node.1.grpc.port = 9091
cluster.node.1.rest.port = 8082
cluster.node.1.storage.path = ~/jettra/data

# ==============================================================================
# NODO 2: NODO SECUNDARIO / SEGUIDOR 1 (Secondary)
# ==============================================================================
cluster.node.2.id = node-02
cluster.node.2.role = SECONDARY
cluster.node.2.ip = 127.0.0.1
cluster.node.2.grpc.port = 9092
cluster.node.2.rest.port = 8083
cluster.node.2.storage.path = ~/jettra/data

# ==============================================================================
# NODO 3: NODO SECUNDARIO / SEGUIDOR 2 (Secondary)
# ==============================================================================
cluster.node.3.id = node-03
cluster.node.3.role = SECONDARY
cluster.node.3.ip = 127.0.0.1
cluster.node.3.grpc.port = 9093
cluster.node.3.rest.port = 8084
cluster.node.3.storage.path = ~/jettra/data

# Asignación de Capacidad de Índices y Buffers en Clúster
cluster.index.initial.capacity = 65536
cluster.index.max.inmemory.keys = 100000
""";
            Files.writeString(target, content, StandardCharsets.UTF_8);
            System.out.printf("[JettraStore] Archivo de configuración '%s' generado automáticamente con éxito.%n", target);
        } catch (IOException e) {
            System.err.printf("[JettraStore] Error al generar archivo '%s': %s%n", target, e.getMessage());
        }
    }

    /**
     * Realiza la validación cruzada estricta entre las propiedades de microprofile-config.properties y cluster.config.
     */
    public static ValidationResult validate(Properties dbProps, Properties clusterProps) {
        if (isDockerEnvironment()) {
            return new ValidationResult(true, List.of(), 
                "[JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada en docker-compose y variables de entorno.");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Extraer los nodos del clúster definidos en cluster.config
        List<ClusterNodeInfo> nodes = parseClusterNodes(clusterProps);
        List<Integer> nodeGrpcPorts = new ArrayList<>();
        List<Integer> nodeRestPorts = new ArrayList<>();

        for (ClusterNodeInfo node : nodes) {
            nodeGrpcPorts.add(node.grpcPort());
            nodeRestPorts.add(node.restPort());
        }

        // 1. Verificar identificador de nodo (cluster.node.id en microprofile-config.properties)
        String configuredNodeId = getPropOrEnv(dbProps, 
            new String[]{"cluster.node.id", "jettra.node.id", "jettra.cluster.node.id", "node.id"}, 
            new String[]{"CLUSTER_NODE_ID", "JETTRA_NODE_ID", "JETTRA_CLUSTER_NODE_ID"}, "").trim();

        ClusterNodeInfo targetNode = null;
        if (!configuredNodeId.isEmpty() && !nodes.isEmpty()) {
            targetNode = findNodeInCluster(nodes, configuredNodeId);
            if (targetNode == null) {
                List<String> validIds = nodes.stream().map(ClusterNodeInfo::id).toList();
                String warnAndErrMsg = String.format(
                    "ADVERTENCIA: El identificador de nodo '%s' indicado en 'cluster.node.id' (microprofile-config.properties) no existe en el archivo cluster.config %s. No se permite ejecutar JettraStore.",
                    configuredNodeId, validIds
                );
                warnings.add(warnAndErrMsg);
                System.err.println("[JettraStore] " + warnAndErrMsg);
                errors.add(warnAndErrMsg);
            }
        } else if (!nodes.isEmpty()) {
            targetNode = nodes.get(0);
        }

        // 2. Resolver y verificar ruta de almacenamiento (storage.path):
        //    Se obtiene directamente de cluster.node.<N>.storage.path en cluster.config.
        //    Si adicionalmente se definió en microprofile-config.properties, se verifica que coincida.
        String explicitDbStoragePath = null;
        for (String sp : new String[]{"storage.path", "jettra.storage.path", "data.dir"}) {
            String val = System.getProperty(sp);
            if (val != null && !val.isBlank()) {
                explicitDbStoragePath = val.trim();
                break;
            }
        }
        if (explicitDbStoragePath == null) {
            for (String ev : new String[]{"STORAGE_PATH", "JETTRA_STORAGE_PATH", "JETTRA_DATA_DIR", "DATA_DIR"}) {
                String val = System.getenv(ev);
                if (val != null && !val.isBlank()) {
                    explicitDbStoragePath = val.trim();
                    break;
                }
            }
        }
        if (explicitDbStoragePath == null && dbProps != null) {
            String spVal = dbProps.getProperty("storage.path");
            String jspVal = dbProps.getProperty("jettra.storage.path");
            String ddVal = dbProps.getProperty("data.dir");

            boolean spCustom = spVal != null && !spVal.isBlank() && !spVal.equals("~/jettra/data") && !spVal.equals("/jettra/data");
            boolean jspCustom = jspVal != null && !jspVal.isBlank() && !jspVal.equals("~/jettra/data") && !jspVal.equals("/jettra/data");

            if (spCustom && !jspCustom) {
                explicitDbStoragePath = spVal.trim();
            } else if (jspCustom && !spCustom) {
                explicitDbStoragePath = jspVal.trim();
            } else if (spVal != null && !spVal.isBlank()) {
                explicitDbStoragePath = spVal.trim();
            } else if (jspVal != null && !jspVal.isBlank()) {
                explicitDbStoragePath = jspVal.trim();
            } else if (ddVal != null && !ddVal.isBlank()) {
                explicitDbStoragePath = ddVal.trim();
            }
        }

        String nodeIdxStr = (targetNode != null && targetNode.nodeNumber() != null)
            ? targetNode.nodeNumber()
            : extractNodeNumber(configuredNodeId);

        String clusterNodeStoragePath = clusterProps != null
            ? clusterProps.getProperty("cluster.node." + nodeIdxStr + ".storage.path",
                targetNode != null ? targetNode.storagePath() : null)
            : (targetNode != null ? targetNode.storagePath() : null);

        String effectiveStoragePath = (explicitDbStoragePath != null && !explicitDbStoragePath.isBlank())
            ? explicitDbStoragePath
            : (clusterNodeStoragePath != null ? clusterNodeStoragePath.trim() : "");

        if (effectiveStoragePath.isEmpty()) {
            errors.add("La propiedad 'cluster.node." + nodeIdxStr + ".storage.path' no está definida en cluster.config.");
        } else {
            if (explicitDbStoragePath != null && !explicitDbStoragePath.isBlank()
                && clusterNodeStoragePath != null && !clusterNodeStoragePath.isBlank()
                && !configuredNodeId.isEmpty()) {
                if (!pathsMatch(explicitDbStoragePath, clusterNodeStoragePath.trim())) {
                    String warnMsg = String.format(
                        "ADVERTENCIA: La propiedad de almacenamiento 'storage.path' / 'jettra.storage.path' ('%s') de microprofile-config.properties no coincide con la propiedad 'cluster.node.%s.storage.path' ('%s') de cluster.config para el nodo %s ('%s').",
                        explicitDbStoragePath, nodeIdxStr, clusterNodeStoragePath.trim(), nodeIdxStr, configuredNodeId
                    );
                    warnings.add(warnMsg);
                    System.err.println("[JettraStore] " + warnMsg);
                }
            }
            // Si el nodo es válido en cluster.config, verificar si existe el directorio cluster.node.<N>.storage.path y crearlo si no existe
            if (errors.isEmpty()) {
                ensureNodeStorageDirectoryExists(effectiveStoragePath);
            }
        }

        // 3. Resolver y verificar puerto gRPC:
        //    Se obtiene de cluster.node.<N>.grpc.port en cluster.config (o se valida si fue especificado explícitamente).
        String explicitGrpcPortStr = getPropOrEnv(dbProps, 
            new String[]{"jettra.network.grpc.port", "jettra.grpc.port", "grpc.port"}, 
            new String[]{"JETTRA_GRPC_PORT", "JETTRA_NETWORK_GRPC_PORT"}, "").trim();
        if (!explicitGrpcPortStr.isEmpty()) {
            try {
                int grpcPort = Integer.parseInt(explicitGrpcPortStr);
                if (!nodeGrpcPorts.contains(grpcPort)) {
                    errors.add(String.format(
                        "El puerto 'jettra.network.grpc.port' (%d) de microprofile-config.properties no coincide con ningún puerto cluster.node.X.grpc.port de cluster.config %s.",
                        grpcPort, nodeGrpcPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de 'jettra.network.grpc.port' ('" + explicitGrpcPortStr + "') no es un número de puerto válido.");
            }
        } else if (targetNode == null || targetNode.grpcPort() <= 0) {
            if (targetNode != null) {
                errors.add("El puerto 'cluster.node." + nodeIdxStr + ".grpc.port' en cluster.config no es válido.");
            }
        }

        // 4. Resolver y verificar puerto REST:
        //    Se obtiene de cluster.node.<N>.rest.port en cluster.config (o se valida si fue especificado explícitamente).
        String explicitRestPortStr = getPropOrEnv(dbProps, 
            new String[]{"server.port", "jettra.network.rest.port", "jettra.rest.port", "rest.port", "jettra.server.port"}, 
            new String[]{"JETTRA_REST_PORT", "JETTRA_NETWORK_REST_PORT", "JETTRA_SERVER_PORT", "SERVER_PORT"}, "").trim();
        if (!explicitRestPortStr.isEmpty()) {
            try {
                int restPort = Integer.parseInt(explicitRestPortStr);
                if (!nodeRestPorts.contains(restPort)) {
                    errors.add(String.format(
                        "El puerto 'server.port' / 'jettra.network.rest.port' (%d) de microprofile-config.properties no coincide con ningún puerto cluster.node.X.rest.port de cluster.config %s.",
                        restPort, nodeRestPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de 'server.port' / 'jettra.network.rest.port' ('" + explicitRestPortStr + "') no es un número de puerto válido.");
            }
        } else if (targetNode == null || targetNode.restPort() <= 0) {
            if (targetNode != null) {
                errors.add("El puerto 'cluster.node." + nodeIdxStr + ".rest.port' en cluster.config no es válido.");
            }
        }

        // 5. Verificar cluster.multinode.active (on / off)
        String multinodeVal = getPropOrEnv(dbProps, "cluster.multinode.active", "JETTRA_CLUSTER_MULTINODE_ACTIVE", "on").trim();
        if (!multinodeVal.equalsIgnoreCase("on") && !multinodeVal.equalsIgnoreCase("off")
            && !multinodeVal.equalsIgnoreCase("true") && !multinodeVal.equalsIgnoreCase("false")) {
            errors.add(String.format(
                "El valor de 'cluster.multinode.active' ('%s') en microprofile-config.properties es inválido. Debe ser 'on' u 'off'.",
                multinodeVal
            ));
        }

        if (errors.isEmpty()) {
            String msg = warnings.isEmpty() 
                ? "[JettraStore] Validación exitosa: microprofile-config.properties y cluster.config están debidamente sincronizados."
                : "[JettraStore] Validación completada con advertencias: " + String.join("; ", warnings);
            return new ValidationResult(true, List.of(), warnings, msg);
        }

        String notification = buildNotificationBanner(errors);
        return new ValidationResult(false, errors, warnings, notification);
    }

    /**
     * Construye un banner visual informativo de advertencia y corrección requerida.
     */
    public static String buildNotificationBanner(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║            ERROR DE CONFIGURACIÓN AL INICIAR JETTRASTORE                     ║\n");
        sb.append("║     (Incompatibilidad entre microprofile-config.properties y cluster.config) ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
        sb.append("Se han detectado las siguientes incoherencias que impiden el inicio seguro:\n\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(String.format("  [%d] %s%n%n", i + 1, errors.get(i)));
        }
        sb.append("────────────────────────────────────────────────────────────────────────────────\n");
        sb.append("DIRECTIVAS DE CORRECCIÓN:\n");
        sb.append(" 1. Verifique que 'cluster.node.id' en microprofile-config.properties exista en\n");
        sb.append("    'cluster.node.<numero-nodo>.id' dentro del archivo cluster.config.\n");
        sb.append(" 2. Verifique que en cluster.config estén definidos 'ip', 'grpc.port', 'rest.port'\n");
        sb.append("    y 'storage.path' para el nodo indicado en 'cluster.node.id'.\n");
        sb.append(" 3. Verifique que 'cluster.multinode.active' sea 'on' u 'off' (on=consenso distribuido, off=servidor local).\n");
        sb.append("────────────────────────────────────────────────────────────────────────────────\n");
        sb.append("[JettraStore] La ejecución se detiene de forma preventiva. Corrija los archivos para iniciar.\n");
        return sb.toString();
    }

    /**
     * Comprueba si dos rutas de almacenamiento son equivalentes, admitiendo tilde (~) y barras separadoras.
     */
    public static boolean pathsMatch(String p1, String p2) {
        if (p1 == null || p2 == null) return false;
        String s1 = normalizePathString(p1);
        String s2 = normalizePathString(p2);
        if (s1.equals(s2)) return true;

        String exp1 = expandUserHome(s1);
        String exp2 = expandUserHome(s2);
        if (exp1.equals(exp2)) return true;

        try {
            return Path.of(exp1).normalize().toAbsolutePath().equals(Path.of(exp2).normalize().toAbsolutePath());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizePathString(String p) {
        String s = p.trim().replace('\\', '/');
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String expandUserHome(String p) {
        if (p.startsWith("~/") || p.startsWith("~" + File.separator) || p.equals("~")) {
            return (System.getProperty("user.home") + p.substring(1)).replace('\\', '/');
        }
        return p;
    }

    /**
     * Verifica si existe el directorio especificado en cluster.node.<N>.storage.path (expandiendo '~' si aplica)
     * y lo crea automáticamente para almacenar las bases de datos y objetos.
     */
    public static Path ensureNodeStorageDirectoryExists(String rawStoragePath) {
        if (rawStoragePath == null || rawStoragePath.isBlank()) {
            return null;
        }
        String resolved = expandUserHome(rawStoragePath.trim());
        Path storageDir = Path.of(resolved);
        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                System.out.printf("[JettraStore] Directorio de almacenamiento '%s' creado automáticamente para el nodo.%n",
                    storageDir.toAbsolutePath().normalize());
            }
            return storageDir;
        } catch (Exception ex) {
            System.err.printf("[JettraStore] Advertencia: No se pudo crear el directorio de almacenamiento '%s': %s%n",
                resolved, ex.getMessage());
            return storageDir;
        }
    }

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

    private static int parseInt(String val, int def) {
        if (val == null || val.isBlank()) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Parsea la lista de nodos configurados en cluster.config (soportando nodos iniciales y nodos agregados dinámicamente).
     */
    public static List<ClusterNodeInfo> parseClusterNodes(Properties props) {
        List<ClusterNodeInfo> list = new ArrayList<>();
        if (props != null) {
            Set<String> discoveredIndices = new LinkedHashSet<>();
            int idx = 1;
            while (props.containsKey("cluster.node." + idx + ".id") 
                   || props.containsKey("cluster.node." + idx + ".storage.path")
                   || props.containsKey("cluster.node." + idx + ".grpc.port")
                   || props.containsKey("cluster.node." + idx + ".rest.port")) {
                discoveredIndices.add(String.valueOf(idx));
                idx++;
            }

            // Buscar también cualquier otro índice de nodo agregado dinámicamente (incluso no consecutivo)
            List<String> extraIndices = new ArrayList<>();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("cluster.node.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length >= 4) {
                        String candidateIdx = parts[2];
                        if (!discoveredIndices.contains(candidateIdx) && !extraIndices.contains(candidateIdx)) {
                            extraIndices.add(candidateIdx);
                        }
                    }
                }
            }
            extraIndices.sort((a, b) -> {
                try {
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });
            discoveredIndices.addAll(extraIndices);

            for (String nodeIdx : discoveredIndices) {
                String prefix = "cluster.node." + nodeIdx + ".";
                int numVal = parseInt(nodeIdx, 0);
                String defaultId = numVal > 0 && numVal < 10 ? "node-0" + numVal : "node-" + nodeIdx;
                String id = props.getProperty(prefix + "id", defaultId).trim();
                String role = props.getProperty(prefix + "role", ("1".equals(nodeIdx) ? "PRIMARY" : "SECONDARY")).trim();
                String ip = props.getProperty(prefix + "ip", "127.0.0.1").trim();
                int grpcPort = parseInt(props.getProperty(prefix + "grpc.port"), 9091);
                int restPort = parseInt(props.getProperty(prefix + "rest.port"), 8080);
                String storagePath = props.getProperty(prefix + "storage.path", "~/jettra/data").trim();

                list.add(new ClusterNodeInfo(id, role, ip, grpcPort, restPort, storagePath, nodeIdx));
            }
        }

        if (list.isEmpty()) {
            list.add(new ClusterNodeInfo("node-01", "PRIMARY", "127.0.0.1", 9091, 8082, "~/jettra/data", "1"));
            list.add(new ClusterNodeInfo("node-02", "SECONDARY", "127.0.0.1", 9091, 8082, "~/jettra/data", "2"));
            list.add(new ClusterNodeInfo("node-03", "SECONDARY", "127.0.0.1", 9091, 8082, "~/jettra/data", "3"));
        }

        return list;
    }

    /**
     * Registra o actualiza dinámicamente un nodo en un objeto Properties de cluster.config.
     */
    public static synchronized ClusterNodeInfo registerDynamicNodeInProperties(
            Properties clusterProps,
            String nodeId,
            String role,
            String ip,
            int grpcPort,
            int restPort,
            String storagePath) {
        if (clusterProps == null || nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("clusterProps y nodeId son requeridos para registrar un nodo dinámico.");
        }
        List<ClusterNodeInfo> existingNodes = parseClusterNodes(clusterProps);
        ClusterNodeInfo existing = findNodeInCluster(existingNodes, nodeId);

        String targetNumber;
        if (existing != null && existing.id().equalsIgnoreCase(nodeId.trim())) {
            targetNumber = existing.nodeNumber();
        } else {
            String candidateNum = extractNodeNumberOrNull(nodeId);
            boolean candidateTaken = candidateNum != null && existingNodes.stream()
                .anyMatch(n -> candidateNum.equals(n.nodeNumber()));
            if (candidateNum != null && !candidateTaken) {
                targetNumber = candidateNum;
            } else {
                int maxIdx = 0;
                for (ClusterNodeInfo n : existingNodes) {
                    int parsed = parseInt(n.nodeNumber(), 0);
                    if (parsed > maxIdx) maxIdx = parsed;
                }
                targetNumber = String.valueOf(maxIdx + 1);
            }
        }

        String resolvedId = nodeId.trim();
        String resolvedRole = (role != null && !role.isBlank()) ? role.trim().toUpperCase(Locale.ROOT) : "SECONDARY";
        String resolvedIp = (ip != null && !ip.isBlank()) ? ip.trim() : "127.0.0.1";
        int resolvedGrpc = grpcPort > 0 ? grpcPort : 9091;
        int resolvedRest = restPort > 0 ? restPort : 8082;
        String resolvedStorage = (storagePath != null && !storagePath.isBlank()) ? storagePath.trim() : "~/jettra/data";

        String prefix = "cluster.node." + targetNumber + ".";
        clusterProps.setProperty(prefix + "id", resolvedId);
        clusterProps.setProperty(prefix + "role", resolvedRole);
        clusterProps.setProperty(prefix + "ip", resolvedIp);
        clusterProps.setProperty(prefix + "grpc.port", String.valueOf(resolvedGrpc));
        clusterProps.setProperty(prefix + "rest.port", String.valueOf(resolvedRest));
        clusterProps.setProperty(prefix + "storage.path", resolvedStorage);

        return new ClusterNodeInfo(resolvedId, resolvedRole, resolvedIp, resolvedGrpc, resolvedRest, resolvedStorage, targetNumber);
    }

    /**
     * Registra dinámicamente un nuevo nodo en el archivo cluster.config localizado por defecto.
     */
    public static synchronized ClusterNodeInfo registerDynamicNodeInClusterConfig(
            String nodeId,
            String role,
            String ip,
            int grpcPort,
            int restPort,
            String storagePath) {
        Path clusterFile = locateClusterConfigFile();
        if (clusterFile == null) {
            clusterFile = Path.of(DEFAULT_CLUSTER_CONFIG_PATH);
        }
        return registerDynamicNodeInClusterConfig(clusterFile, nodeId, role, ip, grpcPort, restPort, storagePath);
    }

    /**
     * Registra dinámicamente un nuevo nodo en el archivo cluster.config especificado en disco.
     */
    public static synchronized ClusterNodeInfo registerDynamicNodeInClusterConfig(
            Path clusterConfigFile,
            String nodeId,
            String role,
            String ip,
            int grpcPort,
            int restPort,
            String storagePath) {
        Properties props = loadProperties(clusterConfigFile, "/cluster.config", "/jettra.config");
        List<ClusterNodeInfo> beforeNodes = parseClusterNodes(props);
        ClusterNodeInfo existing = findNodeInCluster(beforeNodes, nodeId);
        boolean alreadyExistedById = existing != null && existing.id().equalsIgnoreCase(nodeId.trim());

        ClusterNodeInfo registered = registerDynamicNodeInProperties(
            props, nodeId, role, ip, grpcPort, restPort, storagePath
        );

        if (clusterConfigFile != null) {
            try {
                if (clusterConfigFile.getParent() != null && !Files.exists(clusterConfigFile.getParent())) {
                    Files.createDirectories(clusterConfigFile.getParent());
                }
                if (!Files.exists(clusterConfigFile)) {
                    generateDefaultClusterConfig(clusterConfigFile);
                }
                if (!alreadyExistedById) {
                    String block = String.format(
                        "%n# ==============================================================================%n" +
                        "# NODO %s: NODO AGREGADO DINÁMICAMENTE (%s)%n" +
                        "# ==============================================================================%n" +
                        "cluster.node.%s.id = %s%n" +
                        "cluster.node.%s.role = %s%n" +
                        "cluster.node.%s.ip = %s%n" +
                        "cluster.node.%s.grpc.port = %d%n" +
                        "cluster.node.%s.rest.port = %d%n" +
                        "cluster.node.%s.storage.path = %s%n",
                        registered.nodeNumber(), registered.role(),
                        registered.nodeNumber(), registered.id(),
                        registered.nodeNumber(), registered.role(),
                        registered.nodeNumber(), registered.ip(),
                        registered.nodeNumber(), registered.grpcPort(),
                        registered.nodeNumber(), registered.restPort(),
                        registered.nodeNumber(), registered.storagePath()
                    );
                    Files.writeString(clusterConfigFile, block, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    // Actualizar propiedades existentes en el archivo cluster.config
                    List<String> lines = Files.readAllLines(clusterConfigFile, StandardCharsets.UTF_8);
                    String prefix = "cluster.node." + registered.nodeNumber() + ".";
                    List<String> updatedLines = new ArrayList<>(lines.size());
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith(prefix + "id")) {
                            updatedLines.add(prefix + "id = " + registered.id());
                        } else if (trimmed.startsWith(prefix + "role")) {
                            updatedLines.add(prefix + "role = " + registered.role());
                        } else if (trimmed.startsWith(prefix + "ip")) {
                            updatedLines.add(prefix + "ip = " + registered.ip());
                        } else if (trimmed.startsWith(prefix + "grpc.port")) {
                            updatedLines.add(prefix + "grpc.port = " + registered.grpcPort());
                        } else if (trimmed.startsWith(prefix + "rest.port")) {
                            updatedLines.add(prefix + "rest.port = " + registered.restPort());
                        } else if (trimmed.startsWith(prefix + "storage.path")) {
                            updatedLines.add(prefix + "storage.path = " + registered.storagePath());
                        } else {
                            updatedLines.add(line);
                        }
                    }
                    Files.write(clusterConfigFile, updatedLines, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                System.err.printf("[JettraStore] Error al persistir nodo dinámico '%s' en '%s': %s%n",
                    nodeId, clusterConfigFile, e.getMessage());
            }
        }

        return registered;
    }

    /**
     * Carga propiedades buscando primero en el classpath y sobreescribiendo con el archivo en disco si existe.
     */
    public static Properties loadProperties(Path diskPath, String classpathResource) {
        return loadProperties(diskPath, new String[]{classpathResource});
    }

    public static Properties loadProperties(Path diskPath, String... classpathResources) {
        Properties props = new Properties();
        if (classpathResources != null) {
            for (String cpRes : classpathResources) {
                if (cpRes == null) continue;
                try (InputStream is = JettraConfigValidator.class.getResourceAsStream(cpRes)) {
                    if (is != null) {
                        props.load(is);
                    }
                } catch (IOException ignored) {}
            }
        }
        if (diskPath != null && Files.exists(diskPath)) {
            try (InputStream is = Files.newInputStream(diskPath)) {
                props.load(is);
            } catch (IOException ignored) {}
        }
        return props;
    }

    /**
     * Valida la configuración antes del arranque de JettraStore y detiene la ejecución si hay errores.
     */
    public static void validateAndBootstrapOrHalt() {
        validateAndBootstrapOrHalt(true);
    }

    /**
     * Valida la configuración antes del arranque de JettraStore.
     * @param haltOnFailure Si es true y no está en modo de prueba, invoca System.exit(1). De lo contrario, lanza JettraConfigurationException.
     */
    public static ValidationResult validateAndBootstrapOrHalt(boolean haltOnFailure) {
        if (isDockerEnvironment()) {
            System.out.println("[JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada mediante docker-compose / variables de entorno.");
            return new ValidationResult(true, List.of(), "Entorno Docker detectado.");
        }

        // 1. Si no existen los archivos, generarlos automáticamente
        ensureConfigFilesExist();

        // 2. Cargar configuraciones
        Path dbPath = locateMicroProfileConfigFile();
        Path clusterPath = locateClusterConfigFile();

        String dbFileName = dbPath != null ? dbPath.getFileName().toString() : "microprofile-config.properties";
        String clusterFileName = clusterPath != null ? clusterPath.getFileName().toString() : "cluster.config";

        System.out.println("[JettraStore] ================================================================================");
        System.out.printf("[JettraStore] Leyendo configuración de nodo (%s): %s%n", 
            dbFileName, dbPath != null ? dbPath.toAbsolutePath().normalize() : "classpath:/META-INF/microprofile-config.properties");
        System.out.printf("[JettraStore] Leyendo topología de clúster (%s):      %s%n", 
            clusterFileName, clusterPath != null ? clusterPath.toAbsolutePath().normalize() : "classpath:/cluster.config");
        System.out.println("[JettraStore] ================================================================================");

        Properties dbProps = loadProperties(dbPath, "/META-INF/microprofile-config.properties", "/microprofile-config.properties");
        Properties clusterProps = loadProperties(clusterPath, "/cluster.config", "/jettra.config");

        // 3. Ejecutar validaciones
        ValidationResult result = validate(dbProps, clusterProps);

        if (result.hasWarnings()) {
            for (String w : result.getWarnings()) {
                System.err.println("[JettraStore] " + w);
            }
        }

        if (!result.isValid()) {
            System.err.println(result.getNotification());
            boolean skipExit = Boolean.getBoolean("jettra.test.skip.exit");
            if (haltOnFailure && !skipExit) {
                System.exit(1);
            } else {
                throw new JettraConfigurationException(result.getNotification());
            }
        } else {
            System.out.println("[JettraStore] Configuración verificada: Parámetros de base de datos y clúster consistentes.");
        }

        return result;
    }
}

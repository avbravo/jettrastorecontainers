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
 * 1. Verifica que 'jettra.storage.path' en database.properties coincida con al menos
 *    uno de cluster.node.X.storage.path en jettra.config y use la sintaxis <path>/jettra/<id-node>/data.
 * 2. Verifica que 'jettra.network.grpc.port' en database.properties coincida con al menos
 *    un cluster.node.X.grpc.port en jettra.config.
 * 3. Verifica que 'jettra.network.rest.port' en database.properties coincida con al menos
 *    un cluster.node.X.rest.port en jettra.config.
 * 4. Verifica que 'jettra.index.storage.path' implemente la sintaxis <path>/jettra/<id-node>/data/indexes.
 * 5. Genera automáticamente database.properties con plantilla recomendada si no existe en disco.
 * 6. Genera automáticamente jettra.config con topología recomendada si no existe en disco.
 * 7. Si se ejecuta en entorno Docker / Docker Compose, reconoce la configuración de los contenedores
 *    y omite la detención por discrepancias de rutas locales del host.
 */
public final class JettraConfigValidator {

    public static final String DEFAULT_CONFIG_DIR = "config";
    public static final String DEFAULT_DATABASE_PROPERTIES_PATH = "config/database.properties";
    public static final String DEFAULT_JETTRA_CONFIG_PATH = "config/jettra.config";

    // Sintaxis recomendada: <path>/jettra/<id-node>/data
    private static final Pattern STORAGE_PATH_PATTERN = Pattern.compile("^.+[/\\\\]jettra[/\\\\][^/\\\\]+[/\\\\]data[/\\\\]?$");

    // Sintaxis recomendada de índices: <path>/jettra/<id-node>/data/indexes
    private static final Pattern INDEX_STORAGE_PATH_PATTERN = Pattern.compile("^.+[/\\\\]jettra[/\\\\][^/\\\\]+[/\\\\]data[/\\\\]indexes[/\\\\]?$");

    public record ClusterNodeInfo(
        String id,
        String role,
        String ip,
        int grpcPort,
        int restPort,
        String storagePath
    ) {}

    public static final class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final String notification;

        public ValidationResult(boolean valid, List<String> errors, String notification) {
            this.valid = valid;
            this.errors = errors != null ? Collections.unmodifiableList(errors) : List.of();
            this.notification = notification != null ? notification : "";
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public String getNotification() { return notification; }
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
     * Localiza el archivo database.properties en las rutas estándar o configuradas.
     */
    public static Path locateDatabasePropertiesFile() {
        String sysProp = System.getProperty("database.properties.path");
        if (sysProp != null && !sysProp.isBlank()) {
            Path p = Path.of(sysProp);
            if (Files.exists(p)) return p;
        }
        Path p1 = Path.of(DEFAULT_DATABASE_PROPERTIES_PATH);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of("database.properties");
        if (Files.exists(p2)) return p2;
        return null;
    }

    /**
     * Localiza el archivo jettra.config en las rutas estándar o configuradas.
     */
    public static Path locateJettraConfigFile() {
        String sysProp = System.getProperty("jettra.config.path");
        if (sysProp != null && !sysProp.isBlank()) {
            Path p = Path.of(sysProp);
            if (Files.exists(p)) return p;
        }
        Path p1 = Path.of(DEFAULT_JETTRA_CONFIG_PATH);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of("jettra.config");
        if (Files.exists(p2)) return p2;
        return null;
    }

    /**
     * Asegura que los archivos de configuración existan en disco. Si no existen, los genera
     * de manera automática en la carpeta 'config/' con los valores y la sintaxis recomendada.
     */
    public static void ensureConfigFilesExist() {
        Path dbPath = locateDatabasePropertiesFile();
        if (dbPath == null) {
            Path target = Path.of(DEFAULT_DATABASE_PROPERTIES_PATH);
            generateDefaultDatabaseProperties(target);
        }

        Path clusterPath = locateJettraConfigFile();
        if (clusterPath == null) {
            Path target = Path.of(DEFAULT_JETTRA_CONFIG_PATH);
            generateDefaultJettraConfig(target);
        }
    }

    /**
     * Genera automáticamente el archivo database.properties con la configuración predeterminada y recomendada.
     */
    public static void generateDefaultDatabaseProperties(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            String content = """
################################################################################
# JettraStore Core Engine Configuration (database.properties)
# Generado automáticamente por JettraStore Engine
################################################################################

# Identificador y rol de este nodo en el cluster
jettra.cluster.node.id = node-01
jettra.cluster.node.role = PRIMARY

# Topología Multinodo y Algoritmo de Consenso Distribuido
# on:  Activa el sistema de distribución de datos mediante algoritmo de consenso integrado en JettraStore.
# off: Desactiva el comportamiento de distribución y asume que todas las operaciones se realizarán
#      solamente en el servidor local donde se está ejecutando JettraStore sin distribuir los datos.
cluster.multinode.active = on

# Ubicación explícita del path del directorio de la base de datos en disco físico
# Sintaxis recomendada: <path>/jettra/<id-node>/data
jettra.storage.path = ~/jettra/node-01/data

# Estructura LSM y Memoria Off-Heap con Project Panama
jettra.storage.memtable.size.mb = 128
jettra.storage.ram.global.limit.mb = 2048
jettra.storage.offheap.direct = true
jettra.storage.mode = JVM_RAM
jettra.storage.file.extension = .jettra

# Umbrales para la Transición Dinámica a Motor de Anillo Distribuido
jettra.ring.saturation.threshold.percent = 85
jettra.ring.release.target.percent = 45

# Componente Autónomo de Supervisión Preventiva (JettraPolice)
jettrapolice.active = true
jettrapolice.interval.ms = 500
jettrapolice.ram.warning.threshold = 75
jettrapolice.disk.warning.threshold = 90
jettrapolice.ram.critical.threshold = 85
jettrapolice.auto.pagination.enabled = true
jettrapolice.max.safe.batch.size = 100

# Métricas de Rendimiento con JMH
jmh.metrics.active = false

# Seguridad y Autenticación Criptográfica con JettraJWT
jettra.security.jwt.algorithm = Ed25519
jettra.security.jwt.expiration.seconds = 86400
jettra.security.jwt.issuer = jettra-store-authority
jettra.security.default.admin.username = admin
jettra.security.default.admin.password = admin-jettra

# Red y Puertos de Escucha
jettra.network.grpc.port = 9091
jettra.network.rest.port = 8080
jettra.network.virtualthreads.enabled = true

# Optimización de Almacenamiento de Índices
jettra.index.initial.capacity = 65536
jettra.index.max.inmemory.keys = 100000
jettra.index.compact.storage = true

# Sintaxis recomendada de índices: <path>/jettra/<id-node>/data/indexes
jettra.index.storage.path = ~/jettra/node-01/data/indexes
jettra.storage.autoflush.batch.size = 50000

# Límites de Consulta y Prevención de OOM
jettra.query.default.limit = 50
jettra.query.max.limit = 5000
jettra.query.pagesize = 50
""";
            Files.writeString(target, content, StandardCharsets.UTF_8);
            System.out.printf("[JettraStore] Archivo de configuración '%s' generado automáticamente con éxito.%n", target);
        } catch (IOException e) {
            System.err.printf("[JettraStore] Error al generar archivo '%s': %s%n", target, e.getMessage());
        }
    }

    /**
     * Genera automáticamente el archivo jettra.config con la topología de clúster recomendada.
     */
    public static void generateDefaultJettraConfig(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            String content = """
################################################################################
# JettraStore Cluster Topology Configuration (jettra.config)
# Topología de 3 Nodos para Consenso Raft y Dynamic Ring Discovery
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
cluster.node.1.rest.port = 8080
cluster.node.1.storage.path = ~/jettra/node-01/data

# ==============================================================================
# NODO 2: NODO SECUNDARIO / SEGUIDOR 1 (Secondary)
# ==============================================================================
cluster.node.2.id = node-02
cluster.node.2.role = SECONDARY
cluster.node.2.ip = 127.0.0.1
cluster.node.2.grpc.port = 9091
cluster.node.2.rest.port = 8080
cluster.node.2.storage.path = ~/jettra/node-02/data

# ==============================================================================
# NODO 3: NODO SECUNDARIO / SEGUIDOR 2 (Secondary)
# ==============================================================================
cluster.node.3.id = node-03
cluster.node.3.role = SECONDARY
cluster.node.3.ip = 127.0.0.1
cluster.node.3.grpc.port = 9091
cluster.node.3.rest.port = 8080
cluster.node.3.storage.path = ~/jettra/node-03/data

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
     * Realiza la validación cruzada estricta entre las propiedades de database.properties y jettra.config.
     */
    public static ValidationResult validate(Properties dbProps, Properties clusterProps) {
        if (isDockerEnvironment()) {
            return new ValidationResult(true, List.of(), 
                "[JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada en docker-compose y variables de entorno.");
        }

        List<String> errors = new ArrayList<>();

        // Extraer los nodos del clúster definidos en jettra.config
        List<ClusterNodeInfo> nodes = parseClusterNodes(clusterProps);
        List<String> nodeStoragePaths = new ArrayList<>();
        List<Integer> nodeGrpcPorts = new ArrayList<>();
        List<Integer> nodeRestPorts = new ArrayList<>();

        for (ClusterNodeInfo node : nodes) {
            if (node.storagePath() != null && !node.storagePath().isBlank()) {
                nodeStoragePaths.add(node.storagePath().trim());
            }
            nodeGrpcPorts.add(node.grpcPort());
            nodeRestPorts.add(node.restPort());
        }

        // 1. Verificar jettra.storage.path
        String dbStoragePath = getPropOrEnv(dbProps, "jettra.storage.path", "JETTRA_STORAGE_PATH", "").trim();
        if (dbStoragePath.isEmpty()) {
            errors.add("La propiedad 'jettra.storage.path' no está definida en database.properties.");
        } else {
            // Verificar coincidencia con al menos un cluster.node.X.storage.path
            boolean matched = false;
            for (String clusterPath : nodeStoragePaths) {
                if (pathsMatch(dbStoragePath, clusterPath)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(String.format(
                    "El path 'jettra.storage.path' ('%s') de database.properties no coincide con ninguna de las propiedades cluster.node.X.storage.path de jettra.config %s. Debe coincidir con al menos un nodo configurado.",
                    dbStoragePath, nodeStoragePaths
                ));
            }

            // Verificar sintaxis recomendada: <path>/jettra/<id-node>/data
            if (!STORAGE_PATH_PATTERN.matcher(dbStoragePath).matches()) {
                errors.add(String.format(
                    "La propiedad 'jettra.storage.path' ('%s') no sigue la sintaxis recomendada: <path>/jettra/<id-node>/data (ejemplo: ~/jettra/node-01/data).",
                    dbStoragePath
                ));
            }
        }

        // 2. Verificar jettra.network.grpc.port
        String grpcPortStr = getPropOrEnv(dbProps, "jettra.network.grpc.port", "JETTRA_GRPC_PORT", "").trim();
        if (grpcPortStr.isEmpty()) {
            errors.add("La propiedad 'jettra.network.grpc.port' no está definida en database.properties.");
        } else {
            try {
                int grpcPort = Integer.parseInt(grpcPortStr);
                if (!nodeGrpcPorts.contains(grpcPort)) {
                    errors.add(String.format(
                        "El puerto 'jettra.network.grpc.port' (%d) de database.properties no coincide con ningún puerto cluster.node.X.grpc.port de jettra.config %s.",
                        grpcPort, nodeGrpcPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de 'jettra.network.grpc.port' ('" + grpcPortStr + "') no es un número de puerto válido.");
            }
        }

        // 3. Verificar jettra.network.rest.port
        String restPortStr = getPropOrEnv(dbProps, "jettra.network.rest.port", "JETTRA_REST_PORT", "").trim();
        if (restPortStr.isEmpty()) {
            errors.add("La propiedad 'jettra.network.rest.port' no está definida en database.properties.");
        } else {
            try {
                int restPort = Integer.parseInt(restPortStr);
                if (!nodeRestPorts.contains(restPort)) {
                    errors.add(String.format(
                        "El puerto 'jettra.network.rest.port' (%d) de database.properties no coincide con ningún puerto cluster.node.X.rest.port de jettra.config %s.",
                        restPort, nodeRestPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de 'jettra.network.rest.port' ('" + restPortStr + "') no es un número de puerto válido.");
            }
        }

        // 4. Verificar jettra.index.storage.path
        String dbIndexPath = dbProps.getProperty("jettra.index.storage.path", "").trim();
        if (dbIndexPath.isEmpty()) {
            errors.add("La propiedad 'jettra.index.storage.path' no está definida en database.properties.");
        } else {
            if (!INDEX_STORAGE_PATH_PATTERN.matcher(dbIndexPath).matches()) {
                errors.add(String.format(
                    "La propiedad 'jettra.index.storage.path' ('%s') no implementa la sintaxis requerida: <path>/jettra/<id-node>/data/indexes (ejemplo: ~/jettra/node-01/data/indexes).",
                    dbIndexPath
                ));
            }
        }

        // 5. Verificar cluster.multinode.active (on / off)
        String multinodeVal = getPropOrEnv(dbProps, "cluster.multinode.active", "JETTRA_CLUSTER_MULTINODE_ACTIVE", "on").trim();
        if (!multinodeVal.equalsIgnoreCase("on") && !multinodeVal.equalsIgnoreCase("off")
            && !multinodeVal.equalsIgnoreCase("true") && !multinodeVal.equalsIgnoreCase("false")) {
            errors.add(String.format(
                "El valor de 'cluster.multinode.active' ('%s') en database.properties es inválido. Debe ser 'on' u 'off'.",
                multinodeVal
            ));
        }

        if (errors.isEmpty()) {
            return new ValidationResult(true, List.of(), 
                "[JettraStore] Validación exitosa: database.properties y jettra.config están debidamente sincronizados.");
        }

        String notification = buildNotificationBanner(errors);
        return new ValidationResult(false, errors, notification);
    }

    /**
     * Construye un banner visual informativo de advertencia y corrección requerida.
     */
    public static String buildNotificationBanner(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║            ERROR DE CONFIGURACIÓN AL INICIAR JETTRASTORE                     ║\n");
        sb.append("║           (Incompatibilidad entre database.properties y jettra.config)       ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
        sb.append("Se han detectado las siguientes incoherencias que impiden el inicio seguro:\n\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(String.format("  [%d] %s%n%n", i + 1, errors.get(i)));
        }
        sb.append("────────────────────────────────────────────────────────────────────────────────\n");
        sb.append("DIRECTIVAS DE CORRECCIÓN:\n");
        sb.append(" 1. Verifique que 'jettra.storage.path' en database.properties coincida con\n");
        sb.append("    al menos uno de los valores cluster.node.[1|2|3].storage.path de jettra.config.\n");
        sb.append("    Sintaxis recomendada: <path>/jettra/<id-node>/data\n");
        sb.append(" 2. Verifique que 'jettra.network.grpc.port' coincida con cluster.node.[1|2|3].grpc.port\n");
        sb.append(" 3. Verifique que 'jettra.network.rest.port' coincida con cluster.node.[1|2|3].rest.port\n");
        sb.append(" 4. Verifique que 'jettra.index.storage.path' cumpla: <path>/jettra/<id-node>/data/indexes\n");
        sb.append(" 5. Verifique que 'cluster.multinode.active' sea 'on' u 'off' (on=consenso distribuido, off=servidor local).\n");
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
        if (p.startsWith("~/") || p.equals("~")) {
            return (System.getProperty("user.home") + p.substring(1)).replace('\\', '/');
        }
        return p;
    }

    private static String getPropOrEnv(Properties props, String sysProp, String envVar, String defaultVal) {
        String sys = System.getProperty(sysProp);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env;
        return props.getProperty(sysProp, defaultVal);
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
     * Parsea la lista de nodos configurados en jettra.config.
     */
    public static List<ClusterNodeInfo> parseClusterNodes(Properties props) {
        List<ClusterNodeInfo> list = new ArrayList<>();
        int idx = 1;
        while (props.containsKey("cluster.node." + idx + ".id") 
               || props.containsKey("cluster.node." + idx + ".storage.path")
               || props.containsKey("cluster.node." + idx + ".grpc.port")
               || props.containsKey("cluster.node." + idx + ".rest.port")) {
            String prefix = "cluster.node." + idx + ".";
            String id = props.getProperty(prefix + "id", "node-0" + idx).trim();
            String role = props.getProperty(prefix + "role", (idx == 1 ? "PRIMARY" : "SECONDARY")).trim();
            String ip = props.getProperty(prefix + "ip", "127.0.0.1").trim();
            int grpcPort = parseInt(props.getProperty(prefix + "grpc.port"), 9091);
            int restPort = parseInt(props.getProperty(prefix + "rest.port"), 8080);
            String storagePath = props.getProperty(prefix + "storage.path", "~/jettra/" + id + "/data").trim();

            list.add(new ClusterNodeInfo(id, role, ip, grpcPort, restPort, storagePath));
            idx++;
        }

        // Si no son secuenciales 1..N, buscar cualquier clave cluster.node.<idx>.
        if (list.isEmpty()) {
            Set<String> nodeIndices = new TreeSet<>();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("cluster.node.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length >= 3) {
                        nodeIndices.add(parts[2]);
                    }
                }
            }
            for (String nodeIdx : nodeIndices) {
                String prefix = "cluster.node." + nodeIdx + ".";
                String id = props.getProperty(prefix + "id", "node-" + nodeIdx).trim();
                String role = props.getProperty(prefix + "role", "SECONDARY").trim();
                String ip = props.getProperty(prefix + "ip", "127.0.0.1").trim();
                int grpcPort = parseInt(props.getProperty(prefix + "grpc.port"), 9091);
                int restPort = parseInt(props.getProperty(prefix + "rest.port"), 8080);
                String storagePath = props.getProperty(prefix + "storage.path", "~/jettra/" + id + "/data").trim();
                list.add(new ClusterNodeInfo(id, role, ip, grpcPort, restPort, storagePath));
            }
        }

        if (list.isEmpty()) {
            list.add(new ClusterNodeInfo("node-01", "PRIMARY", "127.0.0.1", 9091, 8080, "~/jettra/node-01/data"));
            list.add(new ClusterNodeInfo("node-02", "SECONDARY", "127.0.0.1", 9091, 8080, "~/jettra/node-02/data"));
            list.add(new ClusterNodeInfo("node-03", "SECONDARY", "127.0.0.1", 9091, 8080, "~/jettra/node-03/data"));
        }

        return list;
    }

    /**
     * Carga propiedades buscando primero en el classpath y sobreescribiendo con el archivo en disco si existe.
     */
    public static Properties loadProperties(Path diskPath, String classpathResource) {
        Properties props = new Properties();
        if (classpathResource != null) {
            try (InputStream is = JettraConfigValidator.class.getResourceAsStream(classpathResource)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException ignored) {}
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
        Path dbPath = locateDatabasePropertiesFile();
        Path clusterPath = locateJettraConfigFile();

        Properties dbProps = loadProperties(dbPath, "/database.properties");
        Properties clusterProps = loadProperties(clusterPath, "/jettra.config");

        // 3. Ejecutar validaciones
        ValidationResult result = validate(dbProps, clusterProps);

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

package io.jettra.store.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
    public static final String DEFAULT_CLUSTER_STATE_CONFIG_DIR = "~/jettra-cluster-state";
    public static final String CLUSTER_STATE_FILENAME = "cluster-state.config";
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
        public String getSummary() { return notification; }
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
                Path p = Path.of(expandPathPlaceholders(sysProp.trim()));
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
                Path p = Path.of(expandPathPlaceholders(env.trim()));
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
                Path p = Path.of(expandPathPlaceholders(sysProp.trim()));
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
                Path p = Path.of(expandPathPlaceholders(env.trim()));
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
            Path.of("config/cluster-config.properties"),
            Path.of("cluster-config.properties"),
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

    private static final ConcurrentHashMap<Path, ReentrantLock> STATE_FILE_LOCKS = new ConcurrentHashMap<>();

    private static ReentrantLock lockForStateFile(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        return STATE_FILE_LOCKS.computeIfAbsent(normalized, k -> new ReentrantLock());
    }

    /**
     * Localiza un archivo semilla estático opcional ({@code cluster.config} o {@code cluster-config.properties})
     * únicamente en sistema de archivos, sin recurrir a recursos embebidos en el JAR.
     */
    public static Path locateOptionalClusterSeedFile() {
        Path stateFile = resolveClusterStateFile();
        Path stateDir = stateFile != null ? stateFile.getParent() : null;
        return locateOptionalClusterSeedFile(stateDir);
    }

    /**
     * Localiza {@code cluster.config} / {@code cluster-config.properties} priorizando el directorio
     * configurado en {@code jettra.cluster_state_config_dir} antes de buscar rutas locales explícitas,
     * sin recorrer directorios padre ({@code ../}) ni usar recursos embebidos en el JAR.
     */
    public static Path locateOptionalClusterSeedFile(Path stateDir) {
        String[] explicitKeys = {
            "cluster.config.path", "jettra.cluster.config.path", "jettra.config.path"
        };
        for (String key : explicitKeys) {
            String customPath = System.getProperty(key);
            if (customPath != null && !customPath.isBlank()) {
                Path p = Path.of(expandPathPlaceholders(customPath.trim()));
                if (Files.exists(p)) {
                    return p;
                }
            }
        }
        String[] explicitEnv = {
            "CLUSTER_CONFIG_PATH", "JETTRA_CLUSTER_CONFIG_PATH", "JETTRA_CONFIG_PATH"
        };
        for (String key : explicitEnv) {
            String customPath = System.getenv(key);
            if (customPath != null && !customPath.isBlank()) {
                Path p = Path.of(expandPathPlaceholders(customPath.trim()));
                if (Files.exists(p)) {
                    return p;
                }
            }
        }
        if (stateDir != null) {
            Path stateClusterConfig = stateDir.resolve("cluster.config");
            if (Files.exists(stateClusterConfig)) {
                return stateClusterConfig;
            }
            Path stateClusterProps = stateDir.resolve("cluster-config.properties");
            if (Files.exists(stateClusterProps)) {
                return stateClusterProps;
            }
        }
        Path mpFile = locateMicroProfileConfigFile();
        if (mpFile != null && mpFile.toAbsolutePath().normalize().getParent() != null) {
            Path mpParent = mpFile.toAbsolutePath().normalize().getParent();
            Path siblingCluster = mpParent.resolve("cluster.config");
            if (Files.exists(siblingCluster)) {
                return siblingCluster;
            }
            Path siblingProps = mpParent.resolve("cluster-config.properties");
            if (Files.exists(siblingProps)) {
                return siblingProps;
            }
        }
        Path[] cwdCandidates = {
            Path.of(DEFAULT_CLUSTER_CONFIG_PATH),
            Path.of("cluster.config"),
            Path.of("config/cluster-config.properties"),
            Path.of("cluster-config.properties")
        };
        for (Path c : cwdCandidates) {
            if (Files.exists(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Localiza {@code cluster.config} para un nodo {@code PRIMARY}, incluyendo el layout estándar de IDE/Maven
     * (por ejemplo NetBeans donde {@code microprofile-config.properties} está en {@code src/main/resources/META-INF/}
     * y {@code cluster.config} reside en {@code src/main/resources/cluster.config}). Nunca debe usarse en {@code SECONDARY}.
     */
    public static Path locatePrimarySeedFile(Path mpPath, Path stateDir) {
        Path direct = locateOptionalClusterSeedFile(stateDir);
        if (direct != null) {
            return direct;
        }
        if (mpPath != null) {
            try {
                Path mpNorm = mpPath.toAbsolutePath().normalize();
                Path mpParent = mpNorm.getParent();
                if (mpParent != null && mpParent.getFileName() != null
                        && "META-INF".equals(mpParent.getFileName().toString())) {
                    Path resParent = mpParent.getParent();
                    if (resParent != null) {
                        Path resCluster = resParent.resolve("cluster.config");
                        if (Files.exists(resCluster)) {
                            return resCluster;
                        }
                        Path resProps = resParent.resolve("cluster-config.properties");
                        if (Files.exists(resProps)) {
                            return resProps;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (mpPath == null || !isIsolatedTestPath(mpPath.toAbsolutePath().normalize().toString())) {
            Path[] projectCandidates = {
                Path.of("src/main/resources/cluster.config"),
                Path.of("JettraStore/src/main/resources/cluster.config"),
                Path.of("target/classes/cluster.config")
            };
            for (Path c : projectCandidates) {
                if (Files.exists(c)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Si no se encontró {@code cluster.config} en sistema de archivos, intenta inicializar {@code cluster-state.config}
     * para un nodo {@code PRIMARY} desde el recurso {@code /cluster.config} empaquetado en el classpath (JAR/IDE).
     * Nunca sobrescribe un {@code cluster-state.config} existente ni se aplica en rutas temporales aisladas de prueba
     * sin archivo semilla explícito.
     */
    public static Properties seedClusterStateFromClasspathIfApplicable(Path stateFile, Path mpPath, Properties dbProps) {
        if (stateFile == null) {
            return null;
        }
        if (mpPath != null && isIsolatedTestPath(mpPath.toAbsolutePath().normalize().toString())) {
            return null;
        }
        try {
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                return seedOrLoadClusterState(stateFile, null);
            }
            for (String cpRes : new String[]{"/cluster.config", "/jettra.config"}) {
                try (InputStream is = JettraConfigValidator.class.getResourceAsStream(cpRes)) {
                    if (is == null) {
                        continue;
                    }
                    byte[] rawSeed = is.readAllBytes();
                    if (rawSeed.length == 0) {
                        continue;
                    }
                    Properties seedProps = new Properties();
                    seedProps.load(new ByteArrayInputStream(rawSeed));
                    List<ClusterNodeInfo> parsedSeedNodes = parseExplicitClusterNodes(seedProps);
                    if (parsedSeedNodes.isEmpty()) {
                        continue;
                    }
                    List<ClusterNodeInfo> seedNodes = normalizeOmittedRestPorts(parsedSeedNodes, seedProps);
                    Map<String, String> statuses = parseClusterNodeStatuses(seedProps);
                    byte[] stateBytes = buildCanonicalClusterStateBytes(seedNodes, statuses, seedProps, 1L, "primary-cluster-config");
                    writeClusterConfigAtomically(stateFile, stateBytes);
                    Properties loaded = new Properties();
                    try (var bais = new ByteArrayInputStream(Files.readAllBytes(stateFile))) {
                        loaded.load(bais);
                    }
                    System.out.printf("[JettraStore] Estado persistente inicializado en '%s' desde recurso '%s' (%d nodo(s)).%n",
                        stateFile, cpRes, seedNodes.size());
                    return loaded;
                }
            }
        } catch (Exception ex) {
            System.err.printf("[JettraStore] Advertencia cargando semilla classpath hacia '%s': %s%n",
                stateFile, ex.getMessage());
        }
        return null;
    }

    /**
     * Expande {@code ~} al directorio home del usuario y variables de entorno como {@code $VAR} o {@code ${VAR}}.
     */
    public static String expandPathPlaceholders(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String val = raw.trim();
        java.util.regex.Matcher braceMatcher = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
                .matcher(val);
        StringBuilder sb = new StringBuilder();
        while (braceMatcher.find()) {
            String envVal = System.getenv(braceMatcher.group(1));
            if (envVal == null && "HOME".equals(braceMatcher.group(1))) {
                envVal = System.getProperty("user.home", "");
            }
            braceMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(envVal != null ? envVal : ""));
        }
        braceMatcher.appendTail(sb);
        val = sb.toString();

        java.util.regex.Matcher varMatcher = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(val);
        sb = new StringBuilder();
        while (varMatcher.find()) {
            String envVal = System.getenv(varMatcher.group(1));
            if (envVal == null && "HOME".equals(varMatcher.group(1))) {
                envVal = System.getProperty("user.home", "");
            }
            varMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(envVal != null ? envVal : ""));
        }
        varMatcher.appendTail(sb);
        val = sb.toString();

        return expandUserHome(val);
    }

    private static boolean isIsolatedTestPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String p = rawPath.trim();
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        return p.startsWith("/tmp")
            || p.contains("/tmp/")
            || p.contains("/target/")
            || (!tmpDir.isBlank() && p.contains(tmpDir));
    }

    private static String firstNonBlankProperty(Properties props, String... keys) {
        if (props == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String val = props.getProperty(key);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }

    public static boolean hasExplicitClusterStateConfigDir(Properties mpProps) {
        for (String sp : new String[]{"jettra.cluster_state_config_dir", "cluster.state.config.dir"}) {
            String val = System.getProperty(sp);
            if (val != null && !val.isBlank()) {
                return true;
            }
        }
        for (String ev : new String[]{"JETTRA_CLUSTER_STATE_CONFIG_DIR", "CLUSTER_STATE_CONFIG_DIR"}) {
            String val = System.getenv(ev);
            if (val != null && !val.isBlank()) {
                return true;
            }
        }
        String mpVal = firstNonBlankProperty(mpProps, "jettra.cluster_state_config_dir", "cluster.state.config.dir");
        return mpVal != null;
    }

    /**
     * Resuelve el directorio configurado en {@code jettra.cluster_state_config_dir}
     * (propiedad del sistema, variable de entorno, {@code microprofile-config.properties} o {@code ~/jettra-cluster-state}).
     */
    public static Path resolveClusterStateConfigDirPath(Properties mpProps) {
        String configured = null;
        for (String sp : new String[]{"jettra.cluster_state_config_dir", "cluster.state.config.dir"}) {
            String val = System.getProperty(sp);
            if (val != null && !val.isBlank()) {
                configured = val.trim();
                break;
            }
        }
        if (configured == null) {
            for (String ev : new String[]{"JETTRA_CLUSTER_STATE_CONFIG_DIR", "CLUSTER_STATE_CONFIG_DIR"}) {
                String val = System.getenv(ev);
                if (val != null && !val.isBlank()) {
                    configured = val.trim();
                    break;
                }
            }
        }
        String mpStateDir = null;
        if (configured == null && mpProps != null) {
            mpStateDir = firstNonBlankProperty(mpProps, "jettra.cluster_state_config_dir", "cluster.state.config.dir");
        }
        if (configured == null && mpStateDir == null) {
            String explicitMp = System.getProperty("microprofile.config.file", System.getenv("MP_CONFIG_FILE"));
            if (explicitMp != null && !explicitMp.isBlank()) {
                Path explicitMpPath = Path.of(expandPathPlaceholders(explicitMp));
                if (Files.exists(explicitMpPath)) {
                    Properties loadedMp = loadProperties(explicitMpPath);
                    mpStateDir = firstNonBlankProperty(loadedMp, "jettra.cluster_state_config_dir", "cluster.state.config.dir");
                }
            }
        }
        boolean mpIsDefault = (mpStateDir == null
            || mpStateDir.equals(DEFAULT_CLUSTER_STATE_CONFIG_DIR)
            || mpStateDir.equals(DEFAULT_CLUSTER_STATE_CONFIG_DIR + "/"));
        boolean homeIsIsolated = isIsolatedTestPath(System.getProperty("user.home", ""));

        if (configured == null && (!mpIsDefault || (homeIsIsolated && mpStateDir != null))) {
            configured = mpStateDir;
        }

        // En pruebas aisladas que redirigen cluster.config.path o jettra.storage.path sin definir state dir personalizado,
        // mantener el estado dentro del entorno temporal para no contaminar el home del usuario.
        if (configured == null) {
            String explicitTestStorage = System.getProperty("jettra.storage.path", System.getProperty("storage.path"));
            String explicitTestCluster = System.getProperty("cluster.config.path");
            String mpStorage = firstNonBlankProperty(mpProps, "jettra.storage.path", "storage.path", "data.dir");
            boolean mpStorageCustom = mpStorage != null
                && !mpStorage.equals("~/jettra/data")
                && !mpStorage.equals("/jettra/data");

            String sysTmp = System.getProperty("java.io.tmpdir", "/tmp");
            if (explicitTestCluster != null && !explicitTestCluster.isBlank()
                    && (mpStateDir == null || isIsolatedTestPath(explicitTestCluster))) {
                try {
                    String hash = Integer.toUnsignedString(
                        Path.of(expandPathPlaceholders(explicitTestCluster)).toAbsolutePath().normalize().toString().hashCode(), 16);
                    return Path.of(sysTmp, "jettra-isolated-state-" + hash).toAbsolutePath().normalize();
                } catch (Exception ignored) {}
            }
            if (explicitTestStorage != null && !explicitTestStorage.isBlank()
                    && (mpStateDir == null || isIsolatedTestPath(explicitTestStorage))) {
                try {
                    String hash = Integer.toUnsignedString(
                        Path.of(expandPathPlaceholders(explicitTestStorage)).toAbsolutePath().normalize().toString().hashCode(), 16);
                    return Path.of(sysTmp, "jettra-isolated-state-" + hash).toAbsolutePath().normalize();
                } catch (Exception ignored) {}
            }
            if (mpStorageCustom && (mpStateDir == null || isIsolatedTestPath(mpStorage))) {
                try {
                    String hash = Integer.toUnsignedString(
                        Path.of(expandPathPlaceholders(mpStorage)).toAbsolutePath().normalize().toString().hashCode(), 16);
                    return Path.of(sysTmp, "jettra-isolated-state-" + hash).toAbsolutePath().normalize();
                } catch (Exception ignored) {}
            }
            configured = mpStateDir != null
                ? mpStateDir
                : (isDockerEnvironment() ? "/jettra/cluster-state" : DEFAULT_CLUSTER_STATE_CONFIG_DIR);
        }
        String expanded = expandPathPlaceholders(configured);
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    /**
     * Garantiza que {@code jettra.cluster_state_config_dir} exista (creándolo con {@code Files.createDirectories})
     * y posea permisos efectivos de lectura y escritura antes de iniciar el nodo.
     */
    public static Path ensureClusterStateConfigDirExists(Properties mpProps) {
        Path dir = resolveClusterStateConfigDirPath(mpProps);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.printf("[JettraStore] Directorio de estado del clúster creado automáticamente en: %s%n", dir);
            }
            if (!Files.isDirectory(dir)) {
                throw new JettraConfigurationException("La ruta 'jettra.cluster_state_config_dir' no es un directorio válido: " + dir);
            }
            if (!Files.isReadable(dir) || !Files.isWritable(dir)) {
                throw new JettraConfigurationException(
                    "Permisos insuficientes (lectura/escritura requeridos) en 'jettra.cluster_state_config_dir': " + dir);
            }
            return dir;
        } catch (IOException ex) {
            throw new JettraConfigurationException(
                "No se pudo crear o acceder al directorio 'jettra.cluster_state_config_dir' ('" + dir + "'): " + ex.getMessage());
        }
    }

    /**
     * Devuelve la ruta completa al archivo persistente {@code <jettra.cluster_state_config_dir>/cluster-state.config}.
     */
    public static Path resolveClusterStateFile(Properties mpProps) {
        return ensureClusterStateConfigDirExists(mpProps).resolve(CLUSTER_STATE_FILENAME);
    }

    public static Path resolveClusterStateFile() {
        Path mpPath = locateMicroProfileConfigFile();
        Properties mpProps = (mpPath != null && Files.exists(mpPath))
            ? loadProperties(mpPath, "/META-INF/microprofile-config.properties", "/microprofile-config.properties")
            : new Properties();
        return resolveClusterStateFile(mpProps);
    }

    /**
     * Resuelve el archivo de topología activo priorizando {@code cluster-state.config} si existe
     * o si ya está inicializado el directorio de estado, y usando {@code cluster.config} solo como respaldo opcional.
     */
    public static Path locateActiveClusterStateOrSeedFile() {
        try {
            Path stateFile = resolveClusterStateFile();
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                return stateFile;
            }
            Path seed = locateOptionalClusterSeedFile();
            if (seed != null && Files.exists(seed)) {
                return seed;
            }
            return stateFile;
        } catch (Exception ignored) {
            return locateClusterConfigFile();
        }
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
     * Asegura que {@code microprofile-config.properties} y el directorio {@code jettra.cluster_state_config_dir}
     * existan en disco. El archivo estático {@code cluster.config} es opcional y no se genera ni sobrescribe.
     */
    public static void ensureConfigFilesExist() {
        Path dbPath = locateMicroProfileConfigFile();
        if (dbPath == null) {
            Path target = Path.of(DEFAULT_MICROPROFILE_CONFIG_PATH);
            generateDefaultMicroProfileConfig(target);
            dbPath = target;
        }
        Properties mpProps = loadProperties(dbPath, "/META-INF/microprofile-config.properties", "/microprofile-config.properties");
        ensureClusterStateConfigDirExists(mpProps);
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
jettra.storage.path=~/jettra-storage-01/data
jettra.cluster_state_config_dir=~/jettra-cluster-state/

# Identificador, Rol y Endpoints del Nodo Local en el Clúster (RF-37)
cluster.node.id=node-01
cluster.node.role=PRIMARY
cluster.node.ip=127.0.0.1
cluster.node.grpc.port=50001
cluster.node.rest.port=60001

# Topología Multinodo y Consenso
cluster.multinode.active=on

# Modo de Almacenamiento (OFF_HEAP, DISK_MEMORY, JVM_RAM)
jettra.storage.mode=OFF_HEAP
storage.mode=OFF_HEAP
jettra.storage.file.extension=.jettra

# Red y Virtual Threads (cluster.node.ip/grpc.port/rest.port definen los endpoints locales; PRIMARY los valida contra cluster.config)
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
cluster.node.1.grpc.port = 50001
cluster.node.1.rest.port = 60001

# ==============================================================================
# NODO 2: NODO SECUNDARIO / SEGUIDOR 1 (Secondary)
# ==============================================================================
cluster.node.2.id = node-02
cluster.node.2.role = SECONDARY
cluster.node.2.ip = 127.0.0.1
cluster.node.2.grpc.port = 50002
cluster.node.2.rest.port = 60002

# ==============================================================================
# NODO 3: NODO SECUNDARIO / SEGUIDOR 2 (Secondary)
# ==============================================================================
cluster.node.3.id = node-03
cluster.node.3.role = SECONDARY
cluster.node.3.ip = 127.0.0.1
cluster.node.3.grpc.port = 50003
cluster.node.3.rest.port = 60003

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
     * Realiza la validación cruzada estricta entre las propiedades de microprofile-config.properties
     * y el archivo de topología del clúster ({@code cluster.config} o {@code cluster-state.config}).
     */
    public static ValidationResult validate(Properties dbProps, Properties clusterProps) {
        String inferredSource = (clusterProps != null && clusterProps.containsKey("cluster.state.version"))
            ? CLUSTER_STATE_FILENAME
            : "cluster.config";
        return validate(dbProps, clusterProps, inferredSource);
    }

    /**
     * Realiza la validación cruzada estricta entre microprofile-config.properties y el archivo indicado
     * ({@code cluster.config} o {@code cluster-state.config}).
     */
    public static ValidationResult validate(Properties dbProps, Properties clusterProps, String sourceFileName) {
        if (isDockerEnvironment()) {
            return new ValidationResult(true, List.of(), 
                "[JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada en docker-compose y variables de entorno.");
        }

        String sourceLabel = (sourceFileName != null && !sourceFileName.isBlank())
            ? sourceFileName.trim()
            : "cluster.config";

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Extraer los nodos del clúster definidos en el archivo de topología/estado
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
                    "ADVERTENCIA: El identificador de nodo '%s' indicado en 'cluster.node.id' (microprofile-config.properties) no existe en el archivo %s %s. No se permite ejecutar JettraStore.",
                    configuredNodeId, sourceLabel, validIds
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
            ? clusterProps.getProperty("cluster.node." + nodeIdxStr + ".storage.path")
            : null;

        String effectiveStoragePath = (explicitDbStoragePath != null && !explicitDbStoragePath.isBlank())
            ? explicitDbStoragePath
            : (clusterNodeStoragePath != null && !clusterNodeStoragePath.isBlank() ? clusterNodeStoragePath.trim() : "~/jettra/data");

        if (explicitDbStoragePath != null && !explicitDbStoragePath.isBlank()
            && clusterNodeStoragePath != null && !clusterNodeStoragePath.isBlank()
            && !configuredNodeId.isEmpty()) {
            if (!pathsMatch(explicitDbStoragePath, clusterNodeStoragePath.trim())) {
                String warnMsg = String.format(
                    "ADVERTENCIA: La propiedad de almacenamiento 'storage.path' / 'jettra.storage.path' ('%s') de microprofile-config.properties no coincide con la propiedad 'cluster.node.%s.storage.path' ('%s') de %s para el nodo %s ('%s').",
                    explicitDbStoragePath, nodeIdxStr, clusterNodeStoragePath.trim(), sourceLabel, nodeIdxStr, configuredNodeId
                );
                warnings.add(warnMsg);
                System.err.println("[JettraStore] " + warnMsg);
            }
        }
        // Si el nodo es válido, verificar si existe el directorio jettra.storage.path y crearlo si no existe
        if (errors.isEmpty() && !effectiveStoragePath.isEmpty()) {
            ensureNodeStorageDirectoryExists(effectiveStoragePath);
        }

        // 3. Verificar coincidencia de rol (Evento 1 PRIMARY vs cluster.config cuando no es cluster-state de failover)
        String configuredRole = getPropOrEnv(dbProps,
            new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
            new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"}, "").trim();
        boolean isClusterStateSnapshot = CLUSTER_STATE_FILENAME.equals(sourceLabel)
            || (clusterProps != null && clusterProps.containsKey("cluster.state.version"));
        if (targetNode != null && !configuredRole.isEmpty() && !isClusterStateSnapshot
                && "PRIMARY".equalsIgnoreCase(configuredRole) && !"PRIMARY".equalsIgnoreCase(targetNode.role())) {
            errors.add(String.format(
                "El rol 'cluster.node.role' (%s) en microprofile-config.properties no coincide con 'cluster.node.%s.role' (%s) en %s para el nodo '%s'.",
                configuredRole, nodeIdxStr, targetNode.role(), sourceLabel, targetNode.id()
            ));
        }

        // 4. Verificar IP del nodo local (cluster.node.ip) contra cluster.node.<N>.ip en cluster.config / cluster-state.config
        String[] ipMatch = getPropOrEnvMatch(dbProps,
            new String[]{"cluster.node.ip", "jettra.node.ip", "jettra.network.ip", "jettra.cluster.node.ip", "node.ip", "server.host"},
            new String[]{"CLUSTER_NODE_IP", "JETTRA_NODE_IP", "JETTRA_IP", "JETTRA_NETWORK_IP", "JETTRA_SERVER_HOST"});
        if (ipMatch != null) {
            String explicitIp = ipMatch[1];
            if (!"0.0.0.0".equals(explicitIp) && !"*".equals(explicitIp)
                    && targetNode != null && targetNode.ip() != null && !targetNode.ip().isBlank()
                    && !explicitIp.equalsIgnoreCase(targetNode.ip().trim())) {
                errors.add(String.format(
                    "La IP '%s' (%s) de microprofile-config.properties no coincide con 'cluster.node.%s.ip' ('%s') en %s para el nodo '%s'.",
                    ipMatch[0], explicitIp, nodeIdxStr, targetNode.ip().trim(), sourceLabel, targetNode.id()
                ));
            }
        }

        // 5. Resolver y verificar puerto gRPC contra cluster.node.<N>.grpc.port en cluster.config / cluster-state.config
        String[] grpcMatch = getPropOrEnvMatch(dbProps,
            new String[]{"cluster.node.grpc.port", "jettra.network.grpc.port", "jettra.grpc.port", "grpc.port"},
            new String[]{"CLUSTER_NODE_GRPC_PORT", "JETTRA_GRPC_PORT", "JETTRA_NETWORK_GRPC_PORT"});
        if (grpcMatch != null) {
            String grpcKey = grpcMatch[0];
            String explicitGrpcPortStr = grpcMatch[1];
            try {
                int grpcPort = Integer.parseInt(explicitGrpcPortStr);
                if (targetNode != null && targetNode.grpcPort() > 0) {
                    if (grpcPort != targetNode.grpcPort()) {
                        errors.add(String.format(
                            "El puerto '%s' (%d) de microprofile-config.properties no coincide con 'cluster.node.%s.grpc.port' (%d) de %s para el nodo '%s'.",
                            grpcKey, grpcPort, nodeIdxStr, targetNode.grpcPort(), sourceLabel, targetNode.id()
                        ));
                    }
                } else if (!nodeGrpcPorts.contains(grpcPort)) {
                    errors.add(String.format(
                        "El puerto '%s' (%d) de microprofile-config.properties no coincide con ningún puerto cluster.node.X.grpc.port de %s %s.",
                        grpcKey, grpcPort, sourceLabel, nodeGrpcPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de '" + grpcKey + "' ('" + explicitGrpcPortStr + "') no es un número de puerto válido.");
            }
        } else if (targetNode == null || targetNode.grpcPort() <= 0) {
            if (targetNode != null) {
                errors.add("El puerto 'cluster.node." + nodeIdxStr + ".grpc.port' en " + sourceLabel + " no es válido.");
            }
        }

        // 6. Resolver y verificar puerto REST contra cluster.node.<N>.rest.port en cluster.config / cluster-state.config
        String[] restMatch = getPropOrEnvMatch(dbProps,
            new String[]{"cluster.node.rest.port", "server.port", "jettra.network.rest.port", "jettra.rest.port", "rest.port", "jettra.server.port"},
            new String[]{"CLUSTER_NODE_REST_PORT", "JETTRA_REST_PORT", "JETTRA_NETWORK_REST_PORT", "JETTRA_SERVER_PORT", "SERVER_PORT"});
        if (restMatch != null) {
            String restKey = restMatch[0];
            String explicitRestPortStr = restMatch[1];
            try {
                int restPort = Integer.parseInt(explicitRestPortStr);
                if (targetNode != null && targetNode.restPort() > 0) {
                    if (restPort != targetNode.restPort()) {
                        errors.add(String.format(
                            "El puerto '%s' (%d) de microprofile-config.properties no coincide con 'cluster.node.%s.rest.port' (%d) de %s para el nodo '%s'.",
                            restKey, restPort, nodeIdxStr, targetNode.restPort(), sourceLabel, targetNode.id()
                        ));
                    }
                } else if (!nodeRestPorts.contains(restPort)) {
                    errors.add(String.format(
                        "El puerto '%s' (%d) de microprofile-config.properties no coincide con ningún puerto cluster.node.X.rest.port de %s %s.",
                        restKey, restPort, sourceLabel, nodeRestPorts
                    ));
                }
            } catch (NumberFormatException e) {
                errors.add("El valor de '" + restKey + "' ('" + explicitRestPortStr + "') no es un número de puerto válido.");
            }
        } else if (targetNode == null || targetNode.restPort() <= 0) {
            if (targetNode != null) {
                errors.add("El puerto 'cluster.node." + nodeIdxStr + ".rest.port' en " + sourceLabel + " no es válido.");
            }
        }

        // 7. Verificar cluster.multinode.active (on / off)
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
                ? String.format("[JettraStore] Validación exitosa: microprofile-config.properties y %s están debidamente sincronizados.", sourceLabel)
                : "[JettraStore] Validación completada con advertencias: " + String.join("; ", warnings);
            return new ValidationResult(true, List.of(), warnings, msg);
        }

        String notification = buildNotificationBanner(errors, sourceLabel);
        return new ValidationResult(false, errors, warnings, notification);
    }

    /**
     * Construye un banner visual informativo de advertencia y corrección requerida.
     */
    public static String buildNotificationBanner(List<String> errors) {
        return buildNotificationBanner(errors, "cluster.config");
    }

    /**
     * Construye un banner visual informativo indicando exactamente el archivo en conflicto
     * ({@code cluster.config} o {@code cluster-state.config}).
     */
    public static String buildNotificationBanner(List<String> errors, String sourceFileName) {
        String sourceLabel = (sourceFileName != null && !sourceFileName.isBlank())
            ? sourceFileName.trim()
            : "cluster.config";
        String subtitle = "(Incompatibilidad entre microprofile-config.properties y " + sourceLabel + ")";
        String subtitleLine;
        if (subtitle.length() <= 76) {
            int padLeft = (78 - subtitle.length()) / 2;
            int padRight = 78 - subtitle.length() - padLeft;
            subtitleLine = "║" + " ".repeat(padLeft) + subtitle + " ".repeat(padRight) + "║";
        } else {
            subtitleLine = "║ " + subtitle + " ║";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║            ERROR DE CONFIGURACIÓN AL INICIAR JETTRASTORE                     ║\n");
        sb.append(subtitleLine).append("\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
        sb.append("Se han detectado las siguientes incoherencias que impiden el inicio seguro:\n\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(String.format("  [%d] %s%n%n", i + 1, errors.get(i)));
        }
        sb.append("────────────────────────────────────────────────────────────────────────────────\n");
        sb.append("DIRECTIVAS DE CORRECCIÓN:\n");
        sb.append(" 1. Verifique que 'cluster.node.id' y 'cluster.node.role' en microprofile-config.properties coincidan\n");
        sb.append("    con 'cluster.node.<numero-nodo>.id' y '.role' en ").append(sourceLabel).append(" para el nodo PRIMARY.\n");
        sb.append(" 2. Verifique que 'cluster.node.ip', 'cluster.node.grpc.port' y 'cluster.node.rest.port' coincidan\n");
        sb.append("    exactamente con los valores declarados en ").append(sourceLabel).append(" para el nodo PRIMARY.\n");
        sb.append(" 3. Los nodos SECONDARY no requieren cluster.config y arrancan con microprofile-config.properties.\n");
        sb.append(" 4. Verifique que 'cluster.multinode.active' sea 'on' u 'off' (on=consenso distribuido, off=servidor local).\n");
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
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank() || "?".equals(home.trim())) {
                home = System.getenv("HOME");
            }
            if (home == null || home.isBlank()) {
                home = ".";
            }
            return (home.trim() + p.substring(1)).replace('\\', '/');
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
        String[] match = getPropOrEnvMatch(props, sysProps, envVars);
        return match != null ? match[1] : defaultVal;
    }

    private static String[] getPropOrEnvMatch(Properties props, String[] sysProps, String[] envVars) {
        if (sysProps != null) {
            for (String sp : sysProps) {
                String sys = System.getProperty(sp);
                if (sys != null && !sys.isBlank()) return new String[]{sp, sys.trim()};
            }
        }
        if (envVars != null) {
            for (String ev : envVars) {
                String env = System.getenv(ev);
                if (env != null && !env.isBlank()) return new String[]{ev, env.trim()};
            }
        }
        if (props != null && sysProps != null) {
            for (String sp : sysProps) {
                String val = props.getProperty(sp);
                if (val != null && !val.isBlank()) return new String[]{sp, val.trim()};
            }
        }
        return null;
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
     * Parsea únicamente nodos definidos explícitamente en el objeto Properties (sin fabricar nodos plantilla
     * cuando el archivo está vacío), preservando configuración real de semilla o de estado.
     */
    public static List<ClusterNodeInfo> parseExplicitClusterNodes(Properties props) {
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
        return list;
    }

    /**
     * Parsea los estados persistidos ({@code RUNNING}, {@code STOPPED}, {@code STANDBY}, {@code UNASSIGNED})
     * indexados por {@code nodeId} (en minúsculas) desde {@code cluster-state.config}.
     */
    public static Map<String, String> parseClusterNodeStatuses(Properties props) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (props == null) {
            return statuses;
        }
        for (ClusterNodeInfo cn : parseExplicitClusterNodes(props)) {
            String rawStatus = props.getProperty("cluster.node." + cn.nodeNumber() + ".status", "RUNNING").trim();
            statuses.put(cn.id().toLowerCase(Locale.ROOT), rawStatus.isEmpty() ? "RUNNING" : rawStatus.toUpperCase(Locale.ROOT));
        }
        return statuses;
    }

    public static long parseClusterStateVersion(Properties props) {
        if (props == null) return 0L;
        String raw = props.getProperty("cluster.state.version", "0").trim();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Parsea la lista de nodos configurados en cluster.config (soportando nodos iniciales y nodos agregados dinámicamente).
     */
    public static List<ClusterNodeInfo> parseClusterNodes(Properties props) {
        List<ClusterNodeInfo> list = parseExplicitClusterNodes(props);
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
     * Registra dinámicamente un nuevo nodo en el archivo de estado persistente {@code cluster-state.config}
     * sin alterar nunca el archivo semilla estático {@code cluster.config}.
     */
    public static synchronized ClusterNodeInfo registerDynamicNodeInClusterConfig(
            String nodeId,
            String role,
            String ip,
            int grpcPort,
            int restPort,
            String storagePath) {
        Path stateFile = resolveClusterStateFile();
        seedOrLoadClusterState(stateFile, locateOptionalClusterSeedFile());
        return registerDynamicNodeInClusterConfig(stateFile, nodeId, role, ip, grpcPort, restPort, storagePath);
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
        String resolvedIp = (ip != null && !ip.isBlank()) ? ip.trim() : "127.0.0.1";
        int resolvedGrpc = grpcPort > 0 ? grpcPort : 9091;
        int resolvedRest = restPort > 0 ? restPort : 8082;
        ValidationResult candidateCheck = validateCandidateNode(beforeNodes, nodeId, resolvedIp, resolvedGrpc, resolvedRest, true);
        if (!candidateCheck.isValid()) {
            throw new IllegalArgumentException(candidateCheck.getSummary());
        }
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
                boolean existsInDiskLines = false;
                List<String> lines = Files.readAllLines(clusterConfigFile, StandardCharsets.UTF_8);
                String prefix = "cluster.node." + registered.nodeNumber() + ".";
                for (String l : lines) {
                    if (l.trim().startsWith(prefix)) {
                        existsInDiskLines = true;
                        break;
                    }
                }
                if (!alreadyExistedById || !existsInDiskLines) {
                    String block = String.format(
                        "%n# ==============================================================================%n" +
                        "# NODO %s: NODO AGREGADO DINÁMICAMENTE (%s)%n" +
                        "# ==============================================================================%n" +
                        "cluster.node.%s.id = %s%n" +
                        "cluster.node.%s.role = %s%n" +
                        "cluster.node.%s.ip = %s%n" +
                        "cluster.node.%s.grpc.port = %d%n" +
                        "cluster.node.%s.rest.port = %d%n",
                        registered.nodeNumber(), registered.role(),
                        registered.nodeNumber(), registered.id(),
                        registered.nodeNumber(), registered.role(),
                        registered.nodeNumber(), registered.ip(),
                        registered.nodeNumber(), registered.grpcPort(),
                        registered.nodeNumber(), registered.restPort()
                    );
                    Files.writeString(clusterConfigFile, block, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    // Actualizar propiedades existentes en el archivo cluster.config
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
     * Valida la integridad completa de un archivo cluster.config (en texto):
     * - Al menos 1 nodo configurado
     * - Sin IDs de nodos duplicados
     * - Sin puertos gRPC o REST duplicados en la misma IP (ni colisiones gRPC/REST)
     * - Puertos válidos (1..65535) e IP/ID no vacíos
     */
    public static ValidationResult validateClusterConfigIntegrity(String content) {
        if (content == null || content.isBlank()) {
            return new ValidationResult(false, List.of("El contenido de cluster.config está vacío."), "Configuración vacía.");
        }
        return validateClusterConfigIntegrity(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Valida la integridad completa de un archivo cluster.config (en bytes).
     */
    public static ValidationResult validateClusterConfigIntegrity(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return new ValidationResult(false, List.of("El contenido de cluster.config está vacío."), "Configuración vacía.");
        }
        Properties props = new Properties();
        try (var bais = new java.io.ByteArrayInputStream(rawBytes)) {
            props.load(bais);
        } catch (IOException e) {
            return new ValidationResult(false, List.of("Error al parsear cluster.config: " + e.getMessage()), "Error de sintaxis.");
        }
        return validateClusterConfigIntegrity(props);
    }

    /**
     * Valida la integridad completa de un Properties de cluster.config:
     * detecta IDs duplicados, puertos gRPC/REST duplicados en la misma IP y entradas incompletas.
     */
    public static ValidationResult validateClusterConfigIntegrity(Properties clusterProps) {
        List<String> errors = new ArrayList<>();
        if (clusterProps == null) {
            errors.add("Las propiedades de cluster.config son nulas.");
            return new ValidationResult(false, errors, "Configuración nula.");
        }
        List<ClusterNodeInfo> nodes = parseClusterNodes(clusterProps);
        if (nodes.isEmpty()) {
            errors.add("cluster.config debe definir al menos un nodo válido (cluster.node.<N>.id).");
            return new ValidationResult(false, errors, "Sin nodos configurados.");
        }

        Map<String, String> seenIds = new LinkedHashMap<>();
        Map<String, String> seenGrpcEndpoints = new LinkedHashMap<>();
        Map<String, String> seenRestEndpoints = new LinkedHashMap<>();
        Map<String, String> seenAnyPorts = new LinkedHashMap<>();

        for (ClusterNodeInfo n : nodes) {
            String num = n.nodeNumber();
            String id = n.id() != null ? n.id().trim() : "";
            String ip = n.ip() != null ? n.ip().trim() : "";
            int grpc = n.grpcPort();
            int rest = n.restPort();

            if (id.isEmpty()) {
                errors.add("El nodo #" + num + " tiene un ID vacío.");
                continue;
            }
            if (ip.isEmpty()) {
                errors.add("El nodo '" + id + "' (#" + num + ") no tiene dirección IP definida.");
            }
            if (grpc <= 0 || grpc > 65535) {
                errors.add("El nodo '" + id + "' tiene un puerto gRPC inválido: " + grpc);
            }
            if (rest <= 0 || rest > 65535) {
                errors.add("El nodo '" + id + "' tiene un puerto REST inválido: " + rest);
            }
            if (grpc > 0 && rest > 0 && grpc == rest) {
                errors.add("El nodo '" + id + "' tiene el mismo puerto para gRPC y REST (" + grpc + ").");
            }

            String idKey = id.toLowerCase(Locale.ROOT);
            if (seenIds.containsKey(idKey)) {
                errors.add("ID de nodo duplicado: '" + id + "' (aparece en cluster.node." + seenIds.get(idKey) + " y cluster.node." + num + ").");
            } else {
                seenIds.put(idKey, num);
            }

            if (!ip.isEmpty() && grpc > 0) {
                String grpcKey = ip.toLowerCase(Locale.ROOT) + ":" + grpc;
                if (seenGrpcEndpoints.containsKey(grpcKey)) {
                    errors.add("Puerto gRPC duplicado: " + grpcKey + " ya está asignado al nodo '" + seenGrpcEndpoints.get(grpcKey) + "' y colisiona con '" + id + "'.");
                } else {
                    seenGrpcEndpoints.put(grpcKey, id);
                }
                if (seenAnyPorts.containsKey(grpcKey) && !seenAnyPorts.get(grpcKey).equalsIgnoreCase(id)) {
                    errors.add("Colisión de puerto en " + grpcKey + " entre '" + seenAnyPorts.get(grpcKey) + "' y '" + id + "'.");
                } else {
                    seenAnyPorts.put(grpcKey, id);
                }
            }

            if (!ip.isEmpty() && rest > 0) {
                String restKey = ip.toLowerCase(Locale.ROOT) + ":" + rest;
                if (seenRestEndpoints.containsKey(restKey)) {
                    errors.add("Puerto REST duplicado: " + restKey + " ya está asignado al nodo '" + seenRestEndpoints.get(restKey) + "' y colisiona con '" + id + "'.");
                } else {
                    seenRestEndpoints.put(restKey, id);
                }
                if (seenAnyPorts.containsKey(restKey) && !seenAnyPorts.get(restKey).equalsIgnoreCase(id)) {
                    errors.add("Colisión de puerto en " + restKey + " entre '" + seenAnyPorts.get(restKey) + "' y '" + id + "'.");
                } else {
                    seenAnyPorts.put(restKey, id);
                }
            }
        }

        boolean valid = errors.isEmpty();
        String summary = valid
            ? "Integridad de cluster.config validada (" + nodes.size() + " nodos sin duplicados)."
            : "Errores de integridad en cluster.config: " + String.join(" | ", errors);
        return new ValidationResult(valid, errors, summary);
    }

    /**
     * Valida un nodo candidato antes de agregarlo o actualizarlo, evitando IDs o puertos gRPC/REST duplicados.
     */
    public static ValidationResult validateCandidateNode(
            List<ClusterNodeInfo> existingNodes,
            String nodeId,
            String ip,
            int grpcPort,
            int restPort,
            boolean allowUpdateSameId) {
        List<String> errors = new ArrayList<>();
        String cleanId = nodeId != null ? nodeId.trim() : "";
        String cleanIp = ip != null ? ip.trim() : "";
        if (cleanId.isEmpty()) {
            errors.add("El ID del nodo es obligatorio.");
        }
        if (cleanIp.isEmpty()) {
            errors.add("La IP/Host del nodo es obligatoria.");
        }
        if (grpcPort <= 0 || grpcPort > 65535) {
            errors.add("Puerto gRPC inválido: " + grpcPort);
        }
        if (restPort <= 0 || restPort > 65535) {
            errors.add("Puerto REST inválido: " + restPort);
        }
        if (grpcPort > 0 && restPort > 0 && grpcPort == restPort) {
            errors.add("El puerto gRPC y el puerto REST no pueden ser iguales (" + grpcPort + ").");
        }

        if (existingNodes != null && !cleanId.isEmpty()) {
            for (ClusterNodeInfo existing : existingNodes) {
                boolean sameId = existing.id() != null && existing.id().equalsIgnoreCase(cleanId);
                if (sameId) {
                    if (!allowUpdateSameId) {
                        errors.add("ID de nodo duplicado: '" + cleanId + "' ya existe en el clúster.");
                    }
                    continue;
                }
                String existingIp = existing.ip() != null ? existing.ip().trim() : "";
                boolean sameIp = existingIp.equalsIgnoreCase(cleanIp);
                if (sameIp && grpcPort > 0 && existing.grpcPort() == grpcPort) {
                    errors.add("Puerto gRPC duplicado: " + cleanIp + ":" + grpcPort + " ya está en uso por el nodo '" + existing.id() + "'.");
                }
                if (sameIp && restPort > 0 && existing.restPort() == restPort) {
                    errors.add("Puerto REST duplicado: " + cleanIp + ":" + restPort + " ya está en uso por el nodo '" + existing.id() + "'.");
                }
                if (sameIp && grpcPort > 0 && existing.restPort() == grpcPort) {
                    errors.add("El puerto gRPC " + grpcPort + " colisiona con el puerto REST del nodo '" + existing.id() + "'.");
                }
                if (sameIp && restPort > 0 && existing.grpcPort() == restPort) {
                    errors.add("El puerto REST " + restPort + " colisiona con el puerto gRPC del nodo '" + existing.id() + "'.");
                }
            }
        }
        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, valid ? "Nodo válido." : String.join(" | ", errors));
    }

    /**
     * Genera el contenido canónico y re-indexado (1..N) de cluster-state.config / cluster.config a partir de una lista de nodos.
     */
    public static byte[] buildCanonicalClusterConfigBytes(List<ClusterNodeInfo> nodes, Properties baseProps) {
        Map<String, String> existingStatuses = parseClusterNodeStatuses(baseProps);
        long currentVersion = parseClusterStateVersion(baseProps);
        long nextVersion = currentVersion > 0 ? currentVersion + 1 : 1L;
        String updatedBy = baseProps != null ? baseProps.getProperty("cluster.state.updated.by", "cluster-admin") : "cluster-admin";
        return buildCanonicalClusterStateBytes(nodes, existingStatuses, baseProps, nextVersion, updatedBy);
    }

    public static byte[] buildCanonicalClusterStateBytes(
            List<ClusterNodeInfo> nodes,
            Map<String, String> nodeStatuses,
            Properties baseProps,
            long stateVersion,
            String updatedBy) {
        String clusterName = baseProps != null ? baseProps.getProperty("cluster.name", "jettra-production-cluster") : "jettra-production-cluster";
        String protocol = baseProps != null ? baseProps.getProperty("cluster.consensus.protocol", "RAFT") : "RAFT";
        String ringEnabled = baseProps != null ? baseProps.getProperty("cluster.ring.enabled", "true") : "true";
        String hb = baseProps != null ? baseProps.getProperty("cluster.heartbeat.interval.ms", "150") : "150";
        String elec = baseProps != null ? baseProps.getProperty("cluster.election.timeout.ms", "300") : "300";
        String idxCap = baseProps != null ? baseProps.getProperty("cluster.index.initial.capacity", "65536") : "65536";
        String idxMax = baseProps != null ? baseProps.getProperty("cluster.index.max.inmemory.keys", "100000") : "100000";
        long effectiveVersion = Math.max(1L, stateVersion);
        long epochMs = System.currentTimeMillis();
        String actor = (updatedBy != null && !updatedBy.isBlank()) ? updatedBy.trim() : "cluster-admin";

        StringBuilder sb = new StringBuilder();
        sb.append("################################################################################\n");
        sb.append("# JettraStore Persistent Cluster State (cluster-state.config)\n");
        sb.append("# Authoritative Topology & Node Membership State (Raft / Gossip Sync)\n");
        sb.append("################################################################################\n\n");
        sb.append("cluster.name = ").append(clusterName).append("\n");
        sb.append("cluster.consensus.protocol = ").append(protocol).append("\n");
        sb.append("cluster.ring.enabled = ").append(ringEnabled).append("\n");
        sb.append("cluster.heartbeat.interval.ms = ").append(hb).append("\n");
        sb.append("cluster.election.timeout.ms = ").append(elec).append("\n");
        sb.append("cluster.state.version = ").append(effectiveVersion).append("\n");
        sb.append("cluster.state.epoch = ").append(epochMs).append("\n");
        sb.append("cluster.state.updated.by = ").append(actor).append("\n");

        int idx = 1;
        for (ClusterNodeInfo n : nodes) {
            String role = (n.role() != null && n.role().equalsIgnoreCase("PRIMARY")) ? "PRIMARY" : "SECONDARY";
            String ip = (n.ip() != null && !n.ip().isBlank()) ? n.ip().trim() : "127.0.0.1";
            int grpc = n.grpcPort() > 0 ? n.grpcPort() : (50000 + idx);
            int rest = n.restPort() > 0 ? n.restPort() : (60000 + idx);
            String st = (nodeStatuses != null)
                ? nodeStatuses.getOrDefault(n.id().trim().toLowerCase(Locale.ROOT), "RUNNING")
                : "RUNNING";
            sb.append("\n# ==============================================================================\n");
            sb.append("# NODO ").append(idx).append(": ").append(n.id().trim()).append(" (").append(role).append(")\n");
            sb.append("# ==============================================================================\n");
            sb.append("cluster.node.").append(idx).append(".id = ").append(n.id().trim()).append("\n");
            sb.append("cluster.node.").append(idx).append(".role = ").append(role).append("\n");
            sb.append("cluster.node.").append(idx).append(".ip = ").append(ip).append("\n");
            sb.append("cluster.node.").append(idx).append(".grpc.port = ").append(grpc).append("\n");
            sb.append("cluster.node.").append(idx).append(".rest.port = ").append(rest).append("\n");
            sb.append("cluster.node.").append(idx).append(".status = ").append(st).append("\n");
            idx++;
        }

        sb.append("\n# Asignación de Capacidad de Índices y Buffers en Clúster\n");
        sb.append("cluster.index.initial.capacity = ").append(idxCap).append("\n");
        sb.append("cluster.index.max.inmemory.keys = ").append(idxMax).append("\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Escribe de forma atómica y durable (tmp + fsync + ATOMIC_MOVE) el archivo de estado/configuración del clúster
     * solo si supera la verificación de integridad y difiere del contenido actual, usando lock por ruta.
     */
    public static boolean writeClusterConfigAtomically(Path target, byte[] newBytes) throws IOException {
        if (target == null || newBytes == null || newBytes.length == 0) {
            return false;
        }
        // Validar integridad completa antes de escribir (sin IDs ni puertos duplicados)
        ValidationResult integrity = validateClusterConfigIntegrity(newBytes);
        if (!integrity.isValid()) {
            System.err.printf("[JettraStore] Rechazada escritura de estado/config en '%s' por fallo de integridad: %s%n",
                target, integrity.getSummary());
            return false;
        }
        ReentrantLock lock = lockForStateFile(target);
        lock.lock();
        try {
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (java.util.Arrays.equals(existing, newBytes)) {
                    return false; // Ya es idéntico; evitar escritura redundante
                }
            }
            writeAtomicBytesInternal(target, newBytes);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static void writeAtomicBytesInternal(Path target, byte[] newBytes) throws IOException {
        if (target.getParent() != null && !Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp." + System.nanoTime());
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(newBytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Carga {@code cluster-state.config} si ya existe en {@code jettra.cluster_state_config_dir}
     * (sin permitir nunca que {@code cluster.config} lo sobrescriba); si aún no existe y hay un archivo
     * semilla opcional {@code cluster.config}, crea atómicamente {@code cluster-state.config} desde esa semilla.
     * Si no existe ninguno, retorna {@code null} para que el nodo inicie en espera ({@code STANDBY}).
     */
    public static Properties seedOrLoadClusterState(Path stateFile, Path optionalSeedFile) {
        if (stateFile == null) {
            return null;
        }
        ReentrantLock lock = lockForStateFile(stateFile);
        lock.lock();
        try {
            if (stateFile.getParent() != null && !Files.exists(stateFile.getParent())) {
                Files.createDirectories(stateFile.getParent());
            }
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(stateFile)) {
                    props.load(is);
                }
                if (!parseExplicitClusterNodes(props).isEmpty()) {
                    return props;
                }
            }
            if (optionalSeedFile != null && Files.exists(optionalSeedFile) && Files.size(optionalSeedFile) > 0) {
                Properties seedProps = new Properties();
                try (InputStream is = Files.newInputStream(optionalSeedFile)) {
                    seedProps.load(is);
                }
                List<ClusterNodeInfo> parsedSeedNodes = parseExplicitClusterNodes(seedProps);
                if (!parsedSeedNodes.isEmpty()) {
                    // Normalizar rest.port solo cuando no se definió explícitamente y colisiona el fallback (8080)
                    List<ClusterNodeInfo> seedNodes = normalizeOmittedRestPorts(parsedSeedNodes, seedProps);
                    Map<String, String> statuses = parseClusterNodeStatuses(seedProps);
                    byte[] stateBytes = buildCanonicalClusterStateBytes(seedNodes, statuses, seedProps, 1L, "seed-bootstrap");
                    boolean wrote = writeClusterConfigAtomically(stateFile, stateBytes);
                    if (!wrote && !Files.exists(stateFile)) {
                        System.err.printf("[JettraStore] No se pudo inicializar '%s' desde semilla '%s'.%n",
                            stateFile, optionalSeedFile);
                        return null;
                    }
                    Properties loaded = new Properties();
                    try (var bais = new java.io.ByteArrayInputStream(Files.readAllBytes(stateFile))) {
                        loaded.load(bais);
                    }
                    System.out.printf("[JettraStore] Estado persistente inicializado en '%s' desde semilla estática '%s' (%d nodo(s)).%n",
                        stateFile, optionalSeedFile, seedNodes.size());
                    return loaded;
                }
            }
        } catch (Exception ex) {
            System.err.printf("[JettraStore] Advertencia cargando/inicializando cluster-state.config en '%s': %s%n",
                stateFile, ex.getMessage());
        } finally {
            lock.unlock();
        }
        return null;
    }

    private static List<ClusterNodeInfo> normalizeOmittedRestPorts(List<ClusterNodeInfo> nodes, Properties sourceProps) {
        List<ClusterNodeInfo> normalized = new ArrayList<>(nodes.size());
        Set<String> usedRest = new HashSet<>();
        for (ClusterNodeInfo cn : nodes) {
            String ip = (cn.ip() != null && !cn.ip().isBlank()) ? cn.ip().trim() : "127.0.0.1";
            int restPort = cn.restPort();
            String explicitKey = "cluster.node." + cn.nodeNumber() + ".rest.port";
            boolean hasExplicitRest = sourceProps != null && sourceProps.containsKey(explicitKey);
            String key = ip.toLowerCase(Locale.ROOT) + ":" + restPort;
            if (!hasExplicitRest && (usedRest.contains(key) || restPort <= 0 || restPort == cn.grpcPort())) {
                int derived = (cn.grpcPort() >= 50001 && cn.grpcPort() <= 50999)
                    ? 60000 + (cn.grpcPort() - 50000)
                    : (cn.grpcPort() + 10000 <= 65535 ? cn.grpcPort() + 10000 : Math.max(8081, cn.grpcPort() - 1000));
                while ((usedRest.contains(ip.toLowerCase(Locale.ROOT) + ":" + derived) || derived == cn.grpcPort()) && derived < 65535) {
                    derived++;
                }
                restPort = derived;
                key = ip.toLowerCase(Locale.ROOT) + ":" + restPort;
            }
            usedRest.add(key);
            normalized.add(new ClusterNodeInfo(cn.id(), cn.role(), ip, cn.grpcPort(), restPort, cn.storagePath(), cn.nodeNumber()));
        }
        return normalized;
    }

    /**
     * Inicializa atómicamente {@code cluster-state.config} si aún no existe cuando el nodo inicia
     * como {@code PRIMARY} (single-node o líder inicial sin {@code cluster.config}) o recibe pares
     * explícitos mediante {@code JETTRA_CLUSTER_PEERS}. Nunca sobrescribe un {@code cluster-state.config} existente.
     */
    public static Properties bootstrapInitialPrimaryOrPeersState(Path stateFile, Properties configProps) {
        if (stateFile == null) {
            return null;
        }
        ReentrantLock lock = lockForStateFile(stateFile);
        lock.lock();
        try {
            if (stateFile.getParent() != null && !Files.exists(stateFile.getParent())) {
                Files.createDirectories(stateFile.getParent());
            }
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                Properties existing = new Properties();
                try (InputStream is = Files.newInputStream(stateFile)) {
                    existing.load(is);
                }
                if (!parseExplicitClusterNodes(existing).isEmpty()) {
                    return existing;
                }
            }

            String envPeers = getPropOrEnv(configProps,
                new String[]{"jettra.cluster.peers", "cluster.peers"},
                new String[]{"JETTRA_CLUSTER_PEERS"}, "");
            boolean hasExplicitPeersEnv = envPeers != null && !envPeers.isBlank();

            String nodeId = getPropOrEnv(configProps,
                new String[]{"cluster.node.id", "jettra.node.id", "jettra.cluster.node.id", "node.id"},
                new String[]{"CLUSTER_NODE_ID", "JETTRA_NODE_ID", "JETTRA_CLUSTER_NODE_ID"},
                "node-01");
            String nodeRole = getPropOrEnv(configProps,
                new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
                new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"},
                "PRIMARY").toUpperCase(Locale.ROOT);

            if (!hasExplicitPeersEnv && !"PRIMARY".equalsIgnoreCase(nodeRole)) {
                return null; // Nodo secundario/adicional sin semilla espera en STANDBY
            }

            String nodeIp = getPropOrEnv(configProps,
                new String[]{"cluster.node.ip", "jettra.node.ip", "jettra.network.ip", "jettra.cluster.node.ip", "node.ip", "server.host"},
                new String[]{"CLUSTER_NODE_IP", "JETTRA_NODE_IP", "JETTRA_IP", "JETTRA_NETWORK_IP", "JETTRA_SERVER_HOST"},
                "127.0.0.1");
            if ("0.0.0.0".equals(nodeIp) || "*".equals(nodeIp)) {
                nodeIp = "127.0.0.1";
            }
            int grpcPort = parseInt(getPropOrEnv(configProps,
                new String[]{"cluster.node.grpc.port", "jettra.network.grpc.port", "jettra.grpc.port", "grpc.port"},
                new String[]{"CLUSTER_NODE_GRPC_PORT", "JETTRA_GRPC_PORT", "JETTRA_NETWORK_GRPC_PORT"},
                "50001"), 50001);
            int restPort = parseInt(getPropOrEnv(configProps,
                new String[]{"cluster.node.rest.port", "jettra.network.rest.port", "jettra.rest.port", "server.port", "jettra.server.port", "rest.port"},
                new String[]{"CLUSTER_NODE_REST_PORT", "JETTRA_REST_PORT", "JETTRA_SERVER_PORT", "SERVER_PORT", "JETTRA_NETWORK_REST_PORT"},
                "60001"), 60001);
            String storagePath = getPropOrEnv(configProps,
                new String[]{"jettra.storage.path", "storage.path", "data.dir"},
                new String[]{"JETTRA_STORAGE_PATH", "STORAGE_PATH", "JETTRA_DATA_DIR"},
                "~/jettra-storage-01/data");

            List<ClusterNodeInfo> nodes = new ArrayList<>();
            Map<String, String> statuses = new LinkedHashMap<>();
            Set<String> seenIds = new HashSet<>();
            Set<String> usedRest = new HashSet<>();

            nodes.add(new ClusterNodeInfo(nodeId, nodeRole, nodeIp, grpcPort, restPort, storagePath));
            seenIds.add(nodeId.toLowerCase(Locale.ROOT));
            usedRest.add(nodeIp.toLowerCase(Locale.ROOT) + ":" + restPort);
            statuses.put(nodeId.toLowerCase(Locale.ROOT), "RUNNING");

            if (hasExplicitPeersEnv) {
                for (String entry : envPeers.split(",")) {
                    String[] p = entry.trim().split(":");
                    if (p.length >= 3) {
                        String pid = p[0].trim();
                        String phost = p[1].trim();
                        int pport = parseInt(p[2].trim(), 50001);
                        String prole = (p.length >= 4 && "PRIMARY".equalsIgnoreCase(p[3].trim())) ? "PRIMARY" : "SECONDARY";
                        if (pid.isBlank() || phost.isBlank() || pport <= 0 || seenIds.contains(pid.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        int prest = (pport >= 50001 && pport <= 50999)
                            ? 60000 + (pport - 50000)
                            : (pport + 10000 <= 65535 ? pport + 10000 : Math.max(8081, pport - 1000));
                        while ((usedRest.contains(phost.toLowerCase(Locale.ROOT) + ":" + prest) || prest == pport) && prest < 65535) {
                            prest++;
                        }
                        usedRest.add(phost.toLowerCase(Locale.ROOT) + ":" + prest);
                        seenIds.add(pid.toLowerCase(Locale.ROOT));
                        nodes.add(new ClusterNodeInfo(pid, prole, phost, pport, prest, storagePath));
                        statuses.put(pid.toLowerCase(Locale.ROOT), "RUNNING");
                    }
                }
            }

            byte[] initialBytes = buildCanonicalClusterStateBytes(nodes, statuses, configProps, 1L, nodeId);
            if (!writeClusterConfigAtomically(stateFile, initialBytes) && !Files.exists(stateFile)) {
                return null;
            }
            Properties loaded = new Properties();
            try (var bais = new java.io.ByteArrayInputStream(Files.readAllBytes(stateFile))) {
                loaded.load(bais);
            }
            System.out.printf("[JettraStore] Estado persistente inicializado en '%s' (%d nodo(s), nodo local='%s' [%s]).%n",
                stateFile, nodes.size(), nodeId, nodeRole);
            return loaded;
        } catch (Exception ex) {
            System.err.printf("[JettraStore] Error inicializando cluster-state.config en '%s': %s%n",
                stateFile, ex.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Actualiza atómicamente el estado ({@code RUNNING}, {@code STOPPED}, {@code STANDBY}) de un nodo
     * dentro de {@code cluster-state.config}, incrementando la versión de estado.
     */
    public static byte[] updateNodeStatusInStateFile(
            Path stateFile, String nodeId, String status, String updatedBy) {
        if (stateFile == null || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        ReentrantLock lock = lockForStateFile(stateFile);
        lock.lock();
        try {
            if (!Files.exists(stateFile)) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(stateFile)) {
                props.load(is);
            }
            List<ClusterNodeInfo> nodes = parseExplicitClusterNodes(props);
            if (nodes.isEmpty()) {
                return null;
            }
            Map<String, String> statuses = parseClusterNodeStatuses(props);
            String normalizedStatus = (status != null && !status.isBlank()) ? status.trim().toUpperCase(Locale.ROOT) : "RUNNING";
            String cleanId = nodeId.trim();
            boolean found = false;
            for (ClusterNodeInfo cn : nodes) {
                if (cn.id() != null && cn.id().equalsIgnoreCase(cleanId)) {
                    statuses.put(cn.id().toLowerCase(Locale.ROOT), normalizedStatus);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
            long nextVer = Math.max(1L, parseClusterStateVersion(props) + 1L);
            byte[] newBytes = buildCanonicalClusterStateBytes(nodes, statuses, props, nextVer, updatedBy);
            writeClusterConfigAtomically(stateFile, newBytes);
            return newBytes;
        } catch (Exception ex) {
            System.err.printf("[JettraStore] Error actualizando estado de '%s' en '%s': %s%n",
                nodeId, stateFile, ex.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserta o actualiza atómicamente un nodo y su estado dentro de {@code cluster-state.config}
     * sin alterar nunca el archivo estático {@code cluster.config}.
     */
    public static byte[] upsertNodeInStateFile(
            Path stateFile, String nodeId, String role, String ip, int grpcPort, int restPort, String status, String updatedBy) {
        if (stateFile == null || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        ReentrantLock lock = lockForStateFile(stateFile);
        lock.lock();
        try {
            Properties props = new Properties();
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                try (InputStream is = Files.newInputStream(stateFile)) {
                    props.load(is);
                }
            }
            List<ClusterNodeInfo> before = parseExplicitClusterNodes(props);
            String resolvedIp = (ip != null && !ip.isBlank()) ? ip.trim() : "127.0.0.1";
            int resolvedGrpc = grpcPort > 0 ? grpcPort : 50001;
            int resolvedRest = restPort > 0 ? restPort : 60001;
            ValidationResult candidateCheck = validateCandidateNode(before, nodeId, resolvedIp, resolvedGrpc, resolvedRest, true);
            if (!candidateCheck.isValid()) {
                throw new IllegalArgumentException(candidateCheck.getSummary());
            }

            Map<String, String> statuses = parseClusterNodeStatuses(props);
            ClusterNodeInfo registered = registerDynamicNodeInProperties(
                props, nodeId, role, resolvedIp, resolvedGrpc, resolvedRest, null);
            List<ClusterNodeInfo> after = parseExplicitClusterNodes(props);
            String st = (status != null && !status.isBlank()) ? status.trim().toUpperCase(Locale.ROOT) : "RUNNING";
            statuses.put(registered.id().toLowerCase(Locale.ROOT), st);

            long nextVer = Math.max(1L, parseClusterStateVersion(props) + 1L);
            byte[] newBytes = buildCanonicalClusterStateBytes(after, statuses, props, nextVer, updatedBy);
            writeClusterConfigAtomically(stateFile, newBytes);
            return newBytes;
        } catch (IOException ex) {
            throw new RuntimeException("Error persistiendo nodo '" + nodeId + "' en '" + stateFile + "': " + ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Elimina dinámicamente un nodo del archivo de estado persistente especificado o de {@code cluster-state.config}.
     */
    public static synchronized boolean removeDynamicNodeFromClusterConfig(String nodeId) {
        Path stateFile = locateActiveClusterStateOrSeedFile();
        if (stateFile == null) {
            stateFile = resolveClusterStateFile();
        }
        return removeDynamicNodeFromClusterConfig(stateFile, nodeId);
    }

    /**
     * Elimina dinámicamente TODAS las referencias de un nodo del archivo cluster.config especificado en disco,
     * re-indexa de forma limpia y verifica integridad antes de escribir atómicamente.
     */
    public static synchronized boolean removeDynamicNodeFromClusterConfig(Path clusterConfigFile, String nodeId) {
        if (clusterConfigFile == null || nodeId == null || nodeId.isBlank() || "node-01".equalsIgnoreCase(nodeId.trim())) {
            return false;
        }
        try {
            if (!Files.exists(clusterConfigFile)) {
                return false;
            }
            Properties props = loadProperties(clusterConfigFile, "/cluster.config", "/jettra.config");
            List<ClusterNodeInfo> nodes = parseClusterNodes(props);
            String cleanTargetId = nodeId.trim();
            List<ClusterNodeInfo> remaining = new ArrayList<>();
            boolean removedAny = false;
            for (ClusterNodeInfo n : nodes) {
                if (n.id() != null && n.id().trim().equalsIgnoreCase(cleanTargetId)) {
                    removedAny = true;
                } else {
                    remaining.add(n);
                }
            }
            if (!removedAny || remaining.isEmpty()) {
                return false;
            }
            // Sanear posibles puertos REST heredados/duplicados en nodos restantes antes de validar
            List<ClusterNodeInfo> sanitized = new ArrayList<>(remaining.size());
            Set<String> usedEndpoints = new HashSet<>();
            for (ClusterNodeInfo n : remaining) {
                String ip = (n.ip() != null && !n.ip().isBlank()) ? n.ip().trim() : "127.0.0.1";
                int gp = n.grpcPort() > 0 ? n.grpcPort() : 50001;
                usedEndpoints.add(ip.toLowerCase(java.util.Locale.ROOT) + ":" + gp);
            }
            int idxFallback = 1;
            for (ClusterNodeInfo n : remaining) {
                String ip = (n.ip() != null && !n.ip().isBlank()) ? n.ip().trim() : "127.0.0.1";
                String ipKey = ip.toLowerCase(java.util.Locale.ROOT);
                int gp = n.grpcPort() > 0 ? n.grpcPort() : (50000 + idxFallback);
                int rp = n.restPort();
                if (rp <= 0 || rp == gp || usedEndpoints.contains(ipKey + ":" + rp)) {
                    if (gp >= 50001 && gp <= 50999) rp = 60000 + (gp - 50000);
                    else if (gp >= 9091 && gp <= 9999) rp = 8080 + (gp - 9090);
                    else rp = 60000 + idxFallback;
                    while (rp == gp || usedEndpoints.contains(ipKey + ":" + rp)) {
                        rp++;
                    }
                }
                usedEndpoints.add(ipKey + ":" + rp);
                sanitized.add(new ClusterNodeInfo(n.id(), n.role(), ip, gp, rp, n.storagePath(), String.valueOf(idxFallback)));
                idxFallback++;
            }
            byte[] canonicalBytes = buildCanonicalClusterConfigBytes(sanitized, props);
            ValidationResult integrity = validateClusterConfigIntegrity(canonicalBytes);
            if (!integrity.isValid()) {
                System.err.printf("[JettraStore] Integridad fallida tras eliminar nodo '%s': %s%n",
                    cleanTargetId, integrity.getSummary());
                return false;
            }
            writeClusterConfigAtomically(clusterConfigFile, canonicalBytes);
            return true;
        } catch (IOException e) {
            System.err.printf("[JettraStore] Error al eliminar nodo '%s' de '%s': %s%n",
                nodeId, clusterConfigFile, e.getMessage());
            return false;
        }
    }

    /**
     * Carga propiedades buscando primero en el classpath y sobreescribiendo con el archivo en disco si existe.
     */
    public static Properties loadProperties(Path diskPath, String classpathResource) {
        return loadProperties(diskPath, new String[]{classpathResource});
    }

    public static Properties loadProperties(Path diskPath, String... classpathResources) {
        Properties props = new Properties();
        boolean diskExists = diskPath != null && Files.exists(diskPath);
        if (diskExists) {
            try (InputStream is = Files.newInputStream(diskPath)) {
                props.load(is);
            } catch (IOException ignored) {}
            // Si el archivo en disco ya define explícitamente nodos del clúster (cluster.node.*),
            // no mezclar nodos del classpath para evitar nodos plantilla fantasma
            boolean hasDiskClusterNodes = props.stringPropertyNames().stream().anyMatch(k -> k.startsWith("cluster.node."));
            if (hasDiskClusterNodes) {
                return props;
            }
        }
        if (classpathResources != null) {
            Properties cpProps = new Properties();
            for (String cpRes : classpathResources) {
                if (cpRes == null) continue;
                try (InputStream is = JettraConfigValidator.class.getResourceAsStream(cpRes)) {
                    if (is != null) {
                        cpProps.load(is);
                    }
                } catch (IOException ignored) {}
            }
            for (String name : cpProps.stringPropertyNames()) {
                if (diskExists && name.startsWith("cluster.node.")) {
                    continue;
                }
                if (!props.containsKey(name)) {
                    props.setProperty(name, cpProps.getProperty(name));
                }
            }
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
            Path mpPathDocker = locateMicroProfileConfigFile();
            Properties mpDocker = mpPathDocker != null
                ? loadProperties(mpPathDocker, "/META-INF/microprofile-config.properties", "/microprofile-config.properties")
                : new Properties();
            String dockerRole = JettraStoreConfig.getPropOrEnv(mpDocker,
                new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
                new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"},
                "PRIMARY");
            boolean isPrimaryDocker = "PRIMARY".equalsIgnoreCase(dockerRole);

            Path dockerStateDir = ensureClusterStateConfigDirExists(mpDocker);
            Path dockerStateFile = dockerStateDir.resolve(CLUSTER_STATE_FILENAME);
            Path dockerSeed = isPrimaryDocker
                ? locateOptionalClusterSeedFile(dockerStateDir)
                : null;
            Properties dockerState = seedOrLoadClusterState(dockerStateFile, dockerSeed);
            if (dockerState == null) {
                bootstrapInitialPrimaryOrPeersState(dockerStateFile, mpDocker);
            }
            System.out.println("[JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada mediante docker-compose / variables de entorno.");
            return new ValidationResult(true, List.of(), "Entorno Docker detectado.");
        }

        // 1. Asegurar microprofile-config.properties y jettra.cluster_state_config_dir (cluster.config es semilla exclusiva de PRIMARY)
        ensureConfigFilesExist();

        // 2. Cargar configuraciones
        Path dbPath = locateMicroProfileConfigFile();
        Properties dbProps = loadProperties(dbPath, "/META-INF/microprofile-config.properties", "/microprofile-config.properties");
        Path stateFile = resolveClusterStateFile(dbProps);

        String configuredRole = JettraStoreConfig.getPropOrEnv(dbProps,
            new String[]{"cluster.node.role", "jettra.node.role", "jettra.cluster.node.role", "node.role"},
            new String[]{"CLUSTER_NODE_ROLE", "JETTRA_NODE_ROLE", "JETTRA_CLUSTER_NODE_ROLE"},
            "PRIMARY");
        boolean isPrimaryRole = "PRIMARY".equalsIgnoreCase(configuredRole);
        String multiStr = JettraStoreConfig.getPropOrEnv(dbProps,
            new String[]{"cluster.multinode.active", "jettra.cluster.multinode.active"},
            new String[]{"JETTRA_CLUSTER_MULTINODE_ACTIVE", "JETTRA_MULTINODE_ACTIVE"},
            "on");
        boolean multinodeActive = "on".equalsIgnoreCase(multiStr) || "true".equalsIgnoreCase(multiStr);
        String peersEnv = JettraStoreConfig.getPropOrEnv(dbProps,
            new String[]{"jettra.cluster.peers", "cluster.peers"},
            new String[]{"JETTRA_CLUSTER_PEERS"},
            "");

        // Evento 2 (SECONDARY): nunca lee ni exige cluster.config; solo carga cluster-state.config si ya existe en jettra.cluster_state_config_dir.
        // Evento 1 (PRIMARY): si aún no existe cluster-state.config, localiza cluster.config (incluyendo layout NetBeans/classpath),
        // valida estrictamente contra cluster.config ANTES de publicar estado y pasa su contenido a cluster-state.config.
        Properties stateProps = seedOrLoadClusterState(stateFile, null);
        if (stateProps == null && isPrimaryRole) {
            Path optionalSeed = locatePrimarySeedFile(dbPath, stateFile.getParent());
            if (optionalSeed != null && Files.exists(optionalSeed)) {
                Properties seedProps = loadProperties(optionalSeed);
                try {
                    byte[] rawSeed = Files.readAllBytes(optionalSeed);
                    ValidationResult integrity = validateClusterConfigIntegrity(rawSeed);
                    if (!integrity.isValid()) {
                        failValidationOrHalt(integrity.getErrors(), integrity.getSummary(), haltOnFailure);
                    }
                } catch (IOException ioEx) {
                    String msg = "Error leyendo cluster.config ('" + optionalSeed + "'): " + ioEx.getMessage();
                    failValidationOrHalt(List.of(msg), msg, haltOnFailure);
                }
                ValidationResult preSeedResult = validate(dbProps, seedProps, "cluster.config");
                if (preSeedResult.hasWarnings()) {
                    for (String w : preSeedResult.getWarnings()) {
                        System.err.println("[JettraStore] " + w);
                    }
                }
                if (!preSeedResult.isValid()) {
                    System.err.println(preSeedResult.getNotification());
                    boolean skipExit = Boolean.getBoolean("jettra.test.skip.exit");
                    if (haltOnFailure && !skipExit) {
                        System.exit(1);
                    } else {
                        throw new JettraConfigurationException(preSeedResult.getNotification());
                    }
                }
                stateProps = seedOrLoadClusterState(stateFile, optionalSeed);
            } else {
                if (multinodeActive && (peersEnv == null || peersEnv.isBlank())) {
                    stateProps = seedClusterStateFromClasspathIfApplicable(stateFile, dbPath, dbProps);
                }
                if (stateProps == null) {
                    stateProps = bootstrapInitialPrimaryOrPeersState(stateFile, dbProps);
                }
                if (stateProps == null && multinodeActive && (peersEnv == null || peersEnv.isBlank())) {
                    String msg = "Error crítico: El nodo PRIMARY requiere el archivo cluster.config (o un cluster-state.config existente en '"
                        + stateFile.getParent().toAbsolutePath().normalize() + "') para iniciar el clúster.";
                    System.err.println("[JettraStore] " + msg);
                    boolean skipExit = Boolean.getBoolean("jettra.test.skip.exit");
                    if (haltOnFailure && !skipExit) {
                        System.exit(1);
                    } else {
                        throw new JettraConfigurationException(msg);
                    }
                }
            }
        }

        String dbFileName = dbPath != null ? dbPath.getFileName().toString() : "microprofile-config.properties";
        System.out.println("[JettraStore] ================================================================================");
        System.out.printf("[JettraStore] Leyendo configuración de nodo (%s): %s%n",
            dbFileName, dbPath != null ? dbPath.toAbsolutePath().normalize() : "classpath:/META-INF/microprofile-config.properties");
        System.out.printf("[JettraStore] Directorio de estado persistente (jettra.cluster_state_config_dir): %s%n",
            stateFile.getParent().toAbsolutePath().normalize());
        if (stateProps != null) {
            System.out.printf("[JettraStore] Estado persistente de clúster cargado (cluster-state.config): %s%n",
                stateFile.toAbsolutePath().normalize());
        } else {
            System.out.printf("[JettraStore] Sin cluster-state.config%s: el nodo iniciará en espera (STANDBY / UNASSIGNED).%n",
                isPrimaryRole ? " ni cluster.config" : " (SECONDARY no requiere cluster.config)");
        }
        System.out.println("[JettraStore] ================================================================================");

        // Los nodos SECONDARY no validan su arranque contra cluster.config ni quedan bloqueados por cluster-state.config previo;
        // arrancan directamente con microprofile-config.properties y sincronizan estado desde el PRIMARY.
        if (!isPrimaryRole) {
            if (stateProps == null) {
                return new ValidationResult(true, List.of(), List.of(), "Modo STANDBY inicial sin topología previa.");
            }
            System.out.println("[JettraStore] Nodo SECONDARY inicializado con microprofile-config.properties (sin validar cluster.config).");
            return new ValidationResult(true, List.of(), List.of(), "Nodo SECONDARY inicializado con microprofile-config.properties.");
        }

        if (stateProps == null) {
            return new ValidationResult(true, List.of(), List.of(), "Modo STANDBY inicial sin topología previa.");
        }

        // 3. Ejecutar validaciones cruzadas del PRIMARY contra el archivo persistente cluster-state.config
        ValidationResult result = validate(dbProps, stateProps, CLUSTER_STATE_FILENAME);

        if (result.hasWarnings()) {
            for (String w : result.getWarnings()) {
                System.err.println("[JettraStore] " + w);
            }
        }

        if (!result.isValid()) {
            System.err.println(result.getNotification());
            boolean skipExit = Boolean.getBoolean("jettra.test.skip.exit");
            if (haltOnFailure && !skipExit) {
                BufferedReader stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                ValidationResult resolved = resolvePrimaryClusterStateConflictInteractive(
                    dbPath, dbProps, stateFile, stateProps, result, stdinReader, System.out);
                if (resolved.isValid()) {
                    System.out.println("[JettraStore] Configuración conciliada: Parámetros de base de datos y cluster-state.config consistentes.");
                    return resolved;
                }
                System.exit(1);
            } else {
                throw new JettraConfigurationException(result.getNotification());
            }
        } else {
            System.out.println("[JettraStore] Configuración verificada: Parámetros de base de datos y cluster-state.config consistentes.");
        }

        return result;
    }

    /**
     * Presenta el menú interactivo al detectar incompatibilidad en un nodo {@code PRIMARY} entre
     * {@code microprofile-config.properties} y {@code cluster-state.config}:
     * <ol>
     *   <li>Modificar {@code cluster-state.config} con la IP y puertos de {@code microprofile-config.properties}.</li>
     *   <li>Modificar {@code microprofile-config.properties} con la IP y puertos de {@code cluster-state.config}.</li>
     *   <li>Salir.</li>
     * </ol>
     */
    public static ValidationResult resolvePrimaryClusterStateConflictInteractive(
            Path mpPath,
            Properties dbProps,
            Path stateFile,
            Properties stateProps,
            ValidationResult initialResult,
            BufferedReader reader,
            PrintStream out) {
        PrintStream printer = out != null ? out : System.out;
        while (true) {
            printer.println();
            printer.println("Acciones:");
            printer.println("1. Modifique cluster-state.config con microprofile-config.properties");
            printer.println("2. Modifique microprofile-config.properties con cluster-state.config");
            printer.println("3. Salir");
            printer.print("Seleccione una opción [1-3]: ");
            printer.flush();

            String line;
            try {
                line = (reader != null) ? reader.readLine() : null;
            } catch (IOException ex) {
                line = null;
            }
            if (line == null) {
                printer.println("\n[JettraStore] Entrada no disponible. Saliendo de JettraStore.");
                return initialResult;
            }

            String choice = line.trim();
            if ("1".equals(choice)) {
                try {
                    Properties reloadedState = applyMicroProfileToClusterState(dbProps, stateFile, stateProps);
                    ValidationResult recheck = validate(dbProps, reloadedState, CLUSTER_STATE_FILENAME);
                    if (recheck.isValid()) {
                        printer.printf("[JettraStore] ✅ Archivo '%s' actualizado con IP y puertos de microprofile-config.properties.%n",
                            stateFile.toAbsolutePath().normalize());
                        return recheck;
                    }
                    printer.println("[JettraStore] ⚠️ Persistieron incompatibilidades tras actualizar cluster-state.config:");
                    printer.println(recheck.getNotification());
                } catch (Exception ex) {
                    printer.printf("[JettraStore] ❌ Error actualizando cluster-state.config: %s%n", ex.getMessage());
                }
            } else if ("2".equals(choice)) {
                try {
                    Path effectiveMpPath = mpPath != null ? mpPath : Path.of(DEFAULT_MICROPROFILE_CONFIG_PATH);
                    Properties reloadedMp = applyClusterStateToMicroProfile(effectiveMpPath, dbProps, stateProps);
                    ValidationResult recheck = validate(reloadedMp, stateProps, CLUSTER_STATE_FILENAME);
                    if (recheck.isValid()) {
                        printer.printf("[JettraStore] ✅ Archivo '%s' actualizado con IP y puertos de cluster-state.config.%n",
                            effectiveMpPath.toAbsolutePath().normalize());
                        return recheck;
                    }
                    printer.println("[JettraStore] ⚠️ Persistieron incompatibilidades tras actualizar microprofile-config.properties:");
                    printer.println(recheck.getNotification());
                } catch (Exception ex) {
                    printer.printf("[JettraStore] ❌ Error actualizando microprofile-config.properties: %s%n", ex.getMessage());
                }
            } else if ("3".equals(choice)) {
                printer.println("[JettraStore] Saliendo por solicitud del usuario.");
                return initialResult;
            } else {
                printer.println("[JettraStore] Opción inválida. Por favor seleccione 1, 2 o 3.");
            }
        }
    }

    /**
     * Opción 1: Actualiza {@code cluster-state.config} con el ID/IP/puertos gRPC y REST de {@code microprofile-config.properties}.
     */
    public static Properties applyMicroProfileToClusterState(Properties dbProps, Path stateFile, Properties stateProps) throws IOException {
        if (stateFile == null) {
            throw new IllegalArgumentException("Ruta de cluster-state.config nula.");
        }
        Properties effectiveState = stateProps != null ? stateProps : new Properties();
        if (effectiveState.isEmpty() && Files.exists(stateFile)) {
            effectiveState = loadProperties(stateFile);
        }
        List<ClusterNodeInfo> existingNodes = parseExplicitClusterNodes(effectiveState);

        String configuredId = getPropOrEnv(dbProps,
            new String[]{"cluster.node.id", "jettra.node.id", "jettra.cluster.node.id", "node.id"},
            new String[]{"CLUSTER_NODE_ID", "JETTRA_NODE_ID", "JETTRA_CLUSTER_NODE_ID"}, "node-01").trim();
        ClusterNodeInfo existingNode = findNodeInCluster(existingNodes, configuredId);
        if (existingNode == null) {
            for (ClusterNodeInfo cn : existingNodes) {
                if ("PRIMARY".equalsIgnoreCase(cn.role())) {
                    existingNode = cn;
                    break;
                }
            }
        }

        String fallbackIp = existingNode != null ? existingNode.ip() : "127.0.0.1";
        int fallbackGrpc = existingNode != null && existingNode.grpcPort() > 0 ? existingNode.grpcPort() : 50001;
        int fallbackRest = existingNode != null && existingNode.restPort() > 0 ? existingNode.restPort() : 60001;

        String rawIp = getPropOrEnv(dbProps,
            new String[]{"cluster.node.ip", "jettra.node.ip", "jettra.network.ip", "jettra.cluster.node.ip", "node.ip", "server.host"},
            new String[]{"CLUSTER_NODE_IP", "JETTRA_NODE_IP", "JETTRA_IP", "JETTRA_NETWORK_IP", "JETTRA_SERVER_HOST"},
            fallbackIp).trim();
        if ("0.0.0.0".equals(rawIp) || "*".equals(rawIp) || rawIp.isEmpty()) {
            rawIp = fallbackIp;
        }
        int grpcPort = parseInt(getPropOrEnv(dbProps,
            new String[]{"cluster.node.grpc.port", "jettra.network.grpc.port", "jettra.grpc.port", "grpc.port"},
            new String[]{"CLUSTER_NODE_GRPC_PORT", "JETTRA_GRPC_PORT", "JETTRA_NETWORK_GRPC_PORT"},
            String.valueOf(fallbackGrpc)), fallbackGrpc);
        int restPort = parseInt(getPropOrEnv(dbProps,
            new String[]{"cluster.node.rest.port", "server.port", "jettra.network.rest.port", "jettra.rest.port", "rest.port", "jettra.server.port"},
            new String[]{"CLUSTER_NODE_REST_PORT", "JETTRA_REST_PORT", "JETTRA_NETWORK_REST_PORT", "JETTRA_SERVER_PORT", "SERVER_PORT"},
            String.valueOf(fallbackRest)), fallbackRest);

        String effectiveId = configuredId.isEmpty() && existingNode != null ? existingNode.id() : configuredId;
        upsertNodeInStateFile(stateFile, effectiveId, "PRIMARY", rawIp, grpcPort, restPort, "RUNNING", "interactive-sync");
        return loadProperties(stateFile);
    }

    /**
     * Opción 2: Actualiza {@code microprofile-config.properties} con la IP y puertos gRPC/REST provenientes de {@code cluster-state.config}.
     */
    public static Properties applyClusterStateToMicroProfile(Path mpPath, Properties dbProps, Properties stateProps) throws IOException {
        if (mpPath == null) {
            throw new IllegalArgumentException("Ruta de microprofile-config.properties nula.");
        }
        List<ClusterNodeInfo> nodes = parseExplicitClusterNodes(stateProps);
        if (nodes.isEmpty()) {
            nodes = parseClusterNodes(stateProps);
        }
        String configuredId = getPropOrEnv(dbProps,
            new String[]{"cluster.node.id", "jettra.node.id", "jettra.cluster.node.id", "node.id"},
            new String[]{"CLUSTER_NODE_ID", "JETTRA_NODE_ID", "JETTRA_CLUSTER_NODE_ID"}, "").trim();
        ClusterNodeInfo targetNode = findNodeInCluster(nodes, configuredId);
        if (targetNode == null) {
            for (ClusterNodeInfo cn : nodes) {
                if ("PRIMARY".equalsIgnoreCase(cn.role())) {
                    targetNode = cn;
                    break;
                }
            }
        }
        if (targetNode == null && !nodes.isEmpty()) {
            targetNode = nodes.get(0);
        }
        if (targetNode == null) {
            throw new IllegalStateException("No se encontró ningún nodo válido dentro de cluster-state.config.");
        }

        Map<String, String> requiredUpdates = new LinkedHashMap<>();
        requiredUpdates.put("cluster.node.id", targetNode.id());
        requiredUpdates.put("cluster.node.role", targetNode.role());
        requiredUpdates.put("cluster.node.ip", targetNode.ip());
        requiredUpdates.put("cluster.node.grpc.port", String.valueOf(targetNode.grpcPort()));
        requiredUpdates.put("cluster.node.rest.port", String.valueOf(targetNode.restPort()));

        Map<String, String> legacyUpdates = new LinkedHashMap<>();
        legacyUpdates.put("jettra.node.id", targetNode.id());
        legacyUpdates.put("jettra.cluster.node.id", targetNode.id());
        legacyUpdates.put("jettra.node.role", targetNode.role());
        legacyUpdates.put("jettra.node.ip", targetNode.ip());
        legacyUpdates.put("jettra.network.ip", targetNode.ip());
        legacyUpdates.put("jettra.cluster.node.ip", targetNode.ip());
        legacyUpdates.put("node.ip", targetNode.ip());
        legacyUpdates.put("server.host", targetNode.ip());
        legacyUpdates.put("jettra.network.grpc.port", String.valueOf(targetNode.grpcPort()));
        legacyUpdates.put("jettra.grpc.port", String.valueOf(targetNode.grpcPort()));
        legacyUpdates.put("grpc.port", String.valueOf(targetNode.grpcPort()));
        legacyUpdates.put("jettra.network.rest.port", String.valueOf(targetNode.restPort()));
        legacyUpdates.put("jettra.rest.port", String.valueOf(targetNode.restPort()));
        legacyUpdates.put("server.port", String.valueOf(targetNode.restPort()));
        legacyUpdates.put("rest.port", String.valueOf(targetNode.restPort()));

        updatePropertiesFilePreservingComments(mpPath, requiredUpdates, legacyUpdates);

        // Sincronizar también en memoria y en system properties si fueron pasados vía -D en la JVM actual
        Properties syncedProps = dbProps != null ? dbProps : new Properties();
        for (Map.Entry<String, String> e : requiredUpdates.entrySet()) {
            syncedProps.setProperty(e.getKey(), e.getValue());
            if (System.getProperty(e.getKey()) != null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : legacyUpdates.entrySet()) {
            if (syncedProps.containsKey(e.getKey())) {
                syncedProps.setProperty(e.getKey(), e.getValue());
            }
            if (System.getProperty(e.getKey()) != null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
        return loadProperties(mpPath, "/META-INF/microprofile-config.properties", "/microprofile-config.properties");
    }

    private static void updatePropertiesFilePreservingComments(
            Path targetFile,
            Map<String, String> requiredUpdates,
            Map<String, String> existingOnlyUpdates) throws IOException {
        List<String> lines = Files.exists(targetFile)
            ? new ArrayList<>(Files.readAllLines(targetFile, StandardCharsets.UTF_8))
            : new ArrayList<>();
        Set<String> updatedKeys = new LinkedHashSet<>();
        List<String> outLines = new ArrayList<>(lines.size() + requiredUpdates.size());

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                outLines.add(line);
                continue;
            }
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0) {
                outLines.add(line);
                continue;
            }
            String key = trimmed.substring(0, eqIdx).trim();
            if (requiredUpdates.containsKey(key)) {
                outLines.add(key + "=" + requiredUpdates.get(key));
                updatedKeys.add(key);
            } else if (existingOnlyUpdates != null && existingOnlyUpdates.containsKey(key)) {
                outLines.add(key + "=" + existingOnlyUpdates.get(key));
                updatedKeys.add(key);
            } else {
                outLines.add(line);
            }
        }

        for (Map.Entry<String, String> e : requiredUpdates.entrySet()) {
            if (!updatedKeys.contains(e.getKey())) {
                outLines.add(e.getKey() + "=" + e.getValue());
            }
        }

        String joined = String.join(System.lineSeparator(), outLines) + System.lineSeparator();
        writeAtomicBytesInternal(targetFile, joined.getBytes(StandardCharsets.UTF_8));
    }

    private static void failValidationOrHalt(List<String> errors, String summary, boolean haltOnFailure) {
        String banner = buildNotificationBanner(errors != null && !errors.isEmpty() ? errors : List.of(summary), "cluster.config");
        System.err.println(banner);
        boolean skipExit = Boolean.getBoolean("jettra.test.skip.exit");
        if (haltOnFailure && !skipExit) {
            System.exit(1);
        }
        throw new JettraConfigurationException(banner);
    }
}

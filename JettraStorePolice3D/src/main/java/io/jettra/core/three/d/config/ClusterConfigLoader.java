package io.jettra.core.three.d.config;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Lector de configuración de topología del clúster de JettraStore (jettra.config)
 * con auto-detección de entornos en ejecución mediante Docker Compose.
 */
public class ClusterConfigLoader {

    public record ConfiguredNode(
        String id,
        String role,
        String host,
        int grpcPort,
        int restPort,
        String storagePath,
        boolean isLeader
    ) {}

    private static ClusterConfigLoader instance;

    public static synchronized ClusterConfigLoader getInstance() {
        if (instance == null) {
            instance = new ClusterConfigLoader();
        }
        return instance;
    }

    private String clusterName = "jettra-production-cluster";
    private String consensusProtocol = "RAFT";
    private String loadedSource = "DEFAULT_BUILTIN";
    private boolean dockerComposeDetected = false;
    private boolean multinodeActive = true;
    private final List<ConfiguredNode> nodes = new ArrayList<>();

    public ClusterConfigLoader() {
        reload();
    }

    public synchronized void reload() {
        nodes.clear();
        dockerComposeDetected = checkDockerComposeRunning();

        File configFile = locateConfigFile();
        if (configFile != null && configFile.exists()) {
            loadedSource = configFile.getAbsolutePath();
            parseConfigFile(configFile);
        } else {
            // Intentar desde classpath
            try (InputStream in = getClass().getResourceAsStream("/jettra.config")) {
                if (in != null) {
                    loadedSource = "classpath:/jettra.config";
                    parseFromInputStream(in);
                } else {
                    fallbackDefaultNodes();
                }
            } catch (Exception e) {
                fallbackDefaultNodes();
            }
        }

        // Resolver cluster.multinode.active
        String envMulti = System.getenv("JETTRA_CLUSTER_MULTINODE_ACTIVE");
        String sysMulti = System.getProperty("cluster.multinode.active");
        if (sysMulti != null && !sysMulti.isBlank()) {
            this.multinodeActive = "on".equalsIgnoreCase(sysMulti.trim()) || "true".equalsIgnoreCase(sysMulti.trim());
        } else if (envMulti != null && !envMulti.isBlank()) {
            this.multinodeActive = "on".equalsIgnoreCase(envMulti.trim()) || "true".equalsIgnoreCase(envMulti.trim());
        } else {
            File dbPropsFile = locateDatabasePropertiesFile();
            if (dbPropsFile != null) {
                try (InputStream in = new FileInputStream(dbPropsFile)) {
                    Properties p = new Properties();
                    p.load(in);
                    String val = p.getProperty("cluster.multinode.active");
                    if (val != null) {
                        this.multinodeActive = "on".equalsIgnoreCase(val.trim()) || "true".equalsIgnoreCase(val.trim());
                    }
                } catch (Exception ignored) {}
            } else {
                try {
                    this.multinodeActive = io.jettra.store.core.JettraStoreConfig.load().isClusterMultinodeActive();
                } catch (Exception ignored) {}
            }
        }

        // Si se detecta ejecución vía Docker Compose o los puertos mapeados están activos, ajustar endpoints
        if (dockerComposeDetected) {
            adjustForDockerCompose();
        }
    }

    private File locateDatabasePropertiesFile() {
        String sysProp = System.getProperty("database.properties.path");
        if (sysProp != null && !sysProp.isBlank()) {
            File f = new File(sysProp);
            if (f.exists()) return f;
        }

        String[] candidates = {
            "config/database.properties",
            "database.properties",
            "../config/database.properties",
            "../JettraStore/config/database.properties",
            "../JettraStore/src/main/resources/database.properties",
            "JettraStorePolice3D/config/database.properties"
        };

        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private File locateConfigFile() {
        String sysProp = System.getProperty("jettra.config.path");
        if (sysProp != null && !sysProp.isBlank()) {
            File f = new File(sysProp);
            if (f.exists()) return f;
        }

        String[] candidates = {
            "jettra.config",
            "config/jettra.config",
            "../JettraStore/src/main/resources/jettra.config",
            "../JettraStore/config/jettra.config",
            "../../jettrastoreworkspace/jettrastoreworkspace/JettraStore/src/main/resources/jettra.config",
            "/home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/jettrastoreworkspace/JettraStore/src/main/resources/jettra.config",
            "/home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/jettrastoreworkspace/JettraStore/config/jettra.config"
        };

        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private void parseConfigFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            parseFromInputStream(in);
        } catch (Exception e) {
            fallbackDefaultNodes();
        }
    }

    private void parseFromInputStream(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);

        this.clusterName = props.getProperty("cluster.name", "jettra-production-cluster").trim();
        this.consensusProtocol = props.getProperty("cluster.consensus.protocol", "RAFT").trim();
        if (props.containsKey("cluster.multinode.active")) {
            String val = props.getProperty("cluster.multinode.active").trim();
            this.multinodeActive = "on".equalsIgnoreCase(val) || "true".equalsIgnoreCase(val);
        }

        // Buscar nodos: cluster.node.1.id, cluster.node.2.id, ...
        int idx = 1;
        while (props.containsKey("cluster.node." + idx + ".id")) {
            String prefix = "cluster.node." + idx + ".";
            String id = props.getProperty(prefix + "id", "node-0" + idx).trim();
            String role = props.getProperty(prefix + "role", (idx == 1 ? "PRIMARY" : "SECONDARY")).trim();
            String ip = props.getProperty(prefix + "ip", "127.0.0.1").trim();
            int grpcPort = parseInt(props.getProperty(prefix + "grpc.port"), 9091);
            int restPort = parseInt(props.getProperty(prefix + "rest.port"), (idx == 1 ? 8080 : 8080 + idx - 1));
            String storagePath = props.getProperty(prefix + "storage.path", "~/jettra/" + id + "/data").trim();
            boolean isLeader = "PRIMARY".equalsIgnoreCase(role);

            nodes.add(new ConfiguredNode(id, role, ip, grpcPort, restPort, storagePath, isLeader));
            idx++;
        }

        if (nodes.isEmpty()) {
            fallbackDefaultNodes();
        }
    }

    private void fallbackDefaultNodes() {
        loadedSource = "FALLBACK_TOPOLOGY";
        nodes.add(new ConfiguredNode("node-01", "PRIMARY", "127.0.0.1", 9091, 8080, "~/jettra/node-01/data", true));
        nodes.add(new ConfiguredNode("node-02", "SECONDARY", "127.0.0.1", 9091, 8080, "~/jettra/node-02/data", false));
        nodes.add(new ConfiguredNode("node-03", "SECONDARY", "127.0.0.1", 9091, 8080, "~/jettra/node-03/data", false));
    }

    private boolean checkDockerComposeRunning() {
        // 1. Variable de entorno explícita
        String envDc = System.getenv("JETTRA_DOCKER_COMPOSE");
        if ("true".equalsIgnoreCase(envDc) || "1".equals(envDc)) {
            return true;
        }

        // 2. Comprobar si los puertos locales mapeados por docker-compose (8081, 8082, 8083) están escuchando
        int[] dcPorts = {8081, 8082, 8083};
        for (int p : dcPorts) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", p), 120);
                return true;
            } catch (Exception ignored) {}
        }

        // Docker Compose solo se considera activo si los puertos de los contenedores están respondiendo
        return false;
    }

    private void adjustForDockerCompose() {
        List<ConfiguredNode> adjusted = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            ConfiguredNode orig = nodes.get(i);
            int dcRestPort = 8081 + i;
            int dcGrpcPort = 9091 + i;
            adjusted.add(new ConfiguredNode(
                orig.id(),
                orig.role(),
                "127.0.0.1",
                dcGrpcPort,
                dcRestPort,
                orig.storagePath(),
                orig.isLeader()
            ));
        }
        nodes.clear();
        nodes.addAll(adjusted);
    }

    private int parseInt(String val, int def) {
        if (val == null || val.isBlank()) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public synchronized List<ConfiguredNode> getNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    public String getClusterName() { return clusterName; }
    public String getConsensusProtocol() { return consensusProtocol; }
    public String getLoadedSource() { return loadedSource; }
    public boolean isDockerComposeDetected() { return dockerComposeDetected; }
    public boolean isMultinodeActive() { return multinodeActive; }
    public String getClusterMultinodeActive() { return multinodeActive ? "on" : "off"; }
    public void setMultinodeActive(boolean multinodeActive) { this.multinodeActive = multinodeActive; }
}

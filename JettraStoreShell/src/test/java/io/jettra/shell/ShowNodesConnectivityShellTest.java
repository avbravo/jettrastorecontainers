package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Pruebas automatizadas con JettraTest para RF-29 Acción 1:
 * Verificación real de conectividad e identidad en `show nodes`, evitando falsos positivos
 * (RUNNING / LEADER / FOLLOWER) cuando ningún nodo JettraStore responde o cuando existe
 * otro servicio TCP distinto escuchando en el mismo puerto.
 */
public class ShowNodesConnectivityShellTest {

    private static Path tempDir;
    private static Path clusterFile;
    private static Path mpFile;
    private static String prevClusterProp;
    private static String prevMpProp;
    private static String prevStorageProp;

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    @BeforeAll
    public static void setup() throws Exception {
        prevClusterProp = System.getProperty("cluster.config.path");
        prevMpProp = System.getProperty("jettra.shell.config.file");
        prevStorageProp = System.getProperty("jettra.storage.path");

        if (JettraStoreServer.getActiveInstance() != null) {
            try {
                JettraStoreServer.getActiveInstance().stop();
            } catch (Exception ignored) {
            }
        }

        tempDir = Files.createTempDirectory("jettra_shell_rf29_shownodes_");
        clusterFile = tempDir.resolve("cluster-rf29.config");
        mpFile = tempDir.resolve("microprofile-rf29.properties");

        System.setProperty("jettra.storage.path", tempDir.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", clusterFile.toAbsolutePath().toString());
        System.setProperty("jettra.shell.config.file", mpFile.toAbsolutePath().toString());
    }

    @AfterAll
    public static void cleanup() {
        restoreProp("cluster.config.path", prevClusterProp);
        restoreProp("jettra.shell.config.file", prevMpProp);
        restoreProp("jettra.storage.path", prevStorageProp);
        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }

    private static void restoreProp(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    private static JettraStoreServer startNodeServer(
            String nodeId, String role, int grpcPort, int restPort) throws Exception {
        Path dataDir = tempDir.resolve(nodeId);
        Files.createDirectories(dataDir);
        Properties props = new Properties();
        props.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", "JVM_RAM");
        props.setProperty("cluster.multinode.active", "on");
        props.setProperty("cluster.node.id", nodeId);
        props.setProperty("cluster.node.role", role);
        props.setProperty("jettra.node.ip", "127.0.0.1");
        props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
        props.setProperty("jettra.network.rest.port", String.valueOf(restPort));
        props.setProperty("jettrapolice.active", "false");

        Properties clusterProps = JettraConfigValidator.loadProperties(clusterFile, "/cluster.config", "/jettra.config");
        JettraStoreConfig cfg = new JettraStoreConfig(props, clusterProps, props);
        JettraStoreServer srv = new JettraStoreServer(cfg);
        srv.setClusterConfigFilePath(clusterFile);
        srv.start();
        return srv;
    }

    @Test
    @DisplayName("RF-29: show nodes nunca reporta RUNNING/LEADER/FOLLOWER si los nodos no están activos")
    public void testShowNodesAccurateWhenNodesDownAndRejectsFakeTcpListener() throws Exception {
        int grpc1 = freePort();
        int rest1 = freePort();
        int grpc2 = freePort();
        int rest2 = freePort();

        Files.writeString(clusterFile,
                "cluster.multinode.active=on\n"
                        + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + grpc1 + "\ncluster.node.1.rest.port=" + rest1 + "\n"
                        + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + grpc2 + "\ncluster.node.2.rest.port=" + rest2 + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(mpFile,
                "cluster.nodes=node-01:127.0.0.1:" + grpc1 + ",node-02:127.0.0.1:" + grpc2 + "\n",
                StandardCharsets.UTF_8);

        JettraClientConfig cfg = JettraClientConfig.builder()
                .addClusterNode("127.0.0.1", grpc1)
                .addClusterNode("127.0.0.1", grpc2)
                .credentials("admin", "admin-jettra")
                .role("admin")
                .build();

        try (JettraClient client = JettraClient.connect(cfg)) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // Caso 1: todos los puertos cerrados -> 0 activos
            String outClosed = shell.executeCommand("show nodes");
            JettraAssert.assertTrue(outClosed.contains("node-01") && outClosed.contains("node-02"),
                    "Debe listar los nodos configurados: " + outClosed);
            JettraAssert.assertFalse(outClosed.contains("RUNNING"),
                    "No debe reportar RUNNING si ningún nodo responde:\n" + outClosed);
            JettraAssert.assertFalse(outClosed.contains("LEADER"),
                    "No debe reportar LEADER si ningún nodo responde:\n" + outClosed);
            JettraAssert.assertFalse(outClosed.contains("FOLLOWER"),
                    "No debe reportar FOLLOWER si ningún nodo responde:\n" + outClosed);
            JettraAssert.assertTrue(outClosed.contains("STOPPED") && outClosed.contains("DISCONNECTED") && outClosed.contains("UNKNOWN"),
                    "Debe mostrar estado STOPPED/DISCONNECTED/UNKNOWN:\n" + outClosed);
            JettraAssert.assertTrue(outClosed.contains("0/2 activos"),
                    "Debe contabilizar 0/2 nodos activos:\n" + outClosed);
            JettraAssert.assertEquals(JettraStoreShellApp.NO_ACTIVE_NODES_ERROR,
                    shell.executeCommand("SHOW BUCKETS"),
                    "SHOW BUCKETS debe seguir bloqueado con 0 nodos activos");

            // Caso 2: socket TCP genérico (NO JettraStore) abierto en grpc1 y rest1 -> tampoco debe dar falso positivo
            try (ServerSocket nonJettraGrpc = new ServerSocket(grpc1, 5, InetAddress.getByName("127.0.0.1"));
                 ServerSocket nonJettraRest = new ServerSocket(rest1, 5, InetAddress.getByName("127.0.0.1"))) {
                Thread acceptThread = Thread.ofVirtual().start(() -> {
                    while (!nonJettraGrpc.isClosed()) {
                        try (var s = nonJettraGrpc.accept()) {
                            s.getOutputStream().write("NOT-JETTRA\n".getBytes(StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            break;
                        }
                    }
                });
                Thread restAcceptThread = Thread.ofVirtual().start(() -> {
                    while (!nonJettraRest.isClosed()) {
                        try (var s = nonJettraRest.accept()) {
                            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            break;
                        }
                    }
                });

                String outFakeTcp = shell.executeCommand("show nodes");
                JettraAssert.assertFalse(outFakeTcp.contains("RUNNING"),
                        "Un listener TCP no-Jettra nunca debe reportarse como RUNNING:\n" + outFakeTcp);
                JettraAssert.assertFalse(outFakeTcp.contains("LEADER"),
                        "Un listener TCP no-Jettra nunca debe reportarse como LEADER:\n" + outFakeTcp);
                JettraAssert.assertEquals(JettraStoreShellApp.NO_ACTIVE_NODES_ERROR,
                        shell.executeCommand("SHOW DATABASES"),
                        "SHOW DATABASES debe rechazar ejecución si solo hay puertos no-Jettra abiertos");

                nonJettraGrpc.close();
                nonJettraRest.close();
                acceptThread.join(300);
                restAcceptThread.join(300);
            }
        }
    }

    @Test
    @DisplayName("RF-29: show nodes refleja en vivo nodo activo (RUNNING/LEADER) vs nodo caído (STOPPED/DISCONNECTED)")
    public void testShowNodesMixedLiveAndStopped() throws Exception {
        int grpc1 = freePort();
        int rest1 = freePort();
        int grpc2 = freePort();
        int rest2 = freePort();

        Files.writeString(clusterFile,
                "cluster.multinode.active=on\n"
                        + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + grpc1 + "\ncluster.node.1.rest.port=" + rest1 + "\n"
                        + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + grpc2 + "\ncluster.node.2.rest.port=" + rest2 + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(mpFile,
                "cluster.nodes=node-01:127.0.0.1:" + grpc1 + ",node-02:127.0.0.1:" + grpc2 + "\n",
                StandardCharsets.UTF_8);

        JettraStoreServer n1 = startNodeServer("node-01", "PRIMARY", grpc1, rest1);
        try {
            JettraClientConfig cfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", grpc1)
                    .addClusterNode("127.0.0.1", grpc2)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .build();

            try (JettraClient client = JettraClient.connect(cfg)) {
                JettraStoreShellApp shell = new JettraStoreShellApp(client);

                String mixed = shell.executeCommand("show nodes");
                boolean node1Running = mixed.lines()
                        .anyMatch(l -> l.contains("node-01") && l.contains("PRIMARY") && l.contains("LEADER") && l.contains("RUNNING"));
                boolean node2Stopped = mixed.lines()
                        .anyMatch(l -> l.contains("node-02") && l.contains("UNKNOWN") && l.contains("DISCONNECTED") && l.contains("STOPPED"));
                JettraAssert.assertTrue(node1Running, "node-01 activo debe verse RUNNING/PRIMARY/LEADER:\n" + mixed);
                JettraAssert.assertTrue(node2Stopped, "node-02 caído debe verse STOPPED/DISCONNECTED/UNKNOWN:\n" + mixed);
                JettraAssert.assertTrue(mixed.contains("1/2 activos"), "Debe indicar 1/2 activos:\n" + mixed);
            }
        } finally {
            try {
                n1.stop();
            } catch (Exception ignored) {
            }
        }

        // Tras detener node-01, show nodes vuelve inmediatamente a 0/2 activos sin caché ni estado fantasma
        JettraClientConfig cfgAfterStop = JettraClientConfig.builder()
                .addClusterNode("127.0.0.1", grpc1)
                .addClusterNode("127.0.0.1", grpc2)
                .credentials("admin", "admin-jettra")
                .role("admin")
                .build();
        try (JettraClient client = JettraClient.connect(cfgAfterStop)) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            String afterStop = shell.executeCommand("show nodes");
            JettraAssert.assertFalse(afterStop.contains("RUNNING"),
                    "Tras detener node-01 no debe permanecer RUNNING:\n" + afterStop);
            JettraAssert.assertTrue(afterStop.contains("0/2 activos"),
                    "Tras detener node-01 debe reportar 0/2 activos:\n" + afterStop);
        }
    }
}

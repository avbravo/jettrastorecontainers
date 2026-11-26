package io.jettra.driver;

import io.jettra.driver.config.JettraClientConfig;
import io.jettra.driver.config.JettraConnectionConfigManager;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-32:
 * - Precedencia de carga cluster.ip.nodes -> conecction.config
 * - Persistencia atómica en conecction.config
 * - Obtención de cluster-state.config del PRIMARY siguiendo referencia desde cualquier nodo
 */
public class ConnectionConfigAndPrimaryStatusTest {

    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void backupConfiguredState() {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
    }

    @AfterAll
    public static void restoreConfiguredState() {
        try {
            JettraStoreServer.stopAllActiveServers();
            JettraAssert.assertEquals(0, JettraStoreServer.getActiveServerCount());
            JettraAssert.assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        } finally {
            if (suiteStateBackup != null) {
                suiteStateBackup.close();
                suiteStateBackup = null;
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("RF-32: Precedencia cluster.ip.nodes vs conecction.config y persistencia atómica")
    public void testConnectionConfigPrecedenceAndAtomicSave() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_conn_cfg_");
        Path connFile = tmp.resolve(JettraConnectionConfigManager.CONNECTION_CONFIG_FILENAME);
        String prevConnFile = System.getProperty("jettra.connection.config.file");
        String prevSeedProp = System.getProperty("cluster.ip.nodes");
        try {
            System.setProperty("jettra.connection.config.file", connFile.toAbsolutePath().toString());
            System.clearProperty("cluster.ip.nodes");

            Properties mp = new Properties();
            mp.setProperty("cluster.ip.nodes", "127.0.0.1:50001");

            // 1. Sin conecction.config -> toma cluster.ip.nodes (127.0.0.1:50001) sin crear aún el archivo
            var initial = JettraConnectionConfigManager.loadInitialConnection(mp);
            JettraAssert.assertFalse(initial.loadedFromConnectionFile(), "Sin conecction.config no debe marcar carga desde archivo");
            JettraAssert.assertEquals("127.0.0.1:50001", initial.clusterIpNodes());
            JettraAssert.assertFalse(Files.exists(connFile), "No debe crear conecction.config hasta que el usuario guarde");

            // 2. Guardar edición múltiple atómicamente -> conecction.config prevalece sobre microprofile-config.properties
            var saved = JettraConnectionConfigManager.saveConnectionAtomically("10.0.0.10:50002, 10.0.0.11:50003", "admin");
            JettraAssert.assertTrue(Files.exists(connFile), "conecction.config debe haberse persistido atómicamente");
            JettraAssert.assertTrue(saved.loadedFromConnectionFile());
            JettraAssert.assertEquals("10.0.0.10:50002,10.0.0.11:50003", saved.clusterIpNodes());

            var reloaded = JettraConnectionConfigManager.loadInitialConnection(mp);
            JettraAssert.assertTrue(reloaded.loadedFromConnectionFile(), "Debe prevalecer conecction.config sobre microprofile-config");
            JettraAssert.assertEquals("10.0.0.10:50002,10.0.0.11:50003", reloaded.clusterIpNodes());
            JettraAssert.assertEquals(2, reloaded.endpoints().size());
        } finally {
            if (prevConnFile == null) System.clearProperty("jettra.connection.config.file");
            else System.setProperty("jettra.connection.config.file", prevConnFile);
            if (prevSeedProp == null) System.clearProperty("cluster.ip.nodes");
            else System.setProperty("cluster.ip.nodes", prevSeedProp);
            deleteDir(tmp);
        }
    }

    @Test
    @DisplayName("RF-32: Conectando vía SECONDARY sigue al PRIMARY activo y lee cluster-state.config actualizado")
    public void testFollowSecondaryToPrimaryAndFetchClusterStatusConfig() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_status_follow_");
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path data1 = tmp.resolve("data1");
        Path data2 = tmp.resolve("data2");
        Path seedFile = tmp.resolve("cluster.config");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();

        String seed = "cluster.name=rf32-status-follow\n"
                + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + g1 + "\ncluster.node.1.rest.port=" + r1 + "\n"
                + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + g2 + "\ncluster.node.2.rest.port=" + r2 + "\n";
        Files.writeString(seedFile, seed, StandardCharsets.UTF_8);

        Properties seedProps = new Properties();
        try (var in = Files.newInputStream(seedFile)) { seedProps.load(in); }

        Properties p1 = new Properties();
        p1.setProperty("cluster.node.id", "node-01");
        p1.setProperty("cluster.node.role", "PRIMARY");
        p1.setProperty("jettra.node.ip", "127.0.0.1");
        p1.setProperty("jettra.network.grpc.port", String.valueOf(g1));
        p1.setProperty("jettra.network.rest.port", String.valueOf(r1));
        p1.setProperty("jettra.storage.path", data1.toAbsolutePath().toString());
        p1.setProperty("jettra.cluster_state_config_dir", state1.toAbsolutePath().toString());
        p1.setProperty("jettra.storage.mode", "JVM_RAM");
        p1.setProperty("cluster.multinode.active", "on");

        Properties p2 = new Properties();
        p2.setProperty("cluster.node.id", "node-02");
        p2.setProperty("cluster.node.role", "SECONDARY");
        p2.setProperty("jettra.node.ip", "127.0.0.1");
        p2.setProperty("jettra.network.grpc.port", String.valueOf(g2));
        p2.setProperty("jettra.network.rest.port", String.valueOf(r2));
        p2.setProperty("jettra.storage.path", data2.toAbsolutePath().toString());
        p2.setProperty("jettra.cluster_state_config_dir", state2.toAbsolutePath().toString());
        p2.setProperty("jettra.storage.mode", "JVM_RAM");
        p2.setProperty("cluster.multinode.active", "on");

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        String prevStateDir = System.getProperty("jettra.cluster_state_config_dir");
        try {
            System.setProperty("jettra.cluster_state_config_dir", tmp.resolve("driver-state").toAbsolutePath().toString());
            n1 = new JettraStoreServer(new JettraStoreConfig(p1, seedProps, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(seedFile);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(p2, seedProps, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(seedFile);
            n2.start();

            // Verificar que solo existe cluster-state.config (y NO cluster-status.config) en el PRIMARY y está expuesto vía REST
            Path stateFile1 = state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            JettraAssert.assertTrue(Files.exists(stateFile1), "PRIMARY debe mantener cluster-state.config");
            JettraAssert.assertFalse(Files.exists(state1.resolve("cluster-status.config")),
                    "No debe generarse archivo cluster-status.config");

            var http = HttpClient.newHttpClient();
            var httpResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r1 + "/api/v1/cluster/state.config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, httpResp.statusCode());
            JettraAssert.assertTrue(httpResp.body().contains("node-01") && httpResp.body().contains("node-02"));

            // Conectando al SECONDARY (g2) -> debe resolver PRIMARY (node-01 @ 127.0.0.1:g1)
            var snapFromSecondary = JettraClient.fetchPrimaryClusterStatus(List.of("127.0.0.1:" + g2), 600, 1200);
            JettraAssert.assertNotNull(snapFromSecondary, "Debe obtener cluster-state.config siguiendo al PRIMARY desde SECONDARY");
            JettraAssert.assertTrue(snapFromSecondary.resolvedFromPrimary(), "Debe confirmar lectura autoritativa desde PRIMARY");
            JettraAssert.assertEquals("node-01", snapFromSecondary.primaryNodeId());
            JettraAssert.assertEquals(g1, snapFromSecondary.primaryGrpcPort());
            JettraAssert.assertEquals(2, snapFromSecondary.nodes().size());

            // Mutar clúster (añadir node-03 en Driver) y volver a leer cluster-state.config desde SECONDARY -> refleja node-03
            JettraClientConfig cfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", g1)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .build();
            try (JettraClient client = JettraClient.connect(cfg)) {
                JettraAssert.assertTrue(client.addClusterNode("node-03", "127.0.0.1", g3, r3, "SECONDARY"));
            }

            var updatedSnap = JettraClient.fetchPrimaryClusterStatus(List.of("127.0.0.1:" + g2), 600, 1200);
            JettraAssert.assertNotNull(updatedSnap);
            JettraAssert.assertEquals(3, updatedSnap.nodes().size(), "cluster-state.config actualizado desde PRIMARY debe incluir node-03");
            JettraAssert.assertFalse(Files.exists(state1.resolve("cluster-status.config")),
                    "No debe generarse archivo cluster-status.config tras mutaciones");
        } finally {
            if (prevStateDir == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateDir);
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            deleteDir(tmp);
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
    }
}

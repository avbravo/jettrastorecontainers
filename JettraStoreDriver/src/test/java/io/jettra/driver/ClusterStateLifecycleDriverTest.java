package io.jettra.driver;

import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.cluster.ClusterNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Pruebas integrales de sistema distribuido con JettraTest para RF-32:
 * - Arranque de nodo en STANDBY sin cluster.config ni cluster-state.config
 * - Alta desde JettraStoreDriver -> actualización atómica en PRIMARY -> replicación a SECONDARY
 * - Detención (STOPPED), reinicio completo de todo el clúster y preservación de cluster-state.config
 * - Reconciliación desde PRIMARY al reiniciar un nodo SECONDARY
 * - Remoción limpia sin alterar cluster.config estático
 */
public class ClusterStateLifecycleDriverTest {

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

    private static Properties baseProps(String nodeId, String role, int grpc, int rest, Path dataDir, Path stateDir) {
        Properties p = new Properties();
        p.setProperty("cluster.node.id", nodeId);
        p.setProperty("cluster.node.role", role);
        p.setProperty("jettra.node.ip", "127.0.0.1");
        p.setProperty("jettra.network.grpc.port", String.valueOf(grpc));
        p.setProperty("jettra.network.rest.port", String.valueOf(rest));
        p.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
        p.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());
        p.setProperty("jettra.storage.mode", "JVM_RAM");
        p.setProperty("cluster.multinode.active", "on");
        p.setProperty("jettrapolice.active", "false");
        return p;
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
    @DisplayName("RF-32: Nodo STANDBY incorporado vía Driver, replicación PRIMARY->SECONDARY, STOP/START/REMOVE y reinicio completo")
    public void testStandbyAssignReplicateLifecycleAndFullClusterRestart() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_cluster_driver_");
        Path staticSeed = tmp.resolve("cluster.config");
        Path state1 = tmp.resolve("state-node1");
        Path state2 = tmp.resolve("state-node2");
        Path state3 = tmp.resolve("state-standby3");
        Path driverState = tmp.resolve("state-driver");
        Path data1 = tmp.resolve("data-node1");
        Path data2 = tmp.resolve("data-node2");
        Path data3 = tmp.resolve("data-standby3");

        String prevStateProp = System.getProperty("jettra.cluster_state_config_dir");
        String prevClusterProp = System.getProperty("cluster.config.path");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();

        String seedContent = "cluster.name = rf32-live-cluster\n"
                + "cluster.node.1.id = node-01\ncluster.node.1.role = PRIMARY\ncluster.node.1.ip = 127.0.0.1\ncluster.node.1.grpc.port = " + g1 + "\ncluster.node.1.rest.port = " + r1 + "\n"
                + "cluster.node.2.id = node-02\ncluster.node.2.role = SECONDARY\ncluster.node.2.ip = 127.0.0.1\ncluster.node.2.grpc.port = " + g2 + "\ncluster.node.2.rest.port = " + r2 + "\n";
        Files.writeString(staticSeed, seedContent, StandardCharsets.UTF_8);

        System.setProperty("jettra.cluster_state_config_dir", driverState.toAbsolutePath().toString());
        System.clearProperty("cluster.config.path");

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        JettraStoreServer n3 = null;
        try {
            Properties seedProps = new Properties();
            try (var in = Files.newInputStream(staticSeed)) { seedProps.load(in); }

            // 1. Iniciar node-01 (PRIMARY) y node-02 (SECONDARY) desde semilla estática -> siembran sus cluster-state.config
            n1 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-01", "PRIMARY", g1, r1, data1, state1), seedProps, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(staticSeed);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-02", "SECONDARY", g2, r2, data2, state2), seedProps, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(staticSeed);
            n2.start();

            // 2. Iniciar node-03 SIN cluster.config ni cluster-state.config -> debe quedar en STANDBY
            n3 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-03", "SECONDARY", g3, r3, data3, state3), new Properties(), new Properties()));
            n3.setClusterStateFilePath(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n3.start();
            JettraAssert.assertTrue(n3.getConfig().isStandbyMode(), "node-03 debe iniciar en modo STANDBY");
            JettraAssert.assertFalse(Files.exists(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)),
                    "node-03 en STANDBY aún no tiene cluster-state.config");

            // 3. Conectar JettraStoreDriver al clúster y añadir node-03
            JettraClientConfig driverCfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", g1)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .build();
            try (JettraClient client = JettraClient.connect(driverCfg)) {
                boolean added = client.addClusterNode("node-03", "127.0.0.1", g3, r3, "SECONDARY");
                JettraAssert.assertTrue(added, "addClusterNode debe añadir node-03");

                // node-03 debe salir de STANDBY y tener su propio cluster-state.config replicado desde PRIMARY
                JettraAssert.assertFalse(n3.getConfig().isStandbyMode(), "node-03 debe salir de STANDBY al recibir cluster-state.config");
                JettraAssert.assertEquals(ClusterNode.Role.SECONDARY, n3.getConfig().getNodeRole(), "node-03 debe asumir rol SECONDARY");

                String state1Content = Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                String state2Content = Files.readString(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                String state3Content = Files.readString(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                JettraAssert.assertTrue(state1Content.contains("node-03"), "PRIMARY debe persistir node-03 en cluster-state.config");
                JettraAssert.assertTrue(state2Content.contains("node-03"), "node-02 debe recibir replicación de cluster-state.config con node-03");
                JettraAssert.assertTrue(state3Content.contains("node-03"), "node-03 debe guardar cluster-state.config replicado");

                // 4. Detener node-03 -> PRIMARY actualiza cluster-state.config con STOPPED y replica a node-02
                var stopRes = client.stopClusterNode("node-03");
                JettraAssert.assertTrue(stopRes.success() && stopRes.verifiedState(), "stopClusterNode debe detener node-03");

                String state1AfterStop = Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                String state2AfterStop = Files.readString(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                JettraAssert.assertTrue(state1AfterStop.contains("STOPPED"), "PRIMARY debe persistir estado STOPPED");
                JettraAssert.assertTrue(state2AfterStop.contains("STOPPED"), "node-02 debe replicar estado STOPPED");

                // Verificar que cluster.config estático jamás fue sobrescrito
                JettraAssert.assertEquals(seedContent, Files.readString(staticSeed, StandardCharsets.UTF_8),
                        "cluster.config estático debe mantenerse intacto");
            }

            // 5. Detener TODO el clúster (n1 y n2) y volver a iniciarlo
            n2.stop();
            n1.stop();

            Properties reloadedState1 = JettraConfigValidator.seedOrLoadClusterState(
                    state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), staticSeed);
            Properties reloadedState2 = JettraConfigValidator.seedOrLoadClusterState(
                    state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), staticSeed);

            n1 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-01", "PRIMARY", g1, r1, data1, state1), reloadedState1, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(staticSeed);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-02", "SECONDARY", g2, r2, data2, state2), reloadedState2, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(staticSeed);
            n2.start();

            // Al reiniciar n1+n2, node-03 debe seguir existiendo (como STOPPED) y no haberse perdido por cluster.config (que solo tenía node-01 y node-02)
            JettraAssert.assertNotNull(n1.getRingEngine().getPeer("node-03"),
                    "Tras reinicio completo, node-03 debe recuperarse desde cluster-state.config");

            // 6. Reiniciar node-03 desde su estado persistido y verificar reconciliación a RUNNING con PRIMARY
            Properties reloadedState3 = JettraConfigValidator.seedOrLoadClusterState(
                    state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), null);
            n3 = new JettraStoreServer(new JettraStoreConfig(baseProps("node-03", "SECONDARY", g3, r3, data3, state3), reloadedState3, new Properties()));
            n3.setClusterStateFilePath(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n3.start();
            JettraAssert.assertFalse(n3.getConfig().isStandbyMode(), "node-03 reiniciado con cluster-state.config no debe volver a STANDBY");

            String state1Rejoined = Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
            JettraAssert.assertFalse(state1Rejoined.contains("STOPPED"),
                    "Al reincorporarse node-03, su estado en PRIMARY debe reconciliarse a RUNNING:\n" + state1Rejoined);

            // 7. Detener node-03 y removerlo definitivamente vía Driver
            try (JettraClient client = JettraClient.connect(driverCfg)) {
                JettraAssert.assertTrue(client.stopClusterNode("node-03").verifiedState());
                JettraAssert.assertTrue(client.removeStoppedClusterNode("node-03"));
                String finalState1 = Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                String finalState2 = Files.readString(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME), StandardCharsets.UTF_8);
                JettraAssert.assertFalse(finalState1.contains("node-03"), "node-03 debe desaparecer de PRIMARY");
                JettraAssert.assertFalse(finalState2.contains("node-03"), "node-03 debe desaparecer de SECONDARY");
            }
        } finally {
            if (n3 != null) try { n3.stop(); } catch (Exception ignored) {}
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevStateProp == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateProp);
            if (prevClusterProp == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevClusterProp);
            deleteDir(tmp);
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g3, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r3, 2000));
    }
}

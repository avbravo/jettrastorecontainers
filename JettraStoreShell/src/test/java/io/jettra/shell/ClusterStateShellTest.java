package io.jettra.shell;

import io.jettra.driver.JettraClient;
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
 * Pruebas JettraTest para RF-32 en JettraStoreShell:
 * - SHOW CLUSTER STATE / SYNC CLUSTER STATE
 * - ADD NODE sobre nodo STANDBY -> persistencia en cluster-state.config y replicación PRIMARY->SECONDARY
 * - STOP NODE / REMOVE NODE sin modificar cluster.config estático opcional
 */
public class ClusterStateShellTest {

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

    private static Properties nodeProps(String nodeId, String role, int grpc, int rest, Path dataDir, Path stateDir) {
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
    @DisplayName("RF-32: Shell SHOW/SYNC CLUSTER STATE y ADD/STOP/REMOVE persisten cluster-state.config sin alterar cluster.config")
    public void testShellClusterStateCommandsAndImmutableSeed() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_shell_");
        Path staticSeed = tmp.resolve("cluster.config");
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path state3 = tmp.resolve("state3");
        Path shellState = tmp.resolve("shell-state");
        Path shellMp = tmp.resolve("shell-mp.properties");

        String prevStateProp = System.getProperty("jettra.cluster_state_config_dir");
        String prevClusterProp = System.getProperty("cluster.config.path");
        String prevShellCfgProp = System.getProperty("jettra.shell.config.file");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();

        Files.writeString(shellMp,
                "app.title=JettraStore Shell RF32\n"
                        + "jettra.cluster_state_config_dir=" + shellState.toAbsolutePath() + "\n"
                        + "cluster.ip.nodes=127.0.0.1:" + g1 + ",127.0.0.1:" + g2 + "\n",
                StandardCharsets.UTF_8);

        String seedContent = "cluster.name=rf32-shell-cluster\n"
                + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + g1 + "\ncluster.node.1.rest.port=" + r1 + "\n"
                + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + g2 + "\ncluster.node.2.rest.port=" + r2 + "\n";
        Files.writeString(staticSeed, seedContent, StandardCharsets.UTF_8);

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        JettraStoreServer n3 = null;
        try {
            Properties seedProps = new Properties();
            try (var in = Files.newInputStream(staticSeed)) { seedProps.load(in); }

            System.clearProperty("cluster.config.path");
            System.setProperty("jettra.cluster_state_config_dir", shellState.toAbsolutePath().toString());
            System.setProperty("jettra.shell.config.file", shellMp.toAbsolutePath().toString());

            n1 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-01", "PRIMARY", g1, r1, tmp.resolve("d1"), state1), seedProps, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(staticSeed);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-02", "SECONDARY", g2, r2, tmp.resolve("d2"), state2), seedProps, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(staticSeed);
            n2.start();

            n3 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-03", "SECONDARY", g3, r3, tmp.resolve("d3"), state3), new Properties(), new Properties()));
            n3.setClusterStateFilePath(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n3.start();
            JettraAssert.assertTrue(n3.getConfig().isStandbyMode(), "node-03 debe iniciar en STANDBY");

            JettraClientConfig clientCfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", g1)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .build();
            try (JettraClient client = JettraClient.connect(clientCfg)) {
                JettraStoreShellApp shell = new JettraStoreShellApp(client);

                String showState1 = shell.executeCommand("SHOW CLUSTER STATE");
                JettraAssert.assertTrue(showState1.contains("node-01") && showState1.contains("node-02"),
                        "SHOW CLUSTER STATE debe listar node-01 y node-02:\n" + showState1);

                String addOut = shell.executeCommand("ADD NODE node-03 127.0.0.1 " + g3 + " SECONDARY " + r3);
                JettraAssert.assertTrue(addOut.contains("[SUCCESS]"), "ADD NODE debe tener éxito: " + addOut);
                JettraAssert.assertFalse(n3.getConfig().isStandbyMode(), "node-03 debe abandonar modo STANDBY");
                JettraAssert.assertEquals(ClusterNode.Role.SECONDARY, n3.getConfig().getNodeRole());

                JettraAssert.assertTrue(Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("node-03"));
                JettraAssert.assertTrue(Files.readString(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("node-03"));
                JettraAssert.assertTrue(Files.readString(state3.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("node-03"));

                String stopOut = shell.executeCommand("STOP NODE node-03");
                JettraAssert.assertTrue(stopOut.contains("[SUCCESS]"), "STOP NODE debe tener éxito: " + stopOut);
                JettraAssert.assertTrue(Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("STOPPED"));

                String syncOut = shell.executeCommand("SYNC CLUSTER STATE");
                JettraAssert.assertTrue(syncOut.contains("[SUCCESS]") || syncOut.contains("Estado") || syncOut.contains("sincronizado"),
                        "SYNC CLUSTER STATE debe ejecutarse limpiamente: " + syncOut);

                String removeOut = shell.executeCommand("REMOVE NODE node-03");
                JettraAssert.assertTrue(removeOut.contains("[SUCCESS]"), "REMOVE NODE debe tener éxito: " + removeOut);
                JettraAssert.assertFalse(Files.readString(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("node-03"));
                JettraAssert.assertFalse(Files.readString(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)).contains("node-03"));

                // cluster.config estático intacto
                JettraAssert.assertEquals(seedContent, Files.readString(staticSeed, StandardCharsets.UTF_8),
                        "cluster.config estático debe mantenerse inmutable");
            }
        } finally {
            if (n3 != null) try { n3.stop(); } catch (Exception ignored) {}
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevStateProp == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateProp);
            if (prevClusterProp == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevClusterProp);
            if (prevShellCfgProp == null) System.clearProperty("jettra.shell.config.file");
            else System.setProperty("jettra.shell.config.file", prevShellCfgProp);
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

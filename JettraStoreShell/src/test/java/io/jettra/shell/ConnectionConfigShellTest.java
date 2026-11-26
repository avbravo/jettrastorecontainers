package io.jettra.shell;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-32 en JettraStoreShell:
 * - Lectura inicial de cluster.ip.nodes=127.0.0.1:50001 y precedencia de conecction.config
 * - Flujo /connections para editar, guardar en conecction.config y resolver PRIMARY desde SECONDARY
 * - Sincronización de cluster-state.config y estado en vivo en show nodes / getActiveClusterConnectionState
 */
public class ConnectionConfigShellTest {

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
    @DisplayName("RF-32: Shell carga cluster.ip.nodes, edita /connections en conecction.config y sincroniza desde PRIMARY")
    public void testShellInitialClusterIpNodesConnectionsCommandAndPersistence() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_shell_conn_");
        Path connFile = tmp.resolve(JettraConnectionConfigManager.CONNECTION_CONFIG_FILENAME);
        Path shellMp = tmp.resolve("shell-mp.properties");
        Path seedFile = tmp.resolve("cluster.config");
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path shellState = tmp.resolve("shell-state");

        String prevConnFile = System.getProperty("jettra.connection.config.file");
        String prevShellCfg = System.getProperty("jettra.shell.config.file");
        String prevStateDir = System.getProperty("jettra.cluster_state_config_dir");
        String prevClusterPath = System.getProperty("cluster.config.path");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();

        Files.writeString(shellMp,
                "app.title=JettraStore Shell\n"
                        + "jettra.cluster_state_config_dir=" + shellState.toAbsolutePath() + "\n"
                        + "cluster.ip.nodes=127.0.0.1:50001\n",
                StandardCharsets.UTF_8);

        String seed = "cluster.name=rf32-shell-conn\n"
                + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + g1 + "\ncluster.node.1.rest.port=" + r1 + "\n"
                + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + g2 + "\ncluster.node.2.rest.port=" + r2 + "\n";
        Files.writeString(seedFile, seed, StandardCharsets.UTF_8);

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        try {
            System.clearProperty("cluster.config.path");
            System.setProperty("jettra.connection.config.file", connFile.toAbsolutePath().toString());
            System.setProperty("jettra.shell.config.file", shellMp.toAbsolutePath().toString());
            System.setProperty("jettra.cluster_state_config_dir", shellState.toAbsolutePath().toString());

            // 1. Verificar carga inicial desde cluster.ip.nodes=127.0.0.1:50001 (sin conecction.config previo)
            try (JettraStoreShellApp shell0 = new JettraStoreShellApp(false)) {
                var st = shell0.getActiveClusterConnectionState();
                JettraAssert.assertNotNull(st);
                JettraAssert.assertFalse(st.loadedFromConnectionFile());
                JettraAssert.assertEquals("127.0.0.1:50001", st.clusterIpNodes());
            }

            // 2. Arrancar clúster con PRIMARY (g1) y SECONDARY (g2)
            Properties seedProps = new Properties();
            try (var in = Files.newInputStream(seedFile)) { seedProps.load(in); }

            n1 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-01", "PRIMARY", g1, r1, tmp.resolve("d1"), state1), seedProps, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(seedFile);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-02", "SECONDARY", g2, r2, tmp.resolve("d2"), state2), seedProps, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(seedFile);
            n2.start();

            // 3. Conectar al SECONDARY mediante /connections y comprobar persistencia en conecction.config + seguimiento a PRIMARY
            try (JettraStoreShellApp shell1 = new JettraStoreShellApp(false)) {
                String connOut = shell1.executeCommand("/connections 127.0.0.1:" + g2 + " admin");
                JettraAssert.assertTrue(connOut.contains("[SUCCESS]") && connOut.contains("node-01"),
                        "/connections debe guardar y resolver node-01 PRIMARY: " + connOut);
                JettraAssert.assertTrue(Files.exists(connFile), "conecction.config debe crearse al guardar conexión");
                JettraAssert.assertTrue(Files.readString(connFile, StandardCharsets.UTF_8).contains("127.0.0.1:" + g2));

                JettraAssert.assertTrue(shell1.connectAndLogin("127.0.0.1", g2, "admin", "admin-jettra"),
                        "connectAndLogin desde SECONDARY debe autenticar contra PRIMARY activo");

                String showNodesOut = shell1.executeCommand("show nodes");
                JettraAssert.assertTrue(showNodesOut.contains("node-01") && showNodesOut.contains("node-02")
                        && showNodesOut.contains("PRIMARY") && showNodesOut.contains("RUNNING"),
                        "show nodes debe mostrar topología viva desde cluster-state.config del PRIMARY:\n" + showNodesOut);

                String showConnOut = shell1.executeCommand("/connections");
                JettraAssert.assertTrue(showConnOut.contains("conecction.config") && showConnOut.contains("cluster-state.config"),
                        "/connections debe mostrar estado sincronizado desde PRIMARY:\n" + showConnOut);
                JettraAssert.assertFalse(Files.exists(state1.resolve("cluster-status.config")),
                        "No debe generarse cluster-status.config en PRIMARY");
                JettraAssert.assertFalse(Files.exists(shellState.resolve("cluster-status.config")),
                        "No debe generarse cluster-status.config en Shell");
            }

            // 4. Nueva instancia de Shell debe cargar automáticamente 127.0.0.1:g2 desde conecction.config
            try (JettraStoreShellApp shellReloaded = new JettraStoreShellApp(false)) {
                var reloadedState = shellReloaded.getActiveClusterConnectionState();
                JettraAssert.assertNotNull(reloadedState);
                JettraAssert.assertTrue(reloadedState.loadedFromConnectionFile(), "Debe cargar desde conecction.config");
                JettraAssert.assertEquals("127.0.0.1:" + g2, reloadedState.clusterIpNodes());
            }
        } finally {
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevConnFile == null) System.clearProperty("jettra.connection.config.file");
            else System.setProperty("jettra.connection.config.file", prevConnFile);
            if (prevShellCfg == null) System.clearProperty("jettra.shell.config.file");
            else System.setProperty("jettra.shell.config.file", prevShellCfg);
            if (prevStateDir == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateDir);
            if (prevClusterPath == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevClusterPath);
            deleteDir(tmp);
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
    }
}

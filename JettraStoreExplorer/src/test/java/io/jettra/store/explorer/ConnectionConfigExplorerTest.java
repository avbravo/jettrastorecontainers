package io.jettra.store.explorer;

import io.jettra.driver.config.JettraConnectionConfigManager;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
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
 * Pruebas JettraTest para RF-32 en JettraStoreExplorer:
 * - Lectura inicial desde cluster.ip.nodes (127.0.0.1:50001) y precedencia de conecction.config
 * - Edición, guardado atómico en conecction.config y resolución del PRIMARY desde cualquier nodo
 * - Propagación en tiempo real del estado de cluster-state.config a serverNodes
 */
public class ConnectionConfigExplorerTest {

    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void backupConfiguredState() {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
    }

    @AfterAll
    public static void restoreConfiguredState() {
        try {
            App.stopAllApplications();
            JettraStoreServer.stopAllActiveServers();
            JettraAssert.assertEquals(0, App.getActiveApplicationCount());
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

    @Test
    @DisplayName("RF-32: Explorer guarda conecction.config, sigue SECONDARY->PRIMARY y sincroniza cluster-state.config")
    public void testExplorerConnectionEditSaveAndRealtimeClusterStatusSync() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_exp_conn_");
        Path connFile = tmp.resolve(JettraConnectionConfigManager.CONNECTION_CONFIG_FILENAME);
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path seedFile = tmp.resolve("cluster.config");

        String prevConnFile = System.getProperty("jettra.connection.config.file");
        String prevStateDir = System.getProperty("jettra.cluster_state_config_dir");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();

        String seed = "cluster.name=rf32-exp-conn\n"
                + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + g1 + "\ncluster.node.1.rest.port=" + r1 + "\n"
                + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + g2 + "\ncluster.node.2.rest.port=" + r2 + "\n";
        Files.writeString(seedFile, seed, StandardCharsets.UTF_8);

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        var originalNodes = new java.util.ArrayList<>(plugin.getServerNodes());
        var originalConnState = plugin.getClusterConnectionState();
        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        try {
            System.setProperty("jettra.connection.config.file", connFile.toAbsolutePath().toString());
            System.setProperty("jettra.cluster_state_config_dir", tmp.resolve("exp-state").toAbsolutePath().toString());

            // 1. Verificar carga inicial desde cluster.ip.nodes (127.0.0.1:50001) cuando no existe conecction.config
            plugin.initClusterConnectionConfig();
            JettraAssert.assertEquals("127.0.0.1:50001", plugin.getClusterConnectionState().clusterIpNodes());
            JettraAssert.assertFalse(plugin.getClusterConnectionState().loadedFromConnectionFile());

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

            // 3. Conectar apuntando únicamente al SECONDARY (127.0.0.1:g2) y persistir en conecction.config
            var res = plugin.applyAndSaveClusterConnection("127.0.0.1:" + g2, "admin", true);
            JettraAssert.assertTrue(res.success(), "applyAndSaveClusterConnection debe tener éxito: " + res.message());
            JettraAssert.assertTrue(res.persisted(), "Debe persistir en conecction.config");
            JettraAssert.assertTrue(res.primaryResolved(), "Debe resolver PRIMARY desde SECONDARY");
            JettraAssert.assertEquals("node-01", res.primaryNodeId());
            JettraAssert.assertTrue(Files.exists(connFile), "conecction.config debe existir en el directorio configurado");
            JettraAssert.assertTrue(Files.readString(connFile, StandardCharsets.UTF_8).contains("127.0.0.1:" + g2));

            // Ambos nodos del clúster deben reflejarse en tiempo real en serverNodes y solo existir cluster-state.config
            JettraAssert.assertNotNull(plugin.getNodeById("node-01"), "node-01 PRIMARY debe haberse sincronizado");
            JettraAssert.assertNotNull(plugin.getNodeById("node-02"), "node-02 SECONDARY debe haberse sincronizado");
            JettraAssert.assertTrue(plugin.getNodeById("node-01").isActive(), "node-01 debe estar ONLINE");
            JettraAssert.assertTrue(Files.exists(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME)),
                    "Debe generarse cluster-state.config en PRIMARY");
            JettraAssert.assertFalse(Files.exists(state1.resolve("cluster-status.config")),
                    "No debe generarse cluster-status.config en PRIMARY");
            JettraAssert.assertFalse(Files.exists(tmp.resolve("exp-state").resolve("cluster-status.config")),
                    "No debe generarse cluster-status.config en Explorer");

            // 4. Al recargar configuración desde disco, conecction.config prevalece
            plugin.initClusterConnectionConfig();
            JettraAssert.assertTrue(plugin.getClusterConnectionState().loadedFromConnectionFile());
            JettraAssert.assertEquals("127.0.0.1:" + g2, plugin.getClusterConnectionState().clusterIpNodes());

            // 5. Simular caída de node-02 y verificar actualización de estado tras refresco
            n2.stop();
            plugin.refreshAllNodeStatuses(true);
            JettraAssert.assertFalse(plugin.getNodeById("node-02").isOnline(), "node-02 detenido debe pasar a OFFLINE");
            JettraAssert.assertTrue(plugin.getNodeById("node-01").isActive(), "node-01 debe permanecer ONLINE");
        } finally {
            plugin.restoreServerNodes(originalNodes);
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevConnFile == null) System.clearProperty("jettra.connection.config.file");
            else System.setProperty("jettra.connection.config.file", prevConnFile);
            if (prevStateDir == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateDir);
            plugin.initClusterConnectionConfig();
            if (originalConnState != null) {
                plugin.applyAndSaveClusterConnection(originalConnState.clusterIpNodes(), originalConnState.username(), false);
            }
            JettraExplorerTestCleanup.deleteRecursively(tmp);
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
    }
}

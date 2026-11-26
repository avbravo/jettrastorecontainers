package io.jettra.store.explorer;

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
 * - Persistencia atómica en <jettra.cluster_state_config_dir>/cluster-state.config desde JettraStorePlugin
 * - Sincronización/replicación hacia PRIMARY y SECONDARY sin alterar cluster.config estático
 * - Lectura prioritaria desde el estado persistente del PRIMARY en fetchPrimaryClusterConfig()
 */
public class ClusterStateExplorerTest {

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
    @DisplayName("RF-32: JettraStorePlugin guarda y sincroniza cluster-state.config en PRIMARY/SECONDARY sin alterar cluster.config")
    public void testExplorerSavesAndReplicatesClusterStateWithoutModifyingStaticSeed() throws Exception {
        Path tmp = Files.createTempDirectory("jettra_rf32_explorer_");
        Path staticSeed = tmp.resolve("cluster.config");
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path explorerState = tmp.resolve("explorer-state");

        String prevStateProp = System.getProperty("jettra.cluster_state_config_dir");
        String prevClusterProp = System.getProperty("cluster.config.path");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();

        String seedContent = "cluster.name=rf32-explorer-cluster\n"
                + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + g1 + "\ncluster.node.1.rest.port=" + r1 + "\n"
                + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + g2 + "\ncluster.node.2.rest.port=" + r2 + "\n";
        Files.writeString(staticSeed, seedContent, StandardCharsets.UTF_8);

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        var originalNodes = new java.util.ArrayList<>(plugin.getServerNodes());
        try {
            Properties seedProps = new Properties();
            try (var in = Files.newInputStream(staticSeed)) { seedProps.load(in); }

            System.setProperty("jettra.cluster_state_config_dir", explorerState.toAbsolutePath().toString());
            System.setProperty("cluster.config.path", staticSeed.toAbsolutePath().toString());

            n1 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-01", "PRIMARY", g1, r1, tmp.resolve("d1"), state1), seedProps, new Properties()));
            n1.setClusterStateFilePath(state1.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n1.setClusterConfigFilePath(staticSeed);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(nodeProps("node-02", "SECONDARY", g2, r2, tmp.resolve("d2"), state2), seedProps, new Properties()));
            n2.setClusterStateFilePath(state2.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME));
            n2.setClusterConfigFilePath(staticSeed);
            n2.start();

            String updatedState = seedContent
                    + "cluster.node.3.id=node-03\n"
                    + "cluster.node.3.role=SECONDARY\n"
                    + "cluster.node.3.ip=127.0.0.1\n"
                    + "cluster.node.3.grpc.port=" + g3 + "\n"
                    + "cluster.node.3.rest.port=" + r3 + "\n"
                    + "cluster.node.3.status=STOPPED\n";

            var report = plugin.saveClusterConfigContent(updatedState, true);
            JettraAssert.assertTrue(report.success(), "saveClusterConfigContent debe guardar en cluster-state.config: " + report.message());

            Path explorerStateFile = explorerState.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            JettraAssert.assertTrue(Files.exists(explorerStateFile), "Explorer debe persistir cluster-state.config");
            JettraAssert.assertTrue(Files.readString(explorerStateFile, StandardCharsets.UTF_8).contains("node-03"),
                    "cluster-state.config debe contener node-03");

            // Verificar que el PRIMARY persistió y fetchPrimaryClusterConfig lee la topología actualizada
            var snap = plugin.fetchPrimaryClusterConfig();
            JettraAssert.assertTrue(snap.valid(), "Snapshot del PRIMARY debe ser válido");
            JettraAssert.assertTrue(snap.content().contains("node-03"), "fetchPrimaryClusterConfig debe incluir node-03");

            // cluster.config estático jamás se altera
            JettraAssert.assertEquals(seedContent, Files.readString(staticSeed, StandardCharsets.UTF_8),
                    "cluster.config estático debe permanecer sin modificaciones");
        } finally {
            plugin.restoreServerNodes(originalNodes);
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevStateProp == null) System.clearProperty("jettra.cluster_state_config_dir");
            else System.setProperty("jettra.cluster_state_config_dir", prevStateProp);
            if (prevClusterProp == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevClusterProp);
            JettraExplorerTestCleanup.deleteRecursively(tmp);
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", g2, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
    }
}

package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ClusterLifecycleShellTest {

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

    private JettraStoreServer startNodeServer(Path baseDir, Path clusterFile, String nodeId, String role, int grpcPort, int restPort) throws Exception {
        Path dataDir = baseDir.resolve(nodeId);
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

        Properties clusterProps = JettraConfigValidator.loadProperties(clusterFile, "/cluster.config", "/jettra.config");
        JettraStoreConfig cfg = new JettraStoreConfig(props, clusterProps, props);
        JettraStoreServer srv = new JettraStoreServer(cfg);
        srv.setClusterConfigFilePath(clusterFile);
        srv.start();
        return srv;
    }

    @Test
    @DisplayName("RF-01: show nodes (líder único), stop node, remove node, add node e índices NUMERIC/AUTO_INCREMENT en Shell")
    public void testClusterLifecycleAndNumericIndexesInShell() throws Exception {
        Path tmpDir = Files.createTempDirectory("jettra_shell_rf01_");
        Path clusterFile = tmpDir.resolve("cluster.config");
        String prevClusterProp = System.getProperty("cluster.config.path");

        int g1 = freePort(), r1 = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();

        String initialCfg = """
                cluster.multinode.active=on
                cluster.node.1.id=node-01
                cluster.node.1.role=PRIMARY
                cluster.node.1.ip=127.0.0.1
                cluster.node.1.grpc.port=%d
                cluster.node.1.rest.port=%d
                cluster.node.2.id=node-02
                cluster.node.2.role=SECONDARY
                cluster.node.2.ip=127.0.0.1
                cluster.node.2.grpc.port=%d
                cluster.node.2.rest.port=%d
                cluster.node.3.id=node-03
                cluster.node.3.role=SECONDARY
                cluster.node.3.ip=127.0.0.1
                cluster.node.3.grpc.port=%d
                cluster.node.3.rest.port=%d
                """.formatted(g1, r1, g2, r2, g3, r3);
        Files.writeString(clusterFile, initialCfg);
        System.setProperty("cluster.config.path", clusterFile.toAbsolutePath().toString());

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        JettraStoreServer n3 = null;
        try {
            n1 = startNodeServer(tmpDir, clusterFile, "node-01", "PRIMARY", g1, r1);
            n2 = startNodeServer(tmpDir, clusterFile, "node-02", "SECONDARY", g2, r2);
            n3 = startNodeServer(tmpDir, clusterFile, "node-03", "SECONDARY", g3, r3);

            JettraClientConfig cCfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", g1)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .defaultDatabase("rf01_shell_db_" + System.currentTimeMillis())
                    .build();
            try (JettraClient client = JettraClient.connect(cCfg);
                 JettraStoreShellApp shell = new JettraStoreShellApp(client)) {

                // 1. Verificar show nodes: exactamente UN PRIMARY/LEADER
                String show1 = shell.executeCommand("show nodes");
                long primaryRows = show1.lines().filter(l -> l.contains("| PRIMARY   | LEADER")).count();
                JettraAssert.assertEquals(1L, primaryRows, "show nodes debe reportar exactamente 1 PRIMARY/LEADER:\n" + show1);

                // 2. Intentar remover un nodo ACTIVO (node-03) -> debe bloquearse
                String blockedRemove = shell.executeCommand("remove node node-03");
                JettraAssert.assertTrue(blockedRemove.contains("[ERROR]") && blockedRemove.contains("RUNNING"),
                        "Debe impedir remover node-03 mientras esté RUNNING: " + blockedRemove);

                // 3. Detener node-03 con STOP NODE -> debe detener realmente el puerto y reflejarse en show nodes
                String stopRes = shell.executeCommand("stop node node-03");
                JettraAssert.assertTrue(stopRes.contains("[SUCCESS]"), "stop node node-03 debe tener éxito: " + stopRes);

                String showAfterStop = shell.executeCommand("show nodes");
                boolean node3Stopped = showAfterStop.lines().anyMatch(l -> l.contains("node-03") && l.contains("STOPPED"));
                JettraAssert.assertTrue(node3Stopped, "show nodes debe mostrar node-03 como STOPPED:\n" + showAfterStop);

                // 4. Remover node-03 ya detenido -> debe tener éxito sin detener node-01 ni node-02
                String removeRes = shell.executeCommand("remove node node-03");
                JettraAssert.assertTrue(removeRes.contains("[SUCCESS]"), "remove node node-03 detenido debe tener éxito: " + removeRes);

                // 5. Índices AUTO_INCREMENT y NUMERIC desde la Shell
                shell.executeCommand("create bucket articulos engine document structure none");
                String idxAuto = shell.executeCommand("create bucket index idx_art_id on articulos (id) type auto_increment");
                JettraAssert.assertTrue(idxAuto.contains("[SUCCESS]") && idxAuto.contains("AUTO_INCREMENT"), "Debe crear índice AUTO_INCREMENT: " + idxAuto);

                String idxNum = shell.executeCommand("create bucket index idx_art_precio on articulos (precio) type numeric");
                JettraAssert.assertTrue(idxNum.contains("[SUCCESS]") && idxNum.contains("NUMERIC"), "Debe crear índice NUMERIC: " + idxNum);

                String ins1 = shell.executeCommand("insert bucket articulos {\"nombre\":\"A\",\"precio\":20.0}");
                String ins2 = shell.executeCommand("insert bucket articulos {\"nombre\":\"B\",\"precio\":45.5}");
                JettraAssert.assertTrue(ins1.contains("[SUCCESS]") && ins2.contains("[SUCCESS]"), "Inserciones deben pasar");

                String rangeRes = shell.executeCommand("find numeric articulos precio 15 50");
                JettraAssert.assertTrue(rangeRes.contains("2 registro(s)"), "Búsqueda por rango numérico debe encontrar 2 registros: " + rangeRes);

                String showIdxs = shell.executeCommand("show bucket indexes articulos");
                JettraAssert.assertTrue(showIdxs.contains("seq=2"), "show bucket indexes debe reportar seq=2: " + showIdxs);
            }
        } finally {
            if (n3 != null) try { n3.stop(); } catch (Exception ignored) {}
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevClusterProp == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevClusterProp);
            JettraShellTestCleanup.deleteRecursively(tmpDir);
        }
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", g1));
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", r1));
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", g2));
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", r2));
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", g3));
        JettraAssert.assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", r3));
    }
}

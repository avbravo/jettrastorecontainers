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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Pruebas automatizadas con JettraTest para RF-28:
 * Validación en tiempo real de nodos activos en JettraStoreShell antes de ejecutar
 * comandos de bases de datos (bloqueo con 0 nodos activos, ejecución con >= 1 nodo activo).
 */
public class ActiveNodeValidationShellTest {

    private static Path tempDir;
    private static Path tempClusterFile;
    private static Path tempMpFile;
    private static String prevClusterProp;
    private static String prevMpProp;
    private static String prevStorageProp;
    private static String prevNodesBaseDirProp;
    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    @BeforeAll
    public static void setup() throws Exception {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
        prevClusterProp = System.getProperty("cluster.config.path");
        prevMpProp = System.getProperty("jettra.shell.config.file");
        prevStorageProp = System.getProperty("jettra.storage.path");
        prevNodesBaseDirProp = System.getProperty("jettra.nodes.base.dir");

        if (JettraStoreServer.getActiveInstance() != null) {
            try {
                JettraStoreServer.getActiveInstance().stop();
            } catch (Exception ignored) {
            }
        }

        tempDir = Files.createTempDirectory("jettra_shell_rf28_");
        tempClusterFile = tempDir.resolve("cluster-rf28.config");
        tempMpFile = tempDir.resolve("microprofile-rf28.properties");

        System.setProperty("jettra.storage.path", tempDir.toAbsolutePath().toString());
        System.setProperty("jettra.nodes.base.dir", tempDir.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", tempClusterFile.toAbsolutePath().toString());
        System.setProperty("jettra.shell.config.file", tempMpFile.toAbsolutePath().toString());
    }

    @AfterAll
    public static void cleanup() {
        try {
            JettraClient.stopLaunchedNodeProcesses();
            JettraStoreServer.stopAllActiveServers();
            JettraAssert.assertEquals(0, JettraStoreServer.getActiveServerCount());
            JettraAssert.assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        } finally {
            restoreProp("jettra.nodes.base.dir", prevNodesBaseDirProp);
            restoreProp("cluster.config.path", prevClusterProp);
            restoreProp("jettra.shell.config.file", prevMpProp);
            restoreProp("jettra.storage.path", prevStorageProp);
            JettraShellTestCleanup.deleteRecursively(tempDir);
            if (suiteStateBackup != null) {
                suiteStateBackup.close();
                suiteStateBackup = null;
            }
        }
    }

    private static void restoreProp(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("RF-28: Bloquea comandos de BD con 0 nodos activos pero mantiene habilitados comandos locales y administrativos")
    public void testDatabaseCommandsBlockedWhenZeroNodesActive() throws Exception {
        int closedGrpc1 = freePort();
        int closedRest1 = freePort();
        int closedGrpc2 = freePort();
        int closedRest2 = freePort();

        Files.writeString(tempClusterFile,
                "cluster.multinode.active=on\n"
                        + "cluster.node.1.id=node-01\n"
                        + "cluster.node.1.role=PRIMARY\n"
                        + "cluster.node.1.ip=127.0.0.1\n"
                        + "cluster.node.1.grpc.port=" + closedGrpc1 + "\n"
                        + "cluster.node.1.rest.port=" + closedRest1 + "\n"
                        + "cluster.node.2.id=node-02\n"
                        + "cluster.node.2.role=SECONDARY\n"
                        + "cluster.node.2.ip=127.0.0.1\n"
                        + "cluster.node.2.grpc.port=" + closedGrpc2 + "\n"
                        + "cluster.node.2.rest.port=" + closedRest2 + "\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempMpFile,
                "app.title=JettraStore Shell\n"
                        + "cluster.ip.nodes=127.0.0.1:" + closedGrpc1
                        + ",127.0.0.1:" + closedGrpc2 + "\n",
                StandardCharsets.UTF_8);

        JettraClientConfig cfg = JettraClientConfig.builder()
                .addClusterNode("127.0.0.1", closedGrpc1)
                .credentials("admin", "admin-jettra")
                .role("admin")
                .clusterMultinodeActive(true)
                .build();

        try (JettraClient client = JettraClient.connect(cfg);
             JettraStoreShellApp shell = new JettraStoreShellApp(client)) {
            // Verificar 0 nodos activos en tiempo real
            JettraAssert.assertFalse(shell.hasActiveClusterNode(),
                    "No debe detectarse ningún nodo activo cuando todos los puertos están cerrados");
            JettraAssert.assertEquals(0, shell.getActiveClusterNodeCount(),
                    "El conteo en tiempo real de nodos activos debe ser 0");

            // 1. Comandos de operación de BD deben ser rechazados inmediatamente con mensaje claro
            List<String> blockedCommands = List.of(
                    "SHOW DATABASES",
                    "CREATE DATABASE rf28_blocked_db",
                    "DROP DATABASE rf28_blocked_db",
                    "USE rf28_blocked_db",
                    "CREATE BUCKET productos ENGINE DOCUMENT",
                    "SHOW BUCKETS",
                    "CREATE INDEX idx_n ON productos (nombre) TYPE BTREE",
                    "SHOW INDEXES",
                    "INSERT INTO productos ID p1 JSON {\"nombre\":\"Test\"}",
                    "SELECT * FROM productos",
                    "UPDATE productos SET {\"nombre\":\"X\"} WHERE _id = 'p1'",
                    "DELETE productos p1",
                    "VERSIONS productos p1",
                    "RESTORE VERSION productos p1 1",
                    "INSTALL SAMPLES"
            );

            for (String cmd : blockedCommands) {
                String out = shell.executeCommand(cmd);
                JettraAssert.assertEquals(JettraStoreShellApp.NO_ACTIVE_NODES_ERROR, out,
                        "El comando de BD '" + cmd + "' debe rechazarse cuando hay 0 nodos activos. Salida: " + out);
            }

            // 2. Comandos locales, administrativos y de diagnóstico siguen habilitados
            JettraAssert.assertTrue(shell.executeCommand("help").contains("JETTRASTORE SHELL"),
                    "help debe seguir funcionando sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("clear").contains("[CLEAR]"),
                    "clear debe seguir funcionando sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("show nodes").contains("DISCONNECTED"),
                    "show nodes debe seguir permitiendo inspeccionar topología sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("status").contains("RAM (MEMORIA)"),
                    "status debe seguir disponible sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("list connections").contains("node-01"),
                    "list connections debe seguir disponible sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("connect 127.0.0.1 " + closedGrpc1).contains("[CONNECTED]"),
                    "connect debe seguir habilitado sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("start node node-01").contains("[ERROR]"),
                    "start node debe seguir intentando levantar/verificar el nodo sin bloquearse como comando de BD");
            JettraAssert.assertTrue(shell.executeCommand("exit").contains("Saliendo"),
                    "exit debe seguir funcionando sin nodos activos");
            JettraAssert.assertTrue(shell.executeCommand("quit").contains("Saliendo"),
                    "quit debe seguir funcionando sin nodos activos");
        }
    }

    @Test
    @DisplayName("RF-28: Permite comandos de BD con >= 1 nodo activo y vuelve a bloquearlos al detener el último nodo")
    public void testDatabaseCommandsSucceedWithActiveNodeAndBlockAgainAfterShutdown() throws Exception {
        int grpcPort = freePort();
        int restPort = freePort();
        Path nodeStorage = tempDir.resolve("node01_data");
        Files.createDirectories(nodeStorage);

        Files.writeString(tempClusterFile,
                "cluster.multinode.active=off\n"
                        + "cluster.node.1.id=node-01\n"
                        + "cluster.node.1.role=PRIMARY\n"
                        + "cluster.node.1.ip=127.0.0.1\n"
                        + "cluster.node.1.grpc.port=" + grpcPort + "\n"
                        + "cluster.node.1.rest.port=" + restPort + "\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempMpFile,
                "app.title=JettraStore Shell\n"
                        + "cluster.ip.nodes=127.0.0.1:" + grpcPort + "\n",
                StandardCharsets.UTF_8);

        Properties srvProps = new Properties();
        srvProps.setProperty("jettra.storage.path", nodeStorage.toAbsolutePath().toString());
        srvProps.setProperty("jettra.storage.mode", "JVM_RAM");
        srvProps.setProperty("cluster.multinode.active", "on");
        srvProps.setProperty("cluster.node.id", "node-01");
        srvProps.setProperty("cluster.node.role", "PRIMARY");
        srvProps.setProperty("jettra.node.ip", "127.0.0.1");
        srvProps.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
        srvProps.setProperty("jettra.network.rest.port", String.valueOf(restPort));
        srvProps.setProperty("jettrapolice.active", "false");

        Properties clusterProps = JettraConfigValidator.loadProperties(tempClusterFile, "/cluster.config", "/jettra.config");
        JettraStoreConfig serverCfg = new JettraStoreConfig(srvProps, clusterProps, srvProps);
        JettraStoreServer server = new JettraStoreServer(serverCfg);
        server.setClusterConfigFilePath(tempClusterFile);

        try {
            server.start();

            JettraClientConfig clientCfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", grpcPort)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .clusterMultinodeActive(true)
                    .build();

            try (JettraClient client = JettraClient.connect(clientCfg);
                 JettraStoreShellApp shell = new JettraStoreShellApp(client)) {
                JettraAssert.assertTrue(shell.hasActiveClusterNode(),
                        "Debe detectar al menos 1 nodo activo (ONLINE) cuando node-01 está ejecutándose");

                String dbName = "rf28_active_test_db";
                JettraAssert.assertTrue(shell.executeCommand("CREATE DATABASE " + dbName).contains("[SUCCESS]"),
                        "CREATE DATABASE debe ejecutarse con éxito teniendo >= 1 nodo activo");
                JettraAssert.assertTrue(shell.executeCommand("USE " + dbName).contains("[SUCCESS]"),
                        "USE debe ejecutarse con éxito teniendo >= 1 nodo activo");
                JettraAssert.assertTrue(shell.executeCommand("CREATE BUCKET clientes TYPE DOCUMENT").contains("[SUCCESS]"),
                        "CREATE BUCKET debe ejecutarse con éxito teniendo >= 1 nodo activo");
                JettraAssert.assertTrue(
                        shell.executeCommand("INSERT INTO clientes ID c1 JSON {\"nombre\":\"Ana\"}").contains("[SUCCESS]"),
                        "INSERT INTO debe ejecutarse con éxito teniendo >= 1 nodo activo");
                JettraAssert.assertTrue(shell.executeCommand("SELECT * FROM clientes").contains("Ana"),
                        "SELECT debe ejecutarse con éxito teniendo >= 1 nodo activo");
                JettraAssert.assertTrue(shell.executeCommand("SHOW DATABASES").contains(dbName),
                        "SHOW DATABASES debe listar la base creada con >= 1 nodo activo");
                JettraAssert.assertTrue(shell.executeCommand("DROP DATABASE " + dbName).contains("[SUCCESS]"),
                        "DROP DATABASE debe limpiar la base temporal");

                // Detener el último nodo activo y verificar bloqueo inmediato en tiempo real
                server.stop();
                for (int i = 0; i < 20 && shell.hasActiveClusterNode(); i++) {
                    Thread.sleep(25);
                }
                JettraAssert.assertFalse(shell.hasActiveClusterNode(),
                        "Tras detener el último nodo activo, hasActiveClusterNode debe retornar false (active="
                                + client.findActiveClusterEndpoints(shell.getCurrentHost(), shell.getCurrentPort(), List.of())
                                + ")");
                String afterStop = shell.executeCommand("SHOW DATABASES");
                JettraAssert.assertEquals(JettraStoreShellApp.NO_ACTIVE_NODES_ERROR, afterStop,
                        "Tras detener todos los nodos, SHOW DATABASES debe bloquearse de inmediato");
            }
        } finally {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", grpcPort, 2000));
        JettraAssert.assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", restPort, 2000));
    }
}

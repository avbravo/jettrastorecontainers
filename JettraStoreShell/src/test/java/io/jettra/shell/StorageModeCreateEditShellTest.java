package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.core.StorageMode;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-38 en JettraStoreShell:
 * - CREATE DATABASE especificando JVM-RAM / DISK-MEMORY / OFF-HEAP (predeterminado)
 * - EDIT DATABASE para actualizar el Storage Mode de una BD
 * - HELP y HELP STORAGE_MODE actualizados con OFF-HEAP predeterminado
 */
public class StorageModeCreateEditShellTest {

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
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("RF-38: Shell crea y edita bases de datos con StorageMode y documenta OFF-HEAP por defecto en HELP")
    public void testShellCreateAndEditDatabaseWithStorageMode() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra-rf38-shell-");
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path stateDir = Files.createDirectories(tempDir.resolve("state"));
        int grpcPort = freePort();
        int restPort = freePort();

        String prevStoragePath = System.getProperty("jettra.storage.path");
        System.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
        JettraStoreServer server = null;
        try {
            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-sh-rf38");
            props.setProperty("cluster.node.role", "PRIMARY");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("cluster.node.ip", "127.0.0.1");
            props.setProperty("cluster.node.grpc.port", String.valueOf(grpcPort));
            props.setProperty("cluster.node.rest.port", String.valueOf(restPort));
            props.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
            props.setProperty("jettra.index.storage.path", dataDir.resolve("indexes").toAbsolutePath().toString());
            props.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());

            server = new JettraStoreServer(new JettraStoreConfig(props));
            server.start();

            JettraClientConfig cfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", grpcPort)
                    .credentials("admin", "admin-jettra")
                    .defaultDatabase("shell_default_db")
                    .build();

            try (JettraClient client = JettraClient.connect(cfg);
                 JettraStoreShellApp shell = new JettraStoreShellApp(client)) {
                JettraAssert.assertTrue(shell.executeCommand("connect 127.0.0.1 " + grpcPort).contains("[CONNECTED]"));
                JettraAssert.assertTrue(shell.executeCommand("login admin admin-jettra").contains("[AUTH SUCCESS]"));

                String helpOut = shell.executeCommand("HELP");
                JettraAssert.assertTrue(helpOut.toUpperCase().contains("CREATE DATABASE <NOMBRE> [STORAGE_MODE]"));
                JettraAssert.assertTrue(helpOut.toUpperCase().contains("EDIT DATABASE <NOMBRE> <STORAGE_MODE>"));
                JettraAssert.assertTrue(helpOut.contains("OFF-HEAP (Predeterminado)"));

                String helpStorage = shell.executeCommand("HELP STORAGE_MODE");
                JettraAssert.assertTrue(helpStorage.contains("CREATE DATABASE"));
                JettraAssert.assertTrue(helpStorage.contains("EDIT DATABASE"));
                JettraAssert.assertTrue(helpStorage.contains("[PREDETERMINADO]"));

                String resDef = shell.executeCommand("CREATE DATABASE shell_rf38_def");
                JettraAssert.assertTrue(resDef.contains("[SUCCESS]"));
                JettraAssert.assertTrue(resDef.contains("OFF-HEAP"));
                JettraAssert.assertEquals(StorageMode.OFF_HEAP, client.getStorageMode("shell_rf38_def"));

                String resDisk = shell.executeCommand("CREATE DATABASE shell_rf38_disk DISK-MEMORY");
                JettraAssert.assertTrue(resDisk.contains("[SUCCESS]"));
                JettraAssert.assertTrue(resDisk.contains("DISK-MEMORY"));
                JettraAssert.assertEquals(StorageMode.DISK_MEMORY, client.getStorageMode("shell_rf38_disk"));

                String resBad = shell.executeCommand("CREATE DATABASE shell_rf38_bad INVALID_MODE");
                JettraAssert.assertTrue(resBad.contains("[ERROR]"));
                JettraAssert.assertTrue(resBad.contains("OFF-HEAP"));

                String resEdit = shell.executeCommand("EDIT DATABASE shell_rf38_disk JVM-RAM");
                JettraAssert.assertTrue(resEdit.contains("[SUCCESS]"));
                JettraAssert.assertTrue(resEdit.contains("JVM-RAM"));
                JettraAssert.assertEquals(StorageMode.JVM_RAM, client.getStorageMode("shell_rf38_disk"));
            }
        } finally {
            if (prevStoragePath == null) {
                System.clearProperty("jettra.storage.path");
            } else {
                System.setProperty("jettra.storage.path", prevStoragePath);
            }
            if (server != null) {
                server.stop();
            }
            JettraStoreServer.stopAllActiveServers();
            deleteDir(tempDir);
        }
    }
}

package io.jettra.driver;

import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.core.JettraDatabase;
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
import java.util.List;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-38 en JettraStoreDriver:
 * - StorageMode predeterminado OFF-HEAP en JettraClientConfig
 * - Creación y edición de Storage Mode por base de datos desde JettraClient
 */
public class StorageModeDriverSelectionTest {

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
    @DisplayName("RF-38: JettraClientConfig usa OFF_HEAP por defecto y JettraClient expone creación/edición por StorageMode")
    public void testDriverDefaultAndPerDatabaseStorageMode() throws Exception {
        JettraClientConfig defaultCfg = JettraClientConfig.builder().build();
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, defaultCfg.getDefaultStorageMode());

        Path tempDir = Files.createTempDirectory("jettra-rf38-driver-");
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path stateDir = Files.createDirectories(tempDir.resolve("state"));
        int grpcPort = freePort();
        int restPort = freePort();

        JettraStoreServer server = null;
        try {
            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-drv-rf38");
            props.setProperty("cluster.node.role", "PRIMARY");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("cluster.node.ip", "127.0.0.1");
            props.setProperty("cluster.node.grpc.port", String.valueOf(grpcPort));
            props.setProperty("cluster.node.rest.port", String.valueOf(restPort));
            props.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
            props.setProperty("jettra.index.storage.path", dataDir.resolve("indexes").toAbsolutePath().toString());
            props.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());

            JettraStoreConfig storeCfg = new JettraStoreConfig(props);
            server = new JettraStoreServer(storeCfg);
            server.start();

            JettraClientConfig clientCfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", grpcPort)
                    .credentials("admin", "admin-jettra")
                    .defaultDatabase("drv_default_db")
                    .build();

            try (JettraClient client = JettraClient.connect(clientCfg)) {
                List<StorageMode> modes = client.getAvailableStorageModes();
                JettraAssert.assertEquals(3, modes.size());
                JettraAssert.assertTrue(modes.contains(StorageMode.JVM_RAM));
                JettraAssert.assertTrue(modes.contains(StorageMode.DISK_MEMORY));
                JettraAssert.assertTrue(modes.contains(StorageMode.OFF_HEAP));

                JettraAssert.assertTrue(client.createDatabase("drv_db_default", null));
                JettraAssert.assertEquals(StorageMode.OFF_HEAP, client.getStorageMode("drv_db_default"));

                JettraAssert.assertTrue(client.createDatabase("drv_db_disk", StorageMode.DISK_MEMORY));
                JettraAssert.assertEquals(StorageMode.DISK_MEMORY, client.getStorageMode("drv_db_disk"));
                JettraAssert.assertEquals(StorageMode.DISK_MEMORY,
                        JettraDatabase.readPersistedStorageMode("drv_db_disk", storeCfg));

                JettraAssert.assertTrue(client.updateDatabaseStorageMode("drv_db_disk", StorageMode.JVM_RAM));
                JettraAssert.assertEquals(StorageMode.JVM_RAM, client.getStorageMode("drv_db_disk"));
                JettraAssert.assertEquals(StorageMode.JVM_RAM,
                        JettraDatabase.readPersistedStorageMode("drv_db_disk", storeCfg));
            }
        } finally {
            if (server != null) {
                server.stop();
            }
            JettraStoreServer.stopAllActiveServers();
            deleteDir(tempDir);
        }
    }
}

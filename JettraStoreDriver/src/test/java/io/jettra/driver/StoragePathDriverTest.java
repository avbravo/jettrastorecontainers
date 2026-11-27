package io.jettra.driver;

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
import java.util.Map;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-39 en JettraStoreDriver:
 * - Verifica que insertDocument y creación de BD vía JettraClient persistan los registros en
 *   <jettra.storage.path>/<db>/ para OFF-HEAP y DISK-MEMORY, y no persistan en disco para JVM-RAM.
 */
public class StoragePathDriverTest {

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
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("RF-39: Driver refleja en jettra.storage.path los registros OFF-HEAP y DISK-MEMORY y mantiene JVM-RAM volátil")
    public void testDriverStoragePathVisibilityByMode() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra-rf39-driver-");
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path stateDir = Files.createDirectories(tempDir.resolve("state"));
        int grpcPort = freePort();
        int restPort = freePort();

        JettraStoreServer server = null;
        try {
            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-drv-rf39");
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
                    .defaultDatabase("drv_offheap_db")
                    .build();

            try (JettraClient client = JettraClient.connect(cfg)) {
                client.createDatabase("drv_offheap_db", StorageMode.OFF_HEAP);
                client.insertDocument("drv_offheap_db", "Clientes", "C-1", Map.of("nombre", "Lucia"));

                client.createDatabase("drv_disk_db", StorageMode.DISK_MEMORY);
                client.insertDocument("drv_disk_db", "Logs", "L-1", Map.of("evento", "LOGIN"));

                client.createDatabase("drv_ram_db", StorageMode.JVM_RAM);
                client.insertDocument("drv_ram_db", "Temp", "T-1", Map.of("volatil", true));

                JettraAssert.assertTrue(Files.exists(dataDir.resolve("drv_offheap_db").resolve("drv_offheap_db_offheap.pages")));
                JettraAssert.assertTrue(Files.exists(dataDir.resolve("drv_offheap_db").resolve("buckets").resolve("Clientes.json")));
                JettraAssert.assertTrue(Files.readString(dataDir.resolve("drv_offheap_db").resolve("buckets").resolve("Clientes.json"))
                        .contains("Lucia"));

                JettraAssert.assertTrue(Files.exists(dataDir.resolve("drv_disk_db").resolve("jettra_memory").resolve("drv_disk_db.jettra")));
                JettraAssert.assertTrue(Files.exists(dataDir.resolve("drv_disk_db").resolve("jettra_memory").resolve("drv_disk_db.idx")));
                JettraAssert.assertTrue(Files.exists(dataDir.resolve("drv_disk_db").resolve("buckets").resolve("Logs.json")));

                JettraAssert.assertFalse(Files.exists(dataDir.resolve("drv_ram_db")));
                JettraAssert.assertFalse(Files.exists(dataDir.resolve("drv_ram_db_meta.json")));
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

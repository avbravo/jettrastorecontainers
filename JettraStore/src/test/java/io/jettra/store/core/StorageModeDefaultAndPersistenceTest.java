package io.jettra.store.core;

import io.jettra.store.JettraStoreServer;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-38 en JettraStore:
 * - OFF-HEAP como StorageMode predeterminado en StorageMode, JettraStoreConfig y JettraDatabase
 * - Persistencia y lectura de storageMode en <db>_meta.json
 * - Creación y actualización de storageMode vía REST /api/v1/cluster/databases
 */
public class StorageModeDefaultAndPersistenceTest {

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
    @DisplayName("RF-38: OFF_HEAP es el StorageMode predeterminado global y parseStrict valida los 3 modos")
    public void testOffHeapIsDefaultAndParseStrictWorks() {
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, StorageMode.fromString(null));
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, StorageMode.fromString(""));
        JettraAssert.assertEquals(StorageMode.JVM_RAM, StorageMode.parseStrict("1"));
        JettraAssert.assertEquals(StorageMode.JVM_RAM, StorageMode.parseStrict("JVM-RAM"));
        JettraAssert.assertEquals(StorageMode.DISK_MEMORY, StorageMode.parseStrict("2"));
        JettraAssert.assertEquals(StorageMode.DISK_MEMORY, StorageMode.parseStrict("DISK-MEMORY"));
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, StorageMode.parseStrict("3"));
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, StorageMode.parseStrict("OFF-HEAP"));
        JettraAssert.assertNull(StorageMode.parseStrict("UNKNOWN_MODE"));

        Properties p = new Properties();
        JettraStoreConfig cfg = new JettraStoreConfig(p);
        JettraAssert.assertEquals(StorageMode.OFF_HEAP, cfg.getStorageMode());
    }

    @Test
    @DisplayName("RF-38: JettraDatabase y REST /api/v1/cluster/databases persisten y editan storageMode")
    public void testDatabaseAndRestStorageModePersistence() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra-rf38-store-");
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path stateDir = Files.createDirectories(tempDir.resolve("state"));
        int grpcPort = freePort();
        int restPort = freePort();

        JettraStoreServer server = null;
        try {
            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-rf38-1");
            props.setProperty("cluster.node.role", "PRIMARY");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("cluster.node.ip", "127.0.0.1");
            props.setProperty("cluster.node.grpc.port", String.valueOf(grpcPort));
            props.setProperty("cluster.node.rest.port", String.valueOf(restPort));
            props.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
            props.setProperty("jettra.index.storage.path", dataDir.resolve("indexes").toAbsolutePath().toString());
            props.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());

            JettraStoreConfig cfg = new JettraStoreConfig(props);
            server = new JettraStoreServer(cfg);
            server.start();

            JettraDatabase dbDefault = server.getOrCreateDatabase("db_default_rf38");
            JettraAssert.assertEquals(StorageMode.OFF_HEAP, dbDefault.getStorageMode());
            JettraAssert.assertEquals(StorageMode.OFF_HEAP,
                    JettraDatabase.readPersistedStorageMode("db_default_rf38", cfg));

            HttpClient http = HttpClient.newHttpClient();
            String token = null;
            HttpRequest authReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + restPort + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin-jettra\"}"))
                    .build();
            HttpResponse<String> authResp = http.send(authReq, HttpResponse.BodyHandlers.ofString());
            if (authResp.statusCode() == 200 && authResp.body().contains("\"token\"")) {
                int s = authResp.body().indexOf("\"token\":\"") + 9;
                int e = authResp.body().indexOf("\"", s);
                token = authResp.body().substring(s, e);
            }

            HttpRequest.Builder postB = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + restPort + "/api/v1/cluster/databases"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"db_disk_rf38\",\"storageMode\":\"DISK-MEMORY\"}"));
            if (token != null) postB.header("Authorization", "Bearer " + token);
            HttpResponse<String> postResp = http.send(postB.build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, postResp.statusCode());
            JettraAssert.assertTrue(postResp.body().contains("DISK-MEMORY"));

            JettraAssert.assertEquals(StorageMode.DISK_MEMORY,
                    JettraDatabase.readPersistedStorageMode("db_disk_rf38", cfg));

            HttpRequest.Builder putB = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + restPort + "/api/v1/cluster/databases"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"db_disk_rf38\",\"storageMode\":\"JVM-RAM\"}"));
            if (token != null) putB.header("Authorization", "Bearer " + token);
            HttpResponse<String> putResp = http.send(putB.build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, putResp.statusCode());
            JettraAssert.assertTrue(putResp.body().contains("JVM-RAM"));
            JettraAssert.assertEquals(StorageMode.JVM_RAM,
                    JettraDatabase.readPersistedStorageMode("db_disk_rf38", cfg));
        } finally {
            if (server != null) {
                server.stop();
            }
            JettraStoreServer.stopAllActiveServers();
            deleteDir(tempDir);
        }
    }
}

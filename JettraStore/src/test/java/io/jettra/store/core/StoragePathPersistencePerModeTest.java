package io.jettra.store.core;

import io.jettra.store.JettraStoreServer;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;

/**
 * Pruebas JettraTest para RF-39:
 * - OFF-HEAP persiste registros por BD en <jettra.storage.path>/<db>/ (<db>_offheap.pages, <db>_meta.json y buckets/*.json)
 * - DISK-MEMORY persiste y hace flush de registros por BD en <jettra.storage.path>/<db>/ (jettra_memory/*.jettra + *.idx, <db>_meta.json y buckets/*.json)
 * - JVM-RAM opera exclusivamente en memoria JVM sin crear ni persistir registros en jettra.storage.path
 */
public class StoragePathPersistencePerModeTest {

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

    private static Properties isolatedProps(Path storagePath, Path stateDir, StorageMode mode) {
        Properties props = new Properties();
        props.setProperty("cluster.node.id", "node-rf39");
        props.setProperty("cluster.node.role", "PRIMARY");
        props.setProperty("cluster.multinode.active", "off");
        props.setProperty("jettra.storage.path", storagePath.toAbsolutePath().toString());
        props.setProperty("jettra.index.storage.path", storagePath.resolve("indexes").toAbsolutePath().toString());
        props.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", mode.name());
        return props;
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
    @DisplayName("RF-39: OFF-HEAP persiste y expone registros por base de datos dentro de jettra.storage.path")
    public void testOffHeapPersistsDatabaseRecordsInStoragePath() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra-rf39-offheap-");
        Path storagePath = Files.createDirectories(tempRoot.resolve("data"));
        Path stateDir = Files.createDirectories(tempRoot.resolve("state"));
        try {
            JettraStoreConfig cfg = new JettraStoreConfig(isolatedProps(storagePath, stateDir, StorageMode.OFF_HEAP));
            try (JettraDatabase db = new JettraDatabase("ventas_offheap_db", cfg)) {
                db.createBucket("DOCUMENT", "Facturas", JettraBucketSchema.StructureType.NONE, null);
                db.getDocumentEngine("Facturas").insert("FAC-100", Map.of("cliente", "ACME", "total", 250.5));
                db.getKeyValueEngine("SessionKV").put("tok_1", "abc123".getBytes(StandardCharsets.UTF_8));
            }

            Path dbDir = storagePath.resolve("ventas_offheap_db");
            Path offheapPages = dbDir.resolve("ventas_offheap_db_offheap.pages");
            Path dbMeta = dbDir.resolve("ventas_offheap_db_meta.json");
            Path bucketDocs = dbDir.resolve("buckets").resolve("Facturas.json");
            Path bucketKv = dbDir.resolve("buckets").resolve("SessionKV_kv.json");

            JettraAssert.assertTrue(Files.exists(dbDir) && Files.isDirectory(dbDir));
            JettraAssert.assertTrue(Files.exists(offheapPages) && Files.size(offheapPages) > 0,
                    "OFF-HEAP debe generar y sincronizar <db>_offheap.pages dentro de jettra.storage.path/<db>/");
            JettraAssert.assertTrue(Files.exists(dbMeta));
            JettraAssert.assertTrue(Files.readString(dbMeta, StandardCharsets.UTF_8).contains("FAC-100"));
            JettraAssert.assertTrue(Files.exists(bucketDocs));
            JettraAssert.assertTrue(Files.readString(bucketDocs, StandardCharsets.UTF_8).contains("ACME"));
            JettraAssert.assertTrue(Files.exists(bucketKv));
            JettraAssert.assertFalse(Files.exists(dbDir.resolve("jettra_memory")),
                    "OFF-HEAP no debe crear subcarpeta vacía jettra_memory");

            // Verificar recuperación al reabrir la base de datos desde disco
            try (JettraDatabase reopened = new JettraDatabase("ventas_offheap_db", cfg)) {
                var loadedDoc = reopened.getDocumentEngine("Facturas").findById("FAC-100");
                JettraAssert.assertNotNull(loadedDoc);
                JettraAssert.assertEquals("ACME", loadedDoc.get("cliente"));
                byte[] loadedKv = reopened.getKeyValueEngine("SessionKV").get("tok_1");
                JettraAssert.assertEquals("abc123", new String(loadedKv, StandardCharsets.UTF_8));
            }
        } finally {
            deleteDir(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-39: DISK-MEMORY persiste y hace flush de registros por BD en jettra.storage.path")
    public void testDiskMemoryPersistsAndFlushesRecordsInStoragePath() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra-rf39-diskmem-");
        Path storagePath = Files.createDirectories(tempRoot.resolve("data"));
        Path stateDir = Files.createDirectories(tempRoot.resolve("state"));
        try {
            JettraStoreConfig cfg = new JettraStoreConfig(isolatedProps(storagePath, stateDir, StorageMode.DISK_MEMORY));
            try (JettraDatabase db = new JettraDatabase("archivo_disk_db", cfg)) {
                db.createBucket("DOCUMENT", "Ordenes", JettraBucketSchema.StructureType.NONE, null);
                db.getDocumentEngine("Ordenes").insert("ORD-900", Map.of("producto", "Servidor", "cantidad", 4));
                db.getKeyValueEngine("ConfigKV").put("modo", "lsm".getBytes(StandardCharsets.UTF_8));
            }

            Path dbDir = storagePath.resolve("archivo_disk_db");
            Path memDir = dbDir.resolve("jettra_memory");
            Path jettraData = memDir.resolve("archivo_disk_db.jettra");
            Path jettraIdx = memDir.resolve("archivo_disk_db.idx");
            Path bucketDocs = dbDir.resolve("buckets").resolve("Ordenes.json");
            Path dbMeta = dbDir.resolve("archivo_disk_db_meta.json");

            JettraAssert.assertTrue(Files.exists(jettraData) && Files.size(jettraData) > 8,
                    "DISK-MEMORY debe persistir datos en jettra_memory/<db>.jettra");
            JettraAssert.assertTrue(Files.exists(jettraIdx) && Files.size(jettraIdx) > 0,
                    "DISK-MEMORY debe hacer flush del índice jettra_memory/<db>.idx");
            JettraAssert.assertTrue(Files.exists(bucketDocs));
            JettraAssert.assertTrue(Files.readString(bucketDocs, StandardCharsets.UTF_8).contains("ORD-900"));
            JettraAssert.assertTrue(Files.readString(dbMeta, StandardCharsets.UTF_8).contains("DISK-MEMORY"));

            try (JettraDatabase reopened = new JettraDatabase("archivo_disk_db", cfg)) {
                var loadedDoc = reopened.getDocumentEngine("Ordenes").findById("ORD-900");
                JettraAssert.assertNotNull(loadedDoc);
                JettraAssert.assertEquals("Servidor", loadedDoc.get("producto"));
                byte[] loadedKv = reopened.getKeyValueEngine("ConfigKV").get("modo");
                JettraAssert.assertEquals("lsm", new String(loadedKv, StandardCharsets.UTF_8));
            }
        } finally {
            deleteDir(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-39: JVM-RAM opera exclusivamente en memoria sin persistir archivos ni registros en jettra.storage.path")
    public void testJvmRamDoesNotPersistRecordsToStoragePath() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra-rf39-jvmram-");
        Path storagePath = Files.createDirectories(tempRoot.resolve("data"));
        Path stateDir = Files.createDirectories(tempRoot.resolve("state"));
        try {
            JettraStoreConfig cfg = new JettraStoreConfig(isolatedProps(storagePath, stateDir, StorageMode.JVM_RAM));
            try (JettraDatabase db = new JettraDatabase("volatile_ram_db", cfg)) {
                db.createBucket("DOCUMENT", "CacheDocs", JettraBucketSchema.StructureType.NONE, null);
                db.getDocumentEngine("CacheDocs").insert("RAM-1", Map.of("ephemeral", true));
                db.getKeyValueEngine("CacheKV").put("k1", "v1".getBytes(StandardCharsets.UTF_8));
                db.flushMemTable();
                db.saveToDisk();

                JettraAssert.assertNotNull(db.getDocumentEngine("CacheDocs").findById("RAM-1"));
                JettraAssert.assertEquals("v1", new String(db.getKeyValueEngine("CacheKV").get("k1"), StandardCharsets.UTF_8));
            }

            JettraAssert.assertFalse(Files.exists(storagePath.resolve("volatile_ram_db")),
                    "JVM-RAM no debe crear carpeta de base de datos en jettra.storage.path");
            JettraAssert.assertFalse(Files.exists(storagePath.resolve("volatile_ram_db_meta.json")),
                    "JVM-RAM no debe escribir _meta.json en disco");
            JettraAssert.assertFalse(Files.exists(storagePath.resolve("volatile_ram_db_sstable.jettra")),
                    "JVM-RAM no debe escribir _sstable.jettra en disco");
            JettraAssert.assertFalse(Files.exists(storagePath.resolve("volatile_ram_db_buckets.json")),
                    "JVM-RAM no debe escribir _buckets.json en disco");
        } finally {
            deleteDir(tempRoot);
        }
    }
}

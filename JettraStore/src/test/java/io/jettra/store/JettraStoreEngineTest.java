package io.jettra.store;

import io.jettra.store.backup.BackupManager;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.DynamicRingEngine;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.models.JettraRef;
import io.jettra.store.engine.panama.NativeMemTable;
import io.jettra.store.security.JettraSecurityManager;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

public class JettraStoreEngineTest extends JettraStoreBaseTest {

    @BeforeAll
    @AfterAll
    public static void cleanUpNode1AndTestDatabases() {
        JettraTestCleanup.cleanUpNode1AndTestDatabases();
    }


    @Test
    @DisplayName("Debe autenticar superusuario por defecto y validar inmutabilidad de roles")
    public void testSuperuserSecurity() {
        JettraSecurityManager sec = new JettraSecurityManager();
        String token = sec.authenticate("admin", "admin-jettra");
        assertNotNull(token);
        assertTrue(token.startsWith("JettraJWT."));

        JettraSecurityManager.Claims claims = sec.validateToken(token);
        assertEquals("admin", claims.username());
        assertEquals("SUPER_ADMIN", claims.role());

        // Crear usuario secundario
        sec.createUser(token, "operator", "Pass123", "DB_ADMIN");
        String operatorToken = sec.authenticate("operator", "Pass123");

        // Intentar alterar el rol de admin desde un usuario secundario debe fallar
        assertThrows(SecurityException.class, () -> {
            sec.alterUserRole(operatorToken, "admin", "READ_ONLY");
        });
    }

    @Test
    @DisplayName("Debe gestionar memoria nativa fuera del Heap con Project Panama FFM")
    public void testPanamaNativeMemTable() throws IOException {
        try (NativeMemTable mem = new NativeMemTable(1024 * 1024)) { // 1 MB
            byte[] key = "item_1".getBytes();
            byte[] payload = "{\"price\": 49.99}".getBytes();
            boolean appended = mem.append((byte) 1, key, payload);
            assertTrue(appended);
            assertTrue(mem.getUsedBytes() > 0);

            Path tempJettra = Files.createTempFile("test_panama", ".jettra");
            mem.flushToJettraFile(tempJettra);
            assertTrue(Files.size(tempJettra) > 0);
            Files.deleteIfExists(tempJettra);
        }
    }

    @Test
    @DisplayName("Debe activar la transición al anillo distribuido al alcanzar el umbral de RAM (85%)")
    public void testDynamicRingTransition() {
        DynamicRingEngine ring = new DynamicRingEngine("node-01", 0.85, 0.45);
        ClusterNode node2 = new ClusterNode("node-02", "127.0.0.1", 9092, ClusterNode.Role.SECONDARY);
        ring.registerPeer(node2);

        try (NativeMemTable mem = new NativeMemTable(1024 * 1024)) {
            mem.append((byte) 1, "k1".getBytes(), "v1".getBytes());

            // 70% de RAM -> No activa anillo
            ring.evaluateMemorySaturation(0.70, mem);
            assertFalse(ring.isRingActive());

            // 88% de RAM -> Activa anillo y descarga carga a nodo 2
            ring.evaluateMemorySaturation(0.88, mem);
            assertTrue(ring.isRingActive());
            assertTrue(ring.getCurrentMemoryUsage() <= 0.45);
            assertTrue(node2.getReceivedRingSegments() > 0);
        }
    }

    @Test
    @DisplayName("Debe soportar referencias cruzadas con Lazy Loading")
    public void testCrossEngineLazyLoading() {
        JettraRef<String> lazyRef = new JettraRef<>(
            "vector", 
            "emb_01", 
            JettraRef.FetchMode.LAZY, 
            () -> "[0.15, -0.42, 0.88]"
        );

        assertFalse(lazyRef.isResolved());
        assertEquals("vector::emb_01", lazyRef.getDescriptor());

        // Resolución explícita bajo demanda
        String resolved = lazyRef.resolve();
        assertEquals("[0.15, -0.42, 0.88]", resolved);
        assertTrue(lazyRef.isResolved());
    }

    @Test
    @DisplayName("Debe ejecutar Hot Backup y Restauración de base de datos .jettra")
    public void testBackupAndRestore() throws IOException {
        JettraStoreConfig cfg = JettraStoreConfig.load();
        try (JettraDatabase db = new JettraDatabase("test_backup_db", cfg)) {
            db.getDocumentEngine("users").insert("u1", Map.of("name", "Alice"));

            Path backupPath = Files.createTempFile("snapshot", ".jettra_bak");
            var meta = BackupManager.backupDatabase(db, backupPath);
            assertNotNull(meta);
            assertEquals("test_backup_db", meta.databaseName());

            boolean restored = BackupManager.restoreDatabase(backupPath, db);
            assertTrue(restored);
            Files.deleteIfExists(backupPath);
        } finally {
            cleanUpNode1AndTestDatabases();
        }
    }

    @Test
    @DisplayName("Debe crear el directorio configurado en database.properties y almacenar datos")
    public void testConfiguredStoragePathCreationAndStore() throws IOException {
        JettraStoreConfig cfg = JettraStoreConfig.load();
        assertTrue(cfg.getStoragePath().contains("jettra") && Files.isDirectory(Path.of(cfg.getStoragePath())));
        assertTrue(Files.exists(Path.of(cfg.getStoragePath())));
        assertTrue(Files.isDirectory(Path.of(cfg.getStoragePath())));
        assertTrue(Files.isWritable(Path.of(cfg.getStoragePath())));

        try (JettraDatabase db = new JettraDatabase("verify_storage_db", cfg)) {
            db.getDocumentEngine("items").insert("i1", Map.of("title", "Product"));
            db.flushMemTable();

            Path expectedFile = Path.of(cfg.getStoragePath(), "verify_storage_db_sstable" + cfg.getFileExtension());
            assertTrue(Files.exists(expectedFile));
            assertTrue(Files.size(expectedFile) > 0);
            Files.deleteIfExists(expectedFile);
        } finally {
            cleanUpNode1AndTestDatabases();
        }
    }

    @Test
    @DisplayName("Debe desactivar la distribución de datos cuando cluster.multinode.active está en off")
    public void testDynamicRingStandaloneModeWhenMultinodeOff() {
        // En modo multinodo off, DynamicRingEngine no debe realizar descargas ni activar transiciones al anillo
        DynamicRingEngine standaloneRing = new DynamicRingEngine("node-01", 0.85, 0.45, false);
        assertFalse(standaloneRing.isMultinodeActive());
        assertEquals("off", standaloneRing.getMultinodeActive());

        ClusterNode node2 = new ClusterNode("node-02", "127.0.0.1", 9092, ClusterNode.Role.SECONDARY);
        standaloneRing.registerPeer(node2);

        try (NativeMemTable mem = new NativeMemTable(1024 * 1024)) {
            mem.append((byte) 1, "k_local".getBytes(), "v_local".getBytes());

            // 95% de saturación -> Con multinode = false, NO se activa transición distribuida
            standaloneRing.evaluateMemorySaturation(0.95, mem);
            assertFalse(standaloneRing.isRingActive());
            assertEquals(0, node2.getReceivedRingSegments());
            assertEquals(0, node2.getReceivedOffloadedBytes());
        }
    }

    @Test
    @DisplayName("Debe eliminar las bases de datos creadas en /jettra/node-1 y eliminar la subcarpeta node-1 al terminar")
    public void testNode1DatabaseCreationAndCleanupOnFinish() throws IOException {
        Path node1Dir = Path.of("/jettra/node-1");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("jettra.node.id", "node-1");
        props.setProperty("jettra.storage.path", "/jettra/node-1");
        JettraStoreConfig node1Config = new JettraStoreConfig(props, new java.util.Properties());

        try (JettraDatabase db = new JettraDatabase("test_node1_db", node1Config)) {
            db.getDocumentEngine("metrics").insert("m1", Map.of("cpu", 45.2, "ram", 78.1));
            db.flushMemTable();
            db.saveToDisk();

            assertTrue(Files.exists(node1Dir));
            Path dbMeta = node1Dir.resolve("test_node1_db_meta.json");
            assertTrue(Files.exists(dbMeta) || Files.exists(Path.of(node1Config.getStoragePath(), "test_node1_db_meta.json")));
        } finally {
            cleanUpNode1AndTestDatabases();
            assertFalse(Files.exists(node1Dir));
        }
    }
}


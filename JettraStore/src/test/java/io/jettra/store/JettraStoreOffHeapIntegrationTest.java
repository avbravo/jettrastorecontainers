package io.jettra.store;

import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.core.StorageMode;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.engine.models.KeyValueEngine;
import io.jettra.store.offheap.JettraTransactionContext;
import io.jettra.store.offheap.OffHeapPageBuffer;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static io.jettra.test.core.JettraAssert.*;

public class JettraStoreOffHeapIntegrationTest extends JettraStoreBaseTest {

    @Test
    @DisplayName("Debe operar JettraDatabase en StorageMode.OFF_HEAP con KeyValueEngine y DocumentEngine off-heap")
    public void testDatabaseOffHeapMode() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_offheap_test_");
        try {
            Properties props = new Properties();
            props.setProperty("jettra.storage.path", tempDir.toAbsolutePath().toString());
            props.setProperty("jettra.storage.mode", "OFF-HEAP");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("jettrapolice.active", "false");

            JettraStoreConfig config = new JettraStoreConfig(props);
            assertEquals(StorageMode.OFF_HEAP, config.getStorageMode());

            try (JettraDatabase db = new JettraDatabase("offheap_db", config)) {
                assertEquals(StorageMode.OFF_HEAP, db.getStorageMode());
                assertTrue(db.getStorageMode().isOffHeap());

                // 1. Probar KeyValueEngine con almacenamiento nativo Slotted Off-Heap
                KeyValueEngine kv = db.getKeyValueEngine("cache_session");
                byte[] val = "{\"user\":\"admin\",\"active\":true}".getBytes(StandardCharsets.UTF_8);
                kv.put("session_token_1001", val);

                assertEquals(1, kv.size());
                assertTrue(kv.containsKey("session_token_1001"));

                byte[] retrieved = kv.get("session_token_1001");
                assertNotNull(retrieved);
                assertArrayEquals(val, retrieved);

                // Lectura Cero-Copia directa
                MemorySegment slice = kv.getSlice("session_token_1001");
                assertNotNull(slice);
                assertEquals(val.length, slice.byteSize());

                // 2. Probar DocumentEngine con persistencia directa a páginas off-heap
                DocumentEngine docEngine = db.getDocumentEngine("facturas");
                Map<String, Object> invoice = Map.of(
                        "numero", "FAC-2026-001",
                        "total", 3500.50,
                        "estado", "PAGADA"
                );
                docEngine.insert("FAC-001", invoice);

                Map<String, Object> found = docEngine.findById("FAC-001");
                assertNotNull(found);
                assertEquals("FAC-2026-001", found.get("numero"));

                // Verificar que el registro reside en el almacén off-heap
                byte[] offHeapRaw = db.getRecordOffHeap("facturas:FAC-001");
                assertNotNull(offHeapRaw);
                assertTrue(new String(offHeapRaw, StandardCharsets.UTF_8).contains("FAC-2026-001"));

                // 3. Probar contexto transaccional RAII determinista
                try (JettraTransactionContext tx = db.newTransactionContext(9999L)) {
                    OffHeapPageBuffer page = tx.createPage(1L);
                    int slot = page.insertRecord("Tx Record".getBytes(StandardCharsets.UTF_8));
                    assertEquals(0, slot);
                    assertNotNull(page.getRecordSlice(slot));
                } // liberado inmediatamente sin pausas de GC
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            if (Files.exists(root)) {
                try (var s = Files.walk(root)) {
                    s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }
}


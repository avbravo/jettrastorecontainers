package io.jettra.store.offheap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapPageManagerTest {

    @Test
    @DisplayName("Debe crear páginas off-heap, escribir registros, persistir a disco y recargar")
    public void testAllocateFlushAndReload() throws IOException {
        Path tempFile = Files.createTempFile("jettra_offheap_page_", ".bin");
        tempFile.toFile().deleteOnExit();

        byte[] payload = "Persistent Invoice Record #5001".getBytes(StandardCharsets.UTF_8);

        // Fase 1: Escribir página y volcar a disco
        try (OffHeapPageManager manager = new OffHeapPageManager(tempFile, 64 * 1024)) {
            OffHeapPageBuffer page1 = manager.allocateNewPage();
            assertEquals(1L, page1.getPageId());

            int slot = page1.insertRecord(payload);
            assertEquals(0, slot);

            manager.flushAll();
            assertTrue(Files.size(tempFile) >= 64 * 1024);
        }

        // Fase 2: Reabrir el archivo y leer la página persistida
        try (OffHeapPageManager manager = new OffHeapPageManager(tempFile, 64 * 1024)) {
            OffHeapPageBuffer reloadedPage = manager.getPage(1L);
            assertNotNull(reloadedPage);
            assertEquals(1L, reloadedPage.getPageId());
            assertEquals(1, reloadedPage.getSlotCount());

            byte[] retrieved = reloadedPage.getRecordBytes(0);
            assertNotNull(retrieved);
            assertArrayEquals(payload, retrieved);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}


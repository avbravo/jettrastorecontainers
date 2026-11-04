package io.jettra.store.offheap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class JettraTransactionContextTest {

    @Test
    @DisplayName("Debe gestionar ciclo de vida determinista RAII con try-with-resources sin impacto en el GC")
    public void testTransactionContextRaiiLifecycle() {
        OffHeapPageBuffer pageInTx;
        MemorySegment workspaceInTx;

        try (JettraTransactionContext tx = new JettraTransactionContext(777L)) {
            assertEquals(777L, tx.getTransactionId());
            assertFalse(tx.isClosed());

            // Crear página transaccional y memoria de trabajo
            pageInTx = tx.createPage(10L);
            workspaceInTx = tx.allocateWorkspace(1024);

            assertEquals(10L, pageInTx.getPageId());
            assertEquals(777L, pageInTx.getTransactionId());
            assertEquals(1, tx.getPages().size());

            int slot = pageInTx.insertRecord("Data in transaction".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, slot);
            assertNotNull(pageInTx.getRecordSlice(0));
        }

        // Al salir del bloque try-with-resources, la transacción está cerrada
        // y los segmentos nativos han sido liberados de forma determinista
        assertThrows(IllegalStateException.class, () -> pageInTx.insertRecord("Fails".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Debe lanzar excepción si se intenta operar sobre una transacción ya cerrada")
    public void testClosedTransactionGuards() {
        JettraTransactionContext tx = new JettraTransactionContext(888L);
        tx.close();
        assertTrue(tx.isClosed());

        assertThrows(IllegalStateException.class, () -> tx.createPage(1L));
        assertThrows(IllegalStateException.class, () -> tx.allocateWorkspace(512));
    }
}


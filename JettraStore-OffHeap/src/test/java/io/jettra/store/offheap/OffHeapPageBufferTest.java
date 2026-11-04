package io.jettra.store.offheap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapPageBufferTest {

    @Test
    @DisplayName("Debe inicializar la cabecera Slotted-Page con Magic de 8 bytes y espacio libre correcto")
    public void testPageHeaderInitialization() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapPageBuffer page = new OffHeapPageBuffer(arena, 42L, 64 * 1024);

            assertEquals(OffHeapPageBuffer.PAGE_MAGIC, page.getMagic());
            assertEquals(42L, page.getPageId());
            assertEquals(0L, page.getTransactionId());
            assertEquals(0, page.getSlotCount());
            assertEquals(64 * 1024, page.getFreeSpacePointer());
            assertTrue(page.freeBytes() > 60000);
        }
    }

    @Test
    @DisplayName("Debe insertar registros binarios y recuperarlos con vista Slice cero-copia")
    public void testInsertAndRetrieveRecordSlice() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapPageBuffer page = new OffHeapPageBuffer(arena, 1L, 64 * 1024);

            byte[] record1 = "CUSTOMER#101:Alice:Admin".getBytes(StandardCharsets.UTF_8);
            byte[] record2 = "CUSTOMER#102:Bob:User".getBytes(StandardCharsets.UTF_8);

            int slot0 = page.insertRecord(record1);
            int slot1 = page.insertRecord(record2);

            assertEquals(0, slot0);
            assertEquals(1, slot1);
            assertEquals(2, page.getSlotCount());

            // Recuperación con slice (cero-copia nativa)
            MemorySegment slice0 = page.getRecordSlice(0);
            assertNotNull(slice0);
            assertArrayEquals(record1, slice0.toArray(ValueLayout.JAVA_BYTE));

            MemorySegment slice1 = page.getRecordSlice(1);
            assertNotNull(slice1);
            assertArrayEquals(record2, slice1.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    @DisplayName("Debe realizar lecturas tipadas selectivas (Zero-Copy Deserialization) con VarHandles")
    public void testTypedSelectiveDeserialization() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapPageBuffer page = new OffHeapPageBuffer(arena, 100L, 64 * 1024);

            // Estructura binaria de factura (32 bytes alineados a 8B):
            // [id (int64, 8B @0) | items (int32, 4B @8) | pad (4B) | total (float64, 8B @16) | code (8B UTF8 @24)]
            MemorySegment record = arena.allocate(32, 8);
            record.set(ValueLayout.JAVA_LONG, 0, 999999L);
            record.set(ValueLayout.JAVA_INT, 8, 15);
            record.set(ValueLayout.JAVA_DOUBLE, 16, 1250.75);
            byte[] codeBytes = "INV-2026".getBytes(StandardCharsets.UTF_8);
            MemorySegment.copy(MemorySegment.ofArray(codeBytes), 0, record, 24, 8);

            int slot = page.insertRecord(record, 32);
            assertEquals(0, slot);

            // Lectura selectiva directa sin deserializar todo el objeto en el Heap
            long id = page.readLong(slot, 0);
            int items = page.readInt(slot, 8);
            double total = page.readDouble(slot, 16);
            String code = page.readUtf8String(slot, 24, 8);

            assertEquals(999999L, id);
            assertEquals(15, items);
            assertEquals(1250.75, total, 0.001);
            assertEquals("INV-2026", code);
        }
    }

    @Test
    @DisplayName("Debe manejar eliminación lógica (Tombstone) y rechazar slots inválidos")
    public void testSlotDeletionAndBounds() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapPageBuffer page = new OffHeapPageBuffer(arena, 2L, 64 * 1024);

            byte[] data = "Temporal".getBytes(StandardCharsets.UTF_8);
            int slot = page.insertRecord(data);

            assertNotNull(page.getRecordSlice(slot));
            assertTrue(page.markSlotDeleted(slot));

            // Tras eliminar, el slice es nulo (Tombstone)
            assertNull(page.getRecordSlice(slot));
            assertNull(page.getRecordBytes(slot));

            assertThrows(IndexOutOfBoundsException.class, () -> page.getRecordSlice(99));
        }
    }
}

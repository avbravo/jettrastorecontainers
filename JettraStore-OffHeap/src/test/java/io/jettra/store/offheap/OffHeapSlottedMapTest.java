package io.jettra.store.offheap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapSlottedMapTest {

    @Test
    @DisplayName("Debe ejecutar operaciones CRUD clave-valor directamente en páginas Slotted Off-Heap")
    public void testBasicCrud() {
        try (OffHeapSlottedMap map = new OffHeapSlottedMap(64 * 1024)) {
            map.put("doc:1", "{\"title\":\"Jettra Off-Heap\",\"author\":\"Team\"}");
            map.put("doc:2", "{\"title\":\"Lilliput 8-byte\",\"status\":\"Active\"}");

            assertEquals(2, map.size());
            assertTrue(map.containsKey("doc:1"));
            assertTrue(map.containsKey("doc:2"));
            assertFalse(map.containsKey("doc:99"));

            String val1 = map.getString("doc:1");
            assertNotNull(val1);
            assertTrue(val1.contains("Jettra Off-Heap"));

            // Actualización de registro
            map.put("doc:1", "{\"title\":\"Jettra Off-Heap v2\"}");
            assertEquals(2, map.size());
            assertTrue(map.getString("doc:1").contains("v2"));

            // Eliminación
            assertTrue(map.remove("doc:2"));
            assertEquals(1, map.size());
            assertNull(map.get("doc:2"));
        }
    }

    @Test
    @DisplayName("Debe expandir páginas dinámicamente al saturar el tamaño de página")
    public void testPageExpansionUnderLoad() {
        // Página pequeña de 4 KB para forzar asignación de múltiples páginas
        try (OffHeapSlottedMap map = new OffHeapSlottedMap(4 * 1024)) {
            byte[] largePayload = new byte[512];
            Arrays.fill(largePayload, (byte) 0x2A);

            // 20 registros * 512 bytes = ~10 KB -> requiere al menos 3 páginas de 4 KB
            for (int i = 0; i < 20; i++) {
                map.put("key_" + i, largePayload);
            }

            assertEquals(20, map.size());
            assertTrue(map.pageCount() >= 3);

            for (int i = 0; i < 20; i++) {
                MemorySegment slice = map.getSlice("key_" + i);
                assertNotNull(slice);
                assertEquals(512, slice.byteSize());
            }
        }
    }
}


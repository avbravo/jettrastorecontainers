package io.jettra.store.offheap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mapa Clave-Valor de alto rendimiento cuyos registros residen en páginas ranuradas Off-Heap.
 * Reduce drásticamente la huella en el Heap de la JVM al mantener solo descriptores ligeros en memoria.
 */
public final class OffHeapSlottedMap implements AutoCloseable {

    public record RecordLocation(long pageId, int slotIndex) {}

    private final Arena arena;
    private final int pageSize;
    private final List<OffHeapPageBuffer> pages = new ArrayList<>();
    private final Map<String, RecordLocation> index = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger(0);

    public OffHeapSlottedMap() {
        this(OffHeapPageBuffer.DEFAULT_PAGE_SIZE);
    }

    public OffHeapSlottedMap(int pageSize) {
        this.pageSize = pageSize > 0 ? pageSize : OffHeapPageBuffer.DEFAULT_PAGE_SIZE;
        this.arena = Arena.ofShared();
        // Inicializar primera página
        allocateNewPage();
    }

    private synchronized OffHeapPageBuffer allocateNewPage() {
        long pageId = pages.size() + 1;
        OffHeapPageBuffer page = new OffHeapPageBuffer(arena, pageId, pageSize);
        pages.add(page);
        return page;
    }

    /**
     * Inserta o actualiza un valor binario asociado a la clave.
     */
    public synchronized void put(String key, byte[] value) {
        Objects.requireNonNull(key, "key no puede ser nula");
        Objects.requireNonNull(value, "value no puede ser nulo");

        // Buscar última página activa o crear nueva si no hay espacio
        OffHeapPageBuffer currentPage = pages.get(pages.size() - 1);
        int slot = currentPage.insertRecord(value);
        if (slot == -1) {
            currentPage = allocateNewPage();
            slot = currentPage.insertRecord(value);
            if (slot == -1) {
                throw new IllegalStateException("Registro demasiado grande para el tamaño de página: " + value.length + " bytes");
            }
        }

        RecordLocation prev = index.put(key, new RecordLocation(currentPage.getPageId(), slot));
        if (prev == null) {
            size.incrementAndGet();
        } else {
            // Marcar anterior como eliminado en su página
            OffHeapPageBuffer oldPage = pages.get((int) prev.pageId() - 1);
            oldPage.markSlotDeleted(prev.slotIndex());
        }
    }

    /**
     * Inserta una cadena UTF-8 asociada a la clave.
     */
    public void put(String key, String value) {
        Objects.requireNonNull(value, "value no puede ser nulo");
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Obtiene una vista directa (Slice cero-copia) del valor almacenado fuera del heap.
     */
    public MemorySegment getSlice(String key) {
        RecordLocation loc = index.get(key);
        if (loc == null) {
            return null;
        }
        OffHeapPageBuffer page = pages.get((int) loc.pageId() - 1);
        return page.getRecordSlice(loc.slotIndex());
    }

    /**
     * Obtiene el valor deserializado en un arreglo de bytes.
     */
    public byte[] get(String key) {
        MemorySegment slice = getSlice(key);
        return slice != null ? slice.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE) : null;
    }

    /**
     * Obtiene el valor como cadena UTF-8.
     */
    public String getString(String key) {
        byte[] bytes = get(key);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * Comprueba si una clave existe en el mapa.
     */
    public boolean containsKey(String key) {
        return index.containsKey(key);
    }

    /**
     * Elimina lógicamente la entrada asociada a la clave.
     */
    public synchronized boolean remove(String key) {
        RecordLocation loc = index.remove(key);
        if (loc != null) {
            size.decrementAndGet();
            OffHeapPageBuffer page = pages.get((int) loc.pageId() - 1);
            return page.markSlotDeleted(loc.slotIndex());
        }
        return false;
    }

    public int size() {
        return size.get();
    }

    public int pageCount() {
        return pages.size();
    }

    @Override
    public void close() {
        index.clear();
        pages.clear();
        arena.close();
    }
}


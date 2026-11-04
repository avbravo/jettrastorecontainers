package io.jettra.store.offheap;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asignador central de memoria nativa off-heap para JettraStore mediante Java 25 Foreign Function & Memory (FFM) API.
 * Administra la memoria fuera del heap de la JVM para evitar pausas del Garbage Collector.
 */
public final class NativeMemoryAllocator {

    private static final AtomicLong TOTAL_ALLOCATED_BYTES = new AtomicLong(0);
    private static final AtomicLong ACTIVE_ARENA_COUNT = new AtomicLong(0);

    /**
     * Arena compartida global para estructuras de ciclo de vida permanente o prolongado
     * (por ejemplo, el Buffer Pool global y tablas de páginas del motor).
     */
    private static final Arena GLOBAL_SHARED_ARENA = Arena.ofShared();

    private NativeMemoryAllocator() {
    }

    /**
     * Asigna un segmento de memoria en la arena global compartida con alineación a 8 bytes.
     */
    public static MemorySegment allocateGlobal(long bytes) {
        return allocateGlobal(bytes, 8);
    }

    /**
     * Asigna un segmento de memoria en la arena global compartida con alineación específica.
     */
    public static MemorySegment allocateGlobal(long bytes, long byteAlignment) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("El tamaño en bytes debe ser mayor que 0: " + bytes);
        }
        MemorySegment segment = GLOBAL_SHARED_ARENA.allocate(bytes, byteAlignment);
        TOTAL_ALLOCATED_BYTES.addAndGet(bytes);
        return segment;
    }

    /**
     * Crea una Arena confinada a un solo hilo (Thread-Confined).
     * Ideal para transacciones aisladas, consultas puntuales y buffers de trabajo temporal (RAII determinista).
     */
    public static Arena createConfinedArena() {
        ACTIVE_ARENA_COUNT.incrementAndGet();
        return Arena.ofConfined();
    }

    /**
     * Crea una Arena compartida con control manual de cierre para transacciones o subprocesos concurrentes.
     */
    public static Arena createSharedArena() {
        ACTIVE_ARENA_COUNT.incrementAndGet();
        return Arena.ofShared();
    }

    /**
     * Crea una Arena automática gestionada por el Cleaner nativo de Java.
     */
    public static Arena createAutoArena() {
        ACTIVE_ARENA_COUNT.incrementAndGet();
        return Arena.ofAuto();
    }

    /**
     * Devuelve el total acumulado de bytes asignados globalmente.
     */
    public static long totalAllocatedBytes() {
        return TOTAL_ALLOCATED_BYTES.get();
    }

    /**
     * Devuelve el número de arenas activas creadas.
     */
    public static long activeArenaCount() {
        return ACTIVE_ARENA_COUNT.get();
    }
}


package io.jettra.store.offheap;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Contexto de transacción transaccional con gestión determinista de ciclo de vida (RAII).
 * Utiliza una {@link Arena} nativa confinada o compartida para alojar buffers, páginas y memoria de trabajo.
 * Al cerrar el contexto con {@code try-with-resources}, se libera instantáneamente toda la memoria nativa
 * sin generar presión ni pausas en el Garbage Collector de la JVM.
 */
public final class JettraTransactionContext implements AutoCloseable {

    private final long transactionId;
    private final Arena arena;
    private final List<OffHeapPageBuffer> pages;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Crea un contexto transaccional con una Arena confinada (Thread-Confined) para máximo rendimiento.
     */
    public JettraTransactionContext(long transactionId) {
        this(transactionId, Arena.ofConfined());
    }

    /**
     * Crea un contexto transaccional con una Arena personalizada (por ejemplo, compartida entre hilos).
     */
    public JettraTransactionContext(long transactionId, Arena arena) {
        this.transactionId = transactionId;
        this.arena = arena != null ? arena : Arena.ofConfined();
        this.pages = new ArrayList<>();
    }

    public long getTransactionId() {
        return transactionId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Crea y asocia una nueva página ranurada off-heap dentro de este contexto de transacción.
     */
    public OffHeapPageBuffer createPage(long pageId, int pageSize) {
        checkNotClosed();
        OffHeapPageBuffer page = new OffHeapPageBuffer(arena, pageId, pageSize);
        page.setTransactionId(transactionId);
        synchronized (pages) {
            pages.add(page);
        }
        return page;
    }

    /**
     * Crea una página con el tamaño predeterminado de 64 KB.
     */
    public OffHeapPageBuffer createPage(long pageId) {
        return createPage(pageId, OffHeapPageBuffer.DEFAULT_PAGE_SIZE);
    }

    /**
     * Reserva un segmento de memoria de trabajo transaccional con alineación a 8 bytes.
     */
    public MemorySegment allocateWorkspace(long bytes) {
        checkNotClosed();
        return arena.allocate(bytes, 8);
    }

    /**
     * Devuelve una lista de solo lectura con las páginas activas en esta transacción.
     */
    public List<OffHeapPageBuffer> getPages() {
        synchronized (pages) {
            return Collections.unmodifiableList(new ArrayList<>(pages));
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("El contexto de transacción Tx-" + transactionId + " ya ha sido cerrado.");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (pages) {
                pages.clear();
            }
            // LIBERACIÓN DETERMINISTA RAII:
            // Cierra la Arena nativa, invalidando y devolviendo al SO todos los segmentos asignados
            // en O(1) tiempo sin intervención del Garbage Collector.
            arena.close();
        }
    }
}


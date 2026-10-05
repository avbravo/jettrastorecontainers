package io.jettra.store.core;

import io.jettra.store.police.JettraPoliceNotification;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Protocolo de flujo continuo (Streaming por Chunks) con metadatos de notificación de Sentinel.
 * Particiona consultas masivas en lotes seguros (ej. 100 registros por lote), vaciando referencias
 * entre lote y lote para facilitar la recolección de basura (Garbage Collection) y proteger
 * el Heap tanto del servidor como del cliente.
 *
 * @param <T> Tipo de registro transmitido en el stream
 */
public final class StreamResponse<T> implements AutoCloseable, Iterable<List<T>> {

    private final JettraPoliceNotification notification;
    private final Iterator<List<T>> chunkIterator;
    private final int safeBatchSize;
    private final long totalEstimated;
    private final Runnable onClose;
    private boolean closed = false;

    public StreamResponse(
            JettraPoliceNotification notification,
            int safeBatchSize,
            long totalEstimated,
            Iterator<List<T>> chunkIterator,
            Runnable onClose) {
        this.notification = notification;
        this.safeBatchSize = Math.max(1, safeBatchSize);
        this.totalEstimated = totalEstimated;
        this.chunkIterator = (chunkIterator != null) ? chunkIterator : Collections.emptyIterator();
        this.onClose = onClose;
    }

    public static <T> StreamResponse<T> empty() {
        return new StreamResponse<>(null, 100, 0, Collections.emptyIterator(), null);
    }

    public static <T> StreamResponse<T> ofSingleChunk(List<T> items) {
        return new StreamResponse<>(null, Math.max(1, items.size()), items.size(),
            Collections.singletonList(items).iterator(), null);
    }

    public JettraPoliceNotification getNotification() {
        return notification;
    }

    public boolean isSentinelActivated() {
        return notification != null && notification.forcedLazyPagination();
    }

    public int getSafeBatchSize() {
        return safeBatchSize;
    }

    public long getTotalEstimated() {
        return totalEstimated;
    }

    @Override
    public Iterator<List<T>> iterator() {
        return chunkIterator;
    }

    /**
     * Consume los chunks de datos de forma iterativa y secuencial, vaciando referencias
     * del lote anterior de inmediato para permitir que el Garbage Collector recoja la memoria.
     */
    public void forEachChunk(Consumer<List<T>> chunkConsumer) {
        try {
            while (chunkIterator.hasNext()) {
                List<T> chunk = chunkIterator.next();
                if (chunk != null && !chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
                // Liberar referencia local para ayudar al Garbage Collector
                chunk = null;
            }
        } finally {
            close();
        }
    }

    /**
     * Itera elemento a elemento de forma continua sin cargar todos los lotes de golpe en memoria.
     */
    public void forEachRecord(Consumer<T> recordConsumer) {
        forEachChunk(chunk -> {
            for (T item : chunk) {
                if (item != null) {
                    recordConsumer.accept(item);
                }
            }
        });
    }

    /**
     * Consolida todos los registros acumulados en una lista en memoria del cliente.
     */
    public List<T> collectAll() {
        List<T> all = new ArrayList<>();
        forEachChunk(all::addAll);
        return all;
    }

    /**
     * Retorna un Stream de Java continuo que consume perezosamente los lotes seguros.
     */
    public Stream<T> stream() {
        Spliterator<T> spliterator = new Spliterators.AbstractSpliterator<T>(
                totalEstimated > 0 ? totalEstimated : Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {
            private Iterator<T> currentChunkIt = Collections.emptyIterator();

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                while (!currentChunkIt.hasNext()) {
                    if (!chunkIterator.hasNext()) {
                        close();
                        return false;
                    }
                    List<T> nextChunk = chunkIterator.next();
                    if (nextChunk != null && !nextChunk.isEmpty()) {
                        currentChunkIt = nextChunk.iterator();
                    }
                }
                action.accept(currentChunkIt.next());
                return true;
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(this::close);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (onClose != null) {
                try {
                    onClose.run();
                } catch (Exception ignored) {}
            }
        }
    }
}

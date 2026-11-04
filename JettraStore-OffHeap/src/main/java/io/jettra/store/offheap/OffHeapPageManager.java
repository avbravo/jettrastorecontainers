package io.jettra.store.offheap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestor de páginas Off-Heap (Buffer Pool Manager) para JettraStore.
 * Coordina la lectura, escritura y persistencia directa a disco mediante {@link FileChannel} NIO
 * sin transferir buffers al Heap de la JVM.
 */
public final class OffHeapPageManager implements AutoCloseable {

    private final Path storageFile;
    private final int pageSize;
    private final Arena sharedArena;
    private final Map<Long, OffHeapPageBuffer> activePages = new ConcurrentHashMap<>();
    private final AtomicLong nextPageId = new AtomicLong(1);
    private FileChannel fileChannel;

    public OffHeapPageManager(Path storageFile) throws IOException {
        this(storageFile, OffHeapPageBuffer.DEFAULT_PAGE_SIZE);
    }

    public OffHeapPageManager(Path storageFile, int pageSize) throws IOException {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile requerido");
        this.pageSize = pageSize > 0 ? pageSize : OffHeapPageBuffer.DEFAULT_PAGE_SIZE;
        this.sharedArena = Arena.ofShared();
        this.fileChannel = FileChannel.open(
                storageFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    /**
     * Asigna una nueva página en memoria nativa e incrementa el contador de páginas.
     */
    public OffHeapPageBuffer allocateNewPage() {
        long pageId = nextPageId.getAndIncrement();
        OffHeapPageBuffer page = new OffHeapPageBuffer(sharedArena, pageId, pageSize);
        activePages.put(pageId, page);
        return page;
    }

    /**
     * Obtiene una página por su identificador. Si no está en memoria, la carga desde disco.
     */
    public OffHeapPageBuffer getPage(long pageId) throws IOException {
        OffHeapPageBuffer page = activePages.get(pageId);
        if (page != null) {
            return page;
        }

        // Leer página desde el archivo contiguo
        long fileOffset = (pageId - 1) * pageSize;
        if (fileOffset + pageSize > fileChannel.size()) {
            return null; // La página no existe aún
        }

        OffHeapPageBuffer loaded = new OffHeapPageBuffer(sharedArena, pageId, pageSize);
        loaded.load(fileChannel, fileOffset);
        activePages.put(pageId, loaded);
        return loaded;
    }

    /**
     * Fuerza la sincronización de una página específica a disco.
     */
    public void flushPage(long pageId) throws IOException {
        OffHeapPageBuffer page = activePages.get(pageId);
        if (page != null) {
            long fileOffset = (pageId - 1) * pageSize;
            page.flush(fileChannel, fileOffset);
            fileChannel.force(false);
        }
    }

    /**
     * Fuerza la sincronización de todas las páginas activas a disco.
     */
    public void flushAll() throws IOException {
        for (Map.Entry<Long, OffHeapPageBuffer> entry : activePages.entrySet()) {
            long fileOffset = (entry.getKey() - 1) * pageSize;
            entry.getValue().flush(fileChannel, fileOffset);
        }
        fileChannel.force(true);
    }

    public int getActivePageCount() {
        return activePages.size();
    }

    @Override
    public void close() throws IOException {
        try {
            flushAll();
        } finally {
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
            activePages.clear();
            sharedArena.close();
        }
    }
}


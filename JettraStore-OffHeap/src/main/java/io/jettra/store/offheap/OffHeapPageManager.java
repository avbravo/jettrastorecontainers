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
    private OffHeapPageBuffer currentAppendPage;

    public OffHeapPageManager(Path storageFile) throws IOException {
        this(storageFile, OffHeapPageBuffer.DEFAULT_PAGE_SIZE);
    }

    public OffHeapPageManager(Path storageFile, int pageSize) throws IOException {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile requerido");
        this.pageSize = pageSize > 0 ? pageSize : OffHeapPageBuffer.DEFAULT_PAGE_SIZE;
        this.sharedArena = Arena.ofShared();
        if (storageFile.getParent() != null) {
            java.nio.file.Files.createDirectories(storageFile.getParent());
        }
        this.fileChannel = FileChannel.open(
                storageFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        long existingPages = this.fileChannel.size() / this.pageSize;
        if (existingPages > 0) {
            this.nextPageId.set(existingPages + 1);
        }
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
     * Inserta un registro binario en memoria nativa off-heap y sincroniza la página a disco inmediatamente.
     */
    public synchronized void appendRecordAndFlush(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || fileChannel == null || !fileChannel.isOpen()) {
            return;
        }
        if (currentAppendPage == null) {
            long existingPages = fileChannel.size() / pageSize;
            if (existingPages > 0) {
                currentAppendPage = getPage(existingPages);
            }
            if (currentAppendPage == null) {
                currentAppendPage = allocateNewPage();
            }
        }
        if (payload.length + OffHeapPageBuffer.HEADER_SIZE + OffHeapPageBuffer.SLOT_SIZE > pageSize) {
            int needed = (int) (payload.length + OffHeapPageBuffer.HEADER_SIZE + OffHeapPageBuffer.SLOT_SIZE + 64);
            long dedicatedId = nextPageId.getAndIncrement();
            OffHeapPageBuffer largePage = new OffHeapPageBuffer(sharedArena, dedicatedId, needed);
            largePage.insertRecord(payload);
            long offset = Math.max(fileChannel.size(), (dedicatedId - 1L) * pageSize);
            largePage.flush(fileChannel, offset);
            fileChannel.force(true);
            return;
        }
        int slot = currentAppendPage.insertRecord(payload);
        if (slot == -1) {
            flushPage(currentAppendPage.getPageId());
            currentAppendPage = allocateNewPage();
            currentAppendPage.insertRecord(payload);
        }
        flushPage(currentAppendPage.getPageId());
    }

    /**
     * Fuerza la sincronización de una página específica a disco.
     */
    public synchronized void flushPage(long pageId) throws IOException {
        OffHeapPageBuffer page = activePages.get(pageId);
        if (page != null && fileChannel != null && fileChannel.isOpen()) {
            long fileOffset = (pageId - 1) * pageSize;
            page.flush(fileChannel, fileOffset);
            fileChannel.force(true);
        }
    }

    /**
     * Fuerza la sincronización de todas las páginas activas a disco.
     */
    public synchronized void flushAll() throws IOException {
        if (fileChannel == null || !fileChannel.isOpen()) {
            return;
        }
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


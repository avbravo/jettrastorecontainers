package com.jettra.memory.engine;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestor de índices en disco (.idx) y mapeo en memoria de JettraMemory.
 * Mantiene un catálogo de alta concurrencia de claves y metadatos de ubicación,
 * operando con Foreign Function & Memory API (FFM) para persistencia sin impacto en el Heap.
 */
public final class IndexManager implements AutoCloseable {

    public static final byte[] INDEX_MAGIC = new byte[]{'J', 'I', 'D', 'X'};
    public static final short INDEX_VERSION = 1;

    private final Path indexPath;
    private final ConcurrentHashMap<String, IndexEntry> indexMap = new ConcurrentHashMap<>();
    private final AtomicLong deadBytesAccumulator = new AtomicLong(0);
    private final AtomicLong activeBytesAccumulator = new AtomicLong(0);

    public IndexManager(Path indexPath) {
        this.indexPath = indexPath;
    }

    public void put(String key, IndexEntry entry) {
        IndexEntry previous = indexMap.put(key, entry);
        if (previous != null) {
            // El registro anterior ahora es espacio muerto que el Garbage Collector debe reciclar
            deadBytesAccumulator.addAndGet(previous.length());
            if (!previous.isTombstone()) {
                activeBytesAccumulator.addAndGet(-previous.payloadLength());
            }
        }
        if (!entry.isTombstone()) {
            activeBytesAccumulator.addAndGet(entry.payloadLength());
        } else {
            deadBytesAccumulator.addAndGet(entry.length());
        }
    }

    public IndexEntry get(String key) {
        IndexEntry entry = indexMap.get(key);
        if (entry == null || entry.isTombstone()) {
            return null;
        }
        return entry;
    }

    public IndexEntry getRaw(String key) {
        return indexMap.get(key);
    }

    public boolean containsKey(String key) {
        IndexEntry entry = indexMap.get(key);
        return entry != null && !entry.isTombstone();
    }

    public IndexEntry markTombstone(String key, long tombstoneOffset, int tombstoneLength) {
        IndexEntry current = indexMap.get(key);
        if (current == null || current.isTombstone()) {
            return null;
        }
        IndexEntry tombstoneEntry = new IndexEntry(
                tombstoneOffset,
                tombstoneLength,
                0,
                current.version() + 1,
                System.currentTimeMillis(),
                true,
                0L
        );
        put(key, tombstoneEntry);
        return tombstoneEntry;
    }

    public IndexEntry remove(String key) {
        IndexEntry removed = indexMap.remove(key);
        if (removed != null) {
            deadBytesAccumulator.addAndGet(removed.length());
            if (!removed.isTombstone()) {
                activeBytesAccumulator.addAndGet(-removed.payloadLength());
            }
        }
        return removed;
    }

    public Map<String, IndexEntry> getAllLiveEntries() {
        Map<String, IndexEntry> live = new ConcurrentHashMap<>();
        indexMap.forEach((k, v) -> {
            if (!v.isTombstone()) {
                live.put(k, v);
            }
        });
        return Collections.unmodifiableMap(live);
    }

    public Map<String, IndexEntry> getAllEntries() {
        return Collections.unmodifiableMap(indexMap);
    }

    public int liveCount() {
        int count = 0;
        for (IndexEntry entry : indexMap.values()) {
            if (!entry.isTombstone()) {
                count++;
            }
        }
        return count;
    }

    public int totalEntries() {
        return indexMap.size();
    }

    public long getDeadBytes() {
        return deadBytesAccumulator.get();
    }

    public long getActiveBytes() {
        return activeBytesAccumulator.get();
    }

    public void resetDeadBytes() {
        deadBytesAccumulator.set(0);
    }

    /**
     * Sincroniza el índice completo al archivo físico .idx usando Panama FFM y NIO FileChannel.
     */
    public synchronized void flushIndexToDisk() throws IOException {
        if (indexPath == null) return;
        Path tempIdx = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");

        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(tempIdx,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // Encabezado del índice: MAGIC (4) + VERSION (2) + ENTRY_COUNT (4)
            int headerSize = 4 + 2 + 4;
            MemorySegment headerSegment = arena.allocate(headerSize);
            headerSegment.set(ValueLayout.JAVA_BYTE, 0, INDEX_MAGIC[0]);
            headerSegment.set(ValueLayout.JAVA_BYTE, 1, INDEX_MAGIC[1]);
            headerSegment.set(ValueLayout.JAVA_BYTE, 2, INDEX_MAGIC[2]);
            headerSegment.set(ValueLayout.JAVA_BYTE, 3, INDEX_MAGIC[3]);
            headerSegment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, INDEX_VERSION);
            headerSegment.set(ValueLayout.JAVA_INT_UNALIGNED, 6, indexMap.size());
            channel.write(headerSegment.asByteBuffer());

            // Escribir cada entrada: keyLength (2) + keyBytes + entryRecord (37)
            for (Map.Entry<String, IndexEntry> e : indexMap.entrySet()) {
                byte[] keyBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                IndexEntry entry = e.getValue();
                int recordLen = 2 + keyBytes.length + IndexEntry.SERIALIZED_BYTE_SIZE;

                MemorySegment seg = arena.allocate(recordLen);
                long pos = 0;
                seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, pos, (short) keyBytes.length);
                pos += 2;
                MemorySegment.copy(MemorySegment.ofArray(keyBytes), 0, seg, pos, keyBytes.length);
                pos += keyBytes.length;

                seg.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, entry.offset());
                pos += 8;
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, entry.length());
                pos += 4;
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, entry.payloadLength());
                pos += 4;
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, entry.version());
                pos += 4;
                seg.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, entry.timestamp());
                pos += 8;
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) (entry.isTombstone() ? 1 : 0));
                pos += 1;
                seg.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, entry.checksum());

                channel.write(seg.asByteBuffer());
            }
            channel.force(true);
        }

        Files.move(tempIdx, indexPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Recupera el índice desde el archivo .idx existente.
     */
    public synchronized boolean loadFromDisk() throws IOException {
        if (indexPath == null || !Files.exists(indexPath)) {
            return false;
        }

        try (FileChannel channel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            if (channel.size() < 10) {
                return false;
            }

            ByteBuffer headerBuf = ByteBuffer.allocate(10).order(ByteOrder.nativeOrder());
            channel.read(headerBuf);
            headerBuf.flip();

            byte m0 = headerBuf.get();
            byte m1 = headerBuf.get();
            byte m2 = headerBuf.get();
            byte m3 = headerBuf.get();
            if (m0 != INDEX_MAGIC[0] || m1 != INDEX_MAGIC[1] || m2 != INDEX_MAGIC[2] || m3 != INDEX_MAGIC[3]) {
                return false;
            }

            short version = headerBuf.getShort();
            int count = headerBuf.getInt();
            if (count < 0 || count > 10_000_000) {
                return false;
            }

            indexMap.clear();
            deadBytesAccumulator.set(0);
            activeBytesAccumulator.set(0);

            ByteBuffer lenBuf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
            ByteBuffer entryBuf = ByteBuffer.allocate(IndexEntry.SERIALIZED_BYTE_SIZE).order(ByteOrder.nativeOrder());

            for (int i = 0; i < count; i++) {
                lenBuf.clear();
                if (channel.read(lenBuf) <= 0) break;
                lenBuf.flip();
                short keyLen = lenBuf.getShort();
                if (keyLen <= 0 || keyLen > 65536) break;

                ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
                channel.read(keyBuf);
                keyBuf.flip();
                String key = new String(keyBuf.array(), StandardCharsets.UTF_8);

                entryBuf.clear();
                if (channel.read(entryBuf) <= 0) break;
                entryBuf.flip();

                long offset = entryBuf.getLong();
                int length = entryBuf.getInt();
                int payloadLength = entryBuf.getInt();
                int recVersion = entryBuf.getInt();
                long timestamp = entryBuf.getLong();
                boolean isTombstone = entryBuf.get() == 1;
                long checksum = entryBuf.getLong();

                IndexEntry entry = new IndexEntry(offset, length, payloadLength, recVersion, timestamp, isTombstone, checksum);
                indexMap.put(key, entry);

                if (isTombstone) {
                    deadBytesAccumulator.addAndGet(length);
                } else {
                    activeBytesAccumulator.addAndGet(payloadLength);
                }
            }
            return true;
        }
    }

    /**
     * Limpia completamente el índice en memoria.
     */
    public void clear() {
        indexMap.clear();
        deadBytesAccumulator.set(0);
        activeBytesAccumulator.set(0);
    }

    @Override
    public void close() throws IOException {
        flushIndexToDisk();
    }
}

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * Motor de almacenamiento directo a disco y gestión Off-Heap de JettraMemory.
 * Utiliza Java 25 Foreign Function & Memory API (FFM) y canales NIO FileChannel
 * para garantizar latencia predecible, cero pausas por GC de la JVM y compatibilidad binaria
 * con los objetos generados por JettraStore.
 */
public final class DiskStorageEngine implements AutoCloseable {

    public static final byte[] JETTRA_DATA_MAGIC = new byte[]{(byte) 0x4A, (byte) 0x53, (byte) 0x4D, (byte) 0x31}; // 'J', 'S', 'M', '1'
    public static final short JETTRA_FORMAT_VERSION = 1;
    public static final int FILE_HEADER_SIZE = 8; // Magic (4) + Version (2) + Reserved (2)

    public static final byte RECORD_ACTIVE = 0x01;
    public static final byte RECORD_TOMBSTONE = 0x02;

    private final Path dataPath;
    private final Path indexPath;
    private final IndexManager indexManager;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    private FileChannel dataChannel;
    private final AtomicLong writeOffset = new AtomicLong(0);

    // Telemetría de operaciones
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong deleteOperations = new AtomicLong(0);

    public DiskStorageEngine(Path baseDirectory, String storeName) throws IOException {
        Files.createDirectories(baseDirectory);
        this.dataPath = baseDirectory.resolve(storeName + ".jettra");
        this.indexPath = baseDirectory.resolve(storeName + ".idx");
        this.indexManager = new IndexManager(indexPath);

        initStorageFiles();
    }

    private void initStorageFiles() throws IOException {
        boolean isNewFile = !Files.exists(dataPath) || Files.size(dataPath) == 0;
        this.dataChannel = FileChannel.open(
                dataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );

        if (isNewFile) {
            writeHeader(dataChannel);
            writeOffset.set(FILE_HEADER_SIZE);
        } else {
            validateHeader(dataChannel);
            writeOffset.set(dataChannel.size());
            // Cargar índice o reconstruir si está ausente
            if (!indexManager.loadFromDisk()) {
                rebuildIndexFromDataFile();
            }
        }
    }

    private void writeHeader(FileChannel channel) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = arena.allocate(FILE_HEADER_SIZE);
            header.set(ValueLayout.JAVA_BYTE, 0, JETTRA_DATA_MAGIC[0]);
            header.set(ValueLayout.JAVA_BYTE, 1, JETTRA_DATA_MAGIC[1]);
            header.set(ValueLayout.JAVA_BYTE, 2, JETTRA_DATA_MAGIC[2]);
            header.set(ValueLayout.JAVA_BYTE, 3, JETTRA_DATA_MAGIC[3]);
            header.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, JETTRA_FORMAT_VERSION);
            header.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) 0); // Reservado
            channel.write(header.asByteBuffer(), 0);
            channel.force(true);
        }
    }

    private void validateHeader(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(FILE_HEADER_SIZE);
        channel.read(buf, 0);
        buf.flip();
        if (buf.remaining() < FILE_HEADER_SIZE) {
            throw new IOException("Archivo de datos .jettra corrupto: tamaño insuficiente.");
        }
        for (int i = 0; i < 4; i++) {
            if (buf.get() != JETTRA_DATA_MAGIC[i]) {
                throw new IOException("Firma mágica .jettra inválida.");
            }
        }
    }

    /**
     * Escribe un registro binario directamente al archivo .jettra fuera del Heap.
     */
    public void put(String key, byte[] payload) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int payloadLength = payload != null ? payload.length : 0;
        int recordSize = computeRecordSize(keyBytes.length, payloadLength);

        CRC32 crc = new CRC32();
        if (payload != null && payload.length > 0) {
            crc.update(payload);
        }
        long checksum = crc.getValue();

        rwLock.readLock().lock();
        try {
            long currentOffset = writeOffset.getAndAdd(recordSize);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment recordSegment = arena.allocate(recordSize);
                long pos = 0;

                recordSegment.set(ValueLayout.JAVA_BYTE, pos++, RECORD_ACTIVE);
                recordSegment.set(ValueLayout.JAVA_INT_UNALIGNED, pos, 1); // Versión inicial
                pos += 4;
                recordSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, System.currentTimeMillis());
                pos += 8;

                recordSegment.set(ValueLayout.JAVA_INT_UNALIGNED, pos, keyBytes.length);
                pos += 4;
                MemorySegment.copy(MemorySegment.ofArray(keyBytes), 0, recordSegment, pos, keyBytes.length);
                pos += keyBytes.length;

                recordSegment.set(ValueLayout.JAVA_INT_UNALIGNED, pos, payloadLength);
                pos += 4;
                if (payloadLength > 0) {
                    MemorySegment.copy(MemorySegment.ofArray(payload), 0, recordSegment, pos, payloadLength);
                    pos += payloadLength;
                }

                recordSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, checksum);

                dataChannel.write(recordSegment.asByteBuffer(), currentOffset);
            }

            IndexEntry entry = new IndexEntry(
                    currentOffset,
                    recordSize,
                    payloadLength,
                    1,
                    System.currentTimeMillis(),
                    false,
                    checksum
            );
            indexManager.put(key, entry);
            writeOperations.incrementAndGet();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Recupera el contenido binario de un registro mapeando el archivo a memoria o lectura directa.
     */
    public byte[] get(String key) throws IOException {
        rwLock.readLock().lock();
        try {
            IndexEntry entry = indexManager.get(key);
            if (entry == null || entry.isTombstone()) {
                return null;
            }

            readOperations.incrementAndGet();

            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            // El payload se encuentra a: offset + 1 (status) + 4 (version) + 8 (timestamp) + 4 (keyLen) + keyLen + 4 (payloadLen)
            long payloadOffset = entry.offset() + 1 + 4 + 8 + 4 + keyBytes.length + 4;
            int payloadLength = entry.payloadLength();

            if (payloadLength == 0) {
                return new byte[0];
            }

            // Lectura directa off-heap mediante Mapped MemorySegment o buffer directo
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment readSegment = arena.allocate(payloadLength);
                ByteBuffer buf = readSegment.asByteBuffer();
                dataChannel.read(buf, payloadOffset);

                byte[] result = new byte[payloadLength];
                MemorySegment.copy(readSegment, 0, MemorySegment.ofArray(result), 0, payloadLength);

                // Validar integridad
                CRC32 crc = new CRC32();
                crc.update(result);
                if (crc.getValue() != entry.checksum()) {
                    throw new IOException("Error de suma de verificación CRC32 para la clave: " + key);
                }

                return result;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Eliminación lógica generando un registro Tombstone en disco.
     */
    public boolean delete(String key) throws IOException {
        rwLock.readLock().lock();
        try {
            IndexEntry current = indexManager.get(key);
            if (current == null || current.isTombstone()) {
                return false;
            }

            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            int tombstoneSize = computeRecordSize(keyBytes.length, 0);
            long tombstoneOffset = writeOffset.getAndAdd(tombstoneSize);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(tombstoneSize);
                long pos = 0;
                seg.set(ValueLayout.JAVA_BYTE, pos++, RECORD_TOMBSTONE);
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, current.version() + 1);
                pos += 4;
                seg.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, System.currentTimeMillis());
                pos += 8;
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, keyBytes.length);
                pos += 4;
                MemorySegment.copy(MemorySegment.ofArray(keyBytes), 0, seg, pos, keyBytes.length);
                pos += keyBytes.length;
                seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, 0); // Payload length = 0
                pos += 4;
                seg.set(ValueLayout.JAVA_LONG_UNALIGNED, pos, 0L); // Checksum = 0

                dataChannel.write(seg.asByteBuffer(), tombstoneOffset);
            }

            indexManager.markTombstone(key, tombstoneOffset, tombstoneSize);
            deleteOperations.incrementAndGet();
            return true;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean containsKey(String key) {
        rwLock.readLock().lock();
        try {
            return indexManager.containsKey(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int size() {
        return indexManager.liveCount();
    }

    /**
     * Reconstruye el índice mapeando secuencialmente el archivo .jettra (Autoreparación ante fallos).
     */
    public synchronized void rebuildIndexFromDataFile() throws IOException {
        indexManager.clear();
        long fileSize = dataChannel.size();
        if (fileSize <= FILE_HEADER_SIZE) {
            return;
        }

        long pos = FILE_HEADER_SIZE;
        ByteBuffer headerBuf = ByteBuffer.allocate(1 + 4 + 8 + 4).order(ByteOrder.nativeOrder());
        ByteBuffer payloadMetaBuf = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        ByteBuffer crcBuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());

        while (pos < fileSize) {
            headerBuf.clear();
            int read = dataChannel.read(headerBuf, pos);
            if (read < 17) break;
            headerBuf.flip();

            byte status = headerBuf.get();
            int version = headerBuf.getInt();
            long timestamp = headerBuf.getLong();
            int keyLen = headerBuf.getInt();

            // Validación estricta anti-OOM para evitar desbordamientos por corrupción o endianness
            if (keyLen <= 0 || keyLen > 65536 || pos + 17 + keyLen > fileSize) {
                break;
            }

            ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
            dataChannel.read(keyBuf, pos + 17);
            keyBuf.flip();
            String key = new String(keyBuf.array(), StandardCharsets.UTF_8);

            payloadMetaBuf.clear();
            if (dataChannel.read(payloadMetaBuf, pos + 17 + keyLen) < 4) break;
            payloadMetaBuf.flip();
            int payloadLen = payloadMetaBuf.getInt();

            if (payloadLen < 0 || payloadLen > 100 * 1024 * 1024) {
                break;
            }

            crcBuf.clear();
            if (dataChannel.read(crcBuf, pos + 17 + keyLen + 4 + payloadLen) < 8) break;
            crcBuf.flip();
            long checksum = crcBuf.getLong();

            int recordTotalSize = computeRecordSize(keyLen, payloadLen);
            boolean isTombstone = (status == RECORD_TOMBSTONE);

            IndexEntry entry = new IndexEntry(pos, recordTotalSize, payloadLen, version, timestamp, isTombstone, checksum);
            indexManager.put(key, entry);

            pos += recordTotalSize;
        }
        writeOffset.set(fileSize);
    }

    /**
     * Sustituye atómicamente el canal y archivo actual por uno compactado (usado por el recolector de basura).
     */
    public void swapCompactedDataFile(Path compactedDataPath, IndexManager newIndexManager) throws IOException {
        rwLock.writeLock().lock();
        try {
            dataChannel.close();

            Files.move(compactedDataPath, dataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            this.dataChannel = FileChannel.open(
                    dataPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );

            this.writeOffset.set(dataChannel.size());

            // Actualizar índice en memoria y persistir
            indexManager.clear();
            newIndexManager.getAllEntries().forEach(indexManager::put);
            indexManager.resetDeadBytes();
            indexManager.flushIndexToDisk();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void flush() throws IOException {
        rwLock.writeLock().lock();
        try {
            indexManager.flushIndexToDisk();
            if (dataChannel != null && dataChannel.isOpen()) {
                dataChannel.force(true);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public StorageMetrics getMetrics() {
        return StorageMetrics.of(
                writeOffset.get(),
                indexManager.getActiveBytes(),
                indexManager.getDeadBytes(),
                indexManager.liveCount(),
                indexManager.totalEntries() - indexManager.liveCount(),
                readOperations.get(),
                writeOperations.get(),
                deleteOperations.get()
        );
    }

    public Path getDataPath() {
        return dataPath;
    }

    public Path getIndexPath() {
        return indexPath;
    }

    public IndexManager getIndexManager() {
        return indexManager;
    }

    public FileChannel getDataChannel() {
        return dataChannel;
    }

    public ReentrantReadWriteLock getRwLock() {
        return rwLock;
    }

    private static int computeRecordSize(int keyLength, int payloadLength) {
        // Status(1) + Version(4) + Timestamp(8) + KeyLength(4) + KeyBytes + PayloadLength(4) + PayloadBytes + Checksum(8)
        return 1 + 4 + 8 + 4 + keyLength + 4 + payloadLength + 8;
    }

    @Override
    public void close() throws IOException {
        rwLock.writeLock().lock();
        try {
            if (indexManager != null) {
                indexManager.close();
            }
            if (dataChannel != null && dataChannel.isOpen()) {
                dataChannel.force(true);
                dataChannel.close();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}

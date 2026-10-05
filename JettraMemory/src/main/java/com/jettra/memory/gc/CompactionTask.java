package com.jettra.memory.gc;

import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.engine.IndexEntry;
import com.jettra.memory.engine.IndexManager;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Tarea de compactación y desfragmentación de disco de JettraMemory.
 * Lee de forma secuencial únicamente los registros activos y vigentes,
 * omitiendo registros obsoletos y Tombstones, escribiéndolos en un archivo
 * temporal y ejecutando una sustitución atómica para liberar espacio físico en disco.
 */
public final class CompactionTask implements Callable<CompactionResult> {

    private final DiskStorageEngine storageEngine;

    public CompactionTask(DiskStorageEngine storageEngine) {
        this.storageEngine = storageEngine;
    }

    @Override
    public CompactionResult call() throws Exception {
        long startTime = System.currentTimeMillis();
        Path originalDataPath = storageEngine.getDataPath();
        Path stagingDataPath = originalDataPath.resolveSibling(originalDataPath.getFileName() + ".compacting");

        long initialSizeBytes = Files.exists(originalDataPath) ? Files.size(originalDataPath) : 0;
        int initialEntries = storageEngine.getIndexManager().totalEntries();

        // 1. Obtener snapshot de entradas activas
        Map<String, IndexEntry> liveEntries = storageEngine.getIndexManager().getAllLiveEntries();
        IndexManager newIndexManager = new IndexManager(storageEngine.getIndexPath());

        // 2. Escribir archivo compactado
        try (FileChannel stagingChannel = FileChannel.open(
                stagingDataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Escribir cabecera mágica
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment header = arena.allocate(DiskStorageEngine.FILE_HEADER_SIZE);
                header.set(ValueLayout.JAVA_BYTE, 0, DiskStorageEngine.JETTRA_DATA_MAGIC[0]);
                header.set(ValueLayout.JAVA_BYTE, 1, DiskStorageEngine.JETTRA_DATA_MAGIC[1]);
                header.set(ValueLayout.JAVA_BYTE, 2, DiskStorageEngine.JETTRA_DATA_MAGIC[2]);
                header.set(ValueLayout.JAVA_BYTE, 3, DiskStorageEngine.JETTRA_DATA_MAGIC[3]);
                header.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, DiskStorageEngine.JETTRA_FORMAT_VERSION);
                header.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) 0);
                stagingChannel.write(header.asByteBuffer(), 0);
            }

            long currentStagingOffset = DiskStorageEngine.FILE_HEADER_SIZE;
            FileChannel originalChannel = storageEngine.getDataChannel();

            // 3. Transferir registros activos sin cargar payloads completos al Heap de la JVM
            for (Map.Entry<String, IndexEntry> e : liveEntries.entrySet()) {
                String key = e.getKey();
                IndexEntry oldEntry = e.getValue();
                int recordLength = oldEntry.length();

                ByteBuffer recordBuffer = ByteBuffer.allocateDirect(recordLength);
                originalChannel.read(recordBuffer, oldEntry.offset());
                recordBuffer.flip();

                stagingChannel.write(recordBuffer, currentStagingOffset);

                IndexEntry newEntry = oldEntry.withNewOffset(currentStagingOffset);
                newIndexManager.put(key, newEntry);

                currentStagingOffset += recordLength;
            }
            stagingChannel.force(true);
        }

        // 4. Intercambio atómico
        storageEngine.swapCompactedDataFile(stagingDataPath, newIndexManager);

        long finalSizeBytes = Files.size(originalDataPath);
        long reclaimedBytes = Math.max(0, initialSizeBytes - finalSizeBytes);
        long durationMs = System.currentTimeMillis() - startTime;

        return new CompactionResult(
                initialSizeBytes,
                finalSizeBytes,
                reclaimedBytes,
                initialEntries,
                newIndexManager.liveCount(),
                durationMs
        );
    }
}

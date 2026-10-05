package com.jettra.memory.engine;

import java.io.Serializable;

/**
 * Métricas en tiempo real del motor de almacenamiento en disco JettraMemory.
 */
public record StorageMetrics(
        long totalAllocatedBytes,
        long activeBytes,
        long deadBytes,
        int liveEntries,
        int tombstoneEntries,
        long readOperations,
        long writeOperations,
        long deleteOperations,
        double fragmentationRatio
) implements Serializable {

    public static StorageMetrics of(
            long totalAllocatedBytes,
            long activeBytes,
            long deadBytes,
            int liveEntries,
            int tombstoneEntries,
            long reads,
            long writes,
            long deletes
    ) {
        double ratio = totalAllocatedBytes > 0 ? (double) deadBytes / (double) totalAllocatedBytes : 0.0;
        return new StorageMetrics(
                totalAllocatedBytes,
                activeBytes,
                deadBytes,
                liveEntries,
                tombstoneEntries,
                reads,
                writes,
                deletes,
                ratio
        );
    }

    @Override
    public String toString() {
        return String.format(
                "StorageMetrics[Allocated=%.2f MB, Active=%.2f MB, Dead=%.2f MB, LiveEntries=%d, Tombstones=%d, Frag=%.1f%%, Reads=%d, Writes=%d]",
                totalAllocatedBytes / (1024.0 * 1024.0),
                activeBytes / (1024.0 * 1024.0),
                deadBytes / (1024.0 * 1024.0),
                liveEntries,
                tombstoneEntries,
                fragmentationRatio * 100.0,
                readOperations,
                writeOperations
        );
    }
}

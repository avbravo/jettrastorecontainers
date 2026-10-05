package com.jettra.memory.gc;

import java.io.Serializable;

/**
 * Resultado estadístico de la ejecución de una tarea de compactación de JettraMemory.
 */
public record CompactionResult(
        long initialSizeBytes,
        long finalSizeBytes,
        long reclaimedBytes,
        int initialEntries,
        int finalLiveEntries,
        long durationMs
) implements Serializable {

    public double spaceReductionPercentage() {
        if (initialSizeBytes <= 0) return 0.0;
        return ((double) reclaimedBytes / (double) initialSizeBytes) * 100.0;
    }

    @Override
    public String toString() {
        return String.format(
                "CompactionResult[Before=%.2f KB, After=%.2f KB, Reclaimed=%.2f KB (%.1f%%), Duration=%d ms]",
                initialSizeBytes / 1024.0,
                finalSizeBytes / 1024.0,
                reclaimedBytes / 1024.0,
                spaceReductionPercentage(),
                durationMs
        );
    }
}

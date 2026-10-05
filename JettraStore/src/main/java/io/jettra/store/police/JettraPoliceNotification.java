package io.jettra.store.police;

import java.io.Serializable;
import java.time.Instant;

/**
 * Metadatos de notificación emitidos cuando el Sentinel de JettraPolice se activa
 * para imponer streaming por chunks y paginación lazy anti-OOM sobre consultas masivas.
 * Permite que clientes y GUIs (JettraShell, JettraStoreFX) reciban feedback transparente
 * sin modificar las firmas de métodos existentes.
 */
public record JettraPoliceNotification(
    String operation,
    String targetCollection,
    long estimatedTotalRecords,
    int safeBatchSize,
    double heapUsagePercent,
    long availableMemoryMb,
    String warningMessage,
    Instant timestamp,
    boolean forcedLazyPagination
) implements Serializable {

    public static JettraPoliceNotification of(
            String operation,
            String targetCollection,
            long estimatedTotalRecords,
            int safeBatchSize,
            double heapUsagePercent,
            long availableMemoryMb,
            String warningMessage) {
        return new JettraPoliceNotification(
            operation,
            targetCollection,
            estimatedTotalRecords,
            safeBatchSize,
            heapUsagePercent,
            availableMemoryMb,
            warningMessage,
            Instant.now(),
            true
        );
    }
}

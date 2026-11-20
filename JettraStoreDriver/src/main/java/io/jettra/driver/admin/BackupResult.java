package io.jettra.driver.admin;

import java.io.Serializable;

/**
 * Resultado de una operación programática de Backup en JettraStore.
 */
public record BackupResult(
    boolean success,
    String snapshotPath,
    long durationMs,
    long sizeBytes,
    String message
) implements Serializable {

    public boolean isSuccess() {
        return success;
    }
}

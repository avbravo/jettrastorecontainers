package io.jettra.driver.admin;

import java.io.Serializable;

/**
 * Resultado de una operación programática de Restauración (Restore) en JettraStore.
 */
public record RestoreResult(
    boolean success,
    long durationMs,
    String message
) implements Serializable {

    public boolean isSuccess() {
        return success;
    }
}

package io.jettra.driver.version;

/**
 * Tipo de operación registrada en el historial de versiones de un registro/documento en JettraStore (RF-18).
 */
public enum VersionOperation {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE
}

package io.jettra.core.three.d.security;

/**
 * Roles de acceso específicos sobre bases de datos en JettraStore.
 */
public enum JettraDatabaseRole {
    ADMIN("Administrador Total (DDL + DML + Police)"),
    READ_WRITE("Lectura y Escritura (DML Completo)"),
    READ_ONLY("Solo Lectura (Consultas y Agregaciones)"),
    NONE("Sin Acceso (Acceso Denegado)");

    private final String description;

    JettraDatabaseRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

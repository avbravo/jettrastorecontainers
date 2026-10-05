package io.jettra.store.explorer.model;

public enum JettraDatabaseRole {
    ADMIN("Acceso Total (DDL, DML, Quorum, Backup)"),
    READ_WRITE("Lectura y Escritura (INSERT, UPDATE, DELETE, SELECT)"),
    READ_ONLY("Solo Lectura (SELECT, KNN_SEARCH, ANALYZE)"),
    NONE("Sin Acceso");

    private final String description;

    JettraDatabaseRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

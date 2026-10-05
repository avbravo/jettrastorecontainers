package io.jettra.core.three.d.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Permiso asignado a un usuario sobre una base de datos específica de JettraStore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabasePermission {
    private String databaseId;
    private JettraDatabaseRole role;

    public DatabasePermission() {
        this.databaseId = "";
        this.role = JettraDatabaseRole.READ_ONLY;
    }

    public DatabasePermission(String databaseId, JettraDatabaseRole role) {
        this.databaseId = databaseId;
        this.role = role != null ? role : JettraDatabaseRole.READ_ONLY;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public JettraDatabaseRole getRole() {
        return role;
    }

    public void setRole(JettraDatabaseRole role) {
        this.role = role;
    }
}

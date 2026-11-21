package io.jettra.core.three.d.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Representa un usuario del sistema de autenticación y autorización de JettraStore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JettraUser {

    public enum GlobalRole {
        ADMIN("Administrador del Clúster"),
        OPERATOR("Operador de Infraestructura"),
        DEVELOPER("Desarrollador de Aplicaciones"),
        ANALYST("Analista de Datos / BI"),
        AUDITOR("Auditor de Seguridad y Cumplimiento"),
        GUEST("Usuario Invitado");

        private final String label;
        GlobalRole(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private String id;
    private String username;
    private String password;
    private String description;
    private GlobalRole globalRole;
    private boolean enabled;
    private long createdAt;
    private List<DatabasePermission> databasePermissions = new ArrayList<>();

    public JettraUser() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
        this.globalRole = GlobalRole.ANALYST;
    }

    public JettraUser(String username, String password, String description, GlobalRole globalRole) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.password = password;
        this.description = description;
        this.globalRole = globalRole != null ? globalRole : GlobalRole.ANALYST;
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    public JettraDatabaseRole getRoleForDatabase(String databaseId) {
        if (globalRole == GlobalRole.ADMIN) {
            return JettraDatabaseRole.ADMIN;
        }
        for (DatabasePermission p : databasePermissions) {
            if (p.getDatabaseId().equalsIgnoreCase(databaseId)) {
                return p.getRole();
            }
        }
        return JettraDatabaseRole.NONE;
    }

    public void setRoleForDatabase(String databaseId, JettraDatabaseRole role) {
        for (DatabasePermission p : databasePermissions) {
            if (p.getDatabaseId().equalsIgnoreCase(databaseId)) {
                p.setRole(role);
                return;
            }
        }
        databasePermissions.add(new DatabasePermission(databaseId, role));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public GlobalRole getGlobalRole() { return globalRole; }
    public void setGlobalRole(GlobalRole globalRole) { this.globalRole = globalRole; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public List<DatabasePermission> getDatabasePermissions() { return databasePermissions; }
    public void setDatabasePermissions(List<DatabasePermission> databasePermissions) {
        this.databasePermissions = databasePermissions != null ? databasePermissions : new ArrayList<>();
    }
}

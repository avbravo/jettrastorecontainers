package io.jettra.store.explorer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JettraUser implements Serializable {
    public enum GlobalRole {
        ADMIN("Super Administrador del Clúster"),
        OPERATOR("Operador de Ingesta y Operaciones"),
        DEVELOPER("Desarrollador y Consultor de Datos"),
        ANALYST("Analista de Consultas y Lectura"),
        AUDITOR("Auditor de Seguridad y Cumplimiento Raft");

        private final String description;
        GlobalRole(String description) { this.description = description; }
        public String getDescription() { return description; }
    }

    private String id;
    private String username;
    private String password;
    private String description;
    private GlobalRole globalRole = GlobalRole.DEVELOPER;
    private Map<String, JettraDatabaseRole> databaseRoles = new HashMap<>();

    public JettraUser() {}

    public JettraUser(String username, String password, String description, GlobalRole globalRole) {
        this.id = "u_" + username;
        this.username = username;
        this.password = password;
        this.description = description;
        this.globalRole = globalRole;
    }

    public String getId() { return id != null ? id : "u_" + username; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public GlobalRole getGlobalRole() { return globalRole; }
    public void setGlobalRole(GlobalRole globalRole) { this.globalRole = globalRole; }
    public Map<String, JettraDatabaseRole> getDatabaseRoles() { return databaseRoles; }
    public void setDatabaseRoles(Map<String, JettraDatabaseRole> databaseRoles) { this.databaseRoles = databaseRoles; }

    public JettraDatabaseRole getRoleForDatabase(String db) {
        if (globalRole == GlobalRole.ADMIN) return JettraDatabaseRole.ADMIN;
        return databaseRoles.getOrDefault(db, JettraDatabaseRole.NONE);
    }

    public void setRoleForDatabase(String db, JettraDatabaseRole role) {
        databaseRoles.put(db, role);
    }
}

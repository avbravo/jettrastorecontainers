package io.jettra.core.three.d.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Gestor de persistencia y administración de usuarios y permisos de bases de datos
 * para JettraStore en tiempo real.
 */
public class UserManager {
    private static UserManager instance;
    public static synchronized UserManager getInstance() {
        if (instance == null) {
            instance = new UserManager();
        }
        return instance;
    }

    private static final String DEFAULT_FILE_PATH = "memory/security/users.json";
    private final String filePath;
    private final ObjectMapper mapper;
    private final List<JettraUser> users = new ArrayList<>();

    public UserManager() {
        this(DEFAULT_FILE_PATH);
    }

    public UserManager(String filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public synchronized List<JettraUser> getUsers() {
        return Collections.unmodifiableList(new ArrayList<>(users));
    }

    public synchronized Optional<JettraUser> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return users.stream().filter(u -> username.equalsIgnoreCase(u.getUsername())).findFirst();
    }

    public synchronized Optional<JettraUser> findById(String id) {
        if (id == null) return Optional.empty();
        return users.stream().filter(u -> id.equalsIgnoreCase(u.getId())).findFirst();
    }

    public synchronized void saveOrUpdate(JettraUser user) {
        if (user == null || user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return;
        }

        int index = -1;
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getUsername().equalsIgnoreCase(user.getUsername())
                || users.get(i).getId().equalsIgnoreCase(user.getId())) {
                index = i;
                break;
            }
        }

        if (index >= 0) {
            users.set(index, user);
        } else {
            users.add(user);
        }
        save();
    }

    public synchronized boolean delete(String username) {
        if (username == null) return false;
        // Proteger el usuario admin root
        if ("admin".equalsIgnoreCase(username) || "dba_root_admin".equalsIgnoreCase(username)) {
            return false;
        }
        boolean removed = users.removeIf(u -> username.equalsIgnoreCase(u.getUsername()) || username.equalsIgnoreCase(u.getId()));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized void assignDatabaseRole(String username, String databaseId, JettraDatabaseRole role) {
        findByUsername(username).ifPresent(u -> {
            u.setRoleForDatabase(databaseId, role);
            save();
        });
    }

    public synchronized void load() {
        users.clear();
        File file = new File(filePath);
        if (file.exists() && file.length() > 0) {
            try {
                List<JettraUser> loaded = mapper.readValue(file, new TypeReference<List<JettraUser>>() {});
                if (loaded != null && !loaded.isEmpty()) {
                    users.addAll(loaded);
                }
            } catch (IOException e) {
                System.err.println("[WARN] Error cargando usuarios desde " + filePath + ": " + e.getMessage());
            }
        }

        if (users.isEmpty()) {
            initDefaultUsers();
            save();
        }
    }

    private void initDefaultUsers() {
        // 1. Root Admin
        JettraUser admin = new JettraUser("admin", "admin123", "Superadministrador del Clúster JettraStore", JettraUser.GlobalRole.ADMIN);
        admin.setRoleForDatabase("example_factura_db", JettraDatabaseRole.ADMIN);
        admin.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.ADMIN);
        admin.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.ADMIN);
        admin.setRoleForDatabase("system_metadata_db", JettraDatabaseRole.ADMIN);
        users.add(admin);

        // 2. Operador Facturación
        JettraUser usrFactura = new JettraUser("usr_facturacion_01", "facturaPass2026", "Operador Fiscal y Facturación", JettraUser.GlobalRole.OPERATOR);
        usrFactura.setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_WRITE);
        usrFactura.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.NONE);
        usrFactura.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.NONE);
        users.add(usrFactura);

        // 3. Médico Especialista
        JettraUser drMed = new JettraUser("dr_gonzalez_01", "medicoSeguro99", "Especialista Clínico y UCI", JettraUser.GlobalRole.DEVELOPER);
        drMed.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.READ_WRITE);
        drMed.setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_ONLY);
        users.add(drMed);

        // 4. Analista IoT Ambiental
        JettraUser ambUser = new JettraUser("sensor_co2_norte", "iotTokenStream", "Agente de Ingesta TimeSeries IoT", JettraUser.GlobalRole.ANALYST);
        ambUser.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.READ_WRITE);
        ambUser.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.NONE);
        users.add(ambUser);

        // 5. Auditor de Seguridad
        JettraUser auditor = new JettraUser("sec_auditor_02", "auditKeyCompliance", "Auditor de Registros y Políticas Raft", JettraUser.GlobalRole.AUDITOR);
        auditor.setRoleForDatabase("system_metadata_db", JettraDatabaseRole.READ_ONLY);
        auditor.setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_ONLY);
        auditor.setRoleForDatabase("samples_hostipal_db", JettraDatabaseRole.READ_ONLY);
        auditor.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.READ_ONLY);
        users.add(auditor);
    }

    public synchronized void save() {
        try {
            Path path = Paths.get(filePath);
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            mapper.writeValue(new File(filePath), users);
        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo guardar la lista de usuarios en " + filePath + ": " + e.getMessage());
        }
    }
}

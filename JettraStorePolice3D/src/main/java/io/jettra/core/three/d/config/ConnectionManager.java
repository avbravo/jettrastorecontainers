package io.jettra.core.three.d.config;

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
 * Gestor de perfiles de conexión a JettraStore.
 * Permite almacenar, editar, eliminar y seleccionar la conexión predeterminada,
 * persistiendo los perfiles en formato JSON.
 */
public class ConnectionManager {

    private static final String DEFAULT_FILE_PATH = "memory/connections.json";
    private final String filePath;
    private final ObjectMapper mapper;
    private final List<ConnectionProfile> profiles = new ArrayList<>();

    public ConnectionManager() {
        this(DEFAULT_FILE_PATH);
    }

    public ConnectionManager(String filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public synchronized List<ConnectionProfile> getProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    public synchronized Optional<ConnectionProfile> getDefaultProfile() {
        return profiles.stream().filter(ConnectionProfile::isDefault).findFirst();
    }

    public synchronized Optional<ConnectionProfile> findById(String id) {
        if (id == null) return Optional.empty();
        return profiles.stream().filter(p -> id.equalsIgnoreCase(p.getId())).findFirst();
    }

    public synchronized void saveOrUpdate(ConnectionProfile profile) {
        if (profile == null) return;
        if (profile.getId() == null || profile.getId().trim().isEmpty()) {
            profile.setId("conn_" + System.currentTimeMillis());
        }

        int index = -1;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equalsIgnoreCase(profile.getId())) {
                index = i;
                break;
            }
        }

        if (profile.isDefault()) {
            for (ConnectionProfile p : profiles) {
                p.setDefault(false);
            }
        }

        if (index >= 0) {
            profiles.set(index, profile);
        } else {
            if (profiles.isEmpty()) {
                profile.setDefault(true);
            }
            profiles.add(profile);
        }

        ensureDefaultExists();
        save();
    }

    public synchronized boolean delete(String id) {
        if (id == null) return false;
        boolean removed = profiles.removeIf(p -> p.getId().equalsIgnoreCase(id));
        if (removed) {
            ensureDefaultExists();
            save();
        }
        return removed;
    }

    public synchronized boolean setDefault(String id) {
        if (id == null) return false;
        boolean found = false;
        for (ConnectionProfile p : profiles) {
            if (p.getId().equalsIgnoreCase(id)) {
                p.setDefault(true);
                found = true;
            } else {
                p.setDefault(false);
            }
        }
        if (found) {
            save();
        }
        return found;
    }

    private void ensureDefaultExists() {
        if (profiles.isEmpty()) return;
        boolean hasDefault = profiles.stream().anyMatch(ConnectionProfile::isDefault);
        if (!hasDefault) {
            profiles.get(0).setDefault(true);
        }
    }

    public synchronized void load() {
        profiles.clear();
        File file = new File(filePath);
        if (file.exists() && file.length() > 0) {
            try {
                List<ConnectionProfile> loaded = mapper.readValue(file, new TypeReference<List<ConnectionProfile>>() {});
                if (loaded != null) {
                    profiles.addAll(loaded);
                }
            } catch (IOException e) {
                System.err.println("[WARN] Error cargando conexiones desde " + filePath + ": " + e.getMessage());
            }
        }

        if (profiles.isEmpty()) {
            ConnectionProfile defaultLocal = new ConnectionProfile(
                "conn_local",
                "JettraStore Local Master",
                "tcp://127.0.0.1:8765",
                "admin",
                "admin123",
                true
            );
            ConnectionProfile replicaCluster = new ConnectionProfile(
                "conn_replica",
                "JettraStore Cluster Node 2",
                "tcp://192.168.1.102:8765",
                "operator",
                "jettraPass!",
                false
            );
            profiles.add(defaultLocal);
            profiles.add(replicaCluster);
            save();
        }
        ensureDefaultExists();
    }

    public synchronized void save() {
        try {
            Path path = Paths.get(filePath);
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            mapper.writeValue(new File(filePath), profiles);
        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo guardar la lista de conexiones en " + filePath + ": " + e.getMessage());
        }
    }
}

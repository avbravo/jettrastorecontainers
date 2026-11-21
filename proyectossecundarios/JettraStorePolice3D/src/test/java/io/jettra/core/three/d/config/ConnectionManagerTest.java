package io.jettra.core.three.d.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ConnectionManagerTest {

    private static final String TEST_FILE = "target/test_connections.json";
    private ConnectionManager manager;

    @BeforeEach
    public void setup() {
        new File(TEST_FILE).delete();
        manager = new ConnectionManager(TEST_FILE);
    }

    @AfterEach
    public void tearDown() {
        new File(TEST_FILE).delete();
    }

    @Test
    @DisplayName("Debe cargar perfiles por defecto y tener uno predeterminado")
    public void testDefaultProfiles() {
        List<ConnectionProfile> profiles = manager.getProfiles();
        assertFalse(profiles.isEmpty(), "Debe contener conexiones por defecto");
        Optional<ConnectionProfile> def = manager.getDefaultProfile();
        assertTrue(def.isPresent(), "Debe existir una conexion predeterminada");
        assertTrue(def.get().isDefault());
    }

    @Test
    @DisplayName("Debe permitir agregar y actualizar conexiones")
    public void testSaveOrUpdateProfile() {
        ConnectionProfile newConn = new ConnectionProfile(
            "custom_1", "Servidor Producción 01", "tcp://db.empresa.com:8765", "dbadmin", "secret99", false
        );
        manager.saveOrUpdate(newConn);

        Optional<ConnectionProfile> found = manager.findById("custom_1");
        assertTrue(found.isPresent());
        assertEquals("Servidor Producción 01", found.get().getName());
        assertEquals("db.empresa.com", found.get().getHost());
        assertEquals(8765, found.get().getPort());

        // Editar
        found.get().setName("Servidor Producción 01 Modificado");
        manager.saveOrUpdate(found.get());

        Optional<ConnectionProfile> updated = manager.findById("custom_1");
        assertTrue(updated.isPresent());
        assertEquals("Servidor Producción 01 Modificado", updated.get().getName());
    }

    @Test
    @DisplayName("Debe permitir cambiar la conexion predeterminada")
    public void testChangeDefaultProfile() {
        ConnectionProfile p2 = new ConnectionProfile(
            "conn_test_2", "Nodo Secundario", "tcp://10.0.0.5:9091", "user2", "pass2", false
        );
        manager.saveOrUpdate(p2);

        manager.setDefault("conn_test_2");
        Optional<ConnectionProfile> def = manager.getDefaultProfile();
        assertTrue(def.isPresent());
        assertEquals("conn_test_2", def.get().getId());
        assertTrue(def.get().isDefault());
    }

    @Test
    @DisplayName("Debe permitir eliminar conexiones y mantener una predeterminada valida")
    public void testDeleteProfile() {
        ConnectionProfile p = new ConnectionProfile(
            "conn_temp", "Temporal", "tcp://127.0.0.1:9099", "admin", "admin", false
        );
        manager.saveOrUpdate(p);
        assertTrue(manager.findById("conn_temp").isPresent());

        boolean deleted = manager.delete("conn_temp");
        assertTrue(deleted);
        assertFalse(manager.findById("conn_temp").isPresent());
    }
}

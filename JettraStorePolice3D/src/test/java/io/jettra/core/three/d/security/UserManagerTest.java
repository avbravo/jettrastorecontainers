package io.jettra.core.three.d.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class UserManagerTest {

    private static final String TEST_FILE = "target/test_users.json";
    private UserManager manager;

    @BeforeEach
    public void setup() {
        new File(TEST_FILE).delete();
        manager = new UserManager(TEST_FILE);
    }

    @AfterEach
    public void tearDown() {
        new File(TEST_FILE).delete();
    }

    @Test
    @DisplayName("Debe inicializar usuarios predeterminados con roles y permisos")
    public void testDefaultUsers() {
        List<JettraUser> users = manager.getUsers();
        assertFalse(users.isEmpty(), "Deben existir usuarios predeterminados");

        Optional<JettraUser> adminOpt = manager.findByUsername("admin");
        assertTrue(adminOpt.isPresent());
        JettraUser admin = adminOpt.get();
        assertEquals(JettraUser.GlobalRole.ADMIN, admin.getGlobalRole());
        assertEquals(JettraDatabaseRole.ADMIN, admin.getRoleForDatabase("example_factura_db"));
    }

    @Test
    @DisplayName("Debe permitir crear, editar y eliminar usuarios")
    public void testCrudUser() {
        JettraUser newUser = new JettraUser("analista_bi", "biPass123", "Analista de Inteligencia de Negocios", JettraUser.GlobalRole.ANALYST);
        newUser.setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_ONLY);
        newUser.setRoleForDatabase("samples_ambiental_db", JettraDatabaseRole.READ_WRITE);

        manager.saveOrUpdate(newUser);

        Optional<JettraUser> found = manager.findByUsername("analista_bi");
        assertTrue(found.isPresent());
        assertEquals("analista_bi", found.get().getUsername());
        assertEquals(JettraDatabaseRole.READ_ONLY, found.get().getRoleForDatabase("example_factura_db"));
        assertEquals(JettraDatabaseRole.READ_WRITE, found.get().getRoleForDatabase("samples_ambiental_db"));

        // Editar rol
        found.get().setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_WRITE);
        manager.saveOrUpdate(found.get());

        Optional<JettraUser> updated = manager.findByUsername("analista_bi");
        assertTrue(updated.isPresent());
        assertEquals(JettraDatabaseRole.READ_WRITE, updated.get().getRoleForDatabase("example_factura_db"));

        // Eliminar
        boolean deleted = manager.delete("analista_bi");
        assertTrue(deleted);
        assertFalse(manager.findByUsername("analista_bi").isPresent());
    }

    @Test
    @DisplayName("No debe permitir eliminar el usuario root admin")
    public void testProtectRootAdmin() {
        boolean deleted = manager.delete("admin");
        assertFalse(deleted, "No se debe poder eliminar el superadmin");
        assertTrue(manager.findByUsername("admin").isPresent());
    }
}

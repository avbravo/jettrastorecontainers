package io.jettra.store.explorer;

import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Utilidad de limpieza para las bases de datos y archivos temporales de prueba de JettraStoreExplorer.
 * Elimina automáticamente todas las bases terminadas en _test_db y las muestras temporales al finalizar el test.
 */
public final class JettraExplorerTestCleanup {

    public static final List<String> KNOWN_TEST_DATABASES = List.of(
        "example_factura_db",
        "samples_hostipal_db",
        "samples_ambiental_db",
        "system_metadata_db",
        "idx_db",
        "idx_test_db",
        "ref_db",
        "ref_test_db",
        "gestacional",
        "gestacional_test_db",
        "test_drop_db",
        "drop_test_db",
        "default_db",
        "default_test_db",
        "dist_test_db",
        "shell_calc_db",
        "shell_calc_test_db",
        "test_billing_db",
        "billing_test_db",
        "primary_crm_db",
        "primary_crm_test_db",
        "primary_erp_db",
        "primary_erp_test_db"
    );

    private JettraExplorerTestCleanup() {}

    public static void cleanAll(JettraStorePlugin plugin) {
        if (plugin != null) {
            try {
                plugin.uninstallAllExampleDatabases();
            } catch (Exception ignored) {}
            for (String db : KNOWN_TEST_DATABASES) {
                try {
                    plugin.deleteDatabase(db);
                } catch (Exception ignored) {}
            }
            // Eliminar también cualquier base remanente con sufijo _test_db en el catálogo
            try {
                for (String db : plugin.getDatabaseNames()) {
                    if (db.endsWith("_test_db")) {
                        plugin.deleteDatabase(db);
                    }
                }
            } catch (Exception ignored) {}
            try {
                plugin.getDatabasesCatalog().clear();
            } catch (Exception ignored) {}
        }
        cleanStorageDirectories();
    }

    public static void cleanStorageDirectories() {
        try {
            JettraStoreConfig cfg = JettraStoreConfig.load();
            if (cfg != null && cfg.getStoragePath() != null && !cfg.getStoragePath().isBlank()) {
                cleanDirectory(Path.of(cfg.getStoragePath()));
            }
        } catch (Exception ignored) {}
    }

    public static void cleanDirectory(Path dir) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) return;
        for (String db : KNOWN_TEST_DATABASES) {
            deleteDatabaseArtifacts(dir, db);
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(entry -> {
                String fn = entry.getFileName().toString();
                if (fn.endsWith("_test_db") || fn.endsWith("_test_db_meta.json")
                        || fn.endsWith("_test_db.jettra") || fn.endsWith("_test_db_sstable.jettra")
                        || fn.endsWith("_test_db_wal.jettra")) {
                    deleteRecursively(entry);
                }
            });
        } catch (Exception ignored) {}
    }

    private static void deleteDatabaseArtifacts(Path dir, String db) {
        try {
            Files.deleteIfExists(dir.resolve(db + "_meta.json"));
            Files.deleteIfExists(dir.resolve(db + "_sstable.jettra"));
            Files.deleteIfExists(dir.resolve(db + ".jettra"));
            Files.deleteIfExists(dir.resolve(db + "_wal.jettra"));
            Files.deleteIfExists(dir.resolve(db + "_backup.snap"));
            deleteRecursively(dir.resolve(db));
        } catch (Exception ignored) {}
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {}
                        });
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
}

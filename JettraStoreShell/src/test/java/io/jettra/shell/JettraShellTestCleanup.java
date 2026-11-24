package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraStoreConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Utilidad de limpieza para las bases de datos de prueba generadas durante los tests de JettraStoreShell.
 * Elimina automáticamente todas las bases de datos terminadas en _test_db y las bases de prueba conocidas.
 */
public final class JettraShellTestCleanup {

    public static final List<String> KNOWN_TEST_DATABASES = List.of(
        "example_factura_db",
        "samples_hospital_db",
        "samples_hostipal_db",
        "samples_ambiental_db",
        "test_memory_engine_db",
        "test_meter_db",
        "test_multiuser_db",
        "system_metadata_db",
        "idx_db",
        "idx_test_db",
        "ref_db",
        "ref_test_db",
        "gestacional",
        "gestacional_test_db",
        "test_drop_db",
        "test_drop_db2",
        "drop_test_db",
        "drop2_test_db",
        "default_db",
        "default_test_db",
        "dist_test_db",
        "shell_calc_db",
        "shell_calc_test_db",
        "ventas_primary_db",
        "ventas_primary_test_db",
        "inventario_primary_db",
        "inventario_primary_test_db"
    );

    private JettraShellTestCleanup() {}

    public static void cleanAll(JettraClient client) {
        if (client != null) {
            for (String db : KNOWN_TEST_DATABASES) {
                try {
                    client.dropDatabase(db);
                } catch (Exception ignored) {}
            }
        }
        cleanStorageDirectories();
    }

    public static void cleanStorageDirectories() {
        try {
            JettraStoreConfig cfg = JettraStoreConfig.load();
            if (cfg != null && cfg.getStoragePath() != null && !cfg.getStoragePath().isBlank()) {
                cleanDirectory(Path.of(cfg.getStoragePath()));
            }
            if (cfg != null && cfg.getConfiguredStoragePath() != null && !cfg.getConfiguredStoragePath().isBlank()) {
                String raw = cfg.getConfiguredStoragePath();
                if (raw.startsWith("~/")) {
                    raw = System.getProperty("user.home") + raw.substring(1);
                }
                cleanDirectory(Path.of(raw));
            }
        } catch (Exception ignored) {}
        cleanDirectory(Path.of("/jettra/data"));
        cleanDirectory(Path.of(System.getProperty("user.home"), "jettra", "data"));
        cleanDirectory(Path.of(System.getProperty("user.home"), "jettra-storage-01", "data"));
        cleanDirectory(Path.of(System.getProperty("user.home"), "jettra-storage-02", "data"));
        cleanDirectory(Path.of(System.getProperty("user.home"), "jettra-storage-03", "data"));
        cleanDirectory(Path.of(System.getProperty("user.home"), "jettra-storage-04", "data"));
        cleanDirectory(Path.of("jettra/data"));
        cleanDirectory(Path.of("./jettra/data"));
        cleanDirectory(Path.of("../jettra/data"));
        cleanDirectory(Path.of("target/test-storage"));
    }

    public static void cleanDirectory(Path dir) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) return;
        for (String db : KNOWN_TEST_DATABASES) {
            deleteDatabaseArtifacts(dir, db);
        }
        // También eliminar cualquier base de datos con sufijo o prefijo de prueba en el directorio
        try (var stream = Files.list(dir)) {
            stream.forEach(entry -> {
                String fn = entry.getFileName().toString();
                if (fn.startsWith("test_")
                        || fn.contains("_test_db")
                        || fn.startsWith("rf")
                        || fn.startsWith("db_modals")
                        || fn.startsWith("databs")
                        || fn.startsWith("nods")
                        || fn.startsWith("mydb")
                        || fn.startsWith("driver_explorer_db")
                        || fn.startsWith("billing_test")
                        || fn.startsWith("examplese")
                        || fn.startsWith("example_factura")
                        || fn.startsWith("samples_")
                        || fn.startsWith("idx_")
                        || fn.startsWith("ref_")
                        || fn.startsWith("shell_calc")
                        || fn.startsWith("dist_")
                        || fn.startsWith("ventas_")
                        || fn.startsWith("inventario_")
                        || fn.startsWith("gestacional")
                        || fn.startsWith("default_")) {
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
            Files.deleteIfExists(dir.resolve(db + "_indexes.json"));
            Files.deleteIfExists(dir.resolve(db + "_sequences.json"));
            Files.deleteIfExists(dir.resolve(db + "_indexes.json.tmp"));
            Files.deleteIfExists(dir.resolve(db + "_sequences.json.tmp"));
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

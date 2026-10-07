package io.jettra.store;

import io.jettra.store.core.JettraStoreConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class JettraTestCleanup {

    private JettraTestCleanup() {}

    /**
     * Elimina las bases de datos creadas en /jettra/node-1 y elimina la subcarpeta node-1,
     * además de limpiar las bases de datos de prueba en los directorios de persistencia.
     */
    public static void cleanUpNode1AndTestDatabases() {
        // 1. Elimina las bases de datos creadas en /jettra/node-1 y elimina la subcarpeta node-1
        List<Path> node1Dirs = List.of(
            Path.of("/jettra/node-1"),
            Path.of(System.getProperty("user.home"), "jettra", "node-1"),
            Path.of("jettra/node-1"),
            Path.of("./jettra/node-1"),
            Path.of("/jettra/node-01"),
            Path.of(System.getProperty("user.home"), "jettra", "node-01"),
            Path.of("jettra/node-01"),
            Path.of("./jettra/node-01")
        );
        for (Path p : node1Dirs) {
            deleteRecursively(p);
        }

        // 2. Eliminar bases de datos de test en storage path configurado
        try {
            JettraStoreConfig cfg = JettraStoreConfig.load();
            if (cfg != null && cfg.getStoragePath() != null) {
                cleanTestDatabases(Path.of(cfg.getStoragePath()));
            }
        } catch (Exception ignored) {}

        cleanTestDatabases(Path.of("/jettra/data"));
        cleanTestDatabases(Path.of(System.getProperty("user.home"), "jettra", "data"));
        cleanTestDatabases(Path.of("./data/jettra"));
        cleanTestDatabases(Path.of("data/jettra"));
    }

    public static void cleanTestDatabases(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        List<String> dbs = List.of(
            "test_backup_db",
            "verify_storage_db",
            "test_calc_db",
            "replicated_test_db",
            "migracion_completa_db",
            "test_node1_db",
            "test_meter_db",
            "test_multiuser_db",
            "test_stream_db",
            "test_memory_engine_db"
        );
        for (String db : dbs) {
            try {
                Files.deleteIfExists(dir.resolve(db + "_meta.json"));
                Files.deleteIfExists(dir.resolve(db + "_sstable.jettra"));
                Files.deleteIfExists(dir.resolve(db + ".jettra"));
                Files.deleteIfExists(dir.resolve(db + "_wal.jettra"));
                Path dbSub = dir.resolve(db);
                deleteRecursively(dbSub);
            } catch (Exception ignored) {}
        }
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                p.toFile().setWritable(true);
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {}
                        });
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
}

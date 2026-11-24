package io.jettra.store.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilidad de limpieza para las bases de datos, nodos de prueba y archivos temporales de JettraStoreExplorer.
 * Elimina automáticamente todas las bases terminadas en _test_db, muestras temporales y nodos de prueba al finalizar el test.
 */
public final class JettraExplorerTestCleanup {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final Set<String> KNOWN_TEST_CLUSTER_NODES = Set.of(
        "peer-dynamic-1",
        "peer-dynamic-1-replica",
        "node-lifecycle-test",
        "node-lifecycle-test-secondary",
        "node-heartbeat-live",
        "node-heartbeat-live-secondary",
        "node-test-dk",
        "node-test-dk-secondary"
    );

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
        cleanAll(plugin, null);
    }

    public static void cleanAll(JettraStorePlugin plugin, Path tempStorageDir) {
        if (plugin != null) {
            try {
                plugin.uninstallAllExampleDatabases();
            } catch (Exception ignored) {}
            for (String db : KNOWN_TEST_DATABASES) {
                try {
                    plugin.deleteDatabase(db);
                } catch (Exception ignored) {}
            }
            // Eliminar cualquier base remanente generada durante las pruebas
            try {
                List<String> remainingDbs = new ArrayList<>(plugin.getDatabaseNames());
                for (String db : remainingDbs) {
                    try {
                        plugin.deleteDatabase(db);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            try {
                plugin.getDatabasesCatalog().clear();
            } catch (Exception ignored) {}
        }
        cleanTestClusterNodes(plugin);
        cleanStorageDirectories();
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            deleteRecursively(tempStorageDir);
        }
    }

    public static void cleanTestClusterNodes(JettraStorePlugin plugin) {
        if (plugin != null) {
            for (String nodeId : List.of("peer-dynamic-1", "node-lifecycle-test", "node-heartbeat-live", "node-test-dk")) {
                try {
                    plugin.removeNode(nodeId);
                } catch (Exception ignored) {}
            }
        }
        cleanClusterNodesJsonFile(Path.of("memory/cluster_nodes.json"));
        String customNodesFile = System.getProperty("cluster.nodes.file");
        if (customNodesFile != null && !customNodesFile.isBlank()) {
            cleanClusterNodesJsonFile(Path.of(customNodesFile));
        }
    }

    public static void cleanClusterNodesJsonFile(Path jsonFile) {
        if (jsonFile == null || !Files.exists(jsonFile)) return;
        try {
            List<Map<String, Object>> nodes = JSON.readValue(jsonFile.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            if (nodes == null) return;
            List<Map<String, Object>> filtered = new ArrayList<>();
            boolean changed = false;
            for (Map<String, Object> node : nodes) {
                String id = node.get("id") != null ? String.valueOf(node.get("id")).trim() : "";
                String name = node.get("name") != null ? String.valueOf(node.get("name")).trim() : "";
                if (KNOWN_TEST_CLUSTER_NODES.contains(id) || KNOWN_TEST_CLUSTER_NODES.contains(name)) {
                    changed = true;
                } else {
                    filtered.add(node);
                }
            }
            if (changed) {
                JSON.writeValue(jsonFile.toFile(), filtered);
            }
        } catch (Exception ignored) {}
    }

    public static void cleanStorageDirectories() {
        try {
            JettraStoreConfig cfg = JettraStoreConfig.load();
            if (cfg != null && cfg.getStoragePath() != null && !cfg.getStoragePath().isBlank()) {
                cleanDirectory(Path.of(cfg.getStoragePath()));
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
                        || fn.startsWith("samples_")) {
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

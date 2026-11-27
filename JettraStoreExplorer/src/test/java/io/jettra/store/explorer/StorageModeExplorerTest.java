package io.jettra.store.explorer;

import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.core.StorageMode;
import io.jettra.store.explorer.pages.databases.DatabasesFluxView;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Pruebas JettraTest para RF-38 en JettraStoreExplorer:
 * - Selector de Storage Mode al crear BD (OFF-HEAP predeterminado) y modal de edición en DatabasesFluxView
 * - JettraStorePlugin createDatabase / updateDatabaseStorageMode
 * - Consola JettraStoreShellExecutor (CREATE DATABASE, EDIT DATABASE, STORAGE_MODE, HELP)
 */
public class StorageModeExplorerTest {

    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;
    private static Path tempTestDir;
    private static String prevStoragePath;
    private static String prevStateDir;
    private static String prevClusterNodesFile;
    private static String prevConnFile;

    @BeforeAll
    public static void backupConfiguredState() throws Exception {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
        prevStoragePath = System.getProperty("jettra.storage.path");
        prevStateDir = System.getProperty("jettra.cluster_state_config_dir");
        prevClusterNodesFile = System.getProperty("jettra.explorer.cluster.nodes.file");
        prevConnFile = System.getProperty("jettra.connection.config.file");

        tempTestDir = Files.createTempDirectory("jettra-rf38-explorer-");
        System.setProperty("jettra.storage.path", tempTestDir.resolve("data").toAbsolutePath().toString());
        System.setProperty("jettra.cluster_state_config_dir", tempTestDir.resolve("state").toAbsolutePath().toString());
        System.setProperty("jettra.explorer.cluster.nodes.file",
                tempTestDir.resolve("cluster_nodes.json").toAbsolutePath().toString());
        System.setProperty("jettra.connection.config.file",
                tempTestDir.resolve("conecction.config").toAbsolutePath().toString());
        JettraStorePlugin.getInstance().reloadRuntimeConfiguration();
    }

    @AfterAll
    public static void restoreConfiguredState() {
        try {
            App.stopAllApplications();
            JettraStoreServer.stopAllActiveServers();
            JettraAssert.assertEquals(0, App.getActiveApplicationCount());
            JettraAssert.assertEquals(0, JettraStoreServer.getActiveServerCount());
            JettraAssert.assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        } finally {
            restoreProp("jettra.storage.path", prevStoragePath);
            restoreProp("jettra.cluster_state_config_dir", prevStateDir);
            restoreProp("jettra.explorer.cluster.nodes.file", prevClusterNodesFile);
            restoreProp("jettra.connection.config.file", prevConnFile);
            try {
                JettraStorePlugin.getInstance().reloadRuntimeConfiguration();
            } catch (Exception ignored) {
            }
            deleteDir(tempTestDir);
            if (suiteStateBackup != null) {
                suiteStateBackup.close();
                suiteStateBackup = null;
            }
        }
    }

    private static void restoreProp(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("RF-38: DatabasesFluxView muestra los 3 Storage Mode (OFF-HEAP predeterminado) y diálogo de edición")
    public void testDatabasesFluxViewRendersStorageModeControlsAndModal() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String testDb = "rf38_explorer_ui_db";
        try {
            JettraAssert.assertTrue(plugin.createDatabase(testDb, "Base prueba RF-38", StorageMode.DISK_MEMORY));
            JettraAssert.assertEquals(StorageMode.DISK_MEMORY, plugin.getDatabaseStorageMode(testDb));

            DatabasesFluxView view = new DatabasesFluxView(testDb, null, null, null, null,
                    false, false, null, null, null, testDb, true, null);
            io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();
            String html = renderer.render(view.build(), "Bases de Datos");

            JettraAssert.assertTrue(html.contains("name=\"storageMode\""));
            JettraAssert.assertTrue(html.contains("JVM-RAM"));
            JettraAssert.assertTrue(html.contains("DISK-MEMORY"));
            JettraAssert.assertTrue(html.contains("OFF-HEAP (Integración Nativa JettraStore-OffHeap — Predeterminado)"));
            JettraAssert.assertTrue(html.contains("Editar Storage Mode"));
            JettraAssert.assertTrue(html.contains("id=\"editDatabaseModal\""));
            JettraAssert.assertTrue(html.contains("value=\"edit_db\""));
        } finally {
            plugin.deleteDatabase(testDb);
        }
    }

    @Test
    @DisplayName("RF-38: JettraStoreShellExecutor soporta CREATE/EDIT DATABASE con StorageMode y HELP actualizado")
    public void testExplorerShellExecutorStorageModeCommandsAndHelp() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        JettraStoreShellExecutor exec = JettraStoreShellExecutor.getInstance();
        String dbName = "rf38_console_db";
        try {
            var helpRes = exec.execute(plugin, "example_factura_db", "Facturas", "HELP");
            JettraAssert.assertTrue(helpRes.success());
            JettraAssert.assertTrue(helpRes.terminalOutput().contains("CREATE DATABASE <db> [STORAGE_MODE]"));
            JettraAssert.assertTrue(helpRes.terminalOutput().contains("EDIT DATABASE <db> <STORAGE_MODE>"));
            JettraAssert.assertTrue(helpRes.terminalOutput().contains("OFF-HEAP"));

            var createRes = exec.execute(plugin, "example_factura_db", "Facturas",
                    "CREATE DATABASE " + dbName + " DISK-MEMORY");
            JettraAssert.assertTrue(createRes.success());
            JettraAssert.assertTrue(createRes.terminalOutput().contains("DISK-MEMORY"));
            JettraAssert.assertEquals(StorageMode.DISK_MEMORY, plugin.getDatabaseStorageMode(dbName));

            var editRes = exec.execute(plugin, dbName, "DefaultBucket",
                    "EDIT DATABASE " + dbName + " OFF-HEAP");
            JettraAssert.assertTrue(editRes.success());
            JettraAssert.assertTrue(editRes.terminalOutput().contains("OFF-HEAP"));
            JettraAssert.assertEquals(StorageMode.OFF_HEAP, plugin.getDatabaseStorageMode(dbName));

            var invalidStorage = exec.execute(plugin, dbName, "DefaultBucket", "STORAGE_MODE BADMODE");
            JettraAssert.assertFalse(invalidStorage.success());
            JettraAssert.assertTrue(invalidStorage.terminalOutput().contains("OFF-HEAP"));
        } finally {
            plugin.deleteDatabase(dbName);
        }
    }
}

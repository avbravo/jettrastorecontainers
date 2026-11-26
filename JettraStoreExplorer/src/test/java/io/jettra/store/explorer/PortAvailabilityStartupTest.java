package io.jettra.store.explorer;

import io.jettra.server.core.JettraServer;
import io.jettra.server.core.config.ServerConfig;
import io.jettra.server.core.storage.StorageMode;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Pruebas automatizadas con JettraTest para RF-27:
 * Validación previa de disponibilidad de puerto al iniciar JettraStoreExplorer.
 */
public class PortAvailabilityStartupTest {

    private static Path tempStorageDir;
    private static Path tempMpConfig;
    private static Path tempClusterConfig;
    private static String prevStoragePath;
    private static String prevMpConfig;
    private static String prevClusterConfig;
    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void setupIsolatedEnvironment() throws Exception {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
        prevStoragePath = System.getProperty("jettra.storage.path");
        prevMpConfig = System.getProperty("jettra.explorer.config.file");
        prevClusterConfig = System.getProperty("cluster.config.path");

        tempStorageDir = Files.createTempDirectory("jettra_rf27_explorer_storage_");
        tempMpConfig = tempStorageDir.resolve("microprofile-config-rf27.properties");
        tempClusterConfig = tempStorageDir.resolve("cluster-rf27.config");

        Files.writeString(tempMpConfig,
                "server.host=127.0.0.1\n"
                        + "server.port=8085\n"
                        + "server.contextpath=/\n"
                        + "storage.mode=OFF_HEAP\n"
                        + "app.title=JettraStore Explorer\n"
                        + "cluster.multinode.active=off\n"
                        + "cluster.healthcheck.timeout.ms=60\n"
                        + "jettra.storage.path=" + tempStorageDir.toAbsolutePath() + "\n",
                StandardCharsets.UTF_8);

        Files.writeString(tempClusterConfig,
                "cluster.name=rf27-test-cluster\n"
                        + "cluster.multinode.active=off\n",
                StandardCharsets.UTF_8);

        System.setProperty("jettra.storage.path", tempStorageDir.toAbsolutePath().toString());
        System.setProperty("jettra.explorer.config.file", tempMpConfig.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", tempClusterConfig.toAbsolutePath().toString());
    }

    @AfterAll
    public static void teardownIsolatedEnvironment() {
        try {
            App.stopAllApplications();
            io.jettra.store.JettraStoreServer.stopAllActiveServers();
            JettraExplorerTestCleanup.cleanAll(JettraStorePlugin.getInstance(), tempStorageDir);
            JettraAssert.assertEquals(0, App.getActiveApplicationCount());
            JettraAssert.assertEquals(0, io.jettra.store.JettraStoreServer.getActiveServerCount());
            JettraAssert.assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        } finally {
            restoreProperty("jettra.storage.path", prevStoragePath);
            restoreProperty("jettra.explorer.config.file", prevMpConfig);
            restoreProperty("cluster.config.path", prevClusterConfig);
            JettraExplorerTestCleanup.deleteRecursively(tempStorageDir);
            if (suiteStateBackup != null) {
                suiteStateBackup.close();
                suiteStateBackup = null;
            }
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static int findFreeLocalPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    @Test
    @DisplayName("RF-27: Detecta puerto ocupado mediante ServerSocket y detiene el arranque limpiamente sin BindException")
    public void testOccupiedPortDetectionAbortsStartupCleanly() throws Exception {
        try (ServerSocket occupiedSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int occupiedPort = occupiedSocket.getLocalPort();

            JettraAssert.assertFalse(App.isPortAvailable("127.0.0.1", occupiedPort),
                    "isPortAvailable debe devolver false cuando el puerto está ocupado por otro socket");

            App.PortAlreadyInUseException thrown = null;
            try {
                App.startApplication(occupiedPort, StorageMode.OFF_HEAP);
            } catch (App.PortAlreadyInUseException ex) {
                thrown = ex;
            }

            JettraAssert.assertNotNull(thrown,
                    "startApplication debe rechazar un puerto ocupado con PortAlreadyInUseException antes de levantar JettraServer");
            String msg = thrown.getMessage();
            JettraAssert.assertTrue(
                    msg != null && msg.contains("Error:") && msg.contains(String.valueOf(occupiedPort))
                            && msg.contains("ya está en uso"),
                    "El mensaje de conflicto debe ser claro e incluir el puerto ocupado: " + msg);
            JettraAssert.assertFalse(msg.contains("BindException"),
                    "El mensaje al usuario no debe exponer una excepción genérica BindException: " + msg);
        }
    }

    @Test
    @DisplayName("RF-27: Puerto libre pasa la verificación (SO_REUSEADDR + try-with-resources) y permite iniciar JettraStoreExplorer")
    public void testFreePortPassesValidationAndStartsServer() throws Exception {
        int freePort = findFreeLocalPort();

        // Verificar repetidamente que la sonda no deja bloqueado el puerto
        JettraAssert.assertTrue(App.isPortAvailable("127.0.0.1", freePort),
                "isPortAvailable debe devolver true para puerto libre");
        JettraAssert.assertTrue(App.isPortAvailable("127.0.0.1", freePort),
                "El socket de prueba debe cerrarse de inmediato para permitir reutilización sin falsos bloqueos");

        JettraServer server = null;
        try {
            server = App.startApplication(freePort, StorageMode.OFF_HEAP);
            JettraAssert.assertNotNull(server, "startApplication debe devolver instancia activa de JettraServer");
            JettraAssert.assertTrue(server.isRunning(), "El servidor web debe quedar en ejecución sobre el puerto libre");

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + freePort + "/login"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, response.statusCode(),
                    "JettraStoreExplorer debe responder HTTP 200 en /login tras pasar la validación de puerto");
        } finally {
            if (server != null) {
                App.stopApplication(server);
            }
        }

        // Tras detener la instancia, el puerto debe quedar nuevamente disponible
        JettraAssert.assertTrue(App.isPortAvailable("127.0.0.1", freePort),
                "El puerto debe liberarse correctamente al detener JettraStoreExplorer");
    }

    @Test
    @DisplayName("RF-27: Resuelve host/puerto por argumentos CLI y propiedades, y App.main sale con código 1 si el puerto está ocupado")
    public void testMainExitCodeAndMessageWhenConfiguredPortOccupied() throws Exception {
        try (ServerSocket occupiedSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int occupiedPort = occupiedSocket.getLocalPort();

            ServerConfig resolved = App.resolveStartupConfig(
                    new String[]{"--server.host=127.0.0.1", "--server.port=" + occupiedPort});
            JettraAssert.assertEquals("127.0.0.1", resolved.getHost(),
                    "resolveStartupConfig debe respetar --server.host");
            JettraAssert.assertEquals(occupiedPort, resolved.getPort(),
                    "resolveStartupConfig debe respetar --server.port");

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");

            Process proc = new ProcessBuilder(
                    javaBin,
                    "-Xms32m",
                    "-Xmx128m",
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Djettra.explorer.config.file=" + tempMpConfig.toAbsolutePath(),
                    "-Dcluster.config.path=" + tempClusterConfig.toAbsolutePath(),
                    "-cp", classpath,
                    App.class.getName(),
                    "--server.host=127.0.0.1",
                    "--server.port=" + occupiedPort)
                    .redirectErrorStream(true)
                    .start();

            boolean finished;
            String output;
            try {
                finished = proc.waitFor(15, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                }
                output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                if (proc.isAlive()) {
                    proc.destroyForcibly();
                }
            }

            JettraAssert.assertTrue(finished, "El proceso de JettraStoreExplorer debe terminar rápidamente ante puerto ocupado");
            JettraAssert.assertEquals(1, proc.exitValue(),
                    "App.main debe salir con código 1 cuando el puerto está ocupado. Salida: " + output);
            JettraAssert.assertTrue(
                    output.contains("El puerto " + occupiedPort + " ya está en uso"),
                    "La consola debe mostrar el mensaje claro de puerto en uso. Salida: " + output);
            JettraAssert.assertFalse(output.contains("java.net.BindException"),
                    "No debe imprimir stacktrace genérico de BindException. Salida: " + output);
        }
    }

    @Test
    @DisplayName("stopAllApplications detiene múltiples servidores Explorer activos y libera todos sus puertos")
    public void testStopAllApplicationsReleasesEveryOpenedPort() throws Exception {
        int portA = findFreeLocalPort();
        int portB = findFreeLocalPort();
        try {
            JettraServer srvA = App.startApplication(portA, StorageMode.OFF_HEAP);
            JettraServer srvB = App.startApplication(portB, StorageMode.OFF_HEAP);
            JettraAssert.assertTrue(srvA != null && srvA.isRunning());
            JettraAssert.assertTrue(srvB != null && srvB.isRunning());
            JettraAssert.assertTrue(App.getActiveApplicationCount() >= 2);
            JettraAssert.assertFalse(App.isPortAvailable("127.0.0.1", portA));
            JettraAssert.assertFalse(App.isPortAvailable("127.0.0.1", portB));
        } finally {
            App.stopAllApplications();
        }
        JettraAssert.assertEquals(0, App.getActiveApplicationCount());
        JettraAssert.assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", portA, 2000));
        JettraAssert.assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", portB, 2000));
    }
}

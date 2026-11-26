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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

/**
 * Pruebas automatizadas con JettraTest para RF-30:
 * Validación de bind en todas las interfaces (0.0.0.0:8085 por defecto),
 * sobreescritura configurable por CLI/propiedades y aceptación de accesos vía IP LAN / Host header.
 */
public class RemoteBindConfigTest {

    private static Path tempStorageDir;
    private static Path tempClusterConfig;
    private static String prevStoragePath;
    private static String prevMpConfig;
    private static String prevClusterConfig;
    private static String prevServerHost;
    private static String prevBindAddress;
    private static String prevServerPort;
    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void setup() throws Exception {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
        prevStoragePath = System.getProperty("jettra.storage.path");
        prevMpConfig = System.getProperty("jettra.explorer.config.file");
        prevClusterConfig = System.getProperty("cluster.config.path");
        prevServerHost = System.getProperty("server.host");
        prevBindAddress = System.getProperty("bindAddress");
        prevServerPort = System.getProperty("server.port");

        tempStorageDir = Files.createTempDirectory("jettra_rf30_explorer_");
        tempClusterConfig = tempStorageDir.resolve("cluster-rf30.config");
        Files.writeString(tempClusterConfig,
                "cluster.name=rf30-test-cluster\ncluster.multinode.active=off\n",
                StandardCharsets.UTF_8);

        System.setProperty("jettra.storage.path", tempStorageDir.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", tempClusterConfig.toAbsolutePath().toString());
        System.clearProperty("server.host");
        System.clearProperty("bindAddress");
        System.clearProperty("server.port");
    }

    @AfterAll
    public static void cleanup() {
        try {
            App.stopAllApplications();
            io.jettra.store.JettraStoreServer.stopAllActiveServers();
            JettraExplorerTestCleanup.cleanAll(JettraStorePlugin.getInstance(), tempStorageDir);
            JettraAssert.assertEquals(0, App.getActiveApplicationCount());
            JettraAssert.assertEquals(0, io.jettra.store.JettraStoreServer.getActiveServerCount());
            JettraAssert.assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        } finally {
            restoreProp("jettra.storage.path", prevStoragePath);
            restoreProp("jettra.explorer.config.file", prevMpConfig);
            restoreProp("cluster.config.path", prevClusterConfig);
            restoreProp("server.host", prevServerHost);
            restoreProp("bindAddress", prevBindAddress);
            restoreProp("server.port", prevServerPort);
            JettraExplorerTestCleanup.deleteRecursively(tempStorageDir);
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

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))) {
            return ss.getLocalPort();
        }
    }

    private static String findNonLoopbackIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Test
    @DisplayName("RF-30: Configuración por defecto resuelve 0.0.0.0 y puerto 8085 sin quedar fijada en 127.0.0.1")
    public void testDefaultBindHostIsWildcardAndPort8085() throws Exception {
        Path emptyProps = tempStorageDir.resolve("empty-explorer.properties");
        Files.writeString(emptyProps, "app.title=JettraStore Explorer\n", StandardCharsets.UTF_8);

        System.setProperty("jettra.explorer.config.file", emptyProps.toAbsolutePath().toString());
        System.clearProperty("server.host");
        System.clearProperty("bindAddress");
        System.clearProperty("server.port");

        ServerConfig resolved = App.resolveStartupConfig(new String[0]);
        JettraAssert.assertEquals("0.0.0.0", resolved.getHost(),
                "Por defecto JettraStoreExplorer debe enlazar en 0.0.0.0");
        JettraAssert.assertEquals(8085, resolved.getPort(),
                "Por defecto JettraStoreExplorer debe usar el puerto 8085");
        JettraAssert.assertTrue(resolved.isCorsEnabled(),
                "CORS debe permanecer habilitado para clientes remotos");
    }

    @Test
    @DisplayName("RF-30: CLI y propiedades (-Dserver.host / --bindAddress) permiten configurar 0.0.0.0 sin ser sobrescritas a 127.0.0.1")
    public void testExplicitWildcardAndBindAddressOverridesArePreserved() throws Exception {
        Path localOnlyProps = tempStorageDir.resolve("local-only-explorer.properties");
        Files.writeString(localOnlyProps,
                "app.title=JettraStore Explorer\nserver.host=127.0.0.1\nserver.port=8085\n",
                StandardCharsets.UTF_8);
        System.setProperty("jettra.explorer.config.file", localOnlyProps.toAbsolutePath().toString());

        ServerConfig cliWildcard = App.resolveStartupConfig(new String[]{"--server.host=0.0.0.0", "--port=8095"});
        JettraAssert.assertEquals("0.0.0.0", cliWildcard.getHost(),
                "--server.host=0.0.0.0 no debe ser sobrescrito por server.host=127.0.0.1 del archivo");
        JettraAssert.assertEquals(8095, cliWildcard.getPort(),
                "--port=8095 debe aplicarse");

        ServerConfig cliBind = App.resolveStartupConfig(new String[]{"--bindAddress=*", "--server.port=8088"});
        JettraAssert.assertEquals("0.0.0.0", cliBind.getHost(),
                "--bindAddress=* debe normalizarse a 0.0.0.0");
        JettraAssert.assertEquals(8088, cliBind.getPort(),
                "--server.port=8088 debe aplicarse");

        System.setProperty("bindAddress", "0.0.0.0");
        try {
            ServerConfig sysPropConfig = App.resolveStartupConfig(new String[0]);
            JettraAssert.assertEquals("0.0.0.0", sysPropConfig.getHost(),
                    "-DbindAddress=0.0.0.0 debe prevalecer sobre server.host del archivo");
        } finally {
            System.clearProperty("bindAddress");
        }
    }

    @Test
    @DisplayName("RF-30: El servidor enlazado en 0.0.0.0 responde HTTP 200 y CORS ante cabecera Host de IP LAN y vía IP remota local")
    public void testWildcardBindAcceptsLanIpAndHostHeader() throws Exception {
        Path wildcardProps = tempStorageDir.resolve("wildcard-explorer.properties");
        Files.writeString(wildcardProps,
                "app.title=JettraStore Explorer\n"
                        + "server.host=0.0.0.0\n"
                        + "server.port=8085\n"
                        + "cluster.multinode.active=off\n"
                        + "cluster.healthcheck.timeout.ms=60\n",
                StandardCharsets.UTF_8);
        System.setProperty("jettra.explorer.config.file", wildcardProps.toAbsolutePath().toString());

        int freePort = findFreePort();
        JettraServer server = null;
        try {
            server = App.startApplication(freePort, StorageMode.OFF_HEAP);
            JettraAssert.assertEquals("0.0.0.0", server.getConfig().getHost(),
                    "JettraServer debe iniciar realmente enlazado en 0.0.0.0");

            String lanIp = findNonLoopbackIpv4();
            String connectHost = (lanIp != null) ? lanIp : "127.0.0.1";
            String hostHeader = connectHost + ":" + freePort;

            try (Socket sock = new Socket(connectHost, freePort)) {
                sock.setSoTimeout(3000);
                OutputStream out = sock.getOutputStream();
                String rawReq = "GET /login HTTP/1.1\r\n"
                        + "Host: " + hostHeader + "\r\n"
                        + "Origin: http://" + hostHeader + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(rawReq.getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String statusLine = br.readLine();
                StringBuilder headersAndBody = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    headersAndBody.append(line).append('\n');
                }
                String payload = headersAndBody.toString();
                JettraAssert.assertTrue(statusLine != null && statusLine.contains("200"),
                        "Debe responder HTTP 200 al acceder por " + hostHeader + ". Respuesta: " + statusLine);
                JettraAssert.assertTrue(payload.contains("Access-Control-Allow-Origin: *"),
                        "Debe incluir cabecera CORS Access-Control-Allow-Origin para peticiones LAN/remotas");
            }
        } finally {
            if (server != null) {
                App.stopApplication(server);
            }
        }
        JettraAssert.assertTrue(
                io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("0.0.0.0", freePort, 2000),
                "El puerto HTTP enlazado en 0.0.0.0 debe quedar libre al terminar el test");
    }
}

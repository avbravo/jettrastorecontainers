package io.jettra.store;

import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Pruebas automatizadas con JettraTest para validar la detección de puertos ocupados/libres,
 * el manejo de colisiones REST/Raft en JettraStoreServer y la parametrización flexible
 * de puertos en docker-compose-hub.yml (single-node y multi-node).
 */
public class PortBindingAndDockerConfigTest extends JettraStoreBaseTest {

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    private static int[] findTwoAdjacentFreePorts() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            int p1 = findFreePort();
            int p2 = p1 + 1;
            if (p2 <= 65535 && JettraStoreServer.isTcpPortAvailable("127.0.0.1", p2)) {
                return new int[]{p1, p2};
            }
        }
        throw new IllegalStateException("No se encontraron dos puertos TCP contiguos disponibles");
    }

    @Test
    @DisplayName("Detecta correctamente puertos TCP ocupados vs libres mediante isTcpPortAvailable")
    public void testTcpPortAvailabilityDetection() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = occupied.getLocalPort();
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", port),
                    "Debe reportar puerto ocupado mientras el ServerSocket esté enlazado");
        }

        int freePort = findFreePort();
        assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", freePort),
                "Debe reportar puerto libre cuando ningún proceso lo ocupa");
        assertTrue(JettraStoreServer.isTcpPortAvailable("127.0.0.1", freePort),
                "La comprobación con SO_REUSEADDR no debe dejar el puerto bloqueado");
    }

    @Test
    @DisplayName("Maneja colisión de puertos REST: conmuta al alternativo si está libre y falla limpiamente si ambos están ocupados")
    public void testRestPortConflictFallbackAndTotalConflict() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_port_conflict_");
        int[] restPair = findTwoAdjacentFreePorts();
        int primaryRest = restPair[0];
        int fallbackRest = restPair[1];
        int grpcPort = findFreePort();

        try {
            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-port-test");
            props.setProperty("cluster.node.role", "PRIMARY");
            props.setProperty("jettra.node.ip", "127.0.0.1");
            props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
            props.setProperty("jettra.network.rest.port", String.valueOf(primaryRest));
            props.setProperty("jettra.storage.path", tempDir.toAbsolutePath().toString());
            props.setProperty("jettra.storage.mode", "JVM_RAM");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("jettrapolice.active", "false");

            // 1. Ambos puertos ocupados (principal y alternativo) -> debe abortar con RuntimeException clara
            try (ServerSocket s1 = new ServerSocket(primaryRest);
                 ServerSocket s2 = new ServerSocket(fallbackRest)) {
                JettraStoreConfig cfg = new JettraStoreConfig(props);
                JettraStoreServer server = new JettraStoreServer(cfg);
                assertThrows(RuntimeException.class, server::start);
            }

            // 2. Solo puerto principal ocupado -> debe conmutar al puerto alternativo y luego liberarlo al detener
            JettraStoreServer startedServer = null;
            try (ServerSocket s1 = new ServerSocket(primaryRest)) {
                JettraStoreConfig cfg = new JettraStoreConfig(props);
                startedServer = new JettraStoreServer(cfg);
                startedServer.start();

                assertFalse(JettraStoreServer.isTcpPortAvailable("0.0.0.0", fallbackRest),
                        "El servidor REST debe quedar escuchando en el puerto alternativo " + fallbackRest);

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> health = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + fallbackRest + "/api/v1/health"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode(),
                        "El endpoint /api/v1/health debe responder 200 en el puerto alternativo");
            } finally {
                if (startedServer != null) {
                    startedServer.stop();
                }
            }
            assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", primaryRest, 2000),
                    "El puerto REST primario " + primaryRest + " debe quedar libre tras el test");
            assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", fallbackRest, 2000),
                    "El puerto REST alternativo " + fallbackRest + " debe quedar libre tras detener el servidor");
        } finally {
            JettraTestCleanup.deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Permite arrancar múltiples nodos JettraStore con puertos gRPC y REST no colisionantes")
    public void testMultiNodeDistinctPortsStartup() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_multinode_ports_");
        int grpc1 = findFreePort();
        int rest1 = findFreePort();
        int grpc2 = findFreePort();
        int rest2 = findFreePort();

        Path clusterFile = tempDir.resolve("cluster.config");
        Files.writeString(clusterFile,
                "cluster.name=port-test-cluster\n"
                        + "cluster.multinode.active=on\n"
                        + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=" + grpc1 + "\ncluster.node.1.rest.port=" + rest1 + "\n"
                        + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=" + grpc2 + "\ncluster.node.2.rest.port=" + rest2 + "\n",
                StandardCharsets.UTF_8);

        JettraStoreServer n1 = null;
        JettraStoreServer n2 = null;
        try {
            Properties p1 = new Properties();
            p1.setProperty("cluster.node.id", "node-01");
            p1.setProperty("cluster.node.role", "PRIMARY");
            p1.setProperty("jettra.node.ip", "127.0.0.1");
            p1.setProperty("jettra.network.grpc.port", String.valueOf(grpc1));
            p1.setProperty("jettra.network.rest.port", String.valueOf(rest1));
            p1.setProperty("jettra.storage.path", tempDir.resolve("n1").toAbsolutePath().toString());
            p1.setProperty("jettra.storage.mode", "JVM_RAM");
            p1.setProperty("cluster.multinode.active", "on");
            p1.setProperty("jettrapolice.active", "false");

            Properties p2 = new Properties();
            p2.setProperty("cluster.node.id", "node-02");
            p2.setProperty("cluster.node.role", "SECONDARY");
            p2.setProperty("jettra.node.ip", "127.0.0.1");
            p2.setProperty("jettra.network.grpc.port", String.valueOf(grpc2));
            p2.setProperty("jettra.network.rest.port", String.valueOf(rest2));
            p2.setProperty("jettra.storage.path", tempDir.resolve("n2").toAbsolutePath().toString());
            p2.setProperty("jettra.storage.mode", "JVM_RAM");
            p2.setProperty("cluster.multinode.active", "on");
            p2.setProperty("jettrapolice.active", "false");

            Properties clusterProps = new Properties();
            try (var in = Files.newInputStream(clusterFile)) {
                clusterProps.load(in);
            }

            n1 = new JettraStoreServer(new JettraStoreConfig(p1, clusterProps, p1));
            n1.setClusterConfigFilePath(clusterFile);
            n1.start();

            n2 = new JettraStoreServer(new JettraStoreConfig(p2, clusterProps, p2));
            n2.setClusterConfigFilePath(clusterFile);
            n2.start();

            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", grpc1), "node-01 gRPC debe estar enlazado");
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", rest1), "node-01 REST debe estar enlazado");
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", grpc2), "node-02 gRPC debe estar enlazado");
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", rest2), "node-02 REST debe estar enlazado");
        } finally {
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            JettraTestCleanup.deleteRecursively(tempDir);
        }
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", grpc1, 2000), "node-01 gRPC debe liberarse");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", rest1, 2000), "node-01 REST debe liberarse");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", grpc2, 2000), "node-02 gRPC debe liberarse");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", rest2, 2000), "node-02 REST debe liberarse");
    }

    @Test
    @DisplayName("stopAllActiveServers cierra todos los servidores y transportes activos y libera sus puertos")
    public void testStopAllActiveServersReleasesAllOpenedPorts() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_stop_all_ports_");
        int g1 = findFreePort();
        int r1 = findFreePort();
        int g2 = findFreePort();
        int r2 = findFreePort();
        int extraTransportPort = findFreePort();

        try {
            Properties p1 = new Properties();
            p1.setProperty("cluster.node.id", "node-stop-1");
            p1.setProperty("cluster.node.role", "PRIMARY");
            p1.setProperty("jettra.node.ip", "127.0.0.1");
            p1.setProperty("jettra.network.grpc.port", String.valueOf(g1));
            p1.setProperty("jettra.network.rest.port", String.valueOf(r1));
            p1.setProperty("jettra.storage.path", tempDir.resolve("s1").toAbsolutePath().toString());
            p1.setProperty("jettra.cluster_state_config_dir", tempDir.resolve("state1").toAbsolutePath().toString());
            p1.setProperty("jettra.storage.mode", "JVM_RAM");
            p1.setProperty("cluster.multinode.active", "off");
            p1.setProperty("jettrapolice.active", "false");

            Properties p2 = new Properties();
            p2.setProperty("cluster.node.id", "node-stop-2");
            p2.setProperty("cluster.node.role", "PRIMARY");
            p2.setProperty("jettra.node.ip", "127.0.0.1");
            p2.setProperty("jettra.network.grpc.port", String.valueOf(g2));
            p2.setProperty("jettra.network.rest.port", String.valueOf(r2));
            p2.setProperty("jettra.storage.path", tempDir.resolve("s2").toAbsolutePath().toString());
            p2.setProperty("jettra.cluster_state_config_dir", tempDir.resolve("state2").toAbsolutePath().toString());
            p2.setProperty("jettra.storage.mode", "JVM_RAM");
            p2.setProperty("cluster.multinode.active", "off");
            p2.setProperty("jettrapolice.active", "false");

            JettraStoreServer s1 = new JettraStoreServer(new JettraStoreConfig(p1, new Properties(), p1));
            JettraStoreServer s2 = new JettraStoreServer(new JettraStoreConfig(p2, new Properties(), p2));
            io.jettra.store.cluster.JettraClusterTransportServer extraTransport =
                    new io.jettra.store.cluster.JettraClusterTransportServer(extraTransportPort, s1);

            s1.start();
            s2.start();
            extraTransport.start();

            assertTrue(JettraStoreServer.getActiveServerCount() >= 2);
            assertTrue(io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount() >= 1);
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", r1));
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", r2));
            assertFalse(JettraStoreServer.isTcpPortAvailable("127.0.0.1", extraTransportPort));
        } finally {
            JettraStoreServer.stopAllActiveServers();
            JettraTestCleanup.deleteRecursively(tempDir);
        }

        assertEquals(0, JettraStoreServer.getActiveServerCount());
        assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r1, 2000));
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", r2, 2000));
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", extraTransportPort, 2000));
    }

    @Test
    @DisplayName("docker-compose-hub.yml parametriza puertos de host y contenedor sin colisión")
    public void testDockerComposeHubPortParameterization() throws Exception {
        Path composeHub = Path.of("docker-compose-hub.yml");
        assertTrue(Files.exists(composeHub), "Debe existir JettraStore/docker-compose-hub.yml");
        String yaml = Files.readString(composeHub, StandardCharsets.UTF_8);

        assertTrue(yaml.contains("JETTRA_HOST_GRPC_PORT"),
                "Debe parametrizar JETTRA_HOST_GRPC_PORT para evitar colisiones de puerto en el host");
        assertTrue(yaml.contains("JETTRA_HOST_REST_PORT"),
                "Debe parametrizar JETTRA_HOST_REST_PORT para evitar colisiones de puerto en el host");
        assertTrue(yaml.contains("JETTRA_CONTAINER_GRPC_PORT"),
                "Debe separar JETTRA_CONTAINER_GRPC_PORT como puerto interno del contenedor");
        assertTrue(yaml.contains("JETTRA_CONTAINER_REST_PORT"),
                "Debe separar JETTRA_CONTAINER_REST_PORT como puerto interno del contenedor");
        assertTrue(yaml.contains("JETTRA_DATA_VOLUME_NAME"),
                "Debe permitir nombrar volúmenes independientes para despliegues multi-nodo");
        assertTrue(yaml.contains("expose:"),
                "Debe declarar puertos internos explícitos en expose para comunicación interna de red Docker");
        assertTrue(yaml.contains("JETTRA_CONTAINER_NAME"),
                "Debe parametrizar JETTRA_CONTAINER_NAME para evitar conflictos de nombre en despliegues paralelos");
        assertTrue(yaml.contains("JETTRA_CLUSTER_PEERS"),
                "Debe permitir configurar pares del clúster en modo multi-nodo");
    }
}

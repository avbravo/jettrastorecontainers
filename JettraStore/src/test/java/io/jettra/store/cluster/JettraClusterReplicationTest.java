package io.jettra.store.cluster;

import io.jettra.store.JettraStoreBaseTest;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.Test;
import io.jettra.test.annotation.DisplayName;
import static io.jettra.test.core.JettraAssert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public class JettraClusterReplicationTest extends JettraStoreBaseTest {

    @Test
    @DisplayName("Debe replicar creación y borrado de base de datos a través de JettraClusterTransport")
    public void testReplicationDatabaseLifecycle() throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("jettra_repl_test");
        int testPort = 19091;

        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.node.id", "node-02");
        dbProps.setProperty("jettra.node.role", "SECONDARY");
        dbProps.setProperty("jettra.storage.path", tempDir.toString());
        dbProps.setProperty("jettra.network.grpc.port", String.valueOf(testPort));
        dbProps.setProperty("jettra.network.rest.port", "18082");
        dbProps.setProperty("cluster.multinode.active", "on");

        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.1.grpc.port", "19090");
        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(testPort));

        JettraStoreConfig secondaryConfig = new JettraStoreConfig(dbProps, clusterProps);
        JettraStoreServer secondaryServer = new JettraStoreServer(secondaryConfig);

        try (JettraClusterTransportServer transportServer = new JettraClusterTransportServer(testPort, secondaryServer)) {
            transportServer.start();
            Thread.sleep(100);

            // Crear cliente de replicación desde nodo primario apuntando al secundario
            ClusterNode peerNode2 = new ClusterNode("node-02", "127.0.0.1", testPort, ClusterNode.Role.SECONDARY);
            try (JettraClusterReplicationClient client = new JettraClusterReplicationClient("node-01", List.of(peerNode2))) {
                
                // 1. Replicar CREATE DATABASE
                boolean created = client.broadcastCreateDatabase("replicated_test_db");
                System.out.println("DEBUG 1 created: " + created);
                assertTrue(created);

                // Verificar que el servidor secundario creó la base de datos
                List<String> secondaryDbs = secondaryServer.listDatabaseNames();
                System.out.println("DEBUG 1 secondaryDbs: " + secondaryDbs);
                assertTrue(secondaryDbs.contains("replicated_test_db"));

                // 2. Replicar CREATE INDEX sobre colección clientes
                boolean indexCreated = client.broadcastCreateIndex("replicated_test_db", "clientes", "idx_cli_email", "email", "HASH", false);
                System.out.println("DEBUG 2 indexCreated: " + indexCreated);
                assertTrue(indexCreated);

                var secDb = secondaryServer.getOrCreateDatabaseInternal("replicated_test_db", false);
                assertNotNull(secDb);
                assertNotNull(secDb.getIndexManager().getIndex("idx_cli_email"));

                // 3. Replicar PUT DOCUMENT
                byte[] docBytes = "{\"nombre\":\"Carlos\",\"email\":\"carlos@example.com\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                boolean docCreated = client.broadcastPutDocument("replicated_test_db", "clientes", "cli_01", docBytes);
                System.out.println("DEBUG 3 docCreated: " + docCreated);
                assertTrue(docCreated);

                System.out.println("DEBUG 3 count: " + secDb.getDocumentEngine("clientes").count());
                assertEquals(1L, secDb.getDocumentEngine("clientes").count());
                var docFound = secDb.getDocumentEngine("clientes").findById("cli_01");
                assertNotNull(docFound);
                assertEquals("Carlos", docFound.get("nombre"));

                // 4. Verificar que en el nodo SECUNDARIO están bloqueadas las mutaciones directas (Read-Only)
                System.out.println("DEBUG 4 isReadOnlyNode: " + secDb.isReadOnlyNode());
                assertTrue(secDb.isReadOnlyNode());
                boolean mutationBlocked = false;
                try {
                    secDb.getDocumentEngine("clientes").insert("cli_02", java.util.Map.of("nombre", "Ilegal"));
                } catch (UnsupportedOperationException e) {
                    mutationBlocked = true;
                }
                System.out.println("DEBUG 4 mutationBlocked: " + mutationBlocked);
                assertTrue(mutationBlocked);

                // 5. Replicar DELETE DOCUMENT
                boolean docDeleted = client.broadcastDeleteDocument("replicated_test_db", "clientes", "cli_01");
                System.out.println("DEBUG 5 docDeleted: " + docDeleted);
                assertTrue(docDeleted);
                assertEquals(0L, secDb.getDocumentEngine("clientes").count());

                // 6. Replicar DROP DATABASE
                boolean dropped = client.broadcastDropDatabase("replicated_test_db");
                System.out.println("DEBUG 6 dropped: " + dropped);
                assertTrue(dropped);

                // Verificar que se eliminó
                secondaryDbs = secondaryServer.listDatabaseNames();
                assertFalse(secondaryDbs.contains("replicated_test_db"));
            }
        }
    }

    @Test
    @DisplayName("Debe migrar todos los registros y elementos con broadcastDistributeDatabase")
    public void testFullDatabaseDistributionWithRecords() throws IOException, InterruptedException {
        Path tempDirPrimary = Files.createTempDirectory("jettra_prim_dist");
        Path tempDirSecondary = Files.createTempDirectory("jettra_sec_dist");
        int testPort = 19095;

        // Servidor primario
        Properties primProps = new Properties();
        primProps.setProperty("jettra.node.id", "node-01");
        primProps.setProperty("jettra.node.role", "PRIMARY");
        primProps.setProperty("jettra.storage.path", tempDirPrimary.toString());
        primProps.setProperty("jettra.network.grpc.port", "19094");
        primProps.setProperty("jettra.network.rest.port", "18084");
        primProps.setProperty("cluster.multinode.active", "on");

        // Servidor secundario
        Properties secProps = new Properties();
        secProps.setProperty("jettra.node.id", "node-02");
        secProps.setProperty("jettra.node.role", "SECONDARY");
        secProps.setProperty("jettra.storage.path", tempDirSecondary.toString());
        secProps.setProperty("jettra.network.grpc.port", String.valueOf(testPort));
        secProps.setProperty("jettra.network.rest.port", "18085");
        secProps.setProperty("cluster.multinode.active", "on");

        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.1.grpc.port", "19094");
        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(testPort));

        JettraStoreConfig primConfig = new JettraStoreConfig(primProps, clusterProps);
        JettraStoreServer primServer = new JettraStoreServer(primConfig);

        JettraStoreConfig secConfig = new JettraStoreConfig(secProps, clusterProps);
        JettraStoreServer secServer = new JettraStoreServer(secConfig);

        try (JettraClusterTransportServer transportServer = new JettraClusterTransportServer(testPort, secServer)) {
            transportServer.start();
            Thread.sleep(100);

            // Crear y poblar base de datos en PRIMARIO con todos los elementos y modelos
            var primDb = primServer.getOrCreateDatabaseInternal("migracion_completa_db", false);
            for (int i = 1; i <= 25; i++) {
                primDb.getDocumentEngine("facturas").insert("fac_" + i, java.util.Map.of("total", 100.0 * i, "folio", "F-" + i));
            }
            for (int i = 1; i <= 10; i++) {
                primDb.getDocumentEngine("clientes").insert("cli_" + i, java.util.Map.of("nombre", "Cliente " + i));
            }
            primDb.getKeyValueEngine("cache_config").put("version", "2.0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            primDb.getTimeSeriesEngine("cpu_metric").record(1000L, 85.5);
            primDb.getGeospatialEngine("poi").insertPoint("pt1", 8.98, -79.52);
            primDb.getVectorEngine("embeddings", 3).index("vec1", new float[]{1.0f, 2.0f, 3.0f});
            primDb.getGraphEngine("social").addEdge("u1", "u2", "FRIEND", java.util.Map.of());
            primDb.getColumnarEngine("analytics").appendRow(java.util.Map.of("views", 100.0, "page", "home"));
            primDb.getIndexManager().createIndex("facturas", "idx_fac_folio", "folio", "HASH", false, primDb.getDocumentEngine("facturas"));
            primDb.getRecordsEngine("test_records", TestRecord.class).persist("r1", new TestRecord("r1", "Admin", 99));

            primDb.flushMemTable();
            primDb.saveToDisk();

            assertEquals(25L, primDb.getDocumentEngine("facturas").count());
            assertEquals(10L, primDb.getDocumentEngine("clientes").count());

            // Distribuir al secundario
            ClusterNode peerNode2 = new ClusterNode("node-02", "127.0.0.1", testPort, ClusterNode.Role.SECONDARY);
            try (JettraClusterReplicationClient client = new JettraClusterReplicationClient("node-01", List.of(peerNode2))) {
                byte[] snapshot = primServer.getDatabaseSnapshotBytes("migracion_completa_db");
                System.out.printf("DEBUG snapshot length: %d bytes%n", snapshot.length);
                assertTrue(snapshot.length > 0);

                boolean distOk = client.broadcastDistributeDatabase("migracion_completa_db", snapshot);
                System.out.println("DEBUG distOk: " + distOk);
                assertTrue(distOk);

                // Verificar en secundario copia exacta de todos los elementos
                var secDb = secServer.getOrCreateDatabaseInternal("migracion_completa_db", false);
                assertNotNull(secDb);

                long secFacturas = secDb.getDocumentEngine("facturas").count();
                long secClientes = secDb.getDocumentEngine("clientes").count();
                System.out.printf("DEBUG secFacturas: %d, secClientes: %d%n", secFacturas, secClientes);
                assertEquals(25L, secFacturas);
                assertEquals(10L, secClientes);

                var doc1 = secDb.getDocumentEngine("facturas").findById("fac_1");
                assertNotNull(doc1);
                assertEquals("F-1", doc1.get("folio"));

                var kvVal = secDb.getKeyValueEngine("cache_config").get("version");
                assertNotNull(kvVal);
                assertEquals("2.0", new String(kvVal, java.nio.charset.StandardCharsets.UTF_8));

                var tsMap = secDb.getTimeSeriesEngine("cpu_metric").getAll();
                assertTrue(tsMap.containsKey(1000L));
                assertEquals(85.5, tsMap.get(1000L));

                assertEquals(1, secDb.getGeospatialEngine("poi").size());
                assertNotNull(secDb.getGeospatialEngine("poi").getAllPoints().get("pt1"));

                assertNotNull(secDb.getVectorEngine("embeddings", 3).getVector("vec1"));
                assertTrue(secDb.getGraphEngine("social").getVertices().contains("u1"));
                assertEquals(1, secDb.getColumnarEngine("analytics").getRowCount());
                assertNotNull(secDb.getIndexManager().getIndex("idx_fac_folio"));

                var secRecEng = secDb.getRecordsEngine("test_records", TestRecord.class);
                assertNotNull(secRecEng.find("r1"));
                assertEquals("Admin", secRecEng.find("r1").name());
            }
        }
    }

    @Test
    @DisplayName("Debe ejecutar failover automático y promover nodo secundario a PRIMARY al detenerse el nodo primario")
    public void testFailoverAndPromotion() throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("jettra_failover_test");
        int testPort = 19098;

        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.node.id", "node-02");
        dbProps.setProperty("jettra.node.role", "SECONDARY");
        dbProps.setProperty("jettra.storage.path", tempDir.toString());
        dbProps.setProperty("jettra.network.grpc.port", String.valueOf(testPort));
        dbProps.setProperty("jettra.network.rest.port", "18088");
        dbProps.setProperty("cluster.multinode.active", "on");

        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.1.grpc.port", "19090");
        clusterProps.setProperty("cluster.node.1.role", "PRIMARY");
        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(testPort));
        clusterProps.setProperty("cluster.node.2.role", "SECONDARY");

        JettraStoreConfig secConfig = new JettraStoreConfig(dbProps, clusterProps);
        JettraStoreServer secServer = new JettraStoreServer(secConfig);

        assertEquals(ClusterNode.Role.SECONDARY, secServer.getConfig().getNodeRole());

        // Simular que el PRIMARY 'node-01' se detiene limpiamente
        secServer.handleNodeStopping("node-01", "Graceful shutdown test");

        // El nodo secundario debe promoverse a PRIMARY
        assertEquals(ClusterNode.Role.PRIMARY, secServer.getConfig().getNodeRole());

        // Validar que el evento fue emitido en el bus de eventos
        List<ClusterLiveEvent> events = JettraClusterEventBus.getInstance().getRecentEvents(10);
        boolean foundPromoted = events.stream().anyMatch(e -> ClusterLiveEvent.TYPE_LEADER_PROMOTED.equals(e.type()));
        assertTrue(foundPromoted);
    }

    @Test
    @DisplayName("Debe publicar y recibir eventos de clúster en tiempo real mediante JettraClusterEventBus")
    public void testClusterLiveEventChannel() throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<ClusterLiveEvent> received = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        java.util.function.Consumer<ClusterLiveEvent> listener = ev -> {
            if ("CUSTOM_LIVE_TEST".equals(ev.type())) {
                received.set(ev);
                latch.countDown();
            }
        };

        JettraClusterEventBus.getInstance().subscribe(listener);
        try {
            JettraClusterEventBus.getInstance().publish(
                "CUSTOM_LIVE_TEST", "node-01", "node-02", "Transferencia de 100 registros", "db=ventas"
            );

            boolean ok = latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ok);
            assertNotNull(received.get());
            assertEquals("node-01", received.get().sourceNodeId());
            assertEquals("node-02", received.get().targetNodeId());
            assertEquals("Transferencia de 100 registros", received.get().message());
        } finally {
            JettraClusterEventBus.getInstance().unsubscribe(listener);
        }
    }

    @Test
    @DisplayName("Debe ajustar dinámicamente el consenso cuando uno o más nodos están desconectados")
    public void testDynamicConsensusWithDisconnectedPeers() throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("jettra_dyn_consensus");
        int activePeerPort = 19120;
        int deadPeerPort = 19129; // Puerto donde no corre ningún servicio

        Properties secProps = new Properties();
        secProps.setProperty("jettra.node.id", "node-02");
        secProps.setProperty("jettra.node.role", "SECONDARY");
        secProps.setProperty("jettra.storage.path", tempDir.toString());
        secProps.setProperty("jettra.network.grpc.port", String.valueOf(activePeerPort));
        secProps.setProperty("jettra.network.rest.port", "18090");
        secProps.setProperty("cluster.multinode.active", "on");

        JettraStoreConfig secConfig = new JettraStoreConfig(secProps, new Properties());
        JettraStoreServer secServer = new JettraStoreServer(secConfig);

        try (JettraClusterTransportServer transportServer = new JettraClusterTransportServer(activePeerPort, secServer)) {
            transportServer.start();
            Thread.sleep(100);

            ClusterNode activePeer = new ClusterNode("node-02", "127.0.0.1", activePeerPort, ClusterNode.Role.SECONDARY);
            ClusterNode deadPeer = new ClusterNode("node-03", "127.0.0.1", deadPeerPort, ClusterNode.Role.SECONDARY);

            // Escenario A: 1 peer activo y 1 peer desconectado -> Quórum dinámico entre los 2 nodos disponibles
            try (JettraClusterReplicationClient clientA = new JettraClusterReplicationClient("node-01", List.of(activePeer, deadPeer))) {
                boolean ok = clientA.broadcastCreateDatabase("dyn_consensus_db");
                assertTrue(ok);
                assertTrue(secServer.listDatabaseNames().contains("dyn_consensus_db"));
            }

            // Escenario B: Todos los peers desconectados -> Quórum dinámico exclusivo en el nodo local activo (1 nodo disponible)
            try (JettraClusterReplicationClient clientB = new JettraClusterReplicationClient("node-01", List.of(deadPeer))) {
                boolean okAlone = clientB.broadcastCreateDatabase("solo_node_db");
                assertTrue(okAlone);
            }
        }
    }

    @Test
    @DisplayName("Debe sincronizar catálogo y registros en nodo SECUNDARIO desde el PRIMARY al arrancar")
    public void testSecondaryNodeStartupSynchronizationFlow() throws IOException, InterruptedException {
        Path primaryDir = Files.createTempDirectory("jettra_prim_sync");
        Path secondaryDir = Files.createTempDirectory("jettra_sec_sync");
        int primPort = 19130;
        int secPort = 19135;

        // Configuración y datos en PRIMARY
        Properties primDbProps = new Properties();
        primDbProps.setProperty("jettra.node.id", "node-01");
        primDbProps.setProperty("jettra.node.role", "PRIMARY");
        primDbProps.setProperty("jettra.storage.path", primaryDir.toString());
        primDbProps.setProperty("jettra.network.grpc.port", String.valueOf(primPort));
        primDbProps.setProperty("jettra.network.rest.port", "18092");
        primDbProps.setProperty("cluster.multinode.active", "on");

        Properties primClusterProps = new Properties();
        primClusterProps.setProperty("cluster.node.1.id", "node-01");
        primClusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        primClusterProps.setProperty("cluster.node.1.grpc.port", String.valueOf(primPort));
        primClusterProps.setProperty("cluster.node.2.id", "node-02");
        primClusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        primClusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(secPort));

        JettraStoreConfig primConfig = new JettraStoreConfig(primDbProps, primClusterProps);
        JettraStoreServer primServer = new JettraStoreServer(primConfig);

        // Crear base de datos en PRIMARIO con datos y persistirla
        var primDb = primServer.getOrCreateDatabaseInternal("tienda_online_db", false);
        primDb.getDocumentEngine("productos").insert("p100", java.util.Map.of("nombre", "Laptop Pro", "precio", 1200.0));
        primDb.getKeyValueEngine("config").put("moneda", "USD".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        primDb.saveToDisk();

        // Iniciar transporte en PRIMARY
        try (JettraClusterTransportServer primTransport = new JettraClusterTransportServer(primPort, primServer)) {
            primTransport.start();
            Thread.sleep(100);

            // Configuración en SECONDARY apuntando a PRIMARY
            Properties secDbProps = new Properties();
            secDbProps.setProperty("jettra.node.id", "node-02");
            secDbProps.setProperty("jettra.node.role", "SECONDARY");
            secDbProps.setProperty("jettra.storage.path", secondaryDir.toString());
            secDbProps.setProperty("jettra.network.grpc.port", String.valueOf(secPort));
            secDbProps.setProperty("jettra.network.rest.port", "18093");
            secDbProps.setProperty("cluster.multinode.active", "on");

            JettraStoreConfig secConfig = new JettraStoreConfig(secDbProps, primClusterProps);
            JettraStoreServer secServer = new JettraStoreServer(secConfig);

            // Ejecutar el flujo de sincronización inicial
            secServer.start();
            boolean syncOk = secServer.synchronizeFromPrimary();
            assertTrue(syncOk);

            // Validar que el nodo SECUNDARIO obtuvo el catálogo y todos los registros del PRIMARY
            List<String> secDbs = secServer.listDatabaseNames();
            assertTrue(secDbs.contains("tienda_online_db"));

            var secDb = secServer.getOrCreateDatabaseInternal("tienda_online_db", false);
            assertNotNull(secDb);
            assertEquals(1L, secDb.getDocumentEngine("productos").count());
            var prod = secDb.getDocumentEngine("productos").findById("p100");
            assertNotNull(prod);
            assertEquals("Laptop Pro", prod.get("nombre"));

            byte[] monedaBytes = secDb.getKeyValueEngine("config").get("moneda");
            assertNotNull(monedaBytes);
            assertEquals("USD", new String(monedaBytes, java.nio.charset.StandardCharsets.UTF_8));

            secServer.stop();
        }
    }

    @Test
    @DisplayName("Debe replicar creación de motores especializados y transferencias en vivo")
    public void testReplicationEngineCreationAndDataTransfer() throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("jettra_engine_repl_test");
        int testPort = 19140;

        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.node.id", "node-02");
        dbProps.setProperty("jettra.node.role", "SECONDARY");
        dbProps.setProperty("jettra.storage.path", tempDir.toString());
        dbProps.setProperty("jettra.network.grpc.port", String.valueOf(testPort));
        dbProps.setProperty("jettra.network.rest.port", "18084");
        dbProps.setProperty("cluster.multinode.active", "on");

        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.1.grpc.port", "19139");
        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        clusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(testPort));

        JettraStoreConfig secondaryConfig = new JettraStoreConfig(dbProps, clusterProps);
        JettraStoreServer secondaryServer = new JettraStoreServer(secondaryConfig);

        try (JettraClusterTransportServer transportServer = new JettraClusterTransportServer(testPort, secondaryServer)) {
            transportServer.start();
            Thread.sleep(100);

            ClusterNode peerNode2 = new ClusterNode("node-02", "127.0.0.1", testPort, ClusterNode.Role.SECONDARY);
            try (JettraClusterReplicationClient client = new JettraClusterReplicationClient("node-01", List.of(peerNode2))) {
                // 1. Replicar creación de base
                boolean dbCreated = client.broadcastCreateDatabase("engines_test_db");
                assertTrue(dbCreated);

                // 2. Replicar creación de motores
                boolean vecEngine = client.broadcastCreateEngine("engines_test_db", "embeddings", "VECTOR", "256".getBytes());
                assertTrue(vecEngine);

                boolean tsEngine = client.broadcastCreateEngine("engines_test_db", "metrics", "TIMESERIES", null);
                assertTrue(tsEngine);

                // 3. Replicar registro KV
                byte[] val = "activo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                boolean kvPut = client.broadcastPutRecord("engines_test_db", "app_config", "status", val);
                assertTrue(kvPut);

                // Verificar en nodo secundario
                var secDb = secondaryServer.getOrCreateDatabaseInternal("engines_test_db", false);
                assertNotNull(secDb);
                assertNotNull(secDb.getVectorEngine("embeddings", 256));
                assertNotNull(secDb.getTimeSeriesEngine("metrics"));
                byte[] readVal = secDb.getKeyValueEngine("app_config").get("status");
                assertNotNull(readVal);
                assertEquals("activo", new String(readVal, java.nio.charset.StandardCharsets.UTF_8));

                // 4. Verificar eventos de transferencia en el bus
                List<ClusterLiveEvent> events = JettraClusterEventBus.getInstance().getRecentEvents(50);
                boolean foundEngine = events.stream().anyMatch(e -> ClusterLiveEvent.TYPE_ENGINE_CREATED.equals(e.type()));
                boolean foundTransfer = events.stream().anyMatch(e -> ClusterLiveEvent.TYPE_DATA_TRANSFER.equals(e.type()));
                assertTrue(foundEngine);
                assertTrue(foundTransfer);
            }
        }
    }

    @Test
    @DisplayName("Debe replicar creación de base de datos, documentos e índices desde PRIMARIO hacia ambos nodos SECUNDARIOS (3 nodos)")
    public void testThreeNodeClusterDatabaseAndIndexReplication() throws IOException, InterruptedException {
        Path tempDirSec1 = Files.createTempDirectory("jettra_cluster_sec1");
        Path tempDirSec2 = Files.createTempDirectory("jettra_cluster_sec2");
        Path tempDirPrim = Files.createTempDirectory("jettra_cluster_prim");
        int sec1Port = 19162;
        int sec2Port = 19163;
        int primPort = 19161;

        // Configuración Nodo Secundario 1
        Properties sec1DbProps = new Properties();
        sec1DbProps.setProperty("jettra.node.id", "node-02");
        sec1DbProps.setProperty("jettra.node.role", "SECONDARY");
        sec1DbProps.setProperty("jettra.storage.path", tempDirSec1.toString());
        sec1DbProps.setProperty("jettra.network.grpc.port", String.valueOf(sec1Port));
        sec1DbProps.setProperty("jettra.network.rest.port", "18092");
        sec1DbProps.setProperty("cluster.multinode.active", "on");

        Properties sec1ClusterProps = new Properties();
        sec1ClusterProps.setProperty("cluster.node.1.id", "node-01");
        sec1ClusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        sec1ClusterProps.setProperty("cluster.node.1.grpc.port", String.valueOf(primPort));
        sec1ClusterProps.setProperty("cluster.node.2.id", "node-02");
        sec1ClusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
        sec1ClusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(sec1Port));

        JettraStoreConfig sec1Config = new JettraStoreConfig(sec1DbProps, sec1ClusterProps);
        JettraStoreServer secServer1 = new JettraStoreServer(sec1Config);

        // Configuración Nodo Secundario 2
        Properties sec2DbProps = new Properties();
        sec2DbProps.setProperty("jettra.node.id", "node-03");
        sec2DbProps.setProperty("jettra.node.role", "SECONDARY");
        sec2DbProps.setProperty("jettra.storage.path", tempDirSec2.toString());
        sec2DbProps.setProperty("jettra.network.grpc.port", String.valueOf(sec2Port));
        sec2DbProps.setProperty("jettra.network.rest.port", "18093");
        sec2DbProps.setProperty("cluster.multinode.active", "on");

        Properties sec2ClusterProps = new Properties();
        sec2ClusterProps.setProperty("cluster.node.1.id", "node-01");
        sec2ClusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
        sec2ClusterProps.setProperty("cluster.node.1.grpc.port", String.valueOf(primPort));
        sec2ClusterProps.setProperty("cluster.node.3.id", "node-03");
        sec2ClusterProps.setProperty("cluster.node.3.ip", "127.0.0.1");
        sec2ClusterProps.setProperty("cluster.node.3.grpc.port", String.valueOf(sec2Port));

        JettraStoreConfig sec2Config = new JettraStoreConfig(sec2DbProps, sec2ClusterProps);
        JettraStoreServer secServer2 = new JettraStoreServer(sec2Config);

        try (JettraClusterTransportServer transportServer1 = new JettraClusterTransportServer(sec1Port, secServer1);
             JettraClusterTransportServer transportServer2 = new JettraClusterTransportServer(sec2Port, secServer2)) {
            transportServer1.start();
            transportServer2.start();
            Thread.sleep(100);

            // Configuración Nodo Primario con los 2 pares secundarios
            Properties primDbProps = new Properties();
            primDbProps.setProperty("jettra.node.id", "node-01");
            primDbProps.setProperty("jettra.node.role", "PRIMARY");
            primDbProps.setProperty("jettra.storage.path", tempDirPrim.toString());
            primDbProps.setProperty("jettra.network.grpc.port", String.valueOf(primPort));
            primDbProps.setProperty("jettra.network.rest.port", "18091");
            primDbProps.setProperty("cluster.multinode.active", "on");

            Properties primClusterProps = new Properties();
            primClusterProps.setProperty("cluster.node.1.id", "node-01");
            primClusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
            primClusterProps.setProperty("cluster.node.1.grpc.port", String.valueOf(primPort));
            primClusterProps.setProperty("cluster.node.2.id", "node-02");
            primClusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
            primClusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(sec1Port));
            primClusterProps.setProperty("cluster.node.3.id", "node-03");
            primClusterProps.setProperty("cluster.node.3.ip", "127.0.0.1");
            primClusterProps.setProperty("cluster.node.3.grpc.port", String.valueOf(sec2Port));

            JettraStoreConfig primConfig = new JettraStoreConfig(primDbProps, primClusterProps);
            JettraStoreServer primServer = new JettraStoreServer(primConfig);

            try {
                // 1. Crear base de datos en PRIMARIO
                String dbName = "tri_node_db";
                var primDb = primServer.getOrCreateDatabase(dbName);
                assertNotNull(primDb);

                // 2. Crear colección, índice y documento en PRIMARIO
                primDb.getDocumentEngine("articulos").insert("art_1", java.util.Map.of("sku", "A-100", "nombre", "Teclado Mecanico"));
                primDb.getIndexManager().createIndex("articulos", "idx_art_sku", "sku", "HASH", false, primDb.getDocumentEngine("articulos"));
                primDb.getKeyValueEngine("config").put("status", "ACTIVE".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                primDb.flushMemTable();
                primDb.saveToDisk();

                // Replicar base a ambos nodos secundarios
                byte[] snapshot = primServer.getDatabaseSnapshotBytes(dbName);
                boolean replDb = primServer.replicateCreateDatabase(dbName, snapshot);
                assertTrue(replDb);

                // Esperar propagación en hilos virtuales
                Thread.sleep(250);

                // 3. Validar en SECUNDARIO 1
                assertTrue(secServer1.listDatabaseNames().contains(dbName));
                var secDb1 = secServer1.getOrCreateDatabaseInternal(dbName, false);
                assertNotNull(secDb1);
                assertTrue(secDb1.isReadOnlyNode());
                assertEquals(1L, secDb1.getDocumentEngine("articulos").count());
                var docSec1 = secDb1.getDocumentEngine("articulos").findById("art_1");
                assertNotNull(docSec1);
                assertEquals("Teclado Mecanico", docSec1.get("nombre"));
                assertNotNull(secDb1.getIndexManager().getIndex("idx_art_sku"));
                java.util.Set<String> foundIds1 = secDb1.getIndexManager().findDocIds("articulos", "sku", "A-100");
                assertNotNull(foundIds1);
                assertTrue(foundIds1.contains("art_1"));
                assertNotNull(secDb1.getKeyValueEngine("config").get("status"));
                assertEquals("ACTIVE", new String(secDb1.getKeyValueEngine("config").get("status"), java.nio.charset.StandardCharsets.UTF_8));

                // 4. Validar en SECUNDARIO 2
                assertTrue(secServer2.listDatabaseNames().contains(dbName));
                var secDb2 = secServer2.getOrCreateDatabaseInternal(dbName, false);
                assertNotNull(secDb2);
                assertTrue(secDb2.isReadOnlyNode());
                assertEquals(1L, secDb2.getDocumentEngine("articulos").count());
                var docSec2 = secDb2.getDocumentEngine("articulos").findById("art_1");
                assertNotNull(docSec2);
                assertEquals("Teclado Mecanico", docSec2.get("nombre"));
                assertNotNull(secDb2.getIndexManager().getIndex("idx_art_sku"));
                java.util.Set<String> foundIds2 = secDb2.getIndexManager().findDocIds("articulos", "sku", "A-100");
                assertNotNull(foundIds2);
                assertTrue(foundIds2.contains("art_1"));
                assertNotNull(secDb2.getKeyValueEngine("config").get("status"));
                assertEquals("ACTIVE", new String(secDb2.getKeyValueEngine("config").get("status"), java.nio.charset.StandardCharsets.UTF_8));

                // 5. Validar que la estructura de archivos en disco se materializó físicamente en ambos secundarios
                Path sec1DbDir = tempDirSec1.resolve(dbName);
                Path sec2DbDir = tempDirSec2.resolve(dbName);
                assertTrue(Files.exists(sec1DbDir));
                assertTrue(Files.exists(sec2DbDir));
                assertTrue(Files.exists(sec1DbDir.resolve("jettra_memory")));
                assertTrue(Files.exists(sec2DbDir.resolve("jettra_memory")));
            } finally {
                primServer.stop();
            }
        } finally {
            secServer1.stop();
            secServer2.stop();
        }
    }

    public record TestRecord(String id, String name, int score) {}
}

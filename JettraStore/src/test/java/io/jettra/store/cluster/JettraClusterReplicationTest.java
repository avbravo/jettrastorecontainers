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

    public record TestRecord(String id, String name, int score) {}
}

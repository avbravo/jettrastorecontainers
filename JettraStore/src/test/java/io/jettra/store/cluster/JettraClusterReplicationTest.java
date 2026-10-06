package io.jettra.store.cluster;

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

public class JettraClusterReplicationTest {

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
}

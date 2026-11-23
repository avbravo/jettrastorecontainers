package io.jettra.example.se;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.example.se.model.NotaLibre;
import io.jettra.example.se.model.Pais;
import io.jettra.example.se.model.Producto;
import io.jettra.example.se.repository.NotaLibreRepository;
import io.jettra.example.se.repository.PaisRepository;
import io.jettra.example.se.repository.ProductoRepository;
import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class JettraStoreExampleSETest {

    private JettraStoreServer startTestNode(Path baseDir, Path clusterFile, String nodeId, String role, int grpcPort, int restPort) throws Exception {
        Path dataDir = baseDir.resolve(nodeId);
        Files.createDirectories(dataDir);
        Properties props = new Properties();
        props.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", "JVM_RAM");
        props.setProperty("cluster.multinode.active", "on");
        props.setProperty("cluster.node.id", nodeId);
        props.setProperty("cluster.node.role", role);
        props.setProperty("jettra.node.ip", "127.0.0.1");
        props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
        props.setProperty("jettra.network.rest.port", String.valueOf(restPort));
        Properties clusterProps = JettraConfigValidator.loadProperties(clusterFile, "/cluster.config", "/jettra.config");
        JettraStoreConfig cfg = new JettraStoreConfig(props, clusterProps, props);
        JettraStoreServer srv = new JettraStoreServer(cfg);
        srv.setClusterConfigFilePath(clusterFile);
        srv.start();
        return srv;
    }

    @Test
    @DisplayName("RF-02: examplesedb, buckets DEFINED/NONE, Java records, Repositories, JettraRules, índices NUMERIC/AUTO_INCREMENT y resiliencia de clúster")
    public void testEndToEndExampleSEAndClusterResilience() throws Exception {
        Path tmpDir = Files.createTempDirectory("jettra_example_se_");
        Path clusterFile = tmpDir.resolve("cluster.config");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStorage = System.getProperty("jettra.storage.path");
        String prevStorageAlias = System.getProperty("storage.path");

        Files.writeString(clusterFile, """
                cluster.multinode.active=on
                cluster.node.1.id=node-01
                cluster.node.1.role=PRIMARY
                cluster.node.1.ip=127.0.0.1
                cluster.node.1.grpc.port=53101
                cluster.node.1.rest.port=63101
                cluster.node.2.id=node-02
                cluster.node.2.role=SECONDARY
                cluster.node.2.ip=127.0.0.1
                cluster.node.2.grpc.port=53102
                cluster.node.2.rest.port=63102
                cluster.node.3.id=node-03
                cluster.node.3.role=SECONDARY
                cluster.node.3.ip=127.0.0.1
                cluster.node.3.grpc.port=53103
                cluster.node.3.rest.port=63103
                """);
        String isolatedClientStorage = tmpDir.resolve("client-storage").toAbsolutePath().toString();
        System.setProperty("cluster.config.path", clusterFile.toAbsolutePath().toString());
        System.clearProperty("jettra.storage.path");
        System.clearProperty("storage.path");

        JettraStoreServer n1 = null, n2 = null, n3 = null;
        String testDb = "examplesedb_test_db";
        try {
            // Arrancar SECONDARY primero y PRIMARY al final para que la instancia activa en la misma JVM sea node-01 (PRIMARY)
            n2 = startTestNode(tmpDir, clusterFile, "node-02", "SECONDARY", 53102, 63102);
            n3 = startTestNode(tmpDir, clusterFile, "node-03", "SECONDARY", 53103, 63103);
            n1 = startTestNode(tmpDir, clusterFile, "node-01", "PRIMARY", 53101, 63101);
            System.setProperty("jettra.storage.path", isolatedClientStorage);
            System.setProperty("storage.path", isolatedClientStorage);

            JettraClientConfig cfg = JettraClientConfig.builder()
                    .addClusterNode("127.0.0.1", 53101)
                    .credentials("admin", "admin-jettra")
                    .role("admin")
                    .defaultDatabase(testDb)
                    .build();

            try (JettraClient client = JettraClient.connect(cfg)) {
                // 1. Obtener cluster.config desde el PRIMARY
                String primaryCfg = client.readPrimaryClusterConfig();
                JettraAssert.assertTrue(primaryCfg.contains("node-01") && primaryCfg.contains("53101"),
                        "Debe obtener cluster.config vigente desde el PRIMARY");

                // 2. Crear base aislada de prueba, buckets DEFINED/NONE e índices
                client.createDatabase(testDb);
                PaisRepository paisRepo = new PaisRepository(client, testDb);
                ProductoRepository prodRepo = new ProductoRepository(client, testDb);
                NotaLibreRepository notaRepo = new NotaLibreRepository(client, testDb);

                paisRepo.initBucketAndIndexes();
                prodRepo.initBucketAndIndexes();
                notaRepo.initBucket();

                // 3. CRUD con Java record + JettraRules + AUTO_INCREMENT + NUMERIC
                paisRepo.save("pa-1", new Pais(null, "Panama", "PA"));
                paisRepo.save("cr-1", new Pais(null, "Costa Rica", "CR"));
                JettraAssert.assertEquals(1L, paisRepo.findById("pa-1").orElseThrow().codigo(), "Primer código AUTO_INCREMENT debe ser 1");
                JettraAssert.assertEquals(2L, paisRepo.findById("cr-1").orElseThrow().codigo(), "Segundo código AUTO_INCREMENT debe ser 2");
                JettraAssert.assertEquals("Panama", paisRepo.findBySiglas("PA").orElseThrow().name(), "Búsqueda por índice siglas debe funcionar");

                // Regla JettraRules: rechazar país con siglas inválidas
                boolean rulesRejected = false;
                try {
                    paisRepo.save("bad-1", new Pais(null, "", "X"));
                } catch (IllegalArgumentException expected) {
                    rulesRejected = true;
                }
                JettraAssert.assertTrue(rulesRejected, "JettraRules debe rechazar entidades inválidas");

                // Productos con índice NUMERIC y búsqueda por rango
                prodRepo.save("p-1", new Producto(null, "Teclado", "Periféricos", 45.0));
                prodRepo.save("p-2", new Producto(null, "Monitor", "Pantallas", 320.0));
                prodRepo.save("p-3", new Producto(null, "Mouse", "Periféricos", 18.5));
                List<Producto> midRange = prodRepo.findByPrecioRange(15.0, 50.0);
                JettraAssert.assertEquals(2, midRange.size(), "Índice NUMERIC debe retornar 2 productos en [15.0, 50.0]");

                // Bucket NONE (esquema libre)
                notaRepo.save("n-1", new NotaLibre("n-1", Map.of("id", 1, "name", "aristides")));
                notaRepo.save("n-2", new NotaLibre("n-2", Map.of("apellido", "Perez", "pais", "Panama")));
                JettraAssert.assertEquals(2, notaRepo.findAll().size(), "Bucket NONE debe permitir registros con esquemas distintos");

                // 4. Resiliencia del clúster: detener node-03, verificar bloqueo si activo y remoción tras detener
                boolean blockedActive = false;
                try {
                    client.removeStoppedClusterNode("node-03");
                } catch (IllegalStateException expected) {
                    blockedActive = true;
                }
                JettraAssert.assertTrue(blockedActive, "No debe permitir remover node-03 mientras esté activo");

                var stopRes = client.stopClusterNode("node-03");
                JettraAssert.assertTrue(stopRes.success() && stopRes.verifiedState(), "Debe detener node-03 limpiamente");

                boolean removed = client.removeStoppedClusterNode("node-03");
                JettraAssert.assertTrue(removed, "Debe remover node-03 tras detenerlo sin detener node-01 ni node-02");

                // Verificar que node-01 y node-02 siguen en ejecución
                JettraAssert.assertTrue(client.isNodeRunning("127.0.0.1", 53101, 63101), "PRIMARY node-01 debe seguir activo");
                JettraAssert.assertTrue(client.isNodeRunning("127.0.0.1", 53102, 63102), "SECONDARY node-02 debe seguir activo");
            }
        } finally {
            if (n3 != null) try { n3.stop(); } catch (Exception ignored) {}
            if (n2 != null) try { n2.stop(); } catch (Exception ignored) {}
            if (n1 != null) try { n1.stop(); } catch (Exception ignored) {}
            if (prevCluster == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevCluster);
            if (prevStorage == null) System.clearProperty("jettra.storage.path");
            else System.setProperty("jettra.storage.path", prevStorage);
            if (prevStorageAlias == null) System.clearProperty("storage.path");
            else System.setProperty("storage.path", prevStorageAlias);
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }
}

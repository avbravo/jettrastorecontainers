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
import io.jettra.store.core.JettraId;
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
    @DisplayName("RF-13: Persistencia real en examplesedb, JettraId en cada registro, Buckets DEFINED/NONE, índices y resiliencia de clúster")
    public void testEndToEndExampleSEPersistenceJettraIdBucketsAndResilience() throws Exception {
        Path tmpDir = Files.createTempDirectory("jettra_example_se_rf13_");
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
        String dbName = JettraStoreExampleSEApp.DATABASE + "_test_" + System.currentTimeMillis();
        JettraId panamaId;
        JettraId costaRicaId;
        JettraId tecladoId;
        JettraId notaAutoId;
        JettraId notaCustomId;

        try {
            // Arrancar SECONDARY primero y PRIMARY al final para que el clúster esté completamente operativo
            n2 = startTestNode(tmpDir, clusterFile, "node-02", "SECONDARY", 53102, 63102);
            n3 = startTestNode(tmpDir, clusterFile, "node-03", "SECONDARY", 53103, 63103);
            n1 = startTestNode(tmpDir, clusterFile, "node-01", "PRIMARY", 53101, 63101);
            System.setProperty("jettra.storage.path", isolatedClientStorage);
            System.setProperty("storage.path", isolatedClientStorage);

            JettraClientConfig cfg = JettraStoreExampleSEApp.buildClusterConfig();

            // SESIÓN 1: Creación de examplesedb, Buckets, inserción con JettraId, actualización y borrado
            try (JettraClient client = JettraClient.connect(cfg)) {
                // 1. Obtener cluster.config vigente desde el PRIMARY
                String primaryCfg = client.readPrimaryClusterConfig();
                JettraAssert.assertTrue(primaryCfg.contains("node-01") && primaryCfg.contains("53101"),
                        "Debe obtener cluster.config vigente desde el PRIMARY");

                // 2. Crear base de datos examplesedb y Buckets (DEFINED y NONE) con sus índices
                client.createDatabase(dbName);
                PaisRepository paisRepo = new PaisRepository(client, dbName);
                ProductoRepository prodRepo = new ProductoRepository(client, dbName);
                NotaLibreRepository notaRepo = new NotaLibreRepository(client, dbName);

                paisRepo.initBucketAndIndexes();
                prodRepo.initBucketAndIndexes();
                notaRepo.initBucket();

                JettraAssert.assertTrue(
                        client.listBuckets(dbName).stream().anyMatch(b -> b.bucketName().equals("Pais") && b.engineType().equals("JAVA_RECORD")),
                        "Debe registrar el Bucket Pais en JAVA_RECORD");
                JettraAssert.assertTrue(
                        client.listBuckets(dbName).stream().anyMatch(b -> b.bucketName().equals("Producto") && b.engineType().equals("DOCUMENT")),
                        "Debe registrar el Bucket Producto en DOCUMENT");
                JettraAssert.assertTrue(
                        client.listBuckets(dbName).stream().anyMatch(b -> b.bucketName().equals("NotasLibres") && b.engineType().equals("DOCUMENT")),
                        "Debe registrar el Bucket NotasLibres (NONE) en DOCUMENT");

                // 3. Insertar con JettraId autogenerado + AUTO_INCREMENT + JettraRules
                Pais paSaved = paisRepo.save(new Pais(null, "Panama", "PA"));
                Pais crSaved = paisRepo.save(new Pais(null, "Costa Rica", "CR"));
                JettraAssert.assertNotNull(paSaved.jettraId(), "Pais guardado debe contener su JettraId autogenerado");
                JettraAssert.assertNotNull(crSaved.jettraId(), "Segundo Pais debe contener su JettraId autogenerado");
                JettraAssert.assertFalse(paSaved.jettraId().equals(crSaved.jettraId()), "Cada registro debe tener un JettraId único");
                JettraAssert.assertEquals(1L, paSaved.codigo(), "Primer código AUTO_INCREMENT debe ser 1");
                JettraAssert.assertEquals(2L, crSaved.codigo(), "Segundo código AUTO_INCREMENT debe ser 2");
                panamaId = paSaved.jettraId();
                costaRicaId = crSaved.jettraId();

                // Actualizar por JettraId y verificar conservación de JettraId y secuencia
                Pais paUpdated = paisRepo.update(panamaId, new Pais(panamaId, paSaved.codigo(), "República de Panamá", "PA"));
                JettraAssert.assertEquals(panamaId, paUpdated.jettraId(), "La actualización debe conservar exactamente el mismo JettraId");
                JettraAssert.assertEquals("República de Panamá", paisRepo.findById(panamaId).orElseThrow().name(),
                        "findById(JettraId) debe devolver el nombre actualizado");
                JettraAssert.assertEquals(panamaId, paisRepo.findBySiglas("PA").orElseThrow().jettraId(),
                        "Búsqueda por índice siglas debe devolver el mismo JettraId");

                // Validación JettraRules: rechazar entidad inválida
                boolean rulesRejected = false;
                try {
                    paisRepo.save(new Pais(null, "", "X"));
                } catch (IllegalArgumentException expected) {
                    rulesRejected = true;
                }
                JettraAssert.assertTrue(rulesRejected, "JettraRules debe rechazar entidades inválidas");

                // Productos: índice AUTO_INCREMENT + índice NUMERIC + deleteById(JettraId)
                Producto teclado = prodRepo.save(new Producto(null, "Teclado", "Periféricos", 45.0));
                Producto monitor = prodRepo.save(new Producto(null, "Monitor", "Pantallas", 320.0));
                Producto mouse = prodRepo.save(new Producto(null, "Mouse", "Periféricos", 18.5));
                Producto temporal = prodRepo.save(new Producto(null, "Cable Temporal", "Accesorios", 5.0));
                tecladoId = teclado.jettraId();

                JettraAssert.assertNotNull(tecladoId, "Producto debe persistir su JettraId");
                JettraAssert.assertNotNull(monitor.jettraId(), "Monitor debe persistir su JettraId");
                JettraAssert.assertNotNull(mouse.jettraId(), "Mouse debe persistir su JettraId");
                JettraAssert.assertTrue(prodRepo.deleteById(temporal.jettraId()), "deleteById(JettraId) debe eliminar el producto temporal");
                JettraAssert.assertTrue(prodRepo.findById(temporal.jettraId()).isEmpty(), "Producto eliminado no debe existir en el Bucket");

                List<Producto> midRange = prodRepo.findByPrecioRange(15.0, 50.0);
                JettraAssert.assertEquals(2, midRange.size(), "Índice NUMERIC debe retornar 2 productos en [15.0, 50.0]");

                // Bucket NONE (esquema libre) con JettraId autogenerado y JettraId definido explícitamente
                NotaLibre nAuto = notaRepo.save(new NotaLibre(Map.of("id", 1, "name", "aristides")));
                notaAutoId = nAuto.jettraId();
                notaCustomId = JettraId.generate(dbName, NotaLibreRepository.BUCKET);
                NotaLibre nCustom = notaRepo.save(new NotaLibre(notaCustomId, Map.of("apellido", "Perez", "pais", "Panama")));
                JettraAssert.assertNotNull(notaAutoId, "NotaLibre sin ID explícito debe generar JettraId");
                JettraAssert.assertEquals(notaCustomId, nCustom.jettraId(), "NotaLibre con JettraId explícito debe conservarlo");

                NotaLibre nUpdated = notaRepo.update(notaAutoId, new NotaLibre(notaAutoId, Map.of("id", 1, "name", "Aristides Actualizado")));
                JettraAssert.assertEquals(notaAutoId, nUpdated.jettraId(), "update(JettraId) en Bucket NONE debe conservar el JettraId");
                JettraAssert.assertEquals("Aristides Actualizado", nUpdated.atributos().get("name"),
                        "Los atributos del Bucket NONE deben actualizarse correctamente");
            }

            // SESIÓN 2: Reabrir un JettraClient completamente nuevo para comprobar persistencia REAL en disco (examplesedb)
            try (JettraClient freshClient = JettraClient.connect(cfg)) {
                PaisRepository paisRepo2 = new PaisRepository(freshClient, dbName);
                ProductoRepository prodRepo2 = new ProductoRepository(freshClient, dbName);
                NotaLibreRepository notaRepo2 = new NotaLibreRepository(freshClient, dbName);

                Pais paReloaded = paisRepo2.findById(panamaId).orElseThrow();
                JettraAssert.assertEquals(panamaId, paReloaded.jettraId(), "El JettraId de Panama debe sobrevivir tras reabrir el cliente");
                JettraAssert.assertEquals("República de Panamá", paReloaded.name(), "Los datos actualizados de Panama deben persistir en disco");
                JettraAssert.assertEquals(2, paisRepo2.findAll().size(), "Deben persistir 2 registros en el Bucket Pais");
                JettraAssert.assertTrue(paisRepo2.findById(costaRicaId).isPresent(), "Costa Rica debe recuperarse por JettraId");

                Producto prodReloaded = prodRepo2.findById(tecladoId).orElseThrow();
                JettraAssert.assertEquals(tecladoId, prodReloaded.jettraId(), "El JettraId de Teclado debe sobrevivir tras reabrir el cliente");
                JettraAssert.assertEquals(3, prodRepo2.findAll().size(), "Deben persistir exactamente 3 productos tras el borrado del temporal");

                NotaLibre notaAutoReloaded = notaRepo2.findById(notaAutoId).orElseThrow();
                NotaLibre notaCustomReloaded = notaRepo2.findById(notaCustomId).orElseThrow();
                JettraAssert.assertEquals(notaAutoId, notaAutoReloaded.jettraId(), "JettraId autogenerado en Bucket NONE debe persistir");
                JettraAssert.assertEquals(notaCustomId, notaCustomReloaded.jettraId(), "JettraId explícito en Bucket NONE debe persistir");

                // 4. Resiliencia del clúster: bloqueo de remoción si activo, detención limpia y remoción de node-03
                boolean blockedActive = false;
                try {
                    freshClient.removeStoppedClusterNode("node-03");
                } catch (IllegalStateException expected) {
                    blockedActive = true;
                }
                JettraAssert.assertTrue(blockedActive, "No debe permitir remover node-03 mientras esté activo");

                var stopRes = freshClient.stopClusterNode("node-03");
                JettraAssert.assertTrue(stopRes.success() && stopRes.verifiedState(), "Debe detener node-03 limpiamente");

                boolean removed = freshClient.removeStoppedClusterNode("node-03");
                JettraAssert.assertTrue(removed, "Debe remover node-03 tras detenerlo sin detener node-01 ni node-02");

                JettraAssert.assertTrue(freshClient.isNodeRunning("127.0.0.1", 53101, 63101), "PRIMARY node-01 debe seguir activo");
                JettraAssert.assertTrue(freshClient.isNodeRunning("127.0.0.1", 53102, 63102), "SECONDARY node-02 debe seguir activo");
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

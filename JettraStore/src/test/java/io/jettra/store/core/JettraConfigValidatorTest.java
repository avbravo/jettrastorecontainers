package io.jettra.store.core;

import io.jettra.store.JettraStoreBaseTest;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static io.jettra.test.core.JettraAssert.*;

public class JettraConfigValidatorTest extends JettraStoreBaseTest {

    private Properties createValidClusterProperties() {
        Properties props = new Properties();
        props.setProperty("cluster.name", "jettra-production-cluster");
        props.setProperty("cluster.consensus.protocol", "RAFT");
        
        props.setProperty("cluster.node.1.id", "node-01");
        props.setProperty("cluster.node.1.role", "PRIMARY");
        props.setProperty("cluster.node.1.ip", "127.0.0.1");
        props.setProperty("cluster.node.1.grpc.port", "9091");
        props.setProperty("cluster.node.1.rest.port", "8080");
        props.setProperty("cluster.node.1.storage.path", "~/jettra/data");

        props.setProperty("cluster.node.2.id", "node-02");
        props.setProperty("cluster.node.2.role", "SECONDARY");
        props.setProperty("cluster.node.2.ip", "127.0.0.1");
        props.setProperty("cluster.node.2.grpc.port", "9092");
        props.setProperty("cluster.node.2.rest.port", "8082");
        props.setProperty("cluster.node.2.storage.path", "~/jettra/data");

        props.setProperty("cluster.node.3.id", "node-03");
        props.setProperty("cluster.node.3.role", "SECONDARY");
        props.setProperty("cluster.node.3.ip", "127.0.0.1");
        props.setProperty("cluster.node.3.grpc.port", "9093");
        props.setProperty("cluster.node.3.rest.port", "8083");
        props.setProperty("cluster.node.3.storage.path", "~/jettra/data");

        return props;
    }

    private Properties createValidMicroProfileProperties() {
        Properties props = new Properties();
        props.setProperty("cluster.node.id", "node-01");
        props.setProperty("cluster.node.role", "PRIMARY");
        props.setProperty("cluster.multinode.active", "on");
        return props;
    }

    private Properties createValidDatabaseProperties() {
        return createValidMicroProfileProperties();
    }

    @Test
    @DisplayName("Debe validar exitosamente cuando todos los atributos coinciden con la topología del clúster")
    public void testValidConfigurationPasses() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("Debe verificar correctamente la coincidencia de storage.path en el nodo 1")
    public void testStoragePathMatchesNode1Passes() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("cluster.node.id", "node-01");
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");
        clusterProps.setProperty("cluster.node.1.storage.path", "~/jettra/data");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Debe verificar correctamente la coincidencia de storage.path en el nodo 2")
    public void testStoragePathMatchesNode2Passes() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("cluster.node.id", "node-02");
        dbProps.setProperty("cluster.node.role", "SECONDARY");
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");
        dbProps.setProperty("jettra.network.grpc.port", "9092");
        dbProps.setProperty("jettra.network.rest.port", "8082");
        clusterProps.setProperty("cluster.node.2.storage.path", "~/jettra/data");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
    }

    @Test
    @DisplayName("Debe emitir advertencia si jettra.storage.path del nodo 2 no coincide con cluster.node.2.storage.path")
    public void testStoragePathMismatchWithNode2EmitsWarning() throws IOException {
        Path tempDir2 = Files.createTempDirectory("jettra_mismatch_2");
        Path tempDir3 = Files.createTempDirectory("jettra_mismatch_3");
        try {
            Properties clusterProps = createValidClusterProperties();
            Properties dbProps = createValidDatabaseProperties();
            dbProps.setProperty("cluster.node.id", "node-02");
            dbProps.setProperty("cluster.node.role", "SECONDARY");
            dbProps.setProperty("jettra.storage.path", tempDir2.toAbsolutePath().toString());
            dbProps.setProperty("jettra.network.grpc.port", "9092");
            dbProps.setProperty("jettra.network.rest.port", "8082");
            // En jettra.config el nodo 2 tiene una ruta diferente
            clusterProps.setProperty("cluster.node.2.storage.path", tempDir3.toAbsolutePath().toString());

            JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
            assertTrue(result.isValid()); // No detiene bruscamente, es una advertencia
            assertTrue(result.hasWarnings());
            boolean hasWarning = result.getWarnings().stream()
                .anyMatch(w -> w.contains("cluster.node.2.storage.path")
                            && w.contains(tempDir2.toAbsolutePath().toString())
                            && w.contains(tempDir3.toAbsolutePath().toString()));
            assertTrue(hasWarning);
        } finally {
            Files.deleteIfExists(tempDir2);
            Files.deleteIfExists(tempDir3);
        }
    }

    @Test
    @DisplayName("Debe permitir cualquier ruta indicada por el usuario en storage.path e index.storage.path")
    public void testCustomStorageAndIndexPathsAllowedWithoutRestrictions() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        // Rutas arbitrarias elegidas libremente por el usuario
        dbProps.setProperty("jettra.node.id", "node-01");
        dbProps.setProperty("jettra.storage.path", "/opt/custom/db/storage");
        dbProps.setProperty("jettra.index.storage.path", "/opt/custom/db/indexes_fast");
        clusterProps.setProperty("cluster.node.1.storage.path", "/opt/custom/db/storage");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
    }

    @Test
    @DisplayName("Debe fallar si jettra.network.grpc.port no coincide con ningún nodo del clúster")
    public void testGrpcPortMismatchFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("jettra.network.grpc.port", "9999");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasGrpcError = result.getErrors().stream()
            .anyMatch(err -> err.contains("jettra.network.grpc.port") && err.contains("no coincide"));
        assertTrue(hasGrpcError);
    }

    @Test
    @DisplayName("Debe fallar si jettra.network.rest.port no coincide con ningún nodo del clúster")
    public void testRestPortMismatchFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("jettra.network.rest.port", "8888");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasRestError = result.getErrors().stream()
            .anyMatch(err -> err.contains("jettra.network.rest.port") && err.contains("no coincide"));
        assertTrue(hasRestError);
    }

    @Test
    @DisplayName("Debe generar automáticamente microprofile-config.properties y cluster.config si no existen")
    public void testAutoGenerationOfMissingConfigFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_auto_gen_test");
        Path targetDb = tempDir.resolve("microprofile-config.properties");
        Path targetCluster = tempDir.resolve("cluster.config");

        assertFalse(Files.exists(targetDb));
        assertFalse(Files.exists(targetCluster));

        JettraConfigValidator.generateDefaultMicroProfileConfig(targetDb);
        JettraConfigValidator.generateDefaultClusterConfig(targetCluster);

        assertTrue(Files.exists(targetDb));
        assertTrue(Files.exists(targetCluster));

        Properties generatedDb = new Properties();
        generatedDb.load(Files.newInputStream(targetDb));

        Properties generatedCluster = new Properties();
        generatedCluster.load(Files.newInputStream(targetCluster));

        // Las configuraciones autogeneradas deben ser válidas entre sí
        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(generatedDb, generatedCluster);
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());

        // Limpiar
        Files.deleteIfExists(targetDb);
        Files.deleteIfExists(targetCluster);
        Files.deleteIfExists(tempDir);
    }

    @Test
    @DisplayName("Debe validar exitosamente con cluster.multinode.active en on y en off")
    public void testClusterMultinodeActiveOnAndOffPasses() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbPropsOn = createValidDatabaseProperties();
        dbPropsOn.setProperty("cluster.multinode.active", "on");

        JettraConfigValidator.ValidationResult resultOn = JettraConfigValidator.validate(dbPropsOn, clusterProps);
        assertTrue(resultOn.isValid());

        Properties dbPropsOff = createValidDatabaseProperties();
        dbPropsOff.setProperty("cluster.multinode.active", "off");

        JettraConfigValidator.ValidationResult resultOff = JettraConfigValidator.validate(dbPropsOff, clusterProps);
        assertTrue(resultOff.isValid());
    }

    @Test
    @DisplayName("Debe fallar si cluster.multinode.active contiene un valor no permitido")
    public void testClusterMultinodeActiveInvalidValueFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("cluster.multinode.active", "maybe");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasMultinodeError = result.getErrors().stream()
            .anyMatch(err -> err.contains("cluster.multinode.active") && err.contains("inválido"));
        assertTrue(hasMultinodeError);
    }

    @Test
    @DisplayName("Debe cargar IP, puertos y peers desde cluster.config y microprofile-config.properties en JettraStoreConfig")
    public void testJettraStoreConfigLoadsClusterTopology() {
        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.multinode.active", "on");
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.role", "PRIMARY");
        clusterProps.setProperty("cluster.node.1.ip", "192.168.1.101");
        clusterProps.setProperty("cluster.node.1.grpc.port", "9091");
        clusterProps.setProperty("cluster.node.1.rest.port", "8080");
        clusterProps.setProperty("cluster.node.1.storage.path", "~/jettra/data");

        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.role", "SECONDARY");
        clusterProps.setProperty("cluster.node.2.ip", "192.168.1.102");
        clusterProps.setProperty("cluster.node.2.grpc.port", "9092");
        clusterProps.setProperty("cluster.node.2.rest.port", "8082");
        clusterProps.setProperty("cluster.node.2.storage.path", "~/jettra/data");

        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.cluster.node.id", "node-01");
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");

        JettraStoreConfig config = new JettraStoreConfig(dbProps, clusterProps);

        assertEquals("node-01", config.getNodeId());
        assertEquals("192.168.1.101", config.getNodeIp());
        assertEquals(9091, config.getGrpcPort());
        assertEquals(8080, config.getRestPort());
        assertTrue(config.isClusterMultinodeActive());

        var peers = config.getParsedPeers();
        assertEquals(1, peers.size());
        var peer = peers.get(0);
        assertEquals("node-02", peer.getId());
        assertEquals("192.168.1.102", peer.getIp());
        assertEquals(9092, peer.getPort());
        assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, peer.getRole());
    }

    @Test
    @DisplayName("Debe ubicar microprofile-config.properties en la misma carpeta que cluster.config mediante sibling resolution")
    public void testLocateMicroProfilePropertiesSibling() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_sibling_test");
        Path cfgFile = tempDir.resolve("cluster.config");
        Path dbFile = tempDir.resolve("microprofile-config.properties");

        Files.writeString(cfgFile, "cluster.node.1.id=node-01\n");
        Files.writeString(dbFile, "jettra.cluster.node.id=node-01\n");

        String prev = System.getProperty("cluster.config.path");
        try {
            System.setProperty("cluster.config.path", cfgFile.toString());
            Path locatedDb = JettraConfigValidator.locateMicroProfileConfigFile();
            assertNotNull(locatedDb);
            assertEquals(dbFile.toAbsolutePath().normalize(), locatedDb.toAbsolutePath().normalize());
        } finally {
            if (prev != null) {
                System.setProperty("cluster.config.path", prev);
            } else {
                System.clearProperty("cluster.config.path");
            }
            Files.deleteIfExists(cfgFile);
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @DisplayName("Debe resolver nodeId y role desde propiedades del sistema -Djettra.node.id y -Djettra.node.role")
    public void testNodeIdAndRoleViaSystemProperties() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");
        dbProps.setProperty("jettra.network.grpc.port", "9092");
        dbProps.setProperty("jettra.network.rest.port", "8082");

        String prevId = System.getProperty("jettra.node.id");
        String prevRole = System.getProperty("jettra.node.role");
        try {
            System.setProperty("jettra.node.id", "node-02");
            System.setProperty("jettra.node.role", "SECONDARY");

            JettraStoreConfig config = new JettraStoreConfig(dbProps, clusterProps);
            assertEquals("node-02", config.getNodeId());
            assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, config.getNodeRole());
            assertEquals(9092, config.getGrpcPort());
            assertEquals(8082, config.getRestPort());
            assertEquals("~/jettra/data", config.getConfiguredStoragePath());
        } finally {
            if (prevId != null) System.setProperty("jettra.node.id", prevId);
            else System.clearProperty("jettra.node.id");
            if (prevRole != null) System.setProperty("jettra.node.role", prevRole);
            else System.clearProperty("jettra.node.role");
        }
    }

    @Test
    @DisplayName("Debe leer nodeId, role, puertos y storage.path / jettra.storage.path directamente de microprofile-config.properties")
    public void testNodeConfigurationDirectlyFromMicroProfileProperties() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = new Properties();
        dbProps.setProperty("jettra.node.id", "node-03");
        dbProps.setProperty("jettra.node.role", "SECONDARY");
        dbProps.setProperty("storage.path", "~/jettra/data");
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");
        dbProps.setProperty("jettra.network.grpc.port", "9093");
        dbProps.setProperty("jettra.network.rest.port", "8083");
        dbProps.setProperty("index.storage.path", "~/jettra/data/indexes");
        dbProps.setProperty("jettra.index.storage.path", "~/jettra/data/indexes");

        JettraStoreConfig config = new JettraStoreConfig(dbProps, clusterProps);
        assertEquals("node-03", config.getNodeId());
        assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, config.getNodeRole());
        assertEquals(9093, config.getGrpcPort());
        assertEquals(8083, config.getRestPort());
        assertEquals("~/jettra/data", config.getConfiguredStoragePath());

        // Validar que pase la validación cruzada
        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("Debe garantizar soporte tanto para 'storage.path' como para 'jettra.storage.path'")
    public void testStoragePathAndJettraStoragePathBothSupported() throws IOException {
        Path tempDir1 = Files.createTempDirectory("jettra_storage_path_test_1");
        Path tempDir2 = Files.createTempDirectory("jettra_storage_path_test_2");
        try {
            // Caso 1: Solo storage.path configurado
            Properties props1 = new Properties();
            props1.setProperty("storage.path", tempDir1.toAbsolutePath().toString());
            JettraStoreConfig config1 = new JettraStoreConfig(props1);
            assertEquals(tempDir1.toAbsolutePath().toString(), config1.getStoragePath());

            // Caso 2: Solo jettra.storage.path configurado
            Properties props2 = new Properties();
            props2.setProperty("jettra.storage.path", tempDir2.toAbsolutePath().toString());
            JettraStoreConfig config2 = new JettraStoreConfig(props2);
            assertEquals(tempDir2.toAbsolutePath().toString(), config2.getStoragePath());

            // Caso 3: Ambos configurados con storage.path personalizado
            Properties props3 = new Properties();
            props3.setProperty("storage.path", tempDir1.toAbsolutePath().toString());
            props3.setProperty("jettra.storage.path", "~/jettra/data");
            JettraStoreConfig config3 = new JettraStoreConfig(props3);
            assertEquals(tempDir1.toAbsolutePath().toString(), config3.getStoragePath());

            // Caso 4: Ambos configurados con jettra.storage.path personalizado
            Properties props4 = new Properties();
            props4.setProperty("storage.path", "~/jettra/data");
            props4.setProperty("jettra.storage.path", tempDir2.toAbsolutePath().toString());
            JettraStoreConfig config4 = new JettraStoreConfig(props4);
            assertEquals(tempDir2.toAbsolutePath().toString(), config4.getStoragePath());
        } finally {
            Files.deleteIfExists(tempDir1);
            Files.deleteIfExists(tempDir2);
        }
    }

    @Test
    @DisplayName("Debe garantizar la persistencia de bases de datos y objetos en el path storage.path")
    public void testDatabaseObjectsPersistenceInStoragePath() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_db_persistence_test");
        try {
            Properties props = new Properties();
            props.setProperty("storage.path", tempDir.toAbsolutePath().toString());
            props.setProperty("jettra.storage.mode", "OFF_HEAP");
            JettraStoreConfig config = new JettraStoreConfig(props);

            try (JettraDatabase db = new JettraDatabase("custom_persist_db", config)) {
                db.getDocumentEngine("items").insert("item1", java.util.Map.of("name", "Laptop", "price", 1200));
                db.flushMemTable();
                db.saveToDisk();

                // Verificar existencia de metadata y sstable en el path configurado
                Path metaFile = Path.of(config.getStoragePath(), "custom_persist_db_meta.json");
                Path sstableFile = Path.of(config.getStoragePath(), "custom_persist_db_sstable" + config.getFileExtension());

                assertTrue(Files.exists(metaFile), "El archivo metadata _meta.json debe existir en storage.path");
                assertTrue(Files.exists(sstableFile), "El archivo _sstable debe existir en storage.path");
                assertTrue(Files.size(metaFile) > 0);
            }
        } finally {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Debe rechazar la configuración y emitir advertencia si cluster.node.id no existe en cluster.config")
    public void testValidationRejectsUnrecognizedNodeId() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("cluster.node.id", "node-99");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        assertTrue(result.hasWarnings());
        boolean hasNodeWarning = result.getWarnings().stream()
            .anyMatch(w -> w.contains("node-99"));
        assertTrue(hasNodeWarning);
        boolean hasNodeError = result.getErrors().stream()
            .anyMatch(err -> err.contains("node-99"));
        assertTrue(hasNodeError);
    }

    @Test
    @DisplayName("Debe obtener rol, ip, puertos gRPC/REST, storage.path e indexes de cluster.config usando cluster.node.id=node-01 o 01")
    public void testResolveNodeConfigFromClusterConfigWithoutDuplicateProperties() {
        Properties clusterProps = new Properties();
        clusterProps.setProperty("cluster.node.1.id", "node-01");
        clusterProps.setProperty("cluster.node.1.role", "PRIMARY");
        clusterProps.setProperty("cluster.node.1.ip", "192.168.60.243");
        clusterProps.setProperty("cluster.node.1.grpc.port", "9091");
        clusterProps.setProperty("cluster.node.1.rest.port", "8081");
        clusterProps.setProperty("cluster.node.1.storage.path", "~/jettra/data");

        clusterProps.setProperty("cluster.node.2.id", "node-02");
        clusterProps.setProperty("cluster.node.2.role", "SECONDARY");
        clusterProps.setProperty("cluster.node.2.ip", "192.168.60.243");
        clusterProps.setProperty("cluster.node.2.grpc.port", "9092");
        clusterProps.setProperty("cluster.node.2.rest.port", "8082");
        clusterProps.setProperty("cluster.node.2.storage.path", "~/jettra/data");

        // Caso A: cluster.node.id=node-01 (sin propiedades duplicadas en microprofile-config.properties)
        Properties mpProps1 = new Properties();
        mpProps1.setProperty("cluster.node.id", "node-01");
        mpProps1.setProperty("cluster.node.role", "PRIMARY");
        mpProps1.setProperty("cluster.multinode.active", "on");
        mpProps1.setProperty("jettra.storage.path", "~/jettra/data");

        JettraConfigValidator.ValidationResult val1 = JettraConfigValidator.validate(mpProps1, clusterProps);
        assertTrue(val1.isValid());

        JettraStoreConfig cfg1 = new JettraStoreConfig(mpProps1, clusterProps);
        assertEquals("node-01", cfg1.getNodeId());
        assertEquals(io.jettra.store.cluster.ClusterNode.Role.PRIMARY, cfg1.getNodeRole());
        assertEquals("192.168.60.243", cfg1.getNodeIp());
        assertEquals(9091, cfg1.getGrpcPort());
        assertEquals(8081, cfg1.getRestPort());
        assertEquals("~/jettra/data", cfg1.getConfiguredStoragePath());
        assertTrue(cfg1.getIndexStoragePath().endsWith("/jettra/data/indexes"));

        // Caso B: cluster.node.id=01 (coincidencia numérica con cluster.node.1.*)
        Properties mpPropsShort = new Properties();
        mpPropsShort.setProperty("cluster.node.id", "01");
        mpPropsShort.setProperty("cluster.node.role", "PRIMARY");

        JettraConfigValidator.ValidationResult valShort = JettraConfigValidator.validate(mpPropsShort, clusterProps);
        assertTrue(valShort.isValid());

        JettraStoreConfig cfgShort = new JettraStoreConfig(mpPropsShort, clusterProps);
        assertEquals("node-01", cfgShort.getNodeId());
        assertEquals("192.168.60.243", cfgShort.getNodeIp());
        assertEquals(9091, cfgShort.getGrpcPort());
        assertEquals(8081, cfgShort.getRestPort());

        // Caso C: cluster.node.id=node-02
        Properties mpProps2 = new Properties();
        mpProps2.setProperty("cluster.node.id", "node-02");
        mpProps2.setProperty("cluster.node.role", "SECONDARY");

        JettraStoreConfig cfg2 = new JettraStoreConfig(mpProps2, clusterProps);
        assertEquals("node-02", cfg2.getNodeId());
        assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, cfg2.getNodeRole());
        assertEquals("192.168.60.243", cfg2.getNodeIp());
        assertEquals(9092, cfg2.getGrpcPort());
        assertEquals(8082, cfg2.getRestPort());
    }

    @Test
    @DisplayName("Debe permitir agregar nodos dinámicamente a cluster.config y validarlos correctamente")
    public void testDynamicNodeRegistrationInClusterConfig() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_dynamic_node_test");
        Path clusterFile = tempDir.resolve("cluster.config");
        Path node4Storage = tempDir.resolve("data-node4");
        try {
            JettraConfigValidator.generateDefaultClusterConfig(clusterFile);

            // Agregar dinámicamente el nodo 4 (node-04)
            JettraConfigValidator.ClusterNodeInfo added = JettraConfigValidator.registerDynamicNodeInClusterConfig(
                clusterFile,
                "node-04",
                "SECONDARY",
                "192.168.60.244",
                9094,
                8084,
                node4Storage.toAbsolutePath().toString()
            );

            assertEquals("node-04", added.id());
            assertEquals("4", added.nodeNumber());
            assertEquals("192.168.60.244", added.ip());
            assertEquals(9094, added.grpcPort());
            assertEquals(8084, added.restPort());

            Properties loadedCluster = new Properties();
            try (var in = Files.newInputStream(clusterFile)) {
                loadedCluster.load(in);
            }

            Properties mpProps = new Properties();
            mpProps.setProperty("cluster.node.id", "node-04");
            mpProps.setProperty("cluster.node.role", "SECONDARY");
            mpProps.setProperty("cluster.multinode.active", "on");
            mpProps.setProperty("jettra.storage.path", node4Storage.toAbsolutePath().toString());

            JettraConfigValidator.ValidationResult val = JettraConfigValidator.validate(mpProps, loadedCluster);
            assertTrue(val.isValid());

            JettraStoreConfig cfg = new JettraStoreConfig(mpProps, loadedCluster);
            assertEquals("node-04", cfg.getNodeId());
            assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, cfg.getNodeRole());
            assertEquals("192.168.60.244", cfg.getNodeIp());
            assertEquals(9094, cfg.getGrpcPort());
            assertEquals(8084, cfg.getRestPort());
            assertEquals(node4Storage.toAbsolutePath().toString(), cfg.getConfiguredStoragePath());
        } finally {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Debe verificar y crear automáticamente el directorio cluster.node.<N>.storage.path al arrancar para almacenar la base de datos y objetos")
    public void testStartupCreatesNodeStorageDirectoryIfMissing() throws IOException {
        Path tempRoot = Files.createTempDirectory("jettra_auto_mkdir_test");
        Path node1MissingDir = tempRoot.resolve("node-01-storage").resolve("jettra").resolve("data");
        try {
            assertFalse(Files.exists(node1MissingDir), "El directorio no debe existir antes del arranque");

            Properties clusterProps = createValidClusterProperties();

            Properties mpProps = new Properties();
            mpProps.setProperty("cluster.node.id", "node-01");
            mpProps.setProperty("cluster.node.role", "PRIMARY");
            mpProps.setProperty("cluster.multinode.active", "on");
            mpProps.setProperty("jettra.storage.path", node1MissingDir.toAbsolutePath().toString());
            mpProps.setProperty("jettra.storage.mode", "OFF_HEAP");

            // 1. Al validar durante el arranque se verifica y crea el directorio si no existe
            JettraConfigValidator.ValidationResult val = JettraConfigValidator.validate(mpProps, clusterProps);
            assertTrue(val.isValid());
            assertTrue(Files.exists(node1MissingDir), "El directorio cluster.node.1.storage.path debe haberse creado al validar el arranque");
            assertTrue(Files.isDirectory(node1MissingDir), "La ruta creada debe ser un directorio");

            // 2. Al cargar JettraStoreConfig y persistir una base de datos y objetos
            JettraStoreConfig config = new JettraStoreConfig(mpProps, clusterProps);
            assertEquals(node1MissingDir.toAbsolutePath().toString(), config.getStoragePath());

            try (JettraDatabase db = new JettraDatabase("startup_dir_db", config)) {
                db.getDocumentEngine("clientes").insert("c1", java.util.Map.of("nombre", "Ana", "activo", true));
                db.flushMemTable();
                db.saveToDisk();

                Path metaFile = node1MissingDir.resolve("startup_dir_db_meta.json");
                Path sstableFile = node1MissingDir.resolve("startup_dir_db_sstable" + config.getFileExtension());
                assertTrue(Files.exists(metaFile), "El archivo de metadatos debe guardarse en el directorio creado");
                assertTrue(Files.exists(sstableFile), "El archivo de objetos SSTable debe guardarse en el directorio creado");
            }
        } finally {
            try (var stream = Files.walk(tempRoot)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Debe almacenar los datos en jettra.storage.path de microprofile-config.properties para node-01, node-02, node-03, node-04 y con -Djettra.node.id")
    public void testPerNodeClusterStoragePathResolutionAndPersistence() throws IOException {
        Path tempRoot = Files.createTempDirectory("jettra_per_node_storage_test");
        Path data1 = tempRoot.resolve("data-1");
        Path data2 = tempRoot.resolve("data-2");
        Path data3 = tempRoot.resolve("data-3");
        Path data4 = tempRoot.resolve("data-4");

        String prevNodeId = System.getProperty("jettra.node.id");
        String prevNodeRole = System.getProperty("jettra.node.role");
        try {
            Properties clusterProps = createValidClusterProperties();
            clusterProps.remove("cluster.node.1.storage.path");
            clusterProps.remove("cluster.node.2.storage.path");
            clusterProps.remove("cluster.node.3.storage.path");

            clusterProps.setProperty("cluster.node.4.id", "node-04");
            clusterProps.setProperty("cluster.node.4.ip", "192.168.60.244");
            clusterProps.setProperty("cluster.node.4.rest.port", "8084");
            clusterProps.setProperty("cluster.node.4.grpc.port", "9094");
            clusterProps.setProperty("cluster.node.4.role", "SECONDARY");

            // 1. Verificar resolución via jettra.storage.path en microprofile-config.properties para 4 nodos
            String[] nodeIds = {"node-01", "node-02", "node-03", "node-04"};
            String[] roles = {"PRIMARY", "SECONDARY", "SECONDARY", "SECONDARY"};
            Path[] expectedPaths = {data1, data2, data3, data4};

            for (int i = 0; i < nodeIds.length; i++) {
                Properties mpProps = new Properties();
                mpProps.setProperty("cluster.node.id", nodeIds[i]);
                mpProps.setProperty("cluster.node.role", roles[i]);
                mpProps.setProperty("cluster.multinode.active", "on");
                mpProps.setProperty("jettra.storage.path", expectedPaths[i].toAbsolutePath().toString());
                mpProps.setProperty("jettra.storage.mode", "JVM_RAM");

                JettraStoreConfig cfg = new JettraStoreConfig(mpProps, clusterProps);
                assertEquals(expectedPaths[i].toAbsolutePath().toString(), cfg.getStoragePath(),
                    "El nodo " + nodeIds[i] + " debe resolver su jettra.storage.path desde microprofile-config.properties");
            }

            // 2. Verificar override estilo start-node.sh (-Djettra.node.id=node-03 -Djettra.node.role=SECONDARY)
            Properties baseMpProps = new Properties();
            baseMpProps.setProperty("cluster.node.id", "node-01");
            baseMpProps.setProperty("cluster.node.role", "PRIMARY");
            baseMpProps.setProperty("cluster.multinode.active", "on");
            baseMpProps.setProperty("jettra.storage.path", data3.toAbsolutePath().toString());
            baseMpProps.setProperty("jettra.storage.mode", "OFF_HEAP");

            System.setProperty("jettra.node.id", "node-03");
            System.setProperty("jettra.node.role", "SECONDARY");

            JettraStoreConfig node3Cfg = new JettraStoreConfig(baseMpProps, clusterProps);
            assertEquals("node-03", node3Cfg.getNodeId());
            assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, node3Cfg.getNodeRole());
            assertEquals(data3.toAbsolutePath().toString(), node3Cfg.getStoragePath());

            try (JettraDatabase db = new JettraDatabase("node3_persist_db", node3Cfg)) {
                db.saveToDisk();

                assertTrue(Files.exists(data3.resolve("node3_persist_db_meta.json")),
                    "Los datos deben guardarse en jettra.storage.path configurado en microprofile-config.properties");
            }
        } finally {
            if (prevNodeId == null) {
                System.clearProperty("jettra.node.id");
            } else {
                System.setProperty("jettra.node.id", prevNodeId);
            }
            if (prevNodeRole == null) {
                System.clearProperty("jettra.node.role");
            } else {
                System.setProperty("jettra.node.role", prevNodeRole);
            }
            try (var stream = Files.walk(tempRoot)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Debe validar integridad de cluster.config rechazando IDs duplicados y puertos gRPC/REST duplicados, y limpiar completamente nodos removidos")
    public void testClusterConfigIntegrityDuplicatePreventionAndCleanRemoval() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_cfg_integrity_test");
        Path cfgFile = tempDir.resolve("cluster.config");
        try {
            // 1. Crear archivo inicial con 2 nodos válidos
            String initial = """
                cluster.name = jettra-test
                cluster.node.1.id = node-01
                cluster.node.1.role = PRIMARY
                cluster.node.1.ip = 127.0.0.1
                cluster.node.1.grpc.port = 50001
                cluster.node.1.rest.port = 60001
                cluster.node.2.id = node-02
                cluster.node.2.role = SECONDARY
                cluster.node.2.ip = 127.0.0.1
                cluster.node.2.grpc.port = 50002
                cluster.node.2.rest.port = 60002
                """;
            Files.writeString(cfgFile, initial);
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(initial).isValid(), "Config inicial debe ser válida");

            // 2. Validar rechazo de ID duplicado
            String dupId = initial + """
                cluster.node.3.id = node-02
                cluster.node.3.role = SECONDARY
                cluster.node.3.ip = 127.0.0.1
                cluster.node.3.grpc.port = 50003
                cluster.node.3.rest.port = 60003
                """;
            var dupIdRes = JettraConfigValidator.validateClusterConfigIntegrity(dupId);
            assertFalse(dupIdRes.isValid(), "Debe rechazar ID de nodo duplicado");
            assertFalse(JettraConfigValidator.writeClusterConfigAtomically(cfgFile, dupId.getBytes()), "writeClusterConfigAtomically debe rechazar ID duplicado");

            // 3. Validar rechazo de puerto gRPC duplicado
            String dupGrpc = initial + """
                cluster.node.3.id = node-03
                cluster.node.3.role = SECONDARY
                cluster.node.3.ip = 127.0.0.1
                cluster.node.3.grpc.port = 50002
                cluster.node.3.rest.port = 60003
                """;
            assertFalse(JettraConfigValidator.validateClusterConfigIntegrity(dupGrpc).isValid(), "Debe rechazar puerto gRPC duplicado");

            // 4. Validar rechazo de puerto REST duplicado
            String dupRest = initial + """
                cluster.node.3.id = node-03
                cluster.node.3.role = SECONDARY
                cluster.node.3.ip = 127.0.0.1
                cluster.node.3.grpc.port = 50003
                cluster.node.3.rest.port = 60002
                """;
            assertFalse(JettraConfigValidator.validateClusterConfigIntegrity(dupRest).isValid(), "Debe rechazar puerto REST duplicado");

            // 5. Eliminar node-02 y verificar que se borran TODOS sus datos y queda limpio para re-agregarlo
            assertTrue(JettraConfigValidator.removeDynamicNodeFromClusterConfig(cfgFile, "node-02"), "Debe eliminar node-02");
            String afterRemove = Files.readString(cfgFile);
            assertFalse(afterRemove.contains("node-02"), "cluster.config no debe conservar referencias a node-02 tras removerlo");
            assertFalse(afterRemove.contains("50002"), "cluster.config no debe conservar el puerto gRPC de node-02 tras removerlo");
            assertFalse(afterRemove.contains("60002"), "cluster.config no debe conservar el puerto REST de node-02 tras removerlo");
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(afterRemove).isValid(), "cluster.config tras remover debe ser íntegro");

            // 6. Volver a agregar node-02 sin conflictos
            JettraConfigValidator.ClusterNodeInfo reAdded = JettraConfigValidator.registerDynamicNodeInClusterConfig(
                    cfgFile, "node-02", "SECONDARY", "127.0.0.1", 50002, 60002, "~/jettra/data");
            assertEquals("node-02", reAdded.id());
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(Files.readString(cfgFile)).isValid());
        } finally {
            Files.deleteIfExists(cfgFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @DisplayName("RF-37: Resuelve cluster.node.ip, cluster.node.grpc.port y cluster.node.rest.port desde microprofile-config.properties")
    public void testRf37NodeIpAndPortsLoadedFromMicroProfileConfig() throws IOException {
        Path tempRoot = Files.createTempDirectory("jettra_rf37_props_");
        try {
            Properties mpProps = new Properties();
            mpProps.setProperty("cluster.node.id", "node-02");
            mpProps.setProperty("cluster.node.role", "SECONDARY");
            mpProps.setProperty("cluster.node.ip", "10.20.30.40");
            mpProps.setProperty("cluster.node.grpc.port", "50002");
            mpProps.setProperty("cluster.node.rest.port", "60002");
            mpProps.setProperty("jettra.storage.path", tempRoot.resolve("data").toAbsolutePath().toString());
            mpProps.setProperty("jettra.cluster_state_config_dir", tempRoot.resolve("state").toAbsolutePath().toString());
            mpProps.setProperty("cluster.multinode.active", "on");

            JettraStoreConfig cfg = new JettraStoreConfig(mpProps, new Properties(), mpProps);
            assertEquals("node-02", cfg.getNodeId());
            assertEquals(io.jettra.store.cluster.ClusterNode.Role.SECONDARY, cfg.getNodeRole());
            assertEquals("10.20.30.40", cfg.getNodeIp());
            assertEquals(50002, cfg.getGrpcPort());
            assertEquals(60002, cfg.getRestPort());
            assertTrue(cfg.isStandbyMode(), "SECONDARY sin cluster-state.config ni cluster.config debe iniciar en STANDBY");
        } finally {
            try (var s = Files.walk(tempRoot)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("RF-37 Evento 1: PRIMARY valida IP, puertos gRPC/REST y rol contra cluster.config antes de crear cluster-state.config")
    public void testRf37PrimaryEvent1StrictValidationAgainstClusterConfig() throws IOException {
        Path tempRoot = Files.createTempDirectory("jettra_rf37_event1_");
        Path mpFile = tempRoot.resolve("microprofile-config.properties");
        Path clusterFile = tempRoot.resolve("cluster.config");
        Path stateDir = tempRoot.resolve("state");

        String prevMp = System.getProperty("microprofile.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");
        try {
            Files.writeString(clusterFile, """
                    cluster.name = rf37-cluster
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 50001
                    cluster.node.1.rest.port = 60001
                    """, java.nio.charset.StandardCharsets.UTF_8);

            // 1. Discrepancia de puerto REST -> debe fallar ANTES de crear cluster-state.config
            Files.writeString(mpFile, String.format("""
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.node.ip=127.0.0.1
                    cluster.node.grpc.port=50001
                    cluster.node.rest.port=60099
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, tempRoot.resolve("data").toAbsolutePath(), stateDir.toAbsolutePath()),
                    java.nio.charset.StandardCharsets.UTF_8);

            System.setProperty("microprofile.config.file", mpFile.toAbsolutePath().toString());
            System.setProperty("cluster.config.path", clusterFile.toAbsolutePath().toString());
            System.clearProperty("jettra.cluster_state_config_dir");

            assertThrows(JettraConfigurationException.class, () -> JettraConfigValidator.validateAndBootstrapOrHalt(false));
            Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertFalse(Files.exists(stateFile), "No debe crear cluster-state.config si PRIMARY falla validación contra cluster.config");

            // 2. Discrepancia de IP y puerto gRPC en validate()
            Properties badIpMp = new Properties();
            badIpMp.setProperty("cluster.node.id", "node-01");
            badIpMp.setProperty("cluster.node.role", "PRIMARY");
            badIpMp.setProperty("cluster.node.ip", "10.99.99.99");
            badIpMp.setProperty("cluster.node.grpc.port", "50001");
            badIpMp.setProperty("cluster.node.rest.port", "60001");
            Properties clProps = JettraConfigValidator.loadProperties(clusterFile);
            assertFalse(JettraConfigValidator.validate(badIpMp, clProps).isValid(), "IP discordante en PRIMARY debe fallar");

            Properties badGrpcMp = new Properties();
            badGrpcMp.setProperty("cluster.node.id", "node-01");
            badGrpcMp.setProperty("cluster.node.role", "PRIMARY");
            badGrpcMp.setProperty("cluster.node.ip", "127.0.0.1");
            badGrpcMp.setProperty("cluster.node.grpc.port", "59999");
            badGrpcMp.setProperty("cluster.node.rest.port", "60001");
            assertFalse(JettraConfigValidator.validate(badGrpcMp, clProps).isValid(), "Puerto gRPC discordante en PRIMARY debe fallar");

            // 3. Configuración consistente -> valida y siembra cluster-state.config en jettra.cluster_state_config_dir
            Files.writeString(mpFile, String.format("""
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.node.ip=127.0.0.1
                    cluster.node.grpc.port=50001
                    cluster.node.rest.port=60001
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, tempRoot.resolve("data").toAbsolutePath(), stateDir.toAbsolutePath()),
                    java.nio.charset.StandardCharsets.UTF_8);

            JettraConfigValidator.ValidationResult ok = JettraConfigValidator.validateAndBootstrapOrHalt(false);
            assertTrue(ok.isValid());
            assertTrue(Files.exists(stateFile) && Files.size(stateFile) > 0,
                    "Debe crear cluster-state.config en jettra.cluster_state_config_dir tras validar PRIMARY");
        } finally {
            if (prevMp != null) System.setProperty("microprofile.config.file", prevMp); else System.clearProperty("microprofile.config.file");
            if (prevCluster != null) System.setProperty("cluster.config.path", prevCluster); else System.clearProperty("cluster.config.path");
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys); else System.clearProperty("jettra.cluster_state_config_dir");
            try (var s = Files.walk(tempRoot)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("RF-37: Conflicto con cluster-state.config muestra banner correcto y resuelve opciones 1, 2 y 3 en PRIMARY; SECONDARY inicia sin bloqueo")
    public void testClusterStateConflictBannerAndInteractiveMenuOptions() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf37_conflict_menu_");
        Path mpFile = tempRoot.resolve("microprofile-config.properties");
        Path stateDir = tempRoot.resolve("state");
        Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
        Path dataDir = tempRoot.resolve("data");

        String prevMp = System.getProperty("microprofile.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");
        String prevIp = System.getProperty("cluster.node.ip");
        String prevGrpc = System.getProperty("cluster.node.grpc.port");
        String prevRest = System.getProperty("cluster.node.rest.port");

        try {
            Files.createDirectories(stateDir);
            Files.createDirectories(dataDir);
            System.setProperty("microprofile.config.file", mpFile.toAbsolutePath().toString());
            System.clearProperty("cluster.config.path");
            System.clearProperty("jettra.cluster_state_config_dir");
            System.clearProperty("cluster.node.ip");
            System.clearProperty("cluster.node.grpc.port");
            System.clearProperty("cluster.node.rest.port");

            // Estado inicial en cluster-state.config con IP 127.0.0.1 y un SECONDARY node-02
            JettraConfigValidator.upsertNodeInStateFile(
                    stateFile, "node-01", "PRIMARY", "127.0.0.1", 50001, 60001, "RUNNING", "init");
            JettraConfigValidator.upsertNodeInStateFile(
                    stateFile, "node-02", "SECONDARY", "127.0.0.1", 50002, 60002, "RUNNING", "init");

            String mpContentWithMismatch = String.format("""
                    # Comentario preservado
                    mp.config.profile=production
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.node.ip=192.168.60.243
                    cluster.node.grpc.port=51001
                    cluster.node.rest.port=61001
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, dataDir.toAbsolutePath(), stateDir.toAbsolutePath());
            Files.writeString(mpFile, mpContentWithMismatch, java.nio.charset.StandardCharsets.UTF_8);

            // Verificar que el banner y errores identifican a cluster-state.config (no cluster.config)
            Properties dbProps = JettraConfigValidator.loadProperties(mpFile);
            Properties stateProps = JettraConfigValidator.loadProperties(stateFile);
            JettraConfigValidator.ValidationResult mismatch =
                    JettraConfigValidator.validate(dbProps, stateProps, JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertFalse(mismatch.isValid());
            assertTrue(mismatch.getNotification().contains("Incompatibilidad entre microprofile-config.properties y cluster-state.config"),
                    "El banner debe indicar incompatibilidad con cluster-state.config");
            assertTrue(mismatch.getErrors().stream().allMatch(e -> e.contains("cluster-state.config") && !e.contains("cluster.config")),
                    "Todos los mensajes deben señalar cluster-state.config y no cluster.config");

            // Opción 3 (Salir): debe retornar inválido sin alterar archivos
            java.io.ByteArrayOutputStream baos3 = new java.io.ByteArrayOutputStream();
            JettraConfigValidator.ValidationResult res3 = JettraConfigValidator.resolvePrimaryClusterStateConflictInteractive(
                    mpFile, dbProps, stateFile, stateProps, mismatch,
                    new java.io.BufferedReader(new java.io.StringReader("3\n")),
                    new java.io.PrintStream(baos3, true, java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(res3.isValid(), "Opción 3 debe abortar el arranque");
            String out3 = baos3.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(out3.contains("Acciones:"));
            assertTrue(out3.contains("1. Modifique cluster-state.config con microprofile-config.properties"));
            assertTrue(out3.contains("2. Modifique microprofile-config.properties con cluster-state.config"));
            assertTrue(out3.contains("3. Salir"));

            // Opción 1: actualiza cluster-state.config con IP y puertos de microprofile-config.properties
            java.io.ByteArrayOutputStream baos1 = new java.io.ByteArrayOutputStream();
            JettraConfigValidator.ValidationResult res1 = JettraConfigValidator.resolvePrimaryClusterStateConflictInteractive(
                    mpFile, dbProps, stateFile, stateProps, mismatch,
                    new java.io.BufferedReader(new java.io.StringReader("9\n1\n")),
                    new java.io.PrintStream(baos1, true, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(res1.isValid(), "Opción 1 debe reconciliar cluster-state.config con microprofile-config.properties");
            var nodesAfterOpt1 = JettraConfigValidator.parseExplicitClusterNodes(JettraConfigValidator.loadProperties(stateFile));
            var n1AfterOpt1 = JettraConfigValidator.findNodeInCluster(nodesAfterOpt1, "node-01");
            assertNotNull(n1AfterOpt1);
            assertEquals("192.168.60.243", n1AfterOpt1.ip());
            assertEquals(51001, n1AfterOpt1.grpcPort());
            assertEquals(61001, n1AfterOpt1.restPort());
            assertNotNull(JettraConfigValidator.findNodeInCluster(nodesAfterOpt1, "node-02"),
                    "Opción 1 debe conservar los demás nodos en cluster-state.config");

            // Opción 2: restaurar discrepancia en microprofile-config.properties y sincronizar desde cluster-state.config
            Files.writeString(mpFile, String.format("""
                    # Comentario preservado
                    mp.config.profile=production
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.node.ip=10.10.10.10
                    cluster.node.grpc.port=52001
                    cluster.node.rest.port=62001
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, dataDir.toAbsolutePath(), stateDir.toAbsolutePath()), java.nio.charset.StandardCharsets.UTF_8);

            Properties dbProps2 = JettraConfigValidator.loadProperties(mpFile);
            Properties stateProps2 = JettraConfigValidator.loadProperties(stateFile);
            JettraConfigValidator.ValidationResult mismatch2 =
                    JettraConfigValidator.validate(dbProps2, stateProps2, JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertFalse(mismatch2.isValid());

            java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream();
            JettraConfigValidator.ValidationResult res2 = JettraConfigValidator.resolvePrimaryClusterStateConflictInteractive(
                    mpFile, dbProps2, stateFile, stateProps2, mismatch2,
                    new java.io.BufferedReader(new java.io.StringReader("2\n")),
                    new java.io.PrintStream(baos2, true, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(res2.isValid(), "Opción 2 debe actualizar microprofile-config.properties con cluster-state.config");

            String updatedMpText = Files.readString(mpFile, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(updatedMpText.contains("# Comentario preservado"), "Opción 2 debe preservar comentarios existentes");
            Properties reloadedMp = JettraConfigValidator.loadProperties(mpFile);
            assertEquals("192.168.60.243", reloadedMp.getProperty("cluster.node.ip"));
            assertEquals("51001", reloadedMp.getProperty("cluster.node.grpc.port"));
            assertEquals("61001", reloadedMp.getProperty("cluster.node.rest.port"));

            // Verificar que validateAndBootstrapOrHalt(false) pasa ahora en PRIMARY y también en SECONDARY sin cluster.config
            assertTrue(JettraConfigValidator.validateAndBootstrapOrHalt(false).isValid());

            Files.writeString(mpFile, String.format("""
                    cluster.node.id=node-02
                    cluster.node.role=SECONDARY
                    cluster.node.ip=192.168.60.244
                    cluster.node.grpc.port=55002
                    cluster.node.rest.port=65002
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, dataDir.toAbsolutePath(), stateDir.toAbsolutePath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(JettraConfigValidator.validateAndBootstrapOrHalt(false).isValid(),
                    "SECONDARY debe arrancar con microprofile-config.properties sin exigir ni bloquearse con cluster.config");
        } finally {
            if (prevMp != null) System.setProperty("microprofile.config.file", prevMp); else System.clearProperty("microprofile.config.file");
            if (prevCluster != null) System.setProperty("cluster.config.path", prevCluster); else System.clearProperty("cluster.config.path");
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys); else System.clearProperty("jettra.cluster_state_config_dir");
            if (prevIp != null) System.setProperty("cluster.node.ip", prevIp); else System.clearProperty("cluster.node.ip");
            if (prevGrpc != null) System.setProperty("cluster.node.grpc.port", prevGrpc); else System.clearProperty("cluster.node.grpc.port");
            if (prevRest != null) System.setProperty("cluster.node.rest.port", prevRest); else System.clearProperty("cluster.node.rest.port");
            try (var s = Files.walk(tempRoot)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("PRIMARY lanzado desde NetBeans/IDE (META-INF/microprofile-config.properties + resources/cluster.config) siembra cluster-state.config sin error")
    public void testPrimaryNetBeansLayoutAndClasspathSeedTransfersClusterConfigToClusterState() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_netbeans_layout_");
        Path resourcesDir = tempRoot.resolve("src/main/resources");
        Path metaInfDir = resourcesDir.resolve("META-INF");
        Path mpFile = metaInfDir.resolve("microprofile-config.properties");
        Path clusterFile = resourcesDir.resolve("cluster.config");
        Path stateDir = tempRoot.resolve("statedir");
        Path dataDir = tempRoot.resolve("data");

        String prevMp = System.getProperty("microprofile.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");
        try {
            Files.createDirectories(metaInfDir);
            Files.createDirectories(dataDir);

            Files.writeString(clusterFile, """
                    cluster.name = netbeans-cluster
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 50001
                    cluster.node.1.rest.port = 60001
                    cluster.node.2.id = node-02
                    cluster.node.2.role = SECONDARY
                    cluster.node.2.ip = 127.0.0.1
                    cluster.node.2.grpc.port = 50002
                    cluster.node.2.rest.port = 60002
                    """, java.nio.charset.StandardCharsets.UTF_8);

            Files.writeString(mpFile, String.format("""
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.node.ip=127.0.0.1
                    cluster.node.grpc.port=50001
                    cluster.node.rest.port=60001
                    cluster.multinode.active=on
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=%s
                    """, dataDir.toAbsolutePath(), stateDir.toAbsolutePath()), java.nio.charset.StandardCharsets.UTF_8);

            System.setProperty("microprofile.config.file", mpFile.toAbsolutePath().toString());
            System.clearProperty("cluster.config.path");
            System.clearProperty("jettra.cluster_state_config_dir");

            Path resolvedSeed = JettraConfigValidator.locatePrimarySeedFile(mpFile, stateDir);
            assertNotNull(resolvedSeed, "Debe localizar resources/cluster.config desde META-INF/microprofile-config.properties");
            assertEquals(clusterFile.toAbsolutePath().normalize(), resolvedSeed.toAbsolutePath().normalize());

            JettraConfigValidator.ValidationResult val = JettraConfigValidator.validateAndBootstrapOrHalt(false);
            assertTrue(val.isValid(), "PRIMARY en layout NetBeans debe validar sin exigir -Dcluster.config.path");

            Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertTrue(Files.exists(stateFile) && Files.size(stateFile) > 0,
                    "Debe copiar el contenido de cluster.config a cluster-state.config");
            var nodes = JettraConfigValidator.parseExplicitClusterNodes(JettraConfigValidator.loadProperties(stateFile));
            assertEquals(2, nodes.size(), "cluster-state.config debe contener node-01 y node-02 de cluster.config");
            assertNotNull(JettraConfigValidator.findNodeInCluster(nodes, "node-01"));
            assertNotNull(JettraConfigValidator.findNodeInCluster(nodes, "node-02"));

            // Verificar también la siembra desde recurso /cluster.config del classpath para PRIMARY
            Path cpStateDir = tempRoot.resolve("cp-statedir");
            Files.createDirectories(cpStateDir);
            Path cpStateFile = cpStateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Properties cpSeeded = JettraConfigValidator.seedClusterStateFromClasspathIfApplicable(cpStateFile, null, new Properties());
            assertNotNull(cpSeeded, "Debe inicializar cluster-state.config desde classpath:/cluster.config");
            assertTrue(Files.exists(cpStateFile) && Files.size(cpStateFile) > 0);
            assertTrue(JettraConfigValidator.parseExplicitClusterNodes(cpSeeded).size() >= 1);
        } finally {
            if (prevMp != null) System.setProperty("microprofile.config.file", prevMp); else System.clearProperty("microprofile.config.file");
            if (prevCluster != null) System.setProperty("cluster.config.path", prevCluster); else System.clearProperty("cluster.config.path");
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys); else System.clearProperty("jettra.cluster_state_config_dir");
            try (var s = Files.walk(tempRoot)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }
}

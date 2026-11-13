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
            props.setProperty("jettra.storage.mode", "JVM_RAM");
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
            clusterProps.setProperty("cluster.node.1.storage.path", node1MissingDir.toAbsolutePath().toString());

            Properties mpProps = new Properties();
            mpProps.setProperty("cluster.node.id", "node-01");
            mpProps.setProperty("cluster.node.role", "PRIMARY");
            mpProps.setProperty("cluster.multinode.active", "on");
            mpProps.setProperty("jettra.storage.mode", "JVM_RAM");

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
}

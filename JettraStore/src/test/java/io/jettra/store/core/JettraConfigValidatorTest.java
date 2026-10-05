package io.jettra.store.core;

import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static io.jettra.test.core.JettraAssert.*;

public class JettraConfigValidatorTest {

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

    private Properties createValidDatabaseProperties() {
        Properties props = new Properties();
        props.setProperty("jettra.cluster.node.id", "node-01");
        props.setProperty("jettra.storage.path", "~/jettra/data");
        props.setProperty("jettra.network.grpc.port", "9091");
        props.setProperty("jettra.network.rest.port", "8080");
        props.setProperty("jettra.index.storage.path", "~/jettra/data/indexes");
        return props;
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
    @DisplayName("Debe fallar si jettra.storage.path no coincide con ningún cluster.node.X.storage.path")
    public void testStoragePathMismatchFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        dbProps.setProperty("jettra.storage.path", "/var/other/jettra/data");
        dbProps.setProperty("jettra.index.storage.path", "/var/other/jettra/data/indexes");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasStorageError = result.getErrors().stream()
            .anyMatch(err -> err.contains("jettra.storage.path") && err.contains("no coincide"));
        assertTrue(hasStorageError);
    }

    @Test
    @DisplayName("Debe fallar si jettra.storage.path no cumple la sintaxis recomendada <path>/jettra/data")
    public void testStoragePathInvalidSyntaxFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        // Ruta que no cumple la sintaxis recomendada
        dbProps.setProperty("jettra.storage.path", "~/misdatos/almacen");
        clusterProps.setProperty("cluster.node.1.storage.path", "~/misdatos/almacen");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasSyntaxError = result.getErrors().stream()
            .anyMatch(err -> err.contains("sintaxis recomendada"));
        assertTrue(hasSyntaxError);
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
    @DisplayName("Debe fallar si jettra.index.storage.path no implementa la sintaxis <path>/jettra/data/indexes")
    public void testIndexStoragePathInvalidSyntaxFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        // Ruta que no cumple la sintaxis requerida
        dbProps.setProperty("jettra.index.storage.path", "~/jettra/indices");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasIndexError = result.getErrors().stream()
            .anyMatch(err -> err.contains("jettra.index.storage.path") && err.contains("sintaxis"));
        assertTrue(hasIndexError);
    }

    @Test
    @DisplayName("Debe generar automáticamente database.properties y jettra.config si no existen")
    public void testAutoGenerationOfMissingConfigFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_auto_gen_test");
        Path targetDb = tempDir.resolve("database.properties");
        Path targetCluster = tempDir.resolve("jettra.config");

        assertFalse(Files.exists(targetDb));
        assertFalse(Files.exists(targetCluster));

        JettraConfigValidator.generateDefaultDatabaseProperties(targetDb);
        JettraConfigValidator.generateDefaultJettraConfig(targetCluster);

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
    @DisplayName("Debe cargar IP, puertos y peers desde jettra.config y database.properties en JettraStoreConfig")
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
    @DisplayName("Debe ubicar database.properties en la misma carpeta que jettra.config mediante sibling resolution")
    public void testLocateDatabasePropertiesSibling() throws IOException {
        Path tempDir = Files.createTempDirectory("jettra_sibling_test");
        Path cfgFile = tempDir.resolve("jettra.config");
        Path dbFile = tempDir.resolve("database.properties");

        Files.writeString(cfgFile, "cluster.node.1.id=node-01\n");
        Files.writeString(dbFile, "jettra.cluster.node.id=node-01\n");

        String prev = System.getProperty("jettra.config.path");
        try {
            System.setProperty("jettra.config.path", cfgFile.toString());
            Path locatedDb = JettraConfigValidator.locateDatabasePropertiesFile();
            assertNotNull(locatedDb);
            assertEquals(dbFile.toAbsolutePath().normalize(), locatedDb.toAbsolutePath().normalize());
        } finally {
            if (prev != null) {
                System.setProperty("jettra.config.path", prev);
            } else {
                System.clearProperty("jettra.config.path");
            }
            Files.deleteIfExists(cfgFile);
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(tempDir);
        }
    }
}

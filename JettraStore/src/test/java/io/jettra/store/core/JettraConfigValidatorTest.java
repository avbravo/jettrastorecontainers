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
        props.setProperty("cluster.node.1.storage.path", "~/jettra/node-01/data");

        props.setProperty("cluster.node.2.id", "node-02");
        props.setProperty("cluster.node.2.role", "SECONDARY");
        props.setProperty("cluster.node.2.ip", "127.0.0.1");
        props.setProperty("cluster.node.2.grpc.port", "9092");
        props.setProperty("cluster.node.2.rest.port", "8082");
        props.setProperty("cluster.node.2.storage.path", "~/jettra/node-02/data");

        props.setProperty("cluster.node.3.id", "node-03");
        props.setProperty("cluster.node.3.role", "SECONDARY");
        props.setProperty("cluster.node.3.ip", "127.0.0.1");
        props.setProperty("cluster.node.3.grpc.port", "9093");
        props.setProperty("cluster.node.3.rest.port", "8083");
        props.setProperty("cluster.node.3.storage.path", "~/jettra/node-03/data");

        return props;
    }

    private Properties createValidDatabaseProperties() {
        Properties props = new Properties();
        props.setProperty("jettra.cluster.node.id", "node-01");
        props.setProperty("jettra.storage.path", "~/jettra/node-01/data");
        props.setProperty("jettra.network.grpc.port", "9091");
        props.setProperty("jettra.network.rest.port", "8080");
        props.setProperty("jettra.index.storage.path", "~/jettra/node-01/data/indexes");
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
        dbProps.setProperty("jettra.storage.path", "~/jettra/node-99/data");
        dbProps.setProperty("jettra.index.storage.path", "~/jettra/node-99/data/indexes");

        JettraConfigValidator.ValidationResult result = JettraConfigValidator.validate(dbProps, clusterProps);
        assertFalse(result.isValid());
        boolean hasStorageError = result.getErrors().stream()
            .anyMatch(err -> err.contains("jettra.storage.path") && err.contains("no coincide"));
        assertTrue(hasStorageError);
    }

    @Test
    @DisplayName("Debe fallar si jettra.storage.path no cumple la sintaxis recomendada <path>/jettra/<id-node>/data")
    public void testStoragePathInvalidSyntaxFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        // Ruta sin el id de nodo
        dbProps.setProperty("jettra.storage.path", "~/jettra/data");
        clusterProps.setProperty("cluster.node.1.storage.path", "~/jettra/data");

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
    @DisplayName("Debe fallar si jettra.index.storage.path no implementa la sintaxis <path>/jettra/<id-node>/data/indexes")
    public void testIndexStoragePathInvalidSyntaxFails() {
        Properties clusterProps = createValidClusterProperties();
        Properties dbProps = createValidDatabaseProperties();
        // Le falta el <id-node>
        dbProps.setProperty("jettra.index.storage.path", "~/jettra/data/indexes");

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
}

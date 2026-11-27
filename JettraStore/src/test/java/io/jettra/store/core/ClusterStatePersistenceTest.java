package io.jettra.store.core;

import io.jettra.store.JettraTestCleanup;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Pruebas unitarias con JettraTest para RF-32 y RF-34:
 * - Creación automática de jettra.cluster_state_config_dir (expandiendo ~) y validación de permisos
 * - Separación estricta entre cluster.config estático opcional y cluster-state.config persistente
 * - Modo STANDBY cuando no existe cluster-state.config ni cluster.config
 * - Preservación de cluster-state.config existente frente a cambios en cluster.config
 * - Respaldo y restauración íntegra desde cluster-state.config-backup durante pruebas
 */
public class ClusterStatePersistenceTest {

    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void backupConfiguredStateBeforeSuite() {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
    }

    @AfterAll
    public static void restoreConfiguredStateAfterSuite() {
        if (suiteStateBackup != null) {
            suiteStateBackup.close();
            suiteStateBackup = null;
        }
    }

    @Test
    @DisplayName("RF-32: Crea automáticamente jettra.cluster_state_config_dir (resolviendo ~ y variables) y valida permisos")
    public void testAutoCreatesClusterStateConfigDirWithHomeExpansion() throws Exception {
        Path fakeHome = Files.createTempDirectory("jettra_rf32_home_");
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
        try {
            Properties mpProps = new Properties();
            mpProps.setProperty("jettra.cluster_state_config_dir", "~/jettra-custom-state/statedir");

            String prevSys = System.getProperty("jettra.cluster_state_config_dir");
            System.clearProperty("jettra.cluster_state_config_dir");
            try {
                Path resolvedDir = JettraConfigValidator.ensureClusterStateConfigDirExists(mpProps);
                Path expectedDir = fakeHome.resolve("jettra-custom-state/statedir").toAbsolutePath().normalize();
                assertEquals(expectedDir, resolvedDir);
                assertTrue(Files.isDirectory(resolvedDir), "Debe crear automáticamente jettra.cluster_state_config_dir");
                assertTrue(Files.isReadable(resolvedDir) && Files.isWritable(resolvedDir),
                        "Debe tener permisos de lectura y escritura");
            } finally {
                if (prevSys != null) {
                    System.setProperty("jettra.cluster_state_config_dir", prevSys);
                }
            }
        } finally {
            System.setProperty("user.home", prevHome);
            JettraTestCleanup.deleteRecursively(fakeHome);
        }
    }

    @Test
    @DisplayName("RF-32: Sin cluster-state.config ni cluster.config arranca en modo STANDBY sin fabricar nodos")
    public void testStartsInStandbyWhenNoStateAndNoSeedConfigExist() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf32_standby_");
        Path stateDir = tempRoot.resolve("state");
        Path dataDir = tempRoot.resolve("data");
        try {
            Properties mpProps = new Properties();
            mpProps.setProperty("cluster.node.id", "node-standby");
            mpProps.setProperty("cluster.node.role", "SECONDARY");
            mpProps.setProperty("cluster.multinode.active", "on");
            mpProps.setProperty("jettra.storage.path", dataDir.toAbsolutePath().toString());
            mpProps.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());

            Path stateFile = JettraConfigValidator.resolveClusterStateFile(mpProps);
            assertTrue(Files.exists(stateDir), "El directorio de estado debe haberse creado automáticamente");
            assertFalse(Files.exists(stateFile), "No debe existir cluster-state.config sin semilla");

            Properties loadedState = JettraConfigValidator.seedOrLoadClusterState(stateFile, null);
            assertNull(loadedState, "seedOrLoadClusterState debe retornar null sin semilla ni estado previo");

            JettraStoreConfig cfg = new JettraStoreConfig(mpProps, new Properties(), mpProps);
            assertTrue(cfg.isStandbyMode(), "Debe entrar en modo STANDBY cuando no hay cluster.config ni cluster-state.config");
            assertEquals(0, cfg.getAllClusterNodes().size(), "No debe inventar nodos cuando está en STANDBY");
            assertEquals(0, cfg.getParsedPeers().size(), "No debe registrar peers falsos en STANDBY");
        } finally {
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-32: cluster.config siembra cluster-state.config una vez, permanece inmutable y nunca sobrescribe estado existente")
    public void testSeedOnceAndPreserveExistingClusterStateOverStaticSeed() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf32_seed_");
        Path stateDir = tempRoot.resolve("statedir");
        Path staticSeed = tempRoot.resolve("cluster.config");
        try {
            String seedContent = """
                    cluster.name = rf32-seed-cluster
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 55001
                    cluster.node.1.rest.port = 65001
                    cluster.node.2.id = node-02
                    cluster.node.2.role = SECONDARY
                    cluster.node.2.ip = 127.0.0.1
                    cluster.node.2.grpc.port = 55002
                    cluster.node.2.rest.port = 65002
                    """;
            Files.writeString(staticSeed, seedContent, StandardCharsets.UTF_8);
            byte[] originalSeedBytes = Files.readAllBytes(staticSeed);

            Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Properties seeded = JettraConfigValidator.seedOrLoadClusterState(stateFile, staticSeed);
            assertNotNull(seeded, "Debe sembrar cluster-state.config a partir de cluster.config opcional");
            assertTrue(Files.exists(stateFile), "cluster-state.config debe haberse creado en statedir");

            // Mutar estado persistente (añadir node-04 y detener node-02)
            JettraConfigValidator.upsertNodeInStateFile(
                    stateFile, "node-04", "SECONDARY", "127.0.0.1", 55004, 65004, "RUNNING", "admin");
            JettraConfigValidator.updateNodeStatusInStateFile(stateFile, "node-02", "STOPPED", "admin");

            // Verificar que el archivo estático cluster.config no fue tocado
            assertEquals(new String(originalSeedBytes, StandardCharsets.UTF_8),
                    Files.readString(staticSeed, StandardCharsets.UTF_8),
                    "cluster.config estático nunca debe modificarse en tiempo de ejecución");

            // Al reiniciar con cluster.config distinto/original, debe prevalecer cluster-state.config intacto
            Properties reloaded = JettraConfigValidator.seedOrLoadClusterState(stateFile, staticSeed);
            assertNotNull(reloaded);
            var nodes = JettraConfigValidator.parseExplicitClusterNodes(reloaded);
            assertEquals(3, nodes.size(), "Debe conservar node-01, node-02 y node-04 en cluster-state.config");
            assertNotNull(JettraConfigValidator.findNodeInCluster(nodes, "node-04"));

            var statuses = JettraConfigValidator.parseClusterNodeStatuses(reloaded);
            assertEquals("STOPPED", statuses.get("node-02"), "El estado STOPPED de node-02 debe persistir");
            assertEquals("RUNNING", statuses.get("node-04"), "El estado RUNNING de node-04 debe persistir");
            assertTrue(JettraConfigValidator.parseClusterStateVersion(reloaded) >= 3L,
                    "La versión del estado debe haberse incrementado en cada mutación");
        } finally {
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-32: Dockerfile y Docker Compose montan jettra.cluster_state_config_dir en volúmenes persistentes")
    public void testDockerFilesPersistClusterStateDir() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        assertTrue(dockerfile.contains("JETTRA_CLUSTER_STATE_CONFIG_DIR=/jettra/cluster-state"));
        assertTrue(dockerfile.contains("/jettra/cluster-state"));

        Path composeMultiPath = Files.exists(Path.of("docker/docker-compose.yml"))
                ? Path.of("docker/docker-compose.yml") : Path.of("docker-compose.yml");
        String composeMulti = Files.readString(composeMultiPath, StandardCharsets.UTF_8);
        assertTrue(composeMulti.contains("jettra_state_node01:/jettra/cluster-state"));
        assertTrue(composeMulti.contains("jettra_state_node02:/jettra/cluster-state"));
        assertTrue(composeMulti.contains("jettra_state_node03:/jettra/cluster-state"));

        Path composeHubPath = java.util.List.of(
                        Path.of("docker/hub/docker-compose-hub.yml"),
                        Path.of("docker/docker-compose-hub.yml"),
                        Path.of("docker-compose-hub.yml"))
                .stream().filter(Files::exists).findFirst()
                .orElse(Path.of("docker/hub/docker-compose-hub.yml"));
        String composeHub = Files.readString(composeHubPath, StandardCharsets.UTF_8);
        assertTrue(composeHub.contains("JETTRA_CLUSTER_STATE_CONFIG_DIR=/jettra/cluster-state"));
        assertTrue(composeHub.contains(":/jettra/cluster-state"));
    }

    @Test
    @DisplayName("RF-34: Respalda cluster-state.config como cluster-state.config-backup, elimina archivo de prueba y restaura original")
    public void testClusterStateBackupAndRestorePreservesOriginal() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf34_backup_");
        try {
            Path stateDir = tempRoot.resolve("state-existing");
            Files.createDirectories(stateDir);
            Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Path backupFile = stateDir.resolve(JettraClusterStateBackupManager.CLUSTER_STATE_BACKUP_FILENAME);

            String originalContent = """
                    cluster.name = prod-original
                    cluster.state.version = 10
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 10.10.0.10
                    cluster.node.1.grpc.port = 50001
                    cluster.node.1.rest.port = 60001
                    cluster.node.1.status = RUNNING
                    """;
            Files.writeString(stateFile, originalContent, StandardCharsets.UTF_8);

            // 1. Con archivo existente: crea cluster-state.config-backup, permite editar y al cerrar restaura original
            try (var guard = JettraClusterStateBackupManager.protect(stateDir)) {
                assertTrue(Files.exists(backupFile), "Debe crear cluster-state.config-backup antes de modificar");
                assertEquals(originalContent, Files.readString(backupFile, StandardCharsets.UTF_8));

                JettraConfigValidator.upsertNodeInStateFile(
                        stateFile, "node-test-99", "SECONDARY", "127.0.0.1", 59999, 64999, "RUNNING", "rf34-test");
                String modified = Files.readString(stateFile, StandardCharsets.UTF_8);
                assertTrue(modified.contains("node-test-99"), "Durante el test debe reflejar cambios temporales");
                assertEquals(originalContent, Files.readString(backupFile, StandardCharsets.UTF_8),
                        "cluster-state.config-backup debe permanecer intacto");
            }

            assertTrue(Files.exists(stateFile), "cluster-state.config debe restaurarse al finalizar el test");
            assertFalse(Files.exists(backupFile), "cluster-state.config-backup debe haberse renombrado a cluster-state.config");
            assertEquals(originalContent, Files.readString(stateFile, StandardCharsets.UTF_8),
                    "El contenido restaurado debe ser idéntico al original");

            // 2. Sin archivo previo: elimina el cluster-state.config temporal creado por el test
            Path emptyDir = tempRoot.resolve("state-empty");
            Files.createDirectories(emptyDir);
            Path emptyStateFile = emptyDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            try (var guard = JettraClusterStateBackupManager.protect(emptyDir)) {
                JettraConfigValidator.upsertNodeInStateFile(
                        emptyStateFile, "node-temp", "PRIMARY", "127.0.0.1", 59001, 64001, "RUNNING", "rf34-test");
                assertTrue(Files.exists(emptyStateFile));
                assertFalse(Files.exists(emptyDir.resolve(JettraClusterStateBackupManager.CLUSTER_STATE_BACKUP_FILENAME)));
            }
            assertFalse(Files.exists(emptyStateFile),
                    "Si no existía cluster-state.config original, debe eliminarse el archivo generado por el test");

            // 3. Concurrencia con Virtual Threads bajo protección de backup
            Path concDir = tempRoot.resolve("state-concurrent");
            Files.createDirectories(concDir);
            Path concState = concDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Files.writeString(concState, originalContent, StandardCharsets.UTF_8);

            try (var outer = JettraClusterStateBackupManager.protect(concDir)) {
                int workers = 6;
                CountDownLatch latch = new CountDownLatch(workers);
                AtomicReference<Throwable> err = new AtomicReference<>();
                for (int i = 2; i <= workers + 1; i++) {
                    final int idx = i;
                    Thread.ofVirtual().name("vt-rf34-" + idx).start(() -> {
                        try (var nested = JettraClusterStateBackupManager.protect(concDir)) {
                            JettraConfigValidator.upsertNodeInStateFile(
                                    concState, "node-vt-" + idx, "SECONDARY", "127.0.0.1",
                                    58100 + idx, 63100 + idx, "RUNNING", "vt");
                        } catch (Throwable t) {
                            err.compareAndSet(null, t);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                assertTrue(latch.await(15, TimeUnit.SECONDS));
                assertNull(err.get(), "No deben ocurrir errores de concurrencia en backup/restore");
            }
            assertTrue(Files.exists(concState));
            assertFalse(Files.exists(concDir.resolve(JettraClusterStateBackupManager.CLUSTER_STATE_BACKUP_FILENAME)));
            assertEquals(originalContent, Files.readString(concState, StandardCharsets.UTF_8));
        } finally {
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }

    @Test
    @DisplayName("PRIMARY requiere cluster.config si no hay estado previo; SECONDARY arranca en STANDBY sin cluster.config y recibe cluster-state.config al ser detectado")
    public void testPrimaryRequiresClusterConfigAndSecondaryReceivesClusterStateOnDetection() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_primary_secondary_sync_");
        int g1, r1, g2, r2;
        try (var s1 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var s2 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var s3 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var s4 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            g1 = s1.getLocalPort(); r1 = s2.getLocalPort();
            g2 = s3.getLocalPort(); r2 = s4.getLocalPort();
        }

        io.jettra.store.JettraStoreServer primary = null;
        io.jettra.store.JettraStoreServer secondary = null;
        try {
            // 1. SECONDARY arranca PRIMERO en STANDBY SIN cluster.config ni cluster-state.config
            Path secStateDir = tempRoot.resolve("sec-state");
            Properties pSec = new Properties();
            pSec.setProperty("cluster.node.id", "node-02");
            pSec.setProperty("cluster.node.role", "SECONDARY");
            pSec.setProperty("jettra.node.ip", "127.0.0.1");
            pSec.setProperty("jettra.network.grpc.port", String.valueOf(g2));
            pSec.setProperty("jettra.network.rest.port", String.valueOf(r2));
            pSec.setProperty("jettra.storage.path", tempRoot.resolve("sec-data").toAbsolutePath().toString());
            pSec.setProperty("jettra.cluster_state_config_dir", secStateDir.toAbsolutePath().toString());
            pSec.setProperty("jettra.storage.mode", "JVM_RAM");
            pSec.setProperty("cluster.multinode.active", "on");
            pSec.setProperty("jettrapolice.active", "false");

            JettraStoreConfig secCfg = new JettraStoreConfig(pSec, new Properties(), pSec);
            assertTrue(secCfg.isStandbyMode(), "SECONDARY sin cluster.config debe iniciar en STANDBY");
            Path secStateFile = secStateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertFalse(Files.exists(secStateFile), "SECONDARY no debe tener cluster-state.config antes de ser detectado");

            secondary = new io.jettra.store.JettraStoreServer(secCfg);
            secondary.start();

            // 2. PRIMARY arranca con su cluster.config listando node-01 y node-02
            Path primStateDir = tempRoot.resolve("prim-state");
            Path primClusterCfg = tempRoot.resolve("cluster.config");
            Files.writeString(primClusterCfg,
                    "cluster.name = dynamic-test-cluster\n"
                    + "cluster.node.1.id = node-01\ncluster.node.1.role = PRIMARY\ncluster.node.1.ip = 127.0.0.1\ncluster.node.1.grpc.port = " + g1 + "\ncluster.node.1.rest.port = " + r1 + "\n"
                    + "cluster.node.2.id = node-02\ncluster.node.2.role = SECONDARY\ncluster.node.2.ip = 127.0.0.1\ncluster.node.2.grpc.port = " + g2 + "\ncluster.node.2.rest.port = " + r2 + "\n",
                    StandardCharsets.UTF_8);

            Properties clusterProps = new Properties();
            try (var in = Files.newInputStream(primClusterCfg)) { clusterProps.load(in); }

            Properties pPrim = new Properties();
            pPrim.setProperty("cluster.node.id", "node-01");
            pPrim.setProperty("cluster.node.role", "PRIMARY");
            pPrim.setProperty("jettra.node.ip", "127.0.0.1");
            pPrim.setProperty("jettra.network.grpc.port", String.valueOf(g1));
            pPrim.setProperty("jettra.network.rest.port", String.valueOf(r1));
            pPrim.setProperty("jettra.storage.path", tempRoot.resolve("prim-data").toAbsolutePath().toString());
            pPrim.setProperty("jettra.cluster_state_config_dir", primStateDir.toAbsolutePath().toString());
            pPrim.setProperty("jettra.storage.mode", "JVM_RAM");
            pPrim.setProperty("cluster.multinode.active", "on");
            pPrim.setProperty("jettrapolice.active", "false");

            primary = new io.jettra.store.JettraStoreServer(new JettraStoreConfig(pPrim, clusterProps, pPrim));
            primary.setClusterConfigFilePath(primClusterCfg);
            primary.start();

            // 3. Esperar a que PRIMARY detecte al SECONDARY en STANDBY y le replique cluster-state.config
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && (!Files.exists(secStateFile) || secCfg.isStandbyMode())) {
                Thread.sleep(100);
            }
            assertTrue(Files.exists(secStateFile),
                    "SECONDARY debe recibir y almacenar cluster-state.config en jettra.cluster_state_config_dir al ser detectado por PRIMARY");
            assertFalse(secCfg.isStandbyMode(),
                    "SECONDARY debe salir de modo STANDBY al recibir cluster-state.config del PRIMARY");
            String syncedContent = Files.readString(secStateFile, StandardCharsets.UTF_8);
            assertTrue(syncedContent.contains("node-01") && syncedContent.contains("node-02"),
                    "El cluster-state.config recibido por SECONDARY debe contener la topología del clúster");
        } finally {
            if (secondary != null) try { secondary.stop(); } catch (Exception ignored) {}
            if (primary != null) try { primary.stop(); } catch (Exception ignored) {}
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }
}

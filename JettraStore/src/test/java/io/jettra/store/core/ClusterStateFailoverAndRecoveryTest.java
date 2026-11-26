package io.jettra.store.core;

import io.jettra.store.JettraStoreServer;
import io.jettra.store.JettraTestCleanup;
import io.jettra.store.cluster.ClusterNode;
import io.jettra.store.cluster.JettraClusterTransportServer;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Pruebas unitarias y de integración con JettraTest para RF-33:
 * 1. Resolución de ~ en jettra.cluster_state_config_dir y creación automática.
 * 2. Semilla desde cluster.config dentro de jettra.cluster_state_config_dir sin sobrescritura posterior.
 * 3. Inicio de nodo secundario sin cluster.config y descarga de cluster-state.config al unirse al PRIMARY.
 * 4. Failover automático leyendo cluster-state.config del nuevo PRIMARY y replicando cambios subsiguientes.
 * 5. Concurrencia segura con Virtual Threads sobre cluster-state.config.
 */
public class ClusterStateFailoverAndRecoveryTest {

    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    @BeforeAll
    public static void protectConfiguredClusterState() {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
    }

    @AfterAll
    public static void restoreConfiguredClusterState() {
        try {
            JettraStoreServer.stopAllActiveServers();
            assertEquals(0, JettraStoreServer.getActiveServerCount(),
                    "Todos los servidores JettraStore deben quedar cerrados");
            assertEquals(0, JettraClusterTransportServer.getActiveTransportCount(),
                    "Todos los transportes TCP de clúster deben quedar cerrados");
        } finally {
            if (suiteStateBackup != null) {
                suiteStateBackup.close();
                suiteStateBackup = null;
            }
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Properties baseNodeProps(String nodeId, String role, int grpcPort, int restPort, Path storageDir, Path stateDir) {
        Properties props = new Properties();
        props.setProperty("cluster.node.id", nodeId);
        props.setProperty("cluster.node.role", role);
        props.setProperty("cluster.multinode.active", "on");
        props.setProperty("jettra.node.ip", "127.0.0.1");
        props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
        props.setProperty("jettra.network.rest.port", String.valueOf(restPort));
        props.setProperty("jettra.storage.path", storageDir.toAbsolutePath().toString());
        props.setProperty("jettra.cluster_state_config_dir", stateDir.toAbsolutePath().toString());
        return props;
    }

    @Test
    @DisplayName("RF-33: Crea jettra.cluster_state_config_dir (~), siembra desde <stateDir>/cluster.config y nunca sobrescribe estado existente")
    public void testBootstrapFromClusterConfigInsideStateDirAndNoOverwrite() throws Exception {
        Path fakeHome = Files.createTempDirectory("jettra_rf33_home_");
        String prevHome = System.getProperty("user.home");
        String prevClusterPath = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");

        System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
        System.clearProperty("cluster.config.path");
        System.clearProperty("jettra.cluster_state_config_dir");

        try {
            Properties mpProps = new Properties();
            mpProps.setProperty("jettra.cluster_state_config_dir", "~/jettra-cluster-state/");

            Path resolvedDir = JettraConfigValidator.ensureClusterStateConfigDirExists(mpProps);
            Path expectedDir = fakeHome.resolve("jettra-cluster-state").toAbsolutePath().normalize();
            assertEquals(expectedDir, resolvedDir, "Debe resolver ~ hacia user.home y crear el directorio");
            assertTrue(Files.isDirectory(resolvedDir));

            // Colocar cluster.config dentro del directorio configurado por jettra.cluster_state_config_dir
            Path seedInStateDir = resolvedDir.resolve("cluster.config");
            Files.writeString(seedInStateDir, """
                    cluster.name = rf33-local-seed
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 54101
                    cluster.node.1.rest.port = 64101
                    cluster.node.2.id = node-02
                    cluster.node.2.role = SECONDARY
                    cluster.node.2.ip = 127.0.0.1
                    cluster.node.2.grpc.port = 54102
                    cluster.node.2.rest.port = 64102
                    """, StandardCharsets.UTF_8);

            Path stateFile = JettraConfigValidator.resolveClusterStateFile(mpProps);
            Path locatedSeed = JettraConfigValidator.locateOptionalClusterSeedFile(stateFile.getParent());
            assertEquals(seedInStateDir.toAbsolutePath().normalize(), locatedSeed.toAbsolutePath().normalize(),
                    "Debe localizar cluster.config dentro de jettra.cluster_state_config_dir");

            Properties seeded = JettraConfigValidator.seedOrLoadClusterState(stateFile, locatedSeed);
            assertNotNull(seeded);
            assertTrue(Files.exists(stateFile), "Debe generar cluster-state.config desde <stateDir>/cluster.config");
            assertEquals(2, JettraConfigValidator.parseExplicitClusterNodes(seeded).size());

            // Modificar cluster-state.config en ejecución (agregar node-03 y marcar node-02 como STOPPED)
            JettraConfigValidator.upsertNodeInStateFile(
                    stateFile, "node-03", "SECONDARY", "127.0.0.1", 54103, 64103, "RUNNING", "node-01");
            JettraConfigValidator.updateNodeStatusInStateFile(stateFile, "node-02", "STOPPED", "node-01");

            // Cambiar cluster.config original con un nodo obsoleto para comprobar regla de no sobrescritura
            Files.writeString(seedInStateDir, """
                    cluster.name = rf33-obsolete-seed
                    cluster.node.1.id = node-99
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 54999
                    cluster.node.1.rest.port = 64999
                    """, StandardCharsets.UTF_8);

            Properties reloaded = JettraConfigValidator.seedOrLoadClusterState(stateFile, seedInStateDir);
            assertNotNull(reloaded);
            List<JettraConfigValidator.ClusterNodeInfo> nodes = JettraConfigValidator.parseExplicitClusterNodes(reloaded);
            assertEquals(3, nodes.size(), "cluster-state.config existente no debe sobrescribirse con cluster.config");
            assertNotNull(JettraConfigValidator.findNodeInCluster(nodes, "node-03"));
            assertNull(JettraConfigValidator.findNodeInCluster(nodes, "node-99"));
            assertEquals("STOPPED", JettraConfigValidator.parseClusterNodeStatuses(reloaded).get("node-02"));
        } finally {
            System.setProperty("user.home", prevHome);
            if (prevClusterPath != null) System.setProperty("cluster.config.path", prevClusterPath);
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys);
            JettraTestCleanup.deleteRecursively(fakeHome);
        }
    }

    @Test
    @DisplayName("RF-33: Secundario sin cluster.config mantiene su directorio creado, arranca sin fallos y descarga cluster-state.config al unirse al PRIMARY")
    public void testSecondaryStartupWithoutSeedThenJoinDownloadsStateFromPrimary() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf33_sec_join_");
        JettraClusterTransportServer t1 = null;
        JettraStoreServer n1Server = null;
        JettraStoreServer n2Server = null;
        int p1Grpc = findFreePort();
        int p1Rest = findFreePort();
        int p2Grpc = findFreePort();
        int p2Rest = findFreePort();
        try {

            Path n1StateDir = tempRoot.resolve("node01-state");
            Path n1Storage = tempRoot.resolve("node01-data");
            Path n1StateFile = n1StateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Files.createDirectories(n1StateDir);

            // PRIMARY arranca sembrado con su cluster.config dentro de n1StateDir
            Path n1Seed = n1StateDir.resolve("cluster.config");
            Files.writeString(n1Seed, String.format("""
                    cluster.name = rf33-live
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = %d
                    cluster.node.1.rest.port = %d
                    """, p1Grpc, p1Rest), StandardCharsets.UTF_8);

            Properties n1Props = baseNodeProps("node-01", "PRIMARY", p1Grpc, p1Rest, n1Storage, n1StateDir);
            Properties n1State = JettraConfigValidator.seedOrLoadClusterState(n1StateFile, n1Seed);
            JettraStoreConfig n1Config = new JettraStoreConfig(n1Props, n1State, n1Props);
            n1Server = new JettraStoreServer(n1Config);
            t1 = new JettraClusterTransportServer(p1Grpc, n1Server);
            t1.start();

            // Secundario node-02 inicia SIN cluster.config ni cluster-state.config previos
            Path n2StateDir = tempRoot.resolve("node02-state");
            Path n2Storage = tempRoot.resolve("node02-data");
            Properties n2Props = baseNodeProps("node-02", "SECONDARY", p2Grpc, p2Rest, n2Storage, n2StateDir);
            Path n2StateFile = JettraConfigValidator.resolveClusterStateFile(n2Props);

            assertTrue(Files.isDirectory(n2StateDir), "Debe crear jettra.cluster_state_config_dir del secundario si no existe");
            assertNull(JettraConfigValidator.seedOrLoadClusterState(n2StateFile, JettraConfigValidator.locateOptionalClusterSeedFile(n2StateDir)),
                    "Sin cluster.config en statedir debe retornar null sin fallar");
            assertFalse(Files.exists(n2StateFile), "No debe existir cluster-state.config aún antes de integrarse");

            JettraStoreConfig n2Config = new JettraStoreConfig(n2Props, new Properties(), n2Props);
            assertTrue(n2Config.isStandbyMode(), "El secundario sin semilla debe permanecer en STANDBY manteniendo el directorio creado");
            n2Server = new JettraStoreServer(n2Config);

            // Al integrarse/unirse al clúster, node-02 solicita join a node-01 y descarga cluster-state.config
            n2Server.applySyncedPeersToRuntime(List.of(
                    new JettraConfigValidator.ClusterNodeInfo("node-01", "PRIMARY", "127.0.0.1", p1Grpc, p1Rest, n1Storage.toString())));
            boolean synced = n2Server.syncClusterConfigWithActivePrimary();

            assertTrue(synced, "Debe descargar y aplicar cluster-state.config desde PRIMARY");
            assertTrue(Files.exists(n2StateFile), "Debe guardar cluster-state.config descargado dentro de jettra.cluster_state_config_dir");
            assertFalse(n2Server.getConfig().isStandbyMode(), "Debe salir de STANDBY tras descargar cluster-state.config");

            Properties downloadedProps = new Properties();
            try (var is = Files.newInputStream(n2StateFile)) {
                downloadedProps.load(is);
            }
            var n2Nodes = JettraConfigValidator.parseExplicitClusterNodes(downloadedProps);
            assertEquals(2, n2Nodes.size());
            assertNotNull(JettraConfigValidator.findNodeInCluster(n2Nodes, "node-01"));
            assertNotNull(JettraConfigValidator.findNodeInCluster(n2Nodes, "node-02"));
            assertEquals("PRIMARY", JettraConfigValidator.findNodeInCluster(n2Nodes, "node-01").role());
            assertEquals("SECONDARY", JettraConfigValidator.findNodeInCluster(n2Nodes, "node-02").role());
        } finally {
            if (t1 != null) {
                try { t1.close(); } catch (Exception ignored) {}
            }
            if (n2Server != null) {
                try { n2Server.stop(); } catch (Exception ignored) {}
            }
            if (n1Server != null) {
                try { n1Server.stop(); } catch (Exception ignored) {}
            }
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", p1Grpc, 2000),
                "El puerto gRPC del PRIMARY debe liberarse al terminar el test");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", p2Grpc, 2000),
                "El puerto gRPC de node-02 debe liberarse al terminar el test");
    }

    @Test
    @DisplayName("RF-33: Tras caída del PRIMARY, el nuevo PRIMARY lee cluster-state.config desde jettra.cluster_state_config_dir y replica nuevos cambios")
    public void testFailoverReadsClusterStateAndReplicatesSubsequentChanges() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf33_failover_");
        JettraClusterTransportServer t1 = null;
        JettraClusterTransportServer t2 = null;
        JettraClusterTransportServer t3 = null;
        JettraStoreServer n1Server = null;
        JettraStoreServer n2Server = null;
        JettraStoreServer n3Server = null;
        int p1Grpc = findFreePort();
        int p1Rest = findFreePort();
        int p2Grpc = findFreePort();
        int p2Rest = findFreePort();
        int p3Grpc = findFreePort();
        int p3Rest = findFreePort();
        try {
            int p4Grpc = findFreePort();
            int p4Rest = findFreePort();
            int p5Grpc = findFreePort();
            int p5Rest = findFreePort();

            String initialState = String.format("""
                    cluster.name = rf33-failover-cluster
                    cluster.state.version = 3
                    cluster.state.updated.by = node-01
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = %d
                    cluster.node.1.rest.port = %d
                    cluster.node.1.status = RUNNING
                    cluster.node.2.id = node-02
                    cluster.node.2.role = SECONDARY
                    cluster.node.2.ip = 127.0.0.1
                    cluster.node.2.grpc.port = %d
                    cluster.node.2.rest.port = %d
                    cluster.node.2.status = RUNNING
                    cluster.node.3.id = node-03
                    cluster.node.3.role = SECONDARY
                    cluster.node.3.ip = 127.0.0.1
                    cluster.node.3.grpc.port = %d
                    cluster.node.3.rest.port = %d
                    cluster.node.3.status = RUNNING
                    cluster.node.4.id = node-04
                    cluster.node.4.role = SECONDARY
                    cluster.node.4.ip = 127.0.0.1
                    cluster.node.4.grpc.port = %d
                    cluster.node.4.rest.port = %d
                    cluster.node.4.status = STOPPED
                    """, p1Grpc, p1Rest, p2Grpc, p2Rest, p3Grpc, p3Rest, p4Grpc, p4Rest);

            Path n1StateDir = tempRoot.resolve("n1-state");
            Path n2StateDir = tempRoot.resolve("n2-state");
            Path n3StateDir = tempRoot.resolve("n3-state");
            Files.createDirectories(n1StateDir);
            Files.createDirectories(n2StateDir);
            Files.createDirectories(n3StateDir);

            Path n1StateFile = n1StateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Path n2StateFile = n2StateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Path n3StateFile = n3StateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Files.writeString(n1StateFile, initialState, StandardCharsets.UTF_8);
            Files.writeString(n2StateFile, initialState, StandardCharsets.UTF_8);
            Files.writeString(n3StateFile, initialState, StandardCharsets.UTF_8);

            // Simular que la memoria del secundario node-02 tenía solo [node-01, node-02, node-03],
            // mientras su cluster-state.config en disco incluye además a node-04.
            Properties partialClusterProps = new Properties();
            partialClusterProps.setProperty("cluster.node.1.id", "node-01");
            partialClusterProps.setProperty("cluster.node.1.role", "PRIMARY");
            partialClusterProps.setProperty("cluster.node.1.ip", "127.0.0.1");
            partialClusterProps.setProperty("cluster.node.1.grpc.port", String.valueOf(p1Grpc));
            partialClusterProps.setProperty("cluster.node.1.rest.port", String.valueOf(p1Rest));
            partialClusterProps.setProperty("cluster.node.2.id", "node-02");
            partialClusterProps.setProperty("cluster.node.2.role", "SECONDARY");
            partialClusterProps.setProperty("cluster.node.2.ip", "127.0.0.1");
            partialClusterProps.setProperty("cluster.node.2.grpc.port", String.valueOf(p2Grpc));
            partialClusterProps.setProperty("cluster.node.2.rest.port", String.valueOf(p2Rest));
            partialClusterProps.setProperty("cluster.node.3.id", "node-03");
            partialClusterProps.setProperty("cluster.node.3.role", "SECONDARY");
            partialClusterProps.setProperty("cluster.node.3.ip", "127.0.0.1");
            partialClusterProps.setProperty("cluster.node.3.grpc.port", String.valueOf(p3Grpc));
            partialClusterProps.setProperty("cluster.node.3.rest.port", String.valueOf(p3Rest));

            Properties n1State = JettraConfigValidator.seedOrLoadClusterState(n1StateFile, null);
            Properties n3State = JettraConfigValidator.seedOrLoadClusterState(n3StateFile, null);

            n1Server = new JettraStoreServer(new JettraStoreConfig(
                    baseNodeProps("node-01", "PRIMARY", p1Grpc, p1Rest, tempRoot.resolve("n1-data"), n1StateDir), n1State));
            n2Server = new JettraStoreServer(new JettraStoreConfig(
                    baseNodeProps("node-02", "SECONDARY", p2Grpc, p2Rest, tempRoot.resolve("n2-data"), n2StateDir), partialClusterProps));
            n3Server = new JettraStoreServer(new JettraStoreConfig(
                    baseNodeProps("node-03", "SECONDARY", p3Grpc, p3Rest, tempRoot.resolve("n3-data"), n3StateDir), n3State));

            t1 = new JettraClusterTransportServer(p1Grpc, n1Server);
            t2 = new JettraClusterTransportServer(p2Grpc, n2Server);
            t3 = new JettraClusterTransportServer(p3Grpc, n3Server);
            t1.start();
            t2.start();
            t3.start();
            Thread.sleep(150);

            // Cae node-01 (PRIMARY) y node-02 inicia failover
            t1.close();
            t1 = null;
            n2Server.handleNodeStopping("node-01", "Simulated primary failure");

            assertEquals(ClusterNode.Role.PRIMARY, n2Server.getConfig().getNodeRole(),
                    "node-02 debe promoverse a nuevo PRIMARY tras caída de node-01");

            // Verificar que node-02 leyó cluster-state.config desde jettra.cluster_state_config_dir (preservando node-04)
            assertNotNull(JettraConfigValidator.findNodeInCluster(n2Server.getConfig().getAllClusterNodes(), "node-04"),
                    "El nuevo PRIMARY debe cargar todo el estado desde cluster-state.config incluyendo node-04");

            Properties p2Updated = new Properties();
            try (var is = Files.newInputStream(n2StateFile)) {
                p2Updated.load(is);
            }
            var n2UpdatedNodes = JettraConfigValidator.parseExplicitClusterNodes(p2Updated);
            var n2UpdatedStatuses = JettraConfigValidator.parseClusterNodeStatuses(p2Updated);
            assertEquals("PRIMARY", JettraConfigValidator.findNodeInCluster(n2UpdatedNodes, "node-02").role());
            assertEquals("RUNNING", n2UpdatedStatuses.get("node-02"));
            assertEquals("SECONDARY", JettraConfigValidator.findNodeInCluster(n2UpdatedNodes, "node-01").role());
            assertEquals("STOPPED", n2UpdatedStatuses.get("node-01"));
            assertNotNull(JettraConfigValidator.findNodeInCluster(n2UpdatedNodes, "node-04"));

            // Esperar propagación del cluster-state.config de failover hacia node-03
            boolean n3Updated = false;
            for (int i = 0; i < 30; i++) {
                Properties p3Check = new Properties();
                try (var is = Files.newInputStream(n3StateFile)) {
                    p3Check.load(is);
                }
                var n3Nodes = JettraConfigValidator.parseExplicitClusterNodes(p3Check);
                var n2InN3 = JettraConfigValidator.findNodeInCluster(n3Nodes, "node-02");
                if (n2InN3 != null && "PRIMARY".equalsIgnoreCase(n2InN3.role())
                        && "STOPPED".equalsIgnoreCase(JettraConfigValidator.parseClusterNodeStatuses(p3Check).get("node-01"))) {
                    n3Updated = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(n3Updated, "node-03 debe recibir el nuevo estado del failover con node-02 como PRIMARY");

            // El nuevo PRIMARY (node-02) acepta un cambio adicional (unión de node-05) y lo replica a node-03
            byte[] afterJoin = n2Server.handleJoinClusterRequest("node-05", "SECONDARY", "127.0.0.1", p5Grpc, p5Rest);
            assertNotNull(afterJoin, "El nuevo PRIMARY debe procesar nuevas uniones y actualizar cluster-state.config");

            boolean n3HasNode05 = false;
            for (int i = 0; i < 30; i++) {
                Properties p3After = new Properties();
                try (var is = Files.newInputStream(n3StateFile)) {
                    p3After.load(is);
                }
                if (JettraConfigValidator.findNodeInCluster(
                        JettraConfigValidator.parseExplicitClusterNodes(p3After), "node-05") != null) {
                    n3HasNode05 = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(n3HasNode05, "El nuevo PRIMARY debe replicar actualizaciones posteriores de cluster-state.config a los demás nodos");
        } finally {
            if (t1 != null) try { t1.close(); } catch (Exception ignored) {}
            if (t2 != null) try { t2.close(); } catch (Exception ignored) {}
            if (t3 != null) try { t3.close(); } catch (Exception ignored) {}
            if (n3Server != null) try { n3Server.stop(); } catch (Exception ignored) {}
            if (n2Server != null) try { n2Server.stop(); } catch (Exception ignored) {}
            if (n1Server != null) try { n1Server.stop(); } catch (Exception ignored) {}
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", p1Grpc, 2000),
                "El puerto gRPC del PRIMARY debe liberarse al terminar el test");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", p2Grpc, 2000),
                "El puerto gRPC de node-02 debe liberarse al terminar el test");
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", p3Grpc, 2000),
                "El puerto gRPC de node-03 debe liberarse al terminar el test");
    }

    @Test
    @DisplayName("RF-33: Virtual Threads concurrentes actualizan cluster-state.config sin corrupción ni bloqueos cruzados")
    public void testConcurrentVirtualThreadsStateUpdatesNoCorruption() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf33_vthreads_");
        try {
            Path stateA = tempRoot.resolve("nodeA/cluster-state.config");
            Path stateB = tempRoot.resolve("nodeB/cluster-state.config");

            JettraConfigValidator.upsertNodeInStateFile(stateA, "node-01", "PRIMARY", "127.0.0.1", 56001, 60001, "RUNNING", "init");
            JettraConfigValidator.upsertNodeInStateFile(stateB, "node-01", "PRIMARY", "127.0.0.1", 57001, 61001, "RUNNING", "init");

            int perFile = 8;
            CountDownLatch latch = new CountDownLatch(perFile * 2);
            AtomicReference<Throwable> error = new AtomicReference<>();

            List<Thread> workers = new ArrayList<>();
            for (int i = 2; i <= perFile + 1; i++) {
                final int idx = i;
                workers.add(Thread.ofVirtual().name("vt-stateA-" + idx).start(() -> {
                    try {
                        JettraConfigValidator.upsertNodeInStateFile(
                                stateA, "node-a" + idx, "SECONDARY", "127.0.0.1", 56000 + idx, 60000 + idx, "RUNNING", "vt");
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                }));
                workers.add(Thread.ofVirtual().name("vt-stateB-" + idx).start(() -> {
                    try {
                        JettraConfigValidator.upsertNodeInStateFile(
                                stateB, "node-b" + idx, "SECONDARY", "127.0.0.1", 57000 + idx, 61000 + idx, "RUNNING", "vt");
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                }));
            }

            assertTrue(latch.await(15, TimeUnit.SECONDS), "Todos los Virtual Threads deben finalizar sin deadlock");
            assertNull(error.get(), "No deben ocurrir excepciones en escrituras concurrentes de estado");

            byte[] bytesA = Files.readAllBytes(stateA);
            byte[] bytesB = Files.readAllBytes(stateB);
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(bytesA).isValid());
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(bytesB).isValid());

            Properties propsA = new Properties();
            Properties propsB = new Properties();
            try (var isA = Files.newInputStream(stateA); var isB = Files.newInputStream(stateB)) {
                propsA.load(isA);
                propsB.load(isB);
            }
            assertEquals(perFile + 1, JettraConfigValidator.parseExplicitClusterNodes(propsA).size());
            assertEquals(perFile + 1, JettraConfigValidator.parseExplicitClusterNodes(propsB).size());
        } finally {
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-33 Diagnóstico: Arranque de PRIMARY sin cluster.config previo (single-node off y multinode on) crea cluster-state.config en jettra.cluster_state_config_dir")
    public void testPrimaryStartupWithoutClusterConfigCreatesClusterStateFile() throws Exception {
        Path fakeHome = Files.createTempDirectory("jettra_rf33_primary_boot_");
        Path mpFile = fakeHome.resolve("microprofile-config.properties");
        String prevHome = System.getProperty("user.home");
        String prevMp = System.getProperty("microprofile.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");

        try {
            System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
            System.clearProperty("cluster.config.path");
            System.clearProperty("jettra.cluster_state_config_dir");

            int gPort = findFreePort();
            int rPort = findFreePort();
            Path storage = fakeHome.resolve("data-single");
            Files.writeString(mpFile, String.format("""
                    mp.config.profile=production
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=~/jettra-cluster-state/
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.multinode.active=off
                    jettra.network.grpc.port=%d
                    jettra.network.rest.port=%d
                    """, storage.toAbsolutePath(), gPort, rPort), StandardCharsets.UTF_8);
            System.setProperty("microprofile.config.file", mpFile.toAbsolutePath().toString());

            JettraConfigValidator.validateAndBootstrapOrHalt(false);
            JettraStoreConfig cfgSingle = JettraStoreConfig.load();

            Path expectedStateFile = fakeHome.resolve("jettra-cluster-state")
                    .resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertTrue(Files.exists(expectedStateFile) && Files.size(expectedStateFile) > 0,
                    "Debe crear cluster-state.config dentro de jettra.cluster_state_config_dir al iniciar PRIMARY en modo single-node");
            assertFalse(cfgSingle.isStandbyMode(), "PRIMARY nunca debe quedar en STANDBY cuando falta cluster.config");

            Properties singleState = new Properties();
            try (var is = Files.newInputStream(expectedStateFile)) {
                singleState.load(is);
            }
            var nodesSingle = JettraConfigValidator.parseExplicitClusterNodes(singleState);
            assertEquals(1, nodesSingle.size());
            assertEquals("node-01", nodesSingle.get(0).id());
            assertEquals("PRIMARY", nodesSingle.get(0).role());

            // Repetir con cluster.multinode.active=on sin cluster.config previo y sin estado previo
            Files.deleteIfExists(expectedStateFile);
            int gPortMulti = findFreePort();
            int rPortMulti = findFreePort();
            Files.writeString(mpFile, String.format("""
                    mp.config.profile=production
                    jettra.storage.path=%s
                    jettra.cluster_state_config_dir=~/jettra-cluster-state/
                    cluster.node.id=node-01
                    cluster.node.role=PRIMARY
                    cluster.multinode.active=on
                    jettra.network.grpc.port=%d
                    jettra.network.rest.port=%d
                    """, fakeHome.resolve("data-multi").toAbsolutePath(), gPortMulti, rPortMulti), StandardCharsets.UTF_8);

            JettraStoreConfig cfgMulti = JettraStoreConfig.load();
            assertTrue(Files.exists(expectedStateFile) && Files.size(expectedStateFile) > 0,
                    "Debe crear cluster-state.config dentro de jettra.cluster_state_config_dir al iniciar PRIMARY con multinode=on sin cluster.config");
            assertFalse(cfgMulti.isStandbyMode());
        } finally {
            System.setProperty("user.home", prevHome);
            if (prevMp != null) System.setProperty("microprofile.config.file", prevMp); else System.clearProperty("microprofile.config.file");
            if (prevCluster != null) System.setProperty("cluster.config.path", prevCluster); else System.clearProperty("cluster.config.path");
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys); else System.clearProperty("jettra.cluster_state_config_dir");
            JettraTestCleanup.deleteRecursively(fakeHome);
        }
    }

    @Test
    @DisplayName("RF-33 Diagnóstico: cluster.config en stateDir sin rest.port explícito normaliza puertos y genera cluster-state.config sin fallar silenciosamente")
    public void testSeedWithOmittedRestPortsNormalizesAndPersistsClusterState() throws Exception {
        Path tempRoot = Files.createTempDirectory("jettra_rf33_seed_norm_");
        try {
            Path stateDir = tempRoot.resolve("state");
            Files.createDirectories(stateDir);
            Path seedFile = stateDir.resolve("cluster.config");
            Files.writeString(seedFile, """
                    cluster.name = seed-no-rest
                    cluster.node.1.id = node-01
                    cluster.node.1.role = PRIMARY
                    cluster.node.1.ip = 127.0.0.1
                    cluster.node.1.grpc.port = 50001
                    cluster.node.2.id = node-02
                    cluster.node.2.role = SECONDARY
                    cluster.node.2.ip = 127.0.0.1
                    cluster.node.2.grpc.port = 50002
                    """, StandardCharsets.UTF_8);

            Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Properties seeded = JettraConfigValidator.seedOrLoadClusterState(stateFile, seedFile);
            assertNotNull(seeded, "Debe inicializar cluster-state.config aunque cluster.config omita rest.port");
            assertTrue(Files.exists(stateFile) && Files.size(stateFile) > 0,
                    "El archivo cluster-state.config debe quedar persistido físicamente en disco");
            assertTrue(JettraConfigValidator.validateClusterConfigIntegrity(Files.readAllBytes(stateFile)).isValid());
            assertEquals(2, JettraConfigValidator.parseExplicitClusterNodes(seeded).size());
        } finally {
            JettraTestCleanup.deleteRecursively(tempRoot);
        }
    }

    @Test
    @DisplayName("RF-34: JettraStoreServer.start() crea cluster-state.config en jettra.cluster_state_config_dir en single-node sin sobrescribir existente")
    public void testServerStartCreatesClusterStateInConfiguredDirAndNeverOverwrites() throws Exception {
        Path fakeHome = Files.createTempDirectory("jettra_rf34_srv_boot_");
        String prevHome = System.getProperty("user.home");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevStateSys = System.getProperty("jettra.cluster_state_config_dir");
        String prevMp = System.getProperty("microprofile.config.file");

        int gPort = findFreePort();
        int rPort = findFreePort();
        JettraStoreServer srv = null;
        try {
            System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
            System.clearProperty("cluster.config.path");
            System.clearProperty("jettra.cluster_state_config_dir");
            System.clearProperty("microprofile.config.file");

            Properties props = new Properties();
            props.setProperty("cluster.node.id", "node-01");
            props.setProperty("cluster.node.role", "PRIMARY");
            props.setProperty("cluster.multinode.active", "off");
            props.setProperty("jettra.node.ip", "127.0.0.1");
            props.setProperty("jettra.network.grpc.port", String.valueOf(gPort));
            props.setProperty("jettra.network.rest.port", String.valueOf(rPort));
            props.setProperty("jettra.storage.path", fakeHome.resolve("data").toAbsolutePath().toString());
            props.setProperty("jettra.storage.mode", "JVM_RAM");
            props.setProperty("jettrapolice.active", "false");

            Path mpFile = fakeHome.resolve("microprofile-config.properties");
            Files.writeString(mpFile,
                    "jettra.cluster_state_config_dir=~/jettra-cluster-state/\n"
                            + "cluster.node.id=node-01\n"
                            + "cluster.node.role=PRIMARY\n",
                    StandardCharsets.UTF_8);
            System.setProperty("microprofile.config.file", mpFile.toAbsolutePath().toString());

            JettraStoreConfig cfg = new JettraStoreConfig(props, new Properties(), props);
            srv = new JettraStoreServer(cfg);
            srv.start();

            Path expectedStateFile = fakeHome.resolve("jettra-cluster-state")
                    .resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            assertTrue(Files.exists(expectedStateFile) && Files.size(expectedStateFile) > 0,
                    "JettraStoreServer.start() debe crear cluster-state.config en jettra.cluster_state_config_dir");

            // Añadir marcador de estado vivo y reiniciar sin sobrescribir el estado existente
            JettraConfigValidator.upsertNodeInStateFile(
                    expectedStateFile, "node-02", "SECONDARY", "127.0.0.1", findFreePort(), findFreePort(), "RUNNING", "rf34");
            srv.stop();
            srv = new JettraStoreServer(new JettraStoreConfig(props, new Properties(), props));
            srv.start();

            String afterRestart = Files.readString(expectedStateFile, StandardCharsets.UTF_8);
            assertTrue(afterRestart.contains("node-02"),
                    "El reinicio de JettraStoreServer no debe sobrescribir el cluster-state.config existente");
        } finally {
            if (srv != null) {
                try { srv.stop(); } catch (Exception ignored) {}
            }
            System.setProperty("user.home", prevHome);
            if (prevCluster != null) System.setProperty("cluster.config.path", prevCluster); else System.clearProperty("cluster.config.path");
            if (prevStateSys != null) System.setProperty("jettra.cluster_state_config_dir", prevStateSys); else System.clearProperty("jettra.cluster_state_config_dir");
            if (prevMp != null) System.setProperty("microprofile.config.file", prevMp); else System.clearProperty("microprofile.config.file");
            JettraTestCleanup.deleteRecursively(fakeHome);
        }
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", gPort, 2000));
        assertTrue(JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", rPort, 2000));
    }
}

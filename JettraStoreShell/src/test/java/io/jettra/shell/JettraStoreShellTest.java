package io.jettra.shell;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.jettra.test.core.JettraAssert.*;

public class JettraStoreShellTest {

    private static Path tempTestStorageDir;
    private static Path tempTestClusterConfig;
    private static io.jettra.store.JettraStoreServer sharedTestServer;
    private static int sharedGrpcPort;
    private static int sharedRestPort;
    private static String prevStoragePathProp;
    private static String prevClusterConfigProp;
    private static String prevHealthTimeoutProp;
    private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    private static void startSharedTestNodeServer() throws Exception {
        if (sharedGrpcPort <= 0 || !io.jettra.store.JettraStoreServer.isTcpPortAvailable("127.0.0.1", sharedGrpcPort)) {
            sharedGrpcPort = freePort();
        }
        if (sharedRestPort <= 0 || !io.jettra.store.JettraStoreServer.isTcpPortAvailable("127.0.0.1", sharedRestPort)) {
            sharedRestPort = freePort();
        }
        java.util.Properties props = new java.util.Properties();
        props.setProperty("cluster.node.id", "node-01");
        props.setProperty("cluster.node.role", "PRIMARY");
        props.setProperty("jettra.network.host", "127.0.0.1");
        props.setProperty("jettra.network.grpc.port", String.valueOf(sharedGrpcPort));
        props.setProperty("jettra.network.rest.port", String.valueOf(sharedRestPort));
        props.setProperty("jettra.storage.path", tempTestStorageDir.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", "JVM_RAM");
        props.setProperty("cluster.multinode.active", "off");
        props.setProperty("jettrapolice.active", "false");
        io.jettra.store.core.JettraStoreConfig cfg = new io.jettra.store.core.JettraStoreConfig(props);
        sharedTestServer = new io.jettra.store.JettraStoreServer(cfg);
        sharedTestServer.start();
    }

    @BeforeAll
    public static void setupTempTestStorage() throws Exception {
        suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
        prevStoragePathProp = System.getProperty("jettra.storage.path");
        prevClusterConfigProp = System.getProperty("cluster.config.path");
        prevHealthTimeoutProp = System.getProperty("cluster.healthcheck.timeout.ms");
        JettraShellTestCleanup.cleanStorageDirectories();
        sharedGrpcPort = freePort();
        sharedRestPort = freePort();
        int g2 = freePort(), r2 = freePort();
        int g3 = freePort(), r3 = freePort();
        tempTestStorageDir = Files.createTempDirectory("jettra_shell_test_storage_");
        tempTestClusterConfig = tempTestStorageDir.resolve("cluster-test_db.config");
        Files.writeString(tempTestClusterConfig,
                "cluster.name = jettra-shell-test\n"
                        + "cluster.consensus.protocol = RAFT\n"
                        + "cluster.node.1.id = node-01\n"
                        + "cluster.node.1.role = PRIMARY\n"
                        + "cluster.node.1.ip = 127.0.0.1\n"
                        + "cluster.node.1.grpc.port = " + sharedGrpcPort + "\n"
                        + "cluster.node.1.rest.port = " + sharedRestPort + "\n"
                        + "cluster.node.2.id = node-02\n"
                        + "cluster.node.2.role = SECONDARY\n"
                        + "cluster.node.2.ip = 127.0.0.1\n"
                        + "cluster.node.2.grpc.port = " + g2 + "\n"
                        + "cluster.node.2.rest.port = " + r2 + "\n"
                        + "cluster.node.3.id = node-03\n"
                        + "cluster.node.3.role = SECONDARY\n"
                        + "cluster.node.3.ip = 127.0.0.1\n"
                        + "cluster.node.3.grpc.port = " + g3 + "\n"
                        + "cluster.node.3.rest.port = " + r3 + "\n",
                java.nio.charset.StandardCharsets.UTF_8);
        System.setProperty("jettra.storage.path", tempTestStorageDir.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", tempTestClusterConfig.toAbsolutePath().toString());
        System.setProperty("cluster.healthcheck.timeout.ms", "60");
        startSharedTestNodeServer();
    }

    @AfterEach
    public void cleanupAfterEachTest() {
        JettraShellTestCleanup.cleanDirectory(tempTestStorageDir);
    }

    @AfterAll
    public static void cleanupAfterAllTests() {
        if (sharedTestServer != null) {
            try {
                sharedTestServer.stop();
            } catch (Exception ignored) {
            }
            sharedTestServer = null;
        }
        io.jettra.store.JettraStoreServer.stopAllActiveServers();
        assertEquals(0, io.jettra.store.JettraStoreServer.getActiveServerCount());
        assertEquals(0, io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount());
        if (sharedGrpcPort > 0) {
            assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", sharedGrpcPort, 2000),
                    "Puerto gRPC compartido de Shell debe quedar libre: " + sharedGrpcPort);
        }
        if (sharedRestPort > 0) {
            assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", sharedRestPort, 2000),
                    "Puerto REST compartido de Shell debe quedar libre: " + sharedRestPort);
        }
        JettraShellTestCleanup.deleteRecursively(tempTestStorageDir);
        JettraShellTestCleanup.cleanStorageDirectories();
        if (prevStoragePathProp == null) {
            System.clearProperty("jettra.storage.path");
        } else {
            System.setProperty("jettra.storage.path", prevStoragePathProp);
        }
        if (prevClusterConfigProp == null) {
            System.clearProperty("cluster.config.path");
        } else {
            System.setProperty("cluster.config.path", prevClusterConfigProp);
        }
        if (prevHealthTimeoutProp == null) {
            System.clearProperty("cluster.healthcheck.timeout.ms");
        } else {
            System.setProperty("cluster.healthcheck.timeout.ms", prevHealthTimeoutProp);
        }
        if (suiteStateBackup != null) {
            suiteStateBackup.close();
            suiteStateBackup = null;
        }
    }

    @Test
    @DisplayName("Debe procesar connect, login, logout, save/remove/list connections")
    public void testConnectionLifecycleAndProfiles() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        // 1. Estado inicial sin autenticación
        assertFalse(shell.isAuthenticated());
        String unauthBlocked = shell.executeCommand("SHOW DATABASES");
        assertTrue(unauthBlocked.contains("[AUTH REQUIRED]"));

        // 2. Conexión y login explícito
        String conn = shell.executeCommand("connect 127.0.0.1 50001");
        assertTrue(conn.contains("[CONNECTED]"));

        String login = shell.executeCommand("login admin admin-jettra");
        assertTrue(login.contains("[AUTH SUCCESS]"));
        assertTrue(shell.isAuthenticated());
        assertEquals("admin", shell.getCurrentUser());

        // 3. Guardar conexión actual con save connection
        String save = shell.executeCommand("save connection prod_node_master");
        assertTrue(save.contains("[SUCCESS]"));
        assertTrue(shell.getSavedConnections().containsKey("prod_node_master"));

        // 4. Listar conexiones con list connections y list conections
        String list1 = shell.executeCommand("list connections");
        assertTrue(list1.contains("prod_node_master"));
        assertTrue(list1.contains("127.0.0.1"));

        String list2 = shell.executeCommand("list conections");
        assertTrue(list2.contains("prod_node_master"));

        // 5. Remover conexión con remove connection
        String rem = shell.executeCommand("remove connection prod_node_master");
        assertTrue(rem.contains("[SUCCESS]"));
        assertFalse(shell.getSavedConnections().containsKey("prod_node_master"));

        // 6. Cierre de sesión con logout
        String logout = shell.executeCommand("logout");
        assertTrue(logout.contains("[LOGOUT]"));
        assertFalse(shell.isAuthenticated());

        // 7. Comando bloqueado tras logout
        String blockedAfterLogout = shell.executeCommand("SHOW DATABASES");
        assertTrue(blockedAfterLogout.contains("[AUTH REQUIRED]"));

        // 8. Re-autenticación
        String relogin = shell.executeCommand("login admin admin-jettra");
        assertTrue(relogin.contains("[AUTH SUCCESS]"));
        assertTrue(shell.isAuthenticated());
    }

    @Test
    @DisplayName("Debe procesar status, administración de nodos y lazy reference")
    public void testStatusNodesAndLazyReference() throws Exception {
        java.nio.file.Path tempCluster = java.nio.file.Files.createTempFile("jettra_status_cluster_", ".config");
        java.nio.file.Path tempMp = java.nio.file.Files.createTempFile("jettra_status_mp_", ".properties");
        String prevCluster = System.getProperty("cluster.config.path");
        String prevMp = System.getProperty("jettra.shell.config.file");
        java.nio.file.Files.writeString(tempCluster,
                "cluster.multinode.active=on\n"
                        + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=58101\ncluster.node.1.rest.port=63101\n"
                        + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=58102\ncluster.node.2.rest.port=63102\n"
                        + "cluster.node.3.id=node-03\ncluster.node.3.role=SECONDARY\ncluster.node.3.ip=127.0.0.1\ncluster.node.3.grpc.port=58103\ncluster.node.3.rest.port=63103\n");
        java.nio.file.Files.writeString(tempMp,
                "cluster.ip.nodes=127.0.0.1:58101,127.0.0.1:58102,127.0.0.1:58103\n");
        System.setProperty("cluster.config.path", tempCluster.toAbsolutePath().toString());
        System.setProperty("jettra.shell.config.file", tempMp.toAbsolutePath().toString());
        try {
        JettraStoreShellApp shell = new JettraStoreShellApp(true);

        // 1. Telemetría de recursos con status
        String status = shell.executeCommand("status");
        assertTrue(status.contains("RAM (MEMORIA)"));
        assertTrue(status.contains("PROCESADOR (CPU)"));
        assertTrue(status.contains("DISCO (ALMACENAMIENTO)"));
        assertTrue(status.contains("Panama FFM"));

        // 2. Mostrar nodos del clúster con show nodes (todos inactivos en 58101..58103)
        String nodes = shell.executeCommand("show nodes");
        assertTrue(nodes.contains("node-01"));
        assertTrue(nodes.contains("node-02"));
        assertTrue(nodes.contains("node-03"));
        assertTrue(nodes.contains("DISCONNECTED"));
        assertTrue(nodes.contains("STOPPED"));
        assertTrue(nodes.contains("UNKNOWN"));
        assertFalse(nodes.contains("RUNNING"));
        assertFalse(nodes.contains("LEADER"));
        assertFalse(nodes.contains("FOLLOWER"));

        // 3. Administración de nodos (ADD, STOP, START, REMOVE) usando puerto loopback cerrado (evita 22s de SYN timeout)
        String addNode = shell.executeCommand("ADD NODE node-04 127.0.0.1 58104 SECONDARY");
        assertTrue(addNode.contains("[SUCCESS]"));

        String stopNode = shell.executeCommand("STOP NODE node-04");
        assertTrue(stopNode.contains("[SUCCESS]"));
        assertTrue(shell.executeCommand("show nodes").contains("STOPPED"));

        String startNode = shell.executeCommand("START NODE node-04");
        assertTrue(startNode.contains("INACTIVE") || startNode.contains("inaccesible") || startNode.contains("[ERROR]"));
        assertTrue(shell.executeCommand("show nodes").contains("node-04"));

        String remNode = shell.executeCommand("REMOVE NODE node-04");
        assertTrue(remNode.contains("[SUCCESS]"));

        // 4. Configuración de lazy reference on / off
        String lazyOff = shell.executeCommand("lazy reference off");
        assertTrue(lazyOff.contains("DESACTIVADO (OFF)"));
        assertFalse(shell.isLazyLoad());

        String lazyOn = shell.executeCommand("lazy reference on");
        assertTrue(lazyOn.contains("ACTIVADO (ON)"));
        assertTrue(shell.isLazyLoad());

        // 5. Configuración de STORAGE_MODE (JVM_RAM vs DISK_MEMORY / JettraMemory)
        String smStatus = shell.executeCommand("STORAGE_MODE");
        assertTrue(smStatus.contains("MODO DE ALMACENAMIENTO"));

        String smDisk = shell.executeCommand("STORAGE_MODE DISK_MEMORY");
        assertTrue(smDisk.contains("DISK-MEMORY"));

        String smRam = shell.executeCommand("STORAGE_MODE JVM_RAM");
        assertTrue(smRam.contains("JVM-RAM"));
        } finally {
            if (prevCluster == null) System.clearProperty("cluster.config.path");
            else System.setProperty("cluster.config.path", prevCluster);
            if (prevMp == null) System.clearProperty("jettra.shell.config.file");
            else System.setProperty("jettra.shell.config.file", prevMp);
            java.nio.file.Files.deleteIfExists(tempCluster);
            java.nio.file.Files.deleteIfExists(tempMp);
        }
    }

    @Test
    @DisplayName("Debe instalar la base de datos de ejemplo con INSTALL SAMPLES, mostrar show samples y ejecutar JettraQL y JettraSQL")
    public void testInstallAllSamplesAndQueries() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // 1. INSTALL SAMPLES debe instalar la única base de datos de ejemplo con máximo
            // 500 registros
            String samples = shell.executeCommand("INSTALL SAMPLES");
            assertTrue(samples.contains("[SUCCESS]"));
            assertTrue(samples.contains("example_factura_db"));
            assertTrue(samples.contains("500"));

            // 2. Verificar que la base de datos está registrada en show dbs y show samples
            String showDbs = shell.executeCommand("SHOW DATABASES");
            assertTrue(showDbs.contains("example_factura_db"));

            String showSamples = shell.executeCommand("SHOW SAMPLES");
            assertTrue(showSamples.contains("example_factura_db"));
            assertTrue(showSamples.contains("INSTALADA"));

            // 3. Consultas expresivas con JettraQL
            String jqlFrom = shell.executeCommand("FROM facturas");
            assertTrue(jqlFrom.contains("JETTRAQL [FROM]"));
            assertTrue(jqlFrom.contains("fac_"));

            String jqlFetch = shell.executeCommand("FETCH facturas fac_0 RESOLVE REFS");
            assertTrue(jqlFetch.contains("JETTRAQL [FETCH]"));

            String jqlMatch = shell.executeCommand("MATCH (cli_0)-[FACTURA_EMITIDA]->(fac_0) IN red_comercial");
            assertTrue(jqlMatch.contains("JETTRAQL [MATCH]"));

            // 4. Consultas con JettraSQL
            String sqlSelect = shell.executeCommand("SQL SELECT * FROM facturas");
            assertTrue(sqlSelect.contains("JETTRASQL RESULTADO"));
            assertTrue(sqlSelect.contains("fac_"));
        }
    }

    @Test
    @DisplayName("Debe administrar índices: creación, listado, reconstrucción y eliminación")
    public void testIndexManagement() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("CREATE DATABASE idx_test_db");
            shell.executeCommand("USE idx_test_db");
            shell.executeCommand(
                    "INSERT INTO articles ID art1 JSON {\"title\": \"Java 25 Vectors\", \"category\": \"Tech\"}");

            // 1. Crear índice
            String createIdx = shell.executeCommand("CREATE INDEX idx_cat ON articles (category) TYPE BTREE");
            assertTrue(createIdx.contains("[SUCCESS]"));
            assertTrue(createIdx.contains("idx_cat"));

            // 2. Listar índices
            String showIdx = shell.executeCommand("SHOW INDEXES");
            assertTrue(showIdx.contains("idx_cat"));
            assertTrue(showIdx.contains("articles"));

            // 3. Reconstruir índice
            String rebuild = shell.executeCommand("ALTER INDEX idx_cat REBUILD");
            assertTrue(rebuild.contains("[SUCCESS]"));

            // 4. Eliminar índice
            String dropIdx = shell.executeCommand("DROP INDEX idx_cat");
            assertTrue(dropIdx.contains("[SUCCESS]"));
        }
    }

    @Test
    @DisplayName("Debe administrar usuarios y roles RBAC por base de datos")
    public void testUserAndRoleManagement() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // 1. Listar usuarios
            String showUsers = shell.executeCommand("SHOW USERS");
            assertTrue(showUsers.contains("admin"));
            assertTrue(showUsers.contains("SUPER_ADMIN"));

            // 2. Crear usuario
            String createUsr = shell.executeCommand("CREATE USER dev_user PASSWORD secret-pass ROLE DEVELOPER");
            assertTrue(createUsr.contains("[SUCCESS]"));

            // 3. Asignar rol de base de datos
            String grant = shell.executeCommand("GRANT READ_WRITE ON example_factura_db TO dev_user");
            assertTrue(grant.contains("[SUCCESS]"));

            // 4. Mostrar privilegios
            String grants = shell.executeCommand("SHOW GRANTS FOR dev_user");
            assertTrue(grants.contains("example_factura_db"));
            assertTrue(grants.contains("READ_WRITE"));

            // 5. Revocar rol
            String revoke = shell.executeCommand("REVOKE example_factura_db FROM dev_user");
            assertTrue(revoke.contains("[SUCCESS]"));

            // 6. Eliminar usuario
            String dropUser = shell.executeCommand("DROP USER dev_user");
            assertTrue(dropUser.contains("[SUCCESS]"));
        }
    }

    @Test
    @DisplayName("Debe procesar registros referenciados y operaciones CRUD")
    public void testReferencedRecordsAndCrud() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("CREATE DATABASE ref_test_db");
            shell.executeCommand("USE ref_test_db");

            // 1. Inserción de documentos
            shell.executeCommand(
                    "INSERT INTO customers ID c100 JSON {\"name\": \"Maria Silva\", \"city\": \"Panama\"}");
            shell.executeCommand("INSERT INTO orders ID o500 JSON {\"amount\": 350.0, \"status\": \"NEW\"}");

            // 2. Vincular referencia cruzada JettraRef
            String insRef = shell
                    .executeCommand("INSERT REF orders o500 KEY _ref_customer TARGET document::customers#c100");
            assertTrue(insRef.contains("[SUCCESS]"));

            // 3. Obtener registro y verificar resolución
            String get = shell.executeCommand("GET orders o500");
            assertTrue(get.contains("o500"));
            assertTrue(get.contains("JettraRef Resolución"));
            assertTrue(get.contains("Maria Silva"));

            // 4. Resolver referencia explícita
            String res = shell.executeCommand("RESOLVE REF document::customers#c100");
            assertTrue(res.contains("Maria Silva"));

            // 5. Mostrar referencias del registro
            String showRefs = shell.executeCommand("SHOW REFS orders o500");
            assertTrue(showRefs.contains("_ref_customer"));

            // 6. Eliminar registro
            String del = shell.executeCommand("DELETE orders o500");
            assertTrue(del.contains("[SUCCESS]"));
        }
    }

    @Test
    @DisplayName("Debe ejecutar comando BACKUP DATABASE y RESTORE DATABASE desde el shell")
    public void testShellBackupRestore() throws IOException {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("INSTALL SAMPLES");

            Path tempBackup = Files.createTempFile("shell_backup", ".jettra_bak");
            String backupOutput = shell
                    .executeCommand("BACKUP DATABASE example_factura_db TO '" + tempBackup.toAbsolutePath() + "'");
            assertTrue(backupOutput.contains("[SUCCESS]"));

            String restoreOutput = shell
                    .executeCommand("RESTORE DATABASE example_factura_db FROM '" + tempBackup.toAbsolutePath() + "'");
            assertTrue(restoreOutput.contains("[SUCCESS]"));

            Files.deleteIfExists(tempBackup);
        }
    }

    @Test
    @DisplayName("Debe listar buckets/units, mostrar registros multimodelo y contar registros con show buckets, show records y count")
    public void testShowBucketsShowRecordsAndCount() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("INSTALL SAMPLES");

            // 1. SHOW BUCKETS / SHOW UNIT en example_factura_db
            shell.executeCommand("USE example_factura_db");
            String buckets = shell.executeCommand("SHOW BUCKETS");
            assertTrue(buckets.contains("example_factura_db"));
            assertTrue(buckets.contains("facturas"));
            assertTrue(buckets.contains("DOCUMENT"));

            String units = shell.executeCommand("SHOW UNIT");
            assertTrue(units.contains("facturas"));

            // 2. Filtro por motor
            String docBuckets = shell.executeCommand("SHOW BUCKETS DOCUMENT");
            assertTrue(docBuckets.contains("facturas"));

            // 3. SHOW RECORDS con paginación
            String recs = shell.executeCommand("SHOW RECORDS facturas LIMIT 3");
            assertTrue(recs.contains("REGISTROS DE DOCUMENT BUCKET 'facturas'"));
            assertTrue(recs.contains("fac_"));

            // 4. COUNT para una unidad específica y COUNT ALL
            String cntFac = shell.executeCommand("COUNT facturas");
            assertTrue(cntFac.contains("[COUNT] [DOCUMENT] 'facturas':"));

            String cntAll = shell.executeCommand("COUNT ALL");
            assertTrue(cntAll.contains("CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: 'example_factura_db'"));
            assertTrue(cntAll.contains("500 registro(s) multimodelo"));

            // 5. Verificar Motores Vectorial y Grafo en example_factura_db
            assertTrue(buckets.contains("VECTOR"));
            assertTrue(buckets.contains("GRAPH"));

            String vecRecs = shell.executeCommand("SHOW RECORDS factura_embeddings LIMIT 2");
            assertTrue(vecRecs.contains("REGISTROS DE VECTOR BUCKET 'factura_embeddings'"));

            String graphRecs = shell.executeCommand("SHOW RECORDS red_comercial LIMIT 2");
            assertTrue(graphRecs.contains("REGISTROS DE GRAPH BUCKET 'red_comercial'"));

            String cntVec = shell.executeCommand("COUNT factura_embeddings");
            assertTrue(cntVec.contains("[COUNT] [VECTOR] 'factura_embeddings':"));

            String cntGraph = shell.executeCommand("COUNT red_comercial");
            assertTrue(cntGraph.contains("[COUNT] [GRAPH] 'red_comercial':"));
        }
    }

    @Test
    @DisplayName("Debe cargar la base de datos example_factura_db como única muestra multimodelo con máximo 500 registros")
    public void testLoadFacturaSampleDatabaseMax500Records() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            String samples = shell.executeCommand("SHOW SAMPLES");
            assertTrue(samples.contains("example_factura_db"));

            String loadResult = shell.executeCommand("LOAD SAMPLE example_factura_db");
            assertTrue(loadResult.contains("CARGA DE BASE DE DATOS DE EJEMPLO"));
            assertTrue(loadResult.contains("500"));

            String buckets = shell.executeCommand("SHOW BUCKETS");

            String countAll = shell.executeCommand("COUNT ALL");
            assertTrue(countAll.contains("500 registro(s) multimodelo"));
            assertTrue(countAll.contains("auditoria_records"));

            String stats = shell.executeCommand("DB STATS");
            assertTrue(stats.contains("Documentos:"));
            assertTrue(stats.contains("Clave-Valor (KV):"));
            assertTrue(stats.contains("Vectores:"));
            assertTrue(stats.contains("Grafos:"));
            assertTrue(stats.contains("Series Temporales:"));
            assertTrue(stats.contains("Geoespacial (GIS):"));
            assertTrue(stats.contains("Columnar (OLAP):"));
            assertTrue(stats.contains("Java Records:"));
            assertTrue(stats.contains("auditoria_records"));
            assertTrue(stats.contains("sucursales_fiscales"));
            assertTrue(stats.contains("analitica_fiscal"));

            String showRecs = shell.executeCommand("SHOW RECORDS facturas LIMIT 2");
            assertTrue(showRecs.contains("REGISTROS DE DOCUMENT BUCKET 'facturas'"));

            String showAudit = shell.executeCommand("SHOW RECORDS auditoria_records LIMIT 2");
            assertTrue(showAudit.contains("REGISTROS DE TYPED RECORD BUCKET 'auditoria_records'"));
        }
    }

    @Test
    @DisplayName("Debe eliminar fisicamente la base de datos con DROP DATABASE y DROP DB")
    public void testDropDatabaseCommand() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // 1. Crear BD 'drop_test_db' y verificar existencia
            String created = shell.executeCommand("CREATE DATABASE drop_test_db");
            assertTrue(created.contains("[SUCCESS]"));
            shell.executeCommand("USE drop_test_db");
            shell.executeCommand("CREATE BUCKET users");
            shell.executeCommand("INSERT INTO users (id, name) VALUES ('u1', 'Alice')");

            String dbsBefore = shell.executeCommand("SHOW DATABASES");
            assertTrue(dbsBefore.contains("drop_test_db"));

            // 2. Eliminar con DROP DATABASE
            String dropResult = shell.executeCommand("DROP DATABASE drop_test_db");
            assertTrue(dropResult.contains("[SUCCESS]"));

            String dbsAfter = shell.executeCommand("SHOW DATABASES");
            assertFalse(dbsAfter.contains("drop_test_db"));

            // 3. Crear con CREATE DB y eliminar con DROP DB
            shell.executeCommand("CREATE DB drop2_test_db");
            String dbs2 = shell.executeCommand("SHOW DATABASES");
            assertTrue(dbs2.contains("drop2_test_db"));

            String dropResult2 = shell.executeCommand("DROP DB drop2_test_db");
            assertTrue(dropResult2.contains("[SUCCESS]"));

            String dbsAfter2 = shell.executeCommand("SHOW DATABASES");
            assertFalse(dbsAfter2.contains("drop2_test_db"));
        }
    }

    @Test
    @DisplayName("show dbs debe ejecutarse de forma perezosa (lazy) sin cargar todo el contenido a RAM")
    public void testShowDatabasesLazyLoadingAndNoOOM() throws Exception {
        java.nio.file.Path tempStorage = java.nio.file.Files.createTempDirectory("jettra_lazy_dbs_");
        java.nio.file.Path tempCluster = java.nio.file.Files.createTempFile("jettra_lazy_cluster_", ".config");
        java.nio.file.Files.writeString(tempCluster,
                "cluster.multinode.active=off\ncluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port="
                        + sharedGrpcPort + "\ncluster.node.1.rest.port=" + sharedRestPort + "\n",
                java.nio.charset.StandardCharsets.UTF_8);
        String prevStorage = System.getProperty("jettra.storage.path");
        String prevCluster = System.getProperty("cluster.config.path");
        System.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
        System.setProperty("cluster.config.path", tempCluster.toAbsolutePath().toString());
        try (JettraClient client = JettraClient.connect("127.0.0.1", sharedGrpcPort, "admin", "admin-jettra");
             JettraStoreShellApp shell = new JettraStoreShellApp(client)) {

            // 1. Sin bases creadas arbitrariamente, show dbs lista la tabla vacía sin
            // default_db
            String showDbsEmpty = shell.executeCommand("show dbs");
            assertTrue(showDbsEmpty.contains("Base de Datos"));
            assertFalse(showDbsEmpty.contains("default_db"));

            // 2. Crear una BD explícitamente y verificar listado con show dbs y show
            // databases
            shell.executeCommand("CREATE DATABASE lazy_test_db");
            String showDbs = shell.executeCommand("show dbs");
            assertTrue(showDbs.contains("lazy_test_db"));
            assertTrue(showDbs.contains("Base de Datos"));

            String showDatabases = shell.executeCommand("show databases");
            assertTrue(showDatabases.contains("lazy_test_db"));
            shell.executeCommand("DROP DATABASE lazy_test_db");
        } finally {
            if (prevStorage == null) {
                System.clearProperty("jettra.storage.path");
            } else {
                System.setProperty("jettra.storage.path", prevStorage);
            }
            if (prevCluster == null) {
                System.clearProperty("cluster.config.path");
            } else {
                System.setProperty("cluster.config.path", prevCluster);
            }
            java.nio.file.Files.deleteIfExists(tempCluster);
        }
    }

    @Test
    @DisplayName("select * from clientes debe ejecutarse de forma streaming acotada sin OutOfMemoryError")
    public void testSelectClientesQueryStreamingAndLimit() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("INSTALL SAMPLES");
            shell.executeCommand("use example_factura_db");

            // 1. select * from clientes sin limit explicito aplica default limit acotado
            String resDefault = shell.executeCommand("select * from clientes");
            System.out.println(
                    "DEBUG select * from clientes:\n" + resDefault.substring(0, Math.min(resDefault.length(), 600)));
            assertTrue(resDefault.contains("JETTRASQL RESULTADO"));
            assertTrue(resDefault.contains("clientes"));
            assertTrue(resDefault.contains("fila(s)"));

            // 2. select con LIMIT explicito
            String resLimit5 = shell.executeCommand("select * from clientes limit 5");
            assertTrue(resLimit5.contains("5 fila(s) retornada(s)"));

            // 3. select con WHERE usando índice
            String resWhere = shell.executeCommand("select * from clientes where _id = cli_50");
            assertTrue(resWhere.contains("JETTRASQL RESULTADO"));
        }
    }

    @Test
    @DisplayName("Debe gestionar paginación interactiva con FIRST, PREV, NEXT, LAST y PAGE_SIZE")
    public void testInteractivePaginationCommands() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            shell.executeCommand("INSTALL SAMPLES FACTURA");
            shell.executeCommand("use example_factura_db");

            // 1. Configurar tamaño de página
            String sizeRes = shell.executeCommand("PAGE_SIZE 15");
            assertTrue(sizeRes.contains("15 registros"));

            // 2. Ejecutar consulta base que activa paginación
            String p1 = shell.executeCommand("select * from clientes");
            assertTrue(p1.contains("PÁGINA [ 1 /"));
            assertTrue(p1.contains("Tamaño de página: 15"));

            // 3. Desplazarse a la siguiente página (NEXT o SIGUIENTE)
            String p2 = shell.executeCommand("NEXT");
            assertTrue(p2.contains("PÁGINA [ 2 /"));

            // 4. Desplazarse a la página anterior (PREV o ANTERIOR)
            String prev = shell.executeCommand("PREV");
            assertTrue(prev.contains("PÁGINA [ 1 /"));

            // 5. Ir a la última página (LAST o ULTIMO)
            String pLast = shell.executeCommand("LAST");
            assertTrue(pLast.contains("PÁGINA ["));

            // 6. Ir a la primera página (FIRST o PRIMERO)
            String pFirst = shell.executeCommand("FIRST");
            assertTrue(pFirst.contains("PÁGINA [ 1 /"));

            // 7. Salto directo a página específica (PAGE 3)
            String p3 = shell.executeCommand("PAGE 3");
            assertTrue(p3.contains("PÁGINA [ 3 /"));
        }
    }

    @Test
    @DisplayName("Debe procesar comandos interactivos de cálculo, agregación, finanzas, estadística y vectores")
    public void testShellCalcAggregationsStatsFinanceVector() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra");
                JettraStoreShellApp shell = new JettraStoreShellApp(client)) {

            // 1. Math
            String mathRes = shell.executeCommand("MATH 50 + cbrt(1000) * 2");
            assertTrue(mathRes.contains("JETTRASQL RESULTADO"));
            assertTrue(mathRes.contains("70.0"));

            // 2. Finance
            String finPmt = shell.executeCommand("FINANCE PMT 0.004166 360 200000");
            assertTrue(finPmt.contains("cuota_pmt"));

            String finCagr = shell.executeCommand("FINANCE CAGR 100 200 3");
            assertTrue(finCagr.contains("cagr_pct"));

            // 3. Stats
            String statsSummary = shell.executeCommand("STATS SUMMARY 10, 20, 30, 40, 50");
            assertTrue(statsSummary.contains("mean"));
            assertTrue(statsSummary.contains("median"));

            // 4. Vector
            String vecDot = shell.executeCommand("VECTOR DOT [1, 2, 3] [4, 5, 6]");
            assertTrue(vecDot.contains("producto_punto"));
            assertTrue(vecDot.contains("32.0"));

            String vecCross = shell.executeCommand("VECTOR CROSS [1, 0, 0] [0, 1, 0]");
            assertTrue(vecCross.contains("producto_cruz_3d"));
            assertTrue(vecCross.contains("0.0, 0.0, 1.0"));

            // 5. Aggregations via Shell
            shell.executeCommand("USE shell_calc_test_db");
            shell.executeCommand("INSERT INTO metrics VALUES ('m1', '{\"server\": \"srv1\", \"cpu\": 40.0}')");
            shell.executeCommand("INSERT INTO metrics VALUES ('m2', '{\"server\": \"srv1\", \"cpu\": 60.0}')");
            shell.executeCommand("INSERT INTO metrics VALUES ('m3', '{\"server\": \"srv2\", \"cpu\": 80.0}')");

            String aggRes = shell.executeCommand("AGGREGATE metrics GROUP BY server SUM cpu AVG cpu COUNT");
            assertTrue(aggRes.contains("SUM(cpu)"));
            assertTrue(aggRes.contains("AVG(cpu)"));

            // 6. SQL GROUP BY standard
            String sqlGroup = shell.executeCommand(
                    "SELECT server, SUM(cpu) AS total_cpu, AVG(cpu) AS avg_cpu FROM metrics GROUP BY server");
            assertTrue(sqlGroup.contains("total_cpu"));
        }
    }

    @Test
    @DisplayName("Debe gestionar historial persistente, expansión bang (!n, !!, !prefix) y autocompletado")
    public void testHistoryAndAutocomplete() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // 1. Limpiar historial inicial
            String clearRes = shell.executeCommand("HISTORY CLEAR");
            assertTrue(clearRes.contains("[OK]"));
            assertTrue(shell.getCommandHistory().isEmpty());

            // 2. Ejecutar comandos y verificar registro
            shell.executeCommand("USE default_db");
            shell.executeCommand("STATUS");
            shell.executeCommand("SHOW DATABASES");

            assertEquals(3, shell.getCommandHistory().size());
            assertEquals("USE default_db", shell.getCommandHistory().get(0));
            assertEquals("STATUS", shell.getCommandHistory().get(1));
            assertEquals("SHOW DATABASES", shell.getCommandHistory().get(2));

            // 3. Comando HISTORY
            String histDisplay = shell.executeCommand("HISTORY");
            assertTrue(histDisplay.contains("USE default_db"));
            assertTrue(histDisplay.contains("STATUS"));
            assertTrue(histDisplay.contains("SHOW DATABASES"));

            // 4. Búsqueda en historial
            String searchRes = shell.executeCommand("HISTORY SEARCH stat");
            assertTrue(searchRes.contains("STATUS"));

            // 5. Expansión bang
            String bangLast = shell.executeCommand("!!");
            assertTrue(bangLast.contains("default_db"));

            String bangFirst = shell.executeCommand("!1");
            assertTrue(bangFirst.contains("[SUCCESS] Conmutado a base de datos activa: 'default_db'"));

            String bangPrefix = shell.executeCommand("!stat");
            System.out.println("DEBUG bangPrefix: " + bangPrefix);
            assertTrue(bangPrefix.contains("Panama FFM") || bangPrefix.contains("RESOURCE MONITOR"));

            // 6. Autocompletado programático y por comando shell
            var complShow = shell.autocomplete("SHOW");
            System.out.println("DEBUG complShow: " + complShow);
            assertTrue(complShow.contains("SHOW DATABASES"));
            assertTrue(complShow.contains("SHOW BUCKETS"));

            var complLoad = shell.autocomplete("LOAD SAMPLE ");
            System.out.println("DEBUG complLoad: " + complLoad);
            assertTrue(complLoad.contains("LOAD SAMPLE example_factura_db"));

            var complUse = shell.autocomplete("USE ");
            System.out.println("DEBUG complUse: " + complUse);
            assertTrue(complUse.contains("USE default_db"));

            String complCli = shell.executeCommand("COMPLETE SH");
            System.out.println("DEBUG complCli: " + complCli);
            assertTrue(complCli.contains("SHOW DATABASES") || complCli.contains("SHOW BUCKETS"));

            String tabCli = shell.executeCommand("TAB AGG");
            System.out.println("DEBUG tabCli: " + tabCli);
            assertTrue(tabCli.contains("AGGREGATE ") || tabCli.contains("AGG SUM "));
        }
    }

    @Test
    @DisplayName("Debe gestionar comandos de modo multinodo MULTINODE, MULTINODE ON y MULTINODE OFF en Shell")
    public void testClusterMultinodeShellCommands() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);

            // 1. Consultar estado multinodo
            String statusRes = shell.executeCommand("MULTINODE");
            assertTrue(statusRes.contains("cluster.multinode.active"));
            assertTrue(statusRes.contains("Algoritmo de Consenso"));

            // 2. Desactivar multinodo (Modo Servidor Único)
            String offRes = shell.executeCommand("MULTINODE OFF");
            assertTrue(offRes.contains("cluster.multinode.active = OFF"));
            assertFalse(client.getRingEngine().isMultinodeActive());

            // 3. Verificar que SHOW NODES y STATUS reflejan el modo OFF
            String nodesOff = shell.executeCommand("SHOW NODES");
            assertTrue(nodesOff.contains("cluster.multinode.active"));
            assertTrue(nodesOff.contains("OFF"));

            String statusOff = shell.executeCommand("STATUS");
            assertTrue(statusOff.contains("cluster.multinode.active"));
            assertTrue(statusOff.contains("OFF"));

            // 4. Reactivar multinodo
            String onRes = shell.executeCommand("MULTINODE ON");
            assertTrue(onRes.contains("cluster.multinode.active = ON"));
            assertTrue(client.getRingEngine().isMultinodeActive());

            String nodesOn = shell.executeCommand("SHOW NODES");
            assertTrue(nodesOn.contains("ON"));
        }
    }

    @Test
    @DisplayName("Debe procesar cluster-distributed info, cluster-distributed <db> y cluster-distributed all")
    public void testClusterDistributedCommands() {
        JettraStoreShellApp shell = new JettraStoreShellApp(true);
        shell.executeCommand("CREATE DATABASE dist_test_db");

        // 1. cluster-distributed info
        String info = shell.executeCommand("cluster-distributed info");
        assertTrue(info.contains("JETTRASTORE CLUSTER DATABASE DISTRIBUTION INFO"));
        assertTrue(info.contains("Nodo ID"));
        assertTrue(info.contains("node-01"));

        // 2. cluster-distributed <nombre-bd>
        String distDb = shell.executeCommand("cluster-distributed dist_test_db");
        assertTrue(distDb.contains("[CLUSTER-DISTRIBUTED]"));
        assertTrue(distDb.contains("dist_test_db"));

        // 3. cluster-distributed con tags '<' y '>'
        String distDbTags = shell.executeCommand("cluster-distributed <dist_test_db>");
        assertTrue(distDbTags.contains("[CLUSTER-DISTRIBUTED]"));

        // 4. cluster-distributed all y <all>
        String distAll = shell.executeCommand("cluster-distributed all");
        assertTrue(distAll.contains("[CLUSTER-DISTRIBUTED <ALL>]"));

        String distAllTags = shell.executeCommand("cluster-distributed <all>");
        assertTrue(distAllTags.contains("[CLUSTER-DISTRIBUTED <ALL>]"));

        // 5. Autocompletado de cluster-distributed
        var compl = shell.autocomplete("cluster-distributed ");
        assertTrue(compl.contains("cluster-distributed all"));
        assertTrue(compl.contains("cluster-distributed info"));
    }

    @Test
    @DisplayName("Debe mostrar ayuda de cluster-distributed en help general y help cluster-distributed")
    public void testHelpClusterDistributedCommands() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        // 1. Ayuda general 'help' y '?'
        String helpGeneral = shell.executeCommand("help");
        assertTrue(helpGeneral.contains(
                "cluster-distributed <all>             distribuye entre todos los nodos todas las bases de datos"));
        assertTrue(
                helpGeneral.contains("cluster-distributed <nombre-base-datos>: distribuye la base de datos indicada"));
        assertTrue(helpGeneral.contains(
                "cluster-distributed info :            Muestra una tabla con los nodos y las bases de datos en cada nodo."));
        assertTrue(helpGeneral.contains("cluster live [límite]"));

        String helpQuestion = shell.executeCommand("?");
        assertTrue(helpQuestion.contains("cluster-distributed <all>"));
        assertTrue(helpQuestion.contains("cluster live [límite]"));

        // 2. Ayuda temática 'help cluster-distributed' y 'help cluster live'
        String helpTopic = shell.executeCommand("help cluster-distributed");
        assertTrue(helpTopic.contains("AYUDA DE COMANDOS: CLUSTER-DISTRIBUTED"));
        assertTrue(helpTopic.contains("cluster-distributed <all>"));
        assertTrue(helpTopic.contains("cluster-distributed <nombre-base-datos>:"));
        assertTrue(helpTopic.contains("cluster-distributed info :"));
        assertTrue(helpTopic.contains("cluster live [límite]"));

        String helpLive = shell.executeCommand("help cluster live");
        assertTrue(helpLive.contains("AYUDA DE COMANDOS: CLUSTER-DISTRIBUTED"));
        assertTrue(helpLive.contains("cluster live [límite]"));
    }

    @Test
    @DisplayName("Debe ejecutar comando 'cluster live' y mostrar flujo de eventos en tiempo real")
    public void testClusterLiveCommand() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);
        shell.executeCommand("connect 127.0.0.1 50001");
        shell.executeCommand("login admin admin-jettra");

        // Publicar un evento de prueba en el bus
        io.jettra.store.cluster.JettraClusterEventBus.getInstance().publish(
                io.jettra.store.cluster.ClusterLiveEvent.TYPE_DOCUMENT_REPLICATED,
                "node-01", "node-02", "Replicando factura fac_100 a nodo 2", "db=ventas");

        String out = shell.executeCommand("cluster live");
        assertTrue(out.contains("JETTRASTORE CLUSTER LIVE EVENT STREAM"));
        assertTrue(out.contains("DOCUMENT_REPLICATED"));
        assertTrue(out.contains("Replicando factura fac_100 a nodo 2"));

        String outLimit = shell.executeCommand("cluster live 5");
        assertTrue(outLimit.contains("JETTRASTORE CLUSTER LIVE EVENT STREAM"));

        String menu = shell.executeCommand("menu");
        assertTrue(menu.contains("[9] Flujo de Eventos en Tiempo Real (cluster live)"));
    }

    @Test
    @DisplayName("Debe verificar autocompletado de comandos con TAB (coincidencia única y sugerencias múltiples)")
    public void testTabAutocompletionSingleAndMultipleMatches() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        // 1. Coincidencia única: 'LOAD SAMPLE ex' debe autocompletar a
        // 'example_factura_db'
        var singleMatches = shell.autocomplete("LOAD SAMPLE ex");
        assertNotNull(singleMatches);
        assertEquals(1, singleMatches.size());
        assertEquals("LOAD SAMPLE example_factura_db", singleMatches.get(0));

        // 2. Coincidencia única: 'STATU' debe retornar 'STATUS'
        var statusMatches = shell.autocomplete("STATU");
        assertNotNull(statusMatches);
        assertTrue(statusMatches.contains("STATUS"));

        // 3. Múltiples coincidencias: 'SH' debe devolver múltiples comandos SHOW
        var multiMatches = shell.autocomplete("SH");
        assertNotNull(multiMatches);
        assertTrue(multiMatches.size() > 1);
        assertTrue(multiMatches.contains("SHOW DATABASES"));
        assertTrue(multiMatches.contains("SHOW BUCKETS"));
        assertFalse(multiMatches.contains("SHOW COLLECTIONS"));

        // 4. Múltiples coincidencias para subcomandos: 'SHOW '
        var showSubMatches = shell.autocomplete("SHOW ");
        assertNotNull(showSubMatches);
        assertTrue(showSubMatches.contains("SHOW DATABASES"));
        assertTrue(showSubMatches.contains("SHOW BUCKETS"));
        assertFalse(showSubMatches.contains("SHOW COLLECTIONS"));

        // 5. Metadatos de autocompletado: grupo y descripción
        String dbGroup = shell.resolveCandidateGroup("SHOW DATABASES");
        assertEquals("Bases de Datos", dbGroup);

        String crudGroup = shell.resolveCandidateGroup("SELECT * FROM facturas");
        assertEquals("CRUD y Consultas", crudGroup);

        String descr = shell.resolveCandidateDescription("SHOW DATABASES");
        assertNotNull(descr);
        assertTrue(descr.contains("bases de datos"));
    }

    @Test
    @DisplayName("Debe validar navegación de historial con flechas Arriba/Abajo y mapeo de teclas ANSI")
    public void testHistoryNavigationUpDownArrowsAndKeyBindings() throws Exception {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        // 1. Limpiar historial
        shell.executeCommand("HISTORY CLEAR");
        assertTrue(shell.getCommandHistory().isEmpty());

        // 2. Crear LineReader simulado con terminal virtual
        org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.builder()
                .dumb(true)
                .build();

        org.jline.reader.LineReader lineReader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(terminal)
                .option(org.jline.reader.LineReader.Option.AUTO_MENU_LIST, false)
                .option(org.jline.reader.LineReader.Option.AUTO_LIST, true)
                .build();

        // Aplicar los mismos mapeos ANSI que utiliza el Shell interactivo
        for (String mapName : java.util.List.of(org.jline.reader.LineReader.EMACS, org.jline.reader.LineReader.MAIN)) {
            org.jline.keymap.KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.UP_LINE_OR_HISTORY), "\033[A",
                        "\033OA");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.DOWN_LINE_OR_HISTORY), "\033[B",
                        "\033OB");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.FORWARD_CHAR), "\033[C", "\033OC");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.BACKWARD_CHAR), "\033[D", "\033OD");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.BEGINNING_OF_LINE), "\033[H",
                        "\033[1~", "\033OH");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.END_OF_LINE), "\033[F", "\033[4~",
                        "\033OF");
                map.bind(new org.jline.reader.Reference(org.jline.reader.LineReader.DELETE_CHAR), "\033[3~");
            }
        }

        // 3. Verificar que tanto secuencias ANSI (\033[A, \033[B) como Application
        // (\033OA, \033OB) están mapeadas
        org.jline.keymap.KeyMap<org.jline.reader.Binding> mainMap = lineReader.getKeyMaps()
                .get(org.jline.reader.LineReader.MAIN);
        assertNotNull(mainMap);
        assertNotNull(mainMap.getBound("\033[A"));
        assertNotNull(mainMap.getBound("\033OA"));
        assertNotNull(mainMap.getBound("\033[B"));
        assertNotNull(mainMap.getBound("\033OB"));

        // 4. Vincular activeLineReader al shell y ejecutar comandos
        shell.setActiveLineReader(lineReader);

        shell.executeCommand("CONNECT 127.0.0.1 50001");
        shell.executeCommand("USE default_db");
        shell.executeCommand("DB STATS");

        assertEquals(3, shell.getCommandHistory().size());
        assertEquals("CONNECT 127.0.0.1 50001", shell.getCommandHistory().get(0));
        assertEquals("USE default_db", shell.getCommandHistory().get(1));
        assertEquals("DB STATS", shell.getCommandHistory().get(2));

        // 5. Verificar que el historial de JLine contiene los comandos registrados
        org.jline.reader.History jlineHistory = lineReader.getHistory();
        assertTrue(jlineHistory.size() >= 3);

        // 6. Navegación secuencial por historial
        // Al final del historial (índice 3), navegar hacia atrás (Arriba)
        jlineHistory.last(); // posicionarse al final
        assertTrue(jlineHistory.previous());
        assertEquals("DB STATS", jlineHistory.current());

        assertTrue(jlineHistory.previous());
        assertEquals("USE default_db", jlineHistory.current());

        assertTrue(jlineHistory.previous());
        assertEquals("CONNECT 127.0.0.1 50001", jlineHistory.current());

        // Navegar hacia adelante (Abajo)
        assertTrue(jlineHistory.next());
        assertEquals("USE default_db", jlineHistory.current());

        assertTrue(jlineHistory.next());
        assertEquals("DB STATS", jlineHistory.current());

        // 7. Limpiar historial con HISTORY CLEAR purga shell y JLine
        String clearMsg = shell.executeCommand("HISTORY CLEAR");
        assertTrue(clearMsg.contains("[OK]"));
        assertTrue(shell.getCommandHistory().isEmpty());
        assertEquals(0, lineReader.getHistory().size());
    }

    @Test
    @DisplayName("Debe iniciar y operar sin cluster.nodes= usando cluster.ip.nodes y conecction.config al agregar o remover nodos/conexiones")
    public void testMicroprofileClusterNodesLoadAndPersist() throws Exception {
        java.nio.file.Path tempMp = java.nio.file.Files.createTempFile("jettra_shell_mp_", ".properties");
        java.nio.file.Path tempCluster = java.nio.file.Files.createTempFile("jettra_shell_cluster_", ".config");
        String prevCfg = System.getProperty("jettra.shell.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        try {
            java.nio.file.Files.writeString(tempCluster,
                    "cluster.multinode.active=on\n"
                            + "cluster.node.1.id=node-01\ncluster.node.1.role=PRIMARY\ncluster.node.1.ip=127.0.0.1\ncluster.node.1.grpc.port=55001\ncluster.node.1.rest.port=65001\n"
                            + "cluster.node.2.id=node-02\ncluster.node.2.role=SECONDARY\ncluster.node.2.ip=127.0.0.1\ncluster.node.2.grpc.port=55002\ncluster.node.2.rest.port=65002\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            System.setProperty("cluster.config.path", tempCluster.toAbsolutePath().toString());
            java.nio.file.Files.writeString(tempMp,
                    "# JettraStore Shell Test Config\napp.title=JettraStore Shell\ncluster.ip.nodes=127.0.0.1:55001,127.0.0.1:55002\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            System.setProperty("jettra.shell.config.file", tempMp.toAbsolutePath().toString());

            try (JettraClient client = JettraClient.connect("127.0.0.1", 55001, "admin", "admin-jettra")) {
                JettraStoreShellApp shell = new JettraStoreShellApp(client);

                // 1. Verificar carga inicial desde cluster.ip.nodes sin requerir cluster.nodes=
                String menuBefore = shell.getConnectionsMenuDisplay();
                assertTrue(menuBefore.contains("node-01"));
                assertTrue(menuBefore.contains("127.0.0.1:55001"));
                assertTrue(menuBefore.contains("node-02"));
                assertTrue(menuBefore.contains("127.0.0.1:55002"));
                assertEquals(2, shell.getOrderedConnectionsList().size());

                String listBefore = shell.executeCommand("LIST CONNECTIONS");
                assertTrue(listBefore.contains("node-01"));
                assertTrue(listBefore.contains("node-02"));

                // 2. Guardar nueva conexión y verificar que no escribe cluster.nodes= en microprofile-config.properties
                String saveRes = shell.executeCommand("SAVE CONNECTION node-04 127.0.0.1 55004 admin");
                assertTrue(saveRes.contains("[SUCCESS]"));
                String mpAfterSave = java.nio.file.Files.readString(tempMp, java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(mpAfterSave.contains("cluster.nodes="));

                // 3. Agregar nodo con ADD NODE y verificar que se registra sin escribir cluster.nodes=
                String addRes = shell.executeCommand("ADD NODE node-05 127.0.0.1 55005 SECONDARY 65005");
                assertTrue(addRes.contains("[SUCCESS]"));
                String mpAfterAdd = java.nio.file.Files.readString(tempMp, java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(mpAfterAdd.contains("cluster.nodes="));

                // 4. Remover nodo con REMOVE NODE
                String remRes = shell.executeCommand("REMOVE NODE node-05");
                assertTrue(remRes.contains("[SUCCESS]"));

                // Limpiar node-04
                shell.executeCommand("REMOVE CONNECTION node-04");
            }
        } finally {
            if (prevCfg == null) {
                System.clearProperty("jettra.shell.config.file");
            } else {
                System.setProperty("jettra.shell.config.file", prevCfg);
            }
            if (prevCluster == null) {
                System.clearProperty("cluster.config.path");
            } else {
                System.setProperty("cluster.config.path", prevCluster);
            }
            java.nio.file.Files.deleteIfExists(tempCluster);
            java.nio.file.Files.deleteIfExists(tempMp);
        }
    }

    @Test
    public void testClusterAddNodeAndRemoveNodeCommands() throws Exception {
        java.nio.file.Path tempMp = java.nio.file.Files.createTempFile("jettra_shell_cluster_", ".properties");
        java.nio.file.Path tempCluster = java.nio.file.Files.createTempFile("cluster_", ".config");
        String prevCfg = System.getProperty("jettra.shell.config.file");
        String prevCluster = System.getProperty("cluster.config.path");
        try {
            java.nio.file.Files.writeString(tempMp,
                    "# Shell Test Config\napp.title=JettraStore Shell\ncluster.ip.nodes=127.0.0.1:50001\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(tempCluster,
                    "cluster.name = test-cluster\ncluster.node.1.id = node-01\ncluster.node.1.role = PRIMARY\ncluster.node.1.ip = 127.0.0.1\ncluster.node.1.grpc.port = 50001\ncluster.node.1.rest.port = 8081\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            System.setProperty("jettra.shell.config.file", tempMp.toAbsolutePath().toString());
            System.setProperty("cluster.config.path", tempCluster.toAbsolutePath().toString());

            try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra");
                 JettraStoreShellApp shell = new JettraStoreShellApp(client)) {

                // CLUSTER ADD-NODE -> RF-32 persiste en cluster-state.config sin sobrescribir cluster.config estático
                String addRes = shell.executeCommand("CLUSTER ADD-NODE node-06 127.0.0.1 50006 SECONDARY 8086");
                assertTrue(addRes.contains("[SUCCESS]"));
                assertTrue(addRes.contains("node-06"));

                java.nio.file.Path stateFile = io.jettra.store.core.JettraConfigValidator.resolveClusterStateFile();
                String stateContentAfterAdd = java.nio.file.Files.readString(stateFile,
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(stateContentAfterAdd.contains("node-06"));
                assertFalse(java.nio.file.Files.readString(tempCluster,
                        java.nio.charset.StandardCharsets.UTF_8).contains("node-06"));

                // CLUSTER REMOVE-NODE
                String remRes = shell.executeCommand("CLUSTER REMOVE-NODE node-06");
                assertTrue(remRes.contains("[SUCCESS]"));

                String stateContentAfterRem = java.nio.file.Files.readString(stateFile,
                        java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(stateContentAfterRem.contains("node-06"));
                java.nio.file.Files.deleteIfExists(stateFile);
            }
        } finally {
            if (prevCfg == null) {
                System.clearProperty("jettra.shell.config.file");
            } else {
                System.setProperty("jettra.shell.config.file", prevCfg);
            }
            if (prevCluster == null) {
                System.clearProperty("cluster.config.path");
            } else {
                System.setProperty("cluster.config.path", prevCluster);
            }
            java.nio.file.Files.deleteIfExists(tempMp);
            java.nio.file.Files.deleteIfExists(tempCluster);
        }
    }

    @Test
    @DisplayName("Debe listar en SHOW DATABASES las bases almacenadas en el nodo PRIMARY sin instalar bases arbitrarias")
    public void testShowDatabasesFromPrimaryWithoutArbitraryInstall() throws Exception {
        java.nio.file.Path tempStorage = java.nio.file.Files.createTempDirectory("jettra_shell_primary_dbs_");
        int grpcPort = freePort();
        int restPort = freePort();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("cluster.node.id", "node-01");
        props.setProperty("cluster.node.role", "PRIMARY");
        props.setProperty("jettra.network.host", "127.0.0.1");
        props.setProperty("jettra.network.rest.port", String.valueOf(restPort));
        props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
        props.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", "JVM_RAM");
        props.setProperty("cluster.multinode.active", "on");
        props.setProperty("jettrapolice.active", "false");
        io.jettra.store.core.JettraStoreConfig primaryCfg = new io.jettra.store.core.JettraStoreConfig(props);
        io.jettra.store.JettraStoreServer primaryServer = new io.jettra.store.JettraStoreServer(primaryCfg);
        try {
            primaryServer.start();
            // 1. Verificar inicio limpio sin bases arbitrarias al conectar desde Shell
            try (JettraStoreShellApp shell = new JettraStoreShellApp(false)) {
                shell.executeCommand("connect 127.0.0.1 " + grpcPort);
                String loginOut = shell.executeCommand("login admin admin-jettra");
                assertTrue(loginOut.contains("[AUTH SUCCESS]"));

                String emptyShow = shell.executeCommand("SHOW DATABASES");
                assertFalse(emptyShow.contains("default_db"));
                assertFalse(emptyShow.contains("example_factura_db"));
                assertTrue(emptyShow.contains("Total: 0 base(s) de datos almacenadas en el nodo PRIMARY"));

                // 2. Crear bases de datos en el nodo PRIMARY y verificar que SHOW DATABASES las
                // lista desde el PRIMARY
                primaryServer.getOrCreateDatabase("ventas_primary_test_db");
                primaryServer.getOrCreateDatabase("inventario_primary_test_db");

                String populatedShow = shell.executeCommand("SHOW DATABASES");
                assertTrue(populatedShow.contains("ventas_primary_test_db"));
                assertTrue(populatedShow.contains("inventario_primary_test_db"));
                assertFalse(populatedShow.contains("default_db"));
                assertTrue(populatedShow.contains("Total: 2 base(s) de datos almacenadas en el nodo PRIMARY"));
            }
        } finally {
            primaryServer.stop();
            JettraShellTestCleanup.deleteRecursively(tempStorage);
            if (sharedTestServer != null) {
                try {
                    sharedTestServer.stop();
                } catch (Exception ignored) {
                }
                startSharedTestNodeServer();
            }
        }
        assertTrue(io.jettra.store.JettraStoreServer.isTcpPortAvailable("127.0.0.1", grpcPort));
        assertTrue(io.jettra.store.JettraStoreServer.isTcpPortAvailable("127.0.0.1", restPort));
    }

    @Test
    @DisplayName("JettraId en JettraStoreShell: inserción sin ID autogenera JettraId y ID manual rechaza duplicados")
    public void testJettraIdShellInsertAutoAndCustomId() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);
        String dbName = "shell_jettraid_test_db";
        try {
            shell.executeCommand("login admin admin-jettra");
            shell.executeCommand("CREATE DATABASE " + dbName);
            shell.executeCommand("USE " + dbName);

            // 1. INSERT INTO sin ID -> autogenera JettraId y lo imprime en consola
            String outAuto = shell.executeCommand("INSERT INTO Ventas JSON {\"cliente\":\"ACME\",\"total\":500}");
            assertTrue(outAuto.contains("[SUCCESS]") && outAuto.contains("JettraId"),
                    "Debe generar JettraId y mostrarlo en la salida de consola: " + outAuto);

            // 2. INSERT INTO con ID personalizado -> acepta y luego rechaza duplicado
            String outCustom = shell.executeCommand("INSERT INTO Ventas ID VEN-001 JSON {\"cliente\":\"Beta\",\"total\":200}");
            assertTrue(outCustom.contains("[SUCCESS]") && outCustom.contains("VEN-001"),
                    "Debe aceptar ID personalizado: " + outCustom);

            String outDup = shell.executeCommand("INSERT INTO Ventas ID VEN-001 JSON {\"cliente\":\"Dup\",\"total\":100}");
            assertTrue(outDup.contains("[ERROR]") && outDup.contains("PRIMARY KEY VIOLATION"),
                    "Debe rechazar ID duplicado en Shell: " + outDup);

            // 3. INSERT BUCKET sin ID y con ID opcional
            String outBucketAuto = shell.executeCommand("INSERT BUCKET Facturas {\"folio\":\"F1\",\"total\":90}");
            assertTrue(outBucketAuto.contains("[SUCCESS]") && outBucketAuto.contains("JettraId"),
                    "INSERT BUCKET sin ID debe generar JettraId: " + outBucketAuto);

            String outBucketCustom = shell.executeCommand("INSERT BUCKET Facturas ID FAC-99 {\"folio\":\"F99\",\"total\":99}");
            assertTrue(outBucketCustom.contains("[SUCCESS]") && outBucketCustom.contains("FAC-99"),
                    "INSERT BUCKET con ID personalizado debe respetarlo: " + outBucketCustom);
        } finally {
            try { shell.executeCommand("DROP DATABASE " + dbName); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("RF-12: Unificación a BUCKET en motor DOCUMENT y rechazo de comandos COLLECTION en JettraStoreShell")
    public void testRf12BucketUnificationAndCollectionRemovalInShell() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);
        String dbName = "rf12_shell_bucket_test_db";
        try {
            shell.executeCommand("login admin admin-jettra");
            shell.executeCommand("CREATE DATABASE " + dbName);
            shell.executeCommand("USE " + dbName);

            // 1. Comandos COLLECTION deben ser rechazados explícitamente
            String errCreate = shell.executeCommand("CREATE COLLECTION clientes");
            assertTrue(errCreate.contains("[ERROR]") && errCreate.contains("BUCKET"),
                    "CREATE COLLECTION debe rechazarse indicando el uso de BUCKET: " + errCreate);

            String errShow = shell.executeCommand("SHOW COLLECTIONS");
            assertTrue(errShow.contains("[ERROR]") && errShow.contains("BUCKET"),
                    "SHOW COLLECTIONS debe rechazarse indicando SHOW BUCKETS: " + errShow);

            String errDrop = shell.executeCommand("DROP COLLECTION clientes");
            assertTrue(errDrop.contains("[ERROR]") && errDrop.contains("BUCKET"),
                    "DROP COLLECTION debe rechazarse indicando DROP BUCKET: " + errDrop);

            // 2. Autocompletado no debe sugerir COLLECTION
            assertFalse(shell.autocomplete("CREATE ").contains("CREATE COLLECTION "));
            assertFalse(shell.autocomplete("DROP ").contains("DROP COLLECTION "));
            assertTrue(shell.autocomplete("CREATE ").contains("CREATE BUCKET "));
            assertTrue(shell.autocomplete("DROP ").contains("DROP BUCKET "));

            // 3. Flujo completo con BUCKET en motor DOCUMENT
            String created = shell.executeCommand("CREATE BUCKET clientes TYPE DOCUMENT");
            assertTrue(created.contains("[SUCCESS]") && created.contains("Bucket 'clientes'"),
                    "CREATE BUCKET debe crear el bucket en DOCUMENT: " + created);

            String listed = shell.executeCommand("SHOW BUCKETS");
            assertTrue(listed.contains("DOCUMENT") && listed.contains("Bucket") && listed.contains("clientes"),
                    "SHOW BUCKETS debe mostrar DOCUMENT con unidad Bucket: " + listed);
            assertFalse(listed.contains("Collection"),
                    "SHOW BUCKETS no debe etiquetar el motor DOCUMENT como Collection: " + listed);

            String dropped = shell.executeCommand("DROP BUCKET clientes");
            assertTrue(dropped.contains("[SUCCESS]") && dropped.contains("clientes"),
                    "DROP BUCKET debe eliminar el bucket: " + dropped);
        } finally {
            try { shell.executeCommand("DROP DATABASE " + dbName); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("RF-17: Comando migrate registrado en help y ejecución con parámetros en JettraStoreShell")
    public void testMigrateCommandInShell() {
        JettraStoreShellApp shell = new JettraStoreShellApp(false);

        // 1. Verificar comando en 'help'
        String help = shell.executeCommand("help");
        assertTrue(help.contains("MIGRACIÓN POSTGRESQL Y GENERADOR BACKEND (RF-17)"),
                "help debe registrar la sección de migración RF-17");
        assertTrue(help.contains("migrate --db=<base_datos> [opciones]"),
                "help debe documentar la sintaxis del comando migrate");

        // 2. Validación de parámetro obligatorio
        String resNoDb = shell.executeCommand("migrate");
        assertTrue(resNoDb.contains("[ERROR] Parámetro obligatorio faltante: --db="),
                "Debe exigir el parámetro --db");

        // 3. Ejecución en modo dry-run / simulación
        String resDry = shell.executeCommand("migrate --host=127.0.0.1 --port=5432 --db=postgres --dry-run");
        assertTrue(resDry.contains("MIGRACIÓN POSTGRESQL"),
                "Debe mostrar el banner de migración");
        assertTrue(resDry.contains("SIMULACIÓN (DRY-RUN)"),
                "Debe indicar modo de simulación");
    }

    @Test
    @DisplayName("RF-18: Comandos de historial de versiones, inspección y restauración en JettraStoreShell")
    public void testVersionHistoryAndRestoreCommandsInShell() {
        String dbName = "rf18_shell_ver_" + System.nanoTime();
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            JettraStoreShellApp shell = new JettraStoreShellApp(client);
            try {
                // 1. Verificar ayuda de comandos de versiones
                String help = shell.executeCommand("help");
                assertTrue(help.contains("HISTORIAL DE VERSIONES Y RESTAURACIÓN DE REGISTROS (RF-18)"),
                        "help debe documentar sección RF-18");
                String helpVer = shell.executeCommand("help versions");
                assertTrue(helpVer.contains("RESTORE VERSION"),
                        "help versions debe incluir RESTORE VERSION");
                String verFlagHelp = shell.executeCommand("version --help");
                assertTrue(verFlagHelp.contains("VERSIONS <bucket> <id>"),
                        "version --help debe mostrar la sintaxis");

                // 2. Crear BD y mutar un registro para generar v1, v2, v3
                assertTrue(shell.executeCommand("CREATE DATABASE " + dbName).contains("[SUCCESS]"));
                shell.executeCommand("USE " + dbName);
                assertTrue(shell.executeCommand("CREATE BUCKET productos TYPE DOCUMENT").contains("[SUCCESS]"));

                assertTrue(shell.executeCommand("INSERT INTO productos ID p1 JSON {\"nombre\":\"Monitor\",\"precio\":300}")
                        .contains("[SUCCESS]"));
                assertTrue(shell.executeCommand("UPDATE productos SET {\"precio\":350} WHERE _id = 'p1'")
                        .contains("[SUCCESS]"));
                assertTrue(shell.executeCommand("UPDATE productos SET {\"precio\":400} WHERE _id = 'p1'")
                        .contains("[SUCCESS]"));

                // 3. Listar historial
                String hist = shell.executeCommand("VERSIONS productos p1");
                assertTrue(hist.contains("v1") && hist.contains("CREATE"), "Historial debe incluir v1 CREATE: " + hist);
                assertTrue(hist.contains("v2") && hist.contains("UPDATE"), "Historial debe incluir v2 UPDATE: " + hist);
                assertTrue(hist.contains("v3"), "Historial debe incluir v3: " + hist);

                // 4. Inspeccionar versión puntual (aceptando v1 o 1)
                String showV1 = shell.executeCommand("VERSION SHOW productos p1 v1");
                assertTrue(showV1.contains("DETALLE DE VERSIÓN v1") && showV1.contains("300"),
                        "VERSION SHOW debe mostrar el snapshot de v1: " + showV1);

                // 5. Restaurar a v1 -> crea v4
                String restore1 = shell.executeCommand("RESTORE VERSION productos p1 1");
                assertTrue(restore1.contains("[SUCCESS]") && restore1.contains("v4") && restore1.contains("RESTORE"),
                        "RESTORE VERSION debe confirmar nueva versión v4: " + restore1);
                String afterRestore1 = shell.executeCommand("GET productos p1");
                assertTrue(afterRestore1.contains("300"), "El registro activo debe tener el precio de v1 (300): " + afterRestore1);

                // 6. Restauración consecutiva a v2 -> crea v5
                String restore2 = shell.executeCommand("RESTORE VERSION productos p1 v2");
                assertTrue(restore2.contains("[SUCCESS]") && restore2.contains("v5"),
                        "Restauración consecutiva debe crear v5: " + restore2);
                String afterRestore2 = shell.executeCommand("GET productos p1");
                assertTrue(afterRestore2.contains("350"), "El registro activo debe tener el precio de v2 (350): " + afterRestore2);

                // 7. Casos borde y validación de errores
                assertTrue(shell.executeCommand("VERSIONS productos no_existe").contains("[NOT FOUND]"),
                        "Registro sin historial debe retornar [NOT FOUND]");
                assertTrue(shell.executeCommand("RESTORE VERSION productos p1 99").contains("[ERROR]"),
                        "Versión inexistente debe retornar [ERROR]");
                assertTrue(shell.executeCommand("RESTORE VERSION productos p1 abc").contains("[ERROR]"),
                        "Número de versión inválido debe retornar [ERROR]");
                assertTrue(shell.executeCommand("VERSION SHOW productos p1").contains("[ERROR]"),
                        "Parámetros insuficientes deben retornar [ERROR]");
            } finally {
                try { shell.executeCommand("DROP DATABASE " + dbName); } catch (Exception ignored) {}
            }
        }
    }
}

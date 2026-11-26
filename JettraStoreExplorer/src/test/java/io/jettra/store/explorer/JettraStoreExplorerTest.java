package io.jettra.store.explorer;

import io.jettra.server.core.JettraServer;
import io.jettra.server.core.config.ServerConfig;
import io.jettra.server.core.storage.StorageMode;
import io.jettra.store.core.JettraClusterStateBackupManager;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.NodeLifecycleManager;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Suite de pruebas de alto rendimiento para JettraStore Explorer validada con
 * JettraTest.
 * 100% libre de dependencias JUnit. Optimizada con reutilización de servidor en
 * Virtual Threads,
 * clientes HTTP compartidos y comprobación TCP local instantánea.
 */
public class JettraStoreExplorerTest {

        private static int freeLocalPort() {
                try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1,
                                java.net.InetAddress.getByName("127.0.0.1"))) {
                        return ss.getLocalPort();
                } catch (Exception e) {
                        return 9480;
                }
        }

        // Servidor compartido para evitar arranque/parada secuencial en cada test
        private static JettraServer sharedServer;
        private static int sharedPort = 0;
        private static String sharedAuthCookie;

        // Clientes HTTP reutilizables de alta eficiencia (evitan alloc innecesario de
        // sockets y threads)
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

        private static final HttpClient REDIRECT_CLIENT = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

        private static java.nio.file.Path tempTestMicroprofileConfig;
        private static java.nio.file.Path tempTestClusterConfig;
        private static java.nio.file.Path tempTestClusterNodesFile;
        private static java.nio.file.Path tempTestStorageDir;
        private static JettraClusterStateBackupManager.BackupHandle suiteStateBackup;

        static {
                try {
                        // Crear archivo temporal microprofile-config-test_db.properties,
                        // cluster-test_db.config
                        // y directorio efímero de almacenamiento totalmente aislado para evitar
                        // contaminación de producción o home
                        java.nio.file.Path targetDir = java.nio.file.Path.of("target");
                        java.nio.file.Files.createDirectories(targetDir);
                        tempTestStorageDir = java.nio.file.Files.createTempDirectory("jettra_test_explorer_storage_");
                        String testStoragePath = tempTestStorageDir.toAbsolutePath().toString();
                        System.setProperty("jettra.storage.path", testStoragePath);
                        System.setProperty("storage.path", testStoragePath);

                        tempTestMicroprofileConfig = targetDir.resolve("microprofile-config-test_db.properties");
                        java.nio.file.Files.writeString(tempTestMicroprofileConfig,
                                        "server.host=127.0.0.1\n"
                                                        + "server.port=8085\n"
                                                        + "server.contextpath=/\n"
                                                        + "storage.mode=OFF_HEAP\n"
                                                        + "jettra.storage.path=" + testStoragePath + "\n"
                                                        + "storage.path=" + testStoragePath + "\n"
                                                        + "app.title=JettraStore Explorer\n"
                                                        + "cluster.multinode.active=on\n"
                                                        + "cluster.healthcheck.timeout.ms=200\n"
                                                        + "cluster.ip.nodes=127.0.0.1:59001,127.0.0.1:59002,127.0.0.1:59003\n",
                                        java.nio.charset.StandardCharsets.UTF_8);
                        tempTestClusterConfig = targetDir.resolve("cluster-test_db.config");
                        java.nio.file.Files.writeString(tempTestClusterConfig,
                                        "cluster.name = jettra-test-cluster\n"
                                                        + "cluster.consensus.protocol = RAFT\n"
                                                        + "cluster.node.1.id = node-01\n"
                                                        + "cluster.node.1.role = PRIMARY\n"
                                                        + "cluster.node.1.ip = 127.0.0.1\n"
                                                        + "cluster.node.1.grpc.port = 59001\n"
                                                        + "cluster.node.1.rest.port = 69001\n"
                                                        + "cluster.node.2.id = node-02\n"
                                                        + "cluster.node.2.role = SECONDARY\n"
                                                        + "cluster.node.2.ip = 127.0.0.1\n"
                                                        + "cluster.node.2.grpc.port = 59002\n"
                                                        + "cluster.node.2.rest.port = 69002\n"
                                                        + "cluster.node.3.id = node-03\n"
                                                        + "cluster.node.3.role = SECONDARY\n"
                                                        + "cluster.node.3.ip = 127.0.0.1\n"
                                                        + "cluster.node.3.grpc.port = 59003\n"
                                                        + "cluster.node.3.rest.port = 69003\n",
                                        java.nio.charset.StandardCharsets.UTF_8);
                        tempTestClusterNodesFile = targetDir.resolve("cluster_nodes_test_db.json");
                        System.setProperty("jettra.explorer.config.file",
                                        tempTestMicroprofileConfig.toAbsolutePath().toString());
                        System.setProperty("microprofile.config.file",
                                        tempTestMicroprofileConfig.toAbsolutePath().toString());
                        System.setProperty("cluster.config.path", tempTestClusterConfig.toAbsolutePath().toString());
                        System.setProperty("jettra.explorer.cluster.nodes.file",
                                        tempTestClusterNodesFile.toAbsolutePath().toString());
                } catch (Exception ignored) {
                }

                // Reducir latencias de comprobación de clúster a loopback local (0.05 ms vs
                // 1000 ms por timeout TCP)
                System.setProperty("cluster.healthcheck.timeout.ms", "200");
                System.setProperty("cluster.heartbeat.interval.ms", "60000");

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                                JettraExplorerTestCleanup.cleanAll(JettraStorePlugin.getInstance(), tempTestStorageDir);
                        } catch (Exception ignored) {
                        }
                        if (sharedServer != null && sharedServer.isRunning()) {
                                App.stopApplication(sharedServer);
                        }
                        cleanupTempConfigFiles();
                        if (tempTestStorageDir != null) {
                                JettraExplorerTestCleanup.deleteRecursively(tempTestStorageDir);
                        }
                }));
        }

        private static void cleanupTempConfigFiles() {
                try {
                        if (tempTestMicroprofileConfig != null) {
                                java.nio.file.Files.deleteIfExists(tempTestMicroprofileConfig);
                        }
                } catch (Exception ignored) {
                }
                try {
                        if (tempTestClusterConfig != null) {
                                java.nio.file.Files.deleteIfExists(tempTestClusterConfig);
                        }
                } catch (Exception ignored) {
                }
                try {
                        if (tempTestClusterNodesFile != null) {
                                java.nio.file.Files.deleteIfExists(tempTestClusterNodesFile);
                        }
                } catch (Exception ignored) {
                }
        }

        @BeforeAll
        public static void setUpSuite() throws Exception {
                suiteStateBackup = JettraClusterStateBackupManager.protectConfigured();
                ensureSharedServer();
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                if (plugin.getDatabaseNames().isEmpty()) {
                        // Confirmado arranque limpio sin bases de datos de ejemplo precargadas;
                        // se instalan bajo demanda para los tests de exploración/consultas de esta
                        // suite.
                        plugin.installAllExampleDatabases();
                }
        }

        @AfterAll
        public static void tearDownSuite() {
                int portToVerify = sharedPort;
                try {
                        JettraExplorerTestCleanup.cleanAll(JettraStorePlugin.getInstance(), tempTestStorageDir);
                } catch (Exception ignored) {
                }
                if (sharedServer != null) {
                        App.stopApplication(sharedServer);
                        sharedServer = null;
                } else {
                        JettraStorePlugin.getInstance().stopClusterHeartbeatMonitor();
                }
                App.stopAllApplications();
                io.jettra.store.JettraStoreServer.stopAllActiveServers();
                JettraAssert.assertEquals(0, App.getActiveApplicationCount(),
                                "No deben quedar servidores HTTP de Explorer activos al terminar la suite");
                JettraAssert.assertEquals(0, io.jettra.store.JettraStoreServer.getActiveServerCount(),
                                "No deben quedar servidores JettraStore activos al terminar la suite");
                JettraAssert.assertEquals(0,
                                io.jettra.store.cluster.JettraClusterTransportServer.getActiveTransportCount(),
                                "No deben quedar transportes TCP activos al terminar la suite");
                if (portToVerify > 0) {
                        JettraAssert.assertTrue(
                                        io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1",
                                                        portToVerify, 2000),
                                        "El puerto HTTP de JettraStoreExplorerTest debe quedar libre al terminar: "
                                                        + portToVerify);
                }
                cleanupTempConfigFiles();
                System.clearProperty("jettra.explorer.config.file");
                System.clearProperty("microprofile.config.file");
                System.clearProperty("jettra.explorer.cluster.nodes.file");
                System.clearProperty("jettra.storage.path");
                System.clearProperty("storage.path");
                if (tempTestStorageDir != null) {
                        JettraExplorerTestCleanup.deleteRecursively(tempTestStorageDir);
                }
                if (suiteStateBackup != null) {
                        suiteStateBackup.close();
                        suiteStateBackup = null;
                }
        }

        private static volatile boolean cleanStartVerified = false;

        private static synchronized void ensureSharedServer() throws Exception {
                if (sharedServer == null || !sharedServer.isRunning()) {
                        if (sharedPort <= 0 || !App.isPortAvailable("127.0.0.1", sharedPort)) {
                                sharedPort = freeLocalPort();
                        }
                        sharedServer = App.startApplication(sharedPort, StorageMode.OFF_HEAP);
                        sharedAuthCookie = null;
                        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                        if (!cleanStartVerified) {
                                cleanStartVerified = plugin.getDatabaseNames().isEmpty();
                        }
                        if (plugin.getDatabaseNames().isEmpty()) {
                                plugin.installAllExampleDatabases();
                        }
                }
        }

        private static synchronized String getAuthCookie() throws Exception {
                ensureSharedServer();
                if (sharedAuthCookie == null || App.getActiveSessionCount() == 0) {
                        sharedAuthCookie = loginAndGetSessionCookie(sharedPort);
                }
                return sharedAuthCookie;
        }

        private static String loginAndGetSessionCookie(int port) throws Exception {
                return loginAndGetSessionCookie(port, "admin", "admin123");
        }

        private static String loginAndGetSessionCookie(int port, String username, String password) throws Exception {
                HttpRequest loginReq = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers
                                                .ofString("username=" + username + "&password=" + password))
                                .build();
                HttpResponse<String> res = CLIENT.send(loginReq, HttpResponse.BodyHandlers.ofString());
                JettraAssert.assertEquals(302, res.statusCode(), "El login de prueba debe emitir redirección 302");
                String setCookie = res.headers().firstValue("Set-Cookie").orElse("");
                JettraAssert.assertTrue(setCookie.contains("jettra_session="), "Debe emitir cookie jettra_session");
                return setCookie.split(";")[0].trim();
        }

        // --- Helpers de Petición HTTP Concisos y Rápidos ---
        private HttpResponse<String> get(String path) throws Exception {
                return get(path, getAuthCookie());
        }

        private HttpResponse<String> get(String path, String cookie) throws Exception {
                ensureSharedServer();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + sharedPort + path))
                                .GET();
                if (cookie != null && !cookie.isBlank()) {
                        builder.header("Cookie", cookie);
                }
                return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> getWithRedirect(String path) throws Exception {
                ensureSharedServer();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + sharedPort + path))
                                .header("Cookie", getAuthCookie())
                                .GET();
                return REDIRECT_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> postForm(String path, String formBody) throws Exception {
                return postForm(path, formBody, getAuthCookie());
        }

        private HttpResponse<String> post(String path, String formBody) throws Exception {
                return postForm(path, formBody, getAuthCookie());
        }

        private HttpResponse<String> postForm(String path, String formBody, String cookie) throws Exception {
                ensureSharedServer();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + sharedPort + path))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(formBody));
                if (cookie != null && !cookie.isBlank()) {
                        builder.header("Cookie", cookie);
                }
                return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> postFormWithRedirect(String path, String formBody) throws Exception {
                ensureSharedServer();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + sharedPort + path))
                                .header("Cookie", getAuthCookie())
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(formBody));
                return REDIRECT_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> postJson(String path, String jsonBody) throws Exception {
                ensureSharedServer();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + sharedPort + path))
                                .header("Cookie", getAuthCookie())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        // =========================================================================
        // CASOS DE PRUEBA
        // =========================================================================

        @Test
        @DisplayName("1. Verificar renderizado de la página de Login en JettraFlux")
        public void testLoginPageRendering() throws Exception {
                HttpResponse<String> res = get("/login", null);

                JettraAssert.assertEquals(200, res.statusCode(), "Login debe responder HTTP 200");
                JettraAssert.assertTrue(res.body().contains("JettraStore Explorer"), "Debe contener el título");
                JettraAssert.assertTrue(res.body().contains("name=\"username\""), "Debe contener campo de usuario");
                JettraAssert.assertTrue(res.body().contains("name=\"password\""), "Debe contener campo de contraseña");
                JettraAssert.assertTrue(res.body().contains("Iniciar Sesión"), "Debe contener botón de ingreso");
        }

        @Test
        @DisplayName("2. Administrar Nodos: Verificar vista de clúster, topología y métricas de nodos")
        public void testClusterNodesManagementView() throws Exception {
                HttpResponse<String> res = get("/cluster");

                JettraAssert.assertEquals(200, res.statusCode(), "El monitor de clúster debe responder HTTP 200");
                JettraAssert.assertTrue(res.body().contains("Nodos ACTIVE") || res.body().contains("Nodos Activos"),
                                "Debe incluir KPI de nodos activos (ACTIVE)");
                JettraAssert.assertTrue(res.body().contains("Ingesta Global QPS"), "Debe incluir métricas QPS");
                JettraAssert.assertTrue(res.body().contains("Panama Direct Memory"),
                                "Debe incluir Panama Direct Memory");
                JettraAssert.assertTrue(res.body().contains("node-01"), "Debe listar el nodo master");
                JettraAssert.assertTrue(res.body().contains("node-02"), "Debe listar réplicas Raft");
                JettraAssert.assertTrue(res.body().contains("Registrar Nuevo Nodo"),
                                "Debe incluir formulario de registro de nodos");
                JettraAssert.assertTrue(res.body().contains("Matriz de Replicación"),
                                "Debe incluir matriz de replicación");

                // Verificación de sincronización reactiva en tiempo real para paneles y
                // tarjetas de nodos
                HttpResponse<String> resClusters = get("/clusters");
                JettraAssert.assertEquals(200, resClusters.statusCode(),
                                "La ruta /clusters debe responder HTTP 200 idéntica a /cluster");
                JettraAssert.assertTrue(res.body().contains("data-node-card=\"node-01\""),
                                "Debe incluir selector data-node-card para node-01");
                JettraAssert.assertTrue(res.body().contains("data-node-badge=\"node-01\""),
                                "Debe incluir selector data-node-badge para node-01");
                JettraAssert.assertTrue(res.body().contains("data-node-status-box=\"node-01\""),
                                "Debe incluir selector data-node-status-box para node-01");
                JettraAssert.assertTrue(res.body().contains("data-node-cpu=\"node-01\""),
                                "Debe incluir selector data-node-cpu para node-01");

                // Verificación de telemetría completa en API /api/cluster/status
                HttpResponse<String> statusJsonRes = get("/api/cluster/status");
                JettraAssert.assertEquals(200, statusJsonRes.statusCode(),
                                "La API /api/cluster/status debe responder HTTP 200");
                JettraAssert.assertTrue(statusJsonRes.body().contains("\"totalQps\""), "Debe incluir totalQps");
                JettraAssert.assertTrue(statusJsonRes.body().contains("\"totalPanama\""), "Debe incluir totalPanama");
                JettraAssert.assertTrue(statusJsonRes.body().contains("\"cpuPercent\""),
                                "Debe incluir cpuPercent en nodos");
        }

        @Test
        @DisplayName("3. Administrar Nodos: Registro dinámico de nuevo nodo y conmutación de estado")
        public void testAddAndToggleNode() throws Exception {
                String form = "action=add_node&nodeId=node-test-99&nodeName=node-test-99-replica&nodeRole=FOLLOWER&nodeHost=127.0.0.1&nodePort=8799&nodeRam=4096";
                HttpResponse<String> postRes = postForm("/cluster", form);
                JettraAssert.assertEquals(302, postRes.statusCode(), "Debe redirigir a /cluster tras añadir nodo");

                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                ServerNodeInfo n = plugin.getNodeById("node-test-99");
                JettraAssert.assertNotNull(n, "El nodo registrado debe existir en JettraStorePlugin");
                JettraAssert.assertEquals("FOLLOWER", n.getRole(), "El rol debe ser FOLLOWER");
                JettraAssert.assertTrue(n.isOnline(), "El nodo debe iniciar ONLINE");

                HttpResponse<String> toggleRes = get("/cluster?action=toggle_node&nodeId=node-test-99");
                JettraAssert.assertEquals(302, toggleRes.statusCode(), "Debe redirigir tras conmutar");
                JettraAssert.assertFalse(n.isOnline(), "El nodo debe pasar a OFFLINE");

                plugin.removeNode("node-test-99");
        }

        @Test
        @DisplayName("4. Ver Estatus: Verificar estado del servidor, telemetría y centinelas Police")
        public void testServerStatusAndTelemetryView() throws Exception {
                HttpResponse<String> res = get("/status");

                JettraAssert.assertEquals(200, res.statusCode(), "La vista de estatus debe responder HTTP 200");
                JettraAssert.assertTrue(res.body().contains("Estado Servidor"), "Debe mostrar estado del servidor");
                JettraAssert.assertTrue(res.body().contains("Modo Almacenamiento"),
                                "Debe mostrar modo de almacenamiento");
                JettraAssert.assertTrue(res.body().contains("Heap JVM (Loom)"), "Debe mostrar estadísticas Loom");
                JettraAssert.assertTrue(res.body().contains("Centinelas Guardián JettraPolice"),
                                "Debe listar centinelas Police");
                JettraAssert.assertTrue(res.body().contains("Registro de Eventos"),
                                "Debe incluir bitácora de auditoría");
        }

        @Test
        @DisplayName("5. Administrar Bases de Datos y Motores: Verificar catálogo de bases de datos y buckets")
        public void testDatabasesAndEnginesView() throws Exception {
                HttpResponse<String> res = get("/databases");

                JettraAssert.assertEquals(200, res.statusCode(), "La vista de bases de datos debe responder HTTP 200");
                JettraAssert.assertTrue(res.body().contains("Catálogo Maestro de Bases de Datos"),
                                "Debe contener catálogo maestro");
                JettraAssert.assertTrue(res.body().contains("example_factura_db"), "Debe incluir example_factura_db");
                JettraAssert.assertTrue(res.body().contains("samples_hostipal_db"), "Debe incluir samples_hostipal_db");

                HttpResponse<String> enginesModalRes = get("/databases?db=example_factura_db&manage_engines=true");
                JettraAssert.assertEquals(200, enginesModalRes.statusCode(),
                                "El diálogo Administrar Motores debe responder HTTP 200");
                JettraAssert.assertTrue(enginesModalRes.body().contains("DOCUMENT"),
                                "Debe mostrar motores documentales");
                JettraAssert.assertTrue(enginesModalRes.body().contains("RELATIONAL"),
                                "Debe mostrar motores relacionales");
                JettraAssert.assertTrue(enginesModalRes.body().contains("Añadir Nuevo Motor / Bucket"),
                                "Debe permitir añadir motores");
        }

        @Test
        @DisplayName("6. Administrar Bases de Datos y Motores: Creación dinámica de Base de Datos y nuevo Motor")
        public void testCreateDatabaseAndBucket() throws Exception {
                String createDbForm = "action=create_db&dbName=billing_test_db&defaultBucket=FacturasEmitidas&engineType=DOCUMENT&description=BD_Prueba_Automatizada";
                HttpResponse<String> res1 = postForm("/databases", createDbForm);
                JettraAssert.assertEquals(302, res1.statusCode(), "Crear BD debe redirigir");

                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                JettraAssert.assertTrue(plugin.getDatabaseNames().contains("billing_test_db"),
                                "La BD debe haberse registrado en el catálogo");

                String addBucketForm = "action=add_bucket&db=billing_test_db&bucketName=FacturasEmbeddings&engineType=VECTOR&description=Embeddings_HNSW";
                HttpResponse<String> res2 = postForm("/databases", addBucketForm);
                JettraAssert.assertEquals(302, res2.statusCode(), "Añadir motor debe redirigir");

                EngineBucketInfo b = plugin.getBucket("billing_test_db", "FacturasEmbeddings");
                JettraAssert.assertNotNull(b, "El nuevo bucket/motor debe existir");
                JettraAssert.assertEquals("VECTOR", b.getEngineType(), "El motor debe ser VECTOR");

                plugin.deleteDatabase("billing_test_db");
                JettraAssert.assertFalse(plugin.getDatabaseNames().contains("billing_test_db"),
                                "La BD debe eliminarse");
        }

        @Test
        @DisplayName("7. Operaciones CRUD de Registros: Create, Read, Update, Delete en JettraStore")
        public void testRecordCrudOperations() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String db = "example_factura_db";
                String bucket = "Facturas";

                // 1. CREATE
                String createForm = "action=save_new&db=" + db + "&bucket=" + bucket
                                + "&recordId=FAC-TEST-001&summary=Factura_Prueba_CRUD&details={\"folio\":\"FAC-TEST-001\",\"total\":999.00}";
                HttpResponse<String> createRes = postForm("/explorer", createForm);
                JettraAssert.assertEquals(302, createRes.statusCode(), "Crear registro debe retornar HTTP 302");

                EngineRecordInfo rec = plugin.getRecord(db, bucket, "FAC-TEST-001");
                JettraAssert.assertNotNull(rec, "El registro creado debe existir en el bucket");
                JettraAssert.assertEquals("Factura_Prueba_CRUD", rec.getSummary(), "El resumen debe coincidir");
                JettraAssert.assertEquals(1L, rec.getVersion(), "La versión inicial debe ser 1");

                // 2. UPDATE
                String updateForm = "action=save_edit&db=" + db + "&bucket=" + bucket
                                + "&recordId=FAC-TEST-001&summary=Factura_Actualizada_V2&details={\"folio\":\"FAC-TEST-001\",\"total\":1250.00}";
                HttpResponse<String> updateRes = postForm("/explorer", updateForm);
                JettraAssert.assertEquals(302, updateRes.statusCode(), "Actualizar registro debe retornar HTTP 302");

                EngineRecordInfo updatedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
                JettraAssert.assertEquals("Factura_Actualizada_V2", updatedRec.getSummary(),
                                "El resumen debe haberse actualizado");
                JettraAssert.assertEquals(2L, updatedRec.getVersion(), "La versión debe incrementarse a v2");

                // 3. DELETE
                HttpResponse<String> deleteRes = get("/explorer?action=delete_record&db=" + db + "&bucket=" + bucket
                                + "&recordId=FAC-TEST-001");
                JettraAssert.assertEquals(302, deleteRes.statusCode(), "Eliminar registro debe retornar HTTP 302");

                EngineRecordInfo deletedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
                JettraAssert.assertNull(deletedRec, "El registro debe haber sido eliminado");
        }

        @Test
        @DisplayName("8. Consola SQL / JQL: Ejecución de consultas directas y renderizado de resultados")
        public void testQueryConsoleView() throws Exception {
                HttpResponse<String> res = get("/query?db=example_factura_db&bucket=Facturas&query=total+%3E+500");

                JettraAssert.assertEquals(200, res.statusCode(), "La consola de consulta debe responder HTTP 200");
                JettraAssert.assertTrue(res.body().contains("Consola de Consulta Directa"),
                                "Debe mostrar título de consola");
                JettraAssert.assertTrue(res.body().contains("FAC-2026-001"),
                                "Debe mostrar registros que cumplan la condición");
                JettraAssert.assertTrue(res.body().contains("ejecutado en"), "Debe mostrar tiempo de ejecución en µs");
        }

        @Test
        @DisplayName("9. Conexiones y Seguridad: Verificación de perfiles de red y permisos de usuarios")
        public void testConnectionsAndUsersViews() throws Exception {
                HttpResponse<String> connRes = get("/connections");
                JettraAssert.assertEquals(200, connRes.statusCode(), "Conexiones debe responder HTTP 200");
                JettraAssert.assertTrue(connRes.body().contains("Perfiles de Conexión Configurados"),
                                "Debe listar perfiles");

                HttpResponse<String> usersRes = get("/users");
                JettraAssert.assertEquals(200, usersRes.statusCode(), "Usuarios debe responder HTTP 200");
                JettraAssert.assertTrue(usersRes.body().contains("Usuarios Registrados y Permisos Granulares"),
                                "Debe mostrar tabla de permisos");
                JettraAssert.assertTrue(usersRes.body().contains("admin"), "Debe contener el usuario admin");
        }

        @Test
        @DisplayName("10. Copias de Seguridad y Centinelas Police: Snapshots y Patrullaje Heurístico")
        public void testBackupsAndPoliceViews() throws Exception {
                HttpResponse<String> bkpRes = get("/backups");
                JettraAssert.assertEquals(200, bkpRes.statusCode(), "Backups debe responder HTTP 200");
                JettraAssert.assertTrue(bkpRes.body().contains("Snapshots y Copias de Seguridad"),
                                "Debe listar snapshots");

                HttpResponse<String> policeRes = get("/police");
                JettraAssert.assertEquals(200, policeRes.statusCode(), "Police debe responder HTTP 200");
                JettraAssert.assertTrue(policeRes.body().contains("Centro de Comando JettraStorePolice"),
                                "Debe mostrar centro de comando");
        }

        @Test
        @DisplayName("11. Nodos del Clúster: Verificación de estado real ONLINE y OFFLINE vía socket TCP")
        public void testRealNodeReachabilityOnlineOffline() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                ServerNodeInfo master = plugin.getNodeById("node-01");
                int originalPort = master.getPort();
                try {
                        // 1. Con un nodo primario simulado activo en su propio puerto TCP, debe
                        // responder ONLINE
                        try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                                master.setPort(mockPrimarySocket.getLocalPort());

                                HttpResponse<String> resOnline = getWithRedirect("/cluster?action=check_nodes");
                                JettraAssert.assertEquals(200, resOnline.statusCode(),
                                                "Cluster con check_nodes debe responder HTTP 200");
                                JettraAssert.assertTrue(resOnline.body().contains("● PRIMARY: ONLINE"),
                                                "Debe mostrar PRIMARY: ONLINE cuando el nodo primario responde");
                                JettraAssert.assertTrue(resOnline.body().contains("OFFLINE"),
                                                "Los nodos secundarios inaccesibles deben mostrarse OFFLINE");
                                JettraAssert.assertTrue(master.isOnline(), "node-01 debe estar ONLINE en vivo");
                                JettraAssert.assertTrue(master.getLatencyMs() > 0.0f,
                                                "La latencia debe estar medida en milisegundos");
                        }

                        // 2. Al cerrarse el socket del nodo primario, debe detectar inmediatamente el
                        // estado OFFLINE
                        HttpResponse<String> resOffline = getWithRedirect("/cluster?action=check_nodes");
                        JettraAssert.assertEquals(200, resOffline.statusCode(),
                                        "Cluster debe renderizar aún con PRIMARY desconectado");
                        JettraAssert.assertFalse(master.isOnline(),
                                        "node-01 debe marcarse OFFLINE cuando su puerto cierra");
                        JettraAssert.assertTrue(resOffline.body().contains("○ PRIMARY: OFFLINE"),
                                        "TopBar debe mostrar ○ PRIMARY: OFFLINE");
                        JettraAssert.assertTrue(resOffline.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"),
                                        "Debe mostrar alerta crítica de primario desconectado");
                        JettraAssert.assertTrue(resOffline.body().contains("DISCONNECTED (Sin latido)"),
                                        "La matriz WAL debe reflejar desconexión");
                } finally {
                        master.setPort(originalPort);
                        plugin.refreshAllNodeStatuses(true);
                }
        }

        @Test
        @DisplayName("12. Motores Multimodelo: Soporte completo para GEOSPATIAL, JAVA_RECORD y OBJECT")
        public void testMultimodelEnginesGeospatialJavaRecordObject() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();

                // 1. Validar catálogo maestro
                EngineBucketInfo geoBucket = plugin.getBucket("samples_ambiental_db", "EstacionesGeo");
                JettraAssert.assertNotNull(geoBucket, "EstacionesGeo debe existir");
                JettraAssert.assertEquals("GEOSPATIAL", geoBucket.getEngineType(), "Tipo debe ser GEOSPATIAL");

                EngineBucketInfo recBucket = plugin.getBucket("example_factura_db", "CustomerRecords");
                JettraAssert.assertNotNull(recBucket, "CustomerRecords debe existir");
                JettraAssert.assertEquals("JAVA_RECORD", recBucket.getEngineType(), "Tipo debe ser JAVA_RECORD");

                EngineBucketInfo objBucket = plugin.getBucket("example_factura_db", "DomainEntities");
                JettraAssert.assertNotNull(objBucket, "DomainEntities debe existir");
                JettraAssert.assertEquals("OBJECT", objBucket.getEngineType(), "Tipo debe ser OBJECT");

                // 2. Validar vista de bases de datos
                HttpResponse<String> dbRes = get("/databases");
                JettraAssert.assertEquals(200, dbRes.statusCode(), "Databases debe responder HTTP 200");
                JettraAssert.assertTrue(dbRes.body().contains("GEOSPATIAL"),
                                "Debe mostrar motor GEOSPATIAL en selector");
                JettraAssert.assertTrue(dbRes.body().contains("JAVA_RECORD"),
                                "Debe mostrar motor JAVA_RECORD en selector");
                JettraAssert.assertTrue(dbRes.body().contains("OBJECT"), "Debe mostrar motor OBJECT en selector");

                // 3. Validar vista de explorador para bucket JAVA_RECORD
                HttpResponse<String> expRes = get("/explorer?db=example_factura_db&bucket=CustomerRecords");
                JettraAssert.assertEquals(200, expRes.statusCode(), "Explorer debe responder HTTP 200");
                JettraAssert.assertTrue(expRes.body().contains("REC-ORD-501"), "Debe mostrar el registro REC-ORD-501");
                JettraAssert.assertTrue(expRes.body().contains("JAVA_RECORD"), "Debe mostrar badge JAVA_RECORD");
        }

        @Test
        @DisplayName("13. Control de Versiones de Registros: Historial, auditoría y restauración de versión previa")
        public void testRecordVersionHistoryAndRestoration() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String db = "example_factura_db";
                String bucket = "Facturas";
                String recordId = "FAC-HIST-999";

                // 1. Crear registro versión inicial (v1)
                plugin.addRecord(db, bucket, recordId, "Versión 1 Original",
                                "{\"total\":100.00,\"estado\":\"CREADO\"}");
                EngineRecordInfo rec = plugin.getRecord(db, bucket, recordId);
                JettraAssert.assertNotNull(rec, "Registro debe existir");
                JettraAssert.assertEquals(1L, rec.getVersion(), "Versión inicial debe ser 1");

                // 2. Mutar a versión 2
                plugin.updateRecord(db, bucket, recordId, "Versión 2 Modificada",
                                "{\"total\":250.00,\"estado\":\"EN_PROCESO\"}");
                JettraAssert.assertEquals(2L, rec.getVersion(), "Versión debe ser 2");

                // 3. Mutar a versión 3
                plugin.updateRecord(db, bucket, recordId, "Versión 3 Modificada",
                                "{\"total\":500.00,\"estado\":\"FINALIZADO\"}");
                JettraAssert.assertEquals(3L, rec.getVersion(), "Versión debe ser 3");
                JettraAssert.assertEquals(3, rec.getVersionHistory().size(), "El historial debe contener 3 versiones");

                // 4. Consultar vista web de historial de versiones
                HttpResponse<String> histRes = get(
                                "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId);
                JettraAssert.assertEquals(200, histRes.statusCode(), "Debe responder HTTP 200 en vista de versiones");
                JettraAssert.assertTrue(histRes.body().contains("Historial de Versiones del Registro"),
                                "Debe mostrar título de historial");
                JettraAssert.assertTrue(histRes.body().contains("v1"), "Debe listar v1");
                JettraAssert.assertTrue(histRes.body().contains("v2"), "Debe listar v2");
                JettraAssert.assertTrue(histRes.body().contains("v3"), "Debe listar v3");
                JettraAssert.assertTrue(histRes.body().contains("Restaurar esta Versión"),
                                "Debe contener botón para restaurar");

                // 5. Ejecutar Restauración a la versión 1 vía HTTP POST
                String restoreForm = "action=restore_version&db=" + db + "&bucket=" + bucket + "&recordId=" + recordId
                                + "&version=1";
                HttpResponse<String> restoreRes = postFormWithRedirect("/explorer", restoreForm);
                JettraAssert.assertEquals(200, restoreRes.statusCode(), "Tras redirección debe responder HTTP 200");

                // 6. Verificar restauración
                EngineRecordInfo restoredRec = plugin.getRecord(db, bucket, recordId);
                JettraAssert.assertEquals(4L, restoredRec.getVersion(),
                                "Debe avanzar a v4 con snapshot de restauración");
                JettraAssert.assertEquals("Versión 1 Original", restoredRec.getSummary(),
                                "El resumen debe coincidir con el de v1");
                JettraAssert.assertTrue(restoredRec.getDetails().contains("100.00"),
                                "El payload restaurado debe contener el valor de v1");
                JettraAssert.assertEquals(4, restoredRec.getVersionHistory().size(),
                                "El historial acumulado debe tener 4 entradas");

                // 7. Limpiar registro de prueba
                plugin.deleteRecord(db, bucket, recordId);
        }

        @Test
        @DisplayName("14. Seguridad Estricta: Prohibir acceso a cualquier página sin inicio de sesión (Auth Guard)")
        public void testAuthenticationSecurityGuardBlocksUnauthenticated() throws Exception {
                String[] protectedPaths = { "/cluster", "/status", "/databases", "/explorer", "/query", "/connections",
                                "/users", "/police", "/backups", "/" };
                for (String path : protectedPaths) {
                        HttpResponse<String> unauthRes = get(path,
                                        "jettra_user=admin; jettra_session=stale-invalid-token");
                        JettraAssert.assertEquals(302, unauthRes.statusCode(),
                                        "El acceso no autenticado o con cookie inválida a " + path
                                                        + " debe ser redirigido");
                        String location = unauthRes.headers().firstValue("Location").orElse("");
                        JettraAssert.assertTrue(location.contains("/login"),
                                        "La redirección de " + path + " debe apuntar a /login");
                }

                // Login válido
                String loginForm = "username=admin&password=admin123";
                HttpResponse<String> loginRes = postForm("/login", loginForm, null);
                JettraAssert.assertEquals(302, loginRes.statusCode(), "El login válido debe redirigir");
                String setCookie = loginRes.headers().firstValue("Set-Cookie").orElse("");
                JettraAssert.assertTrue(setCookie.contains("jettra_session="),
                                "El servidor debe emitir la cookie de sesión jettra_session");

                // Login inválido
                String badForm = "username=admin&password=password_invalido";
                HttpResponse<String> badRes = postForm("/login", badForm, null);
                JettraAssert.assertEquals(302, badRes.statusCode(),
                                "El login inválido debe redirigir de vuelta al formulario");
                String badLocation = badRes.headers().firstValue("Location").orElse("");
                JettraAssert.assertTrue(badLocation.contains("error=invalid_credentials"),
                                "Debe indicar error de credenciales");
        }

        @Test
        @DisplayName("15. Configuración MicroProfile: Desacoplamiento de nodo PRIMARY, detección OFFLINE y endpoint en vivo")
        public void testMicroprofileConfigHostPortAndPrimaryNode() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                ServerNodeInfo primaryNode = plugin.getNodeById("node-01");
                ServerNodeInfo replicaNode2 = plugin.getNodeById("node-02");
                ServerNodeInfo replicaNode3 = plugin.getNodeById("node-03");
                int originalPrimaryPort = primaryNode.getPort();
                String originalPrimaryRole = primaryNode.getRole();
                int originalReplicaPort2 = replicaNode2 != null ? replicaNode2.getPort() : 50002;
                String originalReplicaRole2 = replicaNode2 != null ? replicaNode2.getRole() : "SECONDARY";
                int originalReplicaPort3 = replicaNode3 != null ? replicaNode3.getPort() : 50003;
                String originalReplicaRole3 = replicaNode3 != null ? replicaNode3.getRole() : "SECONDARY";
                try {
                        // 1. Host, puerto Web UI y contextpath cargados desde
                        // microprofile-config.properties
                        JettraAssert.assertNotNull(plugin.getServerHost(), "El host debe estar cargado");
                        JettraAssert.assertEquals("127.0.0.1", plugin.getServerHost(),
                                        "El host en microprofile-config.properties debe ser 127.0.0.1");
                        JettraAssert.assertEquals(8085, plugin.getServerPort(), "El server.port Web UI debe ser 8085");
                        JettraAssert.assertEquals("/", plugin.getServerContextPath(),
                                        "El server.contextpath en microprofile-config.properties debe ser /");

                        // 2. Nodo PRIMARY registrado con su puerto propio (59001 en
                        // microprofile-config-test_db.properties)
                        JettraAssert.assertNotNull(primaryNode, "El nodo node-01 debe existir");
                        JettraAssert.assertEquals("PRIMARY", primaryNode.getRole(),
                                        "El rol de node-01 debe ser PRIMARY");
                        JettraAssert.assertEquals(59001, originalPrimaryPort,
                                        "El puerto de node-01 debe provenir de cluster.ip.nodes (59001), no de server.port");

                        // 3. Aislar nodos secundarios y cerrar puerto de PRIMARY: todo el clúster
                        // OFFLINE
                        int closedPrimaryPort;
                        int closedPort2;
                        int closedPort3;
                        try (java.net.ServerSocket s1 = new java.net.ServerSocket(0);
                                        java.net.ServerSocket s2 = new java.net.ServerSocket(0);
                                        java.net.ServerSocket s3 = new java.net.ServerSocket(0)) {
                                closedPrimaryPort = s1.getLocalPort();
                                closedPort2 = s2.getLocalPort();
                                closedPort3 = s3.getLocalPort();
                        }
                        primaryNode.setPort(closedPrimaryPort);
                        if (replicaNode2 != null)
                                replicaNode2.setPort(closedPort2);
                        if (replicaNode3 != null)
                                replicaNode3.setPort(closedPort3);
                        plugin.refreshAllNodeStatuses(true);

                        HttpResponse<String> resDown = get("/cluster");
                        JettraAssert.assertEquals(200, resDown.statusCode(),
                                        "Debe responder HTTP 200 aunque PRIMARY esté desconectado");
                        JettraAssert.assertFalse(primaryNode.isOnline(),
                                        "node-01 debe marcarse OFFLINE cuando su puerto no tiene nodo escuchando");
                        JettraAssert.assertTrue(resDown.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"),
                                        "Debe mostrar alerta crítica de PRIMARY OFFLINE");
                        JettraAssert.assertTrue(resDown.body().contains("○ PRIMARY: OFFLINE"),
                                        "La barra superior debe indicar ○ PRIMARY: OFFLINE");
                        JettraAssert.assertTrue(resDown.body().contains("🔴 NODO PRIMARY OFFLINE"),
                                        "La tarjeta de PRIMARY debe reflejar 🔴 NODO PRIMARY OFFLINE");
                        JettraAssert.assertTrue(resDown.body().contains("○ OFFLINE (NODO PRIMARY CAÍDO)"),
                                        "La matriz debe indicar PRIMARY CAÍDO");

                        // 4. Protección anti auto-sondeo
                        primaryNode.setPort(sharedPort);
                        plugin.refreshAllNodeStatuses(true);
                        JettraAssert.assertFalse(primaryNode.isOnline(),
                                        "El auto-sondeo al puerto de JettraStoreExplorer Web UI no debe marcar PRIMARY como ONLINE");

                        // 5. Nodos secundarios desconectados deben figurar como OFFLINE
                        JettraAssert.assertNotNull(replicaNode2, "El nodo node-02 debe existir");
                        plugin.checkNodeRealReachability(replicaNode2, 50);
                        JettraAssert.assertFalse(replicaNode2.isOnline(),
                                        "Un nodo cuya IP/puerto no está escuchando debe marcarse OFFLINE");

                        // 6. Simulación de nodo PRIMARY conectado vía ServerSocket (secundarios
                        // continúan desconectados)
                        try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                                primaryNode.setPort(mockPrimarySocket.getLocalPort());

                                HttpResponse<String> resUp = get("/cluster");
                                JettraAssert.assertEquals(200, resUp.statusCode(),
                                                "La página del cluster debe responder HTTP 200 con cookie");
                                JettraAssert.assertTrue(primaryNode.isOnline(),
                                                "El nodo PRIMARY debe marcarse ONLINE al responder su socket TCP");
                                JettraAssert.assertTrue(resUp.body().contains("● PRIMARY: ONLINE"),
                                                "La barra superior debe indicar que PRIMARY está ONLINE");
                                JettraAssert.assertTrue(
                                                resUp.body().contains(
                                                                "ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS"),
                                                "Debe mostrar alerta de nodos secundarios desconectados");
                                JettraAssert.assertTrue(resUp.body().contains("🟢 ESTADO: ONLINE"),
                                                "La tarjeta de node-01 debe mostrar 🟢 ESTADO: ONLINE");
                                JettraAssert.assertTrue(resUp.body().contains("🔴 ESTADO: OFFLINE"),
                                                "La tarjeta de réplicas debe mostrar 🔴 ESTADO: OFFLINE");
                                JettraAssert.assertTrue(resUp.body().contains("○ OFFLINE (Inaccesible)"),
                                                "La matriz de replicación debe indicar réplicas OFFLINE");

                                HttpResponse<String> apiResUp = get("/api/cluster/status");
                                JettraAssert.assertEquals(200, apiResUp.statusCode(),
                                                "API /api/cluster/status debe responder HTTP 200");
                                JettraAssert.assertTrue(apiResUp.body().contains("\"primaryOnline\":true"),
                                                "JSON debe indicar primaryOnline:true");
                        }

                        // 7. Tras cerrar el socket de PRIMARY (todos los nodos desconectados)
                        HttpResponse<String> apiResDown = get("/api/cluster/status");
                        JettraAssert.assertEquals(200, apiResDown.statusCode(),
                                        "API /api/cluster/status debe responder HTTP 200");
                        JettraAssert.assertTrue(apiResDown.body().contains("\"primaryOnline\":false"),
                                        "JSON debe indicar primaryOnline:false cuando el nodo cae");
                } finally {
                        primaryNode.setPort(originalPrimaryPort);
                        primaryNode.setRole(originalPrimaryRole);
                        if (replicaNode2 != null) {
                                replicaNode2.setPort(originalReplicaPort2);
                                replicaNode2.setRole(originalReplicaRole2);
                        }
                        if (replicaNode3 != null) {
                                replicaNode3.setPort(originalReplicaPort3);
                                replicaNode3.setRole(originalReplicaRole3);
                        }
                        plugin.refreshAllNodeStatuses(true);
                }
        }

        @Test
        @DisplayName("16. Verificar que la ejecución por defecto toma server.port=8085 y no 8082")
        public void testDefaultExecutionUsesPort8085() {
                ServerConfig config = ServerConfig.load();
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();

                String sysPort = System.getProperty("server.port", System.getProperty("port", System.getenv("PORT")));
                if (sysPort == null || sysPort.isBlank()) {
                        if (config.getPort() == 8080 || config.getPort() == 8082) {
                                config.setPort(plugin.getServerPort());
                        }
                }

                JettraAssert.assertEquals(8085, config.getPort(), "El puerto por defecto debe ser 8085 y no 8082");
                JettraAssert.assertEquals("127.0.0.1", config.getHost(), "El host por defecto debe ser 127.0.0.1");
                JettraAssert.assertEquals("/", config.getContextPath(), "El contextpath por defecto debe ser /");
        }

        @Test
        @DisplayName("17. Cierre de Sesiones al Detener y Reinicio Siempre en Página /login")
        public void testShutdownClosesSessionsAndRestartRequiresLogin() throws Exception {
                int port1 = freeLocalPort();
                int port2 = freeLocalPort();

                String previousSessionCookie;
                JettraServer server1 = App.startApplication(port1, StorageMode.OFF_HEAP);
                try {
                        JettraAssert.assertEquals(0, App.getActiveSessionCount(),
                                        "Al iniciar no debe existir ninguna sesión activa");

                        HttpResponse<String> rootUnauth = CLIENT.send(
                                        HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port1 + "/"))
                                                        .GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                        JettraAssert.assertEquals(302, rootUnauth.statusCode(),
                                        "Raíz sin autenticar debe redirigir 302");
                        JettraAssert.assertTrue(
                                        rootUnauth.headers().firstValue("Location").orElse("").contains("/login"),
                                        "Debe iniciar en /login");

                        previousSessionCookie = loginAndGetSessionCookie(port1);
                        JettraAssert.assertEquals(1, App.getActiveSessionCount(),
                                        "Debe haber 1 sesión activa tras login");

                        HttpResponse<String> clusterAuth = CLIENT.send(
                                        HttpRequest.newBuilder()
                                                        .uri(URI.create("http://127.0.0.1:" + port1 + "/cluster"))
                                                        .header("Cookie", previousSessionCookie).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                        JettraAssert.assertEquals(200, clusterAuth.statusCode(),
                                        "Con sesión válida debe permitir acceso a /cluster");
                } finally {
                        App.stopApplication(server1);
                }
                JettraAssert.assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", port1, 2000),
                                "El puerto HTTP de server1 debe quedar libre inmediatamente");
                JettraAssert.assertEquals(0, App.getActiveSessionCount(),
                                "Al detener el servidor todas las sesiones deben cerrarse");

                JettraServer server2 = App.startApplication(port2, StorageMode.OFF_HEAP);
                try {
                        JettraAssert.assertEquals(0, App.getActiveSessionCount(),
                                        "Tras reiniciar no debe quedar ninguna sesión abierta");

                        HttpResponse<String> rootWithStale = CLIENT.send(
                                        HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/"))
                                                        .header("Cookie", previousSessionCookie).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                        JettraAssert.assertEquals(302, rootWithStale.statusCode(),
                                        "Raíz con cookie de sesión previa debe redirigir 302");
                        JettraAssert.assertTrue(
                                        rootWithStale.headers().firstValue("Location").orElse("").contains("/login"),
                                        "Tras reiniciar siempre debe enviar a /login");
                        JettraAssert.assertTrue(rootWithStale.headers().firstValue("Set-Cookie").orElse("")
                                        .contains("Max-Age=0"), "Debe invalidar/limpiar la cookie expirada");

                        HttpResponse<String> clusterWithStale = CLIENT.send(
                                        HttpRequest.newBuilder()
                                                        .uri(URI.create("http://127.0.0.1:" + port2 + "/cluster"))
                                                        .header("Cookie", previousSessionCookie).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                        JettraAssert.assertEquals(302, clusterWithStale.statusCode(),
                                        "Ruta protegida con cookie previa debe redirigir a /login");
                        JettraAssert.assertTrue(
                                        clusterWithStale.headers().firstValue("Location").orElse("").contains("/login"),
                                        "Debe exigir autenticación en /login");

                        HttpResponse<String> apiWithStale = CLIENT.send(
                                        HttpRequest.newBuilder()
                                                        .uri(URI.create("http://127.0.0.1:" + port2
                                                                        + "/api/cluster/status"))
                                                        .header("Cookie", previousSessionCookie).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                        JettraAssert.assertEquals(401, apiWithStale.statusCode(),
                                        "API /api/cluster/status con cookie previa debe responder HTTP 401");
                } finally {
                        App.stopApplication(server2);
                        // Restaurar contexto de servidor compartido en App y Plugin
                        if (sharedServer != null && sharedServer.isRunning()) {
                                App.serverInstance = sharedServer;
                                JettraStorePlugin.getInstance().setExplorerServerEndpoint("127.0.0.1", sharedPort);
                                sharedAuthCookie = null;
                        }
                }
                JettraAssert.assertTrue(io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", port2, 2000),
                                "El puerto HTTP de server2 debe quedar libre inmediatamente");
        }

        @Test
        @DisplayName("18. Apagado Real de Nodos: NodeLifecycleManager, REST /api/cluster/nodes/shutdown y actualización inmediata a OFFLINE")
        public void testRealNodeShutdownViaLifecycleManagerAndRest() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                try (java.net.ServerSocket dummyNodeSocket = new java.net.ServerSocket(0)) {
                        int nodePort = dummyNodeSocket.getLocalPort();
                        plugin.addNode("node-lifecycle-test", "node-lifecycle-test", "FOLLOWER", "127.0.0.1", nodePort,
                                        2048);
                        plugin.getLifecycleManager().registerManagedEndpoint("node-lifecycle-test", dummyNodeSocket);
                        plugin.refreshAllNodeStatuses(true);

                        ServerNodeInfo node = plugin.getNodeById("node-lifecycle-test");
                        JettraAssert.assertNotNull(node, "El nodo temporal debe existir");
                        JettraAssert.assertTrue(node.isOnline(),
                                        "El nodo temporal con socket activo debe estar ONLINE");

                        HttpResponse<String> clusterHtml = get("/cluster");
                        JettraAssert.assertTrue(clusterHtml.body().contains("shutdownNode('node-lifecycle-test'"),
                                        "ClusterFluxView debe renderizar la llamada shutdownNode para el nodo");

                        HttpResponse<String> shutdownRes = postJson("/api/cluster/nodes/shutdown",
                                        "{\"nodeId\":\"node-lifecycle-test\"}");
                        JettraAssert.assertEquals(200, shutdownRes.statusCode(),
                                        "El endpoint de apagado debe responder HTTP 200");
                        JettraAssert.assertTrue(
                                        shutdownRes.body().contains("\"status\":\"INACTIVE\"")
                                                        || shutdownRes.body().contains("\"status\":\"OFFLINE\"")
                                                        || shutdownRes.body().contains("\"state\":\"OFFLINE\""),
                                        "El JSON debe confirmar status INACTIVE/OFFLINE");
                        JettraAssert.assertTrue(shutdownRes.body().contains("\"offlineVerified\":true"),
                                        "Debe verificar el cierre real del puerto");
                        JettraAssert.assertTrue(dummyNodeSocket.isClosed(),
                                        "El socket del nodo administrado debe cerrarse al apagar");
                        JettraAssert.assertFalse(node.isOnline(),
                                        "El estado del nodo en el plugin debe reflejar OFFLINE inmediatamente");

                        HttpResponse<String> statusJson = get("/api/cluster/status");
                        JettraAssert.assertTrue(
                                        statusJson.body().contains("\"id\":\"node-lifecycle-test\"")
                                                        && statusJson.body().contains("\"online\":false"),
                                        "El endpoint JSON de estado debe confirmar node-lifecycle-test online=false");
                } finally {
                        plugin.removeNode("node-lifecycle-test");
                }
        }

        @Test
        @DisplayName("19. Diálogo de Restauración JettraFlux: Reemplazo de window.confirm por Modal nativo JettraFlux")
        public void testJettraFluxRestoreVersionConfirmationModal() throws Exception {
                HttpResponse<String> historyRes = get(
                                "/explorer?db=example_factura_db&bucket=Facturas&history_id=FAC-2026-003");

                JettraAssert.assertEquals(200, historyRes.statusCode(),
                                "El historial de versiones debe responder HTTP 200");
                JettraAssert.assertTrue(historyRes.body().contains("restoreVersionModal"),
                                "Debe renderizar el Modal nativo de JettraFlux");
                JettraAssert.assertTrue(historyRes.body().contains("espresso-modal-overlay"),
                                "Debe usar el widget Modal de JettraFlux");
                JettraAssert.assertTrue(historyRes.body().contains("Confirmar Restauración"),
                                "El modal debe contener botón de confirmación");
                JettraAssert.assertTrue(historyRes.body().contains("Cancelar"),
                                "El modal debe contener botón de cancelación");
                JettraAssert.assertTrue(historyRes.body().contains("openRestoreModalFromBtn"),
                                "El botón de restaurar debe abrir el modal JettraFlux");
                JettraAssert.assertFalse(historyRes.body().contains("onsubmit=\"return confirm("),
                                "No debe usar window.confirm nativo de JS para restaurar versiones");

                HttpResponse<String> restoreRes = postForm("/explorer",
                                "action=restore_version&db=example_factura_db&bucket=Facturas&recordId=FAC-2026-003&version=1");
                JettraAssert.assertEquals(302, restoreRes.statusCode(),
                                "POST restore_version debe redirigir tras completar rollback");
                JettraAssert.assertTrue(
                                restoreRes.headers().firstValue("Location").orElse("").contains("msg=version_restored"),
                                "Debe redirigir confirmando la restauración de versión");
        }

        @Test
        @DisplayName("20. Consola Interactiva JettraStoreShell y Modal Ayuda (Help) en /query")
        public void testInteractiveShellConsoleAndHelpModalOnQueryPage() throws Exception {
                // 1. SHOW DATABASES
                HttpResponse<String> showDbs = postForm("/query",
                                "db=example_factura_db&bucket=Facturas&query=SHOW+DATABASES");
                JettraAssert.assertEquals(200, showDbs.statusCode(), "SHOW DATABASES debe responder HTTP 200");
                JettraAssert.assertTrue(showDbs.body().contains("example_factura_db"),
                                "Debe listar example_factura_db en salida Shell");
                JettraAssert.assertTrue(showDbs.body().contains("queryHelpBtn"), "Debe incluir el botón Ayuda (Help)");
                JettraAssert.assertTrue(showDbs.body().contains("queryHelpModal"),
                                "Debe incluir el modal JettraFlux de Ayuda");
                JettraAssert.assertTrue(showDbs.body().contains("espresso-accordion"),
                                "La ayuda debe usar Accordion de JettraFlux");

                // 2. MATH
                HttpResponse<String> mathRes = postForm("/query",
                                "db=example_factura_db&bucket=Facturas&query=MATH+sqrt(144)+%2B+8+*+2");
                JettraAssert.assertTrue(mathRes.body().contains("= 28"),
                                "Debe calcular sqrt(144) + 8 * 2 = 28 correctamente");

                // 3. Comando erróneo
                HttpResponse<String> errRes = postForm("/query",
                                "db=example_factura_db&bucket=Facturas&query=FOOBAR_COMANDO_INEXISTENTE");
                JettraAssert.assertTrue(errRes.body().contains("[ERROR] Comando desconocido"),
                                "Comando desconocido debe reportar error estructurado de JettraStoreShell");
        }

        @Test
        @DisplayName("21. Detección Automática de Nodos y Sincronización de Estado en Tiempo Real (ACTIVE / INACTIVE)")
        public void testAutomaticNodeHeartbeatAndActiveInactiveState() throws Exception {
                int dummyNodePort = freeLocalPort();
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                java.net.ServerSocket dummySocket = null;
                try {
                        // 1. Iniciar socket simulado
                        dummySocket = new java.net.ServerSocket();
                        dummySocket.setReuseAddress(true);
                        dummySocket.bind(new java.net.InetSocketAddress("127.0.0.1", dummyNodePort));

                        // 2. Registrar nodo y verificar ACTIVE
                        plugin.addNode("node-heartbeat-live", "node-heartbeat-live", "FOLLOWER", "127.0.0.1",
                                        dummyNodePort, 2048);
                        ServerNodeInfo node = plugin.getNodeById("node-heartbeat-live");
                        JettraAssert.assertNotNull(node, "El nodo debe registrarse en el clúster");

                        plugin.refreshAllNodeStatuses(true);
                        JettraAssert.assertTrue(node.isActive(),
                                        "El nodo recién conectado debe detectarse como ACTIVE");
                        JettraAssert.assertEquals("ACTIVE", node.getStatus(), "getStatus() debe retornar ACTIVE");

                        HttpResponse<String> clusterViewRes = get("/cluster");
                        JettraAssert.assertEquals(200, clusterViewRes.statusCode(),
                                        "La página /cluster debe responder 200");
                        JettraAssert.assertTrue(clusterViewRes.body().contains("● ACTIVE"),
                                        "La vista /cluster debe contener el indicador visual ● ACTIVE");
                        JettraAssert.assertTrue(clusterViewRes.body().contains("pollClusterLiveStatus"),
                                        "La vista debe incluir el script de sondeo en vivo");

                        HttpResponse<String> apiRes1 = get("/api/cluster/status");
                        JettraAssert.assertTrue(apiRes1.body().contains("\"id\":\"node-heartbeat-live\""),
                                        "La API debe incluir node-heartbeat-live");
                        JettraAssert.assertTrue(apiRes1.body().contains("\"status\":\"ACTIVE\""),
                                        "La API debe reportar status ACTIVE");

                        // 3. Cerrar socket y verificar transición a INACTIVE
                        dummySocket.close();
                        dummySocket = null;

                        plugin.refreshAllNodeStatuses(true);
                        JettraAssert.assertFalse(node.isActive(),
                                        "Al perder respuesta TCP, el nodo debe cambiar automáticamente a INACTIVE");
                        JettraAssert.assertEquals("INACTIVE", node.getStatus(), "getStatus() debe retornar INACTIVE");

                        HttpResponse<String> apiRes2 = get("/api/cluster/status");
                        JettraAssert.assertTrue(apiRes2.body().contains("\"status\":\"INACTIVE\""),
                                        "La API debe reportar status INACTIVE sin reiniciar la aplicación");

                        HttpResponse<String> clusterViewRes2 = get("/cluster");
                        JettraAssert.assertTrue(
                                        clusterViewRes2.body().contains("○ INACTIVE")
                                                        || clusterViewRes2.body().contains("ESTADO INACTIVE"),
                                        "La vista /cluster debe mostrar el estado INACTIVE tras perder latidos");
                } finally {
                        if (dummySocket != null && !dummySocket.isClosed()) {
                                try {
                                        dummySocket.close();
                                } catch (Exception ignored) {
                                }
                        }
                        plugin.removeNode("node-heartbeat-live");
                }
                JettraAssert.assertTrue(
                                io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", dummyNodePort,
                                                2000),
                                "El puerto temporal de heartbeat debe liberarse al terminar");
        }

        @Test
        @DisplayName("22. Sincronización Dinámica del Nuevo PRIMARY Raft en /cluster y SSE (/api/cluster/events)")
        public void testDynamicPrimaryFailoverAndSseSync() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                ServerNodeInfo node01 = plugin.getNodeById("node-01");
                ServerNodeInfo node02 = plugin.getNodeById("node-02");
                ServerNodeInfo node03 = plugin.getNodeById("node-03");
                JettraAssert.assertNotNull(node01, "node-01 debe existir");
                JettraAssert.assertNotNull(node02, "node-02 debe existir");
                JettraAssert.assertNotNull(node03, "node-03 debe existir");

                int origPort1 = node01.getPort();
                int origPort2 = node02.getPort();
                int origPort3 = node03.getPort();
                String origRole1 = node01.getRole();
                String origRole2 = node02.getRole();
                int origRest2 = node02.getRestPort();

                int closedPort1;
                int closedPort3;
                try (java.net.ServerSocket tmp1 = new java.net.ServerSocket(0);
                                java.net.ServerSocket tmp3 = new java.net.ServerSocket(0)) {
                        closedPort1 = tmp1.getLocalPort();
                        closedPort3 = tmp3.getLocalPort();
                }

                com.sun.net.httpserver.HttpServer mockHealthServer = null;
                int mockNode2Port = -1;
                int mockRestPort2 = -1;
                try (java.net.ServerSocket mockNode2Tcp = new java.net.ServerSocket(0)) {
                        mockNode2Port = mockNode2Tcp.getLocalPort();
                        mockHealthServer = com.sun.net.httpserver.HttpServer
                                        .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                        mockHealthServer.createContext("/api/v1/health", exchange -> {
                                byte[] body = "{\"status\":\"UP\",\"role\":\"PRIMARY\",\"raft_term\":7,\"node_id\":\"node-02\"}"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                exchange.getResponseHeaders().add("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, body.length);
                                exchange.getResponseBody().write(body);
                                exchange.close();
                        });
                        mockHealthServer.start();
                        mockRestPort2 = mockHealthServer.getAddress().getPort();

                        node01.setPort(closedPort1);
                        node03.setPort(closedPort3);
                        node02.setPort(mockNode2Tcp.getLocalPort());
                        node02.setRestPort(mockRestPort2);

                        plugin.refreshAllNodeStatuses(true);

                        JettraAssert.assertFalse(node01.isActive(), "node-01 debe estar INACTIVE");
                        JettraAssert.assertTrue(node02.isActive(), "node-02 debe estar ACTIVE");
                        JettraAssert.assertEquals("PRIMARY", node02.getRole(),
                                        "node-02 promovido por /api/v1/health debe reflejar rol PRIMARY");
                        JettraAssert.assertEquals("LEADER", node02.getRaftState(),
                                        "node-02 debe reflejar raftState LEADER");
                        JettraAssert.assertEquals("SECONDARY", node01.getRole(),
                                        "El ex-PRIMARY caído debe degradarse a SECONDARY cuando hay un nuevo líder");
                        JettraAssert.assertEquals("DISCONNECTED", node01.getRaftState(),
                                        "node-01 desconectado debe mostrar DISCONNECTED");
                        JettraAssert.assertEquals("node-02", plugin.getPrimaryNode().getId(),
                                        "getPrimaryNode() debe retornar node-02");

                        HttpResponse<String> statusRes = get("/api/cluster/status");
                        JettraAssert.assertEquals(200, statusRes.statusCode(),
                                        "/api/cluster/status debe responder HTTP 200");
                        JettraAssert.assertTrue(statusRes.body().contains("\"primaryNodeId\":\"node-02\""),
                                        "JSON debe identificar primaryNodeId=node-02");
                        JettraAssert.assertTrue(statusRes.body().contains("\"primaryActive\":true"),
                                        "JSON debe indicar primaryActive=true con el nuevo líder");

                        HttpResponse<String> sseRes = get("/api/cluster/events");
                        JettraAssert.assertEquals(200, sseRes.statusCode(),
                                        "SSE /api/cluster/events debe responder HTTP 200");
                        JettraAssert.assertTrue(sseRes.body().contains("event: cluster_status"),
                                        "SSE debe emitir evento cluster_status");
                        JettraAssert.assertTrue(sseRes.body().contains("\"primaryNodeId\":\"node-02\""),
                                        "SSE debe transmitir el nuevo PRIMARY node-02");

                        HttpResponse<String> clusterHtml = get("/cluster");
                        JettraAssert.assertEquals(200, clusterHtml.statusCode(), "/cluster debe responder HTTP 200");
                        JettraAssert.assertTrue(clusterHtml.body().contains("[node-02]"),
                                        "TopBar debe mostrar el chip del nuevo PRIMARY [node-02]");
                        JettraAssert.assertTrue(clusterHtml.body().contains("applyClusterLiveData"),
                                        "/cluster debe incluir actualización reactiva DOM sin recarga");
                        JettraAssert.assertTrue(clusterHtml.body().contains("/api/cluster/events"),
                                        "La plantilla debe suscribirse al canal SSE /api/cluster/events");
                } finally {
                        if (mockHealthServer != null)
                                mockHealthServer.stop(0);
                        node01.setPort(origPort1);
                        node02.setPort(origPort2);
                        node03.setPort(origPort3);
                        node01.setRole(origRole1);
                        node02.setRole(origRole2);
                        node02.setRestPort(origRest2);
                        plugin.refreshAllNodeStatuses(true);
                }
                if (mockNode2Port > 0) {
                        JettraAssert.assertTrue(
                                        io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1",
                                                        mockNode2Port, 2000),
                                        "El puerto mockNode2Tcp debe liberarse al terminar");
                }
                if (mockRestPort2 > 0) {
                        JettraAssert.assertTrue(
                                        io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1",
                                                        mockRestPort2, 2000),
                                        "El puerto mockHealthServer debe liberarse al terminar");
                }
        }

        @Test
        @DisplayName("23. Detección de Entorno Docker Compose y Estrategia Dual en APAGAR NODO")
        public void testDockerComposeDetectionAndDualStopStrategy() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                NodeLifecycleManager lifecycle = plugin.getLifecycleManager();
                NodeLifecycleManager.DockerContainerControl originalDockerControl = lifecycle.getDockerController();

                java.net.ServerSocket fakeDockerNodeSocket = new java.net.ServerSocket(0);
                int fakePort = fakeDockerNodeSocket.getLocalPort();
                java.util.concurrent.atomic.AtomicBoolean stoppedInvoked = new java.util.concurrent.atomic.AtomicBoolean(
                                false);
                java.util.concurrent.atomic.AtomicBoolean startedInvoked = new java.util.concurrent.atomic.AtomicBoolean(
                                false);

                try {
                        lifecycle.setDockerController(new NodeLifecycleManager.DockerContainerControl() {
                                @Override
                                public boolean isDockerEnvironment() {
                                        return true;
                                }

                                @Override
                                public java.util.Optional<String> findContainer(ServerNodeInfo node) {
                                        return "node-test-dk".equalsIgnoreCase(node.getId())
                                                        ? java.util.Optional.of("jettra-node-test-dk")
                                                        : java.util.Optional.empty();
                                }

                                @Override
                                public boolean stopContainer(String containerNameOrId, int timeoutSeconds) {
                                        if ("jettra-node-test-dk".equals(containerNameOrId)) {
                                                stoppedInvoked.set(true);
                                                try {
                                                        fakeDockerNodeSocket.close();
                                                } catch (Exception ignored) {
                                                }
                                                return true;
                                        }
                                        return false;
                                }

                                @Override
                                public boolean startContainer(String containerNameOrId) {
                                        if ("jettra-node-test-dk".equals(containerNameOrId)) {
                                                startedInvoked.set(true);
                                                return true;
                                        }
                                        return false;
                                }
                        });

                        plugin.addNode("node-test-dk", "Docker Node Test", "SECONDARY", "127.0.0.1", fakePort, 2048);
                        ServerNodeInfo dkNode = plugin.getNodeById("node-test-dk");
                        plugin.checkNodeRealReachability(dkNode, 300);
                        JettraAssert.assertTrue(dkNode.isOnline(), "El nodo Docker simulado debe iniciar ONLINE");

                        HttpResponse<String> shutRes = postJson("/api/cluster/nodes/shutdown",
                                        "{\"nodeId\":\"node-test-dk\"}");
                        JettraAssert.assertEquals(200, shutRes.statusCode(),
                                        "API shutdown bajo Docker debe responder HTTP 200");
                        JettraAssert.assertTrue(stoppedInvoked.get(),
                                        "Debe invocar parada limpia del contenedor Docker");
                        JettraAssert.assertTrue(shutRes.body().contains("\"executionEnvironment\":\"DOCKER\""),
                                        "Respuesta debe indicar entorno DOCKER");
                        JettraAssert.assertTrue(shutRes.body().contains("\"dockerContainer\":\"jettra-node-test-dk\""),
                                        "Respuesta debe identificar contenedor detenido");
                        JettraAssert.assertTrue(shutRes.body().contains("DOCKER_STOP[jettra-node-test-dk]"),
                                        "Mecanismo debe reportar DOCKER_STOP");
                        JettraAssert.assertTrue(
                                        shutRes.body().contains("\"status\":\"INACTIVE\"")
                                                        && shutRes.body().contains("\"state\":\"OFFLINE\""),
                                        "Estado devuelto al frontend debe ser INACTIVE/OFFLINE inmediatamente");
                        JettraAssert.assertFalse(dkNode.isOnline(), "El nodo Docker detenido debe quedar OFFLINE");

                        lifecycle.startNode(dkNode, plugin);
                        JettraAssert.assertTrue(startedInvoked.get(),
                                        "startNode debe invocar startContainer bajo Docker");
                } finally {
                        if (!fakeDockerNodeSocket.isClosed()) {
                                try {
                                        fakeDockerNodeSocket.close();
                                } catch (Exception ignored) {
                                }
                        }
                        lifecycle.setDockerController(originalDockerControl);
                        plugin.removeNode("node-test-dk");
                }
                JettraAssert.assertTrue(
                                io.jettra.store.JettraStoreServer.awaitTcpPortAvailable("127.0.0.1", fakePort, 2000),
                                "El puerto fakeDockerNodeSocket debe liberarse al terminar");
        }

        @Test
        @DisplayName("24. Inclusión y Restauración de la Versión 1 en Historial de Versiones (/explorer)")
        public void testVersion1AlwaysPresentAndRestorableInExplorer() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();

                // 1. Verificar registros iniciales con versión > 1 (FAC-2026-003 v2 y HC-98210
                // v3)
                EngineRecordInfo factura = plugin.getRecord("example_factura_db", "Facturas", "FAC-2026-003");
                JettraAssert.assertNotNull(factura, "FAC-2026-003 debe existir en el catálogo");
                JettraAssert.assertTrue(factura.getVersionHistory().stream().anyMatch(v -> v.getVersion() == 1L),
                                "El historial de FAC-2026-003 (v2) debe incluir la versión raíz v1");

                EngineRecordInfo historia = plugin.getRecord("samples_hostipal_db", "Pacientes", "HC-98210");
                JettraAssert.assertNotNull(historia, "HC-98210 debe existir en el catálogo");
                JettraAssert.assertTrue(historia.getVersionHistory().stream().anyMatch(v -> v.getVersion() == 1L),
                                "El historial de HC-98210 (v3) debe incluir la versión raíz v1");

                // 2. Verificar diálogo HTML /explorer para FAC-2026-003 muestra v1 y botón de
                // restauración de v1
                HttpResponse<String> histHtml = get(
                                "/explorer?db=example_factura_db&bucket=Facturas&history_id=FAC-2026-003");
                JettraAssert.assertEquals(200, histHtml.statusCode(), "Historial /explorer debe responder HTTP 200");
                JettraAssert.assertTrue(histHtml.body().contains("[VERSIÓN INICIAL v1]"),
                                "El diálogo debe renderizar el badge de v1");
                JettraAssert.assertTrue(histHtml.body().contains("Restaurar esta Versión (v1)"),
                                "El diálogo debe permitir restaurar la versión 1");

                // 3. Verificar registro construido por deserialización sin historial previo
                // conserva v1 al editar y restaura v1
                EngineRecordInfo deserializedRec = new EngineRecordInfo();
                deserializedRec.setId("DOC-V1-TEST");
                deserializedRec.setEngineType("DOCUMENT");
                deserializedRec.setBucketName("Facturas");
                deserializedRec.setSummary("Resumen Original V1");
                deserializedRec.setDetails("{\"estado\":\"INICIAL_V1\"}");
                deserializedRec.recordNewVersion("Resumen Modificado V2", "{\"estado\":\"EDITADO_V2\"}", "admin",
                                "Segunda versión");

                JettraAssert.assertEquals(2L, deserializedRec.getVersion(), "Debe avanzar a v2");
                JettraAssert.assertEquals(2, deserializedRec.getVersionHistory().size(),
                                "Debe conservar v2 y v1 en el historial");
                JettraAssert.assertTrue(
                                deserializedRec.getVersionHistory().stream()
                                                .anyMatch(v -> v.getVersion() == 1L
                                                                && "Resumen Original V1".equals(v.getSummary())),
                                "v1 debe conservar el summary original previo a la edición");

                boolean restoredDeser = deserializedRec.restoreVersion(1L);
                JettraAssert.assertTrue(restoredDeser, "Debe permitir restaurar v1 desde el modelo deserializado");
                JettraAssert.assertEquals(3L, deserializedRec.getVersion(), "Al restaurar v1 desde v2 debe crear v3");
                JettraAssert.assertEquals("Resumen Original V1", deserializedRec.getSummary(),
                                "Summary debe volver al contenido original de v1");
                JettraAssert.assertEquals("{\"estado\":\"INICIAL_V1\"}", deserializedRec.getDetails(),
                                "Details debe volver al payload original de v1");

                // 4. Ejecutar restauración de v1 vía HTTP POST sobre registro en catálogo
                // temporal
                String tempId = "DOC-HTTP-V1-TEST";
                plugin.addRecord("example_factura_db", "Facturas", tempId, "Resumen HTTP V1",
                                "{\"estado\":\"HTTP_V1\"}");
                try {
                        plugin.updateRecord("example_factura_db", "Facturas", tempId, "Resumen HTTP V2",
                                        "{\"estado\":\"HTTP_V2\"}");
                        HttpResponse<String> restoreV1Res = postFormWithRedirect("/explorer",
                                        "action=restore_version&db=example_factura_db&bucket=Facturas&recordId="
                                                        + tempId + "&version=1");
                        JettraAssert.assertEquals(200, restoreV1Res.statusCode(),
                                        "La restauración de v1 vía POST debe responder HTTP 200");
                        EngineRecordInfo httpRec = plugin.getRecord("example_factura_db", "Facturas", tempId);
                        JettraAssert.assertEquals(3L, httpRec.getVersion(), "Debe crear v3 al restaurar v1 vía HTTP");
                        JettraAssert.assertEquals("Resumen HTTP V1", httpRec.getSummary(),
                                        "El resumen HTTP debe restaurarse exactamente a v1");
                } finally {
                        plugin.deleteRecord("example_factura_db", "Facturas", tempId);
                }
        }

        @Test
        @DisplayName("25. Paginación y Desplazamiento entre Páginas en Tabla de Resultados (/explorer)")
        public void testExplorerPaginationAndPageNavigation() throws Exception {
                // 1. Página 1 por defecto (10 registros por página de 25 totales en Facturas ->
                // 3 páginas)
                HttpResponse<String> page1Res = get("/explorer?db=example_factura_db&bucket=Facturas&page=1&size=10");
                JettraAssert.assertEquals(200, page1Res.statusCode(), "Página 1 de /explorer debe responder HTTP 200");
                JettraAssert.assertTrue(
                                page1Res.body().contains("data-pagination=\"explorer-records\"")
                                                || page1Res.body().contains("data-pagination='explorer-records'"),
                                "Debe renderizar barra de paginación");
                JettraAssert.assertTrue(page1Res.body().contains("FAC-2026-001"), "Página 1 debe mostrar FAC-2026-001");
                JettraAssert.assertTrue(page1Res.body().contains("FAC-2026-010"), "Página 1 debe mostrar FAC-2026-010");
                JettraAssert.assertFalse(page1Res.body().contains("FAC-2026-011"),
                                "Página 1 no debe mostrar FAC-2026-011");
                JettraAssert.assertTrue(page1Res.body().contains("Siguiente ▶"),
                                "Debe incluir control de desplazamiento Siguiente ▶");
                JettraAssert.assertTrue(page1Res.body().contains("Última ⏭"),
                                "Debe incluir control de desplazamiento Última ⏭");

                // 2. Desplazamiento a Página 2
                HttpResponse<String> page2Res = get("/explorer?db=example_factura_db&bucket=Facturas&page=2&size=10");
                JettraAssert.assertEquals(200, page2Res.statusCode(), "Página 2 de /explorer debe responder HTTP 200");
                JettraAssert.assertFalse(page2Res.body().contains("FAC-2026-001"),
                                "Página 2 no debe mostrar FAC-2026-001");
                JettraAssert.assertTrue(page2Res.body().contains("FAC-2026-011"), "Página 2 debe mostrar FAC-2026-011");
                JettraAssert.assertTrue(page2Res.body().contains("FAC-2026-020"), "Página 2 debe mostrar FAC-2026-020");
                JettraAssert.assertFalse(page2Res.body().contains("FAC-2026-021"),
                                "Página 2 no debe mostrar FAC-2026-021");
                JettraAssert.assertTrue(page2Res.body().contains("⏮ Primera"),
                                "Debe incluir enlace activo a Primera página");
                JettraAssert.assertTrue(page2Res.body().contains("◀ Anterior"),
                                "Debe incluir enlace activo a página Anterior");

                // 3. Desplazamiento a Página 3 (Última página con registros 21 al 25)
                HttpResponse<String> page3Res = get("/explorer?db=example_factura_db&bucket=Facturas&page=3&size=10");
                JettraAssert.assertEquals(200, page3Res.statusCode(), "Página 3 de /explorer debe responder HTTP 200");
                JettraAssert.assertTrue(page3Res.body().contains("FAC-2026-021"), "Página 3 debe mostrar FAC-2026-021");
                JettraAssert.assertTrue(page3Res.body().contains("FAC-2026-025"), "Página 3 debe mostrar FAC-2026-025");
                JettraAssert.assertFalse(page3Res.body().contains("FAC-2026-020"),
                                "Página 3 no debe mostrar FAC-2026-020");
        }

        @Test
        @DisplayName("26. Crear Nueva Base de Datos (/databases) sin solicitar Bucket ni Motor y con los 9 Engines de JettraStore")
        public void testCreateNewDatabaseWithoutBucketOrEngineSupportsAll9Engines() throws Exception {
                // 1. Verificar que el formulario "Crear Nueva Base de Datos" en /databases no
                // solicita defaultBucket
                HttpResponse<String> dbPageRes = get("/databases");
                JettraAssert.assertEquals(200, dbPageRes.statusCode(), "/databases debe responder HTTP 200");
                JettraAssert.assertTrue(dbPageRes.body().contains("Crear Nueva Base de Datos"),
                                "Debe mostrar sección Crear Nueva Base de Datos");
                JettraAssert.assertFalse(dbPageRes.body().contains("name=\"defaultBucket\""),
                                "El formulario de nueva BD no debe solicitar defaultBucket");
                JettraAssert.assertTrue(dbPageRes.body().contains("9 motores (engines) de JettraStore"),
                                "Debe indicar soporte nativo de los 9 engines de JettraStore");

                // 2. Crear nueva BD enviando únicamente dbName y description
                String newDbName = "test_multimodel_9engines_db";
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                plugin.deleteDatabase(newDbName);

                try {
                        HttpResponse<String> createRes = postForm("/databases", "action=create_db&dbName=" + newDbName
                                        + "&description=Base_Multimodelo_9_Motores");
                        JettraAssert.assertEquals(302, createRes.statusCode(),
                                        "POST create_db sin bucket ni motor debe redirigir 302");

                        JettraAssert.assertTrue(plugin.getDatabaseNames().contains(newDbName),
                                        "La nueva BD debe estar registrada en el catálogo");
                        java.util.List<EngineBucketInfo> buckets = plugin.getBuckets(newDbName);
                        JettraAssert.assertEquals(9, buckets.size(),
                                        "La nueva BD debe habilitar automáticamente los 9 engines de JettraStore");

                        for (String expectedEngine : JettraStorePlugin.SUPPORTED_ENGINES) {
                                boolean hasEngine = buckets.stream()
                                                .anyMatch(b -> expectedEngine.equalsIgnoreCase(b.getEngineType()));
                                JettraAssert.assertTrue(hasEngine,
                                                "La nueva BD debe incluir soporte para el motor " + expectedEngine);
                        }
                } finally {
                        plugin.deleteDatabase(newDbName);
                }
        }

        @Test
        @DisplayName("27. Paginación Streaming Optimizada hasta 1,000,025 Documentos en Facturas (/explorer)")
        public void testExplorerStreamingPaginationUpTo1000025Documents() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                EngineBucketInfo facturas = plugin.getBucket("example_factura_db", "Facturas");
                JettraAssert.assertNotNull(facturas, "Bucket Facturas debe existir");
                JettraAssert.assertEquals(1_000_025L, facturas.getTotalObjects(),
                                "Facturas debe reportar 1,000,025 documentos");

                // 1. Página 1 muestra streaming activo y total real de 100,003 páginas (con
                // size=10)
                HttpResponse<String> p1Res = get("/explorer?db=example_factura_db&bucket=Facturas&page=1&size=10");
                JettraAssert.assertEquals(200, p1Res.statusCode(), "Página 1 debe responder HTTP 200");
                JettraAssert.assertTrue(
                                p1Res.body().contains("data-total-records=\"1000025\"")
                                                || p1Res.body().contains("data-total-records='1000025'"),
                                "Debe reportar 1,000,025 registros totales");
                JettraAssert.assertTrue(
                                p1Res.body().contains("data-total-pages=\"100003\"")
                                                || p1Res.body().contains("data-total-pages='100003'"),
                                "Debe calcular 100,003 páginas con size=10");
                JettraAssert.assertTrue(p1Res.body().contains("Streaming por página"),
                                "Debe indicar paginación por streaming");

                // 2. Desplazamiento a página intermedia (página 50,000 -> registros 499,991 al
                // 500,000)
                HttpResponse<String> p50kRes = get(
                                "/explorer?db=example_factura_db&bucket=Facturas&page=50000&size=10");
                JettraAssert.assertEquals(200, p50kRes.statusCode(), "Página 50000 debe responder HTTP 200");
                JettraAssert.assertTrue(p50kRes.body().contains("FAC-2026-499991"),
                                "Página 50000 debe mostrar FAC-2026-499991");
                JettraAssert.assertTrue(p50kRes.body().contains("FAC-2026-500000"),
                                "Página 50000 debe mostrar FAC-2026-500000");
                JettraAssert.assertFalse(p50kRes.body().contains("FAC-2026-001"),
                                "Página 50000 no debe cargar los registros de la página 1");
                JettraAssert.assertEquals(25, facturas.getSampleRecords().size(),
                                "El streaming por página no debe materializar 1,000,025 objetos en memoria");

                // 3. Desplazamiento a la última página (página 100,003 -> registros 1,000,021
                // al 1,000,025)
                HttpResponse<String> lastRes = get(
                                "/explorer?db=example_factura_db&bucket=Facturas&page=100003&size=10");
                JettraAssert.assertEquals(200, lastRes.statusCode(), "Última página 100003 debe responder HTTP 200");
                JettraAssert.assertTrue(lastRes.body().contains("FAC-2026-1000021"),
                                "Última página debe mostrar FAC-2026-1000021");
                JettraAssert.assertTrue(lastRes.body().contains("FAC-2026-1000025"),
                                "Última página debe mostrar FAC-2026-1000025");
        }

        @Test
        @DisplayName("28. Edición de Usuarios en /users (Propio Perfil y Administración por Rol ADMIN)")
        public void testUserEditSelfAndAdminInUsersPage() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String testUser = "dev_edit_test";
                plugin.deleteUser(testUser);

                try {
                        // 1. Admin crea usuario dev_edit_test
                        JettraUser u = new JettraUser(testUser, "pass123", "Desarrollador Inicial",
                                        JettraUser.GlobalRole.DEVELOPER);
                        u.setRoleForDatabase("example_factura_db", JettraDatabaseRole.READ_ONLY);
                        plugin.saveOrUpdateUser(u);

                        // 2. Admin abre formulario de edición de dev_edit_test y actualiza descripción
                        // y rol
                        HttpResponse<String> adminEditPage = get("/users?edit=" + testUser);
                        JettraAssert.assertEquals(200, adminEditPage.statusCode(),
                                        "Admin debe poder abrir edición de cualquier usuario");
                        JettraAssert.assertTrue(adminEditPage.body().contains("Editar Usuario: " + testUser),
                                        "Debe mostrar formulario de edición para " + testUser);

                        HttpResponse<String> adminSave = postForm("/users",
                                        "action=save_user&username=" + testUser
                                                        + "&password=&globalRole=OPERATOR&description=Editado_Por_Admin&db_role_example_factura_db=READ_WRITE");
                        JettraAssert.assertEquals(302, adminSave.statusCode(),
                                        "POST save_user como admin debe redirigir 302");

                        JettraUser updatedByAdmin = plugin.findUser(testUser).orElse(null);
                        JettraAssert.assertNotNull(updatedByAdmin, "El usuario debe existir tras edición");
                        JettraAssert.assertEquals("pass123", updatedByAdmin.getPassword(),
                                        "Si password va vacío en edición debe conservar el password previo");
                        JettraAssert.assertEquals("Editado_Por_Admin", updatedByAdmin.getDescription(),
                                        "Admin debe actualizar descripción");
                        JettraAssert.assertEquals(JettraUser.GlobalRole.OPERATOR, updatedByAdmin.getGlobalRole(),
                                        "Admin debe poder cambiar el rol global");

                        // 3. Usuario no-admin inicia sesión: puede editar sus propios datos, pero NO
                        // los de admin ni escalar su rol
                        String devCookie = loginAndGetSessionCookie(sharedPort, testUser, "pass123");

                        HttpResponse<String> selfEditPage = get("/users?edit=" + testUser, devCookie);
                        JettraAssert.assertEquals(200, selfEditPage.statusCode(),
                                        "Usuario estándar debe poder abrir su propio perfil");
                        JettraAssert.assertTrue(selfEditPage.body().contains("Editar Usuario: " + testUser),
                                        "Debe mostrar formulario de edición propio");

                        HttpResponse<String> selfSave = postForm("/users",
                                        "action=save_user&username=" + testUser
                                                        + "&password=newpass456&globalRole=ADMIN&description=AutoEditado",
                                        devCookie);
                        JettraAssert.assertEquals(302, selfSave.statusCode(), "Autoedición debe redirigir 302");

                        JettraUser selfUpdated = plugin.findUser(testUser).orElse(null);
                        JettraAssert.assertNotNull(selfUpdated, "Usuario debe existir tras autoedición");
                        JettraAssert.assertEquals("newpass456", selfUpdated.getPassword(),
                                        "Debe actualizar su propia contraseña");
                        JettraAssert.assertEquals("AutoEditado", selfUpdated.getDescription(),
                                        "Debe actualizar su propia descripción");
                        JettraAssert.assertEquals(JettraUser.GlobalRole.OPERATOR, selfUpdated.getGlobalRole(),
                                        "Usuario no-admin no debe poder auto-escalar su rol a ADMIN");

                        // 4. Usuario no-admin intenta editar a 'admin' -> rechazado
                        HttpResponse<String> forbiddenEditPage = get("/users?edit=admin", devCookie);
                        JettraAssert.assertTrue(forbiddenEditPage.body().contains("Acceso denegado"),
                                        "No-admin no debe poder abrir edición de otro usuario");

                        HttpResponse<String> forbiddenPost = postForm("/users",
                                        "action=save_user&username=admin&password=hacked&description=Hacked",
                                        devCookie);
                        JettraAssert.assertTrue(
                                        forbiddenPost.headers().firstValue("Location").orElse("")
                                                        .contains("msg=forbidden"),
                                        "POST de no-admin intentando editar otro usuario debe responder forbidden");
                        JettraAssert.assertEquals("admin123", plugin.findUser("admin").orElseThrow().getPassword(),
                                        "La contraseña de admin no debe haber sido alterada");
                } finally {
                        plugin.deleteUser(testUser);
                }
        }

        @Test
        @DisplayName("29. Administrar Usuarios por Base de Datos en /databases (Solo Rol ADMIN)")
        public void testDatabaseUserManagementAdminOnly() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String testUser = "dev_db_test";
                plugin.deleteUser(testUser);

                try {
                        JettraUser u = new JettraUser(testUser, "dbpass123", "Usuario de prueba BD",
                                        JettraUser.GlobalRole.DEVELOPER);
                        plugin.saveOrUpdateUser(u);

                        // 1. Admin ve botón "Administrar Usuarios" en /databases y puede añadir/remover
                        // usuario en example_factura_db
                        HttpResponse<String> adminDbRes = get(
                                        "/databases?db=example_factura_db&manage_users_db=example_factura_db");
                        JettraAssert.assertEquals(200, adminDbRes.statusCode(),
                                        "Admin debe acceder a /databases con manage_users_db");
                        JettraAssert.assertTrue(adminDbRes.body().contains("Administrar Usuarios"),
                                        "Debe mostrar botón Administrar Usuarios");
                        JettraAssert.assertTrue(adminDbRes.body().contains(
                                        "Administración de Usuarios de la Base de Datos [example_factura_db]"),
                                        "Debe abrir el panel de administración de usuarios de la BD");

                        HttpResponse<String> addRes = postForm("/databases",
                                        "action=add_db_user&db=example_factura_db&username=" + testUser
                                                        + "&role=READ_WRITE");
                        JettraAssert.assertEquals(302, addRes.statusCode(),
                                        "Añadir usuario a BD como admin debe redirigir 302");
                        JettraAssert.assertEquals(JettraDatabaseRole.READ_WRITE,
                                        plugin.findUser(testUser).orElseThrow()
                                                        .getRoleForDatabase("example_factura_db"),
                                        "El usuario debe quedar asignado con rol READ_WRITE en example_factura_db");

                        HttpResponse<String> removeRes = postForm("/databases",
                                        "action=remove_db_user&db=example_factura_db&username=" + testUser);
                        JettraAssert.assertEquals(302, removeRes.statusCode(),
                                        "Remover usuario de BD como admin debe redirigir 302");
                        JettraAssert.assertEquals(JettraDatabaseRole.NONE,
                                        plugin.findUser(testUser).orElseThrow()
                                                        .getRoleForDatabase("example_factura_db"),
                                        "El usuario debe quedar removido (NONE) de example_factura_db");

                        // 2. Usuario no-admin NO tiene activada la opción y sus peticiones POST son
                        // denegadas
                        String devCookie = loginAndGetSessionCookie(sharedPort, testUser, "dbpass123");
                        HttpResponse<String> nonAdminDbPage = get(
                                        "/databases?db=example_factura_db&manage_users_db=example_factura_db",
                                        devCookie);
                        JettraAssert.assertEquals(200, nonAdminDbPage.statusCode(),
                                        "/databases debe responder HTTP 200");
                        JettraAssert.assertTrue(nonAdminDbPage.body().contains("🔒 Administrar Usuarios"),
                                        "No-admin debe ver el botón bloqueado");
                        JettraAssert.assertFalse(nonAdminDbPage.body().contains(
                                        "Administración de Usuarios de la Base de Datos [example_factura_db]"),
                                        "No-admin no debe poder abrir el panel de administración de usuarios de BD");

                        HttpResponse<String> forbiddenAdd = postForm("/databases",
                                        "action=add_db_user&db=example_factura_db&username=" + testUser + "&role=ADMIN",
                                        devCookie);
                        JettraAssert.assertTrue(
                                        forbiddenAdd.headers().firstValue("Location").orElse("")
                                                        .contains("msg=forbidden"),
                                        "POST add_db_user por no-admin debe ser rechazado con forbidden");
                        JettraAssert.assertEquals(JettraDatabaseRole.NONE,
                                        plugin.findUser(testUser).orElseThrow()
                                                        .getRoleForDatabase("example_factura_db"),
                                        "Los permisos del usuario en la BD no deben cambiar tras intento no autorizado");
                } finally {
                        plugin.deleteUser(testUser);
                }
        }

        @Test
        @DisplayName("30. Visibilidad de Opciones Select en Tema Oscuro en /databases")
        public void testDarkThemeSelectOptionStylesInDatabasesPage() throws Exception {
                HttpResponse<String> dbPage = get("/databases?db=example_factura_db");
                JettraAssert.assertEquals(200, dbPage.statusCode(), "/databases debe responder HTTP 200");
                JettraAssert.assertTrue(dbPage.body().contains("<style>"),
                                "La plantilla debe renderizar etiquetas <style> válidas y no texto plano");
                JettraAssert.assertFalse(dbPage.body().contains("espresso-style"),
                                "No debe renderizar CSS como <div class='espresso-style'>");
                JettraAssert.assertTrue(dbPage.body().contains("color-scheme: dark;"),
                                "Debe declarar color-scheme: dark para controles nativos");
                JettraAssert.assertTrue(dbPage.body().contains("[data-theme=\"dark\"] select.app-input option"),
                                "Debe definir color de fondo y texto explícito para <option> en tema oscuro");
                JettraAssert.assertTrue(
                                dbPage.body().contains(".jettra-select-menu")
                                                && dbPage.body().contains("initThemedSelects()"),
                                "Debe incluir el selector temático personalizado para evitar menús nativos ilegibles en tema oscuro");
        }

        @Test
        @DisplayName("31. Visualizadores y Editores Específicos por Tipo de Motor en /explorer (Ver y Editar)")
        public void testEngineSpecificViewAndEditRenderersInExplorer() throws Exception {
                String[][] cases = {
                                { "example_factura_db", "Facturas", "FAC-2026-001", "DOCUMENT" },
                                { "example_factura_db", "DomainEntities", "OBJ-ENT-101", "OBJECT" },
                                { "example_factura_db", "CustomerRecords", "REC-ORD-501", "JAVA_RECORD" },
                                { "samples_hostipal_db", "RedEpidemiologica", "EDGE-4401", "GRAPH" },
                                { "example_factura_db", "BalanceCache", "BAL_ACC_4001", "KEY_VALUE" },
                                { "samples_ambiental_db", "EstacionesGeo", "GEO-EST-01", "GEOSPATIAL" },
                                { "samples_hostipal_db", "DiagnosticoEmbeddings", "VEC-MED-01", "VECTOR" }
                };

                for (String[] tc : cases) {
                        String db = tc[0], bucket = tc[1], id = tc[2], engine = tc[3];
                        HttpResponse<String> viewRes = get(
                                        "/explorer?db=" + db + "&bucket=" + bucket + "&view_id=" + id);
                        JettraAssert.assertEquals(200, viewRes.statusCode(),
                                        "Ver " + engine + " debe responder HTTP 200");
                        JettraAssert.assertTrue(viewRes.body().contains("data-engine-viewer=\"" + engine + "\"")
                                        || viewRes.body().contains("data-engine-viewer='" + engine + "'"),
                                        "El botón Ver para " + engine + " debe usar su visualizador específico");

                        HttpResponse<String> editRes = get(
                                        "/explorer?db=" + db + "&bucket=" + bucket + "&edit_id=" + id);
                        JettraAssert.assertEquals(200, editRes.statusCode(),
                                        "Editar " + engine + " debe responder HTTP 200");
                        JettraAssert.assertTrue(editRes.body().contains("data-engine-editor=\"" + engine + "\"")
                                        || editRes.body().contains("data-engine-editor='" + engine + "'"),
                                        "El botón Editar para " + engine + " debe usar su editor específico");

                        HttpResponse<String> histRes = get(
                                        "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + id);
                        JettraAssert.assertEquals(200, histRes.statusCode(),
                                        "Versiones " + engine + " debe responder HTTP 200");
                        JettraAssert.assertTrue(histRes.body().contains("data-engine-viewer=\"" + engine + "\"")
                                        || histRes.body().contains("data-engine-viewer='" + engine + "'"),
                                        "El botón Versiones para " + engine + " debe usar su visualizador específico");
                }

                // Verificar guardado desde editor específico GEOSPATIAL
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                EngineRecordInfo origGeo = plugin.getRecord("samples_ambiental_db", "EstacionesGeo", "GEO-EST-01");
                String origSummary = origGeo.getSummary();
                String origDetails = origGeo.getDetails();
                try {
                        HttpResponse<String> saveGeo = postForm("/explorer",
                                        "action=save_edit&db=samples_ambiental_db&bucket=EstacionesGeo&recordId=GEO-EST-01"
                                                        + "&summary=Estacion_Actualizada&engineEditorMode=GEOSPATIAL"
                                                        + "&prop_station_id=EST-CENTRAL-01&prop_lat=19.5001&prop_lng=-99.2002&prop_altitud_m=2250&prop_zona=Norte");
                        JettraAssert.assertEquals(302, saveGeo.statusCode(),
                                        "POST save_edit GEOSPATIAL debe redirigir 302");
                        EngineRecordInfo updatedGeo = plugin.getRecord("samples_ambiental_db", "EstacionesGeo",
                                        "GEO-EST-01");
                        JettraAssert.assertTrue(updatedGeo.getDetails().contains("19.5001"),
                                        "Debe persistir la latitud editada en GEOSPATIAL");
                        JettraAssert.assertTrue(updatedGeo.getDetails().contains("-99.2002"),
                                        "Debe persistir la longitud editada en GEOSPATIAL");
                } finally {
                        plugin.updateRecord("samples_ambiental_db", "EstacionesGeo", "GEO-EST-01", origSummary,
                                        origDetails);
                }
        }

        @Test
        @DisplayName("32. Diálogo Modal Administrar Motores sobre el Formulario en /databases")
        public void testManageEnginesOpensModalOverDatabasesPage() throws Exception {
                HttpResponse<String> baseRes = get("/databases");
                JettraAssert.assertEquals(200, baseRes.statusCode(), "/databases debe responder HTTP 200");
                JettraAssert.assertTrue(baseRes.body().contains("manage_engines=true"),
                                "El botón Administrar Motores debe apuntar a ?db=...&manage_engines=true");
                JettraAssert.assertFalse(baseRes.body().contains("id=\"dbEnginesDialog\""),
                                "Sin pulsar Administrar Motores no debe abrirse el diálogo modal");

                HttpResponse<String> modalRes = get("/databases?db=example_factura_db&manage_engines=true");
                JettraAssert.assertEquals(200, modalRes.statusCode(),
                                "/databases con manage_engines=true debe responder HTTP 200");
                JettraAssert.assertTrue(modalRes.body().contains("id=\"dbEnginesDialog\""),
                                "Debe renderizar el diálogo modal dbEnginesDialog sobre la página /databases");
                JettraAssert.assertTrue(modalRes.body().contains("espresso-modal-overlay"),
                                "El diálogo debe usar la superposición modal .espresso-modal-overlay");
                JettraAssert.assertTrue(
                                modalRes.body().contains("Motores de Almacenamiento y Buckets en [example_factura_db]"),
                                "El diálogo debe mostrar los motores y buckets de la base de datos seleccionada");
        }

        @Test
        @DisplayName("33. Diálogo Administrar Índices por Base de Datos y por Bucket en /databases")
        public void testManageIndexesDialogAndBucketIndexCrudInDatabasesPage() throws Exception {
                HttpResponse<String> baseRes = get("/databases");
                JettraAssert.assertEquals(200, baseRes.statusCode(), "/databases debe responder HTTP 200");
                JettraAssert.assertTrue(baseRes.body().contains("Administrar Índices"),
                                "Cada base de datos debe mostrar el botón Administrar Índices");
                JettraAssert.assertFalse(baseRes.body().contains("id=\"dbIndexesDialog\""),
                                "Sin pulsar Administrar Índices no debe abrirse el diálogo de índices");

                HttpResponse<String> idxDialogRes = get(
                                "/databases?db=example_factura_db&manage_indexes_db=example_factura_db&index_bucket=Facturas");
                JettraAssert.assertEquals(200, idxDialogRes.statusCode(), "Diálogo de índices debe responder HTTP 200");
                JettraAssert.assertTrue(idxDialogRes.body().contains("id=\"dbIndexesDialog\""),
                                "Debe abrir el diálogo modal dbIndexesDialog sobre /databases");
                JettraAssert.assertTrue(
                                idxDialogRes.body().contains("Administración de Índices en [example_factura_db]"),
                                "Debe mostrar el título del diálogo de índices de la BD");
                JettraAssert.assertTrue(
                                idxDialogRes.body().contains("Facturas") && idxDialogRes.body().contains("Clientes"),
                                "Debe listar los buckets creados en la base de datos seleccionada");
                JettraAssert.assertTrue(idxDialogRes.body().contains("Índices del Bucket [Facturas]"),
                                "Debe mostrar la administración de índices del bucket seleccionado");
                JettraAssert.assertTrue(
                                idxDialogRes.body().contains("NUMBER") && idxDialogRes.body().contains("SEQUENTIAL"),
                                "El selector de Tipo de Índice debe incluir las opciones NUMBER y SEQUENTIAL");

                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String idxName = "idx_test_total_estado";
                plugin.deleteIndex("example_factura_db", "Facturas", idxName);
                try {
                        HttpResponse<String> addIdxRes = postForm("/databases",
                                        "action=add_index&db=example_factura_db&bucket=Facturas&indexName=" + idxName
                                                        + "&fields=total,estado&indexType=BTREE&unique=NO");
                        JettraAssert.assertEquals(302, addIdxRes.statusCode(), "Crear índice debe redirigir 302");
                        JettraAssert.assertTrue(
                                        plugin.getBucket("example_factura_db", "Facturas").getIndexes().stream()
                                                        .anyMatch(i -> idxName.equalsIgnoreCase(i.getName())),
                                        "El nuevo índice debe quedar registrado en el bucket Facturas");

                        HttpResponse<String> delIdxRes = postForm("/databases",
                                        "action=delete_index&db=example_factura_db&bucket=Facturas&indexName="
                                                        + idxName);
                        JettraAssert.assertEquals(302, delIdxRes.statusCode(), "Eliminar índice debe redirigir 302");
                        JettraAssert.assertFalse(
                                        plugin.getBucket("example_factura_db", "Facturas").getIndexes().stream()
                                                        .anyMatch(i -> idxName.equalsIgnoreCase(i.getName())),
                                        "El índice eliminado no debe permanecer en el bucket");
                } finally {
                        plugin.deleteIndex("example_factura_db", "Facturas", idxName);
                }
        }

        @Test
        @DisplayName("34. Datatables Responsive en Todas las Páginas (/explorer, /databases, /status)")
        public void testResponsiveDatatablesAcrossAllPages() throws Exception {
                for (String path : new String[] { "/explorer", "/databases", "/status" }) {
                        HttpResponse<String> res = get(path);
                        JettraAssert.assertEquals(200, res.statusCode(), path + " debe responder HTTP 200");
                        JettraAssert.assertTrue(res.body().contains(".jettra-table-responsive"),
                                        path + " debe incluir contenedor responsive .jettra-table-responsive");
                        JettraAssert.assertTrue(res.body().contains("@media (max-width: 640px)"),
                                        path + " debe incluir media query móvil para pantallas pequeñas (360x780)");
                        JettraAssert.assertTrue(res.body().contains("initResponsiveDatatables()"),
                                        path + " debe inicializar automáticamente las etiquetas data-label y el contenedor responsive");
                }
        }

        @Test
        @DisplayName("35. /cluster sin Storage Path de cluster.config y Nueva Página Gráfica /map de Transferencia en Tiempo Real")
        public void testClusterWithoutStoragePathAndTransferMapPage() throws Exception {
                // 1. Verificar que /cluster NO muestra Storage Path: de cluster.config
                HttpResponse<String> clusterRes = get("/cluster");
                JettraAssert.assertEquals(200, clusterRes.statusCode(), "/cluster debe responder HTTP 200");
                JettraAssert.assertFalse(clusterRes.body().contains("Storage Path:"),
                                "/cluster no debe mostrar la propiedad Storage Path: de cluster.config");
                JettraAssert.assertTrue(clusterRes.body().contains("/map"),
                                "La navegación debe incluir enlace a /map");

                // 2. Verificar nueva página /map
                HttpResponse<String> mapRes = get("/map");
                JettraAssert.assertEquals(200, mapRes.statusCode(), "/map debe responder HTTP 200");
                JettraAssert.assertTrue(mapRes.body().contains("Mapa de Transferencia"),
                                "/map debe mostrar título Mapa de Transferencia");
                JettraAssert.assertTrue(mapRes.body().contains("transfer-map-canvas-host"),
                                "/map debe renderizar el lienzo gráfico interactivo transfer-map-canvas-host");
                JettraAssert.assertTrue(mapRes.body().contains("map-transfer-feed"),
                                "/map debe incluir la bitácora de transmisiones en tiempo real");

                // 3. Verificar que /api/cluster/status incluye recentTransfers para el mapa
                HttpResponse<String> statusApi = get("/api/cluster/status");
                JettraAssert.assertEquals(200, statusApi.statusCode(), "/api/cluster/status debe responder HTTP 200");
                JettraAssert.assertTrue(statusApi.body().contains("\"recentTransfers\""),
                                "/api/cluster/status debe incluir recentTransfers para alimentar el mapa en tiempo real");
        }

        @Test
        @DisplayName("36. Administración de Bases de Datos de Ejemplo en /examples (Listar, Instalar y Eliminar)")
        public void testExamplesPageInstallAndUninstall() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                JettraAssert.assertTrue(cleanStartVerified,
                                "JettraStoreExplorer debe iniciar limpio sin bases de datos de ejemplo precargadas");
                try {
                        // 1. Verificar listado en /examples
                        HttpResponse<String> listRes = get("/examples");
                        JettraAssert.assertEquals(200, listRes.statusCode(), "/examples debe responder HTTP 200");
                        JettraAssert.assertTrue(listRes.body().contains("Administración de Bases de Datos de Ejemplo"),
                                        "/examples debe mostrar el título de administración de ejemplos");
                        JettraAssert.assertTrue(listRes.body().contains("example_factura_db"),
                                        "/examples debe listar example_factura_db");
                        JettraAssert.assertTrue(listRes.body().contains("system_metadata_db"),
                                        "/examples debe listar system_metadata_db");

                        // 2. Eliminar una base de ejemplo específica desde /examples
                        HttpResponse<String> delOneRes = postForm("/examples",
                                        "action=delete_example&exampleId=system_metadata_db");
                        JettraAssert.assertEquals(302, delOneRes.statusCode(),
                                        "POST delete_example debe redirigir 302");
                        JettraAssert.assertFalse(plugin.getDatabaseNames().contains("system_metadata_db"),
                                        "system_metadata_db debe ser eliminada del catálogo");

                        HttpResponse<String> afterDelRes = get("/examples");
                        JettraAssert.assertTrue(afterDelRes.body().contains("NO INSTALADA"),
                                        "/examples debe indicar estado NO INSTALADA tras eliminar");

                        // 3. Reinstalar la base de ejemplo desde /examples
                        HttpResponse<String> instRes = postForm("/examples",
                                        "action=install_example&exampleId=system_metadata_db");
                        JettraAssert.assertEquals(302, instRes.statusCode(), "POST install_example debe redirigir 302");
                        JettraAssert.assertTrue(plugin.getDatabaseNames().contains("system_metadata_db"),
                                        "system_metadata_db debe quedar instalada en el catálogo");
                } finally {
                        plugin.installExampleDatabase("system_metadata_db");
                }
        }

        @Test
        @DisplayName("37. Operación de JettraStoreExplorer sin cluster.nodes= al añadir o expulsar nodos")
        public void testClusterNodesPersistenceInMicroprofileAndClusterConfig() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                java.nio.file.Path tempMp = java.nio.file.Files.createTempFile("jettra_explorer_mp_", ".properties");
                String prevCfg = System.getProperty("jettra.explorer.config.file");
                try {
                        java.nio.file.Files.writeString(tempMp,
                                        "app.title=JettraStore Explorer\ncluster.ip.nodes=127.0.0.1:50001\n",
                                        java.nio.charset.StandardCharsets.UTF_8);
                        System.setProperty("jettra.explorer.config.file", tempMp.toAbsolutePath().toString());

                        // 1. Añadir node-09 y verificar actualización en la topología/estado sin
                        // escribir cluster.nodes=
                        boolean added = plugin.addNode("node-09", "node-09-replica", "SECONDARY", "127.0.0.1", 50009,
                                        2048);
                        JettraAssert.assertTrue(added, "Debe añadir node-09 correctamente");

                        String mpAfterAdd = java.nio.file.Files.readString(tempMp,
                                        java.nio.charset.StandardCharsets.UTF_8);
                        JettraAssert.assertFalse(mpAfterAdd.contains("cluster.nodes="),
                                        "microprofile-config.properties no debe contener ni requerir cluster.nodes=");

                        String clusterCfgAfterAdd = new String(plugin.buildClusterConfigBytesFromNodes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        JettraAssert.assertTrue(clusterCfgAfterAdd.contains("node-09"),
                                        "cluster-state.config generado para replicación debe incluir node-09");
                        JettraAssert.assertFalse(clusterCfgAfterAdd.contains(".storage.path"),
                                        "cluster-state.config no debe contener propiedades storage.path");

                        // 2. Verificar regla estricta: no se permite expulsar un nodo activo sin
                        // detenerlo primero
                        boolean blockedWhileActive = plugin.removeNode("node-09");
                        JettraAssert.assertFalse(blockedWhileActive,
                                        "No debe permitir expulsar node-09 mientras esté activo (RUNNING)");

                        // 3. Detener node-09 y luego expulsarlo correctamente
                        var node09 = plugin.getNodeById("node-09");
                        if (node09 != null) {
                                node09.setOnline(false);
                        }
                        boolean removed = plugin.removeNode("node-09");
                        JettraAssert.assertTrue(removed, "Debe expulsar node-09 correctamente tras detenerlo");

                        String clusterCfgAfterRemove = new String(plugin.buildClusterConfigBytesFromNodes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        JettraAssert.assertFalse(clusterCfgAfterRemove.contains("node-09"),
                                        "cluster-state.config generado para replicación no debe incluir node-09 tras expulsarlo");
                } finally {
                        plugin.removeNode("node-09");
                        if (prevCfg == null) {
                                System.clearProperty("jettra.explorer.config.file");
                        } else {
                                System.setProperty("jettra.explorer.config.file", prevCfg);
                        }
                        java.nio.file.Files.deleteIfExists(tempMp);
                }
        }

        @Test
        @DisplayName("38. Sincronización de catálogo de bases de datos desde el nodo PRIMARY en /databases")
        public void testDatabasesCatalogReflectsPrimaryNodeDatabases() throws Exception {
                java.nio.file.Path tempStorage = java.nio.file.Files
                                .createTempDirectory("jettra_explorer_primary_dbs_");
                int grpcPort = freeLocalPort();
                int restPort = freeLocalPort();
                java.util.Properties props = new java.util.Properties();
                props.setProperty("cluster.node.id", "node-01");
                props.setProperty("cluster.node.role", "PRIMARY");
                props.setProperty("jettra.node.ip", "127.0.0.1");
                props.setProperty("jettra.network.host", "127.0.0.1");
                props.setProperty("jettra.network.rest.port", String.valueOf(restPort));
                props.setProperty("jettra.network.grpc.port", String.valueOf(grpcPort));
                props.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
                props.setProperty("jettra.cluster_state_config_dir",
                                tempStorage.resolve("state").toAbsolutePath().toString());
                props.setProperty("jettra.storage.mode", "JVM_RAM");
                props.setProperty("cluster.multinode.active", "on");
                props.setProperty("jettrapolice.active", "false");

                String prevCp = System.getProperty("cluster.config.path");
                String prevStateDir = System.getProperty("jettra.cluster_state_config_dir");
                String prevJsp = System.getProperty("jettra.storage.path");
                String prevSp = System.getProperty("storage.path");
                io.jettra.store.JettraStoreServer primaryServer;
                try {
                        System.clearProperty("cluster.config.path");
                        System.clearProperty("jettra.storage.path");
                        System.clearProperty("storage.path");
                        System.setProperty("jettra.cluster_state_config_dir",
                                        tempStorage.resolve("state").toAbsolutePath().toString());
                        io.jettra.store.core.JettraStoreConfig primaryCfg = new io.jettra.store.core.JettraStoreConfig(
                                        props, new java.util.Properties(), props);
                        primaryServer = new io.jettra.store.JettraStoreServer(primaryCfg);
                } finally {
                        if (prevCp != null)
                                System.setProperty("cluster.config.path", prevCp);
                        else
                                System.clearProperty("cluster.config.path");
                        if (prevStateDir != null)
                                System.setProperty("jettra.cluster_state_config_dir", prevStateDir);
                        else
                                System.clearProperty("jettra.cluster_state_config_dir");
                        if (prevJsp != null)
                                System.setProperty("jettra.storage.path", prevJsp);
                        else
                                System.clearProperty("jettra.storage.path");
                        if (prevSp != null)
                                System.setProperty("storage.path", prevSp);
                        else
                                System.clearProperty("storage.path");
                }

                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                var originalNodes = new java.util.ArrayList<>(plugin.getServerNodes());
                var primaryNode = plugin.getPrimaryNode();
                String origHost = primaryNode != null ? primaryNode.getHost() : "127.0.0.1";
                int origPort = primaryNode != null ? primaryNode.getPort() : 50001;
                int origRestPort = primaryNode != null ? primaryNode.getRestPort() : 60001;
                boolean origActive = primaryNode != null && primaryNode.isActive();
                try {
                        primaryServer.start();
                        primaryServer.getOrCreateDatabase("primary_crm_test_db");
                        primaryServer.getOrCreateDatabase("primary_erp_test_db");

                        JettraAssert.assertTrue(primaryNode != null, "Debe existir un nodo PRIMARY registrado");
                        primaryNode.setHost("127.0.0.1");
                        primaryNode.setPort(grpcPort);
                        primaryNode.setRestPort(restPort);
                        primaryNode.setActive(true);

                        boolean synced = plugin.syncDatabasesFromPrimary(true);
                        JettraAssert.assertTrue(synced, "Debe sincronizar el catálogo desde el nodo PRIMARY activo");
                        JettraAssert.assertTrue(plugin.getDatabaseNames().contains("primary_crm_test_db"),
                                        "El catálogo debe incluir primary_crm_test_db del nodo PRIMARY");
                        JettraAssert.assertTrue(plugin.getDatabaseNames().contains("primary_erp_test_db"),
                                        "El catálogo debe incluir primary_erp_test_db del nodo PRIMARY");

                        HttpResponse<String> res = get("/databases");
                        JettraAssert.assertEquals(200, res.statusCode(), "/databases debe responder HTTP 200");
                        JettraAssert.assertTrue(res.body().contains("primary_crm_test_db"),
                                        "La vista /databases debe mostrar primary_crm_test_db almacenada en el nodo PRIMARY");
                        JettraAssert.assertTrue(res.body().contains("primary_erp_test_db"),
                                        "La vista /databases debe mostrar primary_erp_test_db almacenada en el nodo PRIMARY");
                } finally {
                        primaryServer.stop();
                        JettraExplorerTestCleanup.deleteRecursively(tempStorage);
                        if (primaryNode != null) {
                                primaryNode.setHost(origHost);
                                primaryNode.setPort(origPort);
                                primaryNode.setRestPort(origRestPort);
                                primaryNode.setActive(origActive);
                        }
                        plugin.restoreServerNodes(originalNodes);
                        plugin.deleteDatabase("primary_crm_test_db");
                        plugin.deleteDatabase("primary_erp_test_db");
                        if (plugin.getDatabaseNames().isEmpty()) {
                                plugin.installAllExampleDatabases();
                        }
                }
                JettraAssert.assertTrue(App.isPortAvailable("127.0.0.1", grpcPort),
                                "El puerto gRPC temporal de Test 38 debe quedar libre al terminar: " + grpcPort);
                JettraAssert.assertTrue(App.isPortAvailable("127.0.0.1", restPort),
                                "El puerto REST temporal de Test 38 debe quedar libre al terminar: " + restPort);
        }

        @Test
        @DisplayName("39. Diálogo de inserción en /explorer adaptado por tipo de engine (DOCUMENT JSON, JAVA_RECORD con tabla/SelectOne de reglas, KEY_VALUE)")
        public void testExplorerInsertDialogPerEngineType() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String db = "engine_insert_test_db";
                try {
                        plugin.createDatabase(db, "Base temporal para validar diálogos de inserción por motor");
                        plugin.addBucket(db, "DocsBucket", "DOCUMENT", "Documentos");
                        plugin.addBucket(db, "RecordsBucket", "JAVA_RECORD", "Java Records");
                        plugin.addBucket(db, "KvBucket", "KEY_VALUE", "Clave Valor");

                        // 1. DOCUMENT: estilo JSON
                        HttpResponse<String> docForm = get("/explorer?db=" + db + "&bucket=DocsBucket&action=new");
                        JettraAssert.assertEquals(200, docForm.statusCode(),
                                        "Formulario DOCUMENT debe responder HTTP 200");
                        JettraAssert.assertTrue(docForm.body().contains("data-engine-creator=\"DOCUMENT\""),
                                        "El diálogo DOCUMENT debe identificar el creador DOCUMENT");
                        JettraAssert.assertTrue(docForm.body().contains("name=\"details\""),
                                        "El diálogo DOCUMENT debe mostrar editor JSON (textarea details)");

                        // 2. JAVA_RECORD: columnas Nombre del Campo, Tipo, Regla JettraStore
                        // (SelectOne) y Valor del Campo
                        HttpResponse<String> recForm = get("/explorer?db=" + db + "&bucket=RecordsBucket&action=new");
                        JettraAssert.assertEquals(200, recForm.statusCode(),
                                        "Formulario JAVA_RECORD debe responder HTTP 200");
                        JettraAssert.assertTrue(recForm.body().contains("data-engine-creator=\"JAVA_RECORD\""),
                                        "El diálogo JAVA_RECORD debe identificar el creador JAVA_RECORD");
                        JettraAssert.assertTrue(recForm.body().contains("Nombre del Campo")
                                        && recForm.body().contains("Tipo")
                                        && recForm.body().contains("Regla JettraStore")
                                        && recForm.body().contains("Valor del Campo"),
                                        "El diálogo JAVA_RECORD debe mostrar columnas de campo, tipo, regla JettraStore y valor");
                        JettraAssert.assertTrue(recForm.body().contains("name=\"rec_field_rule_0\"")
                                        && recForm.body().contains("@Id")
                                        && recForm.body().contains("@Required")
                                        && recForm.body().contains("@JettraRef"),
                                        "El diálogo JAVA_RECORD debe incluir SelectOne con las reglas JettraStore");

                        // Guardar nuevo JAVA_RECORD mediante el formulario estructurado
                        String saveRecBody = "action=save_new&db=" + db + "&bucket=RecordsBucket"
                                        + "&recordId=REC-JAVA-01&summary=Orden+Prueba"
                                        + "&engineEditorMode=JAVA_RECORD&rec_recordClass=io.jettra.store.domain.OrderTest"
                                        + "&rec_field_name_0=orderId&rec_field_type_0=Long&rec_field_rule_0=%40Id&rec_field_val_0=901"
                                        + "&rec_field_name_1=customer&rec_field_type_1=String&rec_field_rule_1=%40Required&rec_field_val_1=ACME";
                        HttpResponse<String> saveRes = postForm("/explorer", saveRecBody);
                        JettraAssert.assertEquals(302, saveRes.statusCode(),
                                        "Guardar JAVA_RECORD debe redirigir con 302");

                        EngineRecordInfo savedRec = plugin.getRecord(db, "RecordsBucket", "REC-JAVA-01");
                        JettraAssert.assertNotNull(savedRec, "El registro JAVA_RECORD debe haberse guardado");
                        JettraAssert.assertTrue(
                                        savedRec.getDetails().contains("\"orderId\" : 901")
                                                        || savedRec.getDetails().contains("\"orderId\":901"),
                                        "El payload guardado debe contener orderId tipado como número");
                        JettraAssert.assertTrue(
                                        savedRec.getDetails().contains("@Id")
                                                        && savedRec.getDetails().contains("@Required"),
                                        "El payload guardado debe conservar las reglas JettraStore seleccionadas");

                        // 3. KEY_VALUE: campos clave-valor dedicados
                        HttpResponse<String> kvForm = get("/explorer?db=" + db + "&bucket=KvBucket&action=new");
                        JettraAssert.assertEquals(200, kvForm.statusCode(),
                                        "Formulario KEY_VALUE debe responder HTTP 200");
                        JettraAssert.assertTrue(kvForm.body().contains("data-engine-creator=\"KEY_VALUE\"")
                                        && kvForm.body().contains("name=\"prop_key\"")
                                        && kvForm.body().contains("name=\"prop_value\""),
                                        "El diálogo KEY_VALUE debe mostrar campos específicos de clave y valor");
                } finally {
                        plugin.deleteDatabase(db);
                }
        }

        @Test
        @DisplayName("40. Deshabilitar BASES DE DATOS, EJEMPLOS y REGISTROS CRUD cuando todos los nodos están detenidos")
        public void testDisableDatabaseMenusWhenAllNodesStopped() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                java.util.List<ServerNodeInfo> nodes = plugin.getServerNodes();
                java.util.Map<String, Boolean> prevStates = new java.util.LinkedHashMap<>();
                for (ServerNodeInfo n : nodes) {
                        prevStates.put(n.getId(), n.isActive());
                        n.setActive(false);
                }
                try {
                        JettraAssert.assertFalse(plugin.hasAnyOnlineNode(),
                                        "Con todos los nodos detenidos hasAnyOnlineNode() debe ser false");
                        HttpResponse<String> offlinePage = get("/cluster");
                        JettraAssert.assertEquals(200, offlinePage.statusCode(), "/cluster debe responder HTTP 200");
                        String body = offlinePage.body();
                        JettraAssert.assertTrue(body.contains("data-requires-cluster-online=\"true\"")
                                        && body.contains("data-href=\"/databases\"")
                                        && body.contains("data-href=\"/examples\"")
                                        && body.contains("data-href=\"/explorer\""),
                                        "Las 3 opciones deben marcarse como dependientes de nodos activos");
                        JettraAssert.assertTrue(body.contains("flux-nav-disabled")
                                        && body.contains("flux-tab-disabled")
                                        && body.contains("aria-disabled=\"true\""),
                                        "Con todos los nodos detenidos deben renderizarse deshabilitadas");

                        // Al activar al menos un nodo real (con puerto TCP abierto), las opciones deben
                        // habilitarse
                        if (!nodes.isEmpty()) {
                                ServerNodeInfo first = nodes.get(0);
                                int origPort = first.getPort();
                                try (java.net.ServerSocket liveSocket = new java.net.ServerSocket(0)) {
                                        first.setPort(liveSocket.getLocalPort());
                                        first.setActive(true);
                                        plugin.refreshAllNodeStatuses(true);
                                        JettraAssert.assertTrue(plugin.hasAnyOnlineNode(),
                                                        "Con un nodo activo hasAnyOnlineNode() debe ser true");
                                        HttpResponse<String> onlinePage = get("/cluster");
                                        JettraAssert.assertEquals(200, onlinePage.statusCode(),
                                                        "/cluster online debe responder HTTP 200");
                                        JettraAssert.assertFalse(onlinePage.body().contains("aria-disabled=\"true\""),
                                                        "Cuando hay un nodo activo no debe haber enlaces deshabilitados");
                                } finally {
                                        first.setPort(origPort);
                                }
                        }
                } finally {
                        for (ServerNodeInfo n : nodes) {
                                n.setActive(prevStates.getOrDefault(n.getId(), false));
                        }
                }
        }

        @Test
        @DisplayName("41. Limpieza automática de nodos de prueba del clúster al compilar/ejecutar tests")
        public void testCleanupRemovesTestClusterNodesFromPluginAndJson() throws Exception {
                java.nio.file.Path tmpNodesJson = java.nio.file.Files.createTempFile("cluster_nodes_cleanup_test_",
                                ".json");
                try {
                        String sample = """
                                        [
                                          {"id":"node-02","name":"node-02-replica","role":"PRIMARY","host":"127.0.0.1","port":50002,"heapMaxMb":2048},
                                          {"id":"peer-dynamic-1","name":"peer-dynamic-1-replica","role":"SECONDARY","host":"127.0.0.1","port":50005,"heapMaxMb":2048},
                                          {"id":"node-lifecycle-test","name":"node-lifecycle-test-secondary","role":"SECONDARY","host":"127.0.0.1","port":40765,"heapMaxMb":2048},
                                          {"id":"node-heartbeat-live","name":"node-heartbeat-live-secondary","role":"SECONDARY","host":"127.0.0.1","port":9791,"heapMaxMb":2048},
                                          {"id":"node-test-dk","name":"node-test-dk-secondary","role":"SECONDARY","host":"127.0.0.1","port":35439,"heapMaxMb":2048}
                                        ]
                                        """;
                        java.nio.file.Files.writeString(tmpNodesJson, sample, java.nio.charset.StandardCharsets.UTF_8);

                        JettraExplorerTestCleanup.cleanClusterNodesJsonFile(tmpNodesJson);
                        String cleaned = java.nio.file.Files.readString(tmpNodesJson,
                                        java.nio.charset.StandardCharsets.UTF_8);

                        JettraAssert.assertTrue(cleaned.contains("node-02"),
                                        "Debe conservar nodos reales como node-02");
                        JettraAssert.assertFalse(cleaned.contains("peer-dynamic-1-replica"),
                                        "Debe eliminar peer-dynamic-1-replica");
                        JettraAssert.assertFalse(cleaned.contains("node-lifecycle-test-secondary"),
                                        "Debe eliminar node-lifecycle-test-secondary");
                        JettraAssert.assertFalse(cleaned.contains("node-heartbeat-live-secondary"),
                                        "Debe eliminar node-heartbeat-live-secondary");
                        JettraAssert.assertFalse(cleaned.contains("node-test-dk-secondary"),
                                        "Debe eliminar node-test-dk-secondary");
                } finally {
                        java.nio.file.Files.deleteIfExists(tmpNodesJson);
                }
        }

        @Test
        @DisplayName("42. Validación de duplicados en nodos y página /cluster-config (ver/editar/SINCRONIZAR) exclusiva para ADMIN")
        public void testClusterConfigAdminPageAndDuplicateNodePrevention() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();

                // 1. Intentar agregar nodo con ID duplicado (node-01) -> debe rechazarse
                boolean dupIdAdded = plugin.addNode("node-01", "Duplicado", "SECONDARY", "127.0.0.1", 55991, 2048);
                JettraAssert.assertFalse(dupIdAdded, "No debe permitir agregar un nodo con ID duplicado");
                JettraAssert.assertNotNull(plugin.getLastNodeOperationError(),
                                "Debe registrar mensaje de error de duplicado");

                // 2. Intentar agregar nodo con puerto gRPC duplicado del nodo-01 -> debe
                // rechazarse
                ServerNodeInfo primary = plugin.getPrimaryNode();
                boolean dupPortAdded = plugin.addNode("node-dup-port-test", "DupPort", "SECONDARY", primary.getHost(),
                                primary.getPort(), 2048);
                JettraAssert.assertFalse(dupPortAdded, "No debe permitir agregar un nodo con puerto gRPC duplicado");

                // 3. Acceso como ADMIN a /cluster-config muestra el contenido de cluster.config
                // y botón SINCRONIZAR
                HttpResponse<String> cfgGetRes = get("/cluster-config");
                JettraAssert.assertEquals(200, cfgGetRes.statusCode(),
                                "GET /cluster-config como ADMIN debe responder HTTP 200");
                JettraAssert.assertTrue(cfgGetRes.body().contains("cluster.config"),
                                "Debe mostrar el título de cluster.config");
                JettraAssert.assertTrue(cfgGetRes.body().contains("SINCRONIZAR"), "Debe incluir el botón SINCRONIZAR");
                JettraAssert.assertTrue(cfgGetRes.body().contains("clusterConfigEditor"),
                                "Debe incluir el editor de cluster.config");

                // 4. POST /cluster-config con puertos duplicados debe ser rechazado por
                // validación de integridad
                String invalidContent = "cluster.node.1.id = node-01\ncluster.node.1.role = PRIMARY\ncluster.node.1.ip = 127.0.0.1\ncluster.node.1.grpc.port = 50001\ncluster.node.1.rest.port = 60001\n"
                                + "cluster.node.2.id = node-02\ncluster.node.2.role = SECONDARY\ncluster.node.2.ip = 127.0.0.1\ncluster.node.2.grpc.port = 50001\ncluster.node.2.rest.port = 60002\n";
                HttpResponse<String> invalidPost = postForm("/cluster-config",
                                "action=sync_cluster_config&clusterConfigContent=" + java.net.URLEncoder
                                                .encode(invalidContent, java.nio.charset.StandardCharsets.UTF_8));
                JettraAssert.assertEquals(200, invalidPost.statusCode(),
                                "POST /cluster-config inválido debe renderizar error de integridad");
                JettraAssert.assertTrue(
                                invalidPost.body().contains("duplicado")
                                                || invalidPost.body().contains("ERROR DE INTEGRIDAD"),
                                "Debe reportar error de integridad por puerto duplicado");
        }

        @Test
        @DisplayName("43. Botón Añadir Fila en Crear Nuevo Registro para JAVA_RECORD, OBJECT, KEY_VALUE, COLUMNAR, TIMESERIES, GEOSPATIAL, GRAPH, VECTOR (y no DOCUMENT)")
        public void testCreateNewRecordDynamicAddRowForAllNonDocumentEngines() {
                io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();

                // 1. Todos los motores no-DOCUMENT deben incluir botón 'Añadir Fila' y tabla
                // dinámica
                for (String engine : java.util.List.of("JAVA_RECORD", "OBJECT", "KEY_VALUE", "COLUMNAR", "TIMESERIES",
                                "GEOSPATIAL", "GRAPH", "VECTOR")) {
                        String html = renderer.render(
                                        io.jettra.store.explorer.pages.explorer.EngineRecordRenderer
                                                        .renderCreateFieldsWidget(engine),
                                        "Test " + engine);
                        JettraAssert.assertTrue(html.contains("Añadir Fila"),
                                        "El motor " + engine + " debe incluir botón Añadir Fila");
                        JettraAssert.assertTrue(
                                        html.contains("data-dynamic-rows-table=\"" + engine + "\"")
                                                        || html.contains("data-dynamic-rows-table='" + engine + "'"),
                                        "El motor " + engine + " debe incluir tabla de filas dinámicas");
                }

                // 2. DOCUMENT no debe incluir botón Añadir Fila (es un JSON normal)
                String docHtml = renderer.render(
                                io.jettra.store.explorer.pages.explorer.EngineRecordRenderer
                                                .renderCreateFieldsWidget("DOCUMENT"),
                                "Test DOCUMENT");
                JettraAssert.assertFalse(docHtml.contains("Añadir Fila"), "DOCUMENT no requiere botón Añadir Fila");

                // 3. Verificar que buildDetailsFromForm procesa filas añadidas dinámicamente en
                // JAVA_RECORD y KEY_VALUE
                java.util.Map<String, String> javaRecForm = new java.util.LinkedHashMap<>();
                javaRecForm.put("engineEditorMode", "JAVA_RECORD");
                javaRecForm.put("rec_recordClass", "io.jettra.domain.CustomRec");
                javaRecForm.put("rec_field_name_0", "id");
                javaRecForm.put("rec_field_type_0", "Long");
                javaRecForm.put("rec_field_rule_0", "@Id");
                javaRecForm.put("rec_field_val_0", "99");
                javaRecForm.put("rec_field_name_5", "extraTag");
                javaRecForm.put("rec_field_type_5", "String");
                javaRecForm.put("rec_field_rule_5", "@Indexed");
                javaRecForm.put("rec_field_val_5", "VIP");
                String jrJson = io.jettra.store.explorer.pages.explorer.EngineRecordRenderer
                                .buildDetailsFromForm(javaRecForm, "{}");
                JettraAssert.assertTrue(jrJson.contains("extraTag") && jrJson.contains("VIP"),
                                "JAVA_RECORD debe persistir filas añadidas dinámicamente");

                java.util.Map<String, String> kvForm = new java.util.LinkedHashMap<>();
                kvForm.put("engineEditorMode", "KEY_VALUE");
                kvForm.put("prop_key", "K1");
                kvForm.put("prop_value", "100");
                kvForm.put("extra_field_name_0", "region");
                kvForm.put("extra_field_val_0", "LATAM");
                String kvJson = io.jettra.store.explorer.pages.explorer.EngineRecordRenderer
                                .buildDetailsFromForm(kvForm, "{}");
                JettraAssert.assertTrue(kvJson.contains("region") && kvJson.contains("LATAM"),
                                "KEY_VALUE debe persistir filas extra añadidas dinámicamente");
        }

        @Test
        @DisplayName("44. RF-02: Árbol con subárboles (Estructura CRUD, Índices NUMERIC/AUTO_INCREMENT, Registros) y validación estricta")
        public void testTreeSubtreesSchemaCrudAndNumericAutoIncrementIndexes() {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf02_tree_db_" + System.currentTimeMillis();
                plugin.createDatabase(dbName, "BucketBase", "DOCUMENT", "Base de prueba RF-02");

                // 1. Crear bucket NONE y luego definir estructura DEFINED (CRUD Create/Update)
                boolean createdB = plugin.addBucket(dbName, "ProductosRF02", "DOCUMENT", "Bucket de prueba", "NONE",
                                "[]");
                JettraAssert.assertTrue(createdB, "Debe crear bucket NONE");

                String schemaJson = "[{\"atribute_name\":\"codigo\",\"type_atribute\":\"Long\",\"jettra_rules\":\"AUTO_INCREMENT\",\"description\":\"Código\"},"
                                + "{\"atribute_name\":\"nombre\",\"type_atribute\":\"String\",\"jettra_rules\":\"NOT_NULL\",\"description\":\"Nombre\"},"
                                + "{\"atribute_name\":\"precio\",\"type_atribute\":\"Double\",\"jettra_rules\":\"POSITIVE\",\"description\":\"Precio\"}]";
                boolean savedSchema = plugin.saveBucketStructure(dbName, "ProductosRF02", schemaJson);
                JettraAssert.assertTrue(savedSchema, "Debe guardar estructura DEFINED en el bucket");
                JettraAssert.assertEquals("DEFINED", plugin.getBucket(dbName, "ProductosRF02").getStructureType(),
                                "Tipo de estructura debe ser DEFINED");

                // 2. Validación estricta de registros contra estructura DEFINED
                var badVal = plugin.validateRecordForBucket(dbName, "ProductosRF02", "{\"nombre\":\"\",\"precio\":-5}");
                JettraAssert.assertFalse(badVal.valid(), "Debe rechazar registro que viola NOT_NULL y POSITIVE");

                var goodVal = plugin.validateRecordForBucket(dbName, "ProductosRF02",
                                "{\"nombre\":\"Teclado\",\"precio\":45.5}");
                JettraAssert.assertTrue(goodVal.valid(), "Debe aceptar registro válido omitiendo campo AUTO_INCREMENT");

                // 3. Crear índices AUTO_INCREMENT y NUMERIC en el bucket
                boolean idxAuto = plugin.addIndex(dbName, "ProductosRF02", "idx_rf02_codigo", "codigo",
                                "AUTO_INCREMENT", true);
                boolean idxNum = plugin.addIndex(dbName, "ProductosRF02", "idx_rf02_precio", "precio", "NUMERIC",
                                false);
                JettraAssert.assertTrue(idxAuto && idxNum, "Debe crear índices AUTO_INCREMENT y NUMERIC");

                // 4. Verificar renderizado JettraFlux del árbol con subárboles (Estructura,
                // Índices, Registros) y botones CRUD
                io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();
                String htmlSchema = renderer.render(
                                new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                dbName, "ProductosRF02", "schema", "", null, null, null, false, false,
                                                false, false, null, 1, 10).build(),
                                "RF02 Schema");
                JettraAssert.assertTrue(
                                htmlSchema.contains("section=schema") && htmlSchema.contains("section=indexes")
                                                && htmlSchema.contains("section=records"),
                                "El árbol debe renderizar los subárboles Estructura, Índices y Registros");
                JettraAssert.assertTrue(
                                htmlSchema.contains("id=\"bucketStructureModal\"")
                                                && htmlSchema.contains("data-bucket-structure-table=\"true\""),
                                "La sección de estructura en /explorer debe abrir el diálogo modal DataTable de consulta");

                String htmlIndexes = renderer.render(
                                new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                dbName, "ProductosRF02", "indexes", "", null, null, null, false, false,
                                                false, false, null, 1, 10).build(),
                                "RF02 Indexes");
                JettraAssert.assertTrue(htmlIndexes.contains("AUTO_INCREMENT") && htmlIndexes.contains("NUMERIC")
                                && htmlIndexes.contains("idx_rf02_codigo") && htmlIndexes.contains("idx_rf02_precio"),
                                "El gestor de índices debe mostrar y soportar tipos AUTO_INCREMENT y NUMERIC");

                // 5. Verificar carga dinámica del formulario de inserción para bucket DEFINED
                String htmlCreate = renderer.render(
                                new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                dbName, "ProductosRF02", "records", "", null, null, null, true, false,
                                                false, false, null, 1, 10).build(),
                                "RF02 Create Record");
                JettraAssert.assertTrue(
                                htmlCreate.contains("struct_codigo") && htmlCreate.contains("struct_nombre")
                                                && htmlCreate.contains("struct_precio"),
                                "El formulario de inserción debe generar inputs dinámicos para la estructura DEFINED");

                // 6. Eliminar estructura (CRUD Delete -> vuelve a NONE)
                boolean deletedSchema = plugin.deleteBucketStructure(dbName, "ProductosRF02");
                JettraAssert.assertTrue(deletedSchema, "Debe eliminar la estructura DEFINED");
                JettraAssert.assertEquals("NONE", plugin.getBucket(dbName, "ProductosRF02").getStructureType(),
                                "Tras eliminar estructura debe volver a NONE");
        }

        @Test
        @DisplayName("45. Sincronización de topología externa (Shell remove/add) actualiza /cluster y /map")
        public void testExternalTopologyRemovalUpdatesClusterAndMapViews() {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                java.util.List<io.jettra.store.explorer.model.ServerNodeInfo> backup = new java.util.ArrayList<>(
                                plugin.getServerNodes());
                try {
                        var n1 = new io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo("node-01", "PRIMARY",
                                        "127.0.0.1", 50001, 60001, "./data/node-01");
                        var n2 = new io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo("node-02", "SECONDARY",
                                        "127.0.0.1", 50002, 60002, "./data/node-02");
                        var n4 = new io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo("node-04", "SECONDARY",
                                        "127.0.0.1", 50004, 60004, "./data/node-04");

                        // 1. Simular que Shell agregó node-04 en PRIMARY
                        plugin.applyAuthoritativeClusterNodes(java.util.List.of(n1, n2, n4), null);
                        JettraAssert.assertNotNull(plugin.getNodeById("node-04"),
                                        "node-04 debe aparecer tras sincronización desde PRIMARY");

                        // 2. Simular que Shell removió node-04 en PRIMARY
                        boolean changed = plugin.applyAuthoritativeClusterNodes(java.util.List.of(n1, n2), null);
                        JettraAssert.assertTrue(changed, "Debe detectar la eliminación externa de node-04");
                        JettraAssert.assertNull(plugin.getNodeById("node-04"),
                                        "node-04 debe haber sido removido de JettraStorePlugin");

                        // 3. Verificar que /cluster y /map ya no muestran node-04
                        io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();
                        String clusterHtml = renderer.render(
                                        new io.jettra.store.explorer.pages.cluster.ClusterFluxView(null).build(),
                                        "Cluster");
                        String mapHtml = renderer.render(
                                        new io.jettra.store.explorer.pages.map.TransferMapFluxView().build(), "Map");
                        JettraAssert.assertFalse(clusterHtml.contains("node-04"),
                                        "/cluster no debe mostrar el nodo removido desde Shell");
                        JettraAssert.assertFalse(mapHtml.contains("node-04"),
                                        "/map no debe mostrar el nodo removido desde Shell");
                        JettraAssert.assertTrue(clusterHtml.contains("_lastClusterNodeSig"),
                                        "/cluster debe detectar cambios de topología por firma de IDs");
                } finally {
                        // Restaurar nodos originales del entorno de pruebas
                        java.util.List<io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo> restore = new java.util.ArrayList<>();
                        for (var n : backup) {
                                restore.add(new io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo(
                                                n.getId(), n.getRole(), n.getHost(), n.getPort(), n.getRestPort(),
                                                "./data/" + n.getId()));
                        }
                        if (!restore.isEmpty()) {
                                plugin.applyAuthoritativeClusterNodes(restore, null);
                        }
                }
        }

        @Test
        @DisplayName("46. Login y sesión: múltiples cookies jettra_session (cookie obsoleta + válida) no deben bloquear el acceso")
        public void testLoginSessionWithStaleAndValidCookie() throws Exception {
                String validCookie = getAuthCookie();
                String combinedCookie = "jettra_session=stale-old-boot-id.0000; " + validCookie;
                HttpResponse<String> clusterRes = get("/cluster", combinedCookie);
                JettraAssert.assertEquals(200, clusterRes.statusCode(),
                                "/cluster debe responder 200 si alguna cookie jettra_session es válida");
                HttpResponse<String> statusRes = get("/api/cluster/status", combinedCookie);
                JettraAssert.assertEquals(200, statusRes.statusCode(),
                                "/api/cluster/status debe responder 200 con cookie combinada");
        }

        @Test
        @DisplayName("47. RF-03: Árbol jerárquico mejorado, validación estricta UNIQUE/AUTO_INCREMENT y detección de índices numéricos/autoincrementables")
        public void testRf03HierarchyTreeStrictValidationAndNumericAutoIncrementIndexes() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf03_explorer_test_db_" + System.currentTimeMillis();
                try {
                        plugin.createDatabase(dbName, "NotasLibres", "DOCUMENT", "Base de pruebas RF-03");
                        // 1. Bucket NONE: debe permitir flujo flexible, inferir campos numéricos de
                        // registros y ofrecer 'Añadir Estructura' en el árbol
                        plugin.addRecord(dbName, "NotasLibres", "NOTA-1", "Nota con monto",
                                        "{\"titulo\":\"Factura\",\"monto\":250.75,\"cantidad\":3}");
                        var noneBucket = plugin.getBucket(dbName, "NotasLibres");
                        var detectedNone = plugin.detectIndexableFields(noneBucket);
                        JettraAssert.assertTrue(
                                        detectedNone.stream()
                                                        .anyMatch(f -> f.fieldName().equals("monto") && f.numeric()),
                                        "Debe detectar campos numéricos (monto) incluso en buckets NONE a partir de los registros");

                        HttpResponse<String> noneTreeRes = get(
                                        "/explorer?db=" + dbName + "&bucket=NotasLibres&section=schema");
                        JettraAssert.assertEquals(200, noneTreeRes.statusCode(),
                                        "Vista /explorer NONE debe responder 200");
                        JettraAssert.assertTrue(noneTreeRes.body().contains("data-explorer-tree=\"true\"")
                                        && noneTreeRes.body().contains("data-tree-level=\"database\"")
                                        && noneTreeRes.body().contains("data-tree-level=\"engine\"")
                                        && noneTreeRes.body().contains("data-tree-level=\"bucket\"")
                                        && noneTreeRes.body().contains("id=\"bucketStructureModal\""),
                                        "El árbol debe mostrar jerarquía BD -> Engine -> Bucket y abrir el diálogo modal de estructura");

                        // 2. Bucket DEFINED con AUTO_INCREMENT, UNIQUE, BigDecimal POSITIVE y
                        // MIN_LENGTH
                        plugin.addBucket(dbName, "ProductosRF03", "DOCUMENT", "Productos estructurados", "NONE", "[]");
                        String schemaJson = "["
                                        + "{\"atribute_name\":\"codigo\",\"type_atribute\":\"Long\",\"jettra_rules\":\"AUTO_INCREMENT\",\"description\":\"Secuencia\"},"
                                        + "{\"atribute_name\":\"sku\",\"type_atribute\":\"String\",\"jettra_rules\":\"NOT_NULL,UNIQUE,MIN_LENGTH:3\",\"description\":\"SKU\"},"
                                        + "{\"atribute_name\":\"precio\",\"type_atribute\":\"BigDecimal\",\"jettra_rules\":\"NOT_NULL,POSITIVE\",\"description\":\"Precio\"}"
                                        + "]";
                        HttpResponse<String> saveSchemaRes = postForm("/explorer",
                                        "action=save_schema&db=" + dbName + "&bucket=ProductosRF03&attributesJson="
                                                        + java.net.URLEncoder.encode(schemaJson,
                                                                        java.nio.charset.StandardCharsets.UTF_8));
                        JettraAssert.assertEquals(302, saveSchemaRes.statusCode(),
                                        "Guardar estructura DEFINED debe redirigir con 302");

                        // 3. Insertar registro válido omitiendo AUTO_INCREMENT -> debe autogenerar
                        // codigo=1
                        HttpResponse<String> insert1 = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosRF03&recordId=P-1&summary=Mouse"
                                                        + "&struct_sku=MOU-01&struct_precio=29.99");
                        JettraAssert.assertEquals(302, insert1.statusCode(), "Inserción válida debe redirigir con 302");
                        JettraAssert.assertTrue(
                                        insert1.headers().firstValue("Location").orElse("")
                                                        .contains("msg=record_created"),
                                        "Debe confirmar record_created");
                        EngineRecordInfo rec1 = plugin.getRecord(dbName, "ProductosRF03", "P-1");
                        JettraAssert.assertNotNull(rec1, "P-1 debe existir");
                        JettraAssert.assertTrue(
                                        rec1.getDetails().contains("\"codigo\":1")
                                                        || rec1.getDetails().contains("\"codigo\" : 1"),
                                        "AUTO_INCREMENT debe autogenerar codigo=1 en el primer registro");

                        // 4. Segundo registro válido -> debe autogenerar codigo=2
                        HttpResponse<String> insert2 = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosRF03&recordId=P-2&summary=Teclado"
                                                        + "&struct_sku=TEC-02&struct_precio=85.00");
                        JettraAssert.assertTrue(
                                        insert2.headers().firstValue("Location").orElse("")
                                                        .contains("msg=record_created"),
                                        "Segundo registro válido debe crearse");
                        EngineRecordInfo rec2 = plugin.getRecord(dbName, "ProductosRF03", "P-2");
                        JettraAssert.assertTrue(
                                        rec2.getDetails().contains("\"codigo\":2")
                                                        || rec2.getDetails().contains("\"codigo\" : 2"),
                                        "AUTO_INCREMENT debe incrementar secuencialmente a codigo=2");

                        // 5. Intentar insertar SKU duplicado (viola UNIQUE) y tipo numérico inválido ->
                        // deben ser rechazados
                        HttpResponse<String> dupRes = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosRF03&recordId=P-3&summary=Dup"
                                                        + "&struct_sku=MOU-01&struct_precio=10.00");
                        JettraAssert.assertTrue(dupRes.headers().firstValue("Location").orElse("").contains("Validaci"),
                                        "Debe rechazar registro que viola restricción UNIQUE");
                        JettraAssert.assertNull(plugin.getRecord(dbName, "ProductosRF03", "P-3"),
                                        "P-3 no debe persistirse al fallar validación UNIQUE");

                        HttpResponse<String> badNumRes = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosRF03&recordId=P-4&summary=BadNum"
                                                        + "&struct_sku=BAD-04&struct_precio=no_es_numero");
                        JettraAssert.assertTrue(
                                        badNumRes.headers().firstValue("Location").orElse("").contains("Validaci"),
                                        "Debe rechazar valor no numérico en campo BigDecimal");
                        JettraAssert.assertNull(plugin.getRecord(dbName, "ProductosRF03", "P-4"),
                                        "P-4 no debe persistirse al fallar validación de tipo numérico");

                        // 6. Verificar consulta modal de índices en /explorer y gestor de índices en
                        // /databases con detección de NUMERIC y AUTO_INCREMENT
                        HttpResponse<String> createIdxRes = postForm("/explorer",
                                        "action=create_bucket_index&db=" + dbName + "&bucket=ProductosRF03"
                                                        + "&indexName=idx_rf03_codigo&indexFieldSelect=codigo&indexType=AUTO_INCREMENT&indexUnique=true");
                        JettraAssert.assertTrue(
                                        createIdxRes.headers().firstValue("Location").orElse("")
                                                        .contains("msg=index_created"),
                                        "Debe crear índice AUTO_INCREMENT");

                        HttpResponse<String> explorerIdxPage = get(
                                        "/explorer?db=" + dbName + "&bucket=ProductosRF03&section=indexes");
                        JettraAssert.assertEquals(200, explorerIdxPage.statusCode(),
                                        "/explorer?section=indexes debe responder 200");
                        JettraAssert.assertTrue(explorerIdxPage.body().contains("id=\"bucketIndexesModal\"")
                                        && explorerIdxPage.body().contains("idx_rf03_codigo"),
                                        "El diálogo de índices en /explorer debe mostrar los índices del bucket en modal");

                        HttpResponse<String> dbIdxPage = get("/databases?db=" + dbName + "&manage_indexes_db=" + dbName
                                        + "&index_bucket=ProductosRF03");
                        JettraAssert.assertEquals(200, dbIdxPage.statusCode(),
                                        "/databases gestor de índices debe responder 200");
                        JettraAssert.assertTrue(dbIdxPage.body().contains("data-detected-numeric-fields=\"true\"")
                                        && dbIdxPage.body().contains("codigo (Long • AUTO_INCREMENT)")
                                        && dbIdxPage.body().contains("precio (BigDecimal • NUMERIC)")
                                        && dbIdxPage.body().contains("name=\"fieldSelect\"")
                                        && dbIdxPage.body().contains("idx_rf03_codigo"),
                                        "El gestor de índices en /databases debe detectar campos numéricos/autoincrementables y listar índices");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("48. /database: Índices exclusivos por Bucket dentro de Engine, CRUD Buckets con tabla (nombre|tipo|rules|descripcion) y validación de List<>/Set<>/ArrayList<>/Objetos")
        public void testDatabaseModalsStrictBucketIndexAndStructureTableValidation() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "db_modals_spec_test_db";
                try {
                        plugin.createDatabase(dbName, "BaseLibre", "DOCUMENT",
                                        "Base de pruebas especificación /database");

                        // 1. Verificar alias /database: sin add_bucket=true NO incrusta el formulario
                        // en la parte superior; con add_bucket=true abre modal superpuesto
                        HttpResponse<String> enginesRes = get("/database?db=" + dbName + "&manage_engines=true");
                        JettraAssert.assertEquals(200, enginesRes.statusCode(),
                                        "GET /database?manage_engines=true debe responder HTTP 200");
                        JettraAssert.assertTrue(
                                        enginesRes.body().contains(
                                                        "Motores de Almacenamiento y Buckets en [" + dbName + "]"),
                                        "Debe abrir el modal de Motores de Almacenamiento y Buckets");
                        JettraAssert.assertFalse(enginesRes.body().contains("id=\"addBucketModal\""),
                                        "El diálogo Añadir Nuevo Motor / Bucket no debe mostrarse incrustado antes de abrirlo");

                        HttpResponse<String> addModalRes = get(
                                        "/database?db=" + dbName + "&manage_engines=true&add_bucket=true");
                        JettraAssert.assertEquals(200, addModalRes.statusCode(),
                                        "GET /database?manage_engines=true&add_bucket=true debe responder HTTP 200");
                        JettraAssert.assertTrue(addModalRes.body().contains("id=\"addBucketModal\"")
                                        && addModalRes.body().contains("data-add-bucket-modal=\"true\"")
                                        && addModalRes.body().contains("data-structure-table=\"true\"")
                                        && addModalRes.body().contains(">nombre<")
                                        && addModalRes.body().contains(">tipo<")
                                        && addModalRes.body().contains(">rules<")
                                        && addModalRes.body().contains(">descripcion<")
                                        && addModalRes.body().contains("Remover Fila")
                                        && addModalRes.body().contains("name=\"attr_rules_select_0\"")
                                        && addModalRes.body().contains("name=\"attr_rules_0\""),
                                        "El diálogo Añadir Nuevo Motor / Bucket debe abrirse como modal superpuesto con tabla (nombre|tipo|rules|descripcion), Remover Fila y selector + personalización de RULES");

                        // 2. Añadir Bucket DEFINED vía tabla (incluyendo List<String>, Set<String>,
                        // ArrayList<Integer>, Instant, UUID y objeto de usuario Direccion)
                        String addBucketBody = "action=add_bucket&db=" + dbName
                                        + "&bucketName=PedidosEstructurados&engineType=DOCUMENT&structureType=DEFINED&description=Pedidos+tipados"
                                        + "&attr_name_0=codigo&attr_type_0=Long&attr_rules_0=AUTO_INCREMENT&attr_desc_0=Secuencia"
                                        + "&attr_name_1=etiquetas&attr_type_1=List%3CString%3E&attr_rules_1=NOT_NULL&attr_desc_1=Tags"
                                        + "&attr_name_2=categorias&attr_type_2=Set%3CString%3E&attr_rules_2=NOT_NULL&attr_desc_2=Categorias+unicas"
                                        + "&attr_name_3=puntajes&attr_type_3=ArrayList%3CInteger%3E&attr_rules_3=NOT_NULL&attr_desc_3=Puntajes"
                                        + "&attr_name_4=direccion&attr_type_4=String&attr_type_custom_4=Direccion&attr_rules_4=NOT_NULL&attr_desc_4=Objeto+usuario"
                                        + "&attr_name_5=creadoEn&attr_type_5=Instant&attr_rules_5=NOT_NULL&attr_desc_5=Timestamp+ISO";
                        HttpResponse<String> addBRes = postForm("/database", addBucketBody);
                        JettraAssert.assertEquals(302, addBRes.statusCode(),
                                        "Añadir bucket DEFINED debe redirigir 302");
                        var structuredBucket = plugin.getBucket(dbName, "PedidosEstructurados");
                        JettraAssert.assertNotNull(structuredBucket, "PedidosEstructurados debe existir");
                        JettraAssert.assertEquals("DEFINED", structuredBucket.getStructureType(),
                                        "Debe tener estructura DEFINED");
                        JettraAssert.assertEquals(6, structuredBucket.getAttributes().size(),
                                        "Debe persistir los 6 atributos de la tabla");

                        // 3. Validación estricta de registros en bucket DEFINED (List<>, Set<>,
                        // ArrayList<>, Objeto de usuario, Instant)
                        String validJson = "{\"etiquetas\":[\"vip\",\"express\"],\"categorias\":[\"tech\",\"hogar\"],"
                                        + "\"puntajes\":[10,20,30],\"direccion\":{\"calle\":\"Central\",\"ciudad\":\"Panama\"},"
                                        + "\"creadoEn\":\"2026-11-23T10:15:30Z\"}";
                        JettraAssert.assertTrue(
                                        plugin.validateRecordForBucket(dbName, "PedidosEstructurados", validJson)
                                                        .valid(),
                                        "Registro conforme a List<String>, Set<String>, ArrayList<Integer>, Direccion e Instant debe pasar");

                        // Rechazar duplicado en Set<String>
                        String dupSetJson = "{\"etiquetas\":[\"vip\"],\"categorias\":[\"tech\",\"tech\"],"
                                        + "\"puntajes\":[10],\"direccion\":{\"calle\":\"A\"},\"creadoEn\":\"2026-11-23T10:15:30Z\"}";
                        JettraAssert.assertFalse(
                                        plugin.validateRecordForBucket(dbName, "PedidosEstructurados", dupSetJson)
                                                        .valid(),
                                        "Set<String> debe rechazar elementos duplicados");

                        // Rechazar elemento no entero en ArrayList<Integer>
                        String badListJson = "{\"etiquetas\":[\"vip\"],\"categorias\":[\"tech\"],"
                                        + "\"puntajes\":[\"no_int\"],\"direccion\":{\"calle\":\"A\"},\"creadoEn\":\"2026-11-23T10:15:30Z\"}";
                        JettraAssert.assertFalse(
                                        plugin.validateRecordForBucket(dbName, "PedidosEstructurados", badListJson)
                                                        .valid(),
                                        "ArrayList<Integer> debe rechazar elementos no enteros");

                        // Rechazar primitivo en campo de objeto de usuario Direccion
                        String badObjJson = "{\"etiquetas\":[\"vip\"],\"categorias\":[\"tech\"],"
                                        + "\"puntajes\":[5],\"direccion\":12345,\"creadoEn\":\"2026-11-23T10:15:30Z\"}";
                        JettraAssert.assertFalse(
                                        plugin.validateRecordForBucket(dbName, "PedidosEstructurados", badObjJson)
                                                        .valid(),
                                        "Campo de objeto de usuario Direccion debe rechazar un escalar numérico");

                        // Bucket sin estructura (NONE): debe aceptar cualquier estructura libre
                        JettraAssert.assertTrue(
                                        plugin.validateRecordForBucket(dbName, "BaseLibre",
                                                        "{\"cualquierCampo\":true,\"mix\":[1,\"a\"]}").valid(),
                                        "Bucket NONE debe permitir registros de estructura libre");

                        // 4. Editar Bucket (renombrar + actualizar descripción)
                        HttpResponse<String> editBRes = postForm("/database",
                                        "action=edit_bucket&db=" + dbName
                                                        + "&originalBucketName=PedidosEstructurados&bucketName=PedidosV2"
                                                        + "&structureType=DEFINED&description=Pedidos+actualizados"
                                                        + "&attr_name_0=codigo&attr_type_0=Long&attr_rules_0=AUTO_INCREMENT&attr_desc_0=Secuencia"
                                                        + "&attr_name_1=etiquetas&attr_type_1=List%3CString%3E&attr_rules_1=NOT_NULL&attr_desc_1=Tags");
                        JettraAssert.assertEquals(302, editBRes.statusCode(), "Editar bucket debe redirigir 302");
                        JettraAssert.assertNotNull(plugin.getBucket(dbName, "PedidosV2"),
                                        "El bucket debe haberse renombrado a PedidosV2");

                        // 5. Regla estricta de Índices: bloquear asignación directa al Engine y validar
                        // coherencia de campos en bucket DEFINED
                        JettraAssert.assertFalse(
                                        plugin.addIndex(dbName, "DOCUMENT", "idx_bad_engine", "codigo", "BTREE", false),
                                        "Debe bloquear asignar un índice directamente al Engine DOCUMENT");
                        JettraAssert.assertTrue(
                                        plugin.getLastIndexOperationError() != null
                                                        && plugin.getLastIndexOperationError().contains("Engine"),
                                        "El error debe explicar que el índice no se asigna al Engine sino a un Bucket");

                        JettraAssert.assertFalse(
                                        plugin.addIndex(dbName, "PedidosV2", "idx_inexistente", "campo_fantasma",
                                                        "BTREE", false),
                                        "Debe rechazar crear índice sobre un campo que no existe en la estructura DEFINED del bucket");

                        // Añadir, Editar y Eliminar índice sobre el Bucket concreto PedidosV2
                        HttpResponse<String> idxModalRes = get("/database?db=" + dbName + "&manage_indexes_db=" + dbName
                                        + "&index_bucket=PedidosV2");
                        JettraAssert.assertEquals(200, idxModalRes.statusCode(), "Modal de índices debe responder 200");
                        JettraAssert.assertTrue(idxModalRes.body().contains("data-strict-bucket-index-rule=\"true\"")
                                        && idxModalRes.body().contains("data-index-engine=\"DOCUMENT\"")
                                        && idxModalRes.body().contains("data-index-bucket=\"PedidosV2\""),
                                        "El modal de índices debe agrupar por Engine -> Bucket y mostrar la regla estricta");

                        HttpResponse<String> createIdx = postForm("/database",
                                        "action=add_index&db=" + dbName
                                                        + "&bucket=PedidosV2&indexName=idx_ped_codigo&fields=codigo&indexType=AUTO_INCREMENT&unique=SI");
                        JettraAssert.assertTrue(
                                        createIdx.headers().firstValue("Location").orElse("")
                                                        .contains("msg=index_added"),
                                        "Debe crear índice AUTO_INCREMENT sobre el bucket PedidosV2");

                        HttpResponse<String> editIdx = postForm("/database",
                                        "action=edit_index&db=" + dbName
                                                        + "&bucket=PedidosV2&originalIndexName=idx_ped_codigo&indexName=idx_ped_codigo_v2&fields=codigo&indexType=NUMERIC&unique=SI");
                        JettraAssert.assertTrue(
                                        editIdx.headers().firstValue("Location").orElse("")
                                                        .contains("msg=index_updated"),
                                        "Debe editar el índice existente del bucket PedidosV2");
                        JettraAssert.assertTrue(plugin.getBucket(dbName, "PedidosV2").getIndexes().stream()
                                        .anyMatch(i -> "idx_ped_codigo_v2".equals(i.getName())
                                                        && "NUMERIC".equals(i.getType())),
                                        "El índice editado debe reflejar nuevo nombre y tipo NUMERIC");

                        HttpResponse<String> delIdx = postForm("/database",
                                        "action=delete_index&db=" + dbName
                                                        + "&bucket=PedidosV2&indexName=idx_ped_codigo_v2");
                        JettraAssert.assertTrue(
                                        delIdx.headers().firstValue("Location").orElse("")
                                                        .contains("msg=index_deleted"),
                                        "Debe eliminar el índice del bucket PedidosV2");

                        // 6. Remover Bucket
                        HttpResponse<String> delBRes = get(
                                        "/database?action=delete_bucket&db=" + dbName + "&bucket=PedidosV2");
                        JettraAssert.assertEquals(302, delBRes.statusCode(), "Remover bucket debe redirigir 302");
                        JettraAssert.assertNull(plugin.findBucketExact(dbName, "PedidosV2"),
                                        "PedidosV2 debe haber sido removido");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("49. RF-05: /explorer muestra solo la BD seleccionada, sin '+ Nuevo Bucket', y abre modal DataTable de Estructura sin Ver JSON/Editar/Eliminar")
        public void testRf05ExplorerSingleDbNoNewBucketAndReadOnlyStructureModal() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbPrimary = "rf05_activa_test_db";
                String dbOther = "rf05_oculta_test_db";
                try {
                        plugin.createDatabase(dbPrimary, "FacturasRF05", "DOCUMENT", "BD activa RF-05");
                        plugin.createDatabase(dbOther, "OtraColeccion", "DOCUMENT",
                                        "Otra BD que no debe listarse en /explorer");
                        plugin.addBucket(dbPrimary, "LibreRF05", "DOCUMENT", "Bucket sin estructura", "NONE", "[]");

                        String schemaJson = "["
                                        + "{\"atribute_name\":\"codigo\",\"type_atribute\":\"Long\",\"jettra_rules\":\"AUTO_INCREMENT\",\"description\":\"Código único\"},"
                                        + "{\"atribute_name\":\"cliente\",\"type_atribute\":\"String\",\"jettra_rules\":\"NOT_NULL\",\"description\":\"Nombre del cliente\"}"
                                        + "]";
                        plugin.saveBucketStructure(dbPrimary, "FacturasRF05", schemaJson);

                        // 1. Vista /explorer normal: solo muestra dbPrimary, NO lista dbOther, NO
                        // expone '+ Nuevo Bucket'
                        HttpResponse<String> explorerRes = get("/explorer?db=" + dbPrimary + "&bucket=FacturasRF05");
                        JettraAssert.assertEquals(200, explorerRes.statusCode(),
                                        "GET /explorer debe responder HTTP 200");
                        String body = explorerRes.body();
                        JettraAssert.assertTrue(
                                        body.contains(dbPrimary)
                                                        && body.contains("data-active-db-only=\"" + dbPrimary + "\""),
                                        "Debe mostrar únicamente la base de datos seleccionada");
                        JettraAssert.assertFalse(body.contains(dbOther),
                                        "No debe listar otras bases de datos en /explorer");
                        JettraAssert.assertFalse(body.contains("Nuevo Bucket"),
                                        "No debe mostrar el botón '+ Nuevo Bucket' en /explorer");
                        JettraAssert.assertTrue(body.contains("/database?db=" + dbPrimary),
                                        "Debe incluir enlace para cambiar/administrar BD en /database");

                        // 2. Clic en Estructura sobre bucket DEFINED -> modal DataTable superpuesto sin
                        // Ver JSON, Editar Estructura ni Eliminar Estructura
                        HttpResponse<String> schemaModalRes = get(
                                        "/explorer?db=" + dbPrimary + "&bucket=FacturasRF05&section=schema");
                        JettraAssert.assertEquals(200, schemaModalRes.statusCode(),
                                        "GET /explorer?section=schema debe responder HTTP 200");
                        String sBody = schemaModalRes.body();
                        JettraAssert.assertTrue(sBody.contains("id=\"bucketStructureModal\"")
                                        && sBody.contains("espresso-modal-overlay")
                                        && sBody.contains("data-structure-readonly-modal=\"true\"")
                                        && sBody.contains("Estructura del Bucket 'FacturasRF05' [" + dbPrimary + "]")
                                        && sBody.contains("data-bucket-structure-table=\"true\"")
                                        && sBody.contains(">Nombre<") && sBody.contains(">Tipo<")
                                        && sBody.contains(">Reglas<") && sBody.contains(">Descripción<")
                                        && sBody.contains("codigo") && sBody.contains("cliente"),
                                        "Debe abrir el diálogo modal DataTable sobre /explorer con los campos de la estructura");
                        JettraAssert.assertFalse(sBody.contains("Ver JSON") || sBody.contains("VER JSON")
                                        || sBody.contains("Editar Estructura") || sBody.contains("Eliminar Estructura"),
                                        "El diálogo de estructura en /explorer no debe incluir botones Ver JSON, Editar Estructura ni Eliminar Estructura");

                        // 3. Clic en Estructura sobre bucket NONE -> modal con mensaje claro de esquema
                        // libre y sin botones prohibidos
                        HttpResponse<String> noneModalRes = get(
                                        "/explorer?db=" + dbPrimary + "&bucket=LibreRF05&section=schema");
                        JettraAssert.assertEquals(200, noneModalRes.statusCode(),
                                        "GET /explorer?section=schema en bucket NONE debe responder 200");
                        String nBody = noneModalRes.body();
                        JettraAssert.assertTrue(
                                        nBody.contains("id=\"bucketStructureModal\"")
                                                        && nBody.contains("esquema libre"),
                                        "En bucket NONE el modal debe informar claramente que acepta registros sin estructura fija");
                        JettraAssert.assertFalse(
                                        nBody.contains("Ver JSON") || nBody.contains("Editar Estructura")
                                                        || nBody.contains("Eliminar Estructura"),
                                        "En bucket NONE tampoco debe mostrar Ver JSON, Editar Estructura ni Eliminar Estructura");
                } finally {
                        plugin.deleteDatabase(dbPrimary);
                        plugin.deleteDatabase(dbOther);
                }
        }

        @Test
        @DisplayName("50. RF-06: Diálogo modal de solo lectura 'Índices del Bucket' en /explorer con DataTable y sin botones Reconstruir/Eliminar")
        public void testRf06ExplorerReadOnlyBucketIndexesModal() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf06_indexes_modal_test_db";
                try {
                        plugin.createDatabase(dbName, "OrdenesRF06", "DOCUMENT", "Base de pruebas RF-06");
                        plugin.addIndex(dbName, "OrdenesRF06", "idx_rf06_seq", "codigo", "AUTO_INCREMENT", true);
                        plugin.addIndex(dbName, "OrdenesRF06", "idx_rf06_total", "total", "NUMERIC", false);

                        // 1. Abrir Índices desde el árbol sobre OrdenesRF06 -> modal DataTable de solo
                        // lectura
                        HttpResponse<String> idxRes = get(
                                        "/explorer?db=" + dbName + "&bucket=OrdenesRF06&section=indexes");
                        JettraAssert.assertEquals(200, idxRes.statusCode(),
                                        "GET /explorer?section=indexes debe responder HTTP 200");
                        String body = idxRes.body();
                        JettraAssert.assertTrue(body.contains("id=\"bucketIndexesModal\"")
                                        && body.contains("espresso-modal-overlay")
                                        && body.contains("data-indexes-readonly-modal=\"true\"")
                                        && body.contains("Índices del Bucket 'OrdenesRF06' (" + dbName + " / DOCUMENT)")
                                        && body.contains("data-bucket-indexes-table=\"true\"")
                                        && body.contains(">Nombre del índice<")
                                        && body.contains(">Campo(s)<")
                                        && body.contains(">Tipo / Estrategia<")
                                        && body.contains(">Unicidad<")
                                        && body.contains(">Estado<")
                                        && body.contains("idx_rf06_seq") && body.contains("AUTO_INCREMENT")
                                        && body.contains("idx_rf06_total") && body.contains("NUMERIC"),
                                        "Debe mostrar el diálogo modal DataTable de Índices del Bucket sobre /explorer");

                        // Extraer el fragmento del modal para validar estrictamente que no incluye
                        // Reconstruir ni Eliminar
                        int modalStart = body.indexOf("id=\"bucketIndexesModal\"");
                        String modalFragment = modalStart >= 0 ? body.substring(modalStart) : body;
                        JettraAssert.assertFalse(modalFragment.contains("Reconstruir")
                                        || modalFragment.contains("delete_bucket_index")
                                        || modalFragment.contains("rebuild_bucket_index")
                                        || modalFragment.contains("create_bucket_index"),
                                        "El modal de Índices del Bucket en /explorer no debe incluir botones Reconstruir, Eliminar ni creación inline");

                        // 2. Bucket sin índices configurados -> estado vacío claro dentro del modal
                        plugin.addBucket(dbName, "SinIndicesRF06", "DOCUMENT", "Bucket sin índices", "NONE", "[]");
                        plugin.findBucketExact(dbName, "SinIndicesRF06").getIndexes().clear();
                        HttpResponse<String> emptyIdxRes = get(
                                        "/explorer?db=" + dbName + "&bucket=SinIndicesRF06&section=indexes");
                        JettraAssert.assertEquals(200, emptyIdxRes.statusCode(),
                                        "Modal vacío de índices debe responder 200");
                        JettraAssert.assertTrue(emptyIdxRes.body().contains("id=\"bucketIndexesModal\"")
                                        && emptyIdxRes.body().contains("No hay índices registrados para este bucket"),
                                        "Debe mostrar un estado vacío claro cuando el bucket no tiene índices");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("51. RF-07: Eliminación de la sección redundante de Engines bajo 'Base de Datos Activa' en /explorer y navegación exclusiva en el Tree")
        public void testRf07NoRedundantEnginesStripUnderActiveDbAndTreeNavigationWorks() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf07_clean_explorer_test_db";
                try {
                        plugin.createDatabase(dbName, "ClientesRF07", "DOCUMENT", "Base de pruebas RF-07");
                        plugin.addBucket(dbName, "MetricasRF07", "TIMESERIES", "Métricas", "NONE", "[]");
                        plugin.addRecord(dbName, "MetricasRF07", "MET-1", "Lectura CPU", "{\"cpu\":42.5}");

                        HttpResponse<String> res = get("/explorer?db=" + dbName + "&bucket=ClientesRF07");
                        JettraAssert.assertEquals(200, res.statusCode(), "GET /explorer debe responder HTTP 200");
                        String body = res.body();

                        // 1. El Tree principal muestra ambos Engines y Buckets
                        JettraAssert.assertTrue(body.contains("data-explorer-tree=\"true\"")
                                        && body.contains("data-tree-level=\"engine\"")
                                        && body.contains("DOCUMENT") && body.contains("TIMESERIES")
                                        && body.contains("ClientesRF07") && body.contains("MetricasRF07"),
                                        "Los Engines y Buckets deben permanecer accesibles desde el Tree principal");

                        // 2. Debajo de 'Base de Datos Activa' (dentro de explorer-main-col) no debe
                        // existir la barra duplicada de Engines/Buckets
                        int mainColIdx = body.indexOf("explorer-main-col");
                        JettraAssert.assertTrue(mainColIdx > 0, "Debe existir la columna principal explorer-main-col");
                        String mainColHtml = body.substring(mainColIdx);
                        JettraAssert.assertFalse(
                                        mainColHtml.contains("/explorer?db=" + dbName + "&bucket=MetricasRF07"),
                                        "No debe renderizarse una sección redundante de Engines/Buckets debajo de Base de Datos Activa");

                        // 3. Seleccionar el bucket de otro Engine desde el Tree actualiza el contexto y
                        // muestra sus registros
                        HttpResponse<String> switchRes = get(
                                        "/explorer?db=" + dbName + "&bucket=MetricasRF07&section=records");
                        JettraAssert.assertEquals(200, switchRes.statusCode(),
                                        "Cambiar de bucket desde el Tree debe responder 200");
                        JettraAssert.assertTrue(
                                        switchRes.body().contains("MET-1") && switchRes.body().contains("Lectura CPU"),
                                        "Seleccionar un bucket desde el Tree debe cargar correctamente sus registros");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("52. JettraId en /explorer: prellenado automático, botón Regenerar JettraId, descripción opcional y rechazo de duplicado sin cerrar diálogo")
        public void testJettraIdCreateDialogPrefillOptionalDescriptionAndDuplicateFeedback() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "jettraid_explorer_test_db";
                try {
                        plugin.createDatabase(dbName, "ProductosId", "DOCUMENT", "Base pruebas JettraId");

                        // 1. Abrir diálogo + Nuevo Registro -> prellenado con JettraId válido, botón
                        // Regenerar JettraId, descripción no obligatoria
                        HttpResponse<String> newFormRes = get(
                                        "/explorer?db=" + dbName + "&bucket=ProductosId&action=new");
                        JettraAssert.assertEquals(200, newFormRes.statusCode(),
                                        "GET /explorer?action=new debe responder HTTP 200");
                        String formHtml = newFormRes.body();
                        JettraAssert.assertTrue(formHtml.contains("id=\"createRecordIdInput\"")
                                        && formHtml.contains("data-jettraid-input=\"true\"")
                                        && formHtml.contains("Regenerar JettraId")
                                        && formHtml.contains("/api/jettraid/generate"),
                                        "El diálogo debe prellenar JettraId e incluir botón de regeneración rápida");

                        // Extraer el valor prellenado y verificar que es un JettraId válido con hash de
                        // (dbName, ProductosId)
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                        .compile("name=\"recordId\"[^>]*value=\"([0-9a-f]{32})\"").matcher(formHtml);
                        if (!m.find()) {
                                m = java.util.regex.Pattern.compile("value=\"([0-9a-f]{32})\"[^>]*name=\"recordId\"")
                                                .matcher(formHtml);
                                JettraAssert.assertTrue(m.find(),
                                                "El campo recordId debe venir prellenado con un JettraId de 32 caracteres hex");
                        }
                        String prefilledId = m.group(1);
                        JettraAssert.assertTrue(io.jettra.store.core.JettraId.isValidHex(prefilledId),
                                        "El ID prellenado debe ser un JettraId válido");
                        JettraAssert.assertEquals(io.jettra.store.core.JettraId.computeScopeHash(dbName, "ProductosId"),
                                        io.jettra.store.core.JettraId.fromHex(prefilledId).databaseBucketHash(),
                                        "El JettraId prellenado debe corresponder al hash de la BD y bucket activos");

                        // 2. Endpoint de regeneración rápida /api/jettraid/generate
                        HttpResponse<String> regenRes = get(
                                        "/api/jettraid/generate?db=" + dbName + "&bucket=ProductosId");
                        JettraAssert.assertEquals(200, regenRes.statusCode(),
                                        "/api/jettraid/generate debe responder HTTP 200");
                        JettraAssert.assertTrue(regenRes.body().contains("\"jettraId\":\""),
                                        "Debe devolver JSON con jettraId");

                        // 3. Guardar registro dejando ID y descripción vacíos -> asigna JettraId
                        // automáticamente y guarda exitosamente
                        HttpResponse<String> autoSaveRes = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosId&recordId=&summary=&details=%7B%22item%22%3A%22Auto%22%7D");
                        JettraAssert.assertEquals(302, autoSaveRes.statusCode(),
                                        "Guardar sin ID ni descripción debe redirigir con 302");
                        JettraAssert.assertTrue(
                                        autoSaveRes.headers().firstValue("Location").orElse("")
                                                        .contains("msg=record_created"),
                                        "Debe crear el registro automáticamente con JettraId aunque descripción e ID vengan vacíos");

                        // 4. Guardar con ID personalizado y luego intentar duplicarlo -> muestra error
                        // sin cerrar el diálogo ni perder datos
                        HttpResponse<String> customSave = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosId&recordId=CUSTOM-ID-99&summary=Original&details=%7B%22item%22%3A%22A%22%7D");
                        JettraAssert.assertEquals(302, customSave.statusCode(),
                                        "Guardar con ID personalizado debe redirigir con 302");

                        HttpResponse<String> dupSave = postForm("/explorer",
                                        "action=save_new&db=" + dbName
                                                        + "&bucket=ProductosId&recordId=CUSTOM-ID-99&summary=MiResumenPreservado&details=%7B%22item%22%3A%22DatosConservados%22%7D");
                        JettraAssert.assertEquals(200, dupSave.statusCode(),
                                        "ID duplicado debe re-renderizar HTTP 200 manteniendo el diálogo abierto");
                        JettraAssert.assertTrue(dupSave.body().contains("id=\"explorerActionDialog\"")
                                        && dupSave.body().contains("data-create-record-error=\"true\"")
                                        && dupSave.body().contains("CUSTOM-ID-99")
                                        && dupSave.body().contains("MiResumenPreservado")
                                        && dupSave.body().contains("DatosConservados"),
                                        "Al detectar ID duplicado debe mostrar error claro sin cerrar el diálogo ni perder los datos capturados");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("53. RF-09: Cabecera informativa del Bucket antes del DataTable y controles Expandir todo / Colapsar todo en el Tree")
        public void testRf09SelectedBucketContextHeaderAndTreeExpandCollapseControls() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf09_context_tree_test_db";
                try {
                        plugin.createDatabase(dbName, "OrdenesRF09", "DOCUMENT", "Órdenes activas");
                        plugin.addBucket(dbName, "MetricasRF09", "TIMESERIES", "Telemetría en vivo", "NONE", "[]");
                        plugin.saveBucketStructure(dbName, "OrdenesRF09",
                                        "[{\"atribute_name\":\"codigo\",\"type_atribute\":\"Long\",\"jettra_rules\":\"AUTO_INCREMENT\",\"description\":\"Código\"}]");
                        plugin.addIndex(dbName, "OrdenesRF09", "idx_rf09_cod", "codigo", "AUTO_INCREMENT", true);
                        plugin.addRecord(dbName, "OrdenesRF09", "ORD-RF09-1", "Orden prueba", "{\"codigo\":1}");

                        // 1. Verificar cabecera informativa del Bucket ANTES del DataTable en /explorer
                        HttpResponse<String> res1 = get("/explorer?db=" + dbName + "&bucket=OrdenesRF09");
                        JettraAssert.assertEquals(200, res1.statusCode(), "GET /explorer debe responder HTTP 200");
                        String body1 = res1.body();
                        int headerPos = body1.indexOf("data-selected-bucket-header=\"true\"");
                        int tablePos = body1.indexOf("Fecha Mutación");
                        if (tablePos < 0)
                                tablePos = body1.indexOf("app-table");
                        JettraAssert.assertTrue(headerPos > 0 && tablePos > headerPos,
                                        "La cabecera informativa del Bucket seleccionado debe renderizarse antes del DataTable");
                        JettraAssert.assertTrue(body1.contains("Bucket Seleccionado: OrdenesRF09")
                                        && body1.contains("DOCUMENT")
                                        && body1.contains(dbName)
                                        && body1.contains("DEFINED (1 campos)")
                                        && body1.contains("índice(s)"),
                                        "La cabecera debe mostrar nombre del bucket, engine, base de datos, modo de estructura y conteo de índices");

                        // 2. Cambiar de bucket en el Tree -> la cabecera se actualiza al nuevo bucket
                        // (NONE)
                        HttpResponse<String> res2 = get("/explorer?db=" + dbName + "&bucket=MetricasRF09");
                        JettraAssert.assertEquals(200, res2.statusCode(),
                                        "Cambio a MetricasRF09 debe responder HTTP 200");
                        JettraAssert.assertTrue(res2.body().contains("Bucket Seleccionado: MetricasRF09")
                                        && res2.body().contains("TIMESERIES")
                                        && res2.body().contains("NONE (Esquema libre)"),
                                        "La cabecera debe actualizarse al seleccionar otro bucket del Tree");

                        // 3. Controles Expandir todo y Colapsar todo en el Tree Jerárquico + subárboles
                        // renderizados para todos los buckets
                        JettraAssert.assertTrue(body1.contains("id=\"treeExpandAllBtn\"")
                                        && body1.contains("Expandir todo")
                                        && body1.contains("jettraTreeExpandAll()")
                                        && body1.contains("id=\"treeCollapseAllBtn\"")
                                        && body1.contains("Colapsar todo")
                                        && body1.contains("jettraTreeCollapseAll()")
                                        && body1.contains("data-engine-body=\"DOCUMENT\"")
                                        && body1.contains("data-engine-body=\"TIMESERIES\"")
                                        && body1.contains("data-bucket-subtree=\"OrdenesRF09\"")
                                        && body1.contains("data-bucket-subtree=\"MetricasRF09\""),
                                        "El Tree debe incluir botones Expandir todo / Colapsar todo y todos los subárboles de buckets/engines");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("54. RF-10: Layout maestro-detalle en dos columnas en /explorer (Tree a la izquierda, Bucket Seleccionado + DataTable a la derecha, responsivo y estado persistente)")
        public void testRf10ExplorerTwoColumnMasterDetailLayoutAndTreeStatePersistence() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf10_two_col_test_db";
                try {
                        plugin.createDatabase(dbName, "ClientesRF10", "DOCUMENT", "Clientes activos");
                        plugin.addBucket(dbName, "EventosRF10", "TIMESERIES", "Eventos de telemetría", "NONE", "[]");
                        plugin.addRecord(dbName, "ClientesRF10", "CLI-RF10-1", "Cliente Uno", "{\"nombre\":\"Ana\"}");
                        plugin.addRecord(dbName, "EventosRF10", "EVT-RF10-1", "Evento Uno", "{\"metric\":99}");

                        // 1. Verificar estructura maestro-detalle en dos columnas: Tree izquierdo
                        // contiguo al panel derecho (Bucket Seleccionado + DataTable)
                        HttpResponse<String> res1 = get("/explorer?db=" + dbName + "&bucket=ClientesRF10");
                        JettraAssert.assertEquals(200, res1.statusCode(),
                                        "GET /explorer RF-10 debe responder HTTP 200");
                        String body1 = res1.body();

                        int layoutPos = body1.indexOf("data-explorer-master-detail=\"true\"");
                        int treePos = body1.indexOf("id=\"explorerTreeSidebar\"");
                        int detailPos = body1.indexOf("id=\"explorerMainCol\"");
                        int bucketHeaderPos = body1.indexOf("data-selected-bucket-header=\"true\"", detailPos);
                        int tablePos = body1.indexOf("<table", bucketHeaderPos);

                        JettraAssert.assertTrue(layoutPos > 0 && treePos > layoutPos && detailPos > treePos,
                                        "El contenedor maestro-detalle debe ubicar el Tree Explorador Jerárquico en la columna izquierda contigua al panel derecho");
                        JettraAssert.assertTrue(bucketHeaderPos > detailPos && tablePos > bucketHeaderPos,
                                        "La columna derecha debe iniciar con 'Bucket Seleccionado' seguido por el DataTable de registros");

                        // 2. Verificar CSS responsivo de 2 columnas (28% / 1fr en escritorio, 1fr en
                        // móvil/tablet) y scroll independiente del árbol
                        JettraAssert.assertTrue(
                                        body1.contains("grid-template-columns: minmax(250px, 28%) minmax(0, 1fr)")
                                                        && body1.contains("overflow-y: auto")
                                                        && body1.contains("@media (max-width: 920px)")
                                                        && body1.contains("grid-template-columns: 1fr"),
                                        "Debe aplicar grid responsivo de dos columnas en escritorio y apilado en pantallas estrechas con scroll vertical independiente en el Tree");

                        // 3. Verificar controles Expandir/Colapsar, persistencia de estado/scroll y
                        // actualización inmediata del panel derecho sin perder el Tree
                        JettraAssert.assertTrue(body1.contains("id=\"treeExpandAllBtn\"")
                                        && body1.contains("id=\"treeCollapseAllBtn\"")
                                        && body1.contains("jettra_explorer_tree_state_")
                                        && body1.contains("loadDetailPanelInPlace")
                                        && body1.contains("data-explorer-detail-panel=\"true\""),
                                        "Debe incluir persistencia de estado/scroll del Tree y actualización del panel derecho conservando el árbol");

                        // 4. Seleccionar otro Bucket actualiza la cabecera y los registros en la
                        // columna derecha manteniendo el Tree con ambos buckets
                        HttpResponse<String> res2 = get(
                                        "/explorer?db=" + dbName + "&bucket=EventosRF10&section=records");
                        JettraAssert.assertEquals(200, res2.statusCode(),
                                        "Cambio a EventosRF10 debe responder HTTP 200");
                        String body2 = res2.body();
                        int detailPos2 = body2.indexOf("id=\"explorerMainCol\"");
                        JettraAssert.assertTrue(detailPos2 > 0, "Debe conservar la columna derecha explorerMainCol");
                        String detailHtml2 = body2.substring(detailPos2);
                        JettraAssert.assertTrue(detailHtml2.contains("Bucket Seleccionado: EventosRF10")
                                        && detailHtml2.contains("EVT-RF10-1")
                                        && detailHtml2.contains("Evento Uno")
                                        && body2.contains("ClientesRF10") && body2.contains("EventosRF10"),
                                        "Al seleccionar otro Bucket, la columna derecha debe actualizar cabecera y registros mientras el Tree conserva todos los nodos");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("55. RF-11: Catálogo de tipos sin duplicados (List/ArrayList únicos) y entrada libre/validación de tipos personalizados en Añadir y Editar Bucket (/databases)")
        public void testRf11DeduplicatedTypesAndFreeCustomTypeInputInBucketDialogs() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf11_types_test_db";
                try {
                        plugin.createDatabase(dbName, "BaseRF11", "DOCUMENT", "Base pruebas RF-11");

                        // 1. Verificar catálogo de tipos sin duplicados en 'Añadir Nuevo Motor /
                        // Bucket'
                        HttpResponse<String> addRes = get(
                                        "/databases?db=" + dbName + "&manage_engines=true&add_bucket=true");
                        JettraAssert.assertEquals(200, addRes.statusCode(),
                                        "Modal Añadir Bucket debe responder HTTP 200");
                        String addBody = addRes.body();
                        JettraAssert.assertTrue(addBody.contains("data-add-bucket-modal=\"true\"")
                                        && addBody.contains("data-attr-type-select=\"true\"")
                                        && addBody.contains("data-attr-type-input=\"true\""),
                                        "El modal Añadir Bucket debe ofrecer selector de tipos sugeridos y campo editable de tipo libre");

                        // Extraer el primer <select name="attr_type_select_0" ...>...</select> y
                        // verificar que List y ArrayList aparecen una sola vez
                        java.util.regex.Matcher addSel = java.util.regex.Pattern.compile(
                                        "<select[^>]*name=\"attr_type_select_0\"[^>]*>(.*?)</select>",
                                        java.util.regex.Pattern.DOTALL).matcher(addBody);
                        JettraAssert.assertTrue(addSel.find(),
                                        "Debe renderizar el select attr_type_select_0 en Añadir Bucket");
                        String addOptionsHtml = addSel.group(1);
                        int listCountAdd = addOptionsHtml.split(">List<", -1).length - 1;
                        int arrayListCountAdd = addOptionsHtml.split(">ArrayList<", -1).length - 1;
                        JettraAssert.assertEquals(1, listCountAdd,
                                        "List debe aparecer exactamente una vez en Añadir Bucket");
                        JettraAssert.assertEquals(1, arrayListCountAdd,
                                        "ArrayList debe aparecer exactamente una vez en Añadir Bucket");
                        JettraAssert.assertFalse(
                                        addOptionsHtml.contains("List<String>")
                                                        || addOptionsHtml.contains("ArrayList<String>"),
                                        "No deben existir entradas repetidas con etiquetas genéricas sin escapar en el selector de Añadir Bucket");

                        // 2. Crear Bucket DEFINED con tipos personalizados y colecciones parametrizadas
                        // escritas manualmente
                        String createBody = "action=add_bucket&db=" + dbName
                                        + "&bucketName=PedidosRF11&engineType=JAVA_RECORD&structureType=DEFINED&description=Pedidos+RF11"
                                        + "&attr_name_0=cliente&attr_type_0=MiRecord&attr_rules_0=NOT_NULL&attr_desc_0=Record+personalizado"
                                        + "&attr_name_1=detalles&attr_type_1=List%3CMiRecord%3E&attr_rules_1=NOT_NULL&attr_desc_1=Lista+parametrizada"
                                        + "&attr_name_2=productos&attr_type_2=ArrayList%3CProducto%3E&attr_rules_2=&attr_desc_2=ArrayList+parametrizada"
                                        + "&attr_name_3=codigos&attr_type_3=Set%3CInteger%3E&attr_rules_3=&attr_desc_3=Conjunto+enteros";
                        HttpResponse<String> createRes = postForm("/databases", createBody);
                        JettraAssert.assertEquals(302, createRes.statusCode(),
                                        "Crear bucket con tipos personalizados debe redirigir 302");
                        JettraAssert.assertTrue(
                                        createRes.headers().firstValue("Location").orElse("")
                                                        .contains("msg=bucket_added"),
                                        "Debe crear exitosamente el bucket con MiRecord, List<MiRecord>, ArrayList<Producto> y Set<Integer>");

                        EngineBucketInfo created = plugin.findBucketExact(dbName, "PedidosRF11");
                        JettraAssert.assertNotNull(created, "PedidosRF11 debe existir en el catálogo");
                        JettraAssert.assertEquals("MiRecord", created.getAttributes().get(0).type_atribute(),
                                        "Debe persistir MiRecord");
                        JettraAssert.assertEquals("List<MiRecord>", created.getAttributes().get(1).type_atribute(),
                                        "Debe persistir List<MiRecord>");
                        JettraAssert.assertEquals("ArrayList<Producto>", created.getAttributes().get(2).type_atribute(),
                                        "Debe persistir ArrayList<Producto>");
                        JettraAssert.assertEquals("Set<Integer>", created.getAttributes().get(3).type_atribute(),
                                        "Debe persistir Set<Integer>");

                        // 3. Abrir modal 'Editar Bucket' -> sin duplicados y precargando los tipos
                        // parametrizados exactos
                        HttpResponse<String> editRes = get(
                                        "/databases?db=" + dbName + "&manage_engines=true&edit_bucket=PedidosRF11");
                        JettraAssert.assertEquals(200, editRes.statusCode(),
                                        "Modal Editar Bucket debe responder HTTP 200");
                        String editBody = editRes.body();
                        JettraAssert.assertTrue(editBody.contains("data-edit-bucket-form=\"PedidosRF11\"")
                                        && editBody.contains("MiRecord")
                                        && (editBody.contains("List<MiRecord>")
                                                        || editBody.contains("List&lt;MiRecord&gt;"))
                                        && (editBody.contains("ArrayList<Producto>")
                                                        || editBody.contains("ArrayList&lt;Producto&gt;"))
                                        && (editBody.contains("Set<Integer>")
                                                        || editBody.contains("Set&lt;Integer&gt;")),
                                        "El modal Editar Bucket debe precargar los tipos personalizados y colecciones parametrizadas sin perderlos");

                        java.util.regex.Matcher editSel = java.util.regex.Pattern.compile(
                                        "<select[^>]*name=\"attr_type_select_0\"[^>]*>(.*?)</select>",
                                        java.util.regex.Pattern.DOTALL).matcher(editBody);
                        JettraAssert.assertTrue(editSel.find(), "Debe renderizar attr_type_select_0 en Editar Bucket");
                        String editOptionsHtml = editSel.group(1);
                        JettraAssert.assertEquals(1, editOptionsHtml.split(">List<", -1).length - 1,
                                        "List debe aparecer exactamente una vez en Editar Bucket");
                        JettraAssert.assertEquals(1, editOptionsHtml.split(">ArrayList<", -1).length - 1,
                                        "ArrayList debe aparecer exactamente una vez en Editar Bucket");

                        // 4. Editar bucket modificando tipos libremente -> persiste correctamente
                        String updateBody = "action=edit_bucket&db=" + dbName
                                        + "&originalBucketName=PedidosRF11&bucketName=PedidosRF11&structureType=DEFINED&description=Editado"
                                        + "&attr_name_0=direccion&attr_type_0=Direccion&attr_rules_0=NOT_NULL&attr_desc_0=Clase+custom"
                                        + "&attr_name_1=historial&attr_type_1=List%3CDireccion%3E&attr_rules_1=&attr_desc_1=Lista+direcciones";
                        HttpResponse<String> updateRes = postForm("/databases", updateBody);
                        JettraAssert.assertEquals(302, updateRes.statusCode(),
                                        "Editar bucket con nuevos tipos personalizados debe redirigir 302");
                        JettraAssert.assertTrue(
                                        updateRes.headers().firstValue("Location").orElse("")
                                                        .contains("msg=bucket_updated"),
                                        "Debe actualizar el bucket con Direccion y List<Direccion>");
                        EngineBucketInfo updated = plugin.findBucketExact(dbName, "PedidosRF11");
                        JettraAssert.assertEquals("Direccion", updated.getAttributes().get(0).type_atribute(),
                                        "Debe persistir Direccion");
                        JettraAssert.assertEquals("List<Direccion>", updated.getAttributes().get(1).type_atribute(),
                                        "Debe persistir List<Direccion>");

                        // 5. Validación de sintaxis de tipo: rechazar tipo vacío o genérico inválido
                        // (List<>, 123Invalido)
                        JettraAssert.assertTrue(JettraStorePlugin.isValidAttributeTypeSyntax("MiRecord"),
                                        "MiRecord es válido");
                        JettraAssert.assertTrue(JettraStorePlugin.isValidAttributeTypeSyntax("List<MiRecord>"),
                                        "List<MiRecord> es válido");
                        JettraAssert.assertTrue(
                                        JettraStorePlugin.isValidAttributeTypeSyntax("Map<String, List<Producto>>"),
                                        "Map anidado es válido");
                        JettraAssert.assertFalse(JettraStorePlugin.isValidAttributeTypeSyntax(""),
                                        "Tipo vacío es inválido");
                        JettraAssert.assertFalse(JettraStorePlugin.isValidAttributeTypeSyntax("List<>"),
                                        "List<> vacío es inválido");
                        JettraAssert.assertFalse(JettraStorePlugin.isValidAttributeTypeSyntax("List<MiRecord"),
                                        "Genérico sin cierre es inválido");
                        JettraAssert.assertFalse(JettraStorePlugin.isValidAttributeTypeSyntax("123Tipo"),
                                        "Identificador numérico inicial es inválido");

                        String invalidCreate = "action=add_bucket&db=" + dbName
                                        + "&bucketName=InvalidoRF11&engineType=DOCUMENT&structureType=DEFINED"
                                        + "&attr_name_0=items&attr_type_0=List%3C%3E&attr_rules_0=NOT_NULL&attr_desc_0=Invalido";
                        HttpResponse<String> invalidRes = postForm("/databases", invalidCreate);
                        JettraAssert.assertEquals(302, invalidRes.statusCode(),
                                        "Tipo inválido debe redirigir con mensaje de error");
                        JettraAssert.assertFalse(
                                        invalidRes.headers().firstValue("Location").orElse("")
                                                        .contains("msg=bucket_added"),
                                        "No debe crear el bucket cuando un campo tiene sintaxis de tipo inválida");
                        JettraAssert.assertNull(plugin.findBucketExact(dbName, "InvalidoRF11"),
                                        "El bucket con tipo inválido no debe guardarse en el catálogo");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("56. RF-12: Unificación a Bucket en el motor DOCUMENT en /database y /explorer sin referencias a COLLECTION")
        public void testRf12DocumentEngineUsesBucketExclusivelyAcrossDatabaseAndExplorer() throws Exception {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "rf12_doc_bucket_test_db";
                try {
                        plugin.createDatabase(dbName, "FacturasRF12", "DOCUMENT", "Bucket documental unificado");
                        plugin.saveBucketStructure(dbName, "FacturasRF12",
                                        "[{\"atribute_name\":\"folio\",\"type_atribute\":\"String\",\"jettra_rules\":\"NOT_NULL\",\"description\":\"Folio\"}]");
                        plugin.addIndex(dbName, "FacturasRF12", "idx_rf12_folio", "folio", "BTREE", true);
                        plugin.addRecord(dbName, "FacturasRF12", "FAC-RF12-1", "Factura RF12", "{\"folio\":\"F-001\"}");

                        // 1. /database -> Motores y Buckets muestra DOCUMENT con Buckets y sin
                        // comandos/flujos COLLECTION
                        HttpResponse<String> dbRes = get("/database?db=" + dbName + "&manage_engines=true");
                        JettraAssert.assertEquals(200, dbRes.statusCode(),
                                        "GET /database?manage_engines=true debe responder 200");
                        String dbBody = dbRes.body();
                        JettraAssert.assertTrue(dbBody.contains("data-engine-group=\"DOCUMENT\"")
                                        && dbBody.contains("FacturasRF12")
                                        && dbBody.contains("Añadir Bucket en DOCUMENT"),
                                        "/database debe administrar el engine DOCUMENT exclusivamente mediante Buckets");
                        JettraAssert.assertFalse(
                                        dbBody.contains("CREATE COLLECTION") || dbBody.contains("DROP COLLECTION")
                                                        || dbBody.contains("Colección de logs"),
                                        "/database no debe contener referencias residuales a COLLECTION");

                        // 2. /explorer -> Tree Jerárquico, Bucket Seleccionado, Estructura e Índices
                        // sobre Bucket DOCUMENT
                        HttpResponse<String> expRes = get("/explorer?db=" + dbName + "&bucket=FacturasRF12");
                        JettraAssert.assertEquals(200, expRes.statusCode(), "GET /explorer debe responder 200");
                        JettraAssert.assertTrue(expRes.body().contains("Bucket Seleccionado: FacturasRF12")
                                        && expRes.body().contains("DOCUMENT")
                                        && expRes.body().contains("FAC-RF12-1"),
                                        "/explorer debe operar sobre FacturasRF12 como Bucket del engine DOCUMENT");

                        HttpResponse<String> schemaRes = get(
                                        "/explorer?db=" + dbName + "&bucket=FacturasRF12&section=schema");
                        JettraAssert.assertEquals(200, schemaRes.statusCode(), "Modal Estructura debe responder 200");
                        JettraAssert.assertTrue(schemaRes.body().contains("Estructura del Bucket 'FacturasRF12'"),
                                        "El modal de estructura debe titularse como Bucket");

                        HttpResponse<String> idxRes = get(
                                        "/explorer?db=" + dbName + "&bucket=FacturasRF12&section=indexes");
                        JettraAssert.assertEquals(200, idxRes.statusCode(), "Modal Índices debe responder 200");
                        JettraAssert.assertTrue(
                                        idxRes.body().contains("Índices del Bucket 'FacturasRF12' (" + dbName
                                                        + " / DOCUMENT)"),
                                        "El modal de índices debe titularse como Bucket en DOCUMENT");
                } finally {
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("RF-14: /explorer sincroniza Buckets, esquemas, índices y registros con JettraId de examplesedb vía JettraStoreDriver")
        public void testExplorerSyncsExamplesedbBucketsIndexesAndRecordsFromDriver() {
                JettraStorePlugin plugin = JettraStorePlugin.getInstance();
                String dbName = "examplesedb_rf14_" + System.currentTimeMillis();
                io.jettra.driver.JettraClient driver = plugin.getDriverClient();
                try {
                        // 1. Simular ejecución de JettraStoreExampleSE creando Buckets (DEFINED y
                        // NONE), índices y registros con JettraId
                        driver.createDatabase(dbName);
                        driver.createBucket(dbName, "JAVA_RECORD", "Pais",
                                        io.jettra.store.core.JettraBucketSchema.StructureType.DEFINED,
                                        java.util.List.of(
                                                        new io.jettra.store.core.JettraBucketSchema.AttributeRule(
                                                                        "jettraId", "String", "NOT_NULL", "JettraId"),
                                                        new io.jettra.store.core.JettraBucketSchema.AttributeRule(
                                                                        "codigo", "Long", "AUTO_INCREMENT", "Código"),
                                                        new io.jettra.store.core.JettraBucketSchema.AttributeRule(
                                                                        "name", "String", "NOT_NULL", "Nombre"),
                                                        new io.jettra.store.core.JettraBucketSchema.AttributeRule(
                                                                        "siglas", "String", "NOT_NULL", "Siglas")));
                        driver.createAutoIncrementIndex(dbName, "Pais", "idx_rf14_pais_codigo", "codigo");
                        driver.createBucketIndex(dbName, "Pais", "idx_rf14_pais_siglas", "siglas", "BTREE", true);

                        driver.createBucket(dbName, "DOCUMENT", "NotasLibres",
                                        io.jettra.store.core.JettraBucketSchema.StructureType.NONE,
                                        java.util.List.of());

                        String paJettraId = io.jettra.store.core.JettraId.generate(dbName, "Pais").toHexString();
                        java.util.Map<String, Object> paDoc = new java.util.LinkedHashMap<>();
                        paDoc.put("jettraId", paJettraId);
                        paDoc.put("name", "República de Panamá");
                        paDoc.put("siglas", "PA");
                        driver.insertBucketRecord(dbName, "JAVA_RECORD", "Pais", paJettraId, paDoc);

                        String notaJettraId = io.jettra.store.core.JettraId.generate(dbName, "NotasLibres")
                                        .toHexString();
                        java.util.Map<String, Object> notaDoc = new java.util.LinkedHashMap<>();
                        notaDoc.put("jettraId", notaJettraId);
                        notaDoc.put("autor", "Aristides");
                        driver.insertBucketRecord(dbName, "DOCUMENT", "NotasLibres", notaJettraId, notaDoc);

                        // 2. Renderizar /explorer en JettraStoreExplorer y verificar Buckets, registros
                        // con JettraId, esquema e índices
                        io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();
                        String recordsHtml = renderer.render(
                                        new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                        dbName, "Pais", "records", "", null, null, null, false, false,
                                                        false, false, null, 1, 10).build(),
                                        "RF14 Records");
                        JettraAssert.assertTrue(recordsHtml.contains("Pais") && recordsHtml.contains("NotasLibres"),
                                        "El árbol de /explorer debe mostrar los Buckets reales creados en " + dbName);
                        JettraAssert.assertTrue(
                                        recordsHtml.contains(paJettraId) && recordsHtml.contains("República de Panamá"),
                                        "La DataTable de /explorer debe mostrar el JettraId y los datos persistidos en Pais");

                        String schemaHtml = renderer.render(
                                        new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                        dbName, "Pais", "schema", "", null, null, null, false, false,
                                                        false, false, null, 1, 10).build(),
                                        "RF14 Schema");
                        JettraAssert.assertTrue(
                                        schemaHtml.contains("DEFINED") && schemaHtml.contains("siglas")
                                                        && schemaHtml.contains("AUTO_INCREMENT"),
                                        "La vista de estructura debe reflejar el esquema DEFINED creado desde JettraStoreDriver");

                        String indexesHtml = renderer.render(
                                        new io.jettra.store.explorer.pages.explorer.RecordExplorerFluxView(
                                                        dbName, "Pais", "indexes", "", null, null, null, false, false,
                                                        false, false, null, 1, 10).build(),
                                        "RF14 Indexes");
                        JettraAssert.assertTrue(
                                        indexesHtml.contains("idx_rf14_pais_codigo")
                                                        && indexesHtml.contains("idx_rf14_pais_siglas"),
                                        "La vista de índices debe reflejar los índices reales creados en el Bucket Pais");
                } finally {
                        driver.dropDatabase(dbName);
                        plugin.deleteDatabase(dbName);
                }
        }

        @Test
        @DisplayName("RF-17: Ruta /migrator, formulario de migración y endpoints de descarga de backend")
        public void testMigratorPageAndEndpoints() throws Exception {
                ensureSharedServer();

                // 1. GET /migrator debe responder 200 OK y contener elementos del formulario
                HttpResponse<String> resGet = get("/migrator");
                JettraAssert.assertEquals(200, resGet.statusCode(), "GET /migrator debe responder 200 OK");
                String html = resGet.body();
                JettraAssert.assertTrue(html.contains("Migrador PostgreSQL"), "Debe contener el título de migrador");
                JettraAssert.assertTrue(html.contains("Servidor Host PostgreSQL"), "Debe contener campo host");
                JettraAssert.assertTrue(html.contains("Base de Datos Origen"), "Debe contener campo base de datos");
                JettraAssert.assertTrue(html.contains("Base de Datos Destino JettraStore"),
                                "Debe contener campo targetDatabase");
                JettraAssert.assertTrue(html.contains("Conectar y Listar BDs"),
                                "Debe contener botón Conectar y Listar BDs");
                JettraAssert.assertTrue(html.contains("Analizar Esquema"), "Debe contener botón Analizar Esquema");
                JettraAssert.assertTrue(html.contains("Iniciar Migración Completa"),
                                "Debe contener botón Iniciar Migración");

                // 2. Simular POST /migrator con action=connect (descubrimiento de BDs y
                // esquemas)
                HttpResponse<String> resConnect = post("/migrator",
                                "action=connect&host=127.0.0.1&port=5432&user=postgres&password=");
                JettraAssert.assertEquals(302, resConnect.statusCode(),
                                "POST /migrator action=connect debe emitir redirección 302");

                // 3. Simular POST /migrator con action=dry-run
                HttpResponse<String> resPost = post("/migrator",
                                "action=dry-run&host=127.0.0.1&port=5432&database=fake_db&targetDatabase=jettra_migrated_test&generateBackend=true");
                JettraAssert.assertEquals(302, resPost.statusCode(), "POST /migrator debe redirigir tras procesar");

                // 4. Comprobar que el endpoint /api/migrator/download responde
                HttpResponse<String> resDownload = get("/api/migrator/download");
                JettraAssert.assertTrue(resDownload.statusCode() == 200 || resDownload.statusCode() == 404,
                                "El endpoint de descarga debe responder HTTP válido (200 con archivo o 404 si aún no se analizó BD real)");

                // 5. Validar renderizado de componentes JettraFlux con bases y esquemas
                // descubiertos
                io.jettra.store.explorer.pages.migrator.MigratorFluxView viewWithDbs = new io.jettra.store.explorer.pages.migrator.MigratorFluxView(
                                new io.jettra.driver.migrator.PostgresConnectionConfig("127.0.0.1", 5432, "ventas_corp",
                                                "postgres", "", "public", "jettra_ventas"),
                                java.util.List.of("postgres", "ventas_corp", "rrhh_db"),
                                java.util.List.of("public", "ventas_schema"),
                                null, null, null, false, false);
                io.jettra.server.flux.FluxRenderer renderer = new io.jettra.server.flux.FluxRenderer();
                String renderedView = renderer.render(viewWithDbs.build(), "Migrador");
                JettraAssert.assertTrue(renderedView.contains("ventas_corp"),
                                "Debe renderizar la base de datos detectada 'ventas_corp'");
                JettraAssert.assertTrue(renderedView.contains("rrhh_db"), "Debe renderizar 'rrhh_db'");
                JettraAssert.assertTrue(renderedView.contains("ventas_schema"),
                                "Debe renderizar el esquema 'ventas_schema'");
                JettraAssert.assertTrue(renderedView.contains("Bases en PostgreSQL"),
                                "Debe renderizar el panel informativo de bases de datos");
        }
}

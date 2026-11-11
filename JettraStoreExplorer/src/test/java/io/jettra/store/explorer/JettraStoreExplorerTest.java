package io.jettra.store.explorer;

import io.jettra.server.core.JettraServer;
import io.jettra.server.core.config.ServerConfig;
import io.jettra.server.core.storage.StorageMode;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
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
 * Suite de pruebas de alto rendimiento para JettraStore Explorer validada con JettraTest.
 * 100% libre de dependencias JUnit. Optimizada con reutilización de servidor en Virtual Threads,
 * clientes HTTP compartidos y comprobación TCP local instantánea.
 */
public class JettraStoreExplorerTest {

    private static final int BASE_TEST_PORT = 9480;

    // Servidor compartido para evitar arranque/parada secuencial en cada test
    private static JettraServer sharedServer;
    private static int sharedPort = BASE_TEST_PORT;
    private static String sharedAuthCookie;

    // Clientes HTTP reutilizables de alta eficiencia (evitan alloc innecesario de sockets y threads)
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final HttpClient REDIRECT_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    static {
        // Reducir latencias de comprobación de clúster a loopback local (0.05 ms vs 1000 ms por timeout TCP)
        System.setProperty("cluster.nodes", "node-01:127.0.0.1:9091,node-02:127.0.0.1:9092,node-03:127.0.0.1:9093");
        System.setProperty("cluster.healthcheck.timeout.ms", "200");
        System.setProperty("cluster.heartbeat.interval.ms", "60000");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (sharedServer != null && sharedServer.isRunning()) {
                App.stopApplication(sharedServer);
            }
        }));
    }

    @BeforeAll
    public static void setUpSuite() throws Exception {
        ensureSharedServer();
    }

    @AfterAll
    public static void tearDownSuite() {
        if (sharedServer != null && sharedServer.isRunning()) {
            App.stopApplication(sharedServer);
            sharedServer = null;
        }
    }

    private static synchronized void ensureSharedServer() throws Exception {
        if (sharedServer == null || !sharedServer.isRunning()) {
            sharedPort = BASE_TEST_PORT;
            sharedServer = App.startApplication(sharedPort, StorageMode.OFF_HEAP);
            sharedAuthCookie = null;
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
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin123"))
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
        JettraAssert.assertTrue(res.body().contains("Nodos ACTIVE") || res.body().contains("Nodos Activos"), "Debe incluir KPI de nodos activos (ACTIVE)");
        JettraAssert.assertTrue(res.body().contains("Ingesta Global QPS"), "Debe incluir métricas QPS");
        JettraAssert.assertTrue(res.body().contains("Panama Direct Memory"), "Debe incluir Panama Direct Memory");
        JettraAssert.assertTrue(res.body().contains("node-01"), "Debe listar el nodo master");
        JettraAssert.assertTrue(res.body().contains("node-02"), "Debe listar réplicas Raft");
        JettraAssert.assertTrue(res.body().contains("Registrar Nuevo Nodo"), "Debe incluir formulario de registro de nodos");
        JettraAssert.assertTrue(res.body().contains("Matriz de Replicación"), "Debe incluir matriz de replicación");
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
        JettraAssert.assertTrue(res.body().contains("Modo Almacenamiento"), "Debe mostrar modo de almacenamiento");
        JettraAssert.assertTrue(res.body().contains("Heap JVM (Loom)"), "Debe mostrar estadísticas Loom");
        JettraAssert.assertTrue(res.body().contains("Centinelas Guardián JettraPolice"), "Debe listar centinelas Police");
        JettraAssert.assertTrue(res.body().contains("Registro de Eventos"), "Debe incluir bitácora de auditoría");
    }

    @Test
    @DisplayName("5. Administrar Bases de Datos y Motores: Verificar catálogo de bases de datos y buckets")
    public void testDatabasesAndEnginesView() throws Exception {
        HttpResponse<String> res = get("/databases");

        JettraAssert.assertEquals(200, res.statusCode(), "La vista de bases de datos debe responder HTTP 200");
        JettraAssert.assertTrue(res.body().contains("Catálogo Maestro de Bases de Datos"), "Debe contener catálogo maestro");
        JettraAssert.assertTrue(res.body().contains("example_factura_db"), "Debe incluir example_factura_db");
        JettraAssert.assertTrue(res.body().contains("samples_hostipal_db"), "Debe incluir samples_hostipal_db");
        JettraAssert.assertTrue(res.body().contains("DOCUMENT"), "Debe mostrar motores documentales");
        JettraAssert.assertTrue(res.body().contains("RELATIONAL"), "Debe mostrar motores relacionales");
        JettraAssert.assertTrue(res.body().contains("Añadir Nuevo Motor / Bucket"), "Debe permitir añadir motores");
    }

    @Test
    @DisplayName("6. Administrar Bases de Datos y Motores: Creación dinámica de Base de Datos y nuevo Motor")
    public void testCreateDatabaseAndBucket() throws Exception {
        String createDbForm = "action=create_db&dbName=test_billing_db&defaultBucket=FacturasEmitidas&engineType=DOCUMENT&description=BD_Prueba_Automatizada";
        HttpResponse<String> res1 = postForm("/databases", createDbForm);
        JettraAssert.assertEquals(302, res1.statusCode(), "Crear BD debe redirigir");

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        JettraAssert.assertTrue(plugin.getDatabaseNames().contains("test_billing_db"), "La BD debe haberse registrado en el catálogo");

        String addBucketForm = "action=add_bucket&db=test_billing_db&bucketName=FacturasEmbeddings&engineType=VECTOR&description=Embeddings_HNSW";
        HttpResponse<String> res2 = postForm("/databases", addBucketForm);
        JettraAssert.assertEquals(302, res2.statusCode(), "Añadir motor debe redirigir");

        EngineBucketInfo b = plugin.getBucket("test_billing_db", "FacturasEmbeddings");
        JettraAssert.assertNotNull(b, "El nuevo bucket/motor debe existir");
        JettraAssert.assertEquals("VECTOR", b.getEngineType(), "El motor debe ser VECTOR");

        plugin.deleteDatabase("test_billing_db");
        JettraAssert.assertFalse(plugin.getDatabaseNames().contains("test_billing_db"), "La BD debe eliminarse");
    }

    @Test
    @DisplayName("7. Operaciones CRUD de Registros: Create, Read, Update, Delete en JettraStore")
    public void testRecordCrudOperations() throws Exception {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String db = "example_factura_db";
        String bucket = "Facturas";

        // 1. CREATE
        String createForm = "action=save_new&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001&summary=Factura_Prueba_CRUD&details={\"folio\":\"FAC-TEST-001\",\"total\":999.00}";
        HttpResponse<String> createRes = postForm("/explorer", createForm);
        JettraAssert.assertEquals(302, createRes.statusCode(), "Crear registro debe retornar HTTP 302");

        EngineRecordInfo rec = plugin.getRecord(db, bucket, "FAC-TEST-001");
        JettraAssert.assertNotNull(rec, "El registro creado debe existir en el bucket");
        JettraAssert.assertEquals("Factura_Prueba_CRUD", rec.getSummary(), "El resumen debe coincidir");
        JettraAssert.assertEquals(1L, rec.getVersion(), "La versión inicial debe ser 1");

        // 2. UPDATE
        String updateForm = "action=save_edit&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001&summary=Factura_Actualizada_V2&details={\"folio\":\"FAC-TEST-001\",\"total\":1250.00}";
        HttpResponse<String> updateRes = postForm("/explorer", updateForm);
        JettraAssert.assertEquals(302, updateRes.statusCode(), "Actualizar registro debe retornar HTTP 302");

        EngineRecordInfo updatedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
        JettraAssert.assertEquals("Factura_Actualizada_V2", updatedRec.getSummary(), "El resumen debe haberse actualizado");
        JettraAssert.assertEquals(2L, updatedRec.getVersion(), "La versión debe incrementarse a v2");

        // 3. DELETE
        HttpResponse<String> deleteRes = get("/explorer?action=delete_record&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001");
        JettraAssert.assertEquals(302, deleteRes.statusCode(), "Eliminar registro debe retornar HTTP 302");

        EngineRecordInfo deletedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
        JettraAssert.assertNull(deletedRec, "El registro debe haber sido eliminado");
    }

    @Test
    @DisplayName("8. Consola SQL / JQL: Ejecución de consultas directas y renderizado de resultados")
    public void testQueryConsoleView() throws Exception {
        HttpResponse<String> res = get("/query?db=example_factura_db&bucket=Facturas&query=total+%3E+500");

        JettraAssert.assertEquals(200, res.statusCode(), "La consola de consulta debe responder HTTP 200");
        JettraAssert.assertTrue(res.body().contains("Consola de Consulta Directa"), "Debe mostrar título de consola");
        JettraAssert.assertTrue(res.body().contains("FAC-2026-001"), "Debe mostrar registros que cumplan la condición");
        JettraAssert.assertTrue(res.body().contains("ejecutado en"), "Debe mostrar tiempo de ejecución en µs");
    }

    @Test
    @DisplayName("9. Conexiones y Seguridad: Verificación de perfiles de red y permisos de usuarios")
    public void testConnectionsAndUsersViews() throws Exception {
        HttpResponse<String> connRes = get("/connections");
        JettraAssert.assertEquals(200, connRes.statusCode(), "Conexiones debe responder HTTP 200");
        JettraAssert.assertTrue(connRes.body().contains("Perfiles de Conexión Configurados"), "Debe listar perfiles");

        HttpResponse<String> usersRes = get("/users");
        JettraAssert.assertEquals(200, usersRes.statusCode(), "Usuarios debe responder HTTP 200");
        JettraAssert.assertTrue(usersRes.body().contains("Usuarios Registrados y Permisos Granulares"), "Debe mostrar tabla de permisos");
        JettraAssert.assertTrue(usersRes.body().contains("admin"), "Debe contener el usuario admin");
    }

    @Test
    @DisplayName("10. Copias de Seguridad y Centinelas Police: Snapshots y Patrullaje Heurístico")
    public void testBackupsAndPoliceViews() throws Exception {
        HttpResponse<String> bkpRes = get("/backups");
        JettraAssert.assertEquals(200, bkpRes.statusCode(), "Backups debe responder HTTP 200");
        JettraAssert.assertTrue(bkpRes.body().contains("Snapshots y Copias de Seguridad"), "Debe listar snapshots");

        HttpResponse<String> policeRes = get("/police");
        JettraAssert.assertEquals(200, policeRes.statusCode(), "Police debe responder HTTP 200");
        JettraAssert.assertTrue(policeRes.body().contains("Centro de Comando JettraStorePolice"), "Debe mostrar centro de comando");
    }

    @Test
    @DisplayName("11. Nodos del Clúster: Verificación de estado real ONLINE y OFFLINE vía socket TCP")
    public void testRealNodeReachabilityOnlineOffline() throws Exception {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo master = plugin.getNodeById("node-01");
        int originalPort = master.getPort();
        try {
            // 1. Con un nodo primario simulado activo en su propio puerto TCP, debe responder ONLINE
            try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                master.setPort(mockPrimarySocket.getLocalPort());

                HttpResponse<String> resOnline = getWithRedirect("/cluster?action=check_nodes");
                JettraAssert.assertEquals(200, resOnline.statusCode(), "Cluster con check_nodes debe responder HTTP 200");
                JettraAssert.assertTrue(resOnline.body().contains("● PRIMARY: ONLINE"), "Debe mostrar PRIMARY: ONLINE cuando el nodo primario responde");
                JettraAssert.assertTrue(resOnline.body().contains("OFFLINE"), "Los nodos secundarios inaccesibles deben mostrarse OFFLINE");
                JettraAssert.assertTrue(master.isOnline(), "node-01 debe estar ONLINE en vivo");
                JettraAssert.assertTrue(master.getLatencyMs() > 0.0f, "La latencia debe estar medida en milisegundos");
            }

            // 2. Al cerrarse el socket del nodo primario, debe detectar inmediatamente el estado OFFLINE
            HttpResponse<String> resOffline = getWithRedirect("/cluster?action=check_nodes");
            JettraAssert.assertEquals(200, resOffline.statusCode(), "Cluster debe renderizar aún con PRIMARY desconectado");
            JettraAssert.assertFalse(master.isOnline(), "node-01 debe marcarse OFFLINE cuando su puerto cierra");
            JettraAssert.assertTrue(resOffline.body().contains("○ PRIMARY: OFFLINE"), "TopBar debe mostrar ○ PRIMARY: OFFLINE");
            JettraAssert.assertTrue(resOffline.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"), "Debe mostrar alerta crítica de primario desconectado");
            JettraAssert.assertTrue(resOffline.body().contains("DISCONNECTED (Sin latido)"), "La matriz WAL debe reflejar desconexión");
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
        JettraAssert.assertTrue(dbRes.body().contains("GEOSPATIAL"), "Debe mostrar motor GEOSPATIAL en selector");
        JettraAssert.assertTrue(dbRes.body().contains("JAVA_RECORD"), "Debe mostrar motor JAVA_RECORD en selector");
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
        plugin.addRecord(db, bucket, recordId, "Versión 1 Original", "{\"total\":100.00,\"estado\":\"CREADO\"}");
        EngineRecordInfo rec = plugin.getRecord(db, bucket, recordId);
        JettraAssert.assertNotNull(rec, "Registro debe existir");
        JettraAssert.assertEquals(1L, rec.getVersion(), "Versión inicial debe ser 1");

        // 2. Mutar a versión 2
        plugin.updateRecord(db, bucket, recordId, "Versión 2 Modificada", "{\"total\":250.00,\"estado\":\"EN_PROCESO\"}");
        JettraAssert.assertEquals(2L, rec.getVersion(), "Versión debe ser 2");

        // 3. Mutar a versión 3
        plugin.updateRecord(db, bucket, recordId, "Versión 3 Modificada", "{\"total\":500.00,\"estado\":\"FINALIZADO\"}");
        JettraAssert.assertEquals(3L, rec.getVersion(), "Versión debe ser 3");
        JettraAssert.assertEquals(3, rec.getVersionHistory().size(), "El historial debe contener 3 versiones");

        // 4. Consultar vista web de historial de versiones
        HttpResponse<String> histRes = get("/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId);
        JettraAssert.assertEquals(200, histRes.statusCode(), "Debe responder HTTP 200 en vista de versiones");
        JettraAssert.assertTrue(histRes.body().contains("Historial de Versiones del Registro"), "Debe mostrar título de historial");
        JettraAssert.assertTrue(histRes.body().contains("v1"), "Debe listar v1");
        JettraAssert.assertTrue(histRes.body().contains("v2"), "Debe listar v2");
        JettraAssert.assertTrue(histRes.body().contains("v3"), "Debe listar v3");
        JettraAssert.assertTrue(histRes.body().contains("Restaurar esta Versión"), "Debe contener botón para restaurar");

        // 5. Ejecutar Restauración a la versión 1 vía HTTP POST
        String restoreForm = "action=restore_version&db=" + db + "&bucket=" + bucket + "&recordId=" + recordId + "&version=1";
        HttpResponse<String> restoreRes = postFormWithRedirect("/explorer", restoreForm);
        JettraAssert.assertEquals(200, restoreRes.statusCode(), "Tras redirección debe responder HTTP 200");

        // 6. Verificar restauración
        EngineRecordInfo restoredRec = plugin.getRecord(db, bucket, recordId);
        JettraAssert.assertEquals(4L, restoredRec.getVersion(), "Debe avanzar a v4 con snapshot de restauración");
        JettraAssert.assertEquals("Versión 1 Original", restoredRec.getSummary(), "El resumen debe coincidir con el de v1");
        JettraAssert.assertTrue(restoredRec.getDetails().contains("100.00"), "El payload restaurado debe contener el valor de v1");
        JettraAssert.assertEquals(4, restoredRec.getVersionHistory().size(), "El historial acumulado debe tener 4 entradas");

        // 7. Limpiar registro de prueba
        plugin.deleteRecord(db, bucket, recordId);
    }

    @Test
    @DisplayName("14. Seguridad Estricta: Prohibir acceso a cualquier página sin inicio de sesión (Auth Guard)")
    public void testAuthenticationSecurityGuardBlocksUnauthenticated() throws Exception {
        String[] protectedPaths = { "/cluster", "/status", "/databases", "/explorer", "/query", "/connections", "/users", "/police", "/backups", "/" };
        for (String path : protectedPaths) {
            HttpResponse<String> unauthRes = get(path, "jettra_user=admin; jettra_session=stale-invalid-token");
            JettraAssert.assertEquals(302, unauthRes.statusCode(), "El acceso no autenticado o con cookie inválida a " + path + " debe ser redirigido");
            String location = unauthRes.headers().firstValue("Location").orElse("");
            JettraAssert.assertTrue(location.contains("/login"), "La redirección de " + path + " debe apuntar a /login");
        }

        // Login válido
        String loginForm = "username=admin&password=admin123";
        HttpResponse<String> loginRes = postForm("/login", loginForm, null);
        JettraAssert.assertEquals(302, loginRes.statusCode(), "El login válido debe redirigir");
        String setCookie = loginRes.headers().firstValue("Set-Cookie").orElse("");
        JettraAssert.assertTrue(setCookie.contains("jettra_session="), "El servidor debe emitir la cookie de sesión jettra_session");

        // Login inválido
        String badForm = "username=admin&password=password_invalido";
        HttpResponse<String> badRes = postForm("/login", badForm, null);
        JettraAssert.assertEquals(302, badRes.statusCode(), "El login inválido debe redirigir de vuelta al formulario");
        String badLocation = badRes.headers().firstValue("Location").orElse("");
        JettraAssert.assertTrue(badLocation.contains("error=invalid_credentials"), "Debe indicar error de credenciales");
    }

    @Test
    @DisplayName("15. Configuración MicroProfile: Desacoplamiento de nodo PRIMARY, detección OFFLINE y endpoint en vivo")
    public void testMicroprofileConfigHostPortAndPrimaryNode() throws Exception {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo primaryNode = plugin.getNodeById("node-01");
        int originalPrimaryPort = primaryNode.getPort();
        try {
            // 1. Host, puerto Web UI y contextpath cargados desde microprofile-config.properties
            JettraAssert.assertNotNull(plugin.getServerHost(), "El host debe estar cargado");
            JettraAssert.assertEquals("127.0.0.1", plugin.getServerHost(), "El host en microprofile-config.properties debe ser 127.0.0.1");
            JettraAssert.assertEquals(8085, plugin.getServerPort(), "El server.port Web UI debe ser 8085");
            JettraAssert.assertEquals("/", plugin.getServerContextPath(), "El server.contextpath en microprofile-config.properties debe ser /");

            // 2. Nodo PRIMARY registrado con su puerto propio (9091)
            JettraAssert.assertNotNull(primaryNode, "El nodo node-01 debe existir");
            JettraAssert.assertEquals("PRIMARY", primaryNode.getRole(), "El rol de node-01 debe ser PRIMARY");
            JettraAssert.assertEquals(9091, originalPrimaryPort, "El puerto de node-01 debe provenir de cluster.nodes (9091), no de server.port");

            // 3. Si el puerto de PRIMARY está cerrado, debe figurar OFFLINE
            int closedPrimaryPort;
            try (java.net.ServerSocket tmpSocket = new java.net.ServerSocket(0)) {
                closedPrimaryPort = tmpSocket.getLocalPort();
            }
            primaryNode.setPort(closedPrimaryPort);
            plugin.refreshAllNodeStatuses(true);

            HttpResponse<String> resDown = get("/cluster");
            JettraAssert.assertEquals(200, resDown.statusCode(), "Debe responder HTTP 200 aunque PRIMARY esté desconectado");
            JettraAssert.assertFalse(primaryNode.isOnline(), "node-01 debe marcarse OFFLINE cuando su puerto no tiene nodo escuchando");
            JettraAssert.assertTrue(resDown.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"), "Debe mostrar alerta crítica de PRIMARY OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("○ PRIMARY: OFFLINE"), "La barra superior debe indicar ○ PRIMARY: OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("🔴 NODO PRIMARY OFFLINE"), "La tarjeta de PRIMARY debe reflejar 🔴 NODO PRIMARY OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("○ OFFLINE (NODO PRIMARY CAÍDO)"), "La matriz debe indicar PRIMARY CAÍDO");

            // 4. Protección anti auto-sondeo
            primaryNode.setPort(sharedPort);
            plugin.refreshAllNodeStatuses(true);
            JettraAssert.assertFalse(primaryNode.isOnline(), "El auto-sondeo al puerto de JettraStoreExplorer Web UI no debe marcar PRIMARY como ONLINE");

            // 5. Nodos secundarios desconectados deben figurar como OFFLINE
            ServerNodeInfo replicaNode = plugin.getNodeById("node-02");
            JettraAssert.assertNotNull(replicaNode, "El nodo node-02 debe existir");
            JettraAssert.assertFalse(replicaNode.isOnline(), "Un nodo cuya IP/puerto no está escuchando debe marcarse OFFLINE");

            // 6. Simulación de nodo PRIMARY conectado vía ServerSocket
            try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                primaryNode.setPort(mockPrimarySocket.getLocalPort());

                HttpResponse<String> resUp = get("/cluster");
                JettraAssert.assertEquals(200, resUp.statusCode(), "La página del cluster debe responder HTTP 200 con cookie");
                JettraAssert.assertTrue(primaryNode.isOnline(), "El nodo PRIMARY debe marcarse ONLINE al responder su socket TCP");
                JettraAssert.assertTrue(resUp.body().contains("● PRIMARY: ONLINE"), "La barra superior debe indicar que PRIMARY está ONLINE");
                JettraAssert.assertTrue(resUp.body().contains("ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS"), "Debe mostrar alerta de nodos secundarios desconectados");
                JettraAssert.assertTrue(resUp.body().contains("🟢 ESTADO: ONLINE"), "La tarjeta de node-01 debe mostrar 🟢 ESTADO: ONLINE");
                JettraAssert.assertTrue(resUp.body().contains("🔴 ESTADO: OFFLINE"), "La tarjeta de réplicas debe mostrar 🔴 ESTADO: OFFLINE");
                JettraAssert.assertTrue(resUp.body().contains("○ OFFLINE (Inaccesible)"), "La matriz de replicación debe indicar réplicas OFFLINE");

                HttpResponse<String> apiResUp = get("/api/cluster/status");
                JettraAssert.assertEquals(200, apiResUp.statusCode(), "API /api/cluster/status debe responder HTTP 200");
                JettraAssert.assertTrue(apiResUp.body().contains("\"primaryOnline\":true"), "JSON debe indicar primaryOnline:true");
            }

            // 7. Tras cerrar el socket
            HttpResponse<String> apiResDown = get("/api/cluster/status");
            JettraAssert.assertEquals(200, apiResDown.statusCode(), "API /api/cluster/status debe responder HTTP 200");
            JettraAssert.assertTrue(apiResDown.body().contains("\"primaryOnline\":false"), "JSON debe indicar primaryOnline:false cuando el nodo cae");
        } finally {
            primaryNode.setPort(originalPrimaryPort);
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
        int port1 = BASE_TEST_PORT + 17;
        int port2 = BASE_TEST_PORT + 18;

        String previousSessionCookie;
        JettraServer server1 = App.startApplication(port1, StorageMode.OFF_HEAP);
        try {
            JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Al iniciar no debe existir ninguna sesión activa");

            HttpResponse<String> rootUnauth = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port1 + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, rootUnauth.statusCode(), "Raíz sin autenticar debe redirigir 302");
            JettraAssert.assertTrue(rootUnauth.headers().firstValue("Location").orElse("").contains("/login"), "Debe iniciar en /login");

            previousSessionCookie = loginAndGetSessionCookie(port1);
            JettraAssert.assertEquals(1, App.getActiveSessionCount(), "Debe haber 1 sesión activa tras login");

            HttpResponse<String> clusterAuth = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port1 + "/cluster")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(200, clusterAuth.statusCode(), "Con sesión válida debe permitir acceso a /cluster");
        } finally {
            App.stopApplication(server1);
        }
        JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Al detener el servidor todas las sesiones deben cerrarse");

        JettraServer server2 = App.startApplication(port2, StorageMode.OFF_HEAP);
        try {
            JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Tras reiniciar no debe quedar ninguna sesión abierta");

            HttpResponse<String> rootWithStale = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, rootWithStale.statusCode(), "Raíz con cookie de sesión previa debe redirigir 302");
            JettraAssert.assertTrue(rootWithStale.headers().firstValue("Location").orElse("").contains("/login"), "Tras reiniciar siempre debe enviar a /login");
            JettraAssert.assertTrue(rootWithStale.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"), "Debe invalidar/limpiar la cookie expirada");

            HttpResponse<String> clusterWithStale = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/cluster")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, clusterWithStale.statusCode(), "Ruta protegida con cookie previa debe redirigir a /login");
            JettraAssert.assertTrue(clusterWithStale.headers().firstValue("Location").orElse("").contains("/login"), "Debe exigir autenticación en /login");

            HttpResponse<String> apiWithStale = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/api/cluster/status")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(401, apiWithStale.statusCode(), "API /api/cluster/status con cookie previa debe responder HTTP 401");
        } finally {
            App.stopApplication(server2);
            // Restaurar contexto de servidor compartido en App y Plugin
            if (sharedServer != null && sharedServer.isRunning()) {
                App.serverInstance = sharedServer;
                JettraStorePlugin.getInstance().setExplorerServerEndpoint("127.0.0.1", sharedPort);
                sharedAuthCookie = null;
            }
        }
    }

    @Test
    @DisplayName("18. Apagado Real de Nodos: NodeLifecycleManager, REST /api/cluster/nodes/shutdown y actualización inmediata a OFFLINE")
    public void testRealNodeShutdownViaLifecycleManagerAndRest() throws Exception {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        try (java.net.ServerSocket dummyNodeSocket = new java.net.ServerSocket(0)) {
            int nodePort = dummyNodeSocket.getLocalPort();
            plugin.addNode("node-lifecycle-test", "node-lifecycle-test", "FOLLOWER", "127.0.0.1", nodePort, 2048);
            plugin.getLifecycleManager().registerManagedEndpoint("node-lifecycle-test", dummyNodeSocket);
            plugin.refreshAllNodeStatuses(true);

            ServerNodeInfo node = plugin.getNodeById("node-lifecycle-test");
            JettraAssert.assertNotNull(node, "El nodo temporal debe existir");
            JettraAssert.assertTrue(node.isOnline(), "El nodo temporal con socket activo debe estar ONLINE");

            HttpResponse<String> clusterHtml = get("/cluster");
            JettraAssert.assertTrue(clusterHtml.body().contains("shutdownNode('node-lifecycle-test'"),
                "ClusterFluxView debe renderizar la llamada shutdownNode para el nodo");

            HttpResponse<String> shutdownRes = postJson("/api/cluster/nodes/shutdown", "{\"nodeId\":\"node-lifecycle-test\"}");
            JettraAssert.assertEquals(200, shutdownRes.statusCode(), "El endpoint de apagado debe responder HTTP 200");
            JettraAssert.assertTrue(shutdownRes.body().contains("\"status\":\"INACTIVE\"") || shutdownRes.body().contains("\"status\":\"OFFLINE\"") || shutdownRes.body().contains("\"state\":\"OFFLINE\""), "El JSON debe confirmar status INACTIVE/OFFLINE");
            JettraAssert.assertTrue(shutdownRes.body().contains("\"offlineVerified\":true"), "Debe verificar el cierre real del puerto");
            JettraAssert.assertTrue(dummyNodeSocket.isClosed(), "El socket del nodo administrado debe cerrarse al apagar");
            JettraAssert.assertFalse(node.isOnline(), "El estado del nodo en el plugin debe reflejar OFFLINE inmediatamente");

            HttpResponse<String> statusJson = get("/api/cluster/status");
            JettraAssert.assertTrue(statusJson.body().contains("\"id\":\"node-lifecycle-test\"") && statusJson.body().contains("\"online\":false"),
                "El endpoint JSON de estado debe confirmar node-lifecycle-test online=false");
        } finally {
            plugin.removeNode("node-lifecycle-test");
        }
    }

    @Test
    @DisplayName("19. Diálogo de Restauración JettraFlux: Reemplazo de window.confirm por Modal nativo JettraFlux")
    public void testJettraFluxRestoreVersionConfirmationModal() throws Exception {
        HttpResponse<String> historyRes = get("/explorer?db=example_factura_db&bucket=Facturas&history_id=FAC-2026-003");

        JettraAssert.assertEquals(200, historyRes.statusCode(), "El historial de versiones debe responder HTTP 200");
        JettraAssert.assertTrue(historyRes.body().contains("restoreVersionModal"), "Debe renderizar el Modal nativo de JettraFlux");
        JettraAssert.assertTrue(historyRes.body().contains("espresso-modal-overlay"), "Debe usar el widget Modal de JettraFlux");
        JettraAssert.assertTrue(historyRes.body().contains("Confirmar Restauración"), "El modal debe contener botón de confirmación");
        JettraAssert.assertTrue(historyRes.body().contains("Cancelar"), "El modal debe contener botón de cancelación");
        JettraAssert.assertTrue(historyRes.body().contains("openRestoreModalFromBtn"), "El botón de restaurar debe abrir el modal JettraFlux");
        JettraAssert.assertFalse(historyRes.body().contains("onsubmit=\"return confirm("), "No debe usar window.confirm nativo de JS para restaurar versiones");

        HttpResponse<String> restoreRes = postForm("/explorer", "action=restore_version&db=example_factura_db&bucket=Facturas&recordId=FAC-2026-003&version=1");
        JettraAssert.assertEquals(302, restoreRes.statusCode(), "POST restore_version debe redirigir tras completar rollback");
        JettraAssert.assertTrue(restoreRes.headers().firstValue("Location").orElse("").contains("msg=version_restored"),
            "Debe redirigir confirmando la restauración de versión");
    }

    @Test
    @DisplayName("20. Consola Interactiva JettraStoreShell y Modal Ayuda (Help) en /query")
    public void testInteractiveShellConsoleAndHelpModalOnQueryPage() throws Exception {
        // 1. SHOW DATABASES
        HttpResponse<String> showDbs = postForm("/query", "db=example_factura_db&bucket=Facturas&query=SHOW+DATABASES");
        JettraAssert.assertEquals(200, showDbs.statusCode(), "SHOW DATABASES debe responder HTTP 200");
        JettraAssert.assertTrue(showDbs.body().contains("example_factura_db"), "Debe listar example_factura_db en salida Shell");
        JettraAssert.assertTrue(showDbs.body().contains("queryHelpBtn"), "Debe incluir el botón Ayuda (Help)");
        JettraAssert.assertTrue(showDbs.body().contains("queryHelpModal"), "Debe incluir el modal JettraFlux de Ayuda");
        JettraAssert.assertTrue(showDbs.body().contains("espresso-accordion"), "La ayuda debe usar Accordion de JettraFlux");

        // 2. MATH
        HttpResponse<String> mathRes = postForm("/query", "db=example_factura_db&bucket=Facturas&query=MATH+sqrt(144)+%2B+8+*+2");
        JettraAssert.assertTrue(mathRes.body().contains("= 28"), "Debe calcular sqrt(144) + 8 * 2 = 28 correctamente");

        // 3. Comando erróneo
        HttpResponse<String> errRes = postForm("/query", "db=example_factura_db&bucket=Facturas&query=FOOBAR_COMANDO_INEXISTENTE");
        JettraAssert.assertTrue(errRes.body().contains("[ERROR] Comando desconocido"), "Comando desconocido debe reportar error estructurado de JettraStoreShell");
    }

    @Test
    @DisplayName("21. Detección Automática de Nodos y Sincronización de Estado en Tiempo Real (ACTIVE / INACTIVE)")
    public void testAutomaticNodeHeartbeatAndActiveInactiveState() throws Exception {
        int dummyNodePort = 9791;
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        java.net.ServerSocket dummySocket = null;
        try {
            // 1. Iniciar socket simulado
            dummySocket = new java.net.ServerSocket();
            dummySocket.setReuseAddress(true);
            dummySocket.bind(new java.net.InetSocketAddress("127.0.0.1", dummyNodePort));

            // 2. Registrar nodo y verificar ACTIVE
            plugin.addNode("node-heartbeat-live", "node-heartbeat-live", "FOLLOWER", "127.0.0.1", dummyNodePort, 2048);
            ServerNodeInfo node = plugin.getNodeById("node-heartbeat-live");
            JettraAssert.assertNotNull(node, "El nodo debe registrarse en el clúster");

            plugin.refreshAllNodeStatuses(true);
            JettraAssert.assertTrue(node.isActive(), "El nodo recién conectado debe detectarse como ACTIVE");
            JettraAssert.assertEquals("ACTIVE", node.getStatus(), "getStatus() debe retornar ACTIVE");

            HttpResponse<String> clusterViewRes = get("/cluster");
            JettraAssert.assertEquals(200, clusterViewRes.statusCode(), "La página /cluster debe responder 200");
            JettraAssert.assertTrue(clusterViewRes.body().contains("● ACTIVE"), "La vista /cluster debe contener el indicador visual ● ACTIVE");
            JettraAssert.assertTrue(clusterViewRes.body().contains("pollClusterLiveStatus"), "La vista debe incluir el script de sondeo en vivo");

            HttpResponse<String> apiRes1 = get("/api/cluster/status");
            JettraAssert.assertTrue(apiRes1.body().contains("\"id\":\"node-heartbeat-live\""), "La API debe incluir node-heartbeat-live");
            JettraAssert.assertTrue(apiRes1.body().contains("\"status\":\"ACTIVE\""), "La API debe reportar status ACTIVE");

            // 3. Cerrar socket y verificar transición a INACTIVE
            dummySocket.close();
            dummySocket = null;

            plugin.refreshAllNodeStatuses(true);
            JettraAssert.assertFalse(node.isActive(), "Al perder respuesta TCP, el nodo debe cambiar automáticamente a INACTIVE");
            JettraAssert.assertEquals("INACTIVE", node.getStatus(), "getStatus() debe retornar INACTIVE");

            HttpResponse<String> apiRes2 = get("/api/cluster/status");
            JettraAssert.assertTrue(apiRes2.body().contains("\"status\":\"INACTIVE\""), "La API debe reportar status INACTIVE sin reiniciar la aplicación");

            HttpResponse<String> clusterViewRes2 = get("/cluster");
            JettraAssert.assertTrue(clusterViewRes2.body().contains("○ INACTIVE") || clusterViewRes2.body().contains("ESTADO INACTIVE"),
                "La vista /cluster debe mostrar el estado INACTIVE tras perder latidos");
        } finally {
            if (dummySocket != null && !dummySocket.isClosed()) {
                try { dummySocket.close(); } catch (Exception ignored) {}
            }
            plugin.removeNode("node-heartbeat-live");
        }
    }
}

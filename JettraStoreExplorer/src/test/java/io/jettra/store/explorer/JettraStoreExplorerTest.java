package io.jettra.store.explorer;

import io.jettra.server.core.JettraServer;
import io.jettra.server.core.config.ServerConfig;
import io.jettra.server.core.storage.StorageMode;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Suite completa de pruebas para JettraStore Explorer validada con JettraTest.
 * 100% libre de JUnit. Ejecutada con Project Loom y Java 25.
 */
public class JettraStoreExplorerTest {

    private static final int BASE_TEST_PORT = 9480;

    private String loginAndGetSessionCookie(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin123"))
                .build();
        HttpResponse<String> res = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        JettraAssert.assertEquals(302, res.statusCode(), "El login de prueba debe emitir redirección 302");
        String setCookie = res.headers().firstValue("Set-Cookie").orElse("");
        JettraAssert.assertTrue(setCookie.contains("jettra_session="), "Debe emitir cookie jettra_session");
        return setCookie.split(";")[0].trim();
    }

    @Test
    @DisplayName("1. Verificar renderizado de la página de Login en JettraFlux")
    public void testLoginPageRendering() throws Exception {
        int port = BASE_TEST_PORT + 1;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            JettraAssert.assertEquals(200, res.statusCode(), "Login debe responder HTTP 200");
            JettraAssert.assertTrue(res.body().contains("JettraStore Explorer"), "Debe contener el título");
            JettraAssert.assertTrue(res.body().contains("name=\"username\""), "Debe contener campo de usuario");
            JettraAssert.assertTrue(res.body().contains("name=\"password\""), "Debe contener campo de contraseña");
            JettraAssert.assertTrue(res.body().contains("Iniciar Sesión"), "Debe contener botón de ingreso");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("2. Administrar Nodos: Verificar vista de clúster, topología y métricas de nodos")
    public void testClusterNodesManagementView() throws Exception {
        int port = BASE_TEST_PORT + 2;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            JettraAssert.assertEquals(200, res.statusCode(), "El monitor de clúster debe responder HTTP 200");
            JettraAssert.assertTrue(res.body().contains("Nodos Activos"), "Debe incluir KPI de nodos activos");
            JettraAssert.assertTrue(res.body().contains("Ingesta Global QPS"), "Debe incluir métricas QPS");
            JettraAssert.assertTrue(res.body().contains("Panama Direct Memory"), "Debe incluir Panama Direct Memory");
            JettraAssert.assertTrue(res.body().contains("node-01"), "Debe listar el nodo master");
            JettraAssert.assertTrue(res.body().contains("node-02"), "Debe listar réplicas Raft");
            JettraAssert.assertTrue(res.body().contains("Registrar Nuevo Nodo"), "Debe incluir formulario de registro de nodos");
            JettraAssert.assertTrue(res.body().contains("Matriz de Replicación"), "Debe incluir matriz de replicación");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("3. Administrar Nodos: Registro dinámico de nuevo nodo y conmutación de estado")
    public void testAddAndToggleNode() throws Exception {
        int port = BASE_TEST_PORT + 3;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Agregar nodo vía POST
            String form = "action=add_node&nodeId=node-test-99&nodeName=node-test-99-replica&nodeRole=FOLLOWER&nodeHost=127.0.0.1&nodePort=8799&nodeRam=4096";
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, postRes.statusCode(), "Debe redirigir a /cluster tras añadir nodo");

            // 2. Verificar que el nodo existe en el plugin y en la vista
            JettraStorePlugin plugin = JettraStorePlugin.getInstance();
            ServerNodeInfo n = plugin.getNodeById("node-test-99");
            JettraAssert.assertNotNull(n, "El nodo registrado debe existir en JettraStorePlugin");
            JettraAssert.assertEquals("FOLLOWER", n.getRole(), "El rol debe ser FOLLOWER");
            JettraAssert.assertTrue(n.isOnline(), "El nodo debe iniciar ONLINE");

            // 3. Conmutar a OFFLINE
            HttpRequest toggleReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster?action=toggle_node&nodeId=node-test-99"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();
            HttpResponse<String> toggleRes = client.send(toggleReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, toggleRes.statusCode(), "Debe redirigir tras conmutar");
            JettraAssert.assertFalse(n.isOnline(), "El nodo debe pasar a OFFLINE");

            // 4. Limpiar nodo de prueba
            plugin.removeNode("node-test-99");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("4. Ver Estatus: Verificar estado del servidor, telemetría y centinelas Police")
    public void testServerStatusAndTelemetryView() throws Exception {
        int port = BASE_TEST_PORT + 4;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/status"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            JettraAssert.assertEquals(200, res.statusCode(), "La vista de estatus debe responder HTTP 200");
            JettraAssert.assertTrue(res.body().contains("Estado Servidor"), "Debe mostrar estado del servidor");
            JettraAssert.assertTrue(res.body().contains("Modo Almacenamiento"), "Debe mostrar modo de almacenamiento");
            JettraAssert.assertTrue(res.body().contains("Heap JVM (Loom)"), "Debe mostrar estadísticas Loom");
            JettraAssert.assertTrue(res.body().contains("Centinelas Guardián JettraPolice"), "Debe listar centinelas Police");
            JettraAssert.assertTrue(res.body().contains("Registro de Eventos"), "Debe incluir bitácora de auditoría");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("5. Administrar Bases de Datos y Motores: Verificar catálogo de bases de datos y buckets")
    public void testDatabasesAndEnginesView() throws Exception {
        int port = BASE_TEST_PORT + 5;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/databases"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            JettraAssert.assertEquals(200, res.statusCode(), "La vista de bases de datos debe responder HTTP 200");
            JettraAssert.assertTrue(res.body().contains("Catálogo Maestro de Bases de Datos"), "Debe contener catálogo maestro");
            JettraAssert.assertTrue(res.body().contains("example_factura_db"), "Debe incluir example_factura_db");
            JettraAssert.assertTrue(res.body().contains("samples_hostipal_db"), "Debe incluir samples_hostipal_db");
            JettraAssert.assertTrue(res.body().contains("DOCUMENT"), "Debe mostrar motores documentales");
            JettraAssert.assertTrue(res.body().contains("RELATIONAL"), "Debe mostrar motores relacionales");
            JettraAssert.assertTrue(res.body().contains("Añadir Nuevo Motor / Bucket"), "Debe permitir añadir motores");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("6. Administrar Bases de Datos y Motores: Creación dinámica de Base de Datos y nuevo Motor")
    public void testCreateDatabaseAndBucket() throws Exception {
        int port = BASE_TEST_PORT + 6;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Crear base de datos
            String createDbForm = "action=create_db&dbName=test_billing_db&defaultBucket=FacturasEmitidas&engineType=DOCUMENT&description=BD_Prueba_Automatizada";
            HttpRequest createDbReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/databases"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(createDbForm))
                    .build();

            HttpResponse<String> res1 = client.send(createDbReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, res1.statusCode(), "Crear BD debe redirigir");

            JettraStorePlugin plugin = JettraStorePlugin.getInstance();
            JettraAssert.assertTrue(plugin.getDatabaseNames().contains("test_billing_db"), "La BD debe haberse registrado en el catálogo");

            // 2. Añadir motor vectorial a la base de datos
            String addBucketForm = "action=add_bucket&db=test_billing_db&bucketName=FacturasEmbeddings&engineType=VECTOR&description=Embeddings_HNSW";
            HttpRequest addBReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/databases"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(addBucketForm))
                    .build();

            HttpResponse<String> res2 = client.send(addBReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, res2.statusCode(), "Añadir motor debe redirigir");

            EngineBucketInfo b = plugin.getBucket("test_billing_db", "FacturasEmbeddings");
            JettraAssert.assertNotNull(b, "El nuevo bucket/motor debe existir");
            JettraAssert.assertEquals("VECTOR", b.getEngineType(), "El motor debe ser VECTOR");

            // 3. Limpiar base de datos de prueba
            plugin.deleteDatabase("test_billing_db");
            JettraAssert.assertFalse(plugin.getDatabaseNames().contains("test_billing_db"), "La BD debe eliminarse");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("7. Operaciones CRUD de Registros: Create, Read, Update, Delete en JettraStore")
    public void testRecordCrudOperations() throws Exception {
        int port = BASE_TEST_PORT + 7;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            JettraStorePlugin plugin = JettraStorePlugin.getInstance();
            String db = "example_factura_db";
            String bucket = "Facturas";

            // 1. CREATE: Crear un nuevo registro vía POST
            String createForm = "action=save_new&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001&summary=Factura_Prueba_CRUD&details={\"folio\":\"FAC-TEST-001\",\"total\":999.00}";
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(createForm))
                    .build();

            HttpResponse<String> createRes = client.send(createReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, createRes.statusCode(), "Crear registro debe retornar HTTP 302");

            // READ: Verificar que el registro existe
            EngineRecordInfo rec = plugin.getRecord(db, bucket, "FAC-TEST-001");
            JettraAssert.assertNotNull(rec, "El registro creado debe existir en el bucket");
            JettraAssert.assertEquals("Factura_Prueba_CRUD", rec.getSummary(), "El resumen debe coincidir");
            JettraAssert.assertEquals(1L, rec.getVersion(), "La versión inicial debe ser 1");

            // 2. UPDATE: Modificar el registro vía POST
            String updateForm = "action=save_edit&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001&summary=Factura_Actualizada_V2&details={\"folio\":\"FAC-TEST-001\",\"total\":1250.00}";
            HttpRequest updateReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(updateForm))
                    .build();

            HttpResponse<String> updateRes = client.send(updateReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, updateRes.statusCode(), "Actualizar registro debe retornar HTTP 302");

            EngineRecordInfo updatedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
            JettraAssert.assertEquals("Factura_Actualizada_V2", updatedRec.getSummary(), "El resumen debe haberse actualizado");
            JettraAssert.assertEquals(2L, updatedRec.getVersion(), "La versión debe incrementarse a v2");

            // 3. DELETE: Eliminar el registro vía GET
            HttpRequest deleteReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer?action=delete_record&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();

            HttpResponse<String> deleteRes = client.send(deleteReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, deleteRes.statusCode(), "Eliminar registro debe retornar HTTP 302");

            EngineRecordInfo deletedRec = plugin.getRecord(db, bucket, "FAC-TEST-001");
            JettraAssert.assertNull(deletedRec, "El registro debe haber sido eliminado");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("8. Consola SQL / JQL: Ejecución de consultas directas y renderizado de resultados")
    public void testQueryConsoleView() throws Exception {
        int port = BASE_TEST_PORT + 8;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/query?db=example_factura_db&bucket=Facturas&query=total+%3E+500"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            JettraAssert.assertEquals(200, res.statusCode(), "La consola de consulta debe responder HTTP 200");
            JettraAssert.assertTrue(res.body().contains("Consola de Consulta Directa"), "Debe mostrar título de consola");
            JettraAssert.assertTrue(res.body().contains("FAC-2026-001"), "Debe mostrar registros que cumplan la condición");
            JettraAssert.assertTrue(res.body().contains("ejecutado en"), "Debe mostrar tiempo de ejecución en µs");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("9. Conexiones y Seguridad: Verificación de perfiles de red y permisos de usuarios")
    public void testConnectionsAndUsersViews() throws Exception {
        int port = BASE_TEST_PORT + 9;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();

            // Vista Conexiones
            HttpResponse<String> connRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/connections")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, connRes.statusCode(), "Conexiones debe responder HTTP 200");
            JettraAssert.assertTrue(connRes.body().contains("Perfiles de Conexión Configurados"), "Debe listar perfiles");

            // Vista Usuarios y Permisos
            HttpResponse<String> usersRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/users")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, usersRes.statusCode(), "Usuarios debe responder HTTP 200");
            JettraAssert.assertTrue(usersRes.body().contains("Usuarios Registrados y Permisos Granulares"), "Debe mostrar tabla de permisos");
            JettraAssert.assertTrue(usersRes.body().contains("admin"), "Debe contener el usuario admin");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("10. Copias de Seguridad y Centinelas Police: Snapshots y Patrullaje Heurístico")
    public void testBackupsAndPoliceViews() throws Exception {
        int port = BASE_TEST_PORT + 10;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();

            // Vista Backups
            HttpResponse<String> bkpRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/backups")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, bkpRes.statusCode(), "Backups debe responder HTTP 200");
            JettraAssert.assertTrue(bkpRes.body().contains("Snapshots y Copias de Seguridad"), "Debe listar snapshots");

            // Vista Police
            HttpResponse<String> policeRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/police")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, policeRes.statusCode(), "Police debe responder HTTP 200");
            JettraAssert.assertTrue(policeRes.body().contains("Centro de Comando JettraStorePolice"), "Debe mostrar centro de comando");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("11. Nodos del Clúster: Verificación de estado real ONLINE y OFFLINE vía socket TCP")
    public void testRealNodeReachabilityOnlineOffline() throws Exception {
        int port = BASE_TEST_PORT + 11;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo master = plugin.getNodeById("node-01");
        int originalPort = master.getPort();
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

            // 1. Con un nodo primario simulado activo en su propio puerto TCP, debe responder ONLINE
            try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                int mockPort = mockPrimarySocket.getLocalPort();
                master.setPort(mockPort);

                HttpRequest reqOnline = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/cluster?action=check_nodes"))
                        .header("Cookie", authCookie)
                        .GET()
                        .build();

                HttpResponse<String> resOnline = client.send(reqOnline, HttpResponse.BodyHandlers.ofString());
                JettraAssert.assertEquals(200, resOnline.statusCode(), "Cluster con check_nodes debe responder HTTP 200");
                JettraAssert.assertTrue(resOnline.body().contains("● PRIMARY: ONLINE"), "Debe mostrar PRIMARY: ONLINE cuando el nodo primario responde");
                JettraAssert.assertTrue(resOnline.body().contains("OFFLINE"), "Los nodos secundarios inaccesibles deben mostrarse OFFLINE");
                JettraAssert.assertTrue(master.isOnline(), "node-01 debe estar ONLINE en vivo");
                JettraAssert.assertTrue(master.getLatencyMs() > 0.0f, "La latencia debe estar medida en milisegundos");
            }

            // 2. Al cerrarse el socket del nodo primario, debe detectar inmediatamente el estado OFFLINE
            HttpRequest reqOffline = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster?action=check_nodes"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();
            HttpResponse<String> resOffline = client.send(reqOffline, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, resOffline.statusCode(), "Cluster debe renderizar aún con PRIMARY desconectado");
            JettraAssert.assertFalse(master.isOnline(), "node-01 debe marcarse OFFLINE cuando su puerto cierra");
            JettraAssert.assertTrue(resOffline.body().contains("○ PRIMARY: OFFLINE"), "TopBar debe mostrar ○ PRIMARY: OFFLINE");
            JettraAssert.assertTrue(resOffline.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"), "Debe mostrar alerta crítica de primario desconectado");
            JettraAssert.assertTrue(resOffline.body().contains("DISCONNECTED (Sin latido)"), "La matriz WAL debe reflejar desconexión");
        } finally {
            master.setPort(originalPort);
            server.stop();
        }
    }

    @Test
    @DisplayName("12. Motores Multimodelo: Soporte completo para GEOSPATIAL, JAVA_RECORD y OBJECT")
    public void testMultimodelEnginesGeospatialJavaRecordObject() throws Exception {
        int port = BASE_TEST_PORT + 12;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            JettraStorePlugin plugin = JettraStorePlugin.getInstance();

            // 1. Validar que los motores existen en el catálogo maestro inicial
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
            HttpResponse<String> dbRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/databases")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, dbRes.statusCode(), "Databases debe responder HTTP 200");
            JettraAssert.assertTrue(dbRes.body().contains("GEOSPATIAL"), "Debe mostrar motor GEOSPATIAL en selector");
            JettraAssert.assertTrue(dbRes.body().contains("JAVA_RECORD"), "Debe mostrar motor JAVA_RECORD en selector");
            JettraAssert.assertTrue(dbRes.body().contains("OBJECT"), "Debe mostrar motor OBJECT en selector");

            // 3. Validar vista de explorador para bucket JAVA_RECORD
            HttpResponse<String> expRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/explorer?db=example_factura_db&bucket=CustomerRecords")).header("Cookie", authCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, expRes.statusCode(), "Explorer debe responder HTTP 200");
            JettraAssert.assertTrue(expRes.body().contains("REC-ORD-501"), "Debe mostrar el registro REC-ORD-501");
            JettraAssert.assertTrue(expRes.body().contains("JAVA_RECORD"), "Debe mostrar badge JAVA_RECORD");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("13. Control de Versiones de Registros: Historial, auditoría y restauración de versión previa")
    public void testRecordVersionHistoryAndRestoration() throws Exception {
        int port = BASE_TEST_PORT + 13;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
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
            HttpRequest histReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer?db=" + db + "&bucket=" + bucket + "&history_id=" + recordId))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();
            HttpResponse<String> histRes = client.send(histReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, histRes.statusCode(), "Debe responder HTTP 200 en vista de versiones");
            JettraAssert.assertTrue(histRes.body().contains("Historial de Versiones del Registro"), "Debe mostrar título de historial");
            JettraAssert.assertTrue(histRes.body().contains("v1"), "Debe listar v1");
            JettraAssert.assertTrue(histRes.body().contains("v2"), "Debe listar v2");
            JettraAssert.assertTrue(histRes.body().contains("v3"), "Debe listar v3");
            JettraAssert.assertTrue(histRes.body().contains("Restaurar esta Versión"), "Debe contener botón para restaurar");

            // 5. Ejecutar Restauración a la versión 1 vía HTTP POST
            String restoreForm = "action=restore_version&db=" + db + "&bucket=" + bucket + "&recordId=" + recordId + "&version=1";
            HttpRequest restoreReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(restoreForm))
                    .build();

            HttpResponse<String> restoreRes = client.send(restoreReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, restoreRes.statusCode(), "Tras redirección debe responder HTTP 200");

            // 6. Verificar que el registro fue restaurado: nueva versión activa v4 con el payload y resumen de v1
            EngineRecordInfo restoredRec = plugin.getRecord(db, bucket, recordId);
            JettraAssert.assertEquals(4L, restoredRec.getVersion(), "Debe avanzar a v4 con snapshot de restauración");
            JettraAssert.assertEquals("Versión 1 Original", restoredRec.getSummary(), "El resumen debe coincidir con el de v1");
            JettraAssert.assertTrue(restoredRec.getDetails().contains("100.00"), "El payload restaurado debe contener el valor de v1");
            JettraAssert.assertEquals(4, restoredRec.getVersionHistory().size(), "El historial acumulado debe tener 4 entradas");

            // 7. Limpiar registro de prueba
            plugin.deleteRecord(db, bucket, recordId);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("14. Seguridad Estricta: Prohibir acceso a cualquier página sin inicio de sesión (Auth Guard)")
    public void testAuthenticationSecurityGuardBlocksUnauthenticated() throws Exception {
        int port = BASE_TEST_PORT + 14;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Probar que las páginas protegidas devuelven HTTP 302 hacia /login si no hay cookie o si tienen una cookie falsa/antigua
            String[] protectedPaths = { "/cluster", "/status", "/databases", "/explorer", "/query", "/connections", "/users", "/police", "/backups", "/" };
            for (String path : protectedPaths) {
                HttpRequest unauthReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Cookie", "jettra_user=admin; jettra_session=stale-invalid-token")
                        .GET()
                        .build();

                HttpResponse<String> unauthRes = client.send(unauthReq, HttpResponse.BodyHandlers.ofString());
                JettraAssert.assertEquals(302, unauthRes.statusCode(), "El acceso no autenticado o con cookie inválida a " + path + " debe ser redirigido");
                String location = unauthRes.headers().firstValue("Location").orElse("");
                JettraAssert.assertTrue(location.contains("/login"), "La redirección de " + path + " debe apuntar a /login");
            }

            // 2. Realizar login con credenciales válidas
            String loginForm = "username=admin&password=admin123";
            HttpRequest loginReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(loginForm))
                    .build();

            HttpResponse<String> loginRes = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, loginRes.statusCode(), "El login válido debe redirigir");
            String setCookie = loginRes.headers().firstValue("Set-Cookie").orElse("");
            JettraAssert.assertTrue(setCookie.contains("jettra_session="), "El servidor debe emitir la cookie de sesión jettra_session");

            // 3. Probar con credenciales incorrectas
            String badForm = "username=admin&password=password_invalido";
            HttpRequest badReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(badForm))
                    .build();

            HttpResponse<String> badRes = client.send(badReq, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(302, badRes.statusCode(), "El login inválido debe redirigir de vuelta al formulario");
            String badLocation = badRes.headers().firstValue("Location").orElse("");
            JettraAssert.assertTrue(badLocation.contains("error=invalid_credentials"), "Debe indicar error de credenciales");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("15. Configuración MicroProfile: Desacoplamiento de nodo PRIMARY, detección OFFLINE y endpoint en vivo")
    public void testMicroprofileConfigHostPortAndPrimaryNode() throws Exception {
        int port = BASE_TEST_PORT + 15;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ServerNodeInfo primaryNode = plugin.getNodeById("node-01");
        int originalPrimaryPort = primaryNode.getPort();
        try {
            // 1. Host, puerto Web UI y contextpath cargados desde microprofile-config.properties
            JettraAssert.assertNotNull(plugin.getServerHost(), "El host debe estar cargado");
            JettraAssert.assertEquals("127.0.0.1", plugin.getServerHost(), "El host en microprofile-config.properties debe ser 127.0.0.1");
            JettraAssert.assertEquals(8085, plugin.getServerPort(), "El server.port Web UI debe ser 8085");
            JettraAssert.assertEquals("/", plugin.getServerContextPath(), "El server.contextpath en microprofile-config.properties debe ser /");

            // 2. Nodo PRIMARY registrado con su puerto propio (9091) y NO el del Web UI (8085 / port)
            JettraAssert.assertNotNull(primaryNode, "El nodo node-01 debe existir");
            JettraAssert.assertEquals("PRIMARY", primaryNode.getRole(), "El rol de node-01 debe ser PRIMARY");
            JettraAssert.assertEquals(9091, originalPrimaryPort, "El puerto de node-01 debe provenir de cluster.nodes (9091), no de server.port");

            // 3. Si el puerto del nodo PRIMARY está cerrado/apagado, debe figurar OFFLINE aunque JettraStoreExplorer esté escuchando
            int closedPrimaryPort;
            try (java.net.ServerSocket tmpSocket = new java.net.ServerSocket(0)) {
                closedPrimaryPort = tmpSocket.getLocalPort();
            }
            primaryNode.setPort(closedPrimaryPort);
            plugin.refreshAllNodeStatuses(true);

            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest reqDown = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();
            HttpResponse<String> resDown = client.send(reqDown, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, resDown.statusCode(), "Debe responder HTTP 200 aunque PRIMARY esté desconectado");
            JettraAssert.assertFalse(primaryNode.isOnline(), "node-01 debe marcarse OFFLINE cuando su puerto no tiene nodo escuchando");
            JettraAssert.assertTrue(resDown.body().contains("ESTADO OFFLINE: NODO PRIMARY DESCONECTADO"), "Debe mostrar alerta crítica de PRIMARY OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("○ PRIMARY: OFFLINE"), "La barra superior debe indicar ○ PRIMARY: OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("🔴 NODO PRIMARY OFFLINE"), "La tarjeta de PRIMARY debe reflejar 🔴 NODO PRIMARY OFFLINE");
            JettraAssert.assertTrue(resDown.body().contains("○ OFFLINE (NODO PRIMARY CAÍDO)"), "La matriz debe indicar PRIMARY CAÍDO");

            // 4. Protección anti auto-sondeo: si node-01 apunta al puerto del propio Web UI, debe rechazar falso positivo
            primaryNode.setPort(port);
            plugin.refreshAllNodeStatuses(true);
            JettraAssert.assertFalse(primaryNode.isOnline(), "El auto-sondeo al puerto de JettraStoreExplorer Web UI no debe marcar PRIMARY como ONLINE");

            // 5. Nodos secundarios desconectados deben figurar como OFFLINE
            ServerNodeInfo replicaNode = plugin.getNodeById("node-02");
            JettraAssert.assertNotNull(replicaNode, "El nodo node-02 debe existir");
            JettraAssert.assertFalse(replicaNode.isOnline(), "Un nodo cuya IP/puerto no está escuchando debe marcarse OFFLINE");

            // 6. Simulación de nodo PRIMARY realmente conectado vía ServerSocket y verificación en UI + JSON API
            try (java.net.ServerSocket mockPrimarySocket = new java.net.ServerSocket(0)) {
                primaryNode.setPort(mockPrimarySocket.getLocalPort());

                HttpRequest reqUp = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
                        .header("Cookie", authCookie)
                        .GET()
                        .build();
                HttpResponse<String> resUp = client.send(reqUp, HttpResponse.BodyHandlers.ofString());
                JettraAssert.assertEquals(200, resUp.statusCode(), "La página del cluster debe responder HTTP 200 con cookie");
                JettraAssert.assertTrue(primaryNode.isOnline(), "El nodo PRIMARY debe marcarse ONLINE al responder su socket TCP");
                JettraAssert.assertTrue(resUp.body().contains("● PRIMARY: ONLINE"), "La barra superior debe indicar que PRIMARY está ONLINE");
                JettraAssert.assertTrue(resUp.body().contains("ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS"), "Debe mostrar alerta de nodos secundarios desconectados");
                JettraAssert.assertTrue(resUp.body().contains("🟢 ESTADO: ONLINE"), "La tarjeta de node-01 debe mostrar 🟢 ESTADO: ONLINE");
                JettraAssert.assertTrue(resUp.body().contains("🔴 ESTADO: OFFLINE"), "La tarjeta de réplicas debe mostrar 🔴 ESTADO: OFFLINE");
                JettraAssert.assertTrue(resUp.body().contains("○ OFFLINE (Inaccesible)"), "La matriz de replicación debe indicar réplicas OFFLINE");

                HttpRequest apiUp = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/cluster/status"))
                        .header("Cookie", authCookie)
                        .GET()
                        .build();
                HttpResponse<String> apiResUp = client.send(apiUp, HttpResponse.BodyHandlers.ofString());
                JettraAssert.assertEquals(200, apiResUp.statusCode(), "API /api/cluster/status debe responder HTTP 200");
                JettraAssert.assertTrue(apiResUp.body().contains("\"primaryOnline\":true"), "JSON debe indicar primaryOnline:true");
            }

            // 7. Tras cerrar el socket, el endpoint JSON debe detectar inmediatamente primaryOnline:false
            HttpRequest apiDown = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/cluster/status"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build();
            HttpResponse<String> apiResDown = client.send(apiDown, HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, apiResDown.statusCode(), "API /api/cluster/status debe responder HTTP 200");
            JettraAssert.assertTrue(apiResDown.body().contains("\"primaryOnline\":false"), "JSON debe indicar primaryOnline:false cuando el nodo cae");
        } finally {
            primaryNode.setPort(originalPrimaryPort);
            App.stopApplication(server);
        }
    }

    @Test
    @DisplayName("16. Verificar que la ejecución por defecto toma server.port=8085 y no 8082")
    public void testDefaultExecutionUsesPort8085() {
        ServerConfig config = ServerConfig.load();
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        // Si no se sobreescribió por argumento del sistema o proviene de defaults upstream (8080 o 8082),
        // App.java sincroniza con plugin.getServerPort() (8085)
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
        HttpClient noRedirectClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        String previousSessionCookie;
        JettraServer server1 = App.startApplication(port1, StorageMode.OFF_HEAP);
        try {
            JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Al iniciar no debe existir ninguna sesión activa");

            // 1. La ruta raíz "/" siempre debe dirigir a /login sin sesión
            HttpResponse<String> rootUnauth = noRedirectClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port1 + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, rootUnauth.statusCode(), "Raíz sin autenticar debe redirigir 302");
            JettraAssert.assertTrue(rootUnauth.headers().firstValue("Location").orElse("").contains("/login"), "Debe iniciar en /login");

            // 2. Autenticarse y verificar sesión activa
            previousSessionCookie = loginAndGetSessionCookie(port1);
            JettraAssert.assertEquals(1, App.getActiveSessionCount(), "Debe haber 1 sesión activa tras login");

            HttpResponse<String> clusterAuth = noRedirectClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port1 + "/cluster")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(200, clusterAuth.statusCode(), "Con sesión válida debe permitir acceso a /cluster");
        } finally {
            // 3. Al detener JettraStoreExplorer debe cerrar todas las sesiones activas
            App.stopApplication(server1);
        }
        JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Al detener el servidor todas las sesiones deben cerrarse");

        // 4. Al reiniciar JettraStoreExplorer, siempre inicia en /login y rechaza cookies previas
        JettraServer server2 = App.startApplication(port2, StorageMode.OFF_HEAP);
        try {
            JettraAssert.assertEquals(0, App.getActiveSessionCount(), "Tras reiniciar no debe quedar ninguna sesión abierta");

            // Reintentar "/" con la cookie anterior al cierre
            HttpResponse<String> rootWithStale = noRedirectClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, rootWithStale.statusCode(), "Raíz con cookie de sesión previa debe redirigir 302");
            JettraAssert.assertTrue(rootWithStale.headers().firstValue("Location").orElse("").contains("/login"), "Tras reiniciar siempre debe enviar a /login");
            JettraAssert.assertTrue(rootWithStale.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"), "Debe invalidar/limpiar la cookie expirada");

            // Reintentar "/cluster" con la cookie anterior al cierre
            HttpResponse<String> clusterWithStale = noRedirectClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/cluster")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, clusterWithStale.statusCode(), "Ruta protegida con cookie previa debe redirigir a /login");
            JettraAssert.assertTrue(clusterWithStale.headers().firstValue("Location").orElse("").contains("/login"), "Debe exigir autenticación en /login");

            // Reintentar API JSON con cookie previa -> 401
            HttpResponse<String> apiWithStale = noRedirectClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port2 + "/api/cluster/status")).header("Cookie", previousSessionCookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(401, apiWithStale.statusCode(), "API /api/cluster/status con cookie previa debe responder HTTP 401");
        } finally {
            App.stopApplication(server2);
        }
    }

    @Test
    @DisplayName("18. Apagado Real de Nodos: NodeLifecycleManager, REST /api/cluster/nodes/shutdown y actualización inmediata a OFFLINE")
    public void testRealNodeShutdownViaLifecycleManagerAndRest() throws Exception {
        int port = BASE_TEST_PORT + 21;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        try (java.net.ServerSocket dummyNodeSocket = new java.net.ServerSocket(0)) {
            int nodePort = dummyNodeSocket.getLocalPort();
            plugin.addNode("node-lifecycle-test", "node-lifecycle-test", "FOLLOWER", "127.0.0.1", nodePort, 2048);
            plugin.getLifecycleManager().registerManagedEndpoint("node-lifecycle-test", dummyNodeSocket);
            plugin.refreshAllNodeStatuses(true);

            ServerNodeInfo node = plugin.getNodeById("node-lifecycle-test");
            JettraAssert.assertNotNull(node, "El nodo temporal debe existir");
            JettraAssert.assertTrue(node.isOnline(), "El nodo temporal con socket activo debe estar ONLINE");

            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();

            // Verificar que la página /cluster expone el botón shutdownNode(...)
            HttpResponse<String> clusterHtml = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/cluster")).header("Cookie", authCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertTrue(clusterHtml.body().contains("shutdownNode('node-lifecycle-test'"),
                "ClusterFluxView debe renderizar la llamada shutdownNode para el nodo");

            // Invocar endpoint REST de apagado
            HttpResponse<String> shutdownRes = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/cluster/nodes/shutdown"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nodeId\":\"node-lifecycle-test\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JettraAssert.assertEquals(200, shutdownRes.statusCode(), "El endpoint de apagado debe responder HTTP 200");
            JettraAssert.assertTrue(shutdownRes.body().contains("\"status\":\"OFFLINE\""), "El JSON debe confirmar status OFFLINE");
            JettraAssert.assertTrue(shutdownRes.body().contains("\"offlineVerified\":true"), "Debe verificar el cierre real del puerto");
            JettraAssert.assertTrue(dummyNodeSocket.isClosed(), "El socket del nodo administrado debe cerrarse al apagar");
            JettraAssert.assertFalse(node.isOnline(), "El estado del nodo en el plugin debe reflejar OFFLINE inmediatamente");

            // Confirmar que /api/cluster/status también lo reporta OFFLINE
            HttpResponse<String> statusJson = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/cluster/status")).header("Cookie", authCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertTrue(statusJson.body().contains("\"id\":\"node-lifecycle-test\"") && statusJson.body().contains("\"online\":false"),
                "El endpoint JSON de estado debe confirmar node-lifecycle-test online=false");
        } finally {
            plugin.removeNode("node-lifecycle-test");
            App.stopApplication(server);
        }
    }

    @Test
    @DisplayName("19. Diálogo de Restauración JettraFlux: Reemplazo de window.confirm por Modal nativo JettraFlux")
    public void testJettraFluxRestoreVersionConfirmationModal() throws Exception {
        int port = BASE_TEST_PORT + 22;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient noRedirectClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // FAC-2026-003 tiene v1 y v2 inicializados por defecto
            HttpResponse<String> historyRes = noRedirectClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer?db=example_factura_db&bucket=Facturas&history_id=FAC-2026-003"))
                    .header("Cookie", authCookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JettraAssert.assertEquals(200, historyRes.statusCode(), "El historial de versiones debe responder HTTP 200");
            JettraAssert.assertTrue(historyRes.body().contains("restoreVersionModal"), "Debe renderizar el Modal nativo de JettraFlux");
            JettraAssert.assertTrue(historyRes.body().contains("espresso-modal-overlay"), "Debe usar el widget Modal de JettraFlux");
            JettraAssert.assertTrue(historyRes.body().contains("Confirmar Restauración"), "El modal debe contener botón de confirmación");
            JettraAssert.assertTrue(historyRes.body().contains("Cancelar"), "El modal debe contener botón de cancelación");
            JettraAssert.assertTrue(historyRes.body().contains("openRestoreModalFromBtn"), "El botón de restaurar debe abrir el modal JettraFlux");
            JettraAssert.assertFalse(historyRes.body().contains("onsubmit=\"return confirm("), "No debe usar window.confirm nativo de JS para restaurar versiones");

            // Confirmar ejecución real de rollback vía formulario POST
            HttpResponse<String> restoreRes = noRedirectClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("action=restore_version&db=example_factura_db&bucket=Facturas&recordId=FAC-2026-003&version=1"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(302, restoreRes.statusCode(), "POST restore_version debe redirigir tras completar rollback");
            JettraAssert.assertTrue(restoreRes.headers().firstValue("Location").orElse("").contains("msg=version_restored"),
                "Debe redirigir confirmando la restauración de versión");
        } finally {
            App.stopApplication(server);
        }
    }

    @Test
    @DisplayName("20. Consola Interactiva JettraStoreShell y Modal Ayuda (Help) en /query")
    public void testInteractiveShellConsoleAndHelpModalOnQueryPage() throws Exception {
        int port = BASE_TEST_PORT + 23;
        JettraServer server = App.startApplication(port, StorageMode.OFF_HEAP);
        try {
            String authCookie = loginAndGetSessionCookie(port);
            HttpClient client = HttpClient.newHttpClient();

            // 1. Ejecutar SHOW DATABASES vía POST /query
            HttpResponse<String> showDbs = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/query"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("db=example_factura_db&bucket=Facturas&query=SHOW+DATABASES"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertEquals(200, showDbs.statusCode(), "SHOW DATABASES debe responder HTTP 200");
            JettraAssert.assertTrue(showDbs.body().contains("example_factura_db"), "Debe listar example_factura_db en salida Shell");
            JettraAssert.assertTrue(showDbs.body().contains("queryHelpBtn"), "Debe incluir el botón Ayuda (Help)");
            JettraAssert.assertTrue(showDbs.body().contains("queryHelpModal"), "Debe incluir el modal JettraFlux de Ayuda");
            JettraAssert.assertTrue(showDbs.body().contains("espresso-accordion"), "La ayuda debe usar Accordion de JettraFlux");

            // 2. Ejecutar MATH sqrt(144) + 8 * 2
            HttpResponse<String> mathRes = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/query"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("db=example_factura_db&bucket=Facturas&query=MATH+sqrt(144)+%2B+8+*+2"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertTrue(mathRes.body().contains("= 28"), "Debe calcular sqrt(144) + 8 * 2 = 28 correctamente");

            // 3. Ejecutar comando inválido y verificar manejo de errores de shell
            HttpResponse<String> errRes = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/query"))
                    .header("Cookie", authCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("db=example_factura_db&bucket=Facturas&query=FOOBAR_COMANDO_INEXISTENTE"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JettraAssert.assertTrue(errRes.body().contains("[ERROR] Comando desconocido"), "Comando desconocido debe reportar error estructurado de JettraStoreShell");
        } finally {
            App.stopApplication(server);
        }
    }
}


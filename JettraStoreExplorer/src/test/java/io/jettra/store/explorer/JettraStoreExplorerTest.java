package io.jettra.store.explorer;

import io.jettra.server.core.JettraServer;
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
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
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
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Agregar nodo vía POST
            String form = "action=add_node&nodeId=node-test-99&nodeName=node-test-99-replica&nodeRole=FOLLOWER&nodeHost=127.0.0.1&nodePort=8799&nodeRam=4096";
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/cluster"))
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
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/status"))
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
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/databases"))
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
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Crear base de datos
            String createDbForm = "action=create_db&dbName=test_billing_db&defaultBucket=FacturasEmitidas&engineType=DOCUMENT&description=BD_Prueba_Automatizada";
            HttpRequest createDbReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/databases"))
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
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            JettraStorePlugin plugin = JettraStorePlugin.getInstance();
            String db = "example_factura_db";
            String bucket = "Facturas";

            // 1. CREATE: Crear un nuevo registro vía POST
            String createForm = "action=save_new&db=" + db + "&bucket=" + bucket + "&recordId=FAC-TEST-001&summary=Factura_Prueba_CRUD&details={\"folio\":\"FAC-TEST-001\",\"total\":999.00}";
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/explorer"))
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
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/query?db=example_factura_db&bucket=Facturas&query=total+%3E+500"))
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
            HttpClient client = HttpClient.newHttpClient();

            // Vista Conexiones
            HttpResponse<String> connRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/connections")).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, connRes.statusCode(), "Conexiones debe responder HTTP 200");
            JettraAssert.assertTrue(connRes.body().contains("Perfiles de Conexión Configurados"), "Debe listar perfiles");

            // Vista Usuarios y Permisos
            HttpResponse<String> usersRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/users")).GET().build(), HttpResponse.BodyHandlers.ofString());
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
            HttpClient client = HttpClient.newHttpClient();

            // Vista Backups
            HttpResponse<String> bkpRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/backups")).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, bkpRes.statusCode(), "Backups debe responder HTTP 200");
            JettraAssert.assertTrue(bkpRes.body().contains("Snapshots y Copias de Seguridad"), "Debe listar snapshots");

            // Vista Police
            HttpResponse<String> policeRes = client.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/police")).GET().build(), HttpResponse.BodyHandlers.ofString());
            JettraAssert.assertEquals(200, policeRes.statusCode(), "Police debe responder HTTP 200");
            JettraAssert.assertTrue(policeRes.body().contains("Centro de Comando JettraStorePolice"), "Debe mostrar centro de comando");
        } finally {
            server.stop();
        }
    }
}


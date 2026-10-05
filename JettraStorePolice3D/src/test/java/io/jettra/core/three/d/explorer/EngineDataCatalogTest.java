package io.jettra.core.three.d.explorer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EngineDataCatalogTest {

    @Test
    @DisplayName("Debe listar engines soportados para example_factura_db")
    public void testSupportedEnginesFacturaDb() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        List<String> engines = catalog.getSupportedEngines("example_factura_db");

        assertTrue(engines.contains("DOCUMENT"), "Debe soportar Document Engine");
        assertTrue(engines.contains("GRAPH"), "Debe soportar Graph Engine");
        assertTrue(engines.contains("VECTOR"), "Debe soportar Vector Engine");
        assertTrue(engines.contains("JAVA_RECORD"), "Debe soportar Java Record Engine");
        assertTrue(engines.contains("KEYVALUE"), "Debe soportar KeyValue Engine");
        assertTrue(engines.contains("TIMESERIES"), "Debe soportar TimeSeries Engine");
        assertTrue(engines.contains("GEOSPATIAL"), "Debe soportar Geospatial Engine");
        assertTrue(engines.contains("COLUMNAR"), "Debe soportar Columnar Engine");
    }

    @Test
    @DisplayName("Debe paginar registros correctamente en Document Engine bucket facturas")
    public void testPaginatedRecords() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        List<EngineRecord> page0 = catalog.getPaginatedRecords("example_factura_db", "DOCUMENT", "facturas", 0, 5);
        assertEquals(5, page0.size(), "La página 0 con tamaño 5 debe tener 5 elementos");
        assertEquals("FAC-2026-00001", page0.get(0).getId());

        List<EngineRecord> page1 = catalog.getPaginatedRecords("example_factura_db", "DOCUMENT", "facturas", 1, 5);
        assertEquals(5, page1.size());
        assertEquals("FAC-2026-00006", page1.get(0).getId());
    }

    @Test
    @DisplayName("Debe contener registros de embeddings y grafos multimodelo")
    public void testMultimodelRecords() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        List<EngineRecord> graphRecs = catalog.getPaginatedRecords("example_factura_db", "GRAPH", "red_comercial", 0, 10);
        assertFalse(graphRecs.isEmpty());
        assertTrue(graphRecs.get(0).getDetails().contains("GraphEdge"));

        List<EngineRecord> vecRecs = catalog.getPaginatedRecords("example_factura_db", "VECTOR", "factura_embeddings", 0, 10);
        assertFalse(vecRecs.isEmpty());
        assertTrue(vecRecs.get(0).getDetails().contains("VectorEmbedding"));
    }

    @Test
    @DisplayName("Debe permitir Agregar, Editar, Eliminar y Restaurar Versiones de registros")
    public void testRecordCrudAndVersioning() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        String db = "example_factura_db";
        String engine = "DOCUMENT";
        String bucket = "facturas";

        // 1. Agregar nuevo registro
        String testId = "TEST-FAC-99999";
        EngineRecord newRec = new EngineRecord(testId, engine, bucket, "Factura Test v1", "{\"total\": 999.0}", "2026-10-01 20:00:00");
        boolean added = catalog.addRecord(db, engine, bucket, newRec);
        assertTrue(added, "El registro debe agregarse exitosamente");
        assertEquals(1, newRec.getCurrentVersion(), "La versión inicial debe ser 1");

        // 2. Editar registro (Crea versión 2)
        boolean updated = catalog.updateRecord(db, engine, bucket, testId, "Factura Test v2 Editada", "{\"total\": 1500.0}", "Ajuste de precio");
        assertTrue(updated, "El registro debe actualizarse");
        assertEquals(2, newRec.getCurrentVersion(), "La versión actual debe avanzar a 2");
        assertEquals(2, newRec.getVersionHistory().size(), "El historial debe tener 2 versiones");
        assertTrue(newRec.getSummary().contains("v2"));

        // 3. Restaurar versión 1 previa
        boolean restored = catalog.restoreRecordVersion(db, engine, bucket, testId, 1);
        assertTrue(restored, "La restauración de la versión 1 debe ser exitosa");
        assertEquals(3, newRec.getCurrentVersion(), "La versión tras restaurar debe ser 3");
        assertEquals("Factura Test v1", newRec.getSummary(), "El contenido restaurado debe coincidir con la versión 1");
        assertTrue(newRec.getDetails().contains("999.0"));

        // 4. Eliminar registro
        boolean deleted = catalog.deleteRecord(db, engine, bucket, testId);
        assertTrue(deleted, "El registro debe ser eliminado");
        assertNull(catalog.getBucket(db, engine, bucket).findRecordById(testId));
    }

    @Test
    @DisplayName("Debe administrar índices: creación, edición y eliminación")
    public void testIndexAdministration() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        String db = "example_factura_db";
        String engine = "DOCUMENT";
        String bucket = "facturas";

        List<EngineIndexInfo> initialIndexes = catalog.getIndexes(db, engine, bucket);
        assertFalse(initialIndexes.isEmpty(), "Deben existir índices preconfigurados");

        // Crear nuevo índice
        String newIdxName = "idx_facturas_rfc_cliente";
        EngineIndexInfo customIdx = new EngineIndexInfo(newIdxName, engine, bucket, "receptor", "BTREE", false, 500_000L);
        boolean added = catalog.addIndex(db, engine, bucket, customIdx);
        assertTrue(added);
        assertNotNull(catalog.getBucket(db, engine, bucket).findExistingIndex(newIdxName));

        // Editar índice
        boolean updated = catalog.updateIndex(db, engine, bucket, newIdxName, "receptor", "FULLTEXT", true);
        assertTrue(updated);
        EngineIndexInfo modified = catalog.getBucket(db, engine, bucket).findExistingIndex(newIdxName);
        assertEquals("FULLTEXT", modified.getType());
        assertTrue(modified.isUnique());

        // Eliminar índice
        boolean deleted = catalog.deleteIndex(db, engine, bucket, newIdxName);
        assertTrue(deleted);
        assertNull(catalog.getBucket(db, engine, bucket).findExistingIndex(newIdxName));
    }

    @Test
    @DisplayName("Debe ejecutar búsquedas mediante JettraSQL y JettraQL con condiciones")
    public void testJettraSqlAndJettraQlQueries() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        String db = "example_factura_db";
        String engine = "DOCUMENT";
        String bucket = "facturas";

        // Consulta JettraSQL con condición mayor que
        EngineDataCatalog.QueryResult sqlRes = catalog.executeQuery(db, engine, bucket, "SELECT * FROM facturas WHERE total > 1000", true);
        assertTrue(sqlRes.success());
        assertFalse(sqlRes.records().isEmpty(), "Debe encontrar registros con total > 1000");
        assertTrue(sqlRes.summaryMessage().contains("JettraSQL ejecutado"));

        // Consulta JettraQL con condición LIKE
        EngineDataCatalog.QueryResult jqlRes = catalog.executeQuery(db, engine, bucket, "FROM facturas WHERE emisor LIKE Corp", false);
        assertTrue(jqlRes.success());
        assertFalse(jqlRes.records().isEmpty());
        assertTrue(jqlRes.summaryMessage().contains("JettraQL procesado"));

        // Consulta vacía debe retornar todos los registros
        EngineDataCatalog.QueryResult allRes = catalog.executeQuery(db, engine, bucket, "", false);
        assertEquals(25, allRes.records().size());
    }

    @Test
    @DisplayName("Debe paginar millones de registros (1,000,025) de forma lazy sin saturar memoria")
    public void testMillionRecordsPagination() {
        EngineDataCatalog catalog = new EngineDataCatalog();
        String db = "example_factura_db";
        String engine = "DOCUMENT";
        String bucket = "facturas";

        EngineBucket b = catalog.getBucket(db, engine, bucket);
        assertNotNull(b);
        assertEquals(1_000_025L, b.getTotalObjects(), "El total de registros debe ser 1,000,025");

        // Página 0 (Primeros 5 registros)
        List<EngineRecord> page0 = catalog.getRecordsForPage(db, engine, bucket, 0, 5);
        assertEquals(5, page0.size());
        assertEquals("FAC-2026-00001", page0.get(0).getId());

        // Página 1000 (Registros del medio)
        List<EngineRecord> page1000 = catalog.getRecordsForPage(db, engine, bucket, 1000, 5);
        assertEquals(5, page1000.size());
        assertTrue(page1000.get(0).getId().contains("FAC-2026-"));

        // Última página (200,004 -> registros finales hasta 1,000,025)
        int lastPageIndex = (int)(b.getTotalObjects() / 5) - 1; // 200005 páginas -> índice 200004
        List<EngineRecord> lastPage = catalog.getRecordsForPage(db, engine, bucket, lastPageIndex, 5);
        assertEquals(5, lastPage.size());
        assertEquals("FAC-2026-1000025", lastPage.get(4).getId());
    }
}

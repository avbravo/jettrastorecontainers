package io.jettra.core.three.d.explorer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catálogo multimodelo que provee acceso jerárquico:
 * <Base-Datos><Engine><Bucket-Contenedor><Registro>
 * con soporte para operaciones CRUD adaptadas a cada engine, versionado,
 * administración de índices y motor de búsqueda para JettraQL y JettraSQL.
 */
public class EngineDataCatalog {
    private static EngineDataCatalog instance;
    public static synchronized EngineDataCatalog getInstance() {
        if (instance == null) {
            instance = new EngineDataCatalog();
        }
        return instance;
    }

    public record QueryResult(
        List<EngineRecord> records,
        String summaryMessage,
        long elapsedMicros,
        boolean success
    ) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Map<String, List<EngineBucket>> databaseBuckets = new HashMap<>();

    public EngineDataCatalog() {
        initFacturaDb();
        initHospitalDb();
        initAmbientalDb();
        initMetadataDb();
    }

    private void initFacturaDb() {
        List<EngineBucket> list = new ArrayList<>();
        String db = "example_factura_db";
        String now = LocalDateTime.now().format(FMT);

        // 1. DOCUMENT ENGINE
        EngineBucket bDocFacturas = new EngineBucket("DOCUMENT", "Document Engine (JSON Off-Heap)", "facturas", "Facturas comerciales electrónicas timbradas", 1_000_000L);
        bDocFacturas.addIndex(new EngineIndexInfo("idx_facturas_id", "DOCUMENT", "facturas", "id", "PRIMARY_HASH", true, 1_000_000L));
        bDocFacturas.addIndex(new EngineIndexInfo("idx_facturas_total_btree", "DOCUMENT", "facturas", "total", "BTREE", false, 1_000_000L));
        bDocFacturas.addIndex(new EngineIndexInfo("idx_facturas_emisor", "DOCUMENT", "facturas", "emisor", "BTREE", false, 1_000_000L));

        for (int i = 1; i <= 25; i++) {
            String id = String.format("FAC-2026-%05d", i);
            float tot = (i * 124.50f * 1.16f);
            String sum = String.format("Factura Fiscal #%d | Total: $%,.2f | Items: %d", i, tot, (i % 8 + 1));
            String det = String.format("{\n  \"id\": \"%s\",\n  \"emisor\": \"Corp Global SA\",\n  \"receptor\": \"Cliente_%d\",\n  \"subtotal\": %.2f,\n  \"iva\": %.2f,\n  \"total\": %.2f,\n  \"metodoPago\": \"TRANSFERENCIA_SPEI\",\n  \"estado\": \"TIMBRADO_VALIDADO\"\n}",
                id, i, (i * 124.50f), (i * 124.50f * 0.16f), tot);
            bDocFacturas.addRecord(new EngineRecord(id, "DOCUMENT", "facturas", sum, det, now));
        }
        list.add(bDocFacturas);

        EngineBucket bDocClientes = new EngineBucket("DOCUMENT", "Document Engine (JSON Off-Heap)", "clientes", "Registro fiscal y comercial de clientes", 200_000L);
        bDocClientes.addIndex(new EngineIndexInfo("idx_clientes_id", "DOCUMENT", "clientes", "idCliente", "PRIMARY_HASH", true, 200_000L));
        bDocClientes.addIndex(new EngineIndexInfo("idx_clientes_razon", "DOCUMENT", "clientes", "razonSocial", "FULLTEXT", false, 200_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("CLI-%04d", i);
            String sum = String.format("Cliente: Empresa %c%d | RFC/RUC: CL%d9921", (char)('A' + (i % 26)), i, 1000 + i);
            String det = String.format("{\n  \"idCliente\": \"%s\",\n  \"razonSocial\": \"Comercializadora Alfa %d\",\n  \"limiteCredito\": $%,.2f,\n  \"zonaFiscal\": \"Norte-01\",\n  \"activo\": true\n}", id, i, i * 50000.0f);
            bDocClientes.addRecord(new EngineRecord(id, "DOCUMENT", "clientes", sum, det, now));
        }
        list.add(bDocClientes);

        // 2. GRAPH ENGINE
        EngineBucket bGraph = new EngineBucket("GRAPH", "Graph Engine (Redes de Nodos y Aristas)", "red_comercial", "Topología de relaciones Clientes -> Facturas -> Proveedores", 200_000L);
        bGraph.addIndex(new EngineIndexInfo("idx_graph_source", "GRAPH", "red_comercial", "sourceVertex", "PRIMARY_HASH", false, 200_000L));
        bGraph.addIndex(new EngineIndexInfo("idx_graph_rel", "GRAPH", "red_comercial", "relationship", "BTREE", false, 200_000L));
        for (int i = 1; i <= 20; i++) {
            String id = String.format("EDGE-PAGO-%04d", i);
            String sum = String.format("(Cliente_%d) ──[EMITE_PAGO $%,.2f]──> (Factura_%d)", i, i * 450.0f, i);
            String det = String.format("GraphEdge: {\n  \"sourceVertex\": \"VERTEX_CLI_%d\",\n  \"targetVertex\": \"VERTEX_FAC_%d\",\n  \"relationship\": \"EMITE_PAGO\",\n  \"weight\": %.2f,\n  \"directSettlement\": true\n}", i, i, i * 450.0f);
            bGraph.addRecord(new EngineRecord(id, "GRAPH", "red_comercial", sum, det, now));
        }
        list.add(bGraph);

        // 3. VECTOR ENGINE
        EngineBucket bVector = new EngineBucket("VECTOR", "Vector Engine (Indexación Cosine 3D/HD)", "factura_embeddings", "Embeddings multidimensionales para detección de anomalías con IA", 200_000L);
        bVector.addIndex(new EngineIndexInfo("idx_vector_hnsw", "VECTOR", "factura_embeddings", "coordinates", "HNSW_VECTOR", false, 200_000L));
        bVector.addIndex(new EngineIndexInfo("idx_vector_cluster", "VECTOR", "factura_embeddings", "cluster", "BITMAP", false, 200_000L));
        for (int i = 1; i <= 18; i++) {
            String id = String.format("VEC-EMBED-%04d", i);
            float vx = (float)Math.sin(i * 0.45);
            float vy = (float)Math.cos(i * 0.45);
            float vz = (float)(Math.sin(i * 0.8) * 0.5);
            String sum = String.format("Vector 3D [%.3f, %.3f, %.3f] | Cosine: %.4f | Cluster: Grupo_%d", vx, vy, vz, 0.985f - (i * 0.005f), (i % 4));
            String det = String.format("VectorEmbedding {\n  \"id\": \"%s\",\n  \"dimensions\": 3,\n  \"coordinates\": [%.4f, %.4f, %.4f],\n  \"metric\": \"COSINE\",\n  \"confidenceScore\": %.3f,\n  \"anomalyDetected\": %b\n}",
                id, vx, vy, vz, 0.985f - (i * 0.005f), (i == 7));
            bVector.addRecord(new EngineRecord(id, "VECTOR", "factura_embeddings", sum, det, now));
        }
        list.add(bVector);

        // 4. JAVA RECORD ENGINE
        EngineBucket bRecord = new EngineBucket("JAVA_RECORD", "Java Record Engine (In-Memory Panama Structs)", "factura_records", "Registros inmutables en memoria nativa sin GC", 500_000L);
        bRecord.addIndex(new EngineIndexInfo("idx_record_folio", "JAVA_RECORD", "factura_records", "folio", "PRIMARY_HASH", true, 500_000L));
        bRecord.addIndex(new EngineIndexInfo("idx_record_sku", "JAVA_RECORD", "factura_records", "itemSku", "BTREE", false, 500_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("REC-STRUCT-%04d", i);
            List<RecordFieldInfo> fields = RecordFieldInfo.createDefaultFacturaFields(i);
            String sum = String.format("record FacturaItemRecord(folio=%d, itemSku='SKU-%d', precio=%.2f)", 1000 + i, i * 3, (i * 15.75f) % 500 + 10.0);
            String det = RecordFieldInfo.buildRecordDetails("FacturaItemRecord", fields);
            bRecord.addRecord(new EngineRecord(id, "JAVA_RECORD", "factura_records", sum, det, now, fields));
        }
        list.add(bRecord);

        // 5. KEYVALUE ENGINE
        EngineBucket bKv = new EngineBucket("KEYVALUE", "KeyValue Engine (Caché Ultrarrápida Off-Heap)", "cache_folios", "Pares Clave-Valor de folios fiscales y tokens en nanosegundos", 300_000L);
        bKv.addIndex(new EngineIndexInfo("idx_kv_key", "KEYVALUE", "cache_folios", "key", "PRIMARY_HASH", true, 300_000L));
        for (int i = 1; i <= 15; i++) {
            String key = String.format("folio_cache:2026:FAC_%05d", i);
            String sum = String.format("Key: '%s' -> Value: 'UUID_TOKEN_%04X' [TTL: 3600s]", key, i * 7919);
            String det = String.format("KeyValueEntry {\n  \"key\": \"%s\",\n  \"value\": \"UUID_HASH_%08X\",\n  \"timeToLiveSeconds\": 3600,\n  \"accessLatencyNs\": 140,\n  \"storageType\": \"DIRECT_PANAMA_MEMORY\"\n}", key, i * 1234567);
            bKv.addRecord(new EngineRecord(key, "KEYVALUE", "cache_folios", sum, det, now));
        }
        list.add(bKv);

        // 6. TIMESERIES ENGINE
        EngineBucket bTs = new EngineBucket("TIMESERIES", "TimeSeries Engine (Series Temporales Nanosegundos)", "factura_metricas", "Ingesta métrica por segundo de volumen facturado y latencias", 1_000_000L);
        bTs.addIndex(new EngineIndexInfo("idx_ts_time", "TIMESERIES", "factura_metricas", "timestamp", "BTREE", false, 1_000_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("TS-METRIC-%04d", i);
            String sum = String.format("TimeStamp: %s.%03d | Ingesta IOPS: %,d ops/s | Latencia: %.2f ms", now, i * 45, 24000 + i * 350, 0.42f + (i * 0.05f));
            String det = String.format("TimeSeriesDataPoint {\n  \"seriesId\": \"%s\",\n  \"timestamp\": \"%s.%03d\",\n  \"metric\": \"THROUGHPUT_IOPS\",\n  \"value\": %d,\n  \"latencyMs\": %.2f,\n  \"resolution\": \"NANOSECOND\"\n}", id, now, i * 45, 24000 + i * 350, 0.42f + (i * 0.05f));
            bTs.addRecord(new EngineRecord(id, "TIMESERIES", "factura_metricas", sum, det, now));
        }
        list.add(bTs);

        // 7. GEOSPATIAL ENGINE
        EngineBucket bGeo = new EngineBucket("GEOSPATIAL", "Geospatial Engine (Índices R-Tree 2D/3D)", "tiendas_coordenadas", "Coordenadas geográficas y radios de cobertura de sucursales", 50_000L);
        bGeo.addIndex(new EngineIndexInfo("idx_geo_rtree", "GEOSPATIAL", "tiendas_coordenadas", "coordinates", "RTREE_SPATIAL", false, 50_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("GEO-SUC-%04d", i);
            double lat = 19.4326 + (i * 0.012);
            double lon = -99.1332 - (i * 0.015);
            String sum = String.format("Sucursal #%d | Point(Lat: %.4f, Lon: %.4f) | Radio: 5.0 km", i, lat, lon);
            String det = String.format("GeoPoint {\n  \"id\": \"%s\",\n  \"sucursal\": \"Sede Comercial %d\",\n  \"latitude\": %.6f,\n  \"longitude\": %.6f,\n  \"altitudeMeters\": 2240,\n  \"coverageRadiusKm\": 5.0\n}", id, i, lat, lon);
            bGeo.addRecord(new EngineRecord(id, "GEOSPATIAL", "tiendas_coordenadas", sum, det, now));
        }
        list.add(bGeo);

        // 8. COLUMNAR ENGINE
        EngineBucket bCol = new EngineBucket("COLUMNAR", "Columnar Engine (Compresión Vectorial Parquet)", "metricas_analiticas", "Columnas numéricas comprimidas para agregaciones SUM, AVG y GROUP BY", 100_000L);
        bCol.addIndex(new EngineIndexInfo("idx_col_chunk", "COLUMNAR", "metricas_analiticas", "chunkId", "PRIMARY_HASH", true, 100_000L));
        for (int i = 1; i <= 12; i++) {
            String id = String.format("COL-CHUNK-%04d", i);
            String sum = String.format("Chunk Columnar #%d (10,000 filas) | SUM(total): $%,.2f | AVG: $%.2f", i, i * 450000.0f, 450.0f);
            String det = String.format("ColumnChunk {\n  \"chunkId\": %d,\n  \"rowCount\": 10000,\n  \"columns\": [\"total\", \"iva\", \"descuento\"],\n  \"compression\": \"ZSTD_SIMD\",\n  \"sumTotal\": %.2f,\n  \"avgTotal\": 450.00\n}", i, i * 450000.0f);
            bCol.addRecord(new EngineRecord(id, "COLUMNAR", "metricas_analiticas", sum, det, now));
        }
        list.add(bCol);

        databaseBuckets.put(db, list);
    }

    private void initHospitalDb() {
        List<EngineBucket> list = new ArrayList<>();
        String db = "samples_hostipal_db";
        String now = LocalDateTime.now().format(FMT);

        EngineBucket bPacientes = new EngineBucket("DOCUMENT", "Document Engine (JSON Off-Heap)", "pacientes", "Historias clínicas de pacientes y diagnósticos", 400_000L);
        bPacientes.addIndex(new EngineIndexInfo("idx_pacientes_id", "DOCUMENT", "pacientes", "idPaciente", "PRIMARY_HASH", true, 400_000L));
        bPacientes.addIndex(new EngineIndexInfo("idx_pacientes_diagnostico", "DOCUMENT", "pacientes", "diagnostico", "FULLTEXT", false, 400_000L));
        for (int i = 1; i <= 20; i++) {
            String id = String.format("PAC-%05d", i);
            String sum = String.format("Paciente: %s | Edad: %d | Sala: %s | Prioridad: %s",
                "Paciente_" + (char)('A' + (i % 26)) + i, 20 + (i * 3 % 60), "Piso " + (i % 5 + 1), (i % 3 == 0 ? "ALTA" : "NORMAL"));
            String det = String.format("{\n  \"idPaciente\": \"%s\",\n  \"diagnostico\": \"Afección Cardiovascular Nivel %d\",\n  \"camaAsignada\": \"UCI-%03d\",\n  \"estadoClinico\": \"ESTABLE\",\n  \"medicoTratante\": \"Dr. Gonzalez\"\n}", id, (i % 4 + 1), i);
            bPacientes.addRecord(new EngineRecord(id, "DOCUMENT", "pacientes", sum, det, now));
        }
        list.add(bPacientes);

        EngineBucket bUci = new EngineBucket("KEYVALUE", "KeyValue Engine", "camas_uci", "Caché de ocupación de camas UCI en tiempo real", 200_000L);
        bUci.addIndex(new EngineIndexInfo("idx_uci_cama", "KEYVALUE", "camas_uci", "camaId", "PRIMARY_HASH", true, 200_000L));
        for (int i = 1; i <= 15; i++) {
            String key = String.format("cama:uci:%03d", i);
            String sum = String.format("Cama UCI-%03d -> Ocupada: %b | Paciente: PAC-%05d", i, (i % 2 == 0), i);
            String det = String.format("KeyValueEntry {\n  \"camaId\": \"UCI-%03d\",\n  \"ocupada\": %b,\n  \"oxigenoPct\": 98.5,\n  \"monitoreoCardiaco\": \"ACTIVO\"\n}", i, (i % 2 == 0));
            bUci.addRecord(new EngineRecord(key, "KEYVALUE", "camas_uci", sum, det, now));
        }
        list.add(bUci);

        EngineBucket bVector = new EngineBucket("VECTOR", "Vector Engine", "sintomas_embeddings", "Vectores de similitud de sintomatología clínica", 200_000L);
        bVector.addIndex(new EngineIndexInfo("idx_sintomas_hnsw", "VECTOR", "sintomas_embeddings", "coordinates", "HNSW_VECTOR", false, 200_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("VEC-SINT-%04d", i);
            String sum = String.format("Vector Síntoma [%.3f, %.3f, %.3f] -> Correlación Diagnóstica: 97.4%%", (float)Math.sin(i), (float)Math.cos(i), 0.15f * i);
            String det = String.format("SymptomEmbedding {\n  \"id\": \"%s\",\n  \"clusterPatologia\": \"Respiratorio_Tipo_%d\",\n  \"cosineSimilarity\": 0.974\n}", id, (i % 3 + 1));
            bVector.addRecord(new EngineRecord(id, "VECTOR", "sintomas_embeddings", sum, det, now));
        }
        list.add(bVector);

        databaseBuckets.put(db, list);
    }

    private void initAmbientalDb() {
        List<EngineBucket> list = new ArrayList<>();
        String db = "samples_ambiental_db";
        String now = LocalDateTime.now().format(FMT);

        EngineBucket bCo2 = new EngineBucket("TIMESERIES", "TimeSeries Engine", "mediciones_co2", "Mediciones continuas de CO2 en ppm por estación", 800_000L);
        bCo2.addIndex(new EngineIndexInfo("idx_co2_time", "TIMESERIES", "mediciones_co2", "timestamp", "BTREE", false, 800_000L));
        for (int i = 1; i <= 20; i++) {
            String id = String.format("CO2-REC-%05d", i);
            float ppm = 412.5f + (float)(Math.sin(i * 0.5) * 15.0);
            String sum = String.format("Estación Est-%d: %.2f ppm CO2 | Estado: %s", (i % 8 + 1), ppm, (ppm > 425 ? "ALERTA" : "NORMAL"));
            String det = String.format("TimeSeriesData {\n  \"estacionId\": \"EST_%03d\",\n  \"parametro\": \"CO2_PPM\",\n  \"valor\": %.2f,\n  \"unidad\": \"ppm\",\n  \"calibracion\": \"OPTICA_NDIR\"\n}", (i % 8 + 1), ppm);
            bCo2.addRecord(new EngineRecord(id, "TIMESERIES", "mediciones_co2", sum, det, now));
        }
        list.add(bCo2);

        EngineBucket bGeo = new EngineBucket("GEOSPATIAL", "Geospatial Engine", "sensores_gps", "Geolocalización de boyas y estaciones ambientales", 300_000L);
        bGeo.addIndex(new EngineIndexInfo("idx_sensores_rtree", "GEOSPATIAL", "sensores_gps", "coordinates", "RTREE_SPATIAL", false, 300_000L));
        for (int i = 1; i <= 15; i++) {
            String id = String.format("BOYA-GPS-%04d", i);
            String sum = String.format("Boya Marina #%d | Coord: (%.4f, %.4f) | Océano Pacífico", i, 12.5 + (i * 0.4), -85.2 - (i * 0.5));
            String det = String.format("GeoSensor {\n  \"boyaId\": \"BOYA_%03d\",\n  \"lat\": %.4f,\n  \"lon\": %.4f,\n  \"temperaturaAgua\": 24.5\n}", i, 12.5 + (i * 0.4), -85.2 - (i * 0.5));
            bGeo.addRecord(new EngineRecord(id, "GEOSPATIAL", "sensores_gps", sum, det, now));
        }
        list.add(bGeo);

        databaseBuckets.put(db, list);
    }

    private void initMetadataDb() {
        List<EngineBucket> list = new ArrayList<>();
        String db = "system_metadata_db";
        String now = LocalDateTime.now().format(FMT);

        EngineBucket bSchemas = new EngineBucket("JAVA_RECORD", "Java Record Engine", "schemas", "Esquemas y catálogos de metadatos del clúster", 5_000L);
        bSchemas.addIndex(new EngineIndexInfo("idx_schema_name", "JAVA_RECORD", "schemas", "schemaName", "PRIMARY_HASH", true, 5_000L));
        String[] schNames = {"FacturaSchemaV2", "PacienteClinicalSchema", "SensorAmbientalSchema", "RaftLogEntryRecord", "PoliceAuditRecord"};
        for (int i = 0; i < schNames.length; i++) {
            String sum = String.format("Esquema Registrado: '%s' | Versión 2.5 | Estado: ACTIVO", schNames[i]);
            List<RecordFieldInfo> sFields = RecordFieldInfo.createDefaultSchemaFields(schNames[i]);
            String det = RecordFieldInfo.buildRecordDetails(schNames[i] + "Record", sFields);
            bSchemas.addRecord(new EngineRecord("SCH-" + (i + 1), "JAVA_RECORD", "schemas", sum, det, now, sFields));
        }
        list.add(bSchemas);

        EngineBucket bPolice = new EngineBucket("DOCUMENT", "Document Engine", "police_audits", "Registros de intervenciones y auditorías de JettraPolice", 20_000L);
        bPolice.addIndex(new EngineIndexInfo("idx_police_audit_id", "DOCUMENT", "police_audits", "auditId", "PRIMARY_HASH", true, 20_000L));
        String[] actions = {"PREVENCIÓN OOM HEAP", "AISLAMIENTO NODO OFFLINE", "FLUSH MEMTABLE FORZADO", "INSPECCIÓN PERMISOS USUARIO"};
        for (int i = 0; i < 15; i++) {
            String sum = String.format("Auditoría #%d: %s | Veredicto: SALUDABLE", i + 1, actions[i % actions.length]);
            String det = String.format("{\n  \"auditId\": \"AUD_%04d\",\n  \"agenteK9\": \"k9_alpha\",\n  \"accion\": \"%s\",\n  \"resultado\": \"INTERVENCIÓN EXITOSA\",\n  \"heapLiberadoMb\": %d\n}", i + 1, actions[i % actions.length], (i + 1) * 32);
            bPolice.addRecord(new EngineRecord("AUD-" + (i + 1), "DOCUMENT", "police_audits", sum, det, now));
        }
        list.add(bPolice);

        databaseBuckets.put(db, list);
    }

    public List<EngineBucket> getBucketsForDatabase(String dbId) {
        return databaseBuckets.getOrDefault(dbId, databaseBuckets.get("example_factura_db"));
    }

    public List<String> getSupportedEngines(String dbId) {
        List<EngineBucket> buckets = getBucketsForDatabase(dbId);
        Set<String> engines = new LinkedHashSet<>();
        for (EngineBucket b : buckets) {
            engines.add(b.getEngineType());
        }
        return new ArrayList<>(engines);
    }

    public List<EngineBucket> getBucketsByEngine(String dbId, String engineType) {
        List<EngineBucket> all = getBucketsForDatabase(dbId);
        List<EngineBucket> res = new ArrayList<>();
        for (EngineBucket b : all) {
            if (b.getEngineType().equalsIgnoreCase(engineType)) {
                res.add(b);
            }
        }
        return res;
    }

    public EngineBucket getBucket(String dbId, String engineType, String bucketName) {
        List<EngineBucket> buckets = getBucketsForDatabase(dbId);
        for (EngineBucket b : buckets) {
            if (b.getEngineType().equalsIgnoreCase(engineType) && b.getBucketName().equalsIgnoreCase(bucketName)) {
                return b;
            }
        }
        return null;
    }

    public List<EngineRecord> getPaginatedRecords(String dbId, String engineType, String bucketName, int pageIndex, int pageSize) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            List<EngineRecord> list = b.getSampleRecords();
            int start = Math.max(0, pageIndex * pageSize);
            int end = Math.min(list.size(), start + pageSize);
            if (start >= list.size()) {
                return Collections.emptyList();
            }
            return list.subList(start, end);
        }
        return Collections.emptyList();
    }

    public int getTotalRecordCount(String dbId, String engineType, String bucketName) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        return (b != null) ? b.getSampleRecords().size() : 0;
    }

    // --- OPERACIONES CRUD POR REGISTRO ---
    public synchronized boolean addRecord(String dbId, String engineType, String bucketName, EngineRecord record) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            b.addRecord(record);
            return true;
        }
        return false;
    }

    public synchronized boolean updateRecord(String dbId, String engineType, String bucketName, String id, String summary, String details, String note) {
        return updateRecord(dbId, engineType, bucketName, id, summary, details, note, null);
    }

    public synchronized boolean updateRecord(String dbId, String engineType, String bucketName, String id, String summary, String details, String note, List<RecordFieldInfo> fields) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            return b.updateRecord(id, summary, details, note, fields);
        }
        return false;
    }

    public synchronized boolean deleteRecord(String dbId, String engineType, String bucketName, String id) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            return b.deleteRecord(id);
        }
        return false;
    }

    public synchronized boolean restoreRecordVersion(String dbId, String engineType, String bucketName, String id, int versionNumber) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            return b.restoreRecordVersion(id, versionNumber);
        }
        return false;
    }

    // --- OPERACIONES DE ÍNDICES ---
    public synchronized List<EngineIndexInfo> getIndexes(String dbId, String engineType, String bucketName) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        return (b != null) ? b.getIndexes() : Collections.emptyList();
    }

    public synchronized boolean addIndex(String dbId, String engineType, String bucketName, EngineIndexInfo index) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            b.addIndex(index);
            return true;
        }
        return false;
    }

    public synchronized boolean updateIndex(String dbId, String engineType, String bucketName, String indexName, String field, String type, boolean unique) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            return b.updateIndex(indexName, field, type, unique);
        }
        return false;
    }

    public synchronized boolean deleteIndex(String dbId, String engineType, String bucketName, String indexName) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b != null) {
            return b.deleteIndex(indexName);
        }
        return false;
    }

    // --- BUSCADOR JQL / SQL Y FILTRADO AVANZADO ---
    // --- PAGINACIÓN INTELIGENTE PARA MILLONES DE REGISTROS (JETTRASTORE STREAMING / CACHE) ---
    public synchronized List<EngineRecord> getRecordsForPage(String dbId, String engineType, String bucketName, int pageIndex, int pageSize) {
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b == null) return Collections.emptyList();

        long total = b.getTotalObjects();
        long offset = (long) pageIndex * pageSize;
        if (offset >= total) return Collections.emptyList();

        List<EngineRecord> samples = b.getSampleRecords();
        // Si el rango solicitado está dentro de las muestras iniciales ya cargadas en memoria
        if (offset < samples.size() && (offset + pageSize) <= samples.size()) {
            return samples.subList((int) offset, (int) Math.min((long) samples.size(), offset + pageSize));
        }

        // Recuperar dinámicamente mediante JettraStoreDriver o generar bloque seguro de stream
        List<EngineRecord> page = new ArrayList<>();
        int countToFetch = (int) Math.min((long) pageSize, total - offset);
        String now = LocalDateTime.now().format(FMT);

        for (int i = 0; i < countToFetch; i++) {
            long currentRecordNum = offset + i + 1;
            String recId = switch (engineType) {
                case "DOCUMENT" -> String.format("FAC-2026-%05d", currentRecordNum);
                case "JAVA_RECORD" -> String.format("REC-STRUCT-%07d", currentRecordNum);
                case "GRAPH" -> String.format("EDGE-PAGO-%07d", currentRecordNum);
                case "VECTOR" -> String.format("VEC-EMBED-%07d", currentRecordNum);
                default -> String.format("OBJ-%07d", currentRecordNum);
            };

            EngineRecord existing = b.findRecordById(recId);
            if (existing != null) {
                page.add(existing);
                continue;
            }

            if ("JAVA_RECORD".equals(engineType)) {
                List<RecordFieldInfo> fields = RecordFieldInfo.createDefaultFacturaFields(currentRecordNum);
                String sum = String.format("record FacturaItemRecord(folio=%d, itemSku='SKU-%d', precio=%.2f)", 1000 + currentRecordNum, currentRecordNum * 3, ((currentRecordNum * 15.75) % 500 + 10.0));
                String det = RecordFieldInfo.buildRecordDetails("FacturaItemRecord", fields);
                EngineRecord dynamicRec = new EngineRecord(recId, engineType, bucketName, sum, det, now, fields);
                page.add(dynamicRec);
            } else if ("GRAPH".equals(engineType)) {
                float w = (float)((currentRecordNum * 450.0) % 15000 + 50.0);
                String sum = String.format("(Cliente_%d) ──[EMITE_PAGO $%,.2f]──> (Factura_%d)", currentRecordNum, w, currentRecordNum);
                String det = String.format("GraphEdge: {\n  \"sourceVertex\": \"VERTEX_CLI_%d\",\n  \"targetVertex\": \"VERTEX_FAC_%d\",\n  \"relationship\": \"EMITE_PAGO\",\n  \"weight\": %.2f,\n  \"directSettlement\": true\n}", currentRecordNum, currentRecordNum, w);
                EngineRecord dynamicRec = new EngineRecord(recId, engineType, bucketName, sum, det, now);
                page.add(dynamicRec);
            } else if ("VECTOR".equals(engineType)) {
                float vx = (float)Math.sin(currentRecordNum * 0.45);
                float vy = (float)Math.cos(currentRecordNum * 0.45);
                float vz = (float)(Math.sin(currentRecordNum * 0.8) * 0.5);
                float score = (float)Math.max(0.70, 0.985f - ((currentRecordNum % 100) * 0.002f));
                String sum = String.format("Vector 3D [%.3f, %.3f, %.3f] | Cosine: %.4f | Cluster: Grupo_%d", vx, vy, vz, score, (currentRecordNum % 8));
                String det = String.format("VectorEmbedding {\n  \"id\": \"%s\",\n  \"dimensions\": 3,\n  \"coordinates\": [%.4f, %.4f, %.4f],\n  \"metric\": \"COSINE\",\n  \"confidenceScore\": %.3f,\n  \"anomalyDetected\": %b\n}",
                    recId, vx, vy, vz, score, (currentRecordNum % 50 == 0));
                EngineRecord dynamicRec = new EngineRecord(recId, engineType, bucketName, sum, det, now);
                page.add(dynamicRec);
            } else {
                float tot = (float) ((currentRecordNum * 124.50) % 50000 + 150.0);
                String sum = String.format("Factura Fiscal #%d | Total: $%,.2f | Items: %d", currentRecordNum, tot, ((int)(currentRecordNum % 8) + 1));
                String det = String.format("{\n  \"id\": \"%s\",\n  \"emisor\": \"Corp Global SA\",\n  \"receptor\": \"Cliente_%d\",\n  \"subtotal\": %.2f,\n  \"iva\": %.2f,\n  \"total\": %.2f,\n  \"metodoPago\": \"%s\",\n  \"estado\": \"TIMBRADO_VALIDADO\"\n}",
                    recId, (currentRecordNum % 1000 + 1), (tot / 1.16f), (tot - (tot / 1.16f)), tot,
                    (currentRecordNum % 2 == 0 ? "TRANSFERENCIA_SPEI" : "TARJETA_EMPRESARIAL"));
                EngineRecord dynamicRec = new EngineRecord(recId, engineType, bucketName, sum, det, now);
                page.add(dynamicRec);
            }
        }
        return page;
    }

    public QueryResult executeQuery(String dbId, String engineType, String bucketName, String query, boolean isSql) {
        long t0 = System.nanoTime();
        EngineBucket b = getBucket(dbId, engineType, bucketName);
        if (b == null) {
            return new QueryResult(Collections.emptyList(), "Bucket no encontrado", 0, false);
        }

        List<EngineRecord> all = b.getSampleRecords();
        if (query == null || query.isBlank()) {
            long el = (System.nanoTime() - t0) / 1000;
            return new QueryResult(all, "Todos los registros cargados (" + all.size() + ")", el, true);
        }

        String q = query.trim();
        List<EngineRecord> matched = new ArrayList<>();

        try {
            if (isSql) {
                // Sintaxis JettraSQL: SELECT ... FROM ... WHERE ... o filtros directos
                String upper = q.toUpperCase();
                String cond = "";
                if (upper.contains(" WHERE ")) {
                    int idx = upper.indexOf(" WHERE ");
                    cond = q.substring(idx + 7).trim();
                } else if (!upper.startsWith("SELECT ")) {
                    cond = q; // Permite ingresar directamente "total > 500" o "emisor LIKE Corp"
                }

                if (cond.isEmpty()) {
                    matched.addAll(all);
                } else {
                    for (EngineRecord r : all) {
                        if (evaluateCondition(r, cond)) {
                            matched.add(r);
                        }
                    }
                }
                long el = Math.max(1, (System.nanoTime() - t0) / 1000);
                String msg = String.format("JettraSQL ejecutado en %.2f ms | %d registros encontrados", el / 1000.0, matched.size());
                return new QueryResult(matched, msg, el, true);
            } else {
                // Sintaxis JettraQL: FROM <bucket> WHERE <cond> / FIND WHERE <cond> / MATCH ... / VECTOR ...
                String upper = q.toUpperCase();
                if (upper.startsWith("MATCH ") || upper.startsWith("GRAPH ")) {
                    // Consulta de Grafo
                    for (EngineRecord r : all) {
                        if (r.getDetails().contains("GraphEdge") || r.getDetails().contains("relationship") || r.getSummary().contains("──[")) {
                            matched.add(r);
                        }
                    }
                } else if (upper.startsWith("VECTOR ") || upper.startsWith("KNN_SEARCH ")) {
                    // Consulta Vectorial
                    for (EngineRecord r : all) {
                        if (r.getEngineType().equalsIgnoreCase("VECTOR") || r.getDetails().contains("VectorEmbedding")) {
                            matched.add(r);
                        }
                    }
                } else {
                    String cond = "";
                    if (upper.contains(" WHERE ")) {
                        cond = q.substring(upper.indexOf(" WHERE ") + 7).trim();
                    } else if (upper.startsWith("FIND ") || upper.startsWith("FETCH ")) {
                        cond = q.substring(4).trim();
                    } else {
                        cond = q;
                    }

                    if (cond.isEmpty()) {
                        matched.addAll(all);
                    } else {
                        for (EngineRecord r : all) {
                            if (evaluateCondition(r, cond)) {
                                matched.add(r);
                            }
                        }
                    }
                }
                long el = Math.max(1, (System.nanoTime() - t0) / 1000);
                String msg = String.format("JettraQL procesado en %.2f ms | %d registros encontrados", el / 1000.0, matched.size());
                return new QueryResult(matched, msg, el, true);
            }
        } catch (Exception e) {
            long el = Math.max(1, (System.nanoTime() - t0) / 1000);
            return new QueryResult(Collections.emptyList(), "Error en consulta: " + e.getMessage(), el, false);
        }
    }

    private boolean evaluateCondition(EngineRecord r, String condition) {
        String c = condition.replace(";", "").trim();
        if (c.isEmpty()) return true;

        // Soporte para operadores: >=, <=, !=, ==, =, >, <, LIKE
        Pattern opPattern = Pattern.compile("([a-zA-Z0-9_]+)\\s*(>=|<=|!=|==|=|>|<|LIKE|like)\\s*(.*)");
        Matcher m = opPattern.matcher(c);

        if (m.matches()) {
            String field = m.group(1).trim();
            String op = m.group(2).trim().toUpperCase();
            String rawVal = m.group(3).trim().replaceAll("^['\"]|['\"]$", "");

            String text = r.getId() + " " + r.getSummary() + " " + r.getDetails();

            // Buscar valor de campo en JSON o payload si existe
            String extractedVal = extractFieldFromRecord(r, field);
            if (extractedVal == null) {
                // Búsqueda aproximada en el texto general
                if (op.equals("=") || op.equals("==") || op.equals("LIKE")) {
                    return text.toLowerCase().contains(rawVal.toLowerCase());
                }
                return false;
            }

            // Comparación numérica
            try {
                double numRec = Double.parseDouble(extractedVal.replaceAll("[$,]", ""));
                double numTarget = Double.parseDouble(rawVal.replaceAll("[$,]", ""));
                return switch (op) {
                    case ">" -> numRec > numTarget;
                    case ">=" -> numRec >= numTarget;
                    case "<" -> numRec < numTarget;
                    case "<=" -> numRec <= numTarget;
                    case "=", "==" -> Math.abs(numRec - numTarget) < 0.0001;
                    case "!=" -> Math.abs(numRec - numTarget) >= 0.0001;
                    default -> false;
                };
            } catch (NumberFormatException ignored) {
                // Comparación textual
                return switch (op) {
                    case "=", "==" -> extractedVal.equalsIgnoreCase(rawVal);
                    case "!=" -> !extractedVal.equalsIgnoreCase(rawVal);
                    case "LIKE" -> extractedVal.toLowerCase().contains(rawVal.toLowerCase());
                    default -> false;
                };
            }
        }

        // Búsqueda genérica por palabra clave
        String lower = c.toLowerCase();
        return r.getId().toLowerCase().contains(lower) 
            || r.getSummary().toLowerCase().contains(lower) 
            || r.getDetails().toLowerCase().contains(lower);
    }

    private String extractFieldFromRecord(EngineRecord r, String field) {
        String det = r.getDetails();
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*([^,\n}]+)");
        Matcher m = p.matcher(det);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^[\"']|[\"']$", "");
        }
        if (field.equalsIgnoreCase("id") || field.equalsIgnoreCase("_id")) return r.getId();
        if (field.equalsIgnoreCase("timestamp")) return r.getTimestamp();
        return null;
    }
}

package io.jettra.store.sample;

import io.jettra.store.core.JettraDatabase;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Gestor Centralizado de Bases de Datos de Muestra y Ejemplos Empresariales para JettraStore.
 * Asegura que las bases de datos de muestra ('sample_*' y 'example_factura_db')
 * contengan sus colecciones y datos multimodelo listos tanto en JettraStore, JettraStoreFX y JettraStoreShell.
 */
public final class JettraStoreSamples {

    public static final String SAMPLE_ENTERPRISE = "sample_enterprise_db";
    public static final String SAMPLE_ECOMMERCE = "sample_ecommerce_db";
    public static final String SAMPLE_AI_GRAPH = "sample_ai_graph_db";
    public static final String SAMPLE_IOT = "sample_iot_telemetry_db";
    public static final String SAMPLE_FINANCIAL = "sample_financial_db";
    public static final String SAMPLE_FACTURA = "example_factura_db";
    public static final String SAMPLE_HOSPITAL = "samples_hostipal_db";
    public static final String SAMPLE_HOSPITAL_ALIAS = "samples_hospital_db";
    public static final String SAMPLE_AMBIENTAL = "samples_ambiental_db";
    public static final String SAMPLE_AMBIENTAL_ALIAS = "samples_environmental_db";

    public static final Set<String> SAMPLE_DATABASES = Set.of(
        SAMPLE_ENTERPRISE,
        SAMPLE_ECOMMERCE,
        SAMPLE_AI_GRAPH,
        SAMPLE_IOT,
        SAMPLE_FINANCIAL
    );

    public static final Set<String> ALL_SAMPLE_DATABASES = Set.of(
        SAMPLE_ENTERPRISE,
        SAMPLE_ECOMMERCE,
        SAMPLE_AI_GRAPH,
        SAMPLE_IOT,
        SAMPLE_FINANCIAL,
        SAMPLE_FACTURA,
        SAMPLE_HOSPITAL,
        SAMPLE_HOSPITAL_ALIAS,
        SAMPLE_AMBIENTAL,
        SAMPLE_AMBIENTAL_ALIAS
    );

    private JettraStoreSamples() {}

    public static boolean isSampleDatabase(String name) {
        if (name == null) return false;
        // Solo las 5 bases de muestra estándar se inicializan automáticamente al inicio.
        // Las bases masivas se inicializan bajo demanda.
        return SAMPLE_DATABASES.contains(name);
    }

    public static boolean isAnySampleDatabase(String name) {
        if (name == null) return false;
        return ALL_SAMPLE_DATABASES.contains(name);
    }

    public static int getSampleCollectionCount(String dbName) {
        if (dbName == null) return 0;
        return switch (dbName) {
            case SAMPLE_ENTERPRISE -> 4;
            case SAMPLE_ECOMMERCE -> 3;
            case SAMPLE_AI_GRAPH -> 4;
            case SAMPLE_IOT -> 4;
            case SAMPLE_FINANCIAL -> 3;
            case SAMPLE_FACTURA -> 9;
            case SAMPLE_HOSPITAL, SAMPLE_HOSPITAL_ALIAS -> 11;
            case SAMPLE_AMBIENTAL, SAMPLE_AMBIENTAL_ALIAS -> 11;
            default -> 0;
        };
    }

    public static void installSample(String dbName, JettraDatabase db) {
        installSample(dbName, db, false);
    }

    public static void installSample(String dbName, JettraDatabase db, boolean massive) {
        if (dbName == null || db == null) return;
        switch (dbName) {
            case SAMPLE_ENTERPRISE -> installEnterprise(db);
            case SAMPLE_ECOMMERCE -> installEcommerce(db);
            case SAMPLE_AI_GRAPH -> installAiGraph(db);
            case SAMPLE_IOT -> installIoT(db);
            case SAMPLE_FINANCIAL -> installFinancial(db);
            case SAMPLE_FACTURA -> installFactura(db, massive);
            case SAMPLE_HOSPITAL, SAMPLE_HOSPITAL_ALIAS -> installHospital(db, massive);
            case SAMPLE_AMBIENTAL, SAMPLE_AMBIENTAL_ALIAS -> installAmbiental(db, massive);
            default -> {
                if (dbName.startsWith("sample")) {
                    installGenericSample(dbName, db);
                }
            }
        }
    }

    public static void installEnterprise(JettraDatabase enterprise) {
        enterprise.getDocumentEngine("departments").insert("dep_rd", Map.of(
            "name", "Research & Advanced Computing", "budget", 15000000.0, "floor", 12
        ));
        enterprise.getDocumentEngine("employees").insert("emp_01", Map.of(
            "name", "Ada Lovelace", "title", "Lead Architect", "salary", 185000.0,
            "_ref_department", "document::departments#dep_rd",
            "_ref_vector", "vector::employee_biometrics#bio_01",
            "_ref_equipment", "kv::inventory_cache#laptop_mac_m3"
        ));
        enterprise.getDocumentEngine("products").insert("prod_01", Map.of(
            "name", "Quantum Neural Accelerator", "category", "Hardware", "price", 4500.0,
            "_ref_vector", "vector::product_embeddings#emb_01",
            "_ref_category", "graph::catalog_graph#cat_hardware"
        ));
        enterprise.getVectorEngine("product_embeddings", 3).index("emb_01", new float[]{0.15f, -0.42f, 0.88f});
        enterprise.getVectorEngine("employee_biometrics", 3).index("bio_01", new float[]{0.92f, 0.11f, -0.05f});
        enterprise.getGraphEngine("catalog_graph").addEdge("prod_01", "cat_hardware", "BELONGS_TO", Map.of("weight", 1.0));
        enterprise.getTimeSeriesEngine("telemetry").record(System.currentTimeMillis(), 42.5);
        enterprise.getKeyValueEngine("inventory_cache").put("laptop_mac_m3", "MacBook Pro M3 Max 64GB".getBytes(StandardCharsets.UTF_8));
        
        try {
            enterprise.getIndexManager().createIndex("employees", "idx_emp_name", "name", "BTREE", false, enterprise.getDocumentEngine("employees"));
            enterprise.getIndexManager().createIndex("products", "idx_prod_cat", "category", "HASH", false, enterprise.getDocumentEngine("products"));
        } catch (Exception ignored) {}
    }

    public static void installEcommerce(JettraDatabase ecommerce) {
        ecommerce.getDocumentEngine("customers").insert("cust_101", Map.of(
            "name", "Elena Rostova", "tier", "VIP_PLATINUM", "country", "ES", "email", "elena@quantum.io"
        ));
        ecommerce.getDocumentEngine("orders").insert("ord_9901", Map.of(
            "customer_id", "cust_101", "total", 899.50, "status", "PAID",
            "_ref_customer", "document::customers#cust_101",
            "_ref_product", "document::products#prod_01"
        ));
        ecommerce.getColumnarEngine("order_analytics").appendRow(Map.of("revenue", 899.50));
        ecommerce.getKeyValueEngine("shopping_carts").put("cart_cust_101", "item_quantum_gpu:2".getBytes(StandardCharsets.UTF_8));
        
        try {
            ecommerce.getIndexManager().createIndex("customers", "idx_cust_tier", "tier", "HASH", false, ecommerce.getDocumentEngine("customers"));
        } catch (Exception ignored) {}
    }

    public static void installAiGraph(JettraDatabase aiGraph) {
        aiGraph.getGraphEngine("knowledge_network").addEdge("DeepLearning", "TransformerModel", "FOUNDATION_OF", Map.of("depth", 4.0));
        aiGraph.getGraphEngine("knowledge_network").addEdge("TransformerModel", "AttentionMechanism", "USES", Map.of("weight", 0.95));
        aiGraph.getVectorEngine("concept_embeddings", 3).index("vec_transformer", new float[]{0.85f, 0.12f, -0.33f});
        aiGraph.getDocumentEngine("prompts_corpus").insert("prompt_01", Map.of(
            "role", "system", "text", "You are an autonomous distributed DB engine expert.",
            "_ref_concept", "graph::knowledge_network#TransformerModel",
            "_ref_embedding", "vector::concept_embeddings#vec_transformer"
        ));
    }

    public static void installIoT(JettraDatabase iot) {
        long now = System.currentTimeMillis();
        iot.getTimeSeriesEngine("sensor_temperature").record(now - 2000, 24.5);
        iot.getTimeSeriesEngine("sensor_temperature").record(now - 1000, 25.1);
        iot.getTimeSeriesEngine("sensor_temperature").record(now, 24.8);
        iot.getTimeSeriesEngine("sensor_vibration").record(now, 0.042);
        iot.getDocumentEngine("smart_devices").insert("iot_gateway_01", Map.of(
            "model", "EdgeGate-X25", "firmware", "v2.5.0-LTS", "status", "ONLINE"
        ));
        iot.getGeospatialEngine("device_locations").insertPoint("iot_gateway_01", 40.4168, -3.7038);
    }

    public static void installFinancial(JettraDatabase finance) {
        finance.getDocumentEngine("transactions").insert("tx_001", Map.of(
            "from_account", "ACC_7712", "to_account", "ACC_9941", "amount", 15000.0, "currency", "USD",
            "_ref_client", "document::customers#cust_101"
        ));
        finance.getTimeSeriesEngine("stock_feed").record(System.currentTimeMillis(), 184.50);
    }

    public static void installFactura(JettraDatabase db, boolean massive) {
        int cliTotal = massive ? 200_000 : 2_000;
        int facTotal = massive ? 1_000_000 : 5_000;
        int detTotal = massive ? 1_000_000 : 5_000;
        int folTotal = massive ? 300_000 : 2_000;
        int vecTotal = massive ? 200_000 : 1_000;
        int edgeTotal = massive ? 100_000 : 1_000;
        int tsTotal = massive ? 50_000 : 500;
        int geoTotal = massive ? 25_000 : 250;
        int colTotal = massive ? 25_000 : 250;
        int recTotal = massive ? 100_000 : 1_000;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Task 1: Clientes
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("clientes");
                int chunkSize = Math.min(cliTotal, 5_000);
                for (int base = 0; base < cliTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, cliTotal);
                    for (int i = base; i < end; i++) {
                        String id = "cli_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "razon_social", "Corporación Comercial " + i + " S.A.",
                            "rfc_tax_id", "RFC-PAN-" + (1000000 + i),
                            "ciudad", (i % 2 == 0) ? "Ciudad de Panamá" : "Colón",
                            "limite_credito", 50000.0 + (i % 1000) * 100
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 2: Facturas
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("facturas");
                int chunkSize = Math.min(facTotal, 5_000);
                for (int base = 0; base < facTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, facTotal);
                    for (int i = base; i < end; i++) {
                        String id = "fac_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "fecha", "2026-09-29",
                            "total", 250.0 + (i % 2000),
                            "estado", "TIMBRADA",
                            "_ref_cliente", "document::clientes#cli_" + (i % cliTotal),
                            "_ref_detalle", "document::detalles_factura#det_" + (i % detTotal),
                            "_ref_folio", "kv::cache_folios#fol_" + (i % folTotal),
                            "_ref_vector", "vector::factura_embeddings#emb_" + (i % vecTotal),
                            "_ref_sucursal", "geospatial::sucursales_fiscales#suc_" + (i % geoTotal)
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 3: Detalles
            executor.submit(() -> {
                var docEngine = db.getDocumentEngine("detalles_factura");
                int chunkSize = Math.min(detTotal, 5_000);
                for (int base = 0; base < detTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, detTotal);
                    for (int i = base; i < end; i++) {
                        String id = "det_" + i;
                        int qty = (i % 10) + 1;
                        double price = 50.0 + (i % 200);
                        batch.put(id, Map.of(
                            "_id", id,
                            "concepto", "Servicio Cloud / Licencia Empresarial #" + (i % 100),
                            "cantidad", qty,
                            "precio_unitario", price,
                            "subtotal", qty * price,
                            "_ref_factura", "document::facturas#fac_" + (i % facTotal)
                        ));
                    }
                    docEngine.insertBatch(batch);
                }
            });

            // Task 4: KV Cache de Folios
            executor.submit(() -> {
                var kvEngine = db.getKeyValueEngine("cache_folios");
                byte[] rawVal = "TIMBRADO_OK_CFDI_2026_SAT".getBytes(StandardCharsets.UTF_8);
                Map<String, byte[]> batch = new HashMap<>(folTotal);
                for (int i = 0; i < folTotal; i++) {
                    batch.put("fol_" + i, rawVal);
                }
                kvEngine.putBatch(batch);
            });

            // Task 5: Factura Embeddings
            executor.submit(() -> {
                var vecEngine = db.getVectorEngine("factura_embeddings", 3);
                float[] emb = new float[]{0.75f, -0.20f, 0.60f};
                Map<String, float[]> batch = new HashMap<>(vecTotal);
                for (int i = 0; i < vecTotal; i++) {
                    batch.put("emb_" + i, emb);
                }
                vecEngine.indexBatch(batch);
            });

            // Task 6: Red Comercial Graph
            executor.submit(() -> {
                var graphEngine = db.getGraphEngine("red_comercial");
                Map<String, List<io.jettra.store.engine.models.GraphEngine.Edge>> batch = new HashMap<>(edgeTotal);
                for (int i = 0; i < edgeTotal; i++) {
                    String from = "cli_" + i;
                    String to = "fac_" + i;
                    batch.put(from, List.of(new io.jettra.store.engine.models.GraphEngine.Edge(
                        to, "FACTURA_EMITIDA", Map.of("weight", 1.0)
                    )));
                }
                graphEngine.addEdgesBatch(batch);
            });

            // Task 7: TimeSeries Volumen Facturación
            executor.submit(() -> {
                var tsEngine = db.getTimeSeriesEngine("volumen_facturacion");
                long now = System.currentTimeMillis();
                Map<Long, Double> batch = new HashMap<>(tsTotal);
                for (int i = 0; i < tsTotal; i++) {
                    batch.put(now - (i * 1000L), 2500.0 + (i % 500));
                }
                tsEngine.recordBatch(batch);
            });

            // Task 8: Geospatial Sucursales
            executor.submit(() -> {
                var geoEngine = db.getGeospatialEngine("sucursales_fiscales");
                Map<String, io.jettra.store.engine.models.GeospatialEngine.GeoPoint> batch = new HashMap<>(geoTotal);
                for (int i = 0; i < geoTotal; i++) {
                    String id = "suc_" + i;
                    batch.put(id, new io.jettra.store.engine.models.GeospatialEngine.GeoPoint(
                        id, 8.9800 + (i % 100) * 0.001, -79.5200 + (i % 100) * 0.001
                    ));
                }
                geoEngine.insertBatch(batch);
            });

            // Task 9: Columnar Analítica Fiscal
            executor.submit(() -> {
                var colEngine = db.getColumnarEngine("analitica_fiscal");
                List<Double> sub = new ArrayList<>(colTotal);
                List<Double> iva = new ArrayList<>(colTotal);
                List<Double> tot = new ArrayList<>(colTotal);
                for (int i = 0; i < colTotal; i++) {
                    double s = 1000.0 + (i % 500);
                    double iv = s * 0.07;
                    sub.add(s);
                    iva.add(iv);
                    tot.add(s + iv);
                }
                colEngine.appendBatch(
                    Map.of("subtotal", sub, "iva", iva, "total", tot),
                    Map.of(),
                    colTotal
                );
            });

            // Task 10: Records Engine (Java 25 Records Tipados)
            executor.submit(() -> {
                var recEngine = db.getRecordsEngine("auditoria_records", io.jettra.store.sample.model.FacturaAuditRecord.class);
                long now = System.currentTimeMillis();
                for (int i = 0; i < recTotal; i++) {
                    String id = "audit_" + i;
                    recEngine.persist(id, new io.jettra.store.sample.model.FacturaAuditRecord(
                        id,
                        "SAT-UUID-" + (1000000 + i),
                        "RFC-PAN-1000000",
                        "RFC-PAN-" + (1000000 + (i % cliTotal)),
                        250.0 + (i % 2000),
                        "SHA256-TIMBRE-" + i,
                        now - (i * 1000L)
                    ));
                }
            });
        }

        try {
            db.flushMemTable();
        } catch (Exception ignored) {}
        System.gc();
        try {
            db.getIndexManager().createIndex("facturas", "idx_fac_cliente", "_ref_cliente", "HASH", false, db.getDocumentEngine("facturas"));
            db.getIndexManager().createIndex("clientes", "idx_cli_rfc", "rfc_tax_id", "BTREE", false, db.getDocumentEngine("clientes"));
        } catch (Exception ignored) {}
    }


    public static void installHospital(JettraDatabase db, boolean massive) {
        // Total masivo: 2,000,000 de objetos distribuidos en motores multimodelo
        int pacTotal = massive ? 500_000 : 2_000;
        int medTotal = massive ? 200_000 : 1_000;
        int enfTotal = massive ? 100_000 : 500;
        int hospTotal = massive ? 50_000 : 200;
        int afecTotal = massive ? 400_000 : 2_000;
        int kvTotal = massive ? 300_000 : 1_000;
        int vecTotal = massive ? 200_000 : 1_000;
        int graphTotal = massive ? 100_000 : 500;
        int tsTotal = massive ? 100_000 : 500;
        int geoTotal = massive ? 25_000 : 100;
        int colTotal = massive ? 25_000 : 100;

        String[] tiposSangre = {"O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-"};
        String[] estados = {"HOSPITALIZADO", "AMBULATORIO", "UCI", "RECUPERACION", "OBSERVACION"};
        String[] categoriasEnf = {"CARDIOVASCULAR", "RESPIRATORIA", "INFECCIOSA", "NEUROLOGICA", "ONCOLOGICA"};

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Task 1: Hospitales (50,000)
            executor.submit(() -> {
                var docHosp = db.getDocumentEngine("hospitales");
                int chunkSize = Math.min(hospTotal, 5_000);
                for (int base = 0; base < hospTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, hospTotal);
                    for (int i = base; i < end; i++) {
                        String id = "hosp_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre", "Hospital Metropolitano Regional #" + (i + 1),
                            "ciudad", (i % 2 == 0) ? "Ciudad de Panamá" : "David",
                            "nivel_atencion", (i % 3) + 1,
                            "camas_disponibles", 50 + (i % 200),
                            "uci_disponible", (i % 4 != 0),
                            "_ref_ubicacion", "geospatial::ubicacion_hospitales#geo_hosp_" + (i % geoTotal)
                        ));
                    }
                    docHosp.insertBatch(batch);
                }
            });

            // Task 2: Enfermedades (100,000)
            executor.submit(() -> {
                var docEnf = db.getDocumentEngine("enfermedades");
                int chunkSize = Math.min(enfTotal, 5_000);
                for (int base = 0; base < enfTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, enfTotal);
                    for (int i = base; i < end; i++) {
                        String id = "enf_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "codigo_cie10", "CIE10-J" + (1000 + (i % 9000)),
                            "nombre_patologia", "Patología Clínica Especializada " + i,
                            "categoria", categoriasEnf[i % categoriasEnf.length],
                            "gravedad", (i % 5 == 0) ? "CRITICA" : ((i % 2 == 0) ? "MODERADA" : "LEVE")
                        ));
                    }
                    docEnf.insertBatch(batch);
                }
            });

            // Task 3: Medicamentos (200,000)
            executor.submit(() -> {
                var docMed = db.getDocumentEngine("medicamentos");
                int chunkSize = Math.min(medTotal, 5_000);
                for (int base = 0; base < medTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, medTotal);
                    for (int i = base; i < end; i++) {
                        String id = "med_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre_comercial", "Fármaco JettraCare " + (i % 5000),
                            "principio_activo", "Compuesto Activo " + (i % 1000),
                            "dosis_mg", 10.0 * ((i % 10) + 1),
                            "laboratorio", "BioPharma Lab #" + (i % 50),
                            "precio", 12.5 + (i % 150)
                        ));
                    }
                    docMed.insertBatch(batch);
                }
            });

            // Task 4: Pacientes (500,000)
            executor.submit(() -> {
                var docPac = db.getDocumentEngine("pacientes");
                int chunkSize = Math.min(pacTotal, 10_000);
                for (int base = 0; base < pacTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, pacTotal);
                    for (int i = base; i < end; i++) {
                        String id = "pac_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre", "Paciente Salud #" + i,
                            "edad", 18 + (i % 75),
                            "genero", (i % 2 == 0) ? "M" : "F",
                            "tipo_sangre", tiposSangre[i % tiposSangre.length],
                            "estado", estados[i % estados.length],
                            "seguro_medico", "Seguro JettraSalud Plan " + ((i % 4) + 1),
                            "_ref_hospital", "document::hospitales#hosp_" + (i % hospTotal),
                            "_ref_enfermedad", "document::enfermedades#enf_" + (i % enfTotal),
                            "_ref_biometria", "vector::sintomas_embeddings#emb_" + (i % vecTotal)
                        ));
                    }
                    docPac.insertBatch(batch);
                }
            });

            // Task 5: Afecciones (400,000)
            executor.submit(() -> {
                var docAfec = db.getDocumentEngine("afecciones");
                int chunkSize = Math.min(afecTotal, 10_000);
                for (int base = 0; base < afecTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, afecTotal);
                    for (int i = base; i < end; i++) {
                        String id = "afec_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "descripcion_sintoma", "Cuadro sintomático observado en triaje grado " + ((i % 5) + 1),
                            "intensidad_dolor", (i % 10) + 1,
                            "fecha_registro", "2026-10-01",
                            "_ref_paciente", "document::pacientes#pac_" + (i % pacTotal),
                            "_ref_medicamento", "document::medicamentos#med_" + (i % medTotal)
                        ));
                    }
                    docAfec.insertBatch(batch);
                }
            });

            // Task 6: KeyValue Inventario de Medicamentos (300,000)
            executor.submit(() -> {
                var kvEngine = db.getKeyValueEngine("inventario_medicamentos");
                int chunkSize = Math.min(kvTotal, 20_000);
                byte[] rawVal = "STOCK_DISPONIBLE_FARMACIA_HOSPITALARIA_LOTE_2026".getBytes(StandardCharsets.UTF_8);
                for (int base = 0; base < kvTotal; base += chunkSize) {
                    Map<String, byte[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, kvTotal);
                    for (int i = base; i < end; i++) {
                        batch.put("stock_med_" + i, rawVal);
                    }
                    kvEngine.putBatch(batch);
                }
            });

            // Task 7: Vector Embeddings de Síntomas (200,000)
            executor.submit(() -> {
                var vecEngine = db.getVectorEngine("sintomas_embeddings", 3);
                int chunkSize = Math.min(vecTotal, 20_000);
                float[] baseVector = new float[]{0.68f, -0.32f, 0.74f};
                for (int base = 0; base < vecTotal; base += chunkSize) {
                    Map<String, float[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, vecTotal);
                    for (int i = base; i < end; i++) {
                        batch.put("emb_" + i, baseVector);
                    }
                    vecEngine.indexBatch(batch);
                }
            });

            // Task 8: Graph Red Hospitalaria (100,000)
            executor.submit(() -> {
                var graphEngine = db.getGraphEngine("red_hospitalaria");
                int chunkSize = Math.min(graphTotal, 10_000);
                for (int base = 0; base < graphTotal; base += chunkSize) {
                    Map<String, List<io.jettra.store.engine.models.GraphEngine.Edge>> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, graphTotal);
                    for (int i = base; i < end; i++) {
                        String from = "pac_" + (i % pacTotal);
                        String to = "hosp_" + (i % hospTotal);
                        batch.put(from, List.of(new io.jettra.store.engine.models.GraphEngine.Edge(
                            to, "INTERNADO_EN", Map.of("pabellon", (i % 12) + 1, "prioridad", "ALTA")
                        )));
                    }
                    graphEngine.addEdgesBatch(batch);
                }
            });

            // Task 9: TimeSeries Telemetría Signos Vitales (100,000)
            executor.submit(() -> {
                var tsEngine = db.getTimeSeriesEngine("telemetria_signos_vitales");
                long now = System.currentTimeMillis();
                Map<Long, Double> batch = new HashMap<>(tsTotal);
                for (int i = 0; i < tsTotal; i++) {
                    batch.put(now - (i * 1000L), 72.0 + (i % 40));
                }
                tsEngine.recordBatch(batch);
            });

            // Task 10: Geospatial Ubicación Hospitales (25,000)
            executor.submit(() -> {
                var geoEngine = db.getGeospatialEngine("ubicacion_hospitales");
                Map<String, io.jettra.store.engine.models.GeospatialEngine.GeoPoint> batch = new HashMap<>(geoTotal);
                for (int i = 0; i < geoTotal; i++) {
                    String id = "geo_hosp_" + i;
                    batch.put(id, new io.jettra.store.engine.models.GeospatialEngine.GeoPoint(
                        id, 8.9800 + (i % 100) * 0.001, -79.5200 + (i % 100) * 0.001
                    ));
                }
                geoEngine.insertBatch(batch);
            });

            // Task 11: Columnar Analítica Costos Salud (25,000)
            executor.submit(() -> {
                var colEngine = db.getColumnarEngine("analitica_costos_salud");
                List<Double> costo = new ArrayList<>(colTotal);
                List<Double> cobertura = new ArrayList<>(colTotal);
                List<Double> copago = new ArrayList<>(colTotal);
                for (int i = 0; i < colTotal; i++) {
                    double c = 800.0 + (i % 4000);
                    double cob = c * 0.85;
                    costo.add(c);
                    cobertura.add(cob);
                    copago.add(c - cob);
                }
                colEngine.appendBatch(
                    Map.of("costo_tratamiento", costo, "cobertura_seguro", cobertura, "copago_paciente", copago),
                    Map.of(),
                    colTotal
                );
            });
        }

        try {
            db.flushMemTable();
        } catch (Exception ignored) {}
        System.gc();
        try {
            db.getIndexManager().createIndex("pacientes", "idx_pac_hospital", "_ref_hospital", "HASH", false, db.getDocumentEngine("pacientes"));
            db.getIndexManager().createIndex("pacientes", "idx_pac_sangre", "tipo_sangre", "HASH", false, db.getDocumentEngine("pacientes"));
            db.getIndexManager().createIndex("enfermedades", "idx_enf_cie10", "codigo_cie10", "BTREE", false, db.getDocumentEngine("enfermedades"));
            db.getIndexManager().createIndex("medicamentos", "idx_med_principio", "principio_activo", "BTREE", false, db.getDocumentEngine("medicamentos"));
        } catch (Exception ignored) {}
    }

    public static void installAmbiental(JettraDatabase db, boolean massive) {
        // Total masivo: 3,000,000 de objetos distribuidos en motores multimodelo (Datos Ambientales Mundiales)
        int estTotal = massive ? 200_000 : 1_000;
        int medTotal = massive ? 1_000_000 : 5_000;
        int resTotal = massive ? 100_000 : 500;
        int fueTotal = massive ? 200_000 : 1_000;
        int espTotal = massive ? 100_000 : 500;
        int kvTotal = massive ? 400_000 : 2_000;
        int vecTotal = massive ? 300_000 : 1_000;
        int graphTotal = massive ? 200_000 : 1_000;
        int tsTotal = massive ? 300_000 : 1_000;
        int geoTotal = massive ? 100_000 : 500;
        int colTotal = massive ? 100_000 : 500;

        String[] paises = {"PAN", "USA", "BRA", "DEU", "JPN", "FRA", "AUS", "CAN", "NOR", "ZAF"};
        String[] continentes = {"AMERICA", "EUROPA", "ASIA", "AFRICA", "OCEANIA"};
        String[] biomas = {"SELVA_TROPICAL", "BOSQUE_TEMPLADO", "TAIGA", "SABANA", "MANGLAR", "ARRECIFE_CORAL"};
        String[] clasifAire = {"BUENA", "MODERADA", "INSALUBRE_SENSIBLES", "INSALUBRE", "MUY_INSALUBRE", "PELIGROSA"};

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Task 1: Estaciones Meteorológicas (200,000)
            executor.submit(() -> {
                var docEst = db.getDocumentEngine("estaciones_meteorologicas");
                int chunkSize = Math.min(estTotal, 10_000);
                for (int base = 0; base < estTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, estTotal);
                    for (int i = base; i < end; i++) {
                        String id = "est_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre_estacion", "Global EcoSensor Station #" + i,
                            "pais", paises[i % paises.length],
                            "continente", continentes[i % continentes.length],
                            "altitud_msnm", 10.0 + (i % 3500),
                            "tipo_sensor", (i % 3 == 0) ? "ATMOSFERICO" : ((i % 3 == 1) ? "OCEANICO" : "SATELITAL"),
                            "_ref_geo", "geospatial::coordenadas_estaciones#geo_est_" + (i % geoTotal)
                        ));
                    }
                    docEst.insertBatch(batch);
                }
            });

            // Task 2: Reservas Naturales (100,000)
            executor.submit(() -> {
                var docRes = db.getDocumentEngine("reservas_naturales");
                int chunkSize = Math.min(resTotal, 5_000);
                for (int base = 0; base < resTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, resTotal);
                    for (int i = base; i < end; i++) {
                        String id = "res_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre_reserva", "Parque Natural & Reserva Biosfera #" + i,
                            "bioma", biomas[i % biomas.length],
                            "area_km2", 150.0 + (i % 5000),
                            "estatus_proteccion", (i % 2 == 0) ? "PARQUE_NACIONAL" : "PATRIMONIO_UNESCO"
                        ));
                    }
                    docRes.insertBatch(batch);
                }
            });

            // Task 3: Fuentes de Emisión (200,000)
            executor.submit(() -> {
                var docFue = db.getDocumentEngine("fuentes_emision");
                int chunkSize = Math.min(fueTotal, 10_000);
                for (int base = 0; base < fueTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, fueTotal);
                    for (int i = base; i < end; i++) {
                        String id = "fue_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre_complejo", "Complejo Industrial & Emisor #" + i,
                            "tipo_industria", (i % 4 == 0) ? "TERMOELECTRICA" : ((i % 4 == 1) ? "PETROQUIMICA" : "METALURGICA"),
                            "capacidad_mw", 50.0 + (i % 800),
                            "huella_carbono_anual_ton", 25000.0 + (i % 100000)
                        ));
                    }
                    docFue.insertBatch(batch);
                }
            });

            // Task 4: Especies Afectadas (100,000)
            executor.submit(() -> {
                var docEsp = db.getDocumentEngine("especies_afectadas");
                int chunkSize = Math.min(espTotal, 5_000);
                for (int base = 0; base < espTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, espTotal);
                    for (int i = base; i < end; i++) {
                        String id = "esp_" + i;
                        batch.put(id, Map.of(
                            "_id", id,
                            "nombre_cientifico", "Species BioRecord " + i,
                            "reino", (i % 2 == 0) ? "FAUNA" : "FLORA",
                            "estado_conservacion", (i % 3 == 0) ? "CRITICO" : ((i % 3 == 1) ? "EN_PELIGRO" : "VULNERABLE"),
                            "_ref_reserva", "document::reservas_naturales#res_" + (i % resTotal)
                        ));
                    }
                    docEsp.insertBatch(batch);
                }
            });

            // Task 5: Mediciones Calidad del Aire (1,000,000)
            executor.submit(() -> {
                var docMed = db.getDocumentEngine("mediciones_calidad_aire");
                int chunkSize = Math.min(medTotal, 20_000);
                for (int base = 0; base < medTotal; base += chunkSize) {
                    Map<String, Map<String, Object>> batch = io.jettra.collections.map.UnifiedMap.newMap(chunkSize);
                    int end = Math.min(base + chunkSize, medTotal);
                    for (int i = base; i < end; i++) {
                        String id = "med_amb_" + i;
                        int aqi = 15 + (i % 280);
                        batch.put(id, Map.of(
                            "_id", id,
                            "aqi_indice", aqi,
                            "pm25", 5.0 + (i % 120),
                            "pm10", 10.0 + (i % 200),
                            "co2_ppm", 380.0 + (i % 120),
                            "clasificacion", clasifAire[Math.min(aqi / 50, clasifAire.length - 1)],
                            "_ref_estacion", "document::estaciones_meteorologicas#est_" + (i % estTotal),
                            "_ref_reserva", "document::reservas_naturales#res_" + (i % resTotal),
                            "_ref_fuente", "document::fuentes_emision#fue_" + (i % fueTotal)
                        ));
                    }
                    docMed.insertBatch(batch);
                }
            });

            // Task 6: KeyValue Alertas Ambientales (400,000)
            executor.submit(() -> {
                var kvEngine = db.getKeyValueEngine("cache_alertas_ambientales");
                int chunkSize = Math.min(kvTotal, 20_000);
                byte[] rawVal = "ALERTA_GLOBAL_SUPERACION_UMBRAL_CALIDAD_AIRE_AQI".getBytes(StandardCharsets.UTF_8);
                for (int base = 0; base < kvTotal; base += chunkSize) {
                    Map<String, byte[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, kvTotal);
                    for (int i = base; i < end; i++) {
                        batch.put("alerta_env_" + i, rawVal);
                    }
                    kvEngine.putBatch(batch);
                }
            });

            // Task 7: Vector Patrones Climáticos (300,000)
            executor.submit(() -> {
                var vecEngine = db.getVectorEngine("patrones_climaticos_embeddings", 3);
                int chunkSize = Math.min(vecTotal, 20_000);
                float[] baseVector = new float[]{0.85f, -0.42f, 0.58f};
                for (int base = 0; base < vecTotal; base += chunkSize) {
                    Map<String, float[]> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, vecTotal);
                    for (int i = base; i < end; i++) {
                        batch.put("vec_clim_" + i, baseVector);
                    }
                    vecEngine.indexBatch(batch);
                }
            });

            // Task 8: Graph Red Corredores Biológicos (200,000)
            executor.submit(() -> {
                var graphEngine = db.getGraphEngine("red_corredores_biologicos");
                int chunkSize = Math.min(graphTotal, 10_000);
                for (int base = 0; base < graphTotal; base += chunkSize) {
                    Map<String, List<io.jettra.store.engine.models.GraphEngine.Edge>> batch = new HashMap<>(chunkSize);
                    int end = Math.min(base + chunkSize, graphTotal);
                    for (int i = base; i < end; i++) {
                        String from = "res_" + (i % resTotal);
                        String to = "est_" + (i % estTotal);
                        batch.put(from, List.of(new io.jettra.store.engine.models.GraphEngine.Edge(
                            to, "MONITOREADO_POR", Map.of("distancia_km", (i % 60) + 2.0, "tipo_cobertura", "SATELITAL")
                        )));
                    }
                    graphEngine.addEdgesBatch(batch);
                }
            });

            // Task 9: TimeSeries Temperatura Global (300,000)
            executor.submit(() -> {
                var tsEngine = db.getTimeSeriesEngine("temperatura_global_telemetria");
                long now = System.currentTimeMillis();
                Map<Long, Double> batch = new HashMap<>(tsTotal);
                for (int i = 0; i < tsTotal; i++) {
                    batch.put(now - (i * 1000L), 14.5 + (i % 30) * 0.1);
                }
                tsEngine.recordBatch(batch);
            });

            // Task 10: Geospatial Coordenadas Estaciones (100,000)
            executor.submit(() -> {
                var geoEngine = db.getGeospatialEngine("coordenadas_estaciones");
                Map<String, io.jettra.store.engine.models.GeospatialEngine.GeoPoint> batch = new HashMap<>(geoTotal);
                for (int i = 0; i < geoTotal; i++) {
                    String id = "geo_est_" + i;
                    batch.put(id, new io.jettra.store.engine.models.GeospatialEngine.GeoPoint(
                        id, -60.0 + (i % 1200) * 0.1, -180.0 + (i % 3600) * 0.1
                    ));
                }
                geoEngine.insertBatch(batch);
            });

            // Task 11: Columnar Analítica Emisiones Anuales (100,000)
            executor.submit(() -> {
                var colEngine = db.getColumnarEngine("analitica_emisiones_anuales");
                List<Double> co2 = new ArrayList<>(colTotal);
                List<Double> creditos = new ArrayList<>(colTotal);
                List<Double> tempAnom = new ArrayList<>(colTotal);
                for (int i = 0; i < colTotal; i++) {
                    double em = 5000.0 + (i % 50000);
                    co2.add(em);
                    creditos.add(em * 0.12);
                    tempAnom.add(1.1 + (i % 15) * 0.05);
                }
                colEngine.appendBatch(
                    Map.of("emisiones_co2", co2, "creditos_carbono", creditos, "temperatura_anomalia", tempAnom),
                    Map.of(),
                    colTotal
                );
            });
        }

        try {
            db.flushMemTable();
        } catch (Exception ignored) {}
        System.gc();
        try {
            db.getIndexManager().createIndex("mediciones_calidad_aire", "idx_med_aqi", "aqi_indice", "BTREE", false, db.getDocumentEngine("mediciones_calidad_aire"));
            db.getIndexManager().createIndex("mediciones_calidad_aire", "idx_med_estacion", "_ref_estacion", "HASH", false, db.getDocumentEngine("mediciones_calidad_aire"));
            db.getIndexManager().createIndex("estaciones_meteorologicas", "idx_est_pais", "pais", "HASH", false, db.getDocumentEngine("estaciones_meteorologicas"));
            db.getIndexManager().createIndex("reservas_naturales", "idx_res_bioma", "bioma", "HASH", false, db.getDocumentEngine("reservas_naturales"));
        } catch (Exception ignored) {}
    }

    private static void installGenericSample(String name, JettraDatabase db) {
        db.getDocumentEngine("items").insert("item_01", Map.of(
            "name", "Generic Sample Item", "database", name, "created_at", System.currentTimeMillis()
        ));
    }
}

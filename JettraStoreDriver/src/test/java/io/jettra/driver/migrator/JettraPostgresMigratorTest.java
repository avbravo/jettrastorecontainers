package io.jettra.driver.migrator;

import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.jettra.test.core.JettraAssert.*;

public class JettraPostgresMigratorTest {

    @Test
    @DisplayName("RF-17: Configuración de conexión y generación de JDBC URL PostgreSQL")
    public void testConnectionConfigAndJdbcUrl() {
        PostgresConnectionConfig cfg = new PostgresConnectionConfig(
                "pg.internal.corp", 5433, "ventas_db", "admin_pg", "supersecret", "erp", "jettra_ventas"
        );
        cfg.setSsl(true);
        cfg.setTargetEngine("JAVA_RECORD");
        cfg.setBackendPackageName("io.corp.ventas");

        assertEquals("pg.internal.corp", cfg.getHost(), "Host debe coincidir");
        assertEquals(5433, cfg.getPort(), "Puerto debe coincidir");
        assertEquals("ventas_db", cfg.getDatabase(), "Base de datos origen debe coincidir");
        assertEquals("erp", cfg.getSchema(), "Esquema debe coincidir");
        assertEquals("jettra_ventas", cfg.getTargetDatabase(), "Base de datos destino debe coincidir");
        assertEquals("JAVA_RECORD", cfg.getTargetEngine(), "Motor destino debe coincidir");
        assertTrue(cfg.isSsl(), "SSL debe estar activo");

        String url = cfg.toJdbcUrl();
        assertTrue(url.startsWith("jdbc:postgresql://pg.internal.corp:5433/ventas_db"), "URL debe tener formato JDBC estándar");
        assertTrue(url.contains("currentSchema=erp"), "URL debe especificar currentSchema");
        assertTrue(url.contains("ssl=true"), "URL debe especificar ssl=true");
    }

    @Test
    @DisplayName("RF-17: Mapeo de tipos críticos: Decimales (BigDecimal), Fechas/Horas ISO-8601 y Auto-Increment")
    public void testTypeMappingDecimalsAndDates() {
        // 1. Decimales con alta precisión (NUMERIC / DECIMAL)
        PostgresColumnMeta colMonto = new PostgresColumnMeta(
                "monto_total", "numeric", 18, 4, false, false, false, null
        );
        assertEquals("java.math.BigDecimal", colMonto.getJavaType(), "NUMERIC debe mapearse a java.math.BigDecimal");
        assertEquals("BigDecimal", colMonto.getJettraStoreType(), "Tipo JettraStore debe ser BigDecimal");
        assertEquals("montoTotal", colMonto.getJavaFieldName(), "Nombre en Java debe ser camelCase");
        assertTrue(colMonto.getJettraRuleAnnotation().contains("@NotNull"), "Columna NOT NULL debe llevar @NotNull");

        // Simular valor decimal de alta precisión sin distorsión IEEE 754
        BigDecimal originalDecimal = new BigDecimal("12345678901234.5678");
        String decimalString = originalDecimal.toPlainString();
        BigDecimal reconstructed = new BigDecimal(decimalString);
        assertEquals(originalDecimal, reconstructed, "La precisión decimal debe preservarse idéntica sin redondeo flotante");

        // 2. Fechas y Marcas de Tiempo (TIMESTAMPTZ y DATE)
        PostgresColumnMeta colFecha = new PostgresColumnMeta(
                "fecha_emision", "date", 0, 0, false, false, false, null
        );
        assertEquals("java.time.LocalDate", colFecha.getJavaType(), "DATE debe mapearse a java.time.LocalDate");
        assertEquals("fechaEmision", colFecha.getJavaFieldName(), "Nombre en Java debe ser fechaEmision");

        LocalDate today = LocalDate.of(2026, 11, 24);
        String isoDate = today.toString();
        assertEquals("2026-11-24", isoDate, "Formato de fecha debe ser ISO-8601 YYYY-MM-DD");
        assertEquals(today, LocalDate.parse(isoDate), "Fecha parseada debe coincidir");

        PostgresColumnMeta colTs = new PostgresColumnMeta(
                "creado_en", "timestamptz", 0, 0, false, false, false, null
        );
        assertEquals("java.time.Instant", colTs.getJavaType(), "TIMESTAMPTZ debe mapearse a java.time.Instant");

        Instant now = Instant.parse("2026-11-24T10:45:00.123456Z");
        String isoTs = now.toString();
        assertEquals(now, Instant.parse(isoTs), "Marca de tiempo ISO-8601 con microsegundos debe preservarse exacta");

        // 3. Serial / Auto-Increment y Clave Primaria
        PostgresColumnMeta colId = new PostgresColumnMeta(
                "id_factura", "bigserial", 0, 0, false, true, true, "nextval('factura_id_seq')"
        );
        assertEquals("Long", colId.getJavaType(), "bigserial debe mapearse a Long");
        assertTrue(colId.isAutoIncrement(), "Debe reconocerse como AutoIncrement");
        assertTrue(colId.isPrimaryKey(), "Debe reconocerse como PrimaryKey");

        // 4. Cadena con restricción de longitud
        PostgresColumnMeta colRfc = new PostgresColumnMeta(
                "rfc_cliente", "varchar", 13, 0, false, false, false, null
        );
        assertEquals("String", colRfc.getJavaType(), "varchar debe mapearse a String");
        assertTrue(colRfc.getJettraRuleAnnotation().contains("@NotBlank"), "Debe tener @NotBlank");
        assertTrue(colRfc.getJettraRuleAnnotation().contains("@Size(max = 13)"), "Debe incluir @Size(max = 13)");
    }

    @Test
    @DisplayName("RF-17: Metadatos de Tabla e Índices: PascalCase y Tipos de Índice")
    public void testTableAndIndexMapping() {
        PostgresTableMeta table = new PostgresTableMeta("ordenes_compra");
        assertEquals("OrdenesCompra", table.getJavaClassName(), "Nombre de tabla snake_case debe convertirse a PascalCase");

        PostgresIndexMeta idxNumeric = new PostgresIndexMeta("idx_orden_total", "ordenes_compra", "total", false, "NUMERIC");
        assertEquals("NUMERIC", idxNumeric.getIndexType(), "Índice numérico debe clasificarse como NUMERIC");

        PostgresIndexMeta idxAuto = new PostgresIndexMeta("idx_orden_secuencia", "ordenes_compra", "numero_orden", true, "AUTO_INCREMENT");
        assertEquals("AUTO_INCREMENT", idxAuto.getIndexType(), "Índice de secuencia debe ser AUTO_INCREMENT");

        PostgresIndexMeta idxUnique = new PostgresIndexMeta("idx_orden_folio", "ordenes_compra", "folio", true, "BTREE");
        assertEquals("BTREE", idxUnique.getIndexType(), "Índice general debe ser BTREE");
        assertTrue(idxUnique.isUnique(), "Debe ser único");
    }

    @Test
    @DisplayName("RF-17: Generación de Proyecto Backend descargable en .zip estilo Jettra")
    public void testBackendProjectZipGeneration() throws IOException {
        PostgresTableMeta factura = new PostgresTableMeta("facturas");
        factura.setRowCount(1500L);
        factura.setColumns(List.of(
                new PostgresColumnMeta("id", "bigserial", 0, 0, false, true, true, "nextval()"),
                new PostgresColumnMeta("folio", "varchar", 20, 0, false, false, false, null),
                new PostgresColumnMeta("total", "numeric", 18, 2, false, false, false, null),
                new PostgresColumnMeta("fecha", "date", 0, 0, false, false, false, null)
        ));
        factura.setIndexes(List.of(
                new PostgresIndexMeta("idx_fac_folio", "facturas", "folio", true, "BTREE"),
                new PostgresIndexMeta("idx_fac_total", "facturas", "total", false, "NUMERIC")
        ));

        PostgresTableMeta cliente = new PostgresTableMeta("clientes");
        cliente.setRowCount(300L);
        cliente.setColumns(List.of(
                new PostgresColumnMeta("id", "serial", 0, 0, false, true, true, "nextval()"),
                new PostgresColumnMeta("nombre", "varchar", 100, 0, false, false, false, null),
                new PostgresColumnMeta("activo", "boolean", 0, 0, false, false, false, "true")
        ));

        PostgresIntrospectionResult metadata = new PostgresIntrospectionResult();
        metadata.setDatabase("facturacion_pg");
        metadata.setSchema("public");
        metadata.setTables(List.of(factura, cliente));
        metadata.setTotalRows(1800L);

        PostgresConnectionConfig config = new PostgresConnectionConfig();
        config.setDatabase("facturacion_pg");
        config.setTargetDatabase("jettra_facturacion");
        config.setBackendPackageName("com.empresa.facturacion");

        JettraBackendProjectGenerator generator = new JettraBackendProjectGenerator();
        byte[] zipBytes = generator.generateZip(metadata, config);

        assertNotNull(zipBytes, "El archivo .zip generado no debe ser nulo");
        assertTrue(zipBytes.length > 500, "El .zip debe contener bytes reales del proyecto");

        // Inspeccionar el contenido del ZIP generado
        Map<String, String> zipEntries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                zipEntries.put(entry.getName(), baos.toString(StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }

        // 1. Verificar pom.xml
        assertTrue(zipEntries.containsKey("pom.xml"), "El ZIP debe contener pom.xml");
        String pom = zipEntries.get("pom.xml");
        assertTrue(pom.contains("<release>25</release>"), "pom.xml debe compilar con Java 25");
        assertTrue(pom.contains("<groupId>com.empresa.facturacion</groupId>"), "pom.xml debe tener el groupId configurado");
        assertTrue(pom.contains("JettraStoreDriver"), "pom.xml debe incluir JettraStoreDriver");
        assertTrue(pom.contains("JettraRules"), "pom.xml debe incluir JettraRules");
        assertTrue(pom.contains("JettraJWT"), "pom.xml debe incluir JettraJWT");

        // 2. Verificar application.properties
        assertTrue(zipEntries.containsKey("src/main/resources/application.properties"), "Debe incluir application.properties");
        String props = zipEntries.get("src/main/resources/application.properties");
        assertTrue(props.contains("jettra.store.database=jettra_facturacion"), "application.properties debe apuntar a la BD destino");

        // 3. Verificar Models (Java 25 Records con JettraRules)
        assertTrue(zipEntries.containsKey("src/main/java/com/empresa/facturacion/model/Facturas.java"), "Debe generar Model Facturas.java");
        String modelFacturas = zipEntries.get("src/main/java/com/empresa/facturacion/model/Facturas.java");
        assertTrue(modelFacturas.contains("public record Facturas("), "El modelo debe ser un Java Record");
        assertTrue(modelFacturas.contains("JettraId jettraId"), "El modelo debe tener JettraId");
        assertTrue(modelFacturas.contains("java.math.BigDecimal total"), "El campo total debe ser BigDecimal");
        assertTrue(modelFacturas.contains("@NotNull"), "Debe contener anotaciones de validación JettraRules");

        // 4. Verificar Repositories con JettraClient
        assertTrue(zipEntries.containsKey("src/main/java/com/empresa/facturacion/repository/FacturasRepository.java"), "Debe generar FacturasRepository.java");
        String repoFacturas = zipEntries.get("src/main/java/com/empresa/facturacion/repository/FacturasRepository.java");
        assertTrue(repoFacturas.contains("public class FacturasRepository implements JettraRepository<Facturas>"), "Debe implementar JettraRepository");
        assertTrue(repoFacturas.contains("client.insertBucketRecord("), "Debe usar client.insertBucketRecord");
        assertTrue(repoFacturas.contains("JettraRulesEngine.validate(entity)"), "Debe validar con JettraRulesEngine");

        // 5. Verificar REST Endpoints en JettraServer
        assertTrue(zipEntries.containsKey("src/main/java/com/empresa/facturacion/endpoint/FacturasEndpoint.java"), "Debe generar FacturasEndpoint.java");
        String endpointFacturas = zipEntries.get("src/main/java/com/empresa/facturacion/endpoint/FacturasEndpoint.java");
        assertTrue(endpointFacturas.contains("server.get(\"/api/facturas\""), "Debe exponer endpoint GET /api/facturas");
        assertTrue(endpointFacturas.contains("server.post(\"/api/facturas\""), "Debe exponer endpoint POST /api/facturas");
        assertTrue(endpointFacturas.contains("SecurityConfig.validateRequest(req, resp)"), "Debe verificar seguridad en mutaciones");

        // 6. Verificar App.java y README.md
        assertTrue(zipEntries.containsKey("src/main/java/com/empresa/facturacion/App.java"), "Debe generar App.java");
        assertTrue(zipEntries.containsKey("README.md"), "Debe generar README.md");
        String readme = zipEntries.get("README.md");
        assertTrue(readme.contains("jettra_facturacion"), "README.md debe documentar la base de datos");
    }

    @Test
    @DisplayName("RF-17: Modo Simulación Dry-Run y Progress")
    public void testDryRunMigrationProgress() {
        PostgresConnectionConfig config = new PostgresConnectionConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabase("test_db");
        config.setDryRun(true);
        config.setGenerateBackend(true);

        MigrationProgress progress = new MigrationProgress();
        progress.setTotalTables(3);
        progress.setTablesMigrated(3);
        progress.setTotalRecords(100L);
        progress.setRecordsMigrated(100L);
        progress.addLog("Simulación de prueba");

        assertEquals(100, progress.getPercentComplete(), "Porcentaje de avance debe ser 100%");
        assertEquals(3, progress.getTablesMigrated(), "Tablas migradas debe ser 3");
        assertFalse(progress.isHasErrors(), "No debe tener errores");
        assertTrue(progress.getLogs().contains("Simulación de prueba"), "Los logs deben registrarse");
    }

    @Test
    @DisplayName("RF-17: Descubrimiento de bases de datos y esquemas con override en JDBC URL")
    public void testDiscoveryAndOverrideJdbcUrl() {
        PostgresConnectionConfig cfg = new PostgresConnectionConfig();
        cfg.setHost("pg.cloud.corp");
        cfg.setPort(5432);
        cfg.setDatabase("ventas");
        cfg.setSchema("public");

        // 1. Validar URL por defecto
        String defaultUrl = cfg.toJdbcUrl();
        assertTrue(defaultUrl.contains("/ventas"), "URL por defecto debe usar 'ventas'");

        // 2. Validar URL con override de base de datos para introspección
        String overrideUrl = cfg.toJdbcUrl("contabilidad");
        assertTrue(overrideUrl.contains("/contabilidad"), "URL con override debe apuntar a 'contabilidad'");

        // 3. Modelo de introspección con bases y esquemas disponibles
        PostgresIntrospectionResult res = new PostgresIntrospectionResult();
        res.setAvailableDatabases(List.of("postgres", "ventas", "contabilidad", "rrhh"));
        res.setAvailableSchemas(List.of("public", "facturacion", "seguridad"));

        assertEquals(4, res.getAvailableDatabases().size(), "Debe almacenar las 4 bases descubiertas");
        assertTrue(res.getAvailableDatabases().contains("contabilidad"), "Debe contener 'contabilidad'");
        assertEquals(3, res.getAvailableSchemas().size(), "Debe almacenar los 3 esquemas");
        assertTrue(res.getAvailableSchemas().contains("facturacion"), "Debe contener 'facturacion'");
    }
}

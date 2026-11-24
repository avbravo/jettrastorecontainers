package io.jettra.driver.migrator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generador de proyecto backend descargable en formato .zip estilo Jettra (Java 25+).
 * Genera models (records), repositories con JettraClient, reglas de validación JettraRules,
 * seguridad JettraJWT, endpoints REST en JettraServer y pom.xml configurado.
 */
public class JettraBackendProjectGenerator {

    public byte[] generateZip(PostgresIntrospectionResult metadata, PostgresConnectionConfig config) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        generateZip(metadata, config, baos);
        return baos.toByteArray();
    }

    public void generateZip(PostgresIntrospectionResult metadata, PostgresConnectionConfig config, OutputStream out) throws IOException {
        String pkg = (config != null && config.getBackendPackageName() != null && !config.getBackendPackageName().isBlank())
                ? config.getBackendPackageName().trim()
                : "com.example.jettra.backend";
        String targetDb = (config != null && config.getTargetDatabase() != null && !config.getTargetDatabase().isBlank())
                ? config.getTargetDatabase().trim()
                : (metadata != null && metadata.getDatabase() != null ? metadata.getDatabase() : "jettra_migrated_db");

        String pkgPath = pkg.replace('.', '/');

        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            // 1. pom.xml
            addZipEntry(zos, "pom.xml", generatePomXml(pkg));

            // 2. application.properties
            addZipEntry(zos, "src/main/resources/application.properties", generateApplicationProperties(targetDb, config));

            // 3. Security Config
            addZipEntry(zos, "src/main/java/" + pkgPath + "/security/SecurityConfig.java", generateSecurityConfig(pkg));

            // 4. Base Repository Interface
            addZipEntry(zos, "src/main/java/" + pkgPath + "/repository/JettraRepository.java", generateBaseRepository(pkg));

            // 5. Models, Repositories y Endpoints por cada tabla
            if (metadata != null && metadata.getTables() != null) {
                for (PostgresTableMeta table : metadata.getTables()) {
                    String className = table.getJavaClassName();
                    // Model Record
                    addZipEntry(zos, "src/main/java/" + pkgPath + "/model/" + className + ".java",
                            generateModelRecord(pkg, table));
                    // Repository
                    addZipEntry(zos, "src/main/java/" + pkgPath + "/repository/" + className + "Repository.java",
                            generateRepository(pkg, table, targetDb));
                    // REST Endpoint
                    addZipEntry(zos, "src/main/java/" + pkgPath + "/endpoint/" + className + "Endpoint.java",
                            generateEndpoint(pkg, table));
                }
            }

            // 6. Main App
            addZipEntry(zos, "src/main/java/" + pkgPath + "/App.java", generateAppClass(pkg, metadata, targetDb));

            // 7. README.md
            addZipEntry(zos, "README.md", generateReadme(pkg, targetDb, metadata));
        }
    }

    private void addZipEntry(ZipOutputStream zos, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zos.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes, 0, bytes.length);
        zos.closeEntry();
    }

    private String generatePomXml(String pkg) {
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>%s</groupId>
    <artifactId>jettra-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <name>Jettra Backend Migrado</name>
    <description>Proyecto Backend Reactivo Moderno en Java 25+ con JettraStack y JettraStore</description>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <jettra.frameworks.version>1.0.0-SNAPSHOT</jettra.frameworks.version>
        <jettra.store.version>1.0-SNAPSHOT</jettra.store.version>
        <main.class>%s.App</main.class>
    </properties>

    <dependencies>
        <!-- Servidor Web Ultraligero JettraServer con Virtual Threads -->
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>jettra-server-core</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>jettra-server-flux</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>

        <!-- Driver de Acceso a JettraStore con Pooling y Failover -->
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>JettraStoreDriver</artifactId>
            <version>${jettra.store.version}</version>
        </dependency>

        <!-- Motor de Reglas y Validaciones JettraRules -->
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>JettraRules</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>

        <!-- Seguridad y Autenticación JettraJWT -->
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>JettraJWT</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>

        <!-- Colecciones Especializadas y JSON Serializer -->
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>JettraCollections</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jettra</groupId>
            <artifactId>JettraJSON</artifactId>
            <version>${jettra.frameworks.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>25</release>
                    <compilerArgs>
                        <arg>--enable-preview</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.1</version>
                <configuration>
                    <mainClass>${main.class}</mainClass>
                    <arguments>
                        <argument>--enable-preview</argument>
                    </arguments>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
""".formatted(pkg, pkg);
    }

    private String generateApplicationProperties(String targetDb, PostgresConnectionConfig config) {
        return """
# Configuración del Servidor Web Jettra
server.port=8080
server.host=0.0.0.0
server.contextpath=/

# Conexión al motor JettraStore
jettra.store.host=127.0.0.1
jettra.store.port=50001
jettra.store.database=%s
jettra.store.user=admin
jettra.store.password=admin-jettra

# Seguridad JettraJWT
server.JWT_SECRET=jettra_secret_jwt_key_migrated_2026_super_secure
server.JWT_EXPIRATION=86400000
""".formatted(targetDb);
    }

    private String generateSecurityConfig(String pkg) {
        return """
package %s.security;

import io.jettra.jwt.JettraJWT;
import io.jettra.server.core.http.HttpRequest;
import io.jettra.server.core.http.HttpResponse;
import io.jettra.server.core.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Configuración de seguridad y autenticación basada en JettraJWT.
 */
public final class SecurityConfig {

    private static final JettraJWT JWT = new JettraJWT();

    private SecurityConfig() {}

    public static String generateToken(String username, List<String> roles) {
        return JWT.generateToken(username, roles);
    }

    public static boolean validateRequest(HttpRequest req, HttpResponse resp) {
        String auth = req.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            resp.status(HttpStatus.UNAUTHORIZED);
            resp.contentType("application/json");
            resp.body("{\\"error\\": \\"Token de autorización Bearer ausente o inválido\\"}");
            return false;
        }
        String token = auth.substring(7).trim();
        if (!JWT.isTokenValid(token, "admin") && !JWT.isTokenValid(token, "user")) {
            resp.status(HttpStatus.FORBIDDEN);
            resp.contentType("application/json");
            resp.body("{\\"error\\": \\"Token JWT expirado o no autorizado\\"}");
            return false;
        }
        return true;
    }
}
""".formatted(pkg);
    }

    private String generateBaseRepository(String pkg) {
        return """
package %s.repository;

import java.util.List;
import java.util.Optional;

/**
 * Interfaz genérica de Repositorio CRUD para modelos de dominio en JettraStore.
 */
public interface JettraRepository<T> {
    void initBucketAndIndexes();
    T save(T entity);
    Optional<T> findById(String id);
    List<T> findAll();
    T update(String id, T entity);
    boolean delete(String id);
}
""".formatted(pkg);
    }

    private String generateModelRecord(String pkg, PostgresTableMeta table) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".model;\n\n");
        sb.append("import io.jettra.rules.validations.*;\n");
        sb.append("import io.jettra.store.core.JettraId;\n\n");
        sb.append("/**\n");
        sb.append(" * Modelo de dominio inmutable (Java 25 Record) para la entidad '").append(table.getTableName()).append("'.\n");
        sb.append(" * Mapeado desde tabla PostgreSQL con soporte de validaciones JettraRules y JettraId.\n");
        sb.append(" */\n");
        sb.append("public record ").append(table.getJavaClassName()).append("(\n");
        sb.append("        JettraId jettraId");

        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append(",\n");
            if (col.getJettraRuleAnnotation() != null && !col.getJettraRuleAnnotation().isBlank()) {
                sb.append("        ").append(col.getJettraRuleAnnotation().replace("\n", "\n        ")).append("\n");
            }
            sb.append("        ").append(col.getJavaType()).append(" ").append(col.getJavaFieldName());
        }
        sb.append("\n) {\n\n");

        // Constructor de conveniencia sin JettraId
        sb.append("    /** Constructor de conveniencia para nuevos registros con JettraId autogenerado */\n");
        sb.append("    public ").append(table.getJavaClassName()).append("(");
        int i = 0;
        for (PostgresColumnMeta col : table.getColumns()) {
            if (i++ > 0) sb.append(", ");
            sb.append(col.getJavaType()).append(" ").append(col.getJavaFieldName());
        }
        sb.append(") {\n");
        sb.append("        this(null");
        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append(", ").append(col.getJavaFieldName());
        }
        sb.append(");\n");
        sb.append("    }\n\n");

        // Método withJettraId
        sb.append("    public ").append(table.getJavaClassName()).append(" withJettraId(JettraId id) {\n");
        sb.append("        return new ").append(table.getJavaClassName()).append("(id");
        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append(", ").append(col.getJavaFieldName());
        }
        sb.append(");\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateRepository(String pkg, PostgresTableMeta table, String targetDb) {
        String className = table.getJavaClassName();
        String tableName = table.getTableName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".repository;\n\n");
        sb.append("import io.jettra.driver.JettraClient;\n");
        sb.append("import ").append(pkg).append(".model.").append(className).append(";\n");
        sb.append("import io.jettra.rules.core.JettraRulesEngine;\n");
        sb.append("import io.jettra.rules.core.RuleResult;\n");
        sb.append("import io.jettra.store.core.JettraBucketSchema;\n");
        sb.append("import io.jettra.store.core.JettraId;\n");
        sb.append("import java.util.*;\n\n");

        sb.append("/**\n");
        sb.append(" * Repositorio de acceso a datos para el Bucket '").append(tableName).append("' en JettraStore.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append("Repository implements JettraRepository<").append(className).append("> {\n\n");
        sb.append("    public static final String ENGINE = \"DOCUMENT\";\n");
        sb.append("    public static final String BUCKET = \"").append(tableName).append("\";\n\n");
        sb.append("    private final JettraClient client;\n");
        sb.append("    private final String database;\n\n");

        sb.append("    public ").append(className).append("Repository(JettraClient client, String database) {\n");
        sb.append("        this.client = client;\n");
        sb.append("        this.database = (database != null && !database.isBlank()) ? database : \"").append(targetDb).append("\";\n");
        sb.append("    }\n\n");

        // initBucketAndIndexes
        sb.append("    @Override\n");
        sb.append("    public void initBucketAndIndexes() {\n");
        sb.append("        List<JettraBucketSchema.AttributeRule> schema = List.of(\n");
        sb.append("            new JettraBucketSchema.AttributeRule(\"jettraId\", \"String\", \"NOT_NULL\", \"Clave única JettraId\")");
        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append(",\n            new JettraBucketSchema.AttributeRule(\"")
              .append(col.getColumnName()).append("\", \"")
              .append(col.getJettraStoreType()).append("\", \"")
              .append(col.isNullable() ? "" : "NOT_NULL")
              .append("\", \"Campo migrado de PostgreSQL\")");
        }
        sb.append("\n        );\n");
        sb.append("        try {\n");
        sb.append("            client.createBucket(database, ENGINE, BUCKET, JettraBucketSchema.StructureType.DEFINED, schema);\n");
        sb.append("        } catch (Exception ignored) {}\n\n");

        // Crear índices detectados
        for (PostgresIndexMeta idx : table.getIndexes()) {
            sb.append("        try {\n");
            if ("AUTO_INCREMENT".equalsIgnoreCase(idx.getIndexType())) {
                sb.append("            client.createAutoIncrementIndex(database, BUCKET, \"").append(idx.getIndexName()).append("\", \"").append(idx.getColumnName()).append("\");\n");
            } else {
                sb.append("            client.createBucketIndex(database, BUCKET, \"").append(idx.getIndexName()).append("\", \"").append(idx.getColumnName()).append("\", \"").append(idx.getIndexType()).append("\", ").append(idx.isUnique()).append(");\n");
            }
            sb.append("        } catch (Exception ignored) {}\n");
        }
        sb.append("    }\n\n");

        // save
        sb.append("    @Override\n");
        sb.append("    public ").append(className).append(" save(").append(className).append(" entity) {\n");
        sb.append("        validate(entity);\n");
        sb.append("        JettraId id = entity.jettraId() != null ? entity.jettraId() : JettraId.generate(database, BUCKET);\n");
        sb.append("        ").append(className).append(" toSave = entity.withJettraId(id);\n");
        sb.append("        Map<String, Object> map = toMap(toSave);\n");
        sb.append("        client.insertBucketRecord(database, ENGINE, BUCKET, id.toHexString(), map);\n");
        sb.append("        return toSave;\n");
        sb.append("    }\n\n");

        // findById
        sb.append("    @Override\n");
        sb.append("    public Optional<").append(className).append("> findById(String id) {\n");
        sb.append("        if (id == null || id.isBlank()) return Optional.empty();\n");
        sb.append("        Map<String, Object> map = client.getBucketRecord(database, ENGINE, BUCKET, id);\n");
        sb.append("        if (map == null || map.isEmpty()) return Optional.empty();\n");
        sb.append("        return Optional.of(fromMap(map));\n");
        sb.append("    }\n\n");

        // findAll
        sb.append("    @Override\n");
        sb.append("    public List<").append(className).append("> findAll() {\n");
        sb.append("        List<Map<String, Object>> records = client.listBucketRecords(database, ENGINE, BUCKET);\n");
        sb.append("        List<").append(className).append("> list = new ArrayList<>();\n");
        sb.append("        if (records != null) {\n");
        sb.append("            for (Map<String, Object> map : records) {\n");
        sb.append("                list.add(fromMap(map));\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return list;\n");
        sb.append("    }\n\n");

        // update
        sb.append("    @Override\n");
        sb.append("    public ").append(className).append(" update(String id, ").append(className).append(" entity) {\n");
        sb.append("        validate(entity);\n");
        sb.append("        Map<String, Object> map = toMap(entity);\n");
        sb.append("        client.updateBucketRecord(database, ENGINE, BUCKET, id, map);\n");
        sb.append("        return entity;\n");
        sb.append("    }\n\n");

        // delete
        sb.append("    @Override\n");
        sb.append("    public boolean delete(String id) {\n");
        sb.append("        return client.deleteBucketRecord(database, ENGINE, BUCKET, id);\n");
        sb.append("    }\n\n");

        // validate
        sb.append("    private void validate(").append(className).append(" entity) {\n");
        sb.append("        List<RuleResult> results = JettraRulesEngine.validate(entity);\n");
        sb.append("        List<String> errors = results.stream().filter(r -> !r.isValid())\n");
        sb.append("            .map(r -> r.getField() + \": \" + r.getMessage()).toList();\n");
        sb.append("        if (!errors.isEmpty()) {\n");
        sb.append("            throw new IllegalArgumentException(\"Violación de reglas de validación en ").append(className).append(": \" + String.join(\", \", errors));\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // toMap
        sb.append("    private Map<String, Object> toMap(").append(className).append(" e) {\n");
        sb.append("        Map<String, Object> m = new LinkedHashMap<>();\n");
        sb.append("        m.put(\"jettraId\", e.jettraId() != null ? e.jettraId().toHexString() : null);\n");
        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append("        m.put(\"").append(col.getColumnName()).append("\", e.").append(col.getJavaFieldName()).append("() != null ? e.").append(col.getJavaFieldName()).append("().toString() : null);\n");
        }
        sb.append("        return m;\n");
        sb.append("    }\n\n");

        // fromMap
        sb.append("    private ").append(className).append(" fromMap(Map<String, Object> m) {\n");
        sb.append("        String rawId = (String) m.get(\"jettraId\");\n");
        sb.append("        JettraId jId = (rawId != null && JettraId.isValidHex(rawId)) ? JettraId.fromHex(rawId) : null;\n");
        for (PostgresColumnMeta col : table.getColumns()) {
            String fn = col.getJavaFieldName();
            String cn = col.getColumnName();
            String jt = col.getJavaType();
            if (jt.equals("Integer")) {
                sb.append("        Integer ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? Integer.valueOf(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("Long")) {
                sb.append("        Long ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? Long.valueOf(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("java.math.BigDecimal")) {
                sb.append("        java.math.BigDecimal ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? new java.math.BigDecimal(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("Double") || jt.equals("Float")) {
                sb.append("        Double ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? Double.valueOf(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("Boolean")) {
                sb.append("        Boolean ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? Boolean.valueOf(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("java.time.Instant")) {
                sb.append("        java.time.Instant ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? java.time.Instant.parse(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else if (jt.equals("java.time.LocalDate")) {
                sb.append("        java.time.LocalDate ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? java.time.LocalDate.parse(m.get(\"").append(cn).append("\").toString()) : null;\n");
            } else {
                sb.append("        String ").append(fn).append(" = m.get(\"").append(cn).append("\") != null ? m.get(\"").append(cn).append("\").toString() : null;\n");
            }
        }
        sb.append("        return new ").append(className).append("(jId");
        for (PostgresColumnMeta col : table.getColumns()) {
            sb.append(", ").append(col.getJavaFieldName());
        }
        sb.append(");\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateEndpoint(String pkg, PostgresTableMeta table) {
        String className = table.getJavaClassName();
        String path = table.getTableName().toLowerCase();

        return """
package %s.endpoint;

import %s.model.%s;
import %s.repository.%sRepository;
import %s.security.SecurityConfig;
import io.jettra.server.core.JettraServer;
import io.jettra.server.core.http.HttpStatus;

/**
 * Endpoints REST CRUD para '%s' montados en JettraServer con Virtual Threads.
 */
public class %sEndpoint {

    public static void register(JettraServer server, %sRepository repo) {
        // Listar todos
        server.get("/api/%s", (req, resp) -> {
            resp.contentType("application/json");
            var list = repo.findAll();
            resp.body(io.jettra.json.JettraJson.toJson(list));
        });

        // Buscar por ID
        server.get("/api/%s/:id", (req, resp) -> {
            String id = req.param("id");
            var opt = repo.findById(id);
            if (opt.isPresent()) {
                resp.contentType("application/json");
                resp.body(io.jettra.json.JettraJson.toJson(opt.get()));
            } else {
                resp.status(HttpStatus.NOT_FOUND);
                resp.contentType("application/json");
                resp.body("{\\"error\\": \\"Registro no encontrado\\"}");
            }
        });

        // Crear nuevo (requiere JWT)
        server.post("/api/%s", (req, resp) -> {
            if (!SecurityConfig.validateRequest(req, resp)) return;
            try {
                %s entity = io.jettra.json.JettraJson.fromJson(req.body(), %s.class);
                %s saved = repo.save(entity);
                resp.status(HttpStatus.CREATED);
                resp.contentType("application/json");
                resp.body(io.jettra.json.JettraJson.toJson(saved));
            } catch (Exception ex) {
                resp.status(HttpStatus.BAD_REQUEST);
                resp.contentType("application/json");
                resp.body("{\\"error\\": \\"" + ex.getMessage().replace("\\"", "\\\\\\"") + "\\"}");
            }
        });

        // Eliminar por ID (requiere JWT)
        server.delete("/api/%s/:id", (req, resp) -> {
            if (!SecurityConfig.validateRequest(req, resp)) return;
            String id = req.param("id");
            boolean ok = repo.delete(id);
            resp.contentType("application/json");
            resp.body("{\\"deleted\\": " + ok + "}");
        });
    }
}
""".formatted(pkg, pkg, className, pkg, className, pkg, path, className, className, path, path, path, className, className, className, path);
    }

    private String generateAppClass(String pkg, PostgresIntrospectionResult metadata, String targetDb) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import io.jettra.driver.JettraClient;\n");
        sb.append("import io.jettra.server.core.JettraServer;\n");
        sb.append("import io.jettra.server.core.config.ServerConfig;\n");
        if (metadata != null && metadata.getTables() != null) {
            for (PostgresTableMeta t : metadata.getTables()) {
                sb.append("import ").append(pkg).append(".repository.").append(t.getJavaClassName()).append("Repository;\n");
                sb.append("import ").append(pkg).append(".endpoint.").append(t.getJavaClassName()).append("Endpoint;\n");
            }
        }
        sb.append("\n");
        sb.append("/**\n");
        sb.append(" * Punto de entrada principal para el Backend Jettra generado.\n");
        sb.append(" */\n");
        sb.append("public class App {\n\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        System.out.println(\"🚀 Iniciando Jettra Backend...\");\n");
        sb.append("        ServerConfig config = ServerConfig.load();\n");
        sb.append("        config.setPort(8080);\n");
        sb.append("        JettraServer server = new JettraServer(config);\n\n");

        sb.append("        // Conectar al clúster/nodo JettraStore\n");
        sb.append("        String dbName = \"").append(targetDb).append("\";\n");
        sb.append("        JettraClient client = JettraClient.connect(\"127.0.0.1\", 50001, \"admin\", \"admin-jettra\");\n\n");

        if (metadata != null && metadata.getTables() != null) {
            for (PostgresTableMeta t : metadata.getTables()) {
                String cn = t.getJavaClassName();
                String varName = Character.toLowerCase(cn.charAt(0)) + cn.substring(1) + "Repo";
                sb.append("        ").append(cn).append("Repository ").append(varName).append(" = new ").append(cn).append("Repository(client, dbName);\n");
                sb.append("        ").append(varName).append(".initBucketAndIndexes();\n");
                sb.append("        ").append(cn).append("Endpoint.register(server, ").append(varName).append(");\n\n");
            }
        }

        sb.append("        server.start();\n");
        sb.append("        System.out.println(\"✅ Jettra Backend ejecutándose en http://localhost:\" + config.getPort());\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateReadme(String pkg, String targetDb, PostgresIntrospectionResult metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Proyecto Backend Jettra (Java 25+)\n\n");
        sb.append("Este proyecto fue generado automáticamente por el **Migrador PostgreSQL a JettraStore** (RF-17).\n\n");
        sb.append("## Características y Arquitectura\n");
        sb.append("- **Lenguaje y Plataforma:** Java 25+ con Virtual Threads (Project Loom) y Records inmutables.\n");
        sb.append("- **Almacenamiento y BD:** JettraStore con motor multimodelo DOCUMENT / JAVA_RECORD y almacenamiento Slotted-Page Off-Heap.\n");
        sb.append("- **Base de datos objetivo:** `").append(targetDb).append("`\n");
        sb.append("- **Validaciones:** JettraRules nativo (`@NotNull`, `@NotBlank`, `@Size`, etc.).\n");
        sb.append("- **Seguridad:** Autenticación JettraJWT con soporte de tokens Bearer.\n");
        sb.append("- **Servidor Web:** JettraServer ultraligero integrado.\n\n");
        sb.append("## Entidades Migradas\n");
        if (metadata != null && metadata.getTables() != null) {
            for (PostgresTableMeta t : metadata.getTables()) {
                sb.append("- **").append(t.getJavaClassName()).append("** (Tabla original: `").append(t.getTableName()).append("`)\n");
                sb.append("  - Registros migrados: ").append(t.getRowCount()).append("\n");
                sb.append("  - Columnas: ").append(t.getColumns().size()).append("\n");
                sb.append("  - Índices: ").append(t.getIndexes().size()).append("\n");
                sb.append("  - Endpoints REST: `/api/").append(t.getTableName().toLowerCase()).append("`\n");
            }
        }
        sb.append("\n## Compilación y Ejecución\n");
        sb.append("```bash\n# Compilar el proyecto\nmvn clean compile\n\n# Ejecutar el servidor backend\nmvn exec:java\n```\n");
        return sb.toString();
    }
}

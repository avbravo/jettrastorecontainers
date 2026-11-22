package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.Pais;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import io.jettra.store.core.JettraBucketSchema;

import java.util.*;

/**
 * Repositorio para el bucket estructurado ({@code DEFINED}) {@code Pais} en el engine {@code JAVA_RECORD}.
 * Valida reglas con {@link JettraRulesEngine}, gestiona índice {@code AUTO_INCREMENT} sobre {@code codigo}
 * e índice {@code BTREE UNIQUE} sobre {@code siglas}.
 */
public final class PaisRepository implements JettraRepository<Pais, String> {

    public static final String ENGINE = "JAVA_RECORD";
    public static final String BUCKET = "Pais";

    private final JettraClient client;
    private final String database;

    public PaisRepository(JettraClient client, String database) {
        this.client = client;
        this.database = database;
    }

    public void initBucketAndIndexes() {
        List<JettraBucketSchema.AttributeRule> schema = List.of(
                new JettraBucketSchema.AttributeRule("codigo", "Long", "AUTO_INCREMENT", "Código secuencial del país"),
                new JettraBucketSchema.AttributeRule("name", "String", "NOT_NULL", "Nombre del país"),
                new JettraBucketSchema.AttributeRule("siglas", "String", "NOT_NULL,MIN_LENGTH:2,MAX_LENGTH:3", "Siglas ISO")
        );
        client.createBucket(database, ENGINE, BUCKET, JettraBucketSchema.StructureType.DEFINED, schema);
        if (client.getBucketIndex(database, "idx_pais_codigo") == null) {
            client.createAutoIncrementIndex(database, BUCKET, "idx_pais_codigo", "codigo");
        }
        if (client.getBucketIndex(database, "idx_pais_siglas") == null) {
            client.createBucketIndex(database, BUCKET, "idx_pais_siglas", "siglas", "BTREE", true);
        }
    }

    private static void validateWithJettraRules(Pais pais) {
        List<RuleResult> results = JettraRulesEngine.validate(pais);
        List<String> errors = results.stream()
                .filter(r -> !r.isValid())
                .map(r -> r.getField() + ": " + r.getMessage())
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("JettraRules rechazó Pais: " + String.join(" | ", errors));
        }
    }

    @Override
    public String save(String documentId, Pais entity) {
        validateWithJettraRules(entity);
        Map<String, Object> map = new LinkedHashMap<>();
        if (entity.codigo() != null && entity.codigo() > 0) {
            map.put("codigo", entity.codigo());
        }
        map.put("name", entity.name());
        map.put("siglas", entity.siglas());
        return client.upsertBucketRecord(database, ENGINE, BUCKET, documentId, map);
    }

    @Override
    public Optional<Pais> findById(String id) {
        Map<String, Object> doc = client.getDatabase(database).getDocumentEngine(BUCKET).findById(id);
        return doc != null ? Optional.of(toRecord(doc)) : Optional.empty();
    }

    public Optional<Pais> findBySiglas(String siglas) {
        Set<String> ids = client.findIdsByBucketIndex(database, BUCKET, "siglas", siglas);
        if (ids.isEmpty()) return Optional.empty();
        return findById(ids.iterator().next());
    }

    @Override
    public List<Pais> findAll() {
        List<Pais> out = new ArrayList<>();
        for (Map<String, Object> doc : client.getDatabase(database).getDocumentEngine(BUCKET)) {
            out.add(toRecord(doc));
        }
        return out;
    }

    @Override
    public boolean deleteById(String id) {
        return client.deleteBucketRecord(database, ENGINE, BUCKET, id);
    }

    private static Pais toRecord(Map<String, Object> doc) {
        Long codigo = doc.get("codigo") instanceof Number n ? n.longValue() : null;
        String name = doc.get("name") != null ? String.valueOf(doc.get("name")) : "";
        String siglas = doc.get("siglas") != null ? String.valueOf(doc.get("siglas")) : "";
        return new Pais(codigo, name, siglas);
    }
}

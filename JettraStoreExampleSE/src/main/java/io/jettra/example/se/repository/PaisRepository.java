package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.Pais;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import io.jettra.store.core.JettraBucketSchema;
import io.jettra.store.core.JettraId;

import java.util.*;

/**
 * Repositorio para el Bucket estructurado ({@code DEFINED}) {@code Pais} en el engine {@code JAVA_RECORD}.
 * Persiste explícitamente el {@link JettraId} en cada registro, valida reglas con {@link JettraRulesEngine}
 * y administra índices {@code AUTO_INCREMENT} sobre {@code codigo} y {@code BTREE UNIQUE} sobre {@code siglas}.
 */
public final class PaisRepository implements JettraRepository<Pais> {

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
                new JettraBucketSchema.AttributeRule("jettraId", "String", "NOT_NULL", "Identificador único JettraId"),
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
    public Pais save(Pais entity) {
        validateWithJettraRules(entity);
        JettraId assignedId = entity.jettraId() != null
                ? entity.jettraId()
                : JettraId.generate(database, BUCKET);
        String idHex = assignedId.toHexString();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jettraId", idHex);
        if (entity.codigo() != null && entity.codigo() > 0) {
            map.put("codigo", entity.codigo());
        }
        map.put("name", entity.name());
        map.put("siglas", entity.siglas());

        String storedId = client.insertBucketRecord(database, ENGINE, BUCKET, idHex, map);
        JettraId resolvedId = JettraId.fromHex(storedId);
        return findById(resolvedId).orElseGet(() -> entity.withJettraId(resolvedId));
    }

    @Override
    public Pais update(JettraId jettraId, Pais entity) {
        Objects.requireNonNull(jettraId, "El JettraId es obligatorio para actualizar Pais");
        validateWithJettraRules(entity);
        String idHex = jettraId.toHexString();
        Optional<Pais> existing = findById(jettraId);
        if (existing.isEmpty()) {
            throw new NoSuchElementException("No existe Pais con JettraId=" + idHex);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jettraId", idHex);
        Long effectiveCodigo = (entity.codigo() != null && entity.codigo() > 0)
                ? entity.codigo()
                : existing.get().codigo();
        if (effectiveCodigo != null && effectiveCodigo > 0) {
            map.put("codigo", effectiveCodigo);
        }
        map.put("name", entity.name());
        map.put("siglas", entity.siglas());

        client.upsertBucketRecord(database, ENGINE, BUCKET, idHex, map);
        return findById(jettraId).orElseThrow();
    }

    @Override
    public Optional<Pais> findById(JettraId jettraId) {
        if (jettraId == null) return Optional.empty();
        Map<String, Object> doc = client.findBucketRecordById(database, BUCKET, jettraId.toHexString());
        return doc != null ? Optional.of(toRecord(doc)) : Optional.empty();
    }

    public Optional<Pais> findBySiglas(String siglas) {
        Set<String> ids = client.findIdsByBucketIndex(database, BUCKET, "siglas", siglas);
        if (ids.isEmpty()) return Optional.empty();
        return findById(JettraId.fromHex(ids.iterator().next()));
    }

    @Override
    public List<Pais> findAll() {
        List<Pais> out = new ArrayList<>();
        for (Map<String, Object> doc : client.findAllBucketRecords(database, BUCKET)) {
            out.add(toRecord(doc));
        }
        return out;
    }

    @Override
    public boolean deleteById(JettraId jettraId) {
        if (jettraId == null) return false;
        return client.deleteBucketRecord(database, ENGINE, BUCKET, jettraId.toHexString());
    }

    private static Pais toRecord(Map<String, Object> doc) {
        String idHex = doc.get("jettraId") != null
                ? String.valueOf(doc.get("jettraId"))
                : (doc.get("_id") != null ? String.valueOf(doc.get("_id")) : null);
        JettraId jid = (idHex != null && !idHex.isBlank()) ? JettraId.fromHex(idHex) : null;
        Long codigo = doc.get("codigo") instanceof Number n ? n.longValue() : null;
        String name = doc.get("name") != null ? String.valueOf(doc.get("name")) : "";
        String siglas = doc.get("siglas") != null ? String.valueOf(doc.get("siglas")) : "";
        return new Pais(jid, codigo, name, siglas);
    }
}

package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.Producto;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import io.jettra.store.core.JettraBucketSchema;
import io.jettra.store.core.JettraId;

import java.util.*;

/**
 * Repositorio para el Bucket estructurado ({@code DEFINED}) {@code Producto} en el engine {@code DOCUMENT}.
 * Persiste explícitamente el {@link JettraId} en cada registro, valida reglas con {@link JettraRulesEngine},
 * gestiona índice {@code AUTO_INCREMENT} sobre {@code sku} e índice {@code NUMERIC} sobre {@code precio}.
 */
public final class ProductoRepository implements JettraRepository<Producto> {

    public static final String ENGINE = "DOCUMENT";
    public static final String BUCKET = "Producto";

    private final JettraClient client;
    private final String database;

    public ProductoRepository(JettraClient client, String database) {
        this.client = client;
        this.database = database;
    }

    public void initBucketAndIndexes() {
        List<JettraBucketSchema.AttributeRule> schema = List.of(
                new JettraBucketSchema.AttributeRule("jettraId", "String", "NOT_NULL", "Identificador único JettraId"),
                new JettraBucketSchema.AttributeRule("sku", "Long", "AUTO_INCREMENT", "SKU autoincrementable"),
                new JettraBucketSchema.AttributeRule("nombre", "String", "NOT_NULL", "Nombre del producto"),
                new JettraBucketSchema.AttributeRule("categoria", "String", "NOT_NULL", "Categoría comercial"),
                new JettraBucketSchema.AttributeRule("precio", "Double", "POSITIVE", "Precio unitario")
        );
        client.createBucket(database, ENGINE, BUCKET, JettraBucketSchema.StructureType.DEFINED, schema);
        if (client.getBucketIndex(database, "idx_producto_sku") == null) {
            client.createAutoIncrementIndex(database, BUCKET, "idx_producto_sku", "sku");
        }
        if (client.getBucketIndex(database, "idx_producto_precio") == null) {
            client.createNumericIndex(database, BUCKET, "idx_producto_precio", "precio", false);
        }
    }

    private static void validateWithJettraRules(Producto producto) {
        List<RuleResult> results = JettraRulesEngine.validate(producto);
        List<String> errors = results.stream()
                .filter(r -> !r.isValid())
                .map(r -> r.getField() + ": " + r.getMessage())
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("JettraRules rechazó Producto: " + String.join(" | ", errors));
        }
    }

    @Override
    public Producto save(Producto entity) {
        validateWithJettraRules(entity);
        JettraId assignedId = entity.jettraId() != null
                ? entity.jettraId()
                : JettraId.generate(database, BUCKET);
        String idHex = assignedId.toHexString();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jettraId", idHex);
        if (entity.sku() != null && entity.sku() > 0) {
            map.put("sku", entity.sku());
        }
        map.put("nombre", entity.nombre());
        map.put("categoria", entity.categoria());
        map.put("precio", entity.precio());

        String storedId = client.insertBucketRecord(database, ENGINE, BUCKET, idHex, map);
        JettraId resolvedId = JettraId.fromHex(storedId);
        return findById(resolvedId).orElseGet(() -> entity.withJettraId(resolvedId));
    }

    @Override
    public Producto update(JettraId jettraId, Producto entity) {
        Objects.requireNonNull(jettraId, "El JettraId es obligatorio para actualizar Producto");
        validateWithJettraRules(entity);
        String idHex = jettraId.toHexString();
        Optional<Producto> existing = findById(jettraId);
        if (existing.isEmpty()) {
            throw new NoSuchElementException("No existe Producto con JettraId=" + idHex);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jettraId", idHex);
        Long effectiveSku = (entity.sku() != null && entity.sku() > 0)
                ? entity.sku()
                : existing.get().sku();
        if (effectiveSku != null && effectiveSku > 0) {
            map.put("sku", effectiveSku);
        }
        map.put("nombre", entity.nombre());
        map.put("categoria", entity.categoria());
        map.put("precio", entity.precio());

        client.upsertBucketRecord(database, ENGINE, BUCKET, idHex, map);
        return findById(jettraId).orElseThrow();
    }

    @Override
    public Optional<Producto> findById(JettraId jettraId) {
        if (jettraId == null) return Optional.empty();
        Map<String, Object> doc = client.findBucketRecordById(database, BUCKET, jettraId.toHexString());
        return doc != null ? Optional.of(toRecord(doc)) : Optional.empty();
    }

    public List<Producto> findByPrecioRange(double min, double max) {
        Set<String> ids = client.findIdsByNumericRange(database, BUCKET, "precio", min, max);
        List<Producto> out = new ArrayList<>();
        for (String id : ids) {
            findById(JettraId.fromHex(id)).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<Producto> findAll() {
        List<Producto> out = new ArrayList<>();
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

    private static Producto toRecord(Map<String, Object> doc) {
        String idHex = doc.get("jettraId") != null
                ? String.valueOf(doc.get("jettraId"))
                : (doc.get("_id") != null ? String.valueOf(doc.get("_id")) : null);
        JettraId jid = (idHex != null && !idHex.isBlank()) ? JettraId.fromHex(idHex) : null;
        Long sku = doc.get("sku") instanceof Number n ? n.longValue() : null;
        String nombre = doc.get("nombre") != null ? String.valueOf(doc.get("nombre")) : "";
        String categoria = doc.get("categoria") != null ? String.valueOf(doc.get("categoria")) : "";
        double precio = doc.get("precio") instanceof Number n ? n.doubleValue() : 0.0;
        return new Producto(jid, sku, nombre, categoria, precio);
    }
}

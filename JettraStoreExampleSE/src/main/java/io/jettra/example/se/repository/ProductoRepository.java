package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.Producto;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import io.jettra.store.core.JettraBucketSchema;

import java.util.*;

/**
 * Repositorio para el bucket estructurado ({@code DEFINED}) {@code Producto} en el engine {@code DOCUMENT}.
 * Combina validación {@link JettraRulesEngine}, índice {@code AUTO_INCREMENT} sobre {@code sku}
 * e índice {@code NUMERIC} sobre {@code precio} con consultas por rango.
 */
public final class ProductoRepository implements JettraRepository<Producto, String> {

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
    public String save(String documentId, Producto entity) {
        validateWithJettraRules(entity);
        Map<String, Object> map = new LinkedHashMap<>();
        if (entity.sku() != null && entity.sku() > 0) {
            map.put("sku", entity.sku());
        }
        map.put("nombre", entity.nombre());
        map.put("categoria", entity.categoria());
        map.put("precio", entity.precio());
        return client.upsertBucketRecord(database, ENGINE, BUCKET, documentId, map);
    }

    @Override
    public Optional<Producto> findById(String id) {
        Map<String, Object> doc = client.getDatabase(database).getDocumentEngine(BUCKET).findById(id);
        return doc != null ? Optional.of(toRecord(doc)) : Optional.empty();
    }

    public List<Producto> findByPrecioRange(double min, double max) {
        Set<String> ids = client.findIdsByNumericRange(database, BUCKET, "precio", min, max);
        List<Producto> out = new ArrayList<>();
        for (String id : ids) {
            findById(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<Producto> findAll() {
        List<Producto> out = new ArrayList<>();
        for (Map<String, Object> doc : client.getDatabase(database).getDocumentEngine(BUCKET)) {
            out.add(toRecord(doc));
        }
        return out;
    }

    @Override
    public boolean deleteById(String id) {
        return client.deleteBucketRecord(database, ENGINE, BUCKET, id);
    }

    private static Producto toRecord(Map<String, Object> doc) {
        Long sku = doc.get("sku") instanceof Number n ? n.longValue() : null;
        String nombre = doc.get("nombre") != null ? String.valueOf(doc.get("nombre")) : "";
        String categoria = doc.get("categoria") != null ? String.valueOf(doc.get("categoria")) : "";
        double precio = doc.get("precio") instanceof Number n ? n.doubleValue() : 0.0;
        return new Producto(sku, nombre, categoria, precio);
    }
}

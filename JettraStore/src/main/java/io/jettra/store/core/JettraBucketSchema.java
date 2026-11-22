package io.jettra.store.core;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Modelo de metadatos y motor de validación de estructuras para Buckets ilimitados en JettraStore.
 *
 * Cada engine de una base de datos (DOCUMENT, JAVA_RECORD, KEY_VALUE, VECTOR, GRAPH, TIMESERIES,
 * COLUMNAR, GEOSPATIAL, RELATIONAL) puede albergar una cantidad ilimitada de buckets.
 * Cada bucket define su modalidad de estructura:
 * <ul>
 *   <li>{@link StructureType#NONE}: esquema libre/flexible sin validación rígida de atributos.</li>
 *   <li>{@link StructureType#DEFINED}: esquema explícito con lista de {@link AttributeRule}
 *       ({@code atribute_name}, {@code type_atribute}, {@code jettra_rules}, {@code description}).</li>
 * </ul>
 */
public final class JettraBucketSchema {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public enum StructureType {
        NONE,
        DEFINED;

        public static StructureType fromString(String raw) {
            if (raw == null || raw.isBlank()) return NONE;
            return "DEFINED".equalsIgnoreCase(raw.trim()) ? DEFINED : NONE;
        }
    }

    /**
     * Definición de atributo de un bucket con estructura {@code DEFINED}.
     * Mantiene los nombres canónicos del contrato JSON:
     * {@code atribute_name}, {@code type_atribute}, {@code jettra_rules}, {@code description}.
     */
    public record AttributeRule(
            String atribute_name,
            String type_atribute,
            String jettra_rules,
            String description
    ) {
        public AttributeRule {
            atribute_name = atribute_name != null ? atribute_name.trim() : "";
            type_atribute = (type_atribute != null && !type_atribute.isBlank()) ? type_atribute.trim() : "String";
            jettra_rules = jettra_rules != null ? jettra_rules.trim() : "";
            description = description != null ? description.trim() : "";
        }
    }

    public record BucketMetadata(
            String databaseName,
            String engineType,
            String bucketName,
            StructureType structureType,
            List<AttributeRule> attributes,
            long createdAt
    ) {
        public BucketMetadata {
            databaseName = databaseName != null ? databaseName.trim() : "";
            engineType = normalizeEngineType(engineType);
            bucketName = bucketName != null ? bucketName.trim() : "";
            structureType = structureType != null ? structureType : StructureType.NONE;
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
        }

        public String compositeKey() {
            return buildKey(engineType, bucketName);
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }

        public String getSummary() {
            return valid ? "OK" : String.join(" | ", errors);
        }
    }

    private JettraBucketSchema() {}

    public static String normalizeEngineType(String raw) {
        if (raw == null || raw.isBlank()) return "DOCUMENT";
        String up = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (up) {
            case "DOC", "DOCUMENT", "DOCUMENTS" -> "DOCUMENT";
            case "RECORD", "RECORDS", "JAVA_RECORD", "JAVA_RECORDS", "JAVARECORD" -> "JAVA_RECORD";
            case "KV", "KEYVALUE", "KEY_VALUE" -> "KEY_VALUE";
            case "VEC", "VECTOR", "VECTORS" -> "VECTOR";
            case "GRAPH", "GRAPHS" -> "GRAPH";
            case "TS", "TIMESERIES", "TIME_SERIES" -> "TIMESERIES";
            case "COL", "COLUMNAR" -> "COLUMNAR";
            case "GEO", "GEOSPATIAL" -> "GEOSPATIAL";
            case "REL", "RELATIONAL", "SQL" -> "RELATIONAL";
            default -> up;
        };
    }

    public static String buildKey(String engineType, String bucketName) {
        return normalizeEngineType(engineType) + ":" + (bucketName != null ? bucketName.trim().toLowerCase(Locale.ROOT) : "");
    }

    /**
     * Valida un registro contra la metadata/estructura del bucket.
     * Si el bucket es {@link StructureType#NONE} (o no tiene esquema), permite cualquier registro libre.
     * Si es {@link StructureType#DEFINED}, verifica cada atributo, tipo y regla {@code jettra_rules}.
     */
    public static ValidationResult validateRecord(BucketMetadata meta, Map<String, Object> record) {
        if (meta == null || meta.structureType() == StructureType.NONE) {
            return ValidationResult.ok();
        }
        if (record == null) {
            return ValidationResult.fail(List.of("El registro no puede ser nulo para el bucket DEFINED '" + meta.bucketName() + "'."));
        }

        List<String> errors = new ArrayList<>();
        for (AttributeRule rule : meta.attributes()) {
            String attr = rule.atribute_name();
            if (attr.isBlank()) continue;

            Object value = record.get(attr);
            List<String> rules = parseRules(rule.jettra_rules());

            boolean autoInc = rules.stream().anyMatch(r ->
                    r.equalsIgnoreCase("AUTO_INCREMENT") || r.equalsIgnoreCase("AUTOINCREMENT"));
            boolean required = !autoInc && rules.stream().anyMatch(r ->
                    r.equalsIgnoreCase("NOT_NULL")
                    || r.equalsIgnoreCase("REQUIRED")
                    || r.equalsIgnoreCase("NOT_EMPTY")
                    || r.equalsIgnoreCase("NOT_BLANK"));

            if (value == null) {
                if (required) {
                    errors.add(String.format("Atributo '%s' (%s) es obligatorio [%s] en bucket '%s'.",
                            attr, rule.type_atribute(), rule.jettra_rules(), meta.bucketName()));
                }
                continue;
            }

            // 1. Validación de tipo
            String typeError = validateType(attr, value, rule.type_atribute());
            if (typeError != null) {
                errors.add(typeError);
                continue;
            }

            // 2. Validación de reglas JettraRules
            for (String token : rules) {
                String upper = token.toUpperCase(Locale.ROOT);
                if (upper.equals("NOT_NULL") || upper.equals("REQUIRED") || upper.equals("UNIQUE")) {
                    if (value instanceof String s && s.isBlank() && upper.equals("NOT_NULL")) {
                        errors.add(String.format("Atributo '%s' no puede estar vacío (regla NOT_NULL).", attr));
                    }
                } else if (upper.equals("NOT_EMPTY") || upper.equals("NOT_BLANK")) {
                    if (String.valueOf(value).isBlank()) {
                        errors.add(String.format("Atributo '%s' no puede estar en blanco (regla %s).", attr, upper));
                    }
                } else if (upper.equals("POSITIVE")) {
                    Double num = toDouble(value);
                    if (num == null || num <= 0.0) {
                        errors.add(String.format("Atributo '%s' debe ser un número positivo (> 0). Valor: '%s'.", attr, value));
                    }
                } else if (upper.equals("NON_NEGATIVE")) {
                    Double num = toDouble(value);
                    if (num == null || num < 0.0) {
                        errors.add(String.format("Atributo '%s' debe ser >= 0. Valor: '%s'.", attr, value));
                    }
                } else if (upper.equals("EMAIL")) {
                    if (!EMAIL_PATTERN.matcher(String.valueOf(value).trim()).matches()) {
                        errors.add(String.format("Atributo '%s' debe ser un correo electrónico válido. Valor: '%s'.", attr, value));
                    }
                } else if (upper.startsWith("MIN_LENGTH:")) {
                    int minLen = parseIntSafe(token.substring("MIN_LENGTH:".length()), 0);
                    if (String.valueOf(value).length() < minLen) {
                        errors.add(String.format("Atributo '%s' requiere longitud mínima de %d caracteres.", attr, minLen));
                    }
                } else if (upper.startsWith("MAX_LENGTH:")) {
                    int maxLen = parseIntSafe(token.substring("MAX_LENGTH:".length()), Integer.MAX_VALUE);
                    if (String.valueOf(value).length() > maxLen) {
                        errors.add(String.format("Atributo '%s' excede la longitud máxima de %d caracteres.", attr, maxLen));
                    }
                } else if (upper.startsWith("MIN:")) {
                    double minVal = parseDoubleSafe(token.substring("MIN:".length()), -Double.MAX_VALUE);
                    Double num = toDouble(value);
                    if (num == null || num < minVal) {
                        errors.add(String.format("Atributo '%s' debe ser >= %s. Valor: '%s'.", attr, minVal, value));
                    }
                } else if (upper.startsWith("MAX:")) {
                    double maxVal = parseDoubleSafe(token.substring("MAX:".length()), Double.MAX_VALUE);
                    Double num = toDouble(value);
                    if (num == null || num > maxVal) {
                        errors.add(String.format("Atributo '%s' debe ser <= %s. Valor: '%s'.", attr, maxVal, value));
                    }
                } else if (upper.startsWith("REGEX:")) {
                    String regex = token.substring("REGEX:".length());
                    if (!Pattern.compile(regex).matcher(String.valueOf(value)).matches()) {
                        errors.add(String.format("Atributo '%s' no cumple el patrón REGEX '%s'.", attr, regex));
                    }
                }
            }
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private static List<String> parseRules(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,;|]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String validateType(String attr, Object value, String declaredType) {
        if (declaredType == null || declaredType.isBlank() || declaredType.equalsIgnoreCase("Object") || declaredType.equalsIgnoreCase("Any")) {
            return null;
        }
        String t = declaredType.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "string", "text", "varchar" -> {
                if (!(value instanceof CharSequence)) {
                    return String.format("Atributo '%s' espera tipo String, recibido: %s (%s).",
                            attr, value.getClass().getSimpleName(), value);
                }
            }
            case "int", "integer" -> {
                if (value instanceof Integer) return null;
                if (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) return null;
                if (value instanceof String s) {
                    try { Integer.parseInt(s.trim()); return null; } catch (Exception ignored) {}
                }
                return String.format("Atributo '%s' espera tipo Integer, recibido: '%s'.", attr, value);
            }
            case "long" -> {
                if (value instanceof Long || value instanceof Integer) return null;
                if (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) return null;
                if (value instanceof String s) {
                    try { Long.parseLong(s.trim()); return null; } catch (Exception ignored) {}
                }
                return String.format("Atributo '%s' espera tipo Long, recibido: '%s'.", attr, value);
            }
            case "double", "float", "number", "decimal", "bigdecimal" -> {
                if (toDouble(value) == null) {
                    return String.format("Atributo '%s' espera tipo numérico (%s), recibido: '%s'.", attr, declaredType, value);
                }
            }
            case "boolean", "bool" -> {
                if (value instanceof Boolean) return null;
                if (value instanceof String s && ("true".equalsIgnoreCase(s.trim()) || "false".equalsIgnoreCase(s.trim()))) return null;
                return String.format("Atributo '%s' espera tipo Boolean, recibido: '%s'.", attr, value);
            }
            case "list", "array" -> {
                if (!(value instanceof Collection<?>) && !value.getClass().isArray()) {
                    return String.format("Atributo '%s' espera tipo List/Array, recibido: %s.", attr, value.getClass().getSimpleName());
                }
            }
            case "map", "json" -> {
                if (!(value instanceof Map<?, ?>)) {
                    return String.format("Atributo '%s' espera tipo Map/JSON, recibido: %s.", attr, value.getClass().getSimpleName());
                }
            }
            default -> {
                // Tipos personalizados o Date/LocalDate aceptan String ISO o el objeto correspondiente
            }
        }
        return null;
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDoubleSafe(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    /**
     * Parsea un arreglo JSON de atributos con el formato:
     * {@code [{"atribute_name":"name","type_atribute":"String","jettra_rules":"NOT_NULL","description":"nombre"}]}
     */
    public static List<AttributeRule> parseAttributesJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<AttributeRule> list = new ArrayList<>();
        try {
            JettraJson parser = new JettraJson();
            Object parsed = parser.parse(json.trim());
            if (parsed instanceof JsonArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof JsonObject obj) {
                        list.add(extractAttribute(obj));
                    } else if (item instanceof Map<?, ?> m) {
                        list.add(extractAttribute(m));
                    }
                }
            } else if (parsed instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof JsonObject obj) {
                        list.add(extractAttribute(obj));
                    } else if (item instanceof Map<?, ?> m) {
                        list.add(extractAttribute(m));
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static AttributeRule extractAttribute(JsonObject obj) {
        String name = firstNonBlank(str(obj.get("atribute_name")), str(obj.get("attribute_name")), str(obj.get("name")));
        String type = firstNonBlank(str(obj.get("type_atribute")), str(obj.get("type_attribute")), str(obj.get("type")));
        String rules = firstNonBlank(str(obj.get("jettra_rules")), str(obj.get("rules")), "");
        String desc = firstNonBlank(str(obj.get("description")), str(obj.get("desc")), "");
        return new AttributeRule(name, type, rules, desc);
    }

    private static AttributeRule extractAttribute(Map<?, ?> m) {
        String name = firstNonBlank(str(m.get("atribute_name")), str(m.get("attribute_name")), str(m.get("name")));
        String type = firstNonBlank(str(m.get("type_atribute")), str(m.get("type_attribute")), str(m.get("type")));
        String rules = firstNonBlank(str(m.get("jettra_rules")), str(m.get("rules")), "");
        String desc = firstNonBlank(str(m.get("description")), str(m.get("desc")), "");
        return new AttributeRule(name, type, rules, desc);
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    public static String toAttributesJson(List<AttributeRule> attrs) {
        if (attrs == null || attrs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < attrs.size(); i++) {
            AttributeRule a = attrs.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"atribute_name\":\"").append(escape(a.atribute_name()))
              .append("\",\"type_atribute\":\"").append(escape(a.type_atribute()))
              .append("\",\"jettra_rules\":\"").append(escape(a.jettra_rules()))
              .append("\",\"description\":\"").append(escape(a.description()))
              .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    public static void saveBucketsCatalog(Path file, Collection<BucketMetadata> buckets) throws IOException {
        if (file == null) return;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("[\n");
        int i = 0;
        for (BucketMetadata b : buckets) {
            if (i++ > 0) sb.append(",\n");
            sb.append("  {\"databaseName\":\"").append(escape(b.databaseName()))
              .append("\",\"engineType\":\"").append(escape(b.engineType()))
              .append("\",\"bucketName\":\"").append(escape(b.bucketName()))
              .append("\",\"structureType\":\"").append(b.structureType().name())
              .append("\",\"createdAt\":").append(b.createdAt())
              .append(",\"attributes\":").append(toAttributesJson(b.attributes()))
              .append("}");
        }
        sb.append("\n]");
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static List<BucketMetadata> loadBucketsCatalog(Path file) {
        List<BucketMetadata> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) return out;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) return out;
            JettraJson parser = new JettraJson();
            Object parsed = parser.parse(raw);
            if (parsed instanceof JsonArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof JsonObject o) {
                        String db = str(o.get("databaseName"));
                        String eng = str(o.get("engineType"));
                        String bName = str(o.get("bucketName"));
                        StructureType st = StructureType.fromString(str(o.get("structureType")));
                        Object rawTs = o.get("createdAt");
                        long ts = (rawTs instanceof Number n) ? n.longValue() : System.currentTimeMillis();
                        List<AttributeRule> attrs = new ArrayList<>();
                        Object rawAttrs = o.get("attributes");
                        if (rawAttrs instanceof JsonArray aArr) {
                            for (int j = 0; j < aArr.size(); j++) {
                                Object ai = aArr.get(j);
                                if (ai instanceof JsonObject ao) attrs.add(extractAttribute(ao));
                                else if (ai instanceof Map<?, ?> am) attrs.add(extractAttribute(am));
                            }
                        }
                        if (bName != null && !bName.isBlank()) {
                            out.add(new BucketMetadata(db, eng, bName, st, attrs, ts));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

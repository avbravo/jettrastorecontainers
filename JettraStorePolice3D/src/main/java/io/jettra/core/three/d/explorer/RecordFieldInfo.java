package io.jettra.core.three.d.explorer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Representa un campo/propiedad de un Java Record en JettraStore,
 * incluyendo su nombre, tipo de dato, valor actual y las anotaciones
 * de validacion implementadas con JettraRules (@NotNull, @Min, @DecimalMin, etc.).
 */
public class RecordFieldInfo {
    private String property;
    private String type;
    private String value;
    private String jettraRules;

    public RecordFieldInfo(String property, String type, String value, String jettraRules) {
        this.property = (property != null) ? property : "campo";
        this.type = (type != null) ? type : "String";
        this.value = (value != null) ? value : "";
        this.jettraRules = (jettraRules != null && !jettraRules.isBlank()) ? jettraRules : "@NotNull";
    }

    public String getProperty() { return property; }
    public void setProperty(String property) { this.property = property; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getJettraRules() { return jettraRules; }
    public void setJettraRules(String jettraRules) { this.jettraRules = jettraRules; }

    public RecordFieldInfo copy() {
        return new RecordFieldInfo(property, type, value, jettraRules);
    }

    /**
     * Genera campos estandar para FacturaItemRecord con reglas JettraRules.
     */
    public static List<RecordFieldInfo> createDefaultFacturaFields(long idNum) {
        List<RecordFieldInfo> list = new ArrayList<>();
        list.add(new RecordFieldInfo("folio", "long", String.valueOf(1000 + idNum), "@NotNull @Min(1)"));
        list.add(new RecordFieldInfo("itemSku", "String", "SKU-" + (idNum * 3), "@NotBlank @Pattern(\"^[A-Z0-9-]+$\")"));
        list.add(new RecordFieldInfo("precio", "double", String.format("%.2f", (idNum * 15.75f) % 500 + 10.0), "@Positive @DecimalMin(\"0.01\")"));
        list.add(new RecordFieldInfo("tasaImpuesto", "double", "0.16", "@PositiveOrZero @DecimalMax(\"0.50\")"));
        list.add(new RecordFieldInfo("stock", "int", String.valueOf((idNum % 200) + 15), "@Min(0) @Max(100000)"));
        list.add(new RecordFieldInfo("activo", "boolean", "true", "@AssertTrue"));
        list.add(new RecordFieldInfo("offHeapOffset", "long", String.format("0x%08X", idNum * 64), "@NotNull"));
        return list;
    }

    /**
     * Genera campos estandar para metadatos de esquema de JettraStore.
     */
    public static List<RecordFieldInfo> createDefaultSchemaFields(String schemaName) {
        List<RecordFieldInfo> list = new ArrayList<>();
        list.add(new RecordFieldInfo("schemaName", "String", schemaName, "@NotBlank @Pattern(\"^[A-Za-z0-9_]+$\")"));
        list.add(new RecordFieldInfo("version", "double", "2.50", "@Positive @DecimalMin(\"1.0\")"));
        list.add(new RecordFieldInfo("multimodelBuckets", "int", "8", "@Min(1) @Max(64)"));
        list.add(new RecordFieldInfo("raftTerm", "int", "12", "@Min(0)"));
        list.add(new RecordFieldInfo("inMemoryPanamaAligned", "boolean", "true", "@AssertTrue"));
        return list;
    }

    /**
     * Parsea un texto con definicion o valores de un Record en una lista de campos.
     */
    public static List<RecordFieldInfo> parseFromDetails(String details, String summary, String recordId) {
        List<RecordFieldInfo> result = new ArrayList<>();
        if (details == null || details.isBlank()) {
            return createDefaultFacturaFields(1);
        }

        // Si contiene formato con asignaciones: // folio = 1001, itemSku = "SKU-3", ...
        Pattern valPat = Pattern.compile("(\\w+)\\s*=\\s*([^,\n\\}]+)");
        Matcher m = valPat.matcher(details);
        while (m.find()) {
            String prop = m.group(1).trim();
            String val = m.group(2).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            if (!prop.equalsIgnoreCase("Instancia") && !prop.equalsIgnoreCase("offset") && !prop.equalsIgnoreCase("public") && !prop.equalsIgnoreCase("record")) {
                String type = inferType(prop, val);
                String rules = inferJettraRules(prop, type);
                result.add(new RecordFieldInfo(prop, type, val, rules));
            }
        }

        if (result.isEmpty()) {
            // Intentar parsear las lineas del record: type name,
            Pattern fieldPat = Pattern.compile("(long|int|double|float|String|boolean|BigDecimal|UUID)\\s+(\\w+)");
            Matcher fm = fieldPat.matcher(details);
            while (fm.find()) {
                String t = fm.group(1).trim();
                String p = fm.group(2).trim();
                result.add(new RecordFieldInfo(p, t, "N/A", inferJettraRules(p, t)));
            }
        }

        if (result.isEmpty()) {
            result = createDefaultFacturaFields(1);
        }
        return result;
    }

    public static String inferType(String prop, String val) {
        if (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false")) return "boolean";
        if (val.matches("-?\\d+")) {
            try {
                long l = Long.parseLong(val);
                if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE || prop.toLowerCase().contains("folio") || prop.toLowerCase().contains("id")) {
                    return "long";
                }
                return "int";
            } catch (Exception e) {
                return "long";
            }
        }
        if (val.matches("-?\\d+\\.\\d+")) {
            if (prop.toLowerCase().contains("precio") || prop.toLowerCase().contains("total") || prop.toLowerCase().contains("monto")) {
                return "BigDecimal";
            }
            return "double";
        }
        if (val.startsWith("0x")) return "long";
        return "String";
    }

    public static String inferJettraRules(String prop, String type) {
        String p = prop.toLowerCase();
        if (p.contains("folio") || p.contains("id")) return "@NotNull @Min(1)";
        if (p.contains("sku") || p.contains("codigo")) return "@NotBlank @Pattern(\"^[A-Z0-9-]+$\")";
        if (p.contains("precio") || p.contains("monto") || p.contains("subtotal")) return "@Positive @DecimalMin(\"0.01\")";
        if (p.contains("impuesto") || p.contains("tasa") || p.contains("iva")) return "@PositiveOrZero @DecimalMax(\"0.50\")";
        if (p.contains("stock") || p.contains("cantidad")) return "@Min(0) @Max(100000)";
        if (p.contains("activo") || p.contains("validado") || p.contains("aligned")) return "@AssertTrue";
        if (p.contains("email") || p.contains("correo")) return "@Email @NotBlank";
        if (p.contains("name") || p.contains("nombre") || p.contains("schema")) return "@NotBlank @Size(min=2, max=100)";
        if ("String".equals(type)) return "@NotBlank";
        if ("long".equals(type) || "int".equals(type)) return "@NotNull @Min(0)";
        if ("double".equals(type) || "BigDecimal".equals(type)) return "@PositiveOrZero";
        return "@NotNull";
    }

    public static String buildRecordDetails(String recordClassName, List<RecordFieldInfo> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("public record ").append(recordClassName).append("(\n");
        for (int i = 0; i < fields.size(); i++) {
            RecordFieldInfo f = fields.get(i);
            sb.append("  ").append(f.getType()).append(" ").append(f.getProperty());
            if (i < fields.size() - 1) sb.append(",");
            sb.append(" // ").append(f.getJettraRules()).append("\n");
        }
        sb.append(") {\n");
        sb.append("  // Instancia Java Panama Struct:\n  // ");
        for (int i = 0; i < fields.size(); i++) {
            RecordFieldInfo f = fields.get(i);
            sb.append(f.getProperty()).append(" = ");
            if ("String".equals(f.getType())) sb.append("\"").append(f.getValue()).append("\"");
            else sb.append(f.getValue());
            if (i < fields.size() - 1) sb.append(", ");
        }
        sb.append("\n}");
        return sb.toString();
    }
}

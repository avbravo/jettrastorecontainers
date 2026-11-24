package io.jettra.driver.migrator;

import java.io.Serializable;

/**
 * Metadatos de una columna de PostgreSQL y sus reglas de mapeo equivalentes para JettraStore y modelos Java.
 */
public class PostgresColumnMeta implements Serializable {

    private String columnName;
    private String dataType;
    private int columnSize;
    private int decimalDigits;
    private boolean isNullable;
    private boolean isPrimaryKey;
    private boolean isAutoIncrement;
    private String defaultValue;
    private String javaFieldName;
    private String javaType;
    private String jettraRuleAnnotation;
    private String jettraStoreType;

    public PostgresColumnMeta() {}

    public PostgresColumnMeta(String columnName, String dataType, int columnSize, int decimalDigits,
                              boolean isNullable, boolean isPrimaryKey, boolean isAutoIncrement, String defaultValue) {
        this.columnName = columnName;
        this.dataType = dataType != null ? dataType.toLowerCase() : "";
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
        this.isNullable = isNullable;
        this.isPrimaryKey = isPrimaryKey;
        this.isAutoIncrement = isAutoIncrement;
        this.defaultValue = defaultValue;
        this.javaFieldName = toCamelCase(columnName);
        resolveTypeMappings();
    }

    private void resolveTypeMappings() {
        String dt = this.dataType.toLowerCase();
        if (dt.contains("serial") || isAutoIncrement) {
            this.javaType = "Long";
            this.jettraStoreType = "Long";
            this.jettraRuleAnnotation = "@NotNull";
        } else if (dt.contains("numeric") || dt.contains("decimal")) {
            this.javaType = "java.math.BigDecimal";
            this.jettraStoreType = "BigDecimal";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("int8") || dt.contains("bigint")) {
            this.javaType = "Long";
            this.jettraStoreType = "Long";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("int4") || dt.contains("int") || dt.contains("integer") || dt.contains("int2") || dt.contains("smallint")) {
            this.javaType = "Integer";
            this.jettraStoreType = "Integer";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("float8") || dt.contains("double")) {
            this.javaType = "Double";
            this.jettraStoreType = "Double";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("float4") || dt.contains("real")) {
            this.javaType = "Float";
            this.jettraStoreType = "Double";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("bool")) {
            this.javaType = "Boolean";
            this.jettraStoreType = "Boolean";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("timestamptz") || dt.contains("timestamp")) {
            this.javaType = "java.time.Instant";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("date")) {
            this.javaType = "java.time.LocalDate";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("time")) {
            this.javaType = "java.time.LocalTime";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotNull";
        } else if (dt.contains("uuid")) {
            this.javaType = "String";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = isNullable ? "" : "@NotBlank";
        } else if (dt.contains("json")) {
            this.javaType = "String";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = "";
        } else if (dt.contains("bytea")) {
            this.javaType = "byte[]";
            this.jettraStoreType = "String";
            this.jettraRuleAnnotation = "";
        } else {
            // varchar, char, text, bpchar, etc.
            this.javaType = "String";
            this.jettraStoreType = "String";
            if (!isNullable) {
                if (columnSize > 0 && columnSize < 10000) {
                    this.jettraRuleAnnotation = "@NotBlank\n        @Size(max = " + columnSize + ")";
                } else {
                    this.jettraRuleAnnotation = "@NotBlank";
                }
            } else if (columnSize > 0 && columnSize < 10000) {
                this.jettraRuleAnnotation = "@Size(max = " + columnSize + ")";
            } else {
                this.jettraRuleAnnotation = "";
            }
        }
    }

    private static String toCamelCase(String snake) {
        if (snake == null || snake.isBlank()) return "field";
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else if (i == 0) {
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public int getColumnSize() { return columnSize; }
    public void setColumnSize(int columnSize) { this.columnSize = columnSize; }

    public int getDecimalDigits() { return decimalDigits; }
    public void setDecimalDigits(int decimalDigits) { this.decimalDigits = decimalDigits; }

    public boolean isNullable() { return isNullable; }
    public void setNullable(boolean nullable) { isNullable = nullable; }

    public boolean isPrimaryKey() { return isPrimaryKey; }
    public void setPrimaryKey(boolean primaryKey) { isPrimaryKey = primaryKey; }

    public boolean isAutoIncrement() { return isAutoIncrement; }
    public void setAutoIncrement(boolean autoIncrement) { isAutoIncrement = autoIncrement; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public String getJavaFieldName() { return javaFieldName; }
    public void setJavaFieldName(String javaFieldName) { this.javaFieldName = javaFieldName; }

    public String getJavaType() { return javaType; }
    public void setJavaType(String javaType) { this.javaType = javaType; }

    public String getJettraRuleAnnotation() { return jettraRuleAnnotation; }
    public void setJettraRuleAnnotation(String jettraRuleAnnotation) { this.jettraRuleAnnotation = jettraRuleAnnotation; }

    public String getJettraStoreType() { return jettraStoreType; }
    public void setJettraStoreType(String jettraStoreType) { this.jettraStoreType = jettraStoreType; }
}

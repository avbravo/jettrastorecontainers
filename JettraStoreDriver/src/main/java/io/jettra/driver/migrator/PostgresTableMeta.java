package io.jettra.driver.migrator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadatos de una tabla de PostgreSQL a migrar como Bucket a JettraStore.
 */
public class PostgresTableMeta implements Serializable {

    private String tableName;
    private String javaClassName;
    private List<PostgresColumnMeta> columns = new ArrayList<>();
    private List<PostgresIndexMeta> indexes = new ArrayList<>();
    private long rowCount;

    public PostgresTableMeta() {}

    public PostgresTableMeta(String tableName) {
        this.tableName = tableName;
        this.javaClassName = toPascalCase(tableName);
    }

    public static String toPascalCase(String snake) {
        if (snake == null || snake.isBlank()) return "Entity";
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public PostgresColumnMeta getPrimaryKeyColumn() {
        for (PostgresColumnMeta col : columns) {
            if (col.isPrimaryKey()) return col;
        }
        return null;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) {
        this.tableName = tableName;
        if (this.javaClassName == null || this.javaClassName.isBlank()) {
            this.javaClassName = toPascalCase(tableName);
        }
    }

    public String getJavaClassName() { return javaClassName; }
    public void setJavaClassName(String javaClassName) { this.javaClassName = javaClassName; }

    public List<PostgresColumnMeta> getColumns() { return columns; }
    public void setColumns(List<PostgresColumnMeta> columns) { this.columns = columns; }

    public List<PostgresIndexMeta> getIndexes() { return indexes; }
    public void setIndexes(List<PostgresIndexMeta> indexes) { this.indexes = indexes; }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
}

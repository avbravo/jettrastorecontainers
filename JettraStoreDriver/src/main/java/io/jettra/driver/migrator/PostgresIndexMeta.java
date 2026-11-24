package io.jettra.driver.migrator;

import java.io.Serializable;

/**
 * Metadatos de un índice detectado en PostgreSQL y su traducción a índices de JettraStore.
 */
public class PostgresIndexMeta implements Serializable {

    private String indexName;
    private String tableName;
    private String columnName;
    private boolean isUnique;
    private String indexType; // BTREE, NUMERIC, AUTO_INCREMENT, HASH

    public PostgresIndexMeta() {}

    public PostgresIndexMeta(String indexName, String tableName, String columnName, boolean isUnique, String indexType) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
        this.isUnique = isUnique;
        this.indexType = (indexType != null && !indexType.isBlank()) ? indexType.toUpperCase() : "BTREE";
    }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public boolean isUnique() { return isUnique; }
    public void setUnique(boolean unique) { isUnique = unique; }

    public String getIndexType() { return indexType; }
    public void setIndexType(String indexType) { this.indexType = indexType; }
}

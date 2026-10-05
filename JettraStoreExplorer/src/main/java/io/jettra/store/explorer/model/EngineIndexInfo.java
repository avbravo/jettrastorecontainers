package io.jettra.store.explorer.model;

import java.io.Serializable;

public class EngineIndexInfo implements Serializable {
    private String name;
    private String fields;
    private String type; // HASH, BTREE, VECTOR_HNSW, GRAPH_ADJACENCY, SPATIAL_R_TREE, TIMESERIES_BRIN
    private boolean unique;
    private String status;

    public EngineIndexInfo() {}

    public EngineIndexInfo(String name, String fields, String type, boolean unique, String status) {
        this.name = name;
        this.fields = fields;
        this.type = type;
        this.unique = unique;
        this.status = status;
    }

    public String getName() { return name; }
    public String getFields() { return fields; }
    public String getType() { return type; }
    public boolean isUnique() { return unique; }
    public String getStatus() { return status; }
}

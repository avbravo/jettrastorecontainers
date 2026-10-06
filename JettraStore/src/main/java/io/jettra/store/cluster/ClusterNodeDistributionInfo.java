package io.jettra.store.cluster;

import java.io.Serializable;
import java.util.List;

/**
 * Representa la información de distribución de bases de datos y estado de un nodo en el clúster Raft.
 */
public record ClusterNodeDistributionInfo(
    String nodeId,
    String ip,
    int port,
    String role,
    String status,
    int databaseCount,
    List<String> databases
) implements Serializable {

    public ClusterNodeDistributionInfo {
        if (databases == null) {
            databases = List.of();
        } else {
            databases = List.copyOf(databases);
        }
    }
}

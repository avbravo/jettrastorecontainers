package com.jettra.memory.integration;

import com.jettra.memory.cluster.NodeCoordinator;
import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.engine.StorageMetrics;
import io.jettra.ee.health.HealthCheck;
import io.jettra.ee.health.HealthCheckResponse;

import java.util.Objects;

/**
 * Health Check para MicroProfile / Jakarta EE / Helidon que expone
 * el estado operativo de JettraMemory (disco, quórum del clúster de tres nodos, fragmentación).
 */
public final class JettraMemoryHealthCheck implements HealthCheck {

    private final DiskStorageEngine storageEngine;
    private final NodeCoordinator coordinator;

    public JettraMemoryHealthCheck(DiskStorageEngine storageEngine, NodeCoordinator coordinator) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine requerido");
        this.coordinator = coordinator;
    }

    @Override
    public HealthCheckResponse call() {
        StorageMetrics metrics = storageEngine.getMetrics();
        boolean quorumOk = coordinator == null || coordinator.hasQuorum();
        boolean healthy = quorumOk && metrics.fragmentationRatio() < 0.90;

        var builder = HealthCheckResponse.named("jettra-memory-storage")
                .status(healthy)
                .withData("allocatedBytes", metrics.totalAllocatedBytes())
                .withData("activeBytes", metrics.activeBytes())
                .withData("deadBytes", metrics.deadBytes())
                .withData("fragmentationRatio", String.format("%.2f%%", metrics.fragmentationRatio() * 100))
                .withData("liveEntries", metrics.liveEntries());

        if (coordinator != null) {
            builder.withData("quorumActive", quorumOk)
                    .withData("clusterOnlineNodes", coordinator.getOnlineNodesCount())
                    .withData("isThreeNodeTopology", coordinator.isConfiguredAsThreeNodeCluster());
        }

        return builder.build();
    }
}

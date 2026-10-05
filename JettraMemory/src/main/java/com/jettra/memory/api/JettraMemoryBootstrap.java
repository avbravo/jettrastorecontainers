package com.jettra.memory.api;

import com.jettra.memory.cluster.ClusterNode;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Bootstrap de inicialización rápida y configuración plug-and-play para JettraMemory.
 */
public final class JettraMemoryBootstrap {

    private JettraMemoryBootstrap() {}

    /**
     * Inicializa una instancia standalone de JettraMemory con configuración por defecto.
     */
    public static JettraMemoryEngine standalone(Path storageDirectory) throws IOException {
        JettraMemoryConfig config = JettraMemoryConfig.builder()
                .storageDirectory(storageDirectory)
                .storeName("jettra_standalone")
                .clusterMode(false)
                .autoGcEnabled(true)
                .build();
        return new JettraMemoryEngine(config);
    }

    /**
     * Inicializa un nodo configurado nativamente para operar en un clúster distribuido de tres nodos.
     */
    public static JettraMemoryEngine cluster3Nodes(
            String nodeId,
            String host,
            int port,
            Path storageDirectory,
            ClusterNode peer1,
            ClusterNode peer2
    ) throws IOException {
        JettraMemoryConfig config = JettraMemoryConfig.builder()
                .nodeId(nodeId)
                .host(host)
                .port(port)
                .storageDirectory(storageDirectory)
                .storeName("jettra_node_" + nodeId)
                .clusterMode(true)
                .addPeer(peer1)
                .addPeer(peer2)
                .autoGcEnabled(true)
                .build();
        return new JettraMemoryEngine(config);
    }

    /**
     * Retorna un constructor builder para configuración personalizada detallada.
     */
    public static JettraMemoryConfig.Builder builder() {
        return JettraMemoryConfig.builder();
    }
}

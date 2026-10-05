package io.jettra.core.three.d.police;

import io.jettra.core.three.d.model.ServerNode3D;
import io.jettra.store.cluster.ClusterNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JettraStorePoliceMonitorTest {

    @Test
    @DisplayName("Debe inicializar nodos de cluster JettraStore y evaluarlos con JettraStorePolice")
    public void testMonitorInitializationAndNodes() {
        try (JettraStorePoliceMonitor monitor = new JettraStorePoliceMonitor()) {
            List<ServerNode3D> nodes = monitor.getServerNodes();
            assertEquals(3, nodes.size(), "Deben existir 3 nodos de cluster preconfigurados");

            // Nodo 1: Master Primario
            ServerNode3D n1 = monitor.getNodeById("node-01");
            assertNotNull(n1);
            assertEquals("node-01-master", n1.getName());
            assertEquals(ClusterNode.Role.PRIMARY, n1.getRole());
            assertEquals(ClusterNode.RaftState.LEADER, n1.getRaftState());
            assertEquals(monitor.getCurrentProfile().getPort(), n1.getPort());

            // Nodo 2: Réplica Secundaria
            ServerNode3D n2 = monitor.getNodeById("node-02");
            assertNotNull(n2);
            assertEquals(ClusterNode.Role.SECONDARY, n2.getRole());
            assertEquals(ClusterNode.RaftState.FOLLOWER, n2.getRaftState());

            // Nodo 3: Configurado inicialmente Fuera de Servicio para verificación visual
            ServerNode3D n3 = monitor.getNodeById("node-03");
            assertNotNull(n3);
            assertFalse(n3.isOnline(), "node-03 debe estar inicialmente fuera de servicio");
            assertEquals(ClusterNode.NodeStatus.OFFLINE, n3.getStatus());
            assertTrue(n3.getStatusMessage().contains("FUERA DE SERVICIO"));
            assertTrue(n3.getPoliceDiagnosis().contains("CRÍTICO"));
        }
    }

    @Test
    @DisplayName("Debe permitir alternar estado de un nodo entre En Línea y Fuera de Servicio")
    public void testToggleNodeOfflineState() {
        try (JettraStorePoliceMonitor monitor = new JettraStorePoliceMonitor()) {
            ServerNode3D n1 = monitor.getNodeById("node-01");
            assertTrue(n1.isOnline());

            // Simular caída de servicio
            monitor.toggleNodeOffline("node-01");
            assertFalse(n1.isOnline(), "Nodo debe pasar a Fuera de Servicio");
            assertEquals(ClusterNode.NodeStatus.OFFLINE, n1.getStatus());
            assertTrue(n1.getStatusMessage().contains("FUERA DE SERVICIO"));
            assertTrue(n1.getPoliceDiagnosis().contains("CRÍTICO"));

            // Restaurar servicio
            monitor.toggleNodeOffline("node-01");
            assertTrue(n1.isOnline(), "Nodo debe volver a estar En Línea");
            assertEquals(ClusterNode.NodeStatus.RUNNING, n1.getStatus());
            assertTrue(n1.getStatusMessage().contains("EN LÍNEA"));
            assertTrue(n1.getPoliceDiagnosis().contains("SALUDABLE"));
        }
    }

    @Test
    @DisplayName("Debe calcular métricas de recursos y bounding box para picking 3D con clic derecho")
    public void testResourceMetricsAndBoundingBox() {
        try (JettraStorePoliceMonitor monitor = new JettraStorePoliceMonitor()) {
            monitor.pollAndEvaluateServers();

            ServerNode3D n1 = monitor.getNodeById("node-01");
            assertTrue(n1.getHeapMaxMb() > 0, "Heap Max debe ser positivo");
            assertTrue(n1.getCpuCores() > 0, "Cores CPU deben ser mayores a 0");
            assertTrue(n1.getPanamaDirectMemMb() > 0, "Memoria Panama FFM debe estar registrada");

            // Bounding box para clic derecho (Ray collision)
            var box = n1.getBoundingBox();
            assertNotNull(box);
            assertTrue(box.max().y() > box.min().y(), "Box Max Y debe ser mayor que Box Min Y");
            assertTrue(box.max().x() > box.min().x(), "Box Max X debe ser mayor que Box Min X");
        }
    }

    @Test
    @DisplayName("Debe calcular objetos procesados en tiempo real y correspondencia con Personas, Edificios, Camiones y Perros")
    public void testRealtimeProcessedObjectsAndCorrespondence() {
        try (JettraStorePoliceMonitor monitor = new JettraStorePoliceMonitor()) {
            long initialTotal = monitor.getProcessedObjectsTotal();
            assertTrue(initialTotal >= 8_250_000L, "Total de objetos inicial debe ser >= 8,250,000");
            assertTrue(monitor.getProcessedObjectsPerSecond() > 10_000L, "Throughput de ops/s debe ser > 10,000");

            // Correspondencia física 3D en tiempo real:
            // 1. Personas: Usuarios conectados en vivo a JettraStore procesando consultas
            assertFalse(monitor.getLiveSessions().isEmpty(), "Deben existir personas (sesiones de usuarios) activas");
            assertEquals(11, monitor.getLiveSessions().size(), "Debe haber 11 usuarios conectados procesando objetos");

            // 2. Edificios: Zonas geográficas donde se conectan los usuarios
            assertEquals(4, monitor.getUserZones().size(), "Deben existir 4 edificios/zonas conectados a las bases de datos");

            // 3. Camiones: Tráfico de replicación y batches de datos en tiempo real
            assertFalse(monitor.getActiveTraffic().isEmpty(), "Deben existir camiones transportando tráfico entre nodos");

            // 4. Perros: Agentes JettraPolice activados
            assertEquals(4, monitor.getActivePoliceAgents().size(), "Deben existir 4 agentes caninos policiales activos");

            // Ejecutar ciclo de evaluación de telemetría
            monitor.pollAndEvaluateServers();

            // Verificar que los objetos procesados avanzaron de acuerdo al throughput
            assertTrue(monitor.getProcessedObjectsTotal() > initialTotal, "Los objetos procesados acumulados deben incrementarse");

            // Verificar que los camiones transportan batches proporcionales a las ops/sec
            for (var tr : monitor.getActiveTraffic()) {
                assertNotNull(tr.getPayloadSummary());
                assertTrue(tr.getPayloadSummary().contains("Batch"), "El resumen del camión debe reportar el lote de objetos procesados");
            }
        }
    }


    @Test
    @DisplayName("Debe reportar soporte de cluster.multinode.active en ClusterConfigLoader y Monitor")
    public void testClusterMultinodeActiveSupport() {
        io.jettra.core.three.d.config.ClusterConfigLoader loader = io.jettra.core.three.d.config.ClusterConfigLoader.getInstance();
        assertNotNull(loader.getClusterMultinodeActive());

        try (JettraStorePoliceMonitor monitor = new JettraStorePoliceMonitor()) {
            assertNotNull(monitor.getClusterMultinodeActive());
            assertEquals(loader.isMultinodeActive(), monitor.isMultinodeActive());

            // Alternar dinámicamente a modo mononodo / standalone
            loader.setMultinodeActive(false);
            assertFalse(loader.isMultinodeActive());
            assertEquals("off", loader.getClusterMultinodeActive());
            assertFalse(monitor.isMultinodeActive());

            // Restaurar a multinodo
            loader.setMultinodeActive(true);
            assertTrue(loader.isMultinodeActive());
            assertEquals("on", loader.getClusterMultinodeActive());
            assertTrue(monitor.isMultinodeActive());
        }
    }
}

package io.jettra.fx;

import io.jettra.fx.profile.ConnectionProfile;
import io.jettra.fx.view3d.Cluster3DVisualizer;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import static io.jettra.test.core.JettraAssert.*;

public class ConnectionProfileTest {

    @Test
    @DisplayName("Debe gestionar perfiles de conexión de JettraStoreFX")
    public void testProfileManagement() {
        ConnectionProfile.ProfileManager manager = new ConnectionProfile.ProfileManager();
        assertNotNull(manager.getActiveProfile());
        assertEquals("admin", manager.getActiveProfile().getUsername());
        assertEquals("admin-jettra", manager.getActiveProfile().getPassword());

        ConnectionProfile staging = new ConnectionProfile("Staging Cluster", "10.0.0.5", 9091, "admin", "pass", "STAGING");
        manager.addProfile(staging);
        manager.setActiveProfile(staging);

        assertEquals("Staging Cluster", manager.getActiveProfile().getProfileName());
        assertEquals(2, manager.getProfiles().size());
    }

    @Test
    @DisplayName("Debe gestionar nodos y transición al anillo en el visualizador 3D")
    public void testCluster3DVisualizerLogic() {
        Cluster3DVisualizer visualizer = new Cluster3DVisualizer();
        assertEquals(3, visualizer.getNodeMeshes().size());
        assertTrue(visualizer.getNodeMeshes().containsKey("node-01"));

        // Simular transición al anillo
        visualizer.setRingTransitionActive(true);
        assertTrue(visualizer.isRingTransitionActive());

        visualizer.addNode("node-04", false, 0, 100, 0);
        assertEquals(4, visualizer.getNodeMeshes().size());

        visualizer.removeNode("node-04");
        assertEquals(3, visualizer.getNodeMeshes().size());
    }
}

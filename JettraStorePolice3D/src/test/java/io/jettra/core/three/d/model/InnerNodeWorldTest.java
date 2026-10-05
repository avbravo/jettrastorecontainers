package io.jettra.core.three.d.model;

import com.raylib.BoundingBox;
import io.jettra.store.cluster.ClusterNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InnerNodeWorldTest {

    @Test
    public void testServerNodeDatabasesInitialization() {
        ServerNode3D node = new ServerNode3D("node-01-master", "Servidor Maestro", "127.0.0.1", 9091,
                ClusterNode.Role.PRIMARY, 0, 0, 0);

        List<DatabaseInfo3D> dbs = node.getDatabases();
        assertNotNull(dbs);
        assertEquals(4, dbs.size(), "El nodo debe inicializar 4 bases de datos principales");

        // Validar example_factura_db
        DatabaseInfo3D facturaDb = dbs.stream()
                .filter(d -> d.getId().equals("example_factura_db"))
                .findFirst()
                .orElse(null);
        assertNotNull(facturaDb);
        assertEquals(3_750_000L, facturaDb.getTotalObjects());
        assertEquals("1.2 GB", facturaDb.getSizeFormatted());
        assertTrue(facturaDb.getBucketsCount() >= 8);
        assertTrue(facturaDb.getBuckets().contains("facturas (1M)"));
        assertTrue(facturaDb.getBuckets().contains("factura_embeddings (200k)"));

        // Validar samples_hostipal_db
        DatabaseInfo3D hospitalDb = dbs.stream()
                .filter(d -> d.getId().equals("samples_hostipal_db"))
                .findFirst()
                .orElse(null);
        assertNotNull(hospitalDb);
        assertEquals(2_000_000L, hospitalDb.getTotalObjects());

        // Validar samples_ambiental_db
        DatabaseInfo3D ambientalDb = dbs.stream()
                .filter(d -> d.getId().equals("samples_ambiental_db"))
                .findFirst()
                .orElse(null);
        assertNotNull(ambientalDb);
        assertEquals(3_000_000L, ambientalDb.getTotalObjects());
    }

    @Test
    public void testDatabaseBoundingBoxAndPulse() {
        ServerNode3D node = new ServerNode3D("node-02-replica", "Réplica Secundaria", "127.0.0.1", 9092,
                ClusterNode.Role.SECONDARY, 10, 0, 10);
        DatabaseInfo3D db = node.getDatabases().get(0);

        BoundingBox box = db.getBoundingBox();
        assertNotNull(box);
        assertTrue(box.min().x() < box.max().x());
        assertTrue(box.min().y() < box.max().y());
        assertTrue(box.min().z() < box.max().z());

        float initialPhase = db.getPulsePhase();
        db.setPulsePhase(initialPhase + 1.5f);
        assertEquals(initialPhase + 1.5f, db.getPulsePhase(), 0.001f);
    }

    @Test
    public void testOfflineStatePropagatesToDatabases() {
        ServerNode3D node = new ServerNode3D("node-03-replica", "Réplica 2", "127.0.0.1", 9093,
                ClusterNode.Role.SECONDARY, -10, 0, 10);
        assertTrue(node.isOnline());

        // Conmutar a fuera de servicio
        node.toggleOffline();
        assertFalse(node.isOnline());
        assertEquals(ClusterNode.NodeStatus.OFFLINE, node.getStatus());

        for (DatabaseInfo3D db : node.getDatabases()) {
            assertTrue(db.getStatus().contains("OFFLINE") || db.getStatus().contains("UNREACHABLE"),
                    "Las bases de datos deben reflejar el estado fuera de servicio del nodo");
        }
    }

    @Test
    public void testArtifactConnectionFacilities() {
        Artifact art = new Artifact("🏢 Centro Financiero", "example_factura_db", "Sede Comercial", 2,
                -22f, 0f, 15f, 255, 190, 30, "4.2 MB/s | 1,200 ops/s");
        assertEquals("🏢 Centro Financiero", art.name);
        assertEquals("example_factura_db", art.connectedDatabase);
        assertEquals(2, art.type);
        assertEquals("4.2 MB/s | 1,200 ops/s", art.networkRate);
    }

    @Test
    public void testJettraStoreEntityClassifications() {
        // 1. Usuario conectado
        HumanEntity user = new HumanEntity();
        user.name = "usr_facturacion_01";
        user.connectedDatabase = "example_factura_db";
        user.connectionFacility = "Centro Financiero";
        assertFalse(user.isCar);
        assertFalse(user.isWolf);
        assertEquals("example_factura_db", user.connectedDatabase);

        // 2. Perro Agente JettraPolice
        HumanEntity dog = new HumanEntity();
        dog.name = "JettraPolice-K9-Beta";
        dog.isWolf = true;
        dog.isPoliceK9 = true;
        assertTrue(dog.isWolf);
        assertTrue(dog.isPoliceK9);

        // 3. Camión de Tráfico de Datos
        HumanEntity truck = new HumanEntity();
        truck.name = "Tráfico-Facturas";
        truck.isCar = true;
        truck.dataPayload = "Lote: 25,000 Facturas JSON (3.8 MB/s)";
        assertTrue(truck.isCar);
        assertEquals("Lote: 25,000 Facturas JSON (3.8 MB/s)", truck.dataPayload);
    }

    @Test
    public void testVoiceNarratorToggle() {
        io.jettra.core.three.d.voice.JettraVoiceNarrator narrator = io.jettra.core.three.d.voice.JettraVoiceNarrator.getInstance();
        boolean initial = narrator.isEnabled();
        narrator.toggle();
        assertEquals(!initial, narrator.isEnabled());
        narrator.toggle();
        assertEquals(initial, narrator.isEnabled());
    }
}

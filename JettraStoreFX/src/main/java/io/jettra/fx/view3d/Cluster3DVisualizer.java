package io.jettra.fx.view3d;

import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PointLight;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visualizador 3D Avanzado del Clúster Raft y Topología JettraStore.
 * Incluye iluminación escénica, rejilla cybernetic cartesiana, anillos holográficos,
 * flujo animado de partículas de replicación y pedestales con elevación 3D.
 */
public final class Cluster3DVisualizer extends Group {
    private final Map<String, NodeVisual> nodeMeshes = new ConcurrentHashMap<>();
    private final Group ringOrbitGroup = new Group();
    private final Group groundGridGroup = new Group();
    private final Group holographicRingsGroup = new Group();
    private final Group dataParticlesGroup = new Group();
    private final List<Sphere> dataParticles = new ArrayList<>();
    private final List<Cylinder> holographicRings = new ArrayList<>();
    private boolean ringTransitionActive = false;
    private double animTime = 0.0;

    public record NodeVisual(Sphere sphere, Box base, double x, double y, double z) {}

    public Cluster3DVisualizer() {
        // 1. Iluminación Tridimensional Escénica
        AmbientLight ambient = new AmbientLight(Color.rgb(60, 75, 110));
        PointLight masterLight = new PointLight(Color.rgb(56, 189, 248));
        masterLight.setTranslateY(-120);
        masterLight.setTranslateZ(-80);

        PointLight secondaryLight = new PointLight(Color.rgb(250, 204, 21, 0.7));
        secondaryLight.setTranslateX(120);
        secondaryLight.setTranslateY(-80);
        secondaryLight.setTranslateZ(40);

        getChildren().addAll(ambient, masterLight, secondaryLight);

        // 2. Plataforma Cartesiana 3D Cybernetic Grid
        buildCybernetic3DGrid();
        getChildren().add(groundGridGroup);

        // 3. Nodos del cluster en el espacio 3D
        addNode("node-01", true, 0, 0, 0);       // Líder Primario Raft en el centro
        addNode("node-02", false, -150, 0, -50); // Secundario 1
        addNode("node-03", false, 150, 0, -50);  // Secundario 2

        // 4. Grupo de anillos de transición y enlaces
        getChildren().add(ringOrbitGroup);

        // 5. Anillos holográficos y partículas de replicación continua
        buildReplicationParticles();
        getChildren().addAll(holographicRingsGroup, dataParticlesGroup);
    }

    private void buildCybernetic3DGrid() {
        // Plataforma base profunda
        Box ground = new Box(520, 4, 440);
        PhongMaterial groundMat = new PhongMaterial(Color.rgb(10, 15, 28));
        groundMat.setSpecularColor(Color.rgb(30, 45, 70));
        ground.setMaterial(groundMat);
        ground.setTranslateY(45);
        groundGridGroup.getChildren().add(ground);

        // Bordes Neón Cian Perimetrales
        Box borderN = new Box(524, 3, 3);
        borderN.setMaterial(new PhongMaterial(Color.rgb(2, 132, 199)));
        borderN.setTranslateY(43);
        borderN.setTranslateZ(220);

        Box borderS = new Box(524, 3, 3);
        borderS.setMaterial(new PhongMaterial(Color.rgb(2, 132, 199)));
        borderS.setTranslateY(43);
        borderS.setTranslateZ(-220);

        Box borderE = new Box(3, 3, 440);
        borderE.setMaterial(new PhongMaterial(Color.rgb(2, 132, 199)));
        borderE.setTranslateY(43);
        borderE.setTranslateX(260);

        Box borderW = new Box(3, 3, 440);
        borderW.setMaterial(new PhongMaterial(Color.rgb(2, 132, 199)));
        borderW.setTranslateY(43);
        borderW.setTranslateX(-260);

        groundGridGroup.getChildren().addAll(borderN, borderS, borderE, borderW);

        // Retícula Cartesiana (Líneas X y Z)
        PhongMaterial gridMat = new PhongMaterial(Color.rgb(20, 35, 60));
        for (int x = -200; x <= 200; x += 50) {
            Box lineZ = new Box(1.5, 1, 420);
            lineZ.setMaterial(gridMat);
            lineZ.setTranslateX(x);
            lineZ.setTranslateY(43);
            groundGridGroup.getChildren().add(lineZ);
        }
        for (int z = -150; z <= 150; z += 50) {
            Box lineX = new Box(480, 1, 1.5);
            lineX.setMaterial(gridMat);
            lineX.setTranslateZ(z);
            lineX.setTranslateY(43);
            groundGridGroup.getChildren().add(lineX);
        }

        // Radar Anillos concéntricos en el suelo
        for (int r : new int[]{60, 120, 180}) {
            Cylinder radarRing = new Cylinder(r, 0.8);
            PhongMaterial rMat = new PhongMaterial(Color.rgb(56, 189, 248, 0.35));
            radarRing.setMaterial(rMat);
            radarRing.setTranslateY(43);
            groundGridGroup.getChildren().add(radarRing);
        }
    }

    public void addNode(String nodeId, boolean isPrimary, double x, double y, double z) {
        Sphere sphere = new Sphere(isPrimary ? 34 : 26);
        PhongMaterial mat = new PhongMaterial();
        mat.setDiffuseColor(isPrimary ? Color.rgb(2, 132, 199) : Color.rgb(16, 185, 129));
        mat.setSpecularColor(Color.WHITE);
        sphere.setMaterial(mat);
        sphere.setTranslateX(x);
        sphere.setTranslateY(y);
        sphere.setTranslateZ(z);

        // Pedestal base estilizado 3D
        Box base = new Box(isPrimary ? 52 : 42, 12, isPrimary ? 52 : 42);
        PhongMaterial baseMat = new PhongMaterial(Color.rgb(30, 41, 59));
        baseMat.setSpecularColor(Color.rgb(56, 189, 248));
        base.setMaterial(baseMat);
        base.setTranslateX(x);
        base.setTranslateY(y + 35);
        base.setTranslateZ(z);

        // Anillo de pedestal inferior
        Cylinder baseRing = new Cylinder(isPrimary ? 32 : 25, 2.0);
        baseRing.setMaterial(new PhongMaterial(isPrimary ? Color.CYAN : Color.AQUAMARINE));
        baseRing.setTranslateX(x);
        baseRing.setTranslateY(y + 41);
        baseRing.setTranslateZ(z);

        // Anillo holográfico orbital sobre el nodo
        Cylinder holoRing = new Cylinder(isPrimary ? 44 : 34, 1.8);
        holoRing.setMaterial(new PhongMaterial(isPrimary ? Color.rgb(250, 204, 21, 0.8) : Color.rgb(56, 189, 248, 0.7)));
        holoRing.setTranslateX(x);
        holoRing.setTranslateY(y - (isPrimary ? 42 : 34));
        holoRing.setTranslateZ(z);
        holoRing.getTransforms().add(new Rotate(25, Rotate.X_AXIS));
        holographicRings.add(holoRing);
        holographicRingsGroup.getChildren().add(holoRing);

        NodeVisual visual = new NodeVisual(sphere, base, x, y, z);
        nodeMeshes.put(nodeId, visual);

        getChildren().addAll(sphere, base, baseRing);
    }

    public void removeNode(String nodeId) {
        NodeVisual visual = nodeMeshes.remove(nodeId);
        if (visual != null) {
            getChildren().removeAll(visual.sphere(), visual.base());
        }
    }

    public void setRingTransitionActive(boolean active) {
        this.ringTransitionActive = active;
        ringOrbitGroup.getChildren().clear();

        if (active) {
            // Banda / enlace de saturación y overflow entre los nodos del cluster
            Cylinder link1 = createLink(0, 0, 0, -150, 0, -50, Color.rgb(245, 158, 11));
            Cylinder link2 = createLink(0, 0, 0, 150, 0, -50, Color.rgb(245, 158, 11));
            Cylinder link3 = createLink(-150, 0, -50, 150, 0, -50, Color.rgb(239, 68, 68));
            ringOrbitGroup.getChildren().addAll(link1, link2, link3);
        }
    }

    private Cylinder createLink(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        Cylinder cyl = new Cylinder(3.5, length);
        PhongMaterial mat = new PhongMaterial(color);
        mat.setSpecularColor(Color.WHITE);
        cyl.setMaterial(mat);
        cyl.setTranslateX((x1 + x2) / 2);
        cyl.setTranslateY((y1 + y2) / 2);
        cyl.setTranslateZ((z1 + z2) / 2);
        cyl.getTransforms().add(new Rotate(45, Rotate.Z_AXIS));
        return cyl;
    }

    private void buildReplicationParticles() {
        PhongMaterial pMat = new PhongMaterial(Color.rgb(0, 242, 254));
        pMat.setSpecularColor(Color.WHITE);
        for (int i = 0; i < 18; i++) {
            Sphere p = new Sphere(3.2);
            p.setMaterial(pMat);
            dataParticles.add(p);
            dataParticlesGroup.getChildren().add(p);
        }
    }

    public void tickAnimation(double dt) {
        animTime += dt;

        // Rotación lenta de los anillos holográficos
        for (int i = 0; i < holographicRings.size(); i++) {
            Cylinder ring = holographicRings.get(i);
            ring.setRotate((ring.getRotate() + (i % 2 == 0 ? 0.8 : -0.7)) % 360);
        }

        // Flujo continuo de partículas de replicación Raft entre Master (0,0,0) y Replicas (-150, 0, -50) y (150, 0, -50)
        for (int i = 0; i < dataParticles.size(); i++) {
            Sphere p = dataParticles.get(i);
            double progress = ((double) i / dataParticles.size() + animTime * 0.35) % 1.0;
            boolean toNode2 = (i % 2 == 0);
            double destX = toNode2 ? -150.0 : 150.0;
            double destZ = -50.0;

            // Interpolación lineal con elevación sinusoidal 3D
            p.setTranslateX(progress * destX);
            p.setTranslateY(-10.0 + Math.sin(progress * Math.PI) * -18.0);
            p.setTranslateZ(progress * destZ);
        }
    }

    public boolean isRingTransitionActive() { return ringTransitionActive; }
    public Map<String, NodeVisual> getNodeMeshes() { return nodeMeshes; }
}

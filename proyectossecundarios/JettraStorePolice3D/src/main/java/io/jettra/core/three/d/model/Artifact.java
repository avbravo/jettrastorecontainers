package io.jettra.core.three.d.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Representa los edificios y sedes donde se conectan los usuarios a JettraStore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Artifact {
    public float x, y, z;
    public float scale = 1.0f;
    public int r, g, b, a = 255;
    public int type; // 0: Sede/Casa, 1: Estación Ambiental, 2: Centro Financiero, 3: Data Center, 4: Hospital
    public String creator = "JettraStore";
    public String info = "";

    // Metadatos de conexión a JettraStore
    public String name = "Edificio de Conexión";
    public String connectedDatabase = "";
    public int connectedUsers = 0;
    public String facilityType = "Sede de Conexión";
    public String networkRate = "1.2 MB/s";

    public Artifact() {}

    public Artifact(String name, String connectedDatabase, String facilityType, int type,
                    float x, float y, float z, int r, int g, int b, String networkRate) {
        this.name = name;
        this.connectedDatabase = connectedDatabase;
        this.facilityType = facilityType;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
        this.networkRate = networkRate;
    }
}

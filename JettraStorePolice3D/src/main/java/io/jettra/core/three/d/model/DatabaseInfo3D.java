package io.jettra.core.three.d.model;

import com.raylib.BoundingBox;
import com.raylib.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa una base de datos alojada dentro del servidor (nodo)
 * visualizada en el mundo interior 3D.
 */
public class DatabaseInfo3D {
    private final String id;
    private final String title;
    private final String description;
    private final String engineType;
    private final long totalObjects;
    private final String sizeFormatted;
    private final int bucketsCount;
    private final List<String> buckets;
    private final int iops;
    private final String status;

    // Coordenadas 3D en el mundo interior del nodo
    private float x;
    private float y;
    private float z;
    private float radius = 1.4f;
    private float height = 3.0f;
    private int r, g, b;
    private float pulsePhase = 0f;

    public DatabaseInfo3D(String id, String title, String description, String engineType,
                          long totalObjects, String sizeFormatted, List<String> buckets,
                          int iops, String status, float x, float y, float z,
                          int r, int g, int b) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.engineType = engineType;
        this.totalObjects = totalObjects;
        this.sizeFormatted = sizeFormatted;
        this.buckets = (buckets != null) ? new ArrayList<>(buckets) : new ArrayList<>();
        this.bucketsCount = this.buckets.size();
        this.iops = iops;
        this.status = status;
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public BoundingBox getBoundingBox() {
        Vector3 min = new Vector3().x(x - radius).y(y).z(z - radius);
        Vector3 max = new Vector3().x(x + radius).y(y + height).z(z + radius);
        return new BoundingBox(min, max);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getEngineType() { return engineType; }
    public long getTotalObjects() { return totalObjects; }
    public String getSizeFormatted() { return sizeFormatted; }
    public int getBucketsCount() { return bucketsCount; }
    public List<String> getBuckets() { return buckets; }
    public int getIops() { return iops; }
    public String getStatus() { return status; }

    public float getX() { return x; }
    public void setX(float x) { this.x = x; }
    public float getY() { return y; }
    public void setY(float y) { this.y = y; }
    public float getZ() { return z; }
    public void setZ(float z) { this.z = z; }
    public float getRadius() { return radius; }
    public float getHeight() { return height; }

    public int getR() { return r; }
    public int getG() { return g; }
    public int getB() { return b; }

    public float getPulsePhase() { return pulsePhase; }
    public void setPulsePhase(float pulsePhase) { this.pulsePhase = pulsePhase; }
}

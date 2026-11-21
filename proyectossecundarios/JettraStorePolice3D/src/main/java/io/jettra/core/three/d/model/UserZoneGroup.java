package io.jettra.core.three.d.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa una zona o subred geográfica que agrupa a múltiples usuarios conectados
 * en un edificio común dentro del mundo 3D.
 */
public class UserZoneGroup {
    private final String zoneId;
    private final String zoneName;
    private final String subnetPrefix;
    private final String buildingName;
    private final String primaryDatabase;
    private final String buildingDescription;
    private final int buildingType; // 1: Escuela/IoT, 2: Financiero, 3: Centro de Datos, 4: Hospital
    private final float buildingX;
    private final float buildingY;
    private final float buildingZ;
    private final int colorR;
    private final int colorG;
    private final int colorB;
    private final List<String> sessionIds = new ArrayList<>();

    public UserZoneGroup(String zoneId, String zoneName, String subnetPrefix, String buildingName,
                         String primaryDatabase, String buildingDescription, int buildingType,
                         float buildingX, float buildingY, float buildingZ,
                         int colorR, int colorG, int colorB) {
        this.zoneId = zoneId;
        this.zoneName = zoneName;
        this.subnetPrefix = subnetPrefix;
        this.buildingName = buildingName;
        this.primaryDatabase = primaryDatabase;
        this.buildingDescription = buildingDescription;
        this.buildingType = buildingType;
        this.buildingX = buildingX;
        this.buildingY = buildingY;
        this.buildingZ = buildingZ;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
    }

    public synchronized void registerSession(String sessionId) {
        if (!sessionIds.contains(sessionId)) {
            sessionIds.add(sessionId);
        }
    }

    public synchronized void unregisterSession(String sessionId) {
        sessionIds.remove(sessionId);
    }

    public synchronized List<String> getSessionIds() {
        return Collections.unmodifiableList(new ArrayList<>(sessionIds));
    }

    public synchronized int getConnectedUserCount() {
        return sessionIds.size();
    }

    public boolean matchesIp(String ip) {
        if (ip == null || subnetPrefix == null) return false;
        return ip.startsWith(subnetPrefix);
    }

    public String getZoneId() { return zoneId; }
    public String getZoneName() { return zoneName; }
    public String getSubnetPrefix() { return subnetPrefix; }
    public String getBuildingName() { return buildingName; }
    public String getPrimaryDatabase() { return primaryDatabase; }
    public String getBuildingDescription() { return buildingDescription; }
    public int getBuildingType() { return buildingType; }
    public float getBuildingX() { return buildingX; }
    public float getBuildingY() { return buildingY; }
    public float getBuildingZ() { return buildingZ; }
    public int getColorR() { return colorR; }
    public int getColorG() { return colorG; }
    public int getColorB() { return colorB; }
}

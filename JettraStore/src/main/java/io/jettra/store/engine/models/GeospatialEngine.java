package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GeospatialEngine {
    private final String layerName;
    private final Map<String, GeoPoint> points = new ConcurrentHashMap<>();

    public record GeoPoint(String id, double latitude, double longitude) {}
    public record GeoDistanceResult(String id, double distanceKm) {}

    public GeospatialEngine(String layerName) {
        this.layerName = layerName;
    }

    public void insertPoint(String id, double lat, double lon) {
        points.put(id, new GeoPoint(id, lat, lon));
    }

    public List<GeoDistanceResult> findWithinRadius(double centerLat, double centerLon, double radiusKm) {
        List<GeoDistanceResult> results = new ArrayList<>();
        for (GeoPoint p : points.values()) {
            double dist = haversine(centerLat, centerLon, p.latitude(), p.longitude());
            if (dist <= radiusKm) {
                results.add(new GeoDistanceResult(p.id(), dist));
            }
        }
        results.sort(Comparator.comparingDouble(GeoDistanceResult::distanceKm));
        return results;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Radio de la Tierra en Km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public Map<String, GeoPoint> getAllPoints() {
        return Collections.unmodifiableMap(points);
    }

    public String getLayerName() { return layerName; }
    public int size() { return points.size(); }

    public void insertBatch(Map<String, GeoPoint> batch) {
        points.putAll(batch);
    }
}

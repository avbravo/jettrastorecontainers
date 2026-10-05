package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VectorEngine {
    private final String name;
    private final int dimensions;
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    public record VectorMatch(String id, float score) {}

    public VectorEngine(String name, int dimensions) {
        this.name = name;
        this.dimensions = dimensions;
    }

    public void index(String id, float[] embedding) {
        if (embedding.length != dimensions) {
            throw new IllegalArgumentException(String.format("Expected %d dimensions, but got %d", dimensions, embedding.length));
        }
        vectors.put(id, embedding);
    }

    public float[] getVector(String id) {
        return vectors.get(id);
    }

    public List<VectorMatch> searchCosine(float[] target, int limit) {
        List<VectorMatch> matches = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            float sim = io.jettra.store.calc.JettraVectorMath.cosineSimilarity(target, entry.getValue());
            matches.add(new VectorMatch(entry.getKey(), sim));
        }
        matches.sort((a, b) -> Float.compare(b.score(), a.score()));
        return matches.subList(0, Math.min(limit, matches.size()));
    }

    public List<VectorMatch> searchEuclidean(float[] target, int limit) {
        List<VectorMatch> matches = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            float dist = io.jettra.store.calc.JettraVectorMath.euclideanDistance(target, entry.getValue());
            matches.add(new VectorMatch(entry.getKey(), dist));
        }
        // Menor distancia = mayor proximidad
        matches.sort((a, b) -> Float.compare(a.score(), b.score()));
        return matches.subList(0, Math.min(limit, matches.size()));
    }

    public float dotProduct(String id1, String id2) {
        float[] v1 = vectors.get(id1);
        float[] v2 = vectors.get(id2);
        if (v1 == null || v2 == null) return 0f;
        return io.jettra.store.calc.JettraVectorMath.dotProduct(v1, v2);
    }

    public float euclideanDistance(String id1, String id2) {
        float[] v1 = vectors.get(id1);
        float[] v2 = vectors.get(id2);
        if (v1 == null || v2 == null) return -1f;
        return io.jettra.store.calc.JettraVectorMath.euclideanDistance(v1, v2);
    }

    public float cosineSimilarity(String id1, String id2) {
        float[] v1 = vectors.get(id1);
        float[] v2 = vectors.get(id2);
        if (v1 == null || v2 == null) return 0f;
        return io.jettra.store.calc.JettraVectorMath.cosineSimilarity(v1, v2);
    }

    public float norm(String id) {
        float[] v = vectors.get(id);
        if (v == null) return 0f;
        return io.jettra.store.calc.JettraVectorMath.norm(v);
    }

    public float[] centroid() {
        if (vectors.isEmpty()) return new float[dimensions];
        return io.jettra.store.calc.JettraVectorMath.centroid(new ArrayList<>(vectors.values()));
    }

    private float computeCosineSimilarity(float[] v1, float[] v2) {
        float dot = 0f;
        float normA = 0f;
        float normB = 0f;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0f || normB == 0f) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public Map<String, float[]> getAllVectors() { return Collections.unmodifiableMap(vectors); }
    public String getName() { return name; }
    public int getDimensions() { return dimensions; }
    public int size() { return vectors.size(); }

    public void indexBatch(Map<String, float[]> batch) {
        vectors.putAll(batch);
    }
}

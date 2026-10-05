package io.jettra.store.calc;

import java.util.*;

/**
 * Motor de Álgebra Vectorial y Búsqueda Multidimensional para JettraStore.
 */
public final class JettraVectorMath {

    private JettraVectorMath() {}

    public static float dotProduct(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        float dot = 0f;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        return dot;
    }

    public static double dotProduct(double[] v1, double[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        double dot = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        return dot;
    }

    public static float norm(float[] v) {
        if (v == null) return 0f;
        float sumSq = 0f;
        for (float val : v) sumSq += val * val;
        return (float) Math.sqrt(sumSq);
    }

    public static double norm(double[] v) {
        if (v == null) return 0.0;
        double sumSq = 0.0;
        for (double val : v) sumSq += val * val;
        return Math.sqrt(sumSq);
    }

    public static float cosineSimilarity(float[] v1, float[] v2) {
        float dot = dotProduct(v1, v2);
        float norm1 = norm(v1);
        float norm2 = norm(v2);
        if (norm1 == 0f || norm2 == 0f) return 0f;
        return dot / (norm1 * norm2);
    }

    public static double cosineSimilarity(double[] v1, double[] v2) {
        double dot = dotProduct(v1, v2);
        double norm1 = norm(v1);
        double norm2 = norm(v2);
        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        return dot / (norm1 * norm2);
    }

    public static float euclideanDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        float sum = 0f;
        for (int i = 0; i < v1.length; i++) {
            float d = v1[i] - v2[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    public static double euclideanDistance(double[] v1, double[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        double sum = 0.0;
        for (int i = 0; i < v1.length; i++) {
            double d = v1[i] - v2[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    public static float manhattanDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        float sum = 0f;
        for (int i = 0; i < v1.length; i++) {
            sum += Math.abs(v1[i] - v2[i]);
        }
        return sum;
    }

    public static float[] add(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        float[] res = new float[v1.length];
        for (int i = 0; i < v1.length; i++) res[i] = v1[i] + v2[i];
        return res;
    }

    public static float[] subtract(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        float[] res = new float[v1.length];
        for (int i = 0; i < v1.length; i++) res[i] = v1[i] - v2[i];
        return res;
    }

    public static float[] multiplyScalar(float[] v, float scalar) {
        if (v == null) return new float[0];
        float[] res = new float[v.length];
        for (int i = 0; i < v.length; i++) res[i] = v[i] * scalar;
        return res;
    }

    public static float[] normalize(float[] v) {
        float n = norm(v);
        if (n == 0f) return v.clone();
        float[] res = new float[v.length];
        for (int i = 0; i < v.length; i++) res[i] = v[i] / n;
        return res;
    }

    public static float[] centroid(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) return new float[0];
        int dim = vectors.get(0).length;
        float[] avg = new float[dim];
        for (float[] v : vectors) {
            if (v.length != dim) throw new IllegalArgumentException("Vectors in centroid must have same dimensions");
            for (int i = 0; i < dim; i++) avg[i] += v[i];
        }
        for (int i = 0; i < dim; i++) avg[i] /= vectors.size();
        return avg;
    }
    public static float chebyshevDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch or null vector");
        }
        float maxDiff = 0f;
        for (int i = 0; i < v1.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(v1[i] - v2[i]));
        }
        return maxDiff;
    }

    public static double minkowskiDistance(float[] v1, float[] v2, double p) {
        if (v1 == null || v2 == null || v1.length != v2.length || p <= 0) {
            throw new IllegalArgumentException("Vector dimension mismatch or invalid p");
        }
        double sum = 0.0;
        for (int i = 0; i < v1.length; i++) {
            sum += Math.pow(Math.abs(v1[i] - v2[i]), p);
        }
        return Math.pow(sum, 1.0 / p);
    }

    public static float l1Norm(float[] v) {
        if (v == null) return 0f;
        float sum = 0f;
        for (float val : v) sum += Math.abs(val);
        return sum;
    }

    public static double angle(float[] v1, float[] v2) {
        float cos = cosineSimilarity(v1, v2);
        cos = Math.max(-1.0f, Math.min(1.0f, cos));
        return Math.acos(cos);
    }

    public static double angleDegrees(float[] v1, float[] v2) {
        return Math.toDegrees(angle(v1, v2));
    }

    public static float[] crossProduct(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != 3 || v2.length != 3) {
            throw new IllegalArgumentException("Cross product requires 3-dimensional vectors");
        }
        return new float[] {
            v1[1] * v2[2] - v1[2] * v2[1],
            v1[2] * v2[0] - v1[0] * v2[2],
            v1[0] * v2[1] - v1[1] * v2[0]
        };
    }

    public static float[] projection(float[] v, float[] onto) {
        if (v == null || onto == null || v.length != onto.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        float dot = dotProduct(v, onto);
        float normOntoSq = 0f;
        for (float val : onto) normOntoSq += val * val;
        if (normOntoSq == 0f) return new float[v.length];
        float scalar = dot / normOntoSq;
        return multiplyScalar(onto, scalar);
    }

    public static float[] rejection(float[] v, float[] from) {
        float[] proj = projection(v, from);
        return subtract(v, proj);
    }

    public static float[] multiply(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        float[] res = new float[v1.length];
        for (int i = 0; i < v1.length; i++) res[i] = v1[i] * v2[i];
        return res;
    }

    public static float[] divide(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        float[] res = new float[v1.length];
        for (int i = 0; i < v1.length; i++) {
            res[i] = v2[i] != 0f ? (v1[i] / v2[i]) : 0f;
        }
        return res;
    }
}

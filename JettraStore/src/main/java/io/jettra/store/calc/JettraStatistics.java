package io.jettra.store.calc;

import java.util.*;

/**
 * Motor Estadístico Centralizado para JettraStore.
 */
public final class JettraStatistics {

    public record StatsSummary(
        long count,
        double sum,
        double mean,
        double median,
        double mode,
        double variance,
        double stddev,
        double min,
        double max,
        double p90,
        double p95,
        double p99
    ) {}

    public record RegressionResult(double slope, double intercept, double rSquared) {}

    private JettraStatistics() {}

    public static double mean(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (Number n : data) {
            if (n != null) {
                sum += n.doubleValue();
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    public static double sum(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double sum = 0.0;
        for (Number n : data) {
            if (n != null) sum += n.doubleValue();
        }
        return sum;
    }

    public static double min(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double min = Double.MAX_VALUE;
        for (Number n : data) {
            if (n != null) min = Math.min(min, n.doubleValue());
        }
        return min;
    }

    public static double max(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double max = -Double.MAX_VALUE;
        for (Number n : data) {
            if (n != null) max = Math.max(max, n.doubleValue());
        }
        return max;
    }

    public static double median(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        List<Double> sorted = toSortedDoubleList(data);
        int n = sorted.size();
        if (n == 0) return 0.0;
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        } else {
            return (sorted.get((n / 2) - 1) + sorted.get(n / 2)) / 2.0;
        }
    }

    public static double mode(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        Map<Double, Integer> freq = new HashMap<>();
        for (Number n : data) {
            if (n != null) {
                double val = n.doubleValue();
                freq.put(val, freq.getOrDefault(val, 0) + 1);
            }
        }
        double best = 0.0;
        int maxCount = -1;
        for (Map.Entry<Double, Integer> e : freq.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public static double variance(List<? extends Number> data, boolean sample) {
        if (data == null || data.size() < (sample ? 2 : 1)) return 0.0;
        double avg = mean(data);
        double sumSq = 0.0;
        int count = 0;
        for (Number n : data) {
            if (n != null) {
                double d = n.doubleValue() - avg;
                sumSq += d * d;
                count++;
            }
        }
        int denom = sample ? (count - 1) : count;
        return denom > 0 ? sumSq / denom : 0.0;
    }

    public static double stddev(List<? extends Number> data, boolean sample) {
        return Math.sqrt(variance(data, sample));
    }

    public static double percentile(List<? extends Number> data, double p) {
        if (data == null || data.isEmpty()) return 0.0;
        List<Double> sorted = toSortedDoubleList(data);
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double rank = (p / 100.0) * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        double weight = rank - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    public static double covariance(List<? extends Number> x, List<? extends Number> y, boolean sample) {
        if (x == null || y == null || x.size() != y.size() || x.size() < (sample ? 2 : 1)) return 0.0;
        double meanX = mean(x);
        double meanY = mean(y);
        double sum = 0.0;
        int n = x.size();
        for (int i = 0; i < n; i++) {
            sum += (x.get(i).doubleValue() - meanX) * (y.get(i).doubleValue() - meanY);
        }
        int denom = sample ? (n - 1) : n;
        return sum / denom;
    }

    public static double correlation(List<? extends Number> x, List<? extends Number> y) {
        double cov = covariance(x, y, true);
        double sX = stddev(x, true);
        double sY = stddev(y, true);
        if (sX == 0.0 || sY == 0.0) return 0.0;
        return cov / (sX * sY);
    }

    public static double zscore(double value, double mean, double stddev) {
        if (stddev == 0.0) return 0.0;
        return (value - mean) / stddev;
    }

    public static RegressionResult linearRegression(List<? extends Number> x, List<? extends Number> y) {
        if (x == null || y == null || x.size() != y.size() || x.size() < 2) {
            return new RegressionResult(0.0, 0.0, 0.0);
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double num = 0.0;
        double den = 0.0;
        int n = x.size();
        for (int i = 0; i < n; i++) {
            double dx = x.get(i).doubleValue() - meanX;
            double dy = y.get(i).doubleValue() - meanY;
            num += dx * dy;
            den += dx * dx;
        }
        double slope = den != 0.0 ? num / den : 0.0;
        double intercept = meanY - (slope * meanX);
        double r = correlation(x, y);
        return new RegressionResult(slope, intercept, r * r);
    }

    public static StatsSummary summary(List<? extends Number> data) {
        if (data == null || data.isEmpty()) {
            return new StatsSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long count = data.size();
        double sum = sum(data);
        double avg = sum / count;
        double med = median(data);
        double mod = mode(data);
        double var = variance(data, true);
        double sd = Math.sqrt(var);
        double mn = min(data);
        double mx = max(data);
        double p90 = percentile(data, 90.0);
        double p95 = percentile(data, 95.0);
        double p99 = percentile(data, 99.0);

        return new StatsSummary(count, sum, avg, med, mod, var, sd, mn, mx, p90, p95, p99);
    }

    public static double skewness(List<? extends Number> data) {
        if (data == null || data.size() < 3) return 0.0;
        int n = data.size();
        double m = mean(data);
        double s = stddev(data, true);
        if (s == 0.0) return 0.0;
        double sumCube = 0.0;
        for (Number num : data) {
            if (num != null) {
                double diff = (num.doubleValue() - m) / s;
                sumCube += diff * diff * diff;
            }
        }
        return (n / ((double)(n - 1) * (n - 2))) * sumCube;
    }

    public static double kurtosis(List<? extends Number> data) {
        if (data == null || data.size() < 4) return 0.0;
        int n = data.size();
        double m = mean(data);
        double s = stddev(data, true);
        if (s == 0.0) return 0.0;
        double sumQuad = 0.0;
        for (Number num : data) {
            if (num != null) {
                double diff = (num.doubleValue() - m) / s;
                sumQuad += diff * diff * diff * diff;
            }
        }
        double factor1 = (double) n * (n + 1) / ((n - 1.0) * (n - 2.0) * (n - 3.0));
        double factor2 = 3.0 * (n - 1.0) * (n - 1.0) / ((n - 2.0) * (n - 3.0));
        return factor1 * sumQuad - factor2;
    }

    public static double iqr(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        return percentile(data, 75.0) - percentile(data, 25.0);
    }

    public static double standardError(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        return stddev(data, true) / Math.sqrt(data.size());
    }

    public static double weightedMean(List<? extends Number> data, List<? extends Number> weights) {
        if (data == null || weights == null || data.size() != weights.size() || data.isEmpty()) return 0.0;
        double sumProd = 0.0;
        double sumWeights = 0.0;
        for (int i = 0; i < data.size(); i++) {
            Number d = data.get(i);
            Number w = weights.get(i);
            if (d != null && w != null) {
                sumProd += d.doubleValue() * w.doubleValue();
                sumWeights += w.doubleValue();
            }
        }
        return sumWeights != 0.0 ? sumProd / sumWeights : 0.0;
    }

    public static double geometricMean(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double logSum = 0.0;
        int count = 0;
        for (Number n : data) {
            if (n != null && n.doubleValue() > 0) {
                logSum += Math.log(n.doubleValue());
                count++;
            }
        }
        return count > 0 ? Math.exp(logSum / count) : 0.0;
    }

    public static double harmonicMean(List<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double invSum = 0.0;
        int count = 0;
        for (Number n : data) {
            if (n != null && n.doubleValue() != 0.0) {
                invSum += 1.0 / n.doubleValue();
                count++;
            }
        }
        return (count > 0 && invSum != 0.0) ? count / invSum : 0.0;
    }

    private static List<Double> toSortedDoubleList(List<? extends Number> data) {
        List<Double> list = new ArrayList<>(data.size());
        for (Number n : data) {
            if (n != null) list.add(n.doubleValue());
        }
        Collections.sort(list);
        return list;
    }
}

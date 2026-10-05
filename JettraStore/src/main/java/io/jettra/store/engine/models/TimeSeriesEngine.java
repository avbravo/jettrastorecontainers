package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public final class TimeSeriesEngine {
    private final String metricName;
    private final ConcurrentSkipListMap<Long, Double> series = new ConcurrentSkipListMap<>();

    public TimeSeriesEngine(String metricName) {
        this.metricName = metricName;
    }

    public void record(long timestamp, double value) {
        series.put(timestamp, value);
    }

    public NavigableMap<Long, Double> range(long fromTimestamp, long toTimestamp) {
        return series.subMap(fromTimestamp, true, toTimestamp, true);
    }

    public double average(long fromTimestamp, long toTimestamp) {
        NavigableMap<Long, Double> sub = range(fromTimestamp, toTimestamp);
        if (sub.isEmpty()) return 0.0;
        double sum = 0;
        for (double v : sub.values()) sum += v;
        return sum / sub.size();
    }

    public NavigableMap<Long, Double> getAll() {
        return Collections.unmodifiableNavigableMap(series);
    }

    public String getMetricName() { return metricName; }
    public int size() { return series.size(); }

    public void recordBatch(Map<Long, Double> batch) {
        series.putAll(batch);
    }
}

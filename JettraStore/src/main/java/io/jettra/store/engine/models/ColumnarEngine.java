package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ColumnarEngine {
    private final String tableName;
    private final Map<String, List<Double>> numericColumns = new ConcurrentHashMap<>();
    private final Map<String, List<String>> textColumns = new ConcurrentHashMap<>();
    private int rowCount = 0;

    public ColumnarEngine(String tableName) {
        this.tableName = tableName;
    }

    public synchronized void appendRow(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof Number num) {
                numericColumns.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>())
                              .add(num.doubleValue());
            } else {
                textColumns.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>())
                           .add(String.valueOf(entry.getValue()));
            }
        }
        rowCount++;
    }

    public List<Double> getNumericColumn(String colName) {
        return numericColumns.getOrDefault(colName, Collections.emptyList());
    }

    public double sumColumn(String colName) {
        List<Double> col = getNumericColumn(colName);
        double s = 0.0;
        for (double v : col) s += v;
        return s;
    }

    public Map<String, List<Double>> getNumericColumns() {
        return Collections.unmodifiableMap(numericColumns);
    }

    public Map<String, List<String>> getTextColumns() {
        return Collections.unmodifiableMap(textColumns);
    }

    public String getTableName() { return tableName; }
    public int getRowCount() { return rowCount; }
    public int size() { return rowCount; }

    public synchronized void appendBatch(Map<String, List<Double>> numCols, Map<String, List<String>> txtCols, int count) {
        for (var entry : numCols.entrySet()) {
            numericColumns.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>()).addAll(entry.getValue());
        }
        for (var entry : txtCols.entrySet()) {
            textColumns.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>()).addAll(entry.getValue());
        }
        this.rowCount += count;
    }

    public synchronized void clear() {
        numericColumns.clear();
        textColumns.clear();
        rowCount = 0;
    }
}

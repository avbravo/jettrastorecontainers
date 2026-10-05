package io.jettra.store.calc;

import io.jettra.store.engine.models.DocumentEngine;
import java.util.*;

/**
 * Motor de Agregaciones y Agrupamiento (GROUP BY, SUM, AVG, COUNT, MIN, MAX, STDDEV) para JettraStore.
 */
public final class JettraAggregation {

    public record AggregateSpec(String function, String field, String alias) {
        public String outputName() {
            if (alias != null && !alias.isBlank()) return alias;
            return function.toUpperCase() + "(" + (field != null ? field : "*") + ")";
        }
    }

    public record AggregationResult(
        List<String> groupByFields,
        List<AggregateSpec> aggregateSpecs,
        List<String> columnHeaders,
        List<Map<String, Object>> rows,
        int totalGroups
    ) {}

    private JettraAggregation() {}

    public static AggregationResult aggregate(DocumentEngine engine, List<String> groupByFields, List<AggregateSpec> specs) {
        return aggregate(engine, groupByFields, specs, null);
    }

    public static AggregationResult aggregate(
            DocumentEngine engine, 
            List<String> groupByFields, 
            List<AggregateSpec> specs,
            Map<String, Object> whereFilter) {

        if (engine == null || engine.isEmpty()) {
            return new AggregationResult(groupByFields, specs, buildHeaders(groupByFields, specs), Collections.emptyList(), 0);
        }

        List<String> groupCols = groupByFields != null ? groupByFields : Collections.emptyList();
        List<AggregateSpec> aggSpecs = specs != null ? specs : Collections.emptyList();

        // 1. Agrupamiento en Hash Buckets
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();

        for (Map<String, Object> doc : engine) {
            if (doc == null) continue;
            // Filtro WHERE básico
            if (whereFilter != null && !whereFilter.isEmpty()) {
                boolean matches = true;
                for (var filterEntry : whereFilter.entrySet()) {
                    Object val = doc.get(filterEntry.getKey());
                    if (val == null || !String.valueOf(val).equalsIgnoreCase(String.valueOf(filterEntry.getValue()))) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) continue;
            }

            // Clave de grupo
            String groupKey;
            if (groupCols.isEmpty()) {
                groupKey = "__ALL__";
            } else {
                StringBuilder sb = new StringBuilder();
                for (String col : groupCols) {
                    sb.append(String.valueOf(doc.get(col))).append("###");
                }
                groupKey = sb.toString();
            }

            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(doc);
        }

        // 2. Computar agregaciones para cada grupo
        List<Map<String, Object>> resultRows = new ArrayList<>(groups.size());
        List<String> headers = buildHeaders(groupCols, aggSpecs);

        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> bucket = entry.getValue();
            Map<String, Object> first = bucket.get(0);
            Map<String, Object> row = new LinkedHashMap<>();

            // Asignar campos agrupados
            for (String col : groupCols) {
                row.put(col, first.get(col));
            }

            // Asignar funciones de agregación
            for (AggregateSpec spec : aggSpecs) {
                String fn = spec.function().toUpperCase();
                String field = spec.field();
                String outName = spec.outputName();

                Object aggValue = computeAggregate(fn, field, bucket);
                row.put(outName, aggValue);
            }

            resultRows.add(row);
        }

        return new AggregationResult(groupCols, aggSpecs, headers, resultRows, resultRows.size());
    }

    private static Object computeAggregate(String function, String field, List<Map<String, Object>> bucket) {
        if (function.equals("COUNT")) {
            if (field == null || field.equals("*")) {
                return (long) bucket.size();
            } else {
                long c = 0;
                for (var doc : bucket) {
                    if (doc.containsKey(field) && doc.get(field) != null) c++;
                }
                return c;
            }
        }

        List<Double> values = new ArrayList<>(bucket.size());
        for (var doc : bucket) {
            Object val = doc.get(field);
            if (val instanceof Number num) {
                values.add(num.doubleValue());
            } else if (val != null) {
                try {
                    values.add(Double.parseDouble(val.toString()));
                } catch (Exception ignored) {}
            }
        }

        if (values.isEmpty()) return 0.0;

        return switch (function) {
            case "SUM" -> JettraMath.round(JettraStatistics.sum(values), 4);
            case "AVG", "AVERAGE", "MEAN" -> JettraMath.round(JettraStatistics.mean(values), 4);
            case "MIN" -> JettraStatistics.min(values);
            case "MAX" -> JettraStatistics.max(values);
            case "MEDIAN", "MED" -> JettraMath.round(JettraStatistics.median(values), 4);
            case "MODE" -> JettraMath.round(JettraStatistics.mode(values), 4);
            case "RANGE" -> JettraMath.round(JettraStatistics.max(values) - JettraStatistics.min(values), 4);
            case "IQR" -> JettraMath.round(JettraStatistics.iqr(values), 4);
            case "STDDEV", "STD" -> JettraMath.round(JettraStatistics.stddev(values, true), 4);
            case "VARIANCE", "VAR" -> JettraMath.round(JettraStatistics.variance(values, true), 4);
            case "SKEWNESS", "SKEW" -> JettraMath.round(JettraStatistics.skewness(values), 4);
            case "KURTOSIS", "KURT" -> JettraMath.round(JettraStatistics.kurtosis(values), 4);
            case "P50" -> JettraMath.round(JettraStatistics.percentile(values, 50.0), 4);
            case "P90" -> JettraMath.round(JettraStatistics.percentile(values, 90.0), 4);
            case "P95" -> JettraMath.round(JettraStatistics.percentile(values, 95.0), 4);
            case "P99" -> JettraMath.round(JettraStatistics.percentile(values, 99.0), 4);
            case "FIRST" -> values.isEmpty() ? 0.0 : values.get(0);
            case "LAST" -> values.isEmpty() ? 0.0 : values.get(values.size() - 1);
            default -> {
                if (function.startsWith("P") && function.length() > 1) {
                    try {
                        double p = Double.parseDouble(function.substring(1));
                        yield JettraMath.round(JettraStatistics.percentile(values, p), 4);
                    } catch (Exception ignored) {}
                }
                yield 0.0;
            }
        };
    }

    private static List<String> buildHeaders(List<String> groupByFields, List<AggregateSpec> specs) {
        List<String> headers = new ArrayList<>();
        if (groupByFields != null) headers.addAll(groupByFields);
        if (specs != null) {
            for (AggregateSpec s : specs) headers.add(s.outputName());
        }
        return headers;
    }
}

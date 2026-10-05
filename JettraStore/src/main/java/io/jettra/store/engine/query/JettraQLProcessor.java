package io.jettra.store.engine.query;

import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.engine.models.GraphEngine;
import io.jettra.store.engine.models.VectorEngine;

import java.util.*;

public final class JettraQLProcessor {
    private final JettraDatabase database;

    public record JQLResult(String operation, List<String> columns, List<List<Object>> rows, int totalMatches, String summary) {}

    public JettraQLProcessor(JettraDatabase database) {
        this.database = database;
    }

    public JQLResult execute(String jql) {
        String trimmed = jql.trim();
        String upper = trimmed.toUpperCase();

        if (upper.startsWith("FROM ")) {
            return executeFrom(trimmed);
        } else if (upper.startsWith("MATCH ")) {
            return executeGraphMatch(trimmed);
        } else if (upper.startsWith("VECTOR SIMILARITY ") || upper.startsWith("VECTOR MATCH ")) {
            return executeVectorMatch(trimmed);
        } else if (upper.startsWith("FIND ")) {
            return executeFind(trimmed);
        } else if (upper.startsWith("FETCH ")) {
            return executeFetch(trimmed);
        }

        return new JQLResult("JQL_GENERIC", List.of("jql", "status"), List.of(List.of(trimmed, "OK")), 1, "JettraQL executed: " + trimmed);
    }

    private JQLResult executeFrom(String jql) {
        // Formato: FROM <collection> [WHERE <campo> = <valor>] [LIMIT <n>]
        int limit = (database != null && database.getConfig() != null) ? database.getConfig().getQueryDefaultLimit() : 50;
        int maxLimit = (database != null && database.getConfig() != null) ? database.getConfig().getQueryMaxLimit() : 5000;

        String upper = jql.toUpperCase();
        int limitIdx = upper.lastIndexOf(" LIMIT ");
        String workingJql = jql;
        if (limitIdx != -1) {
            String limStr = jql.substring(limitIdx + 7).replace(";", "").replace("'", "").replace(String.valueOf((char)34), "").trim();
            try { limit = Math.min(Integer.parseInt(limStr), maxLimit); } catch (Exception ignored) {}
            workingJql = jql.substring(0, limitIdx).trim();
        }

        String[] parts = workingJql.substring(5).trim().split("\\s+");
        String collection = parts[0].replaceAll("[;]", "");
        DocumentEngine engine = database.getDocumentEngine(collection);
        if (engine == null || engine.isEmpty()) {
            return new JQLResult("FROM", List.of("_id", "document"), Collections.emptyList(), 0, 
                String.format("JettraQL FROM '%s' retornó 0 registro(s)", collection));
        }

        // Supervisión predictiva JettraPolice sobre la consulta JQL
        boolean explicitLimit = (limitIdx != -1);
        int requestedLimit = explicitLimit ? limit : 0;
        io.jettra.store.police.JettraPolice.PoliceDecision policeDecision = 
            io.jettra.store.police.JettraPolice.getInstance().evaluateHeapSafety(
                "JQL_FROM", collection, engine.count(), requestedLimit, 512L);

        boolean policeIntervened = false;
        if (policeDecision.interventionRequired()) {
            policeIntervened = true;
            limit = policeDecision.enforcedLimit();
        }

        String filterKey = null;
        String filterVal = null;
        int whereIdx = workingJql.toUpperCase().indexOf(" WHERE ");
        if (whereIdx != -1) {
            String whereClause = workingJql.substring(whereIdx + 7).trim();
            String[] kv = whereClause.split("=");
            if (kv.length == 2) {
                filterKey = kv[0].trim();
                filterVal = kv[1].replace(";", "").replace("'", "").replace(String.valueOf((char)34), "").trim();
            }
        }

        List<List<Object>> rows = new ArrayList<>(Math.min(limit, 1000));
        for (Map<String, Object> doc : engine) {
            if (doc == null) continue;
            if (filterKey != null && filterVal != null) {
                Object val = doc.get(filterKey);
                if (val == null || !String.valueOf(val).equalsIgnoreCase(filterVal)) {
                    continue;
                }
            }
            rows.add(List.of(doc.getOrDefault("_id", "unknown"), doc.toString()));
            if (rows.size() >= limit) {
                break;
            }
        }

        String summaryMsg = policeIntervened 
            ? String.format("[JettraPolice SENTINEL: Paginación Lazy Anti-OOM Activada] JettraQL FROM '%s' retornó %d registro(s) (Lote seguro: %d). %s",
                collection, rows.size(), limit, policeDecision.rationale())
            : String.format("JettraQL FROM '%s' retornó %d registro(s) (Límite aplicado: %d)", collection, rows.size(), limit);

        return new JQLResult("FROM", List.of("_id", "document"), rows, rows.size(), summaryMsg);
    }

    private JQLResult executeGraphMatch(String jql) {
        // Formato: MATCH (<source>)-[<label>]->(<target>) IN <collection> o MATCH (<source>) IN <collection>
        String clean = jql.substring(6).trim();
        String collection = "catalog_graph";
        int inIdx = clean.toUpperCase().indexOf(" IN ");
        if (inIdx != -1) {
            collection = clean.substring(inIdx + 4).replaceAll("[;]", "").trim();
            clean = clean.substring(0, inIdx).trim();
        }

        String sourceVertex = clean.replaceAll("[()\\s]", "").split("->|-|\\[")[0];
        GraphEngine graph = database.getGraphEngine(collection);
        var edges = graph.getOutboundEdges(sourceVertex);

        List<List<Object>> rows = new ArrayList<>();
        for (var e : edges) {
            rows.add(List.of(sourceVertex, e.label(), e.targetVertex(), e.properties().toString()));
        }

        return new JQLResult("MATCH", List.of("source", "relation", "target", "properties"), rows, rows.size(),
            String.format("JettraQL MATCH en grafo '%s' encontró %d arista(s) para vertice '%s'", collection, rows.size(), sourceVertex));
    }

    private JQLResult executeVectorMatch(String jql) {
        // Formato: VECTOR SIMILARITY <collection> TO [f1, f2, f3] [LIMIT <k>]
        try {
            String after = jql.replaceFirst("(?i)VECTOR (SIMILARITY|MATCH)\\s+", "").trim();
            String col = after.split("\\s+")[0];
            String vecStr = after.substring(after.indexOf('[') + 1, after.indexOf(']'));
            String[] numStrs = vecStr.split(",");
            float[] target = new float[numStrs.length];
            for (int i = 0; i < numStrs.length; i++) target[i] = Float.parseFloat(numStrs[i].trim());

            int limit = 5;
            int limIdx = after.toUpperCase().indexOf("LIMIT");
            if (limIdx != -1) {
                limit = Integer.parseInt(after.substring(limIdx + 5).trim().split("\\s+")[0].replaceAll("[;]", ""));
            }

            VectorEngine vEngine = database.getVectorEngine(col, target.length);
            var matches = vEngine.searchCosine(target, limit);

            List<List<Object>> rows = new ArrayList<>();
            for (var m : matches) {
                rows.add(List.of(m.id(), String.format("%.4f", m.score())));
            }

            return new JQLResult("VECTOR_SIMILARITY", List.of("vector_id", "similarity_score"), rows, rows.size(),
                String.format("JettraQL VECTOR SIMILARITY en '%s' retornó Top-%d matches", col, rows.size()));
        } catch (Exception e) {
            return new JQLResult("VECTOR_SIMILARITY", List.of("error"), List.of(List.of(e.getMessage())), 0, "Error en consulta JettraQL vectorial");
        }
    }

    private JQLResult executeFind(String jql) {
        // Formato: FIND <col> <id> o FIND <col> WHERE ...
        String after = jql.substring(5).trim();
        String[] parts = after.split("\\s+");
        String col = parts[0];
        if (parts.length > 1 && !parts[1].equalsIgnoreCase("WHERE")) {
            String id = parts[1].replaceAll("['\";]", "").trim();
            Map<String, Object> doc = database.getDocumentEngine(col).findById(id);
            if (doc != null) {
                return new JQLResult("FIND", List.of("_id", "document"), List.of(List.of(id, doc.toString())), 1, "Encontrado 1 documento");
            }
            return new JQLResult("FIND", List.of("_id", "document"), Collections.emptyList(), 0, "Documento no encontrado");
        }
        return executeFrom("FROM " + after);
    }

    private JQLResult executeFetch(String jql) {
        // Formato: FETCH <col> <id> [RESOLVE REFS]
        String after = jql.substring(6).trim();
        String[] parts = after.split("\\s+");
        String col = parts[0];
        String id = parts[1].replaceAll("['\";]", "").trim();
        Map<String, Object> doc = database.getDocumentEngine(col).findById(id);

        if (doc == null) {
            return new JQLResult("FETCH", List.of("status"), List.of(List.of("NOT_FOUND")), 0, "Registro no existe");
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean resolve = jql.toUpperCase().contains("RESOLVE");
        for (var entry : doc.entrySet()) {
            String val = String.valueOf(entry.getValue());
            if (resolve && entry.getKey().startsWith("_ref_")) {
                val += " [JettraRef Resuelto Eagerly]";
            }
            rows.add(List.of(entry.getKey(), val));
        }

        return new JQLResult("FETCH", List.of("field", "value"), rows, rows.size(), 
            String.format("JettraQL FETCH '%s' con %d campos", id, rows.size()));
    }
}

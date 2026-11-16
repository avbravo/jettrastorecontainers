package io.jettra.store.explorer.pages.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jettra.store.explorer.model.EngineRecordInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renderizador y constructor de formularios adaptado al formato de almacenamiento
 * de cada motor (engine) de JettraStore: DOCUMENT, OBJECT, JAVA_RECORD/RECORD,
 * GRAPH, KEY_VALUE, GEOSPATIAL, VECTOR, TIMESERIES, COLUMNAR y RELATIONAL.
 */
public final class EngineRecordRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EngineRecordRenderer() {}

    public static String normalizeEngine(String engineType) {
        if (engineType == null) return "DOCUMENT";
        String upper = engineType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("RECORD".equals(upper)) return "JAVA_RECORD";
        return upper;
    }

    public static Map<String, Object> parseDetailsMap(String details) {
        if (details == null || details.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(details, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("rawValue", details);
            return fallback;
        }
    }

    // =========================================================================
    // VISUALIZADOR POR TIPO DE MOTOR (VER)
    // =========================================================================

    public static String renderVersionViewHtml(EngineRecordInfo parentRec, io.jettra.store.explorer.model.EngineRecordVersion version) {
        EngineRecordInfo snapshot = new EngineRecordInfo(
                parentRec.getId(),
                parentRec.getEngineType(),
                parentRec.getBucketName(),
                version.getSummary(),
                version.getDetails(),
                version.getTimestamp(),
                version.getVersion()
        );
        return renderViewHtml(snapshot);
    }

    public static String renderViewHtml(EngineRecordInfo rec) {
        String engine = normalizeEngine(rec.getEngineType());
        Map<String, Object> data = parseDetailsMap(rec.getDetails());

        return switch (engine) {
            case "OBJECT" -> renderObjectView(rec, data);
            case "JAVA_RECORD" -> renderJavaRecordView(rec, data);
            case "GRAPH" -> renderGraphView(rec, data);
            case "KEY_VALUE" -> renderKeyValueView(rec, data);
            case "GEOSPATIAL" -> renderGeospatialView(rec, data);
            case "VECTOR" -> renderVectorView(rec, data);
            case "TIMESERIES" -> renderTimeseriesView(rec, data);
            case "COLUMNAR", "RELATIONAL" -> renderTabularView(rec, engine, data);
            default -> renderDocumentView(rec, data);
        };
    }

    private static String renderDocumentView(EngineRecordInfo rec, Map<String, Object> data) {
        return String.format("""
            <div data-engine-viewer='DOCUMENT' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#00d4ff; margin-bottom:0.6rem;'>📄 Visualizador de Documento BSON / JSON</div>
              %s
              <details style='margin-top:0.85rem;'><summary style='cursor:pointer; font-size:0.8rem; color:var(--text-muted); font-weight:600;'>Ver documento JSON completo</summary>
                <pre style='background:#020617; border:1px solid var(--border-subtle); border-radius:8px; padding:1rem; margin-top:0.5rem; font-family:monospace; font-size:0.84rem; color:#38bdf8; overflow-x:auto;'>%s</pre>
              </details>
            </div>
            """, renderKeyValueGrid(data, "#00d4ff"), escapeHtml(rec.getDetails()));
    }

    @SuppressWarnings("unchecked")
    private static String renderObjectView(EngineRecordInfo rec, Map<String, Object> data) {
        String className = strVal(data.get("className"), "io.jettra.model.Entity");
        String serialVersion = strVal(data.get("serialVersion"), "1");
        Map<String, Object> props = (data.get("properties") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        String rows = props.entrySet().stream()
                .map(e -> "<tr><td><code>" + escapeHtml(e.getKey()) + "</code></td>"
                        + "<td style='color:var(--text-muted); font-size:0.78rem;'>" + escapeHtml(javaTypeName(e.getValue())) + "</td>"
                        + "<td><strong>" + escapeHtml(String.valueOf(e.getValue())) + "</strong></td></tr>")
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-viewer='OBJECT' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#eab308; margin-bottom:0.6rem;'>🧩 Visualizador de Objeto Java (POJO Serializado)</div>
              <div style='display:flex; gap:1rem; flex-wrap:wrap; background:var(--bg-subtle); border:1px solid #eab308; border-radius:8px; padding:0.75rem 1rem; margin-bottom:0.85rem;'>
                <div><span style='font-size:0.72rem; color:var(--text-muted); display:block;'>Clase Java</span><code style='color:#eab308; font-weight:700;'>%s</code></div>
                <div><span style='font-size:0.72rem; color:var(--text-muted); display:block;'>serialVersionUID</span><code style='color:var(--text-main); font-weight:700;'>%sL</code></div>
                <div><span style='font-size:0.72rem; color:var(--text-muted); display:block;'>ID Instancia</span><code style='color:#00d4ff; font-weight:700;'>%s</code></div>
              </div>
              <table class='app-table'><thead><tr><th>Atributo / Propiedad</th><th>Tipo</th><th>Valor</th></tr></thead><tbody>%s</tbody></table>
            </div>
            """, escapeHtml(className), escapeHtml(serialVersion), escapeHtml(rec.getId()), rows);
    }

    @SuppressWarnings("unchecked")
    private static String renderJavaRecordView(EngineRecordInfo rec, Map<String, Object> data) {
        String recordClass = strVal(data.get("recordClass"), "io.jettra.store.domain.Record");
        Map<String, Object> components = (data.get("components") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        String rows = components.entrySet().stream()
                .map(e -> "<tr><td><code style='color:#3b82f6; font-weight:700;'>" + escapeHtml(e.getKey()) + "</code></td>"
                        + "<td style='color:var(--text-muted); font-size:0.78rem;'>" + escapeHtml(javaTypeName(e.getValue())) + "</td>"
                        + "<td><strong>" + escapeHtml(String.valueOf(e.getValue())) + "</strong></td></tr>")
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-viewer='JAVA_RECORD' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#3b82f6; margin-bottom:0.6rem;'>💎 Visualizador de Java Canonical Record (Inmutable)</div>
              <div style='background:var(--bg-subtle); border:1px solid #3b82f6; border-radius:8px; padding:0.75rem 1rem; margin-bottom:0.85rem; font-family:monospace; font-size:0.85rem;'>
                <span style='color:#94a3b8;'>public record </span><strong style='color:#3b82f6;'>%s</strong> (%s)
              </div>
              <table class='app-table'><thead><tr><th>Componente Canónico</th><th>Tipo Inferido</th><th>Valor</th></tr></thead><tbody>%s</tbody></table>
            </div>
            """, escapeHtml(recordClass), escapeHtml(String.join(", ", components.keySet())), rows);
    }

    private static String renderGraphView(EngineRecordInfo rec, Map<String, Object> data) {
        String edgeId = strVal(data.get("edge_id"), rec.getId());
        String source = strVal(data.get("source"), "N/A");
        String target = strVal(data.get("target"), "N/A");
        String rel = strVal(data.get("rel"), "CONNECTED_TO");

        return String.format("""
            <div data-engine-viewer='GRAPH' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#ec4899; margin-bottom:0.6rem;'>🕸️ Visualizador de Grafo (Vértices y Arista Dirigida)</div>
              <div style='display:flex; align-items:center; justify-content:center; gap:0.85rem; flex-wrap:wrap; background:#020617; border:1px solid #ec4899; border-radius:10px; padding:1.15rem; margin-bottom:0.85rem;'>
                <div style='background:rgba(0,212,255,0.12); border:2px solid #00d4ff; border-radius:9999px; padding:0.5rem 1rem; font-weight:800; color:#00d4ff;'>● %s</div>
                <div style='text-align:center; color:#ec4899; font-family:monospace; font-size:0.82rem; font-weight:700;'>───[ %s ]───▶</div>
                <div style='background:rgba(16,185,129,0.12); border:2px solid #10b981; border-radius:9999px; padding:0.5rem 1rem; font-weight:800; color:#10b981;'>● %s</div>
              </div>
              <div style='font-size:0.78rem; color:var(--text-muted); margin-bottom:0.5rem;'>ID Arista: <code>%s</code> • Propiedades de la arista:</div>
              %s
            </div>
            """, escapeHtml(source), escapeHtml(rel), escapeHtml(target), escapeHtml(edgeId), renderKeyValueGrid(data, "#ec4899"));
    }

    private static String renderKeyValueView(EngineRecordInfo rec, Map<String, Object> data) {
        String keyName = strVal(data.getOrDefault("key", data.get("account")), rec.getId());
        return String.format("""
            <div data-engine-viewer='KEY_VALUE' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#f97316; margin-bottom:0.6rem;'>🔑 Visualizador Clave-Valor (In-Memory Key/Value Store)</div>
              <div style='display:grid; grid-template-columns: 1fr 2fr; gap:1rem; background:var(--bg-subtle); border:1px solid #f97316; border-radius:8px; padding:1rem;'>
                <div>
                  <div style='font-size:0.72rem; color:var(--text-muted); font-weight:700; text-transform:uppercase;'>Clave (KEY)</div>
                  <code style='font-size:1rem; color:#f97316; font-weight:800;'>%s</code>
                </div>
                <div>
                  <div style='font-size:0.72rem; color:var(--text-muted); font-weight:700; text-transform:uppercase; margin-bottom:0.4rem;'>Valor Asociado (VALUE)</div>
                  %s
                </div>
              </div>
            </div>
            """, escapeHtml(keyName), renderKeyValueGrid(data, "#f97316"));
    }

    private static String renderGeospatialView(EngineRecordInfo rec, Map<String, Object> data) {
        String stationId = strVal(data.get("station_id"), rec.getId());
        String lat = strVal(data.get("lat"), "0.0");
        String lng = strVal(data.get("lng"), "0.0");
        String alt = strVal(data.getOrDefault("altitud_m", data.get("altitud")), "0");
        String zona = strVal(data.get("zona"), "N/A");

        return String.format("""
            <div data-engine-viewer='GEOSPATIAL' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#06b6d4; margin-bottom:0.6rem;'>🌍 Visualizador Geoespacial (GeoJSON &amp; R-Tree)</div>
              <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;'>
                %s
                %s
                %s
                %s
                %s
              </div>
              <div style='background:#020617; border:1px solid #06b6d4; border-radius:8px; padding:0.75rem 1rem; font-family:monospace; font-size:0.82rem; color:#38bdf8;'>
                GeoJSON Geometry: { "type": "Point", "coordinates": [%s, %s] }
              </div>
            </div>
            """, metricBox("Estación / Punto", stationId, "#06b6d4"),
                 metricBox("Latitud (WGS84)", lat + "°", "#00d4ff"),
                 metricBox("Longitud (WGS84)", lng + "°", "#00d4ff"),
                 metricBox("Altitud", alt + " m", "#10b981"),
                 metricBox("Zona", zona, "#ffd700"),
                 escapeHtml(lng), escapeHtml(lat));
    }

    private static String renderVectorView(EngineRecordInfo rec, Map<String, Object> data) {
        String model = strVal(data.get("model"), "embedding-model");
        String dims = strVal(data.get("dims"), "1536");
        String label = strVal(data.get("etiqueta"), rec.getSummary());
        Object embObj = data.getOrDefault("embedding_preview", data.get("embedding"));
        List<String> dimsList = new ArrayList<>();
        if (embObj instanceof List<?> list) {
            for (Object item : list) dimsList.add(String.valueOf(item));
        }

        String chips = java.util.stream.IntStream.range(0, dimsList.size())
                .mapToObj(i -> "<span style='background:rgba(168,85,247,0.18); border:1px solid #a855f7; color:#e9d5ff; padding:3px 8px; border-radius:6px; font-family:monospace; font-size:0.8rem;'>["
                        + i + "]: " + escapeHtml(dimsList.get(i)) + "</span>")
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-viewer='VECTOR' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#a855f7; margin-bottom:0.6rem;'>🧠 Visualizador Vectorial (HNSW Dense Embedding)</div>
              <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;'>
                %s
                %s
                %s
              </div>
              <div style='background:#020617; border:1px solid #a855f7; border-radius:8px; padding:0.85rem 1rem;'>
                <div style='font-size:0.75rem; color:var(--text-muted); margin-bottom:0.5rem;'>Componentes del Vector (Preview):</div>
                <div style='display:flex; gap:0.5rem; flex-wrap:wrap;'>
                  %s
                </div>
              </div>
            </div>
            """, metricBox("Modelo", model, "#a855f7"),
                 metricBox("Dimensiones", dims + "d", "#00d4ff"),
                 metricBox("Etiqueta Semántica", label, "#10b981"),
                 chips);
    }

    private static String renderTimeseriesView(EngineRecordInfo rec, Map<String, Object> data) {
        String dataBoxes = data.entrySet().stream()
                .map(e -> metricBox(e.getKey(), String.valueOf(e.getValue()), "#00d4ff"))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-viewer='TIMESERIES' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:#22c55e; margin-bottom:0.6rem;'>📈 Visualizador de Serie Temporal (BRIN / Telemetry)</div>
              <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;'>
                %s
                %s
              </div>
            </div>
            """, metricBox("Timestamp", rec.getTimestamp(), "#22c55e"), dataBoxes);
    }

    private static String renderTabularView(EngineRecordInfo rec, String engine, Map<String, Object> data) {
        String accent = "COLUMNAR".equals(engine) ? "#38bdf8" : "#ffd700";
        String title = "COLUMNAR".equals(engine)
                ? "📊 Visualizador Columnar (Almacenamiento por Columnas)"
                : "🗃️ Visualizador Relacional (Registro Tabular)";

        String ths = data.keySet().stream()
                .map(col -> "<th>" + escapeHtml(col) + "</th>")
                .collect(Collectors.joining());
        String tds = data.values().stream()
                .map(val -> "<td><strong>" + escapeHtml(String.valueOf(val)) + "</strong></td>")
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-viewer='%s' style='margin-bottom:1rem;'>
              <div style='font-size:0.8rem; font-weight:700; color:%s; margin-bottom:0.6rem;'>%s</div>
              <table class='app-table'><thead><tr>%s</tr></thead><tbody><tr>%s</tr></tbody></table>
            </div>
            """, engine, accent, title, ths, tds);
    }

    // =========================================================================
    // EDITOR POR TIPO DE MOTOR (EDITAR)
    // =========================================================================

    public static String renderEditFieldsHtml(EngineRecordInfo rec) {
        String engine = normalizeEngine(rec.getEngineType());
        Map<String, Object> data = parseDetailsMap(rec.getDetails());

        return switch (engine) {
            case "OBJECT" -> renderObjectEditor(data);
            case "JAVA_RECORD" -> renderJavaRecordEditor(data);
            case "GRAPH" -> renderGraphEditor(rec, data);
            case "KEY_VALUE" -> renderKeyValueEditor(rec, data);
            case "GEOSPATIAL" -> renderGeospatialEditor(rec, data);
            case "VECTOR" -> renderVectorEditor(rec, data);
            case "TIMESERIES" -> renderTimeseriesEditor(data);
            case "COLUMNAR", "RELATIONAL" -> renderTabularEditor(engine, data);
            default -> renderDocumentEditor(rec);
        };
    }

    private static String renderDocumentEditor(EngineRecordInfo rec) {
        return String.format("""
            <div data-engine-editor="DOCUMENT" style="margin-bottom: 1rem;">
              <label style="font-size: 0.78rem; font-weight: 700; color: #00d4ff; display: block; margin-bottom: 0.35rem;">📄 Editor de Documento (JSON / BSON)</label>
              <textarea class="app-input" name="details" rows="8" style="font-family: monospace; font-size: 0.85rem;" required>%s</textarea>
            </div>
        """, escapeHtml(rec.getDetails()));
    }

    @SuppressWarnings("unchecked")
    private static String renderObjectEditor(Map<String, Object> data) {
        String className = strVal(data.get("className"), "io.jettra.model.Entity");
        String serialVersion = strVal(data.get("serialVersion"), "1");
        Map<String, Object> props = (data.get("properties") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        String propInputs = props.entrySet().stream()
                .map(e -> fieldInput(e.getKey(), "prop_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='OBJECT' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #eab308; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #eab308; margin-bottom: 0.75rem;'>🧩 Editor de Objeto Java (POJO)</div>
              <input type='hidden' name='engineEditorMode' value='OBJECT' />
              <div style='display: grid; grid-template-columns: 2fr 1fr; gap: 0.85rem; margin-bottom: 0.85rem;'>
                %s
                %s
              </div>
              <div style='font-size:0.75rem; font-weight:700; color:var(--text-muted); margin-bottom:0.45rem;'>Propiedades de la instancia:</div>
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0.75rem;'>
                %s
              </div>
            </div>
            """, fieldInput("Clase Java (className)", "obj_className", className),
                 fieldInput("serialVersionUID", "obj_serialVersion", serialVersion),
                 propInputs);
    }

    @SuppressWarnings("unchecked")
    private static String renderJavaRecordEditor(Map<String, Object> data) {
        String recordClass = strVal(data.get("recordClass"), "io.jettra.store.domain.Record");
        Map<String, Object> components = (data.get("components") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        String compInputs = components.entrySet().stream()
                .map(e -> fieldInput(e.getKey(), "comp_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='JAVA_RECORD' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #3b82f6; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #3b82f6; margin-bottom: 0.75rem;'>💎 Editor de Java Canonical Record (Nueva versión inmutable)</div>
              <input type='hidden' name='engineEditorMode' value='JAVA_RECORD' />
              <div style='margin-bottom: 0.85rem;'>
                %s
              </div>
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;'>
                %s
              </div>
            </div>
            """, fieldInput("Record Class", "rec_recordClass", recordClass), compInputs);
    }

    private static String renderGraphEditor(EngineRecordInfo rec, Map<String, Object> data) {
        String extraFields = data.entrySet().stream()
                .filter(e -> !"edge_id".equals(e.getKey()) && !"source".equals(e.getKey()) && !"rel".equals(e.getKey()) && !"target".equals(e.getKey()))
                .map(e -> fieldInput(e.getKey(), "prop_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='GRAPH' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #ec4899; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #ec4899; margin-bottom: 0.75rem;'>🕸️ Editor de Arista de Grafo (Nodos y Relación)</div>
              <input type='hidden' name='engineEditorMode' value='GRAPH' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;'>
                %s
                %s
                %s
                %s
                %s
              </div>
            </div>
            """, fieldInput("ID Arista (edge_id)", "prop_edge_id", strVal(data.get("edge_id"), rec.getId())),
                 fieldInput("Nodo Origen (source)", "prop_source", strVal(data.get("source"), "")),
                 fieldInput("Relación (rel)", "prop_rel", strVal(data.get("rel"), "")),
                 fieldInput("Nodo Destino (target)", "prop_target", strVal(data.get("target"), "")),
                 extraFields);
    }

    private static String renderKeyValueEditor(EngineRecordInfo rec, Map<String, Object> data) {
        String keyField = (!data.containsKey("key") && !data.containsKey("account"))
                ? fieldInput("key", "prop_key", rec.getId())
                : "";
        String propFields = data.entrySet().stream()
                .map(e -> fieldInput(e.getKey(), "prop_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='KEY_VALUE' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #f97316; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #f97316; margin-bottom: 0.75rem;'>🔑 Editor Clave-Valor</div>
              <input type='hidden' name='engineEditorMode' value='KEY_VALUE' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;'>
                %s
                %s
              </div>
            </div>
            """, keyField, propFields);
    }

    private static String renderGeospatialEditor(EngineRecordInfo rec, Map<String, Object> data) {
        return String.format("""
            <div data-engine-editor='GEOSPATIAL' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #06b6d4; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #06b6d4; margin-bottom: 0.75rem;'>🌍 Editor Geoespacial (Coordenadas WGS84 &amp; Metadatos)</div>
              <input type='hidden' name='engineEditorMode' value='GEOSPATIAL' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;'>
                %s
                %s
                %s
                %s
                %s
              </div>
            </div>
            """, fieldInput("Estación (station_id)", "prop_station_id", strVal(data.get("station_id"), rec.getId())),
                 fieldInput("Latitud (lat)", "prop_lat", strVal(data.get("lat"), "0.0")),
                 fieldInput("Longitud (lng)", "prop_lng", strVal(data.get("lng"), "0.0")),
                 fieldInput("Altitud m (altitud_m)", "prop_altitud_m", strVal(data.getOrDefault("altitud_m", data.get("altitud")), "0")),
                 fieldInput("Zona", "prop_zona", strVal(data.get("zona"), "")));
    }

    private static String renderVectorEditor(EngineRecordInfo rec, Map<String, Object> data) {
        Object embObj = data.getOrDefault("embedding_preview", data.get("embedding"));
        String embStr = "";
        if (embObj instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(String.valueOf(o));
            embStr = String.join(", ", parts);
        } else if (embObj != null) {
            embStr = String.valueOf(embObj);
        }

        return String.format("""
            <div data-engine-editor='VECTOR' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #a855f7; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #a855f7; margin-bottom: 0.75rem;'>🧠 Editor de Vector Embedding (HNSW)</div>
              <input type='hidden' name='engineEditorMode' value='VECTOR' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem; margin-bottom: 0.75rem;'>
                %s
                %s
                %s
                %s
              </div>
              %s
            </div>
            """, fieldInput("ID Vector", "prop_id", strVal(data.get("id"), rec.getId())),
                 fieldInput("Modelo", "prop_model", strVal(data.get("model"), "biomed-clip-v3")),
                 fieldInput("Dimensiones", "prop_dims", strVal(data.get("dims"), "1536")),
                 fieldInput("Etiqueta", "prop_etiqueta", strVal(data.get("etiqueta"), "")),
                 fieldInput("Vector Embedding (floats separados por coma)", "vec_embedding", embStr));
    }

    private static String renderTimeseriesEditor(Map<String, Object> data) {
        String inputs = data.entrySet().stream()
                .map(e -> fieldInput(e.getKey(), "prop_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='TIMESERIES' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #22c55e; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: #22c55e; margin-bottom: 0.75rem;'>📈 Editor de Serie Temporal</div>
              <input type='hidden' name='engineEditorMode' value='TIMESERIES' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;'>
                %s
              </div>
            </div>
            """, inputs);
    }

    private static String renderTabularEditor(String engine, Map<String, Object> data) {
        String accent = "COLUMNAR".equals(engine) ? "#38bdf8" : "#ffd700";
        String inputs = data.entrySet().stream()
                .map(e -> fieldInput(e.getKey(), "prop_" + e.getKey(), String.valueOf(e.getValue())))
                .collect(Collectors.joining());

        return String.format("""
            <div data-engine-editor='%s' style='margin-bottom: 1rem; padding: 0.9rem; border: 1px solid %s; border-radius: 8px;'>
              <div style='font-size: 0.8rem; font-weight: 700; color: %s; margin-bottom: 0.75rem;'>📊 Editor %s (Columnas / Campos)</div>
              <input type='hidden' name='engineEditorMode' value='%s' />
              <div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;'>
                %s
              </div>
            </div>
            """, engine, accent, accent, engine, engine, inputs);
    }

    // =========================================================================
    // RECONSTRUCCIÓN DE DETAILS AL GUARDAR EDICIÓN
    // =========================================================================

    public static String buildDetailsFromForm(Map<String, String> form, String existingDetails) {
        String explicitDetails = form.get("details");
        String mode = form.get("engineEditorMode");
        if (mode == null || mode.isBlank()) {
            return explicitDetails != null ? explicitDetails : existingDetails;
        }

        Map<String, Object> existingMap = parseDetailsMap(existingDetails);
        Map<String, Object> result = new LinkedHashMap<>();

        if ("OBJECT".equalsIgnoreCase(mode)) {
            result.put("className", form.getOrDefault("obj_className", strVal(existingMap.get("className"), "io.jettra.model.Entity")));
            result.put("serialVersion", parseScalar(form.getOrDefault("obj_serialVersion", strVal(existingMap.get("serialVersion"), "1"))));
            Map<String, Object> props = new LinkedHashMap<>();
            collectPrefixed(form, "prop_", props);
            result.put("properties", props);
        } else if ("JAVA_RECORD".equalsIgnoreCase(mode)) {
            result.put("recordClass", form.getOrDefault("rec_recordClass", strVal(existingMap.get("recordClass"), "io.jettra.store.domain.Record")));
            Map<String, Object> comps = new LinkedHashMap<>();
            collectPrefixed(form, "comp_", comps);
            result.put("components", comps);
        } else if ("VECTOR".equalsIgnoreCase(mode)) {
            collectPrefixed(form, "prop_", result);
            String embStr = form.get("vec_embedding");
            if (embStr != null) {
                List<Double> nums = new ArrayList<>();
                for (String token : embStr.split(",")) {
                    String t = token.trim();
                    if (!t.isEmpty()) {
                        try { nums.add(Double.parseDouble(t)); } catch (NumberFormatException ignored) {}
                    }
                }
                result.put("embedding_preview", nums);
            }
        } else {
            collectPrefixed(form, "prop_", result);
        }

        if (result.isEmpty()) {
            return explicitDetails != null ? explicitDetails : existingDetails;
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return explicitDetails != null ? explicitDetails : existingDetails;
        }
    }

    private static void collectPrefixed(Map<String, String> form, String prefix, Map<String, Object> target) {
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String fieldName = entry.getKey().substring(prefix.length());
                target.put(fieldName, parseScalar(entry.getValue()));
            }
        }
    }

    private static Object parseScalar(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        if (t.matches("-?\\d+")) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignored) {}
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            try { return Double.parseDouble(t); } catch (NumberFormatException ignored) {}
        }
        return raw;
    }

    // =========================================================================
    // UTILIDADES HTML
    // =========================================================================

    private static String renderKeyValueGrid(Map<String, Object> data, String accent) {
        String items = data.entrySet().stream()
                .map(e -> "<div style='background:var(--bg-subtle); border-left:3px solid " + accent + "; border-radius:6px; padding:0.5rem 0.7rem;'>"
                        + "<div style='font-size:0.72rem; color:var(--text-muted); font-weight:700;'>" + escapeHtml(e.getKey()) + "</div>"
                        + "<div style='font-size:0.86rem; color:var(--text-main); font-weight:600; word-break:break-word;'>" + escapeHtml(String.valueOf(e.getValue())) + "</div>"
                        + "</div>")
                .collect(Collectors.joining());

        return "<div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap:0.6rem;'>" + items + "</div>";
    }

    private static String metricBox(String label, String value, String color) {
        return "<div style='background:var(--bg-subtle); border:1px solid var(--border-subtle); border-top:3px solid " + color + "; border-radius:8px; padding:0.6rem 0.8rem;'>"
                + "<div style='font-size:0.72rem; color:var(--text-muted); font-weight:700;'>" + escapeHtml(label) + "</div>"
                + "<div style='font-size:0.95rem; color:var(--text-main); font-weight:800; margin-top:0.2rem;'>" + escapeHtml(value) + "</div>"
                + "</div>";
    }

    private static String fieldInput(String label, String name, String value) {
        return "<div>"
                + "<label style='font-size:0.74rem; font-weight:700; color:var(--text-muted); display:block; margin-bottom:0.25rem;'>" + escapeHtml(label) + "</label>"
                + "<input class='app-input' type='text' name='" + escapeHtml(name) + "' value='" + escapeHtml(value) + "' />"
                + "</div>";
    }

    private static String javaTypeName(Object val) {
        if (val == null) return "null";
        if (val instanceof Number) return val instanceof Double || val instanceof Float ? "double" : "long";
        if (val instanceof Boolean) return "boolean";
        if (val instanceof List<?>) return "List";
        if (val instanceof Map<?, ?>) return "Map";
        return "String";
    }

    private static String strVal(Object obj, String def) {
        return obj != null ? String.valueOf(obj) : def;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}


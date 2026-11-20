package io.jettra.store.explorer.pages.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.EngineRecordVersion;
import io.jettra.store.explorer.pages.template.FluxUi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renderizador y constructor de formularios en widgets nativos JettraFlux
 * adaptado al formato de almacenamiento de cada motor (engine) de JettraStore.
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
    // VISUALIZADOR POR TIPO DE MOTOR (VER / VERSIONES)
    // =========================================================================

    public static Widget renderVersionViewWidget(EngineRecordInfo parentRec, EngineRecordVersion version) {
        EngineRecordInfo snapshot = new EngineRecordInfo(
                parentRec.getId(),
                parentRec.getEngineType(),
                parentRec.getBucketName(),
                version.getSummary(),
                version.getDetails(),
                version.getTimestamp(),
                version.getVersion()
        );
        return renderViewWidget(snapshot);
    }

    public static Widget renderViewWidget(EngineRecordInfo rec) {
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
            case "COLUMNAR", "RELATIONAL" -> renderTabularView(engine, data);
            default -> renderDocumentView(rec, data);
        };
    }

    private static Widget renderDocumentView(EngineRecordInfo rec, Map<String, Object> data) {
        Widget title = FluxUi.coloredSpan("📄 Visualizador de Documento BSON / JSON",
                "font-size:0.8rem; font-weight:700; color:#00d4ff; display:block; margin-bottom:0.6rem;");
        Widget grid = renderKeyValueGrid(data, "#00d4ff");
        Widget rawPre = Div.of(FluxUi.preBlock(rec.getDetails(), "color:#38bdf8;"))
                .modifier(new Modifier().style("background:#020617; border:1px solid var(--border-subtle); border-radius:8px; padding:1rem; margin-top:0.65rem; overflow-x:auto;"));

        Widget box = Div.of(title, grid, rawPre);
        box.modifier(new Modifier().attribute("data-engine-viewer", "DOCUMENT").style("margin-bottom:1rem;"));
        return box;
    }

    @SuppressWarnings("unchecked")
    private static Widget renderObjectView(EngineRecordInfo rec, Map<String, Object> data) {
        String className = strVal(data.get("className"), "io.jettra.model.Entity");
        String serialVersion = strVal(data.get("serialVersion"), "1");
        Map<String, Object> props = (data.get("properties") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        Widget title = FluxUi.coloredSpan("🧩 Visualizador de Objeto Java (POJO Serializado)",
                "font-size:0.8rem; font-weight:700; color:#eab308; display:block; margin-bottom:0.6rem;");

        Widget metaBar = Div.of(
                Div.of(FluxUi.coloredSpan("Clase Java", "font-size:0.72rem; color:var(--text-muted); display:block;"),
                       FluxUi.coloredSpan(className, "color:#eab308; font-weight:700; font-family:monospace;")),
                Div.of(FluxUi.coloredSpan("serialVersionUID", "font-size:0.72rem; color:var(--text-muted); display:block;"),
                       FluxUi.coloredSpan(serialVersion + "L", "color:var(--text-main); font-weight:700; font-family:monospace;")),
                Div.of(FluxUi.coloredSpan("ID Instancia", "font-size:0.72rem; color:var(--text-muted); display:block;"),
                       FluxUi.coloredSpan(rec.getId(), "color:#00d4ff; font-weight:700; font-family:monospace;"))
        ).modifier(new Modifier().style("display:flex; gap:1rem; flex-wrap:wrap; background:var(--bg-subtle); border:1px solid #eab308; border-radius:8px; padding:0.75rem 1rem; margin-bottom:0.85rem;"));

        List<Widget> headers = List.of(Text.of("Atributo / Propiedad"), Text.of("Tipo"), Text.of("Valor"));
        List<List<Widget>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            rows.add(List.of(
                    FluxUi.coloredSpan(e.getKey(), "font-family:monospace;"),
                    FluxUi.coloredSpan(javaTypeName(e.getValue()), "color:var(--text-muted); font-size:0.78rem;"),
                    FluxUi.coloredSpan(String.valueOf(e.getValue()), "font-weight:700;")
            ));
        }
        Datatable table = Datatable.ofWidgets(headers, rows);
        table.modifier(new Modifier().cssClass("app-table"));

        Widget box = Div.of(title, metaBar, table);
        box.modifier(new Modifier().attribute("data-engine-viewer", "OBJECT").style("margin-bottom:1rem;"));
        return box;
    }

    @SuppressWarnings("unchecked")
    private static Widget renderJavaRecordView(EngineRecordInfo rec, Map<String, Object> data) {
        String recordClass = strVal(data.get("recordClass"), "io.jettra.store.domain.Record");
        Map<String, Object> components = (data.get("components") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;
        Map<String, Object> schema = (data.get("schema") instanceof Map<?, ?> s)
                ? (Map<String, Object>) s : Map.of();

        Widget title = FluxUi.coloredSpan("💎 Visualizador de Java Canonical Record (Inmutable)",
                "font-size:0.8rem; font-weight:700; color:#3b82f6; display:block; margin-bottom:0.6rem;");

        Widget sigBar = Div.of(
                FluxUi.coloredSpan("public record ", "color:#94a3b8; font-family:monospace; font-size:0.85rem;"),
                FluxUi.coloredSpan(recordClass, "color:#3b82f6; font-weight:700; font-family:monospace; font-size:0.85rem;"),
                FluxUi.coloredSpan(" (" + String.join(", ", components.keySet()) + ")", "font-family:monospace; font-size:0.85rem;")
        ).modifier(new Modifier().style("background:var(--bg-subtle); border:1px solid #3b82f6; border-radius:8px; padding:0.75rem 1rem; margin-bottom:0.85rem;"));

        List<Widget> headers = List.of(Text.of("Componente Canónico"), Text.of("Tipo"), Text.of("Regla JettraStore"), Text.of("Valor"));
        List<List<Widget>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : components.entrySet()) {
            String typeLabel = javaTypeName(e.getValue());
            String ruleLabel = "NONE";
            Object metaObj = schema.get(e.getKey());
            if (metaObj instanceof Map<?, ?> meta) {
                if (meta.get("type") != null) typeLabel = String.valueOf(meta.get("type"));
                if (meta.get("rule") != null) ruleLabel = String.valueOf(meta.get("rule"));
            }
            Widget ruleWidget = "NONE".equalsIgnoreCase(ruleLabel)
                    ? FluxUi.coloredSpan("—", "color:var(--text-muted); font-size:0.78rem;")
                    : FluxUi.pillBadge(ruleLabel, "#3b82f6");
            rows.add(List.of(
                    FluxUi.coloredSpan(e.getKey(), "color:#3b82f6; font-weight:700; font-family:monospace;"),
                    FluxUi.coloredSpan(typeLabel, "color:var(--text-muted); font-size:0.78rem;"),
                    ruleWidget,
                    FluxUi.coloredSpan(String.valueOf(e.getValue()), "font-weight:700;")
            ));
        }
        Datatable table = Datatable.ofWidgets(headers, rows);
        table.modifier(new Modifier().cssClass("app-table"));

        Widget box = Div.of(title, sigBar, table);
        box.modifier(new Modifier().attribute("data-engine-viewer", "JAVA_RECORD").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderGraphView(EngineRecordInfo rec, Map<String, Object> data) {
        String edgeId = strVal(data.get("edge_id"), rec.getId());
        String source = strVal(data.get("source"), "N/A");
        String target = strVal(data.get("target"), "N/A");
        String rel = strVal(data.get("rel"), "CONNECTED_TO");

        Widget title = FluxUi.coloredSpan("🕸️ Visualizador de Grafo (Vértices y Arista Dirigida)",
                "font-size:0.8rem; font-weight:700; color:#ec4899; display:block; margin-bottom:0.6rem;");

        Widget srcNode = Div.of(Text.of("● " + source)).modifier(new Modifier().style(
                "background:rgba(0,212,255,0.12); border:2px solid #00d4ff; border-radius:9999px; padding:0.5rem 1rem; font-weight:800; color:#00d4ff;"));
        Widget relEdge = Div.of(Text.of("───[ " + rel + " ]───▶")).modifier(new Modifier().style(
                "text-align:center; color:#ec4899; font-family:monospace; font-size:0.82rem; font-weight:700;"));
        Widget dstNode = Div.of(Text.of("● " + target)).modifier(new Modifier().style(
                "background:rgba(16,185,129,0.12); border:2px solid #10b981; border-radius:9999px; padding:0.5rem 1rem; font-weight:800; color:#10b981;"));

        Widget diagram = Div.of(srcNode, relEdge, dstNode).modifier(new Modifier().style(
                "display:flex; align-items:center; justify-content:center; gap:0.85rem; flex-wrap:wrap; background:#020617; border:1px solid #ec4899; border-radius:10px; padding:1.15rem; margin-bottom:0.85rem;"));

        Widget sub = FluxUi.coloredSpan("ID Arista: " + edgeId + " • Propiedades de la arista:",
                "font-size:0.78rem; color:var(--text-muted); display:block; margin-bottom:0.5rem;");

        Widget box = Div.of(title, diagram, sub, renderKeyValueGrid(data, "#ec4899"));
        box.modifier(new Modifier().attribute("data-engine-viewer", "GRAPH").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderKeyValueView(EngineRecordInfo rec, Map<String, Object> data) {
        String keyName = strVal(data.getOrDefault("key", data.get("account")), rec.getId());
        Widget title = FluxUi.coloredSpan("🔑 Visualizador Clave-Valor (In-Memory Key/Value Store)",
                "font-size:0.8rem; font-weight:700; color:#f97316; display:block; margin-bottom:0.6rem;");

        Widget keyCol = Div.of(
                FluxUi.coloredSpan("Clave (KEY)", "font-size:0.72rem; color:var(--text-muted); font-weight:700; text-transform:uppercase; display:block;"),
                FluxUi.coloredSpan(keyName, "font-size:1rem; color:#f97316; font-weight:800; font-family:monospace;")
        );
        Widget valCol = Div.of(
                FluxUi.coloredSpan("Valor Asociado (VALUE)", "font-size:0.72rem; color:var(--text-muted); font-weight:700; text-transform:uppercase; display:block; margin-bottom:0.4rem;"),
                renderKeyValueGrid(data, "#f97316")
        );
        Widget panel = Div.of(keyCol, valCol).modifier(new Modifier().style(
                "display:grid; grid-template-columns: 1fr 2fr; gap:1rem; background:var(--bg-subtle); border:1px solid #f97316; border-radius:8px; padding:1rem;"));

        Widget box = Div.of(title, panel);
        box.modifier(new Modifier().attribute("data-engine-viewer", "KEY_VALUE").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderGeospatialView(EngineRecordInfo rec, Map<String, Object> data) {
        String stationId = strVal(data.get("station_id"), rec.getId());
        String lat = strVal(data.get("lat"), "0.0");
        String lng = strVal(data.get("lng"), "0.0");
        String alt = strVal(data.getOrDefault("altitud_m", data.get("altitud")), "0");
        String zona = strVal(data.get("zona"), "N/A");

        Widget title = FluxUi.coloredSpan("🌍 Visualizador Geoespacial (GeoJSON & R-Tree)",
                "font-size:0.8rem; font-weight:700; color:#06b6d4; display:block; margin-bottom:0.6rem;");

        Widget metrics = Div.of(
                metricBox("Estación / Punto", stationId, "#06b6d4"),
                metricBox("Latitud (WGS84)", lat + "°", "#00d4ff"),
                metricBox("Longitud (WGS84)", lng + "°", "#00d4ff"),
                metricBox("Altitud", alt + " m", "#10b981"),
                metricBox("Zona", zona, "#ffd700")
        ).modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;"));

        Widget geoJson = Div.of(Text.of("GeoJSON Geometry: { \"type\": \"Point\", \"coordinates\": [" + lng + ", " + lat + "] }"))
                .modifier(new Modifier().style("background:#020617; border:1px solid #06b6d4; border-radius:8px; padding:0.75rem 1rem; font-family:monospace; font-size:0.82rem; color:#38bdf8;"));

        Widget box = Div.of(title, metrics, geoJson);
        box.modifier(new Modifier().attribute("data-engine-viewer", "GEOSPATIAL").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderVectorView(EngineRecordInfo rec, Map<String, Object> data) {
        String model = strVal(data.get("model"), "embedding-model");
        String dims = strVal(data.get("dims"), "1536");
        String label = strVal(data.get("etiqueta"), rec.getSummary());
        Object embObj = data.getOrDefault("embedding_preview", data.get("embedding"));
        List<Widget> dimChips = new ArrayList<>();
        if (embObj instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                dimChips.add(FluxUi.coloredSpan("[" + i + "]: " + list.get(i),
                        "background:rgba(168,85,247,0.18); border:1px solid #a855f7; color:#e9d5ff; padding:3px 8px; border-radius:6px; font-family:monospace; font-size:0.8rem;"));
            }
        }

        Widget title = FluxUi.coloredSpan("🧠 Visualizador Vectorial (HNSW Dense Embedding)",
                "font-size:0.8rem; font-weight:700; color:#a855f7; display:block; margin-bottom:0.6rem;");

        Widget metrics = Div.of(
                metricBox("Modelo", model, "#a855f7"),
                metricBox("Dimensiones", dims + "d", "#00d4ff"),
                metricBox("Etiqueta Semántica", label, "#10b981")
        ).modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;"));

        Widget chipsWrap = Div.of(dimChips.toArray(new Widget[0]))
                .modifier(new Modifier().style("display:flex; gap:0.5rem; flex-wrap:wrap;"));
        Widget embBox = Div.of(
                FluxUi.coloredSpan("Componentes del Vector (Preview):", "font-size:0.75rem; color:var(--text-muted); display:block; margin-bottom:0.5rem;"),
                chipsWrap
        ).modifier(new Modifier().style("background:#020617; border:1px solid #a855f7; border-radius:8px; padding:0.85rem 1rem;"));

        Widget box = Div.of(title, metrics, embBox);
        box.modifier(new Modifier().attribute("data-engine-viewer", "VECTOR").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderTimeseriesView(EngineRecordInfo rec, Map<String, Object> data) {
        Widget title = FluxUi.coloredSpan("📈 Visualizador de Serie Temporal (BRIN / Telemetry)",
                "font-size:0.8rem; font-weight:700; color:#22c55e; display:block; margin-bottom:0.6rem;");

        List<Widget> boxes = new ArrayList<>();
        boxes.add(metricBox("Timestamp", rec.getTimestamp(), "#22c55e"));
        for (Map.Entry<String, Object> e : data.entrySet()) {
            boxes.add(metricBox(e.getKey(), String.valueOf(e.getValue()), "#00d4ff"));
        }
        Widget grid = Div.of(boxes.toArray(new Widget[0]))
                .modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap:0.75rem; margin-bottom:0.85rem;"));

        Widget box = Div.of(title, grid);
        box.modifier(new Modifier().attribute("data-engine-viewer", "TIMESERIES").style("margin-bottom:1rem;"));
        return box;
    }

    private static Widget renderTabularView(String engine, Map<String, Object> data) {
        String accent = "COLUMNAR".equals(engine) ? "#38bdf8" : "#ffd700";
        String titleText = "COLUMNAR".equals(engine)
                ? "📊 Visualizador Columnar (Almacenamiento por Columnas)"
                : "🗃️ Visualizador Relacional (Registro Tabular)";
        Widget title = FluxUi.coloredSpan(titleText,
                "font-size:0.8rem; font-weight:700; color:" + accent + "; display:block; margin-bottom:0.6rem;");

        List<Widget> headers = new ArrayList<>();
        List<Widget> rowVals = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            headers.add(Text.of(e.getKey()));
            rowVals.add(FluxUi.coloredSpan(String.valueOf(e.getValue()), "font-weight:700;"));
        }
        Datatable table = Datatable.ofWidgets(headers, List.of(rowVals));
        table.modifier(new Modifier().cssClass("app-table"));

        Widget box = Div.of(title, table);
        box.modifier(new Modifier().attribute("data-engine-viewer", engine).style("margin-bottom:1rem;"));
        return box;
    }

    // =========================================================================
    // CREADOR POR TIPO DE MOTOR (INSERTAR / CREATE)
    // =========================================================================

    private static final List<String> JAVA_RECORD_TYPES = List.of(
            "String", "Long", "Integer", "Double", "BigDecimal", "Boolean", "LocalDate", "LocalDateTime", "UUID"
    );

    private static final List<String> JETTRA_STORE_RULES = List.of(
            "NONE", "@Id", "@Required", "@Unique", "@Indexed", "@JettraRef", "@Version", "@Encrypted", "@NotNull", "@AutoIncrement"
    );

    public static Widget renderCreateFieldsWidget(String engineType) {
        String engine = normalizeEngine(engineType);
        return switch (engine) {
            case "JAVA_RECORD" -> renderJavaRecordCreator();
            case "OBJECT" -> renderObjectCreator();
            case "KEY_VALUE" -> renderKeyValueCreator();
            case "GRAPH" -> renderGraphCreator();
            case "VECTOR" -> renderVectorCreator();
            case "TIMESERIES" -> renderTimeseriesCreator();
            case "GEOSPATIAL" -> renderGeospatialCreator();
            case "COLUMNAR", "RELATIONAL" -> renderTabularCreator(engine);
            default -> renderDocumentCreator();
        };
    }

    private static Widget renderDocumentCreator() {
        String sampleJson = "{\n  \"folio\": \"FAC-2026-004\",\n  \"emisor\": \"Servicios Digitales SA\",\n  \"receptor\": \"Cliente Ejemplo\",\n  \"total\": 1500.00,\n  \"estado\": \"PAGADO\"\n}";
        Widget box = Div.of(
                FluxUi.coloredSpan("📄 Nuevo Documento (Estilo JSON / BSON)",
                        "font-size: 0.8rem; font-weight: 700; color: #00d4ff; display: block; margin-bottom: 0.45rem;"),
                FluxUi.textArea("details", sampleJson, 8, true, null)
        );
        box.modifier(new Modifier()
                .attribute("data-engine-creator", "DOCUMENT")
                .attribute("data-engine-editor", "DOCUMENT")
                .style("margin-bottom: 1rem; padding: 0.9rem; border: 1px solid #00d4ff; border-radius: 8px;"));
        return box;
    }

    private static Widget renderJavaRecordCreator() {
        List<String[]> defaults = List.of(
                new String[]{"orderId", "Long", "@Id", "502"},
                new String[]{"clientName", "String", "@Required", "Comercializadora"},
                new String[]{"total", "BigDecimal", "@Indexed", "8500.00"},
                new String[]{"status", "String", "NONE", "APPROVED"},
                new String[]{"", "String", "NONE", ""}
        );

        List<Widget> headers = List.of(
                Text.of("Nombre del Campo"),
                Text.of("Tipo"),
                Text.of("Regla JettraStore"),
                Text.of("Valor del Campo")
        );

        List<List<Widget>> rows = new ArrayList<>();
        for (int i = 0; i < defaults.size(); i++) {
            String[] def = defaults.get(i);
            rows.add(List.of(
                    FluxUi.textInput("rec_field_name_" + i, def[0], "nombreCampo", false),
                    FluxUi.dropdown("rec_field_type_" + i, JAVA_RECORD_TYPES, def[1]),
                    FluxUi.dropdown("rec_field_rule_" + i, JETTRA_STORE_RULES, def[2]),
                    FluxUi.textInput("rec_field_val_" + i, def[3], "valor", false)
            ));
        }

        Datatable fieldsTable = Datatable.ofWidgets(headers, rows);
        fieldsTable.modifier(new Modifier().cssClass("app-table").attribute("data-java-record-fields", "true"));

        Widget classRow = Div.of(
                FluxUi.fieldColumn("Clase Java Record (recordClass)",
                        FluxUi.textInput("rec_recordClass", "io.jettra.store.domain.OrderSummary", "io.jettra.store.domain.MiRecord", true), null)
        ).modifier(new Modifier().style("margin-bottom: 0.85rem;"));

        Widget hint = FluxUi.coloredSpan(
                "Defina cada componente del Java Record indicando nombre, tipo de dato, regla JettraStore (@Id, @Required, @Unique, @Indexed, @JettraRef, etc.) y valor.",
                "font-size: 0.76rem; color: var(--text-muted); display: block; margin-bottom: 0.65rem;");

        return creatorCard("JAVA_RECORD", "#3b82f6", "💎 Nuevo Java Canonical Record (Campos, Tipos y Reglas JettraStore)",
                FluxUi.hidden("engineEditorMode", "JAVA_RECORD"),
                classRow,
                hint,
                fieldsTable);
    }

    private static Widget renderObjectCreator() {
        Widget topRow = Div.of(
                FluxUi.fieldColumn("Clase Java (className)", FluxUi.textInput("obj_className", "io.jettra.model.FiscalEntity", "io.jettra.model.Entity", true), null),
                FluxUi.fieldColumn("serialVersionUID", FluxUi.textInput("obj_serialVersion", "1", "1", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: 2fr 1fr; gap: 0.85rem; margin-bottom: 0.85rem;"));

        Widget propsGrid = Div.of(
                FluxUi.fieldColumn("entityId", FluxUi.textInput("prop_entityId", "ENT-202", "ENT-001", false), null),
                FluxUi.fieldColumn("taxCategory", FluxUi.textInput("prop_taxCategory", "SPECIAL_REGIME", "GENERAL", false), null),
                FluxUi.fieldColumn("auditState", FluxUi.textInput("prop_auditState", "SYNCHRONIZED", "ACTIVE", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0.75rem;"));

        return creatorCard("OBJECT", "#eab308", "🧩 Nuevo Objeto Java (POJO Serializado)",
                FluxUi.hidden("engineEditorMode", "OBJECT"),
                topRow,
                FluxUi.coloredSpan("Propiedades de la instancia:", "font-size:0.75rem; font-weight:700; color:var(--text-muted); display:block; margin-bottom:0.45rem;"),
                propsGrid);
    }

    private static Widget renderKeyValueCreator() {
        Widget grid = Div.of(
                FluxUi.fieldColumn("Clave (key)", FluxUi.textInput("prop_key", "BAL_ACC_4002", "KEY_001", true), null),
                FluxUi.fieldColumn("Valor / Balance (value)", FluxUi.textInput("prop_value", "125000.50", "valor", true), null),
                FluxUi.fieldColumn("Moneda / Categoría (currency)", FluxUi.textInput("prop_currency", "MXN", "USD / MXN", false), null),
                FluxUi.fieldColumn("TTL Segundos (ttl_sec)", FluxUi.textInput("prop_ttl_sec", "3600", "0 = sin expiración", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return creatorCard("KEY_VALUE", "#f97316", "🔑 Nuevo Registro Clave-Valor (Key/Value Store)",
                FluxUi.hidden("engineEditorMode", "KEY_VALUE"), grid);
    }

    private static Widget renderGraphCreator() {
        Widget grid = Div.of(
                FluxUi.fieldColumn("ID Arista (edge_id)", FluxUi.textInput("prop_edge_id", "EDGE-9901", "EDGE-001", true), null),
                FluxUi.fieldColumn("Nodo Origen (source)", FluxUi.textInput("prop_source", "P-1001", "Nodo origen", true), null),
                FluxUi.fieldColumn("Relación (rel)", FluxUi.textInput("prop_rel", "CONTACTO_FAMILIAR", "CONNECTED_TO", true), null),
                FluxUi.fieldColumn("Nodo Destino (target)", FluxUi.textInput("prop_target", "P-1002", "Nodo destino", true), null),
                FluxUi.fieldColumn("Peso (weight)", FluxUi.textInput("prop_weight", "1.0", "1.0", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return creatorCard("GRAPH", "#ec4899", "🕸️ Nueva Arista / Relación de Grafo",
                FluxUi.hidden("engineEditorMode", "GRAPH"), grid);
    }

    private static Widget renderVectorCreator() {
        Widget grid = Div.of(
                FluxUi.fieldColumn("ID Vector (id)", FluxUi.textInput("prop_id", "VEC-02", "VEC-001", true), null),
                FluxUi.fieldColumn("Modelo (model)", FluxUi.textInput("prop_model", "biomed-clip-v3", "embedding-model", true), null),
                FluxUi.fieldColumn("Dimensiones (dims)", FluxUi.textInput("prop_dims", "1536", "1536", true), null),
                FluxUi.fieldColumn("Etiqueta Semántica (etiqueta)", FluxUi.textInput("prop_etiqueta", "Muestra clínica", "Etiqueta", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem; margin-bottom: 0.75rem;"));

        return creatorCard("VECTOR", "#a855f7", "🧠 Nuevo Vector Embedding (HNSW)",
                FluxUi.hidden("engineEditorMode", "VECTOR"),
                grid,
                FluxUi.fieldColumn("Vector Embedding (floats separados por coma)",
                        FluxUi.textInput("vec_embedding", "0.012, -0.421, 0.881", "0.1, -0.2, 0.3", true), null));
    }

    private static Widget renderTimeseriesCreator() {
        Widget grid = Div.of(
                FluxUi.fieldColumn("Sensor ID (sensor_id)", FluxUi.textInput("prop_sensor_id", "SN-02-SUR", "SN-01", true), null),
                FluxUi.fieldColumn("CO2 ppm (co2_ppm)", FluxUi.textInput("prop_co2_ppm", "415.2", "400.0", false), null),
                FluxUi.fieldColumn("Temperatura °C (temp_c)", FluxUi.textInput("prop_temp_c", "23.8", "22.0", false), null),
                FluxUi.fieldColumn("Humedad % (humidity_pct)", FluxUi.textInput("prop_humidity_pct", "54.0", "50.0", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;"));

        return creatorCard("TIMESERIES", "#22c55e", "📈 Nuevo Punto de Serie Temporal (Telemetry / BRIN)",
                FluxUi.hidden("engineEditorMode", "TIMESERIES"), grid);
    }

    private static Widget renderGeospatialCreator() {
        Widget grid = Div.of(
                FluxUi.fieldColumn("Estación (station_id)", FluxUi.textInput("prop_station_id", "GEO-CDMX-02", "GEO-001", true), null),
                FluxUi.fieldColumn("Latitud WGS84 (lat)", FluxUi.textInput("prop_lat", "19.4326", "0.0", true), null),
                FluxUi.fieldColumn("Longitud WGS84 (lng)", FluxUi.textInput("prop_lng", "-99.1332", "0.0", true), null),
                FluxUi.fieldColumn("Altitud m (altitud_m)", FluxUi.textInput("prop_altitud_m", "2240", "0", false), null),
                FluxUi.fieldColumn("Zona (zona)", FluxUi.textInput("prop_zona", "Metropolitana Central", "Zona", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;"));

        return creatorCard("GEOSPATIAL", "#06b6d4", "🌍 Nuevo Punto Geoespacial (GeoJSON & R-Tree)",
                FluxUi.hidden("engineEditorMode", "GEOSPATIAL"), grid);
    }

    private static Widget renderTabularCreator(String engine) {
        String accent = "COLUMNAR".equals(engine) ? "#38bdf8" : "#ffd700";
        String title = "COLUMNAR".equals(engine)
                ? "📊 Nuevo Registro Columnar (Columnas / Campos)"
                : "🗃️ Nuevo Registro Relacional (Columnas / Campos)";
        Widget grid = Div.of(
                FluxUi.fieldColumn("cliente_id", FluxUi.textInput("prop_cliente_id", "1002", "1001", false), null),
                FluxUi.fieldColumn("razon_social", FluxUi.textInput("prop_razon_social", "Comercializadora Pacífico", "Razón social", false), null),
                FluxUi.fieldColumn("rfc", FluxUi.textInput("prop_rfc", "CPA150202TT9", "RFC", false), null),
                FluxUi.fieldColumn("ciudad", FluxUi.textInput("prop_ciudad", "Guadalajara", "Ciudad", false), null),
                FluxUi.fieldColumn("limite_credito", FluxUi.textInput("prop_limite_credito", "350000.00", "0.00", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return creatorCard(engine, accent, title,
                FluxUi.hidden("engineEditorMode", engine), grid);
    }

    private static Widget creatorCard(String engine, String accent, String title, Widget... children) {
        List<Widget> all = new ArrayList<>();
        all.add(FluxUi.coloredSpan(title, "font-size: 0.8rem; font-weight: 700; color: " + accent + "; display: block; margin-bottom: 0.75rem;"));
        for (Widget w : children) {
            all.add(w);
        }
        Widget box = Div.of(all.toArray(new Widget[0]));
        box.modifier(new Modifier()
                .attribute("data-engine-creator", engine)
                .attribute("data-engine-editor", engine)
                .style("margin-bottom: 1rem; padding: 0.9rem; border: 1px solid " + accent + "; border-radius: 8px;"));
        return box;
    }

    // =========================================================================
    // EDITOR POR TIPO DE MOTOR (EDITAR)
    // =========================================================================

    public static Widget renderEditFieldsWidget(EngineRecordInfo rec) {
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

    private static Widget renderDocumentEditor(EngineRecordInfo rec) {
        Widget box = Div.of(
                FluxUi.coloredSpan("📄 Editor de Documento (JSON / BSON)",
                        "font-size: 0.78rem; font-weight: 700; color: #00d4ff; display: block; margin-bottom: 0.35rem;"),
                FluxUi.textArea("details", rec.getDetails(), 8, true, null)
        );
        box.modifier(new Modifier().attribute("data-engine-editor", "DOCUMENT").style("margin-bottom: 1rem;"));
        return box;
    }

    @SuppressWarnings("unchecked")
    private static Widget renderObjectEditor(Map<String, Object> data) {
        String className = strVal(data.get("className"), "io.jettra.model.Entity");
        String serialVersion = strVal(data.get("serialVersion"), "1");
        Map<String, Object> props = (data.get("properties") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        Widget topRow = Div.of(
                FluxUi.fieldColumn("Clase Java (className)", FluxUi.textInput("obj_className", className, "", false), null),
                FluxUi.fieldColumn("serialVersionUID", FluxUi.textInput("obj_serialVersion", serialVersion, "", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: 2fr 1fr; gap: 0.85rem; margin-bottom: 0.85rem;"));

        List<Widget> propFields = new ArrayList<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            propFields.add(FluxUi.fieldColumn(e.getKey(), FluxUi.textInput("prop_" + e.getKey(), String.valueOf(e.getValue()), "", false), null));
        }
        Widget propsGrid = Div.of(propFields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0.75rem;"));

        return editorCard("OBJECT", "#eab308", "🧩 Editor de Objeto Java (POJO)",
                FluxUi.hidden("engineEditorMode", "OBJECT"),
                topRow,
                FluxUi.coloredSpan("Propiedades de la instancia:", "font-size:0.75rem; font-weight:700; color:var(--text-muted); display:block; margin-bottom:0.45rem;"),
                propsGrid);
    }

    @SuppressWarnings("unchecked")
    private static Widget renderJavaRecordEditor(Map<String, Object> data) {
        String recordClass = strVal(data.get("recordClass"), "io.jettra.store.domain.Record");
        Map<String, Object> components = (data.get("components") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : data;

        List<Widget> compFields = new ArrayList<>();
        for (Map.Entry<String, Object> e : components.entrySet()) {
            compFields.add(FluxUi.fieldColumn(e.getKey(), FluxUi.textInput("comp_" + e.getKey(), String.valueOf(e.getValue()), "", false), null));
        }
        Widget compsGrid = Div.of(compFields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return editorCard("JAVA_RECORD", "#3b82f6", "💎 Editor de Java Canonical Record (Nueva versión inmutable)",
                FluxUi.hidden("engineEditorMode", "JAVA_RECORD"),
                Div.of(FluxUi.fieldColumn("Record Class", FluxUi.textInput("rec_recordClass", recordClass, "", false), null))
                        .modifier(new Modifier().style("margin-bottom: 0.85rem;")),
                compsGrid);
    }

    private static Widget renderGraphEditor(EngineRecordInfo rec, Map<String, Object> data) {
        List<Widget> fields = new ArrayList<>();
        fields.add(FluxUi.fieldColumn("ID Arista (edge_id)", FluxUi.textInput("prop_edge_id", strVal(data.get("edge_id"), rec.getId()), "", false), null));
        fields.add(FluxUi.fieldColumn("Nodo Origen (source)", FluxUi.textInput("prop_source", strVal(data.get("source"), ""), "", false), null));
        fields.add(FluxUi.fieldColumn("Relación (rel)", FluxUi.textInput("prop_rel", strVal(data.get("rel"), ""), "", false), null));
        fields.add(FluxUi.fieldColumn("Nodo Destino (target)", FluxUi.textInput("prop_target", strVal(data.get("target"), ""), "", false), null));
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String k = e.getKey();
            if ("edge_id".equals(k) || "source".equals(k) || "rel".equals(k) || "target".equals(k)) continue;
            fields.add(FluxUi.fieldColumn(k, FluxUi.textInput("prop_" + k, String.valueOf(e.getValue()), "", false), null));
        }
        Widget grid = Div.of(fields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return editorCard("GRAPH", "#ec4899", "🕸️ Editor de Arista de Grafo (Nodos y Relación)",
                FluxUi.hidden("engineEditorMode", "GRAPH"), grid);
    }

    private static Widget renderKeyValueEditor(EngineRecordInfo rec, Map<String, Object> data) {
        List<Widget> fields = new ArrayList<>();
        if (!data.containsKey("key") && !data.containsKey("account")) {
            fields.add(FluxUi.fieldColumn("key", FluxUi.textInput("prop_key", rec.getId(), "", false), null));
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            fields.add(FluxUi.fieldColumn(e.getKey(), FluxUi.textInput("prop_" + e.getKey(), String.valueOf(e.getValue()), "", false), null));
        }
        Widget grid = Div.of(fields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return editorCard("KEY_VALUE", "#f97316", "🔑 Editor Clave-Valor",
                FluxUi.hidden("engineEditorMode", "KEY_VALUE"), grid);
    }

    private static Widget renderGeospatialEditor(EngineRecordInfo rec, Map<String, Object> data) {
        Widget grid = Div.of(
                FluxUi.fieldColumn("Estación (station_id)", FluxUi.textInput("prop_station_id", strVal(data.get("station_id"), rec.getId()), "", false), null),
                FluxUi.fieldColumn("Latitud (lat)", FluxUi.textInput("prop_lat", strVal(data.get("lat"), "0.0"), "", false), null),
                FluxUi.fieldColumn("Longitud (lng)", FluxUi.textInput("prop_lng", strVal(data.get("lng"), "0.0"), "", false), null),
                FluxUi.fieldColumn("Altitud m (altitud_m)", FluxUi.textInput("prop_altitud_m", strVal(data.getOrDefault("altitud_m", data.get("altitud")), "0"), "", false), null),
                FluxUi.fieldColumn("Zona", FluxUi.textInput("prop_zona", strVal(data.get("zona"), ""), "", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;"));

        return editorCard("GEOSPATIAL", "#06b6d4", "🌍 Editor Geoespacial (Coordenadas WGS84 & Metadatos)",
                FluxUi.hidden("engineEditorMode", "GEOSPATIAL"), grid);
    }

    private static Widget renderVectorEditor(EngineRecordInfo rec, Map<String, Object> data) {
        Object embObj = data.getOrDefault("embedding_preview", data.get("embedding"));
        String embStr = "";
        if (embObj instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(String.valueOf(o));
            embStr = String.join(", ", parts);
        } else if (embObj != null) {
            embStr = String.valueOf(embObj);
        }

        Widget grid = Div.of(
                FluxUi.fieldColumn("ID Vector", FluxUi.textInput("prop_id", strVal(data.get("id"), rec.getId()), "", false), null),
                FluxUi.fieldColumn("Modelo", FluxUi.textInput("prop_model", strVal(data.get("model"), "biomed-clip-v3"), "", false), null),
                FluxUi.fieldColumn("Dimensiones", FluxUi.textInput("prop_dims", strVal(data.get("dims"), "1536"), "", false), null),
                FluxUi.fieldColumn("Etiqueta", FluxUi.textInput("prop_etiqueta", strVal(data.get("etiqueta"), ""), "", false), null)
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem; margin-bottom: 0.75rem;"));

        return editorCard("VECTOR", "#a855f7", "🧠 Editor de Vector Embedding (HNSW)",
                FluxUi.hidden("engineEditorMode", "VECTOR"),
                grid,
                FluxUi.fieldColumn("Vector Embedding (floats separados por coma)", FluxUi.textInput("vec_embedding", embStr, "", false), null));
    }

    private static Widget renderTimeseriesEditor(Map<String, Object> data) {
        List<Widget> fields = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            fields.add(FluxUi.fieldColumn(e.getKey(), FluxUi.textInput("prop_" + e.getKey(), String.valueOf(e.getValue()), "", false), null));
        }
        Widget grid = Div.of(fields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.75rem;"));

        return editorCard("TIMESERIES", "#22c55e", "📈 Editor de Serie Temporal",
                FluxUi.hidden("engineEditorMode", "TIMESERIES"), grid);
    }

    private static Widget renderTabularEditor(String engine, Map<String, Object> data) {
        String accent = "COLUMNAR".equals(engine) ? "#38bdf8" : "#ffd700";
        List<Widget> fields = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            fields.add(FluxUi.fieldColumn(e.getKey(), FluxUi.textInput("prop_" + e.getKey(), String.valueOf(e.getValue()), "", false), null));
        }
        Widget grid = Div.of(fields.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem;"));

        return editorCard(engine, accent, "📊 Editor " + engine + " (Columnas / Campos)",
                FluxUi.hidden("engineEditorMode", engine), grid);
    }

    private static Widget editorCard(String engine, String accent, String title, Widget... children) {
        List<Widget> all = new ArrayList<>();
        all.add(FluxUi.coloredSpan(title, "font-size: 0.8rem; font-weight: 700; color: " + accent + "; display: block; margin-bottom: 0.75rem;"));
        for (Widget w : children) {
            all.add(w);
        }
        Widget box = Div.of(all.toArray(new Widget[0]));
        box.modifier(new Modifier().attribute("data-engine-editor", engine)
                .style("margin-bottom: 1rem; padding: 0.9rem; border: 1px solid " + accent + "; border-radius: 8px;"));
        return box;
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
            Map<String, Object> schema = new LinkedHashMap<>();
            List<Integer> fieldIndices = new ArrayList<>();
            for (String key : form.keySet()) {
                if (key.startsWith("rec_field_name_")) {
                    try {
                        fieldIndices.add(Integer.parseInt(key.substring("rec_field_name_".length())));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!fieldIndices.isEmpty()) {
                fieldIndices.sort(Integer::compareTo);
                for (int idx : fieldIndices) {
                    String fName = form.get("rec_field_name_" + idx);
                    if (fName == null || fName.isBlank()) continue;
                    String cleanName = fName.trim();
                    String fType = form.getOrDefault("rec_field_type_" + idx, "String").trim();
                    String fRule = form.getOrDefault("rec_field_rule_" + idx, "NONE").trim();
                    String fVal = form.getOrDefault("rec_field_val_" + idx, "");
                    comps.put(cleanName, parseTypedScalar(fVal, fType));
                    Map<String, Object> fieldMeta = new LinkedHashMap<>();
                    fieldMeta.put("type", fType);
                    fieldMeta.put("rule", fRule);
                    schema.put(cleanName, fieldMeta);
                }
                result.put("components", comps);
                if (!schema.isEmpty()) {
                    result.put("schema", schema);
                }
            } else {
                collectPrefixed(form, "comp_", comps);
                result.put("components", comps);
            }
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

    private static Object parseTypedScalar(String raw, String typeName) {
        if (raw == null) return "";
        String t = raw.trim();
        if (typeName == null || typeName.isBlank()) return parseScalar(raw);
        return switch (typeName.trim()) {
            case "Long", "Integer" -> {
                try { yield Long.parseLong(t); } catch (NumberFormatException e) { yield raw; }
            }
            case "Double", "BigDecimal" -> {
                try { yield Double.parseDouble(t); } catch (NumberFormatException e) { yield raw; }
            }
            case "Boolean" -> Boolean.parseBoolean(t);
            default -> raw;
        };
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
    // UTILIDADES WIDGETS
    // =========================================================================

    private static Widget renderKeyValueGrid(Map<String, Object> data, String accent) {
        List<Widget> cells = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            Widget cell = Div.of(
                    FluxUi.coloredSpan(e.getKey(), "font-size:0.72rem; color:var(--text-muted); font-weight:700; display:block;"),
                    FluxUi.coloredSpan(String.valueOf(e.getValue()), "font-size:0.86rem; color:var(--text-main); font-weight:600; word-break:break-word; display:block;")
            );
            cell.modifier(new Modifier().style("background:var(--bg-subtle); border-left:3px solid " + accent + "; border-radius:6px; padding:0.5rem 0.7rem;"));
            cells.add(cell);
        }
        Widget grid = Div.of(cells.toArray(new Widget[0]));
        grid.modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap:0.6rem;"));
        return grid;
    }

    private static Widget metricBox(String label, String value, String color) {
        Widget box = Div.of(
                FluxUi.coloredSpan(label, "font-size:0.72rem; color:var(--text-muted); font-weight:700; display:block;"),
                FluxUi.coloredSpan(value, "font-size:0.95rem; color:var(--text-main); font-weight:800; margin-top:0.2rem; display:block;")
        );
        box.modifier(new Modifier().style("background:var(--bg-subtle); border:1px solid var(--border-subtle); border-top:3px solid " + color + "; border-radius:8px; padding:0.6rem 0.8rem;"));
        return box;
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
}

package io.jettra.store.explorer.pages.explorer;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.EngineRecordVersion;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.List;

/**
 * Explorador de Registros con soporte completo de operaciones CRUD en JettraFlux.
 */
public class RecordExplorerFluxView extends FluxView {

    private final String activeDb;
    private final String activeBucket;
    private final String searchQuery;
    private final String editRecordId;
    private final String viewRecordId;
    private final String historyRecordId;
    private final boolean showCreateForm;
    private final String message;

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, String historyRecordId,
                                  boolean showCreateForm, String message) {
        this.activeDb = activeDb;
        this.activeBucket = activeBucket;
        this.searchQuery = searchQuery != null ? searchQuery : "";
        this.editRecordId = editRecordId;
        this.viewRecordId = viewRecordId;
        this.historyRecordId = historyRecordId;
        this.showCreateForm = showCreateForm;
        this.message = message;
    }

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, boolean showCreateForm, String message) {
        this(activeDb, activeBucket, searchQuery, editRecordId, viewRecordId, null, showCreateForm, message);
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<String> dbs = plugin.getDatabaseNames();
        String currentDb = (activeDb != null && plugin.getDatabasesCatalog().containsKey(activeDb))
                ? activeDb
                : (dbs.isEmpty() ? "example_factura_db" : dbs.get(0));

        List<EngineBucketInfo> buckets = plugin.getBuckets(currentDb);
        String rawBucket = activeBucket;
        String currentBucket = (rawBucket != null && buckets.stream().anyMatch(b -> b.getBucketName().equalsIgnoreCase(rawBucket)))
                ? rawBucket
                : (buckets.isEmpty() ? "Facturas" : buckets.get(0).getBucketName());

        EngineBucketInfo bucketInfo = plugin.getBucket(currentDb, currentBucket);
        List<EngineRecordInfo> records;
        if (searchQuery != null && !searchQuery.isBlank()) {
            JettraStorePlugin.QueryResult qr = plugin.executeQuery(currentDb, currentBucket, searchQuery, false);
            records = qr.records();
        } else {
            records = (bucketInfo != null) ? bucketInfo.getSampleRecords() : List.of();
        }

        // 1. Notificación Flash si existe
        Widget flashWidget = null;
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

        // 2. Selectores Superiores de Base de Datos y de Buckets
        Widget dbSelector = buildDbSelector(dbs, currentDb);
        Widget bucketSelector = buildBucketSelector(buckets, currentDb, currentBucket);

        // 3. Barra de Búsqueda y Botón "+ Nuevo Registro"
        Widget searchAndActions = buildSearchToolbar(currentDb, currentBucket, searchQuery, records.size());

        // 4. Panel Formulario Crear / Editar Registro si está activo
        Widget formPanel = null;
        if (showCreateForm) {
            formPanel = buildCreateRecordForm(currentDb, currentBucket, bucketInfo != null ? bucketInfo.getEngineType() : "DOCUMENT");
        } else if (editRecordId != null && !editRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, editRecordId);
            if (rec != null) {
                formPanel = buildEditRecordForm(currentDb, currentBucket, rec);
            }
        } else if (viewRecordId != null && !viewRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, viewRecordId);
            if (rec != null) {
                formPanel = buildViewRecordModal(currentDb, currentBucket, rec);
            }
        } else if (historyRecordId != null && !historyRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, historyRecordId);
            if (rec != null) {
                formPanel = buildVersionHistoryPanel(currentDb, currentBucket, rec);
            }
        }

        // 5. Tabla de Registros CRUD
        Widget recordsTable = buildRecordsTable(records, currentDb, currentBucket);

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(dbSelector);
        rootChildren.add(bucketSelector);
        rootChildren.add(searchAndActions);
        if (formPanel != null) rootChildren.add(formPanel);
        rootChildren.add(recordsTable);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Widget buildDbSelector(List<String> dbs, String currentDb) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; gap: 0.5rem; margin-bottom: 1rem; overflow-x: auto; padding-bottom: 4px;'>");
        for (String db : dbs) {
            boolean active = db.equalsIgnoreCase(currentDb);
            String style = active
                ? "background: #00d4ff; color: #050b14; font-weight: 700; border: 1px solid #00d4ff;"
                : "background: var(--bg-card); color: var(--text-muted); border: 1px solid var(--border-subtle);";
            sb.append("<a href='/explorer?db=").append(db).append("' style='text-decoration: none; padding: 0.45rem 1rem; border-radius: 8px; font-size: 0.85rem; display: inline-flex; align-items: center; gap: 0.35rem; ").append(style).append("'>")
              .append("🗄️ ").append(db).append("</a>");
        }
        sb.append("</div>");
        return RawHtml.of(sb.toString());
    }

    private Widget buildBucketSelector(List<EngineBucketInfo> buckets, String currentDb, String currentBucket) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; gap: 0.5rem; margin-bottom: 1.5rem; flex-wrap: wrap;'>");
        for (EngineBucketInfo b : buckets) {
            boolean active = b.getBucketName().equalsIgnoreCase(currentBucket);
            String engineColor = switch (b.getEngineType()) {
                case "DOCUMENT" -> "#00d4ff";
                case "RELATIONAL" -> "#ffd700";
                case "GRAPH" -> "#ec4899";
                case "VECTOR" -> "#a855f7";
                case "TIMESERIES" -> "#22c55e";
                case "KEY_VALUE" -> "#f97316";
                case "COLUMNAR" -> "#38bdf8";
                case "GEOSPATIAL" -> "#06b6d4";
                case "JAVA_RECORD" -> "#3b82f6";
                case "OBJECT" -> "#eab308";
                default -> "#94a3b8";
            };

            String border = active ? "border: 2px solid " + engineColor + ";" : "border: 1px solid var(--border-subtle);";
            String bg = active ? "background: rgba(0, 212, 255, 0.12);" : "background: var(--bg-subtle);";

            sb.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(b.getBucketName())
              .append("' style='text-decoration: none; padding: 0.6rem 1.1rem; border-radius: 10px; display: inline-flex; align-items: center; gap: 0.6rem; ")
              .append(bg).append(border).append("'>")
              .append("<span style='background: ").append(engineColor).append("; color: #050b14; font-size: 0.7rem; font-weight: 800; padding: 0.15rem 0.45rem; border-radius: 9999px;'>").append(b.getEngineType()).append("</span>")
              .append("<strong style='color: var(--text-main); font-size: 0.9rem;'>").append(b.getBucketName()).append("</strong>")
              .append("<span style='color: var(--text-muted); font-size: 0.78rem;'>(").append(String.format("%,d", b.getTotalObjects())).append(")</span>")
              .append("</a>");
        }
        sb.append("</div>");
        return RawHtml.of(sb.toString());
    }

    private Widget buildSearchToolbar(String currentDb, String currentBucket, String query, int count) {
        RawHtml html = RawHtml.of(String.format("""
            <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1.25rem;">
              <form method="GET" action="/explorer" style="display: flex; gap: 0.5rem; flex: 1; max-width: 520px;">
                <input type="hidden" name="db" value="%s" />
                <input type="hidden" name="bucket" value="%s" />
                <input class="app-input" type="text" name="q" value="%s" placeholder="🔍 Buscar por ID, texto o valor JSON..." />
                <button type="submit" class="app-btn-secondary">Buscar</button>
                %s
              </form>
              <div style="display: flex; align-items: center; gap: 0.75rem;">
                <span style="font-size: 0.85rem; color: var(--text-muted); font-weight: 600;">%d registros encontrados</span>
                <a href="/explorer?db=%s&bucket=%s&action=new" class="app-btn-primary">➕ Nuevo Registro (Create)</a>
              </div>
            </div>
        """, currentDb, currentBucket, escapeHtml(query),
             (query != null && !query.isBlank() ? "<a href='/explorer?db=" + currentDb + "&bucket=" + currentBucket + "' class='app-btn-secondary'>Limpiar</a>" : ""),
             count, currentDb, currentBucket));

        return html;
    }

    private Widget buildCreateRecordForm(String currentDb, String currentBucket, String engineType) {
        Header header = Header.of(3, "➕ Crear Nuevo Registro en [" + currentDb + " / " + currentBucket + "]");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0 0 1rem 0;"));

        String sampleJson = switch (engineType) {
            case "RELATIONAL" -> "{\n  \"cliente_id\": 1002,\n  \"razon_social\": \"Comercializadora Pacífico\",\n  \"rfc\": \"CPA150202TT9\",\n  \"ciudad\": \"Guadalajara\",\n  \"limite_credito\": 350000.00\n}";
            case "KEY_VALUE" -> "{\n  \"key\": \"BAL_ACC_4002\",\n  \"currency\": \"MXN\",\n  \"balance\": 125000.50\n}";
            case "GRAPH" -> "{\n  \"edge_id\": \"EDGE-9901\",\n  \"source\": \"P-1001\",\n  \"target\": \"P-1002\",\n  \"rel\": \"CONTACTO_FAMILIAR\"\n}";
            case "VECTOR" -> "{\n  \"id\": \"VEC-02\",\n  \"dims\": 1536,\n  \"embedding\": [0.012, -0.421, 0.881]\n}";
            case "TIMESERIES" -> "{\n  \"sensor_id\": \"SN-02-SUR\",\n  \"co2_ppm\": 415.2,\n  \"temp_c\": 23.8\n}";
            case "GEOSPATIAL" -> "{\n  \"station_id\": \"GEO-CDMX-02\",\n  \"lat\": 19.4326,\n  \"lng\": -99.1332,\n  \"altitud\": 2240,\n  \"zona\": \"Metropolitana Central\"\n}";
            case "JAVA_RECORD" -> "{\n  \"recordClass\": \"io.jettra.store.domain.OrderSummary\",\n  \"components\": {\n    \"orderId\": 502,\n    \"clientName\": \"Comercializadora\",\n    \"total\": 8500.00,\n    \"status\": \"APPROVED\"\n  }\n}";
            case "OBJECT" -> "{\n  \"className\": \"io.jettra.model.FiscalEntity\",\n  \"serialVersion\": 1,\n  \"properties\": {\n    \"entityId\": \"ENT-202\",\n    \"taxCategory\": \"SPECIAL_REGIME\",\n    \"auditState\": \"SYNCHRONIZED\"\n  }\n}";
            default -> "{\n  \"folio\": \"FAC-2026-004\",\n  \"emisor\": \"Servicios Digitales SA\",\n  \"receptor\": \"Cliente Ejemplo\",\n  \"total\": 1500.00,\n  \"estado\": \"PAGADO\"\n}";
        };

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="POST" action="/explorer">
              <input type="hidden" name="action" value="save_new" />
              <input type="hidden" name="db" value="%s" />
              <input type="hidden" name="bucket" value="%s" />
              
              <div style="display: grid; grid-template-columns: 1fr 2fr; gap: 1rem; margin-bottom: 1rem;">
                <div>
                  <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Identificador Único (ID / Clave Primaria)</label>
                  <input class="app-input" type="text" name="recordId" placeholder="REG-2026-001" required />
                </div>
                <div>
                  <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Descripción / Resumen Corto</label>
                  <input class="app-input" type="text" name="summary" placeholder="Descripción breve del documento o entidad" required />
                </div>
              </div>

              <div style="margin-bottom: 1rem;">
                <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Contenido del Registro (Payload JSON / BSON Multimodelo)</label>
                <textarea class="app-input" name="details" rows="8" style="font-family: monospace; font-size: 0.85rem;" required>%s</textarea>
              </div>

              <div style="display: flex; gap: 0.75rem; justify-content: flex-end;">
                <a href="/explorer?db=%s&bucket=%s" class="app-btn-secondary">Cancelar</a>
                <button type="submit" class="app-btn-primary">💾 Guardar Registro</button>
              </div>
            </form>
        """, currentDb, currentBucket, escapeHtml(sampleJson), currentDb, currentBucket));

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border-left: 4px solid #00d4ff; background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildEditRecordForm(String currentDb, String currentBucket, EngineRecordInfo rec) {
        Header header = Header.of(3, "✏️ Modificar Registro [" + rec.getId() + "] (v" + rec.getVersion() + ")");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #ffd700; margin: 0 0 1rem 0;"));

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="POST" action="/explorer">
              <input type="hidden" name="action" value="save_edit" />
              <input type="hidden" name="db" value="%s" />
              <input type="hidden" name="bucket" value="%s" />
              <input type="hidden" name="recordId" value="%s" />

              <div style="display: grid; grid-template-columns: 1fr 2fr; gap: 1rem; margin-bottom: 1rem;">
                <div>
                  <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Identificador (Solo Lectura)</label>
                  <input class="app-input" type="text" value="%s" disabled style="opacity: 0.7;" />
                </div>
                <div>
                  <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Descripción / Resumen Corto</label>
                  <input class="app-input" type="text" name="summary" value="%s" required />
                </div>
              </div>

              <div style="margin-bottom: 1rem;">
                <label style="font-size: 0.78rem; font-weight: 700; color: var(--text-muted); display: block; margin-bottom: 0.35rem;">Contenido del Registro (Payload JSON / BSON)</label>
                <textarea class="app-input" name="details" rows="8" style="font-family: monospace; font-size: 0.85rem;" required>%s</textarea>
              </div>

              <div style="display: flex; gap: 0.75rem; justify-content: flex-end;">
                <a href="/explorer?db=%s&bucket=%s" class="app-btn-secondary">Cancelar</a>
                <button type="submit" class="app-btn-primary" style="background:#ffd700; color:#050b14;">💾 Actualizar Registro</button>
              </div>
            </form>
        """, currentDb, currentBucket, rec.getId(), rec.getId(), escapeHtml(rec.getSummary()), escapeHtml(rec.getDetails()), currentDb, currentBucket));

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border-left: 4px solid #ffd700; background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildViewRecordModal(String currentDb, String currentBucket, EngineRecordInfo rec) {
        Header header = Header.of(3, "👁️ Inspección de Registro [" + rec.getId() + "] • " + rec.getEngineType());
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0 0 0.85rem 0;"));

        Paragraph meta = Paragraph.of("Versión: v" + rec.getVersion() + " • Última modificación: " + rec.getTimestamp() + " • Bucket: " + rec.getBucketName());
        meta.modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin: 0 0 1rem 0;"));

        RawHtml viewHtml = RawHtml.of(String.format("""
            <div style="background: #020617; border: 1px solid var(--border-subtle); border-radius: 8px; padding: 1.25rem; margin-bottom: 1rem; overflow-x: auto;">
              <pre style="margin: 0; font-family: 'JetBrains Mono', Consolas, monospace; font-size: 0.88rem; color: #38bdf8; line-height: 1.5;">%s</pre>
            </div>
            <div style="display: flex; justify-content: flex-end; gap: 0.5rem;">
              <a href="/explorer?db=%s&bucket=%s&edit_id=%s" class="app-btn-secondary">✏️ Editar Este Registro</a>
              <a href="/explorer?db=%s&bucket=%s" class="app-btn-primary">Cerrar Visor</a>
            </div>
        """, escapeHtml(rec.getDetails()), currentDb, currentBucket, rec.getId(), currentDb, currentBucket));

        Div card = Div.of(header, meta, viewHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border: 2px solid #00d4ff; background: var(--bg-card);"));
        return card;
    }

    private Widget buildRecordsTable(List<EngineRecordInfo> records, String currentDb, String currentBucket) {
        if (records.isEmpty()) {
            return Div.of(
                Header.of(4, "No hay registros disponibles en este bucket.").modifier(new Modifier().style("color: var(--text-muted); margin: 2rem 0; text-align: center;"))
            );
        }

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>ID</th><th>Motor</th><th>Resumen</th><th>Versión</th><th>Fecha Mutación</th><th>Acciones</th></tr></thead>");
        table.append("<tbody>");

        for (EngineRecordInfo r : records) {
            String engineColor = switch (r.getEngineType()) {
                case "DOCUMENT" -> "#00d4ff";
                case "RELATIONAL" -> "#ffd700";
                case "GRAPH" -> "#ec4899";
                case "VECTOR" -> "#a855f7";
                case "TIMESERIES" -> "#22c55e";
                case "KEY_VALUE" -> "#f97316";
                case "COLUMNAR" -> "#38bdf8";
                case "GEOSPATIAL" -> "#06b6d4";
                case "JAVA_RECORD" -> "#3b82f6";
                case "OBJECT" -> "#eab308";
                default -> "#94a3b8";
            };

            table.append("<tr>");
            table.append("<td><code style='color:#00d4ff; font-weight:700;'>").append(r.getId()).append("</code></td>");
            table.append("<td><span style='background:rgba(255,255,255,0.06); border:1px solid ").append(engineColor)
                 .append("; color:").append(engineColor).append("; padding:2px 6px; border-radius:9999px; font-weight:700; font-size:0.72rem;'>")
                 .append(r.getEngineType()).append("</span></td>");
            table.append("<td><strong>").append(escapeHtml(r.getSummary())).append("</strong></td>");
            table.append("<td><span style='color:#ffd700; font-weight:600;'>v").append(r.getVersion()).append("</span></td>");
            table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(r.getTimestamp()).append("</td>");
            table.append("<td>");
            table.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(currentBucket).append("&view_id=").append(r.getId()).append("' class='app-btn-secondary' style='margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem;'>👁️ Ver</a>");
            table.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(currentBucket).append("&edit_id=").append(r.getId()).append("' class='app-btn-secondary' style='margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem;'>✏️ Editar</a>");
            table.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(currentBucket).append("&history_id=").append(r.getId()).append("' class='app-btn-secondary' style='margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem; background:rgba(16,185,129,0.15); border-color:#10b981; color:#10b981;'>🕒 Versiones</a>");
            table.append("<a href='/explorer?action=delete_record&db=").append(currentDb).append("&bucket=").append(currentBucket).append("&recordId=").append(r.getId()).append("' class='app-btn-danger' style='padding:0.3rem 0.6rem; font-size:0.78rem;' onclick=\"return confirm('¿Seguro de eliminar el registro ").append(r.getId()).append("?');\">🗑️</a>");
            table.append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(RawHtml.of(table.toString()));
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildVersionHistoryPanel(String currentDb, String currentBucket, EngineRecordInfo rec) {
        Header header = Header.of(3, "🕒 Historial de Versiones del Registro [" + rec.getId() + "] • " + rec.getEngineType());
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #10b981; margin: 0 0 0.5rem 0;"));

        Paragraph sub = Paragraph.of("Versión activa actual: v" + rec.getVersion() + " • Total versiones registradas: " + rec.getVersionHistory().size() + " • Bucket: " + rec.getBucketName());
        sub.modifier(new Modifier().style("font-size: 0.85rem; color: var(--text-muted); margin: 0 0 1.25rem 0;"));

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; flex-direction: column; gap: 1rem; margin-bottom: 1.25rem;'>");

        for (EngineRecordVersion v : rec.getVersionHistory()) {
            boolean isCurrent = (v.getVersion() == rec.getVersion());
            String borderColor = isCurrent ? "#10b981" : "var(--border-subtle)";
            String badgeBg = isCurrent ? "rgba(16, 185, 129, 0.2)" : "rgba(255, 255, 255, 0.08)";
            String badgeColor = isCurrent ? "#10b981" : "#94a3b8";

            sb.append("<div style='border: 1px solid ").append(borderColor).append("; background: var(--bg-card); border-radius: 8px; padding: 1rem; position: relative;'>");
            sb.append("<div style='display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;'>");
            sb.append("  <div style='display: flex; align-items: center; gap: 0.6rem;'>");
            sb.append("    <span style='background: ").append(badgeBg).append("; color: ").append(badgeColor).append("; font-weight: 800; font-size: 0.85rem; padding: 2px 8px; border-radius: 6px; border: 1px solid ").append(badgeColor).append(";'>v").append(v.getVersion()).append("</span>");
            if (isCurrent) {
                sb.append("    <span style='color: #10b981; font-size: 0.75rem; font-weight: 700;'>[VERSIÓN ACTIVA EN MEMORIA]</span>");
            }
            sb.append("    <strong style='color: var(--text-main); font-size: 0.92rem;'>").append(escapeHtml(v.getSummary())).append("</strong>");
            sb.append("  </div>");
            sb.append("  <div style='font-size: 0.78rem; color: var(--text-muted);'>");
            sb.append("    <span>👤 ").append(escapeHtml(v.getAuthor())).append("</span> • <span>🕒 ").append(v.getTimestamp()).append("</span>");
            sb.append("  </div>");
            sb.append("</div>");

            if (v.getChangeNote() != null && !v.getChangeNote().isBlank()) {
                sb.append("<div style='font-size: 0.8rem; color: #38bdf8; margin-bottom: 0.6rem;'>📝 <em>").append(escapeHtml(v.getChangeNote())).append("</em></div>");
            }

            sb.append("<div style='background: #020617; border: 1px solid var(--border-subtle); border-radius: 6px; padding: 0.75rem; margin-bottom: 0.75rem; max-height: 160px; overflow-y: auto;'>");
            sb.append("<pre style='margin: 0; font-family: monospace; font-size: 0.82rem; color: #93c5fd; white-space: pre-wrap;'>").append(escapeHtml(v.getDetails())).append("</pre>");
            sb.append("</div>");

            sb.append("<div style='display: flex; justify-content: flex-end;'>");
            if (!isCurrent) {
                long nextVersion = Math.max(rec.getVersion(), v.getVersion()) + 1L;
                sb.append("  <button type='button' class='app-btn-primary' style='background: #10b981; color: #050b14; font-weight: 700; padding: 0.35rem 0.85rem; font-size: 0.8rem;'")
                  .append(" data-db='").append(escapeHtml(currentDb)).append("'")
                  .append(" data-bucket='").append(escapeHtml(currentBucket)).append("'")
                  .append(" data-record-id='").append(escapeHtml(rec.getId())).append("'")
                  .append(" data-version='").append(v.getVersion()).append("'")
                  .append(" data-next-version='").append(nextVersion).append("'")
                  .append(" data-summary='").append(escapeHtml(v.getSummary())).append("'")
                  .append(" onclick=\"openRestoreModalFromBtn(this)\">♻️ Restaurar esta Versión (v").append(v.getVersion()).append(")</button>");
            } else {
                sb.append("<span style='font-size: 0.8rem; color: #10b981; font-weight: 600;'>✓ Estado Vigente</span>");
            }
            sb.append("</div>");

            sb.append("</div>");
        }

        sb.append("</div>");

        sb.append("<div style='display: flex; justify-content: flex-end;'>");
        sb.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(currentBucket).append("' class='app-btn-secondary'>Cerrar Historial</a>");
        sb.append("</div>");

        // Formulario POST oculto conectado al botón de confirmación del Modal JettraFlux
        RawHtml hiddenFormAndScript = RawHtml.of(String.format("""
            <form id="restoreVersionForm" method="POST" action="/explorer" style="display:none;">
              <input type="hidden" name="action" value="restore_version" />
              <input type="hidden" id="restoreDbInput" name="db" value="%s" />
              <input type="hidden" id="restoreBucketInput" name="bucket" value="%s" />
              <input type="hidden" id="restoreRecordIdInput" name="recordId" value="%s" />
              <input type="hidden" id="restoreVersionInput" name="version" value="" />
            </form>
            <script>
            function openRestoreModalFromBtn(btn) {
                var db = btn.getAttribute('data-db') || '';
                var bucket = btn.getAttribute('data-bucket') || '';
                var recordId = btn.getAttribute('data-record-id') || '';
                var version = btn.getAttribute('data-version') || '';
                var nextVersion = btn.getAttribute('data-next-version') || '';
                var summary = btn.getAttribute('data-summary') || '';

                document.getElementById('restoreDbInput').value = db;
                document.getElementById('restoreBucketInput').value = bucket;
                document.getElementById('restoreRecordIdInput').value = recordId;
                document.getElementById('restoreVersionInput').value = version;

                var promptEl = document.getElementById('restoreModalPrompt');
                if (promptEl) {
                    promptEl.textContent = '¿Desea restaurar el registro ' + recordId + ' a la versión v' + version + '?';
                }
                var detailsEl = document.getElementById('restoreModalDetails');
                if (detailsEl) {
                    detailsEl.innerHTML = '<div><strong>Base / Bucket:</strong> ' + db + ' / ' + bucket + '</div>'
                        + '<div><strong>Versión Objetivo:</strong> v' + version + ' — ' + summary + '</div>'
                        + '<div style="color:#10b981; margin-top:0.35rem;">Se generará automáticamente un nuevo snapshot vigente <strong>v' + nextVersion + '</strong> sin perder la trazabilidad histórica.</div>';
                }
                var modal = document.getElementById('restoreVersionModal');
                if (modal) {
                    modal.style.display = 'flex';
                }
            }
            function closeRestoreModal() {
                var modal = document.getElementById('restoreVersionModal');
                if (modal) {
                    modal.style.display = 'none';
                }
            }
            function confirmRestoreVersion() {
                var form = document.getElementById('restoreVersionForm');
                if (form) {
                    form.submit();
                }
            }
            </script>
            """, escapeHtml(currentDb), escapeHtml(currentBucket), escapeHtml(rec.getId())));

        Modal restoreModal = buildRestoreConfirmationModal();

        Div card = Div.of(header, sub, RawHtml.of(sb.toString()), hiddenFormAndScript, restoreModal);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border: 2px solid #10b981; background: var(--bg-subtle);"));
        return card;
    }

    private Modal buildRestoreConfirmationModal() {
        Header modalTitle = Header.of(3, "♻️ Confirmar Restauración de Versión");
        modalTitle.modifier(new Modifier().style("margin: 0 0 0.75rem 0; font-size: 1.15rem; color: #10b981;"));

        Paragraph modalPrompt = Paragraph.of("¿Desea restaurar el registro seleccionado a esta versión histórica?");
        modalPrompt.modifier(new Modifier().attribute("id", "restoreModalPrompt").style("margin: 0 0 0.75rem 0; font-size: 0.95rem; color: var(--text-main); font-weight: 600;"));

        Div detailsBox = Div.of(Text.of(""));
        detailsBox.modifier(new Modifier()
            .attribute("id", "restoreModalDetails")
            .style("background: rgba(15, 23, 42, 0.85); border: 1px solid #334155; border-radius: 8px; padding: 0.85rem; margin-bottom: 1.25rem; font-size: 0.84rem; color: #cbd5e1; line-height: 1.55;"));

        JettraButton cancelBtn = JettraButton.of("Cancelar")
            .severity(JettraButton.Severity.SECONDARY)
            .type(JettraButton.Type.OUTLINED);
        cancelBtn.modifier(new Modifier()
            .attribute("id", "cancelRestoreBtn")
            .cssClass("app-btn-secondary")
            .attribute("onclick", "closeRestoreModal()"));

        JettraButton confirmBtn = JettraButton.of("Confirmar Restauración")
            .severity(JettraButton.Severity.SUCCESS)
            .type(JettraButton.Type.SOLID);
        confirmBtn.modifier(new Modifier()
            .attribute("id", "confirmRestoreBtn")
            .cssClass("app-btn-primary")
            .style("background: #10b981; color: #050b14; font-weight: 700; border: none;")
            .attribute("onclick", "confirmRestoreVersion()"));

        Row actionsRow = Row.of(cancelBtn, confirmBtn);
        actionsRow.modifier(new Modifier().style("display: flex; justify-content: flex-end; gap: 0.75rem;"));

        Modal modal = Modal.of(modalTitle, modalPrompt, detailsBox, actionsRow).open(false);
        modal.modifier(new Modifier()
            .attribute("id", "restoreVersionModal")
            .style("background: #0f172a; border: 1px solid #10b981; max-width: 520px; width: 92%; color: var(--text-main);"));
        return modal;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}

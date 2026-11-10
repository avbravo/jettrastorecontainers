package io.jettra.store.explorer.pages.query;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.List;

/**
 * Consola Interactiva JettraSQL y JettraQL para ejecución directa de consultas multimodelo.
 */
public class QueryConsoleFluxView extends FluxView {

    private final String selectedDb;
    private final String selectedBucket;
    private final String queryText;

    public QueryConsoleFluxView(String selectedDb, String selectedBucket, String queryText) {
        this.selectedDb = selectedDb;
        this.selectedBucket = selectedBucket;
        this.queryText = queryText;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<String> dbs = plugin.getDatabaseNames();

        String activeDb = (selectedDb != null && plugin.getDatabasesCatalog().containsKey(selectedDb))
                ? selectedDb
                : (dbs.isEmpty() ? "example_factura_db" : dbs.get(0));

        List<EngineBucketInfo> buckets = plugin.getBuckets(activeDb);
        String rawBucket = selectedBucket;
        String activeBucket = (rawBucket != null && buckets.stream().anyMatch(b -> b.getBucketName().equalsIgnoreCase(rawBucket)))
                ? rawBucket
                : (buckets.isEmpty() ? "Facturas" : buckets.get(0).getBucketName());

        String q = (queryText != null && !queryText.isBlank()) ? queryText : "total > 500";
        JettraStorePlugin.QueryResult result = plugin.executeQuery(activeDb, activeBucket, q, true);

        // Opciones HTML para selector
        StringBuilder dbOpts = new StringBuilder();
        for (String db : dbs) {
            dbOpts.append("<option value='").append(db).append("' ").append(db.equalsIgnoreCase(activeDb) ? "selected" : "").append(">").append(db).append("</option>");
        }

        StringBuilder bucketOpts = new StringBuilder();
        for (EngineBucketInfo b : buckets) {
            bucketOpts.append("<option value='").append(b.getBucketName()).append("' ").append(b.getBucketName().equalsIgnoreCase(activeBucket) ? "selected" : "").append(">")
                      .append(b.getBucketName()).append(" [").append(b.getEngineType()).append("]</option>");
        }

        Header header = Header.of(3, "⚡ Consola de Consulta Directa JettraSQL / JettraQL");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0 0 1rem 0;"));

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="GET" action="/query">
              <div style="display: flex; gap: 1rem; margin-bottom: 1rem; flex-wrap: wrap;">
                <div style="flex: 1; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">Base de Datos:</label>
                  <select name="db" onchange="this.form.submit()" class="app-input" style="color: #ffd700; font-weight: 600;">
                    %s
                  </select>
                </div>
                <div style="flex: 1; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">Bucket / Motor:</label>
                  <select name="bucket" onchange="this.form.submit()" class="app-input" style="color: #10b981; font-weight: 600;">
                    %s
                  </select>
                </div>
              </div>

              <div style="margin-bottom: 1rem;">
                <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">Instrucción de Consulta (JettraSQL / JettraQL):</label>
                <textarea name="query" rows="3" class="app-input" style="font-family: monospace; font-size: 0.95rem; color: #38bdf8;">%s</textarea>
              </div>

              <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem;">
                <div style="font-size: 0.8rem; color: var(--text-muted);">
                  Plantillas rápidas:
                  <a href="/query?db=%s&bucket=%s&query=total > 1000" style="color: #00d4ff; margin-right: 8px;">Filtro Numérico</a>
                  <a href="/query?db=%s&bucket=%s&query=emisor LIKE Corp" style="color: #00d4ff; margin-right: 8px;">Filtro Texto</a>
                  <a href="/query?db=%s&bucket=%s&query=FIND WHERE estado == ESTABLE" style="color: #00d4ff;">JettraQL FIND</a>
                </div>
                <button type="submit" class="app-btn-primary">⚡ Ejecutar Consulta</button>
              </div>
            </form>
        """, dbOpts.toString(), bucketOpts.toString(), escapeHtml(q), activeDb, activeBucket, activeDb, activeBucket, activeDb, activeBucket));

        Div consoleCard = Div.of(header, formHtml);
        consoleCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem;"));

        // Resultado de Consulta
        Div feedback = Div.of(Text.of("⏱️ " + result.message()));
        feedback.modifier(new Modifier().style("background: rgba(16, 185, 129, 0.15); border: 1px solid #10b981; color: #10b981; padding: 0.65rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.85rem;"));

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>ID</th><th>Resumen</th><th>Detalles JSON</th><th>Versión</th><th>Fecha</th></tr></thead>");
        table.append("<tbody>");

        if (result.records().isEmpty()) {
            table.append("<tr><td colspan='5' style='text-align:center; color:var(--text-muted); padding:2rem;'>No se encontraron registros que coincidan con la consulta.</td></tr>");
        } else {
            for (EngineRecordInfo r : result.records()) {
                String snippet = r.getDetails().replaceAll("\\s+", " ");
                if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";

                table.append("<tr>");
                table.append("<td><code style='color:#00d4ff; font-weight:700;'>").append(r.getId()).append("</code></td>");
                table.append("<td><strong>").append(escapeHtml(r.getSummary())).append("</strong></td>");
                table.append("<td><code style='color:#38bdf8; font-size:0.8rem;'>").append(escapeHtml(snippet)).append("</code></td>");
                table.append("<td><span style='color:#ffd700; font-weight:700;'>v").append(r.getVersion()).append("</span></td>");
                table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(r.getTimestamp()).append("</td>");
                table.append("</tr>");
            }
        }
        table.append("</tbody></table>");

        Div resultCard = Div.of(feedback, RawHtml.of(table.toString()));
        resultCard.modifier(new Modifier().cssClass("ui-card"));

        return Div.of(consoleCard, resultCard);
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

package io.jettra.store.explorer.pages.query;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.util.List;
import java.util.Map;

@Page(path = "/query")
public class QueryConsolePage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Consola Interactiva JettraSQL y JettraQL - JettraStore";
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<String> dbs = plugin.getDatabaseNames();

        String activeDb = params.getOrDefault("db", dbs.isEmpty() ? "example_factura_db" : dbs.get(0));
        List<EngineBucketInfo> buckets = plugin.getBuckets(activeDb);
        String activeBucket = params.getOrDefault("bucket", buckets.isEmpty() ? "Facturas" : buckets.get(0).getBucketName());
        String query = params.getOrDefault("query", "total > 500");

        JettraStorePlugin.QueryResult result = plugin.executeQuery(activeDb, activeBucket, query, true);

        // Selector DB y Bucket
        StringBuilder dbOptions = new StringBuilder();
        for (String db : dbs) {
            dbOptions.append("<option value='").append(db).append("' ").append(db.equals(activeDb) ? "selected" : "").append(">").append(db).append("</option>");
        }

        StringBuilder bucketOptions = new StringBuilder();
        for (EngineBucketInfo b : buckets) {
            bucketOptions.append("<option value='").append(b.getBucketName()).append("' ").append(b.getBucketName().equals(activeBucket) ? "selected" : "").append(">").append(b.getBucketName()).append(" (").append(b.getEngineType()).append(")</option>");
        }

        Widget consoleCard = Card.of(Column.of(
            Header.of(4, "⚡ Consola de Consulta Directa Multimodelo").modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 14px 0; color:#00d4ff;")),
            Paragraph.of(
                "<form method='GET' action='" + JettraServer.resolvePath("/query") + "'>" +
                "  <div style='display:flex; gap:12px; margin-bottom:12px; flex-wrap:wrap;'>" +
                "    <div style='flex:1; min-width:200px;'>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Base de Datos:</label>" +
                "      <select name='db' onchange='this.form.submit()' style='width:100%; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#ffd700;'>" +
                       dbOptions.toString() +
                "      </select>" +
                "    </div>" +
                "    <div style='flex:1; min-width:200px;'>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Bucket / Colección:</label>" +
                "      <select name='bucket' onchange='this.form.submit()' style='width:100%; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#22c55e;'>" +
                       bucketOptions.toString() +
                "      </select>" +
                "    </div>" +
                "  </div>" +
                "  <div style='margin-bottom:12px;'>" +
                "    <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Instrucción JettraSQL / JettraQL:</label>" +
                "    <textarea name='query' rows='3' style='width:100%; box-sizing:border-box; padding:10px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#00ff66; font-family:monospace; font-size:0.95rem;'>" + query + "</textarea>" +
                "  </div>" +
                "  <div style='display:flex; justify-content:space-between; align-items:center;'>" +
                "    <div style='font-size:0.8rem; color:#94a3b8;'>" +
                "      Plantillas rápidas: " +
                "      <a href='?db=" + activeDb + "&bucket=" + activeBucket + "&query=total > 1000' style='color:#00d4ff; margin-right:8px;'>Filtro Numérico</a>" +
                "      <a href='?db=" + activeDb + "&bucket=" + activeBucket + "&query=emisor LIKE Corp' style='color:#00d4ff; margin-right:8px;'>Filtro Texto LIKE</a>" +
                "      <a href='?db=" + activeDb + "&bucket=" + activeBucket + "&query=FIND WHERE estado == ESTABLE' style='color:#00d4ff;'>JettraQL FIND</a>" +
                "    </div>" +
                "    <button type='submit' style='padding:8px 24px; background:#00d4ff; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>⚡ Ejecutar en JettraStore</button>" +
                "  </div>" +
                "</form>"
            )
        )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:20px; margin-bottom:20px;"));

        Widget feedback = Paragraph.of("<span class='police-badge-lime'><i class='fas fa-check-circle'></i> " + result.message() + "</span>")
            .modifier(new io.jettra.flux.core.Modifier().style("margin-bottom:15px;"));

        // Tabla de Resultados
        StringBuilder resTable = new StringBuilder();
        resTable.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px;'>")
                .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
                .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
                .append("<th style='padding:8px;'>ID Registro</th><th style='padding:8px;'>Resumen</th><th style='padding:8px;'>Detalles JSON</th><th style='padding:8px;'>Versión</th><th style='padding:8px;'>Fecha</th>")
                .append("</tr></thead><tbody>");

        if (!result.records().isEmpty()) {
            for (EngineRecordInfo r : result.records()) {
                resTable.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                        .append("<td style='padding:8px; font-weight:700; color:#ffd700; font-family:monospace;'>").append(r.getId()).append("</td>")
                        .append("<td style='padding:8px;'>").append(r.getSummary()).append("</td>")
                        .append("<td style='padding:8px; font-family:monospace; color:#00ff66;'>").append(r.getDetails().replace("\n", " ").substring(0, Math.min(65, r.getDetails().length()))).append("...</td>")
                        .append("<td style='padding:8px;'><span class='police-badge-gold'>v").append(r.getVersion()).append("</span></td>")
                        .append("<td style='padding:8px; color:#64748b;'>").append(r.getTimestamp()).append("</td>")
                        .append("</tr>");
            }
        } else {
            resTable.append("<tr><td colspan='5' style='padding:20px; text-align:center; color:#64748b;'>Sin registros para los criterios especificados.</td></tr>");
        }
        resTable.append("</tbody></table></div>");

        return Column.of(
            Header.of(4, "⚡ Consola de Consultas JettraSQL / JettraQL").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")),
            consoleCard,
            feedback,
            Paragraph.of(resTable.toString())
        );
    }
}

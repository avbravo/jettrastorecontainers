package io.jettra.store.explorer.pages.explorer;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineIndexInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Page(path = "/explorer")
public class EngineExplorerPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Explorador de Motores y Datos Multimodelo - JettraStore";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = params.get("_action");
        String db = params.get("db");
        String bucket = params.get("bucket");

        if ("add_record".equals(action)) {
            String id = params.get("record_id");
            String summary = params.get("summary");
            String details = params.get("details");
            if (id != null && !id.isBlank() && db != null && bucket != null) {
                plugin.addRecord(db, bucket, id.trim(), summary, details);
            }
            redirect(exchange, "/explorer?db=" + db + "&bucket=" + bucket + "&success=added");
            return true;
        } else if ("delete_record".equals(action)) {
            String id = params.get("record_id");
            if (id != null && db != null && bucket != null) {
                plugin.deleteRecord(db, bucket, id.trim());
            }
            redirect(exchange, "/explorer?db=" + db + "&bucket=" + bucket + "&success=deleted");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<String> dbs = plugin.getDatabaseNames();
        String activeDb = params.getOrDefault("db", dbs.isEmpty() ? "example_factura_db" : dbs.get(0));

        List<EngineBucketInfo> buckets = plugin.getBuckets(activeDb);
        String activeBucket = params.getOrDefault("bucket", buckets.isEmpty() ? "Facturas" : buckets.get(0).getBucketName());

        EngineBucketInfo bucketInfo = plugin.getBucket(activeDb, activeBucket);
        String query = params.getOrDefault("q", "");
        String activeTab = params.getOrDefault("tab", "records");

        // 1. Selector de Bases de Datos (Tabs Superiores)
        StringBuilder dbNav = new StringBuilder();
        dbNav.append("<div style='display:flex; gap:10px; margin-bottom:20px; overflow-x:auto; padding-bottom:5px;'>");
        for (String db : dbs) {
            boolean isCur = db.equalsIgnoreCase(activeDb);
            String style = isCur 
                ? "background:#00d4ff; color:#05080c; font-weight:700; border:1px solid #00d4ff;" 
                : "background:rgba(10, 18, 28, 0.8); color:#94a3b8; border:1px solid rgba(0, 212, 255, 0.2);";
            dbNav.append("<a href='").append(JettraServer.resolvePath("/explorer?db=" + db))
                 .append("' style='text-decoration:none; padding:8px 18px; border-radius:6px; font-size:0.9rem; ")
                 .append(style).append("'><i class='fas fa-database'></i> ").append(db).append("</a>");
        }
        dbNav.append("</div>");

        // 2. Selector de Buckets de la base de datos seleccionada
        StringBuilder bucketNav = new StringBuilder();
        bucketNav.append("<div style='display:flex; gap:10px; margin-bottom:20px; flex-wrap:wrap;'>");
        for (EngineBucketInfo b : buckets) {
            boolean isCur = b.getBucketName().equalsIgnoreCase(activeBucket);
            String border = isCur ? "border:2px solid #ffd700;" : "border:1px solid rgba(255,255,255,0.1);";
            String bg = isCur ? "background:rgba(255, 215, 0, 0.15);" : "background:rgba(10, 18, 28, 0.6);";
            String engineColor = switch (b.getEngineType()) {
                case "DOCUMENT" -> "#00d4ff";
                case "RELATIONAL" -> "#ffd700";
                case "GRAPH" -> "#ec4899";
                case "VECTOR" -> "#a855f7";
                case "TIMESERIES" -> "#22c55e";
                case "KEY_VALUE" -> "#f97316";
                default -> "#38bdf8";
            };

            bucketNav.append("<a href='").append(JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + b.getBucketName()))
                     .append("' style='text-decoration:none; padding:10px 16px; border-radius:8px; ")
                     .append(bg).append(border).append("'>")
                     .append("<div style='font-size:0.95rem; font-weight:700; color:#e2e8f0;'>").append(b.getBucketName()).append("</div>")
                     .append("<div style='font-size:0.75rem; margin-top:4px;'><span style='color:").append(engineColor).append("; font-weight:700;'>")
                     .append(b.getEngineType()).append("</span> | <span style='color:#64748b;'>").append(String.format("%,d", b.getTotalObjects())).append(" objs</span></div>")
                     .append("</a>");
        }
        bucketNav.append("</div>");

        // 3. Barra de Consulta y Filtro (JettraSQL / JettraQL)
        JettraStorePlugin.QueryResult qResult = plugin.executeQuery(activeDb, activeBucket, query, true);

        String filterForm = 
            "<form method='GET' action='" + JettraServer.resolvePath("/explorer") + "' style='display:flex; gap:12px; align-items:center; background:rgba(10, 18, 28, 0.85); padding:15px; border-radius:8px; border:1px solid rgba(0, 212, 255, 0.25); margin-bottom:20px;'>" +
            "  <input type='hidden' name='db' value='" + activeDb + "'/>" +
            "  <input type='hidden' name='bucket' value='" + activeBucket + "'/>" +
            "  <input type='hidden' name='tab' value='" + activeTab + "'/>" +
            "  <span style='color:#ffd700; font-weight:700;'><i class='fas fa-search'></i> JettraSQL / Filtro:</span>" +
            "  <input type='text' name='q' value='" + query + "' placeholder='Ej: total > 500 o emisor LIKE Corp o estado == ESTABLE' style='flex:1; padding:8px 12px; background:#05080c; border:1px solid #334155; border-radius:6px; color:#00ff66; font-family:monospace;'/>" +
            "  <button type='submit' style='padding:8px 18px; background:#00d4ff; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>Ejecutar Consulta</button>" +
            "  <a href='" + JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + activeBucket) + "' style='padding:8px 14px; background:#334155; color:#cbd5e1; border-radius:6px; text-decoration:none;'>Limpiar</a>" +
            "</form>";

        Widget queryFeedback = Paragraph.of("<span class='police-badge-lime'><i class='fas fa-bolt'></i> " + qResult.message() + "</span>")
            .modifier(new io.jettra.flux.core.Modifier().style("margin-bottom:15px;"));

        // 4. Pestañas: REGISTROS vs ÍNDICES
        String recordsTabUrl = JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + activeBucket + "&tab=records");
        String indexesTabUrl = JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + activeBucket + "&tab=indexes");

        String tabRecordsClass = "records".equals(activeTab) ? "police-badge-gold" : "police-badge-blue";
        String tabIndexesClass = "indexes".equals(activeTab) ? "police-badge-gold" : "police-badge-blue";

        Widget tabsHeader = Row.of(
            Link.of(recordsTabUrl, Span.of("📄 Registros y Documentos (" + qResult.records().size() + ")"))
                  .modifier(new io.jettra.flux.core.Modifier().cssClass(tabRecordsClass).style("text-decoration:none; padding:8px 16px; font-weight:700; cursor:pointer;")),
            Link.of(indexesTabUrl, Span.of("⚡ Índices del Bucket (" + (bucketInfo != null ? bucketInfo.getIndexes().size() : 0) + ")"))
                  .modifier(new io.jettra.flux.core.Modifier().cssClass(tabIndexesClass).style("text-decoration:none; padding:8px 16px; font-weight:700; cursor:pointer;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("gap:12px; margin-bottom:15px;"));

        Widget mainContent;

        if ("indexes".equals(activeTab)) {
            // Tabla de Índices
            StringBuilder idxTable = new StringBuilder();
            idxTable.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px;'>")
                    .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
                    .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
                    .append("<th style='padding:8px;'>Nombre Índice</th><th style='padding:8px;'>Campos</th><th style='padding:8px;'>Tipo Estructura</th><th style='padding:8px;'>Único</th><th style='padding:8px;'>Estado</th><th style='padding:8px; text-align:right;'>Acciones</th>")
                    .append("</tr></thead><tbody>");

            if (bucketInfo != null && !bucketInfo.getIndexes().isEmpty()) {
                for (EngineIndexInfo idx : bucketInfo.getIndexes()) {
                    idxTable.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                            .append("<td style='padding:8px; font-weight:700;'>").append(idx.getName()).append("</td>")
                            .append("<td style='padding:8px; color:#ffd700; font-family:monospace;'>").append(idx.getFields()).append("</td>")
                            .append("<td style='padding:8px;'><span class='police-badge-blue'>").append(idx.getType()).append("</span></td>")
                            .append("<td style='padding:8px;'>").append(idx.isUnique() ? "<span class='police-badge-lime'>SÍ</span>" : "NO").append("</td>")
                            .append("<td style='padding:8px;'><span class='police-badge-lime'>").append(idx.getStatus()).append("</span></td>")
                            .append("<td style='padding:8px; text-align:right;'>")
                            .append("<button style='padding:4px 8px; background:#334155; color:#38bdf8; border:none; border-radius:4px; cursor:pointer;' onclick='alert(\"Reconstrucción iniciada con Zero-Lock\");'>Reconstruir</button>")
                            .append("</td></tr>");
                }
            } else {
                idxTable.append("<tr><td colspan='6' style='padding:15px; text-align:center; color:#64748b;'>No hay índices registrados para este bucket.</td></tr>");
            }
            idxTable.append("</tbody></table></div>");
            mainContent = Paragraph.of(idxTable.toString());
        } else {
            // Tabla de Registros
            StringBuilder recTable = new StringBuilder();
            recTable.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px;'>")
                    .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
                    .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
                    .append("<th style='padding:8px;'>ID Registro</th><th style='padding:8px;'>Motor</th><th style='padding:8px;'>Resumen</th><th style='padding:8px;'>Versión</th><th style='padding:8px;'>Fecha Actualización</th><th style='padding:8px; text-align:right;'>Acciones</th>")
                    .append("</tr></thead><tbody>");

            if (!qResult.records().isEmpty()) {
                for (EngineRecordInfo r : qResult.records()) {
                    recTable.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                            .append("<td style='padding:8px; font-weight:700; color:#ffd700; font-family:monospace;'>").append(r.getId()).append("</td>")
                            .append("<td style='padding:8px;'><span class='police-badge-blue'>").append(r.getEngineType()).append("</span></td>")
                            .append("<td style='padding:8px;'>").append(r.getSummary()).append("</td>")
                            .append("<td style='padding:8px;'><span class='police-badge-gold'>v").append(r.getVersion()).append("</span></td>")
                            .append("<td style='padding:8px; color:#64748b;'>").append(r.getTimestamp()).append("</td>")
                            .append("<td style='padding:8px; text-align:right;'>")
                            .append("<a href='").append(JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + activeBucket + "&view=" + r.getId()))
                            .append("' style='text-decoration:none; padding:4px 8px; background:rgba(0,212,255,0.15); color:#00d4ff; border:1px solid #00d4ff; border-radius:4px; margin-right:6px;'>👁️ Ver</a>")
                            .append("<form method='POST' action='").append(JettraServer.resolvePath("/explorer"))
                            .append("' style='display:inline;' onsubmit='return confirm(\"¿Eliminar registro ").append(r.getId()).append("?\");'>")
                            .append("<input type='hidden' name='_action' value='delete_record'/>")
                            .append("<input type='hidden' name='db' value='").append(activeDb).append("'/>")
                            .append("<input type='hidden' name='bucket' value='").append(activeBucket).append("'/>")
                            .append("<input type='hidden' name='record_id' value='").append(r.getId()).append("'/>")
                            .append("<button type='submit' style='padding:4px 8px; background:rgba(239,68,68,0.15); color:#ef4444; border:1px solid #ef4444; border-radius:4px; cursor:pointer;'>🗑️</button>")
                            .append("</form>")
                            .append("</td></tr>");
                }
            } else {
                recTable.append("<tr><td colspan='6' style='padding:15px; text-align:center; color:#64748b;'>No se encontraron registros que coincidan con el filtro.</td></tr>");
            }
            recTable.append("</tbody></table></div>");
            mainContent = Paragraph.of(recTable.toString());
        }

        // 5. Visor Modal de Registro si hay param ?view=
        Widget recordDetailModal = null;
        if (params.containsKey("view") && bucketInfo != null) {
            String viewId = params.get("view");
            EngineRecordInfo found = bucketInfo.getSampleRecords().stream().filter(r -> r.getId().equalsIgnoreCase(viewId)).findFirst().orElse(null);
            if (found != null) {
                recordDetailModal = Card.of(Column.of(
                    Row.of(
                        Header.of(4, "🔍 Detalle del Registro: " + found.getId()).modifier(new io.jettra.flux.core.Modifier().style("margin:0; color:#ffd700;")),
                        Link.of(JettraServer.resolvePath("/explorer?db=" + activeDb + "&bucket=" + activeBucket), Span.of("✖ Cerrar"))
                              .modifier(new io.jettra.flux.core.Modifier().style("color:#ef4444; text-decoration:none; font-weight:700;"))
                    ).modifier(new io.jettra.flux.core.Modifier().style("justify-content:space-between; align-items:center; margin-bottom:12px;")),
                    Paragraph.of("<b>Motor:</b> " + found.getEngineType() + " | <b>Versión:</b> v" + found.getVersion() + " | <b>Actualizado:</b> " + found.getTimestamp()),
                    Paragraph.of("<pre style='background:#05080c; border:1px solid #334155; padding:15px; border-radius:6px; color:#00ff66; overflow-x:auto; font-family:monospace;'>" + found.getDetails() + "</pre>")
                )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("margin-bottom:20px; padding:20px;"));
            }
        }

        // 6. Formulario para Nuevo Registro
        Widget newRecordCard = Card.of(Column.of(
            Header.of(4, "➕ Añadir Nuevo Registro en " + activeBucket).modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 12px 0; color:#00d4ff;")),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/explorer") + "'>" +
                "  <input type='hidden' name='_action' value='add_record'/>" +
                "  <input type='hidden' name='db' value='" + activeDb + "'/>" +
                "  <input type='hidden' name='bucket' value='" + activeBucket + "'/>" +
                "  <div style='display:flex; gap:12px; margin-bottom:10px;'>" +
                "    <div style='flex:1;'>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>ID Registro:</label>" +
                "      <input type='text' name='record_id' required placeholder='Ej: REC-99401' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div style='flex:2;'>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Resumen:</label>" +
                "      <input type='text' name='summary' required placeholder='Ej: Ingesta de Datos Transaccionales' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "  </div>" +
                "  <div style='margin-bottom:12px;'>" +
                "    <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Contenido JSON / Payload:</label>" +
                "    <textarea name='details' rows='4' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#00ff66; font-family:monospace;'>{\n  \"status\": \"ACTIVO\",\n  \"valor\": 100.00\n}</textarea>" +
                "  </div>" +
                "  <button type='submit' style='padding:8px 20px; background:#22c55e; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>Guardar Registro</button>" +
                "</form>"
            )
        )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("margin-top:25px; padding:18px;"));

        List<Widget> centerItems = new ArrayList<>();
        centerItems.add(Header.of(4, "🗄️ Explorador de Bases de Datos Multimodelo y Buckets").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")));
        centerItems.add(Paragraph.of(dbNav.toString()));
        centerItems.add(Paragraph.of(bucketNav.toString()));
        if (recordDetailModal != null) {
            centerItems.add(recordDetailModal);
        }
        centerItems.add(Paragraph.of(filterForm));
        centerItems.add(queryFeedback);
        centerItems.add(tabsHeader);
        centerItems.add(mainContent);
        centerItems.add(newRecordCard);

        return Column.of(centerItems.toArray(new Widget[0]));
    }
}

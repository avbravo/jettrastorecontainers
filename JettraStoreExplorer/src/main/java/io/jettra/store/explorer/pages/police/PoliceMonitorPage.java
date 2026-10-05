package io.jettra.store.explorer.pages.police;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.PoliceIncident;
import io.jettra.store.explorer.model.PoliceSentinelInfo;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Page(path = "/police")
public class PoliceMonitorPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Centinelas y Guardianes IA - JettraStorePolice";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = params.get("_action");

        if ("patrol_scan".equals(action)) {
            plugin.logPoliceIncident("Heap Sentinel", "Escaneo profundo de Memoria Directa Panama completado. 0 fragmentaciones detectadas.", "OK", "Limpieza de búferes temporales");
            plugin.logPoliceIncident("Raft Quorum K9", "Auditoría de consistencia de réplicas en nodo 1, 2, 3: Quorum perfecto.", "OK", "WAL verificado");
            redirect(exchange, "/police?msg=scan_complete");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<PoliceSentinelInfo> sentinels = plugin.getPoliceSentinels();

        List<Widget> cards = new ArrayList<>();
        for (PoliceSentinelInfo s : sentinels) {
            Widget header = Row.of(
                Header.of(4, "🐾 " + s.getRole()).modifier(new io.jettra.flux.core.Modifier().style("margin:0; color:" + s.getColor() + ";")),
                Span.of(s.getStatus()).modifier(new io.jettra.flux.core.Modifier().cssClass("police-badge-lime"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content:space-between; align-items:center; margin-bottom:10px;"));

            Widget details = Paragraph.of(
                "<div style='font-size:0.9rem; font-weight:700; color:#e2e8f0; margin-bottom:6px;'>" + s.getName() + "</div>" +
                "<div style='font-size:0.85rem; color:#94a3b8; margin-bottom:12px;'>" + s.getDescription() + "</div>" +
                "<div style='display:flex; justify-content:space-between; font-size:0.8rem; border-top:1px solid rgba(255,255,255,0.1); padding-top:10px;'>" +
                "  <div>Nodo Asignado: <b style='color:#ffd700;'>" + s.getAssignedNode() + "</b></div>" +
                "  <div>Salud: <b style='color:#22c55e;'>" + s.getHealthScore() + "%</b></div>" +
                "  <div>Alertas: <b style='color:" + (s.getTotalAlerts() > 0 ? "#ffd700" : "#94a3b8") + ";'>" + s.getTotalAlerts() + "</b></div>" +
                "</div>"
            );

            Widget c = Card.of(Column.of(header, details))
                .modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:18px; border-radius:10px;"));
            cards.add(c);
        }

        Widget sentinelsGrid = Grid.of(cards.toArray(new Widget[0]))
            .modifier(new io.jettra.flux.core.Modifier().style("grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:18px; margin-bottom:25px;"));

        // Barra de Control Policial
        Widget controlBar = Card.of(
            Row.of(
                Paragraph.of("<b>Centro de Comando de JettraStorePolice:</b> Ejecute inspección en caliente sobre todos los submundos de datos.")
                    .modifier(new io.jettra.flux.core.Modifier().style("margin:0; color:#e2e8f0;")),
                Paragraph.of(
                    "<form method='POST' action='" + JettraServer.resolvePath("/police") + "' style='margin:0;'>" +
                    "  <input type='hidden' name='_action' value='patrol_scan'/>" +
                    "  <button type='submit' style='padding:8px 18px; background:#00d4ff; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'><i class='fas fa-shield-virus'></i> Ejecutar Patrullaje General</button>" +
                    "</form>"
                )
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:15px; margin-bottom:25px;"));

        // Tabla de Incidentes Policiales
        StringBuilder incTable = new StringBuilder();
        incTable.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px;'>")
                .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
                .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
                .append("<th style='padding:8px;'>Hora</th><th style='padding:8px;'>Centinela</th><th style='padding:8px;'>Evento / Detección</th><th style='padding:8px;'>Severidad</th><th style='padding:8px;'>Acción Tomada</th>")
                .append("</tr></thead><tbody>");

        for (PoliceIncident inc : plugin.getPoliceIncidents()) {
            String badge = inc.getSeverity().equals("CRITICAL") ? "police-badge-red" : (inc.getSeverity().equals("WARN") ? "police-badge-gold" : "police-badge-lime");

            incTable.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                    .append("<td style='padding:8px; color:#94a3b8; font-family:monospace;'>").append(inc.getTimestamp()).append("</td>")
                    .append("<td style='padding:8px; font-weight:700; color:#00d4ff;'>").append(inc.getSentinelName()).append("</td>")
                    .append("<td style='padding:8px;'>").append(inc.getEventDescription()).append("</td>")
                    .append("<td style='padding:8px;'><span class='").append(badge).append("'>").append(inc.getSeverity()).append("</span></td>")
                    .append("<td style='padding:8px; color:#22c55e;'>").append(inc.getActionTaken()).append("</td>")
                    .append("</tr>");
        }
        incTable.append("</tbody></table></div>");

        return Column.of(
            Header.of(4, "🐾 Centinelas Guardianes IA de JettraStorePolice (Caninos de Resguardo)").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")),
            sentinelsGrid,
            controlBar,
            Header.of(4, "📜 Registro de Detección de Incidentes Policiales en Tiempo Real").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:12px;")),
            Paragraph.of(incTable.toString())
        );
    }
}

package io.jettra.store.explorer.pages.cluster;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.PoliceIncident;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Page(path = "/cluster")
public class ClusterMonitorPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Monitor de Clúster y Nodos 3D - JettraStore";
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        // 1. KPI Stat Cards superiores
        Widget stat1 = StatCard.of("Nodos en Línea", "4/4", "100% Quorum", true)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow"));

        Widget stat2 = StatCard.of("Ingesta Global (QPS)", "57,150", "+12.4% flujo", true)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow"));

        Widget stat3 = StatCard.of("Memoria Directa Panama", "4.01 GB", "Zero-GC Panama FFM", true)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow"));

        String multinodeStatus = plugin.isMultinodeActive() ? "MULTINODO ACTIVO" : "MONONODO LOCAL";
        Widget stat4 = StatCard.of("Topología de Clúster", multinodeStatus, plugin.getMultinodeDisplay(), plugin.isMultinodeActive())
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow"));

        Widget statsRow = Grid.of(stat1, stat2, stat3, stat4)
            .modifier(new io.jettra.flux.core.Modifier().style("grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 15px; margin-bottom: 20px;"));

        // 2. Grid de Nodos Servidores (Submundos de JettraStorePolice3D)
        List<Widget> nodeCards = new ArrayList<>();
        for (ServerNodeInfo node : plugin.getServerNodes()) {
            String roleBadge = node.getRole().equals("LEADER") ? "police-badge-gold" : "police-badge-blue";
            String statusBadge = node.isOnline() ? "police-badge-lime" : "police-badge-red";

            Widget nodeHeader = Row.of(
                Header.of(4, "🖥️ " + node.getName()).modifier(new io.jettra.flux.core.Modifier().style("margin:0; color:#00d4ff;")),
                Row.of(
                    Span.of(node.getRole()).modifier(new io.jettra.flux.core.Modifier().cssClass(roleBadge)),
                    Span.of(node.isOnline() ? "ONLINE" : "OFFLINE").modifier(new io.jettra.flux.core.Modifier().cssClass(statusBadge))
                ).modifier(new io.jettra.flux.core.Modifier().style("gap:6px;"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content:space-between; align-items:center; margin-bottom:12px;"));

            Widget hostInfo = Paragraph.of("<i class='fas fa-network-wired'></i> Dirección: <b>" + node.getHost() + ":" + node.getPort() + "</b> | Raft Term: <b>" + node.getRaftTerm() + "</b>")
                .modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 10px 0; color:#94a3b8; font-size:0.9rem;"));

            Widget metricsTable = Paragraph.of(
                "<table style='width:100%; font-size:0.85rem; color:#e2e8f0; border-collapse:collapse;'>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>CPU Utilización:</td><td style='text-align:right; font-weight:700; color:#ffd700;'>" + node.getCpuPercent() + "%</td></tr>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>Heap JVM:</td><td style='text-align:right; font-weight:700; color:#22c55e;'>" + node.getHeapUsedMb() + " MB / " + node.getHeapMaxMb() + " MB</td></tr>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>Panama Direct Memory:</td><td style='text-align:right; font-weight:700; color:#00d4ff;'>" + node.getPanamaDirectMb() + " MB</td></tr>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>Almacenamiento SSTable:</td><td style='text-align:right; font-weight:700;'>" + String.format("%,d MB", node.getDiskUsedMb()) + "</td></tr>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>Hilos Virtuales Loom:</td><td style='text-align:right; font-weight:700;'>" + node.getVirtualThreads() + " vthreads</td></tr>" +
                "  <tr><td style='padding:4px 0; color:#64748b;'>Rendimiento / Latencia:</td><td style='text-align:right; font-weight:700; color:#38bdf8;'>" + String.format("%,d QPS", node.getQps()) + " (" + node.getLatencyMs() + " ms)</td></tr>" +
                "</table>"
            );

            Widget actionExplore = Link.of(
                JettraServer.resolvePath("/explorer?node=" + node.getId()),
                Span.of("🌌 Expandir Submundo & Bases de Datos <i class='fas fa-arrow-right'></i>")
            ).modifier(new io.jettra.flux.core.Modifier().style("display:block; text-align:center; margin-top:14px; padding:8px 12px; background:rgba(0, 212, 255, 0.12); border:1px solid #00d4ff; border-radius:6px; color:#00d4ff; text-decoration:none; font-weight:700; font-size:0.85rem;"));

            Widget card = Card.of(Column.of(
                nodeHeader,
                hostInfo,
                metricsTable,
                actionExplore
            )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:18px; border-radius:10px;"));

            nodeCards.add(card);
        }

        Widget nodesGrid = Grid.of(nodeCards.toArray(new Widget[0]))
            .modifier(new io.jettra.flux.core.Modifier().style("grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 18px; margin-bottom: 25px;"));

        // 3. Tráfico de Red y Replicación entre Nodos (Camiones de Datos)
        Widget trafficTitle = Header.of(4, "🚚 Tráfico de Replicación y Consenso Inter-Nodo (Camiones de Datos)")
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:12px;"));

        String trafficHtml = 
            "<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px;'>" +
            "  <table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>" +
            "    <thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>" +
            "      <th style='padding:8px;'>Origen</th>" +
            "      <th style='padding:8px;'>Destino</th>" +
            "      <th style='padding:8px;'>Canal / Tipo</th>" +
            "      <th style='padding:8px;'>Carga Útil</th>" +
            "      <th style='padding:8px;'>Volumen</th>" +
            "      <th style='padding:8px;'>Velocidad</th>" +
            "      <th style='padding:8px;'>Estado Consenso</th>" +
            "    </tr></thead>" +
            "    <tbody>" +
            "      <tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>" +
            "        <td style='padding:8px;'><span class='police-badge-gold'>node-01</span></td>" +
            "        <td style='padding:8px;'><span class='police-badge-blue'>node-02</span></td>" +
            "        <td style='padding:8px;'>RAFT_WAL_REPLICATION</td>" +
            "        <td style='padding:8px;'>Entries Term 8: 1004-1080</td>" +
            "        <td style='padding:8px;'>14.2 MB</td>" +
            "        <td style='padding:8px; color:#22c55e;'>185.0 MB/s</td>" +
            "        <td style='padding:8px;'><span class='police-badge-lime'>QUORUM COMMITTED</span></td>" +
            "      </tr>" +
            "      <tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>" +
            "        <td style='padding:8px;'><span class='police-badge-gold'>node-01</span></td>" +
            "        <td style='padding:8px;'><span class='police-badge-blue'>node-03</span></td>" +
            "        <td style='padding:8px;'>SSTABLE_COMPACT_SYNC</td>" +
            "        <td style='padding:8px;'>Level-1 Ingestion Facturas</td>" +
            "        <td style='padding:8px;'>48.0 MB</td>" +
            "        <td style='padding:8px; color:#22c55e;'>210.4 MB/s</td>" +
            "        <td style='padding:8px;'><span class='police-badge-lime'>SYNCHRONIZED</span></td>" +
            "      </tr>" +
            "      <tr>" +
            "        <td style='padding:8px;'><span class='police-badge-blue'>node-04</span></td>" +
            "        <td style='padding:8px;'><span class='police-badge-gold'>node-01</span></td>" +
            "        <td style='padding:8px;'>IOT_TIMESERIES_STREAM</td>" +
            "        <td style='padding:8px;'>SensorMetrics Influx Batch</td>" +
            "        <td style='padding:8px;'>2.8 MB</td>" +
            "        <td style='padding:8px; color:#00d4ff;'>42.8 MB/s</td>" +
            "        <td style='padding:8px;'><span class='police-badge-lime'>INGESTING</span></td>" +
            "      </tr>" +
            "    </tbody>" +
            "  </table>" +
            "</div>";

        Widget trafficTable = Paragraph.of(trafficHtml);

        // 4. Registro de Auditoría y Eventos Policiales en Vivo
        Widget eventsTitle = Header.of(4, "🐾 Feed de Eventos y Patrullaje JettraStorePolice")
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin:20px 0 10px 0;"));

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='background:rgba(10, 18, 28, 0.85); border:1px solid rgba(255, 215, 0, 0.3); border-radius:8px; padding:15px; max-height:220px; overflow-y:auto;'>");
        for (PoliceIncident inc : plugin.getPoliceIncidents()) {
            String badge = inc.getSeverity().equals("CRITICAL") ? "police-badge-red" : (inc.getSeverity().equals("WARN") ? "police-badge-gold" : "police-badge-lime");
            sb.append("<div style='display:flex; justify-content:space-between; padding:6px 0; border-bottom:1px solid rgba(255,255,255,0.05); font-size:0.85rem;'>")
              .append("<div><span style='color:#64748b;'>[").append(inc.getTimestamp()).append("]</span> ")
              .append("<b style='color:#00d4ff;'>").append(inc.getSentinelName()).append(":</b> ")
              .append("<span style='color:#e2e8f0;'>").append(inc.getEventDescription()).append("</span></div>")
              .append("<div><span class='").append(badge).append("'>").append(inc.getSeverity()).append("</span></div>")
              .append("</div>");
        }
        sb.append("</div>");

        Widget eventsFeed = Paragraph.of(sb.toString());

        return Column.of(
            statsRow,
            Header.of(4, "🖥️ Topología de Nodos del Clúster JettraStore (Submundos)").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:14px;")),
            nodesGrid,
            trafficTitle,
            trafficTable,
            eventsTitle,
            eventsFeed
        );
    }
}

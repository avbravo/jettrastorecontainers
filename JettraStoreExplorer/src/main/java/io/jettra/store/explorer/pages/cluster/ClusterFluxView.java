package io.jettra.store.explorer.pages.cluster;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Vista de Administración y Monitor de Nodos del Clúster JettraStore en JettraFlux.
 */
public class ClusterFluxView extends FluxView {

    private final String message;

    public ClusterFluxView() {
        this(null);
    }

    public ClusterFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<ServerNodeInfo> nodes = plugin.getServerNodes();

        long onlineCount = nodes.stream().filter(ServerNodeInfo::isOnline).count();
        long totalQps = nodes.stream().filter(ServerNodeInfo::isOnline).mapToLong(ServerNodeInfo::getQps).sum();
        long totalPanama = nodes.stream().filter(ServerNodeInfo::isOnline).mapToLong(ServerNodeInfo::getPanamaDirectMb).sum();

        List<Widget> rootChildren = new ArrayList<>();

        // 1. Mensaje Flash si existe
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            rootChildren.add(flash);
        }

        // 2. Tarjetas KPI Superiores
        Div kpi1 = buildKpiCard("Nodos Activos", onlineCount + " / " + nodes.size(), "Quorum Raft: " + (onlineCount >= (nodes.size()/2 + 1) ? "Óptimo (100%)" : "Degradado"), "#00d4ff");
        Div kpi2 = buildKpiCard("Ingesta Global QPS", String.format("%,d", totalQps) + " ops/s", "Flujo multimodelo distribuido", "#10b981");
        Div kpi3 = buildKpiCard("Panama Direct Memory", String.format("%.2f GB", totalPanama / 1024.0), "Zero-GC Direct Foreign Memory", "#a855f7");
        Div kpi4 = buildKpiCard("Modo de Topología", plugin.isMultinodeActive() ? "MULTINODO RAFT" : "STANDALONE LOCAL", plugin.getMultinodeDisplay(), plugin.isMultinodeActive() ? "#00d4ff" : "#f59e0b");

        Div kpiGrid = Div.of(kpi1, kpi2, kpi3, kpi4);
        kpiGrid.modifier(new Modifier().cssClass("kpi-grid"));
        rootChildren.add(kpiGrid);

        // 3. Formulario para Registrar Nuevo Nodo
        Widget addNodeSection = buildAddNodeSection();
        rootChildren.add(addNodeSection);

        // 4. Grid de Tarjetas de Nodos del Clúster
        List<Widget> cardList = new ArrayList<>();
        for (ServerNodeInfo node : nodes) {
            cardList.add(buildNodeCard(node));
        }
        Div nodesGrid = Div.of(cardList.toArray(new Widget[0]));
        nodesGrid.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(330px, 1fr)); gap: 1.25rem; margin-bottom: 2rem;"));
        rootChildren.add(nodesGrid);

        // 5. Tabla de Matriz de Replicación y Enrutamiento Raft
        Widget replicationMatrix = buildReplicationMatrix(nodes);
        rootChildren.add(replicationMatrix);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Div buildKpiCard(String title, String value, String sub, String accentColor) {
        Div t = Div.of(Text.of(title));
        t.modifier(new Modifier().cssClass("kpi-title"));

        Div v = Div.of(Text.of(value));
        v.modifier(new Modifier().cssClass("kpi-value").style("color:" + accentColor + ";"));

        Div s = Div.of(Text.of(sub));
        s.modifier(new Modifier().cssClass("kpi-sub"));

        Div card = Div.of(t, v, s);
        card.modifier(new Modifier().cssClass("ui-card").style("border-top: 3px solid " + accentColor + ";"));
        return card;
    }

    private Widget buildAddNodeSection() {
        Header header = Header.of(3, "➕ Registrar Nuevo Nodo en el Clúster");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        RawHtml formHtml = RawHtml.of("""
            <form method="POST" action="/cluster" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
              <input type="hidden" name="action" value="add_node" />
              <div style="flex: 1; min-width: 140px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">ID del Nodo</label>
                <input class="app-input" type="text" name="nodeId" placeholder="node-05" required />
              </div>
              <div style="flex: 1.5; min-width: 180px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Nombre del Nodo</label>
                <input class="app-input" type="text" name="nodeName" placeholder="node-05-replica" required />
              </div>
              <div style="flex: 1; min-width: 130px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Rol Raft</label>
                <select class="app-input" name="nodeRole">
                  <option value="FOLLOWER">FOLLOWER</option>
                  <option value="LEADER">LEADER</option>
                  <option value="EDGE">EDGE</option>
                </select>
              </div>
              <div style="flex: 1.2; min-width: 140px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Host / IP</label>
                <input class="app-input" type="text" name="nodeHost" value="127.0.0.1" required />
              </div>
              <div style="flex: 0.8; min-width: 90px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Puerto</label>
                <input class="app-input" type="number" name="nodePort" value="8769" required />
              </div>
              <div style="flex: 0.8; min-width: 110px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Max RAM (MB)</label>
                <input class="app-input" type="number" name="nodeRam" value="2048" required />
              </div>
              <div>
                <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Agregar Nodo</button>
              </div>
            </form>
        """);

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
        return card;
    }

    private Div buildNodeCard(ServerNodeInfo node) {
        String roleColor = "LEADER".equalsIgnoreCase(node.getRole()) ? "#ffd700" : "#38bdf8";
        String statusBg = node.isOnline() ? "rgba(16, 185, 129, 0.2)" : "rgba(239, 68, 68, 0.2)";
        String statusColor = node.isOnline() ? "#10b981" : "#ef4444";
        String statusLabel = node.isOnline() ? "● ONLINE" : "○ OFFLINE";

        Header nodeTitle = Header.of(4, "🖥️ " + node.getName());
        nodeTitle.modifier(new Modifier().style("margin: 0; font-size: 1.1rem; color: #ffffff;"));

        Badge roleBadge = Badge.of(node.getRole());
        roleBadge.modifier(new Modifier().style("background: rgba(0, 212, 255, 0.15); border: 1px solid " + roleColor + "; color: " + roleColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Badge statusBadge = Badge.of(statusLabel);
        statusBadge.modifier(new Modifier().style("background: " + statusBg + "; border: 1px solid " + statusColor + "; color: " + statusColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Row headerRight = Row.of(roleBadge, statusBadge);
        headerRight.modifier(new Modifier().style("display: flex; gap: 0.4rem; align-items: center;"));

        Row cardHeader = Row.of(nodeTitle, headerRight);
        cardHeader.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem;"));

        Paragraph addressInfo = Paragraph.of("🌐 " + node.getHost() + ":" + node.getPort() + " | Raft Term: #" + node.getRaftTerm());
        addressInfo.modifier(new Modifier().style("font-size: 0.8rem; color: var(--text-muted); margin: 0 0 1rem 0;"));

        RawHtml statsHtml = RawHtml.of(String.format("""
            <table style="width: 100%%; font-size: 0.82rem; border-collapse: collapse; margin-bottom: 1rem;">
              <tr><td style="padding: 4px 0; color: var(--text-muted);">CPU:</td><td style="text-align: right; font-weight: 700; color: #ffd700;">%.1f%%</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Heap JVM:</td><td style="text-align: right; font-weight: 700; color: #10b981;">%d MB / %d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Panama Direct:</td><td style="text-align: right; font-weight: 700; color: #00d4ff;">%d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Almacén SSTable:</td><td style="text-align: right; font-weight: 700; color: var(--text-main);">%,d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Loom Virtual Threads:</td><td style="text-align: right; font-weight: 700; color: var(--text-main);">%d vthreads</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Rendimiento QPS:</td><td style="text-align: right; font-weight: 700; color: #38bdf8;">%,d ops/s (%.2f ms)</td></tr>
            </table>
        """, node.getCpuPercent(), node.getHeapUsedMb(), node.getHeapMaxMb(), node.getPanamaDirectMb(), node.getDiskUsedMb(), node.getVirtualThreads(), node.getQps(), node.getLatencyMs()));

        // Acciones sobre el nodo: Conmutar Online / Eliminar / Explorar
        Link toggleBtn = Link.of("/cluster?action=toggle_node&nodeId=" + node.getId(), node.isOnline() ? "Apagar Nodo" : "Encender Nodo");
        toggleBtn.modifier(new Modifier().cssClass(node.isOnline() ? "app-btn-warning" : "app-btn-primary"));

        Link exploreBtn = Link.of("/explorer?node=" + node.getId(), "Ver Datos");
        exploreBtn.modifier(new Modifier().cssClass("app-btn-secondary"));

        List<Widget> actionWidgets = new ArrayList<>();
        actionWidgets.add(toggleBtn);
        actionWidgets.add(exploreBtn);

        if (!"node-01".equalsIgnoreCase(node.getId())) {
            Link deleteBtn = Link.of("/cluster?action=remove_node&nodeId=" + node.getId(), "Expulsar");
            deleteBtn.modifier(new Modifier().cssClass("app-btn-danger").attribute("onclick", "return confirm('¿Está seguro de expulsar este nodo del clúster?');"));
            actionWidgets.add(deleteBtn);
        }

        Row actionsRow = Row.of(actionWidgets.toArray(new Widget[0]));
        actionsRow.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; justify-content: flex-end;"));

        Div card = Div.of(cardHeader, addressInfo, statsHtml, actionsRow);
        card.modifier(new Modifier().cssClass("ui-card").style("border-top: 3px solid " + (node.isOnline() ? "#00d4ff" : "#ef4444") + ";"));
        return card;
    }

    private Widget buildReplicationMatrix(List<ServerNodeInfo> nodes) {
        Header header = Header.of(3, "🚚 Matriz de Replicación y Enrutamiento Raft Consensus");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Nodo</th><th>Host:Puerto</th><th>Rol Quorum</th><th>Estado</th><th>Latencia Réplica</th><th>Sincronización WAL</th></tr></thead>");
        table.append("<tbody>");

        for (ServerNodeInfo n : nodes) {
            String roleBadge = "LEADER".equalsIgnoreCase(n.getRole()) 
                ? "<span style='color:#ffd700; font-weight:700;'>LEADER (Coord.)</span>"
                : "<span style='color:#38bdf8;'>FOLLOWER (Sync)</span>";
            String statusBadge = n.isOnline()
                ? "<span style='color:#10b981; font-weight:700;'>● 100% ONLINE</span>"
                : "<span style='color:#ef4444; font-weight:700;'>○ DESCONECTADO</span>";

            table.append("<tr>");
            table.append("<td><strong>").append(n.getName()).append("</strong></td>");
            table.append("<td>").append(n.getHost()).append(":").append(n.getPort()).append("</td>");
            table.append("<td>").append(roleBadge).append("</td>");
            table.append("<td>").append(statusBadge).append("</td>");
            table.append("<td>").append(n.getLatencyMs()).append(" ms</td>");
            table.append("<td><span style='color:#10b981;'>COMMITTED (Raft Term #").append(n.getRaftTerm()).append(")</span></td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(header, RawHtml.of(table.toString()));
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }
}

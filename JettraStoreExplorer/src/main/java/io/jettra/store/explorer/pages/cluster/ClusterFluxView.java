package io.jettra.store.explorer.pages.cluster;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
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
        // Consultar el registro del clúster en tiempo real (forzar refresco de salud para evitar datos estáticos o en caché)
        plugin.refreshAllNodeStatusesIfStale(100L);

        List<ServerNodeInfo> nodes = plugin.getServerNodes();

        long activeCount = nodes.stream().filter(ServerNodeInfo::isActive).count();
        long totalQps = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getQps).sum();
        long totalPanama = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getPanamaDirectMb).sum();

        List<Widget> rootChildren = new ArrayList<>();

        // 1. Mensaje Flash si existe
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            rootChildren.add(flash);
        }

        // Verificación y Detección de Estado INACTIVE para nodo PRIMARY y Réplicas Secundarias (dinámico según consenso Raft)
        ServerNodeInfo primaryNode = plugin.getPrimaryNode();

        boolean primaryActive = primaryNode != null && primaryNode.isActive();
        List<ServerNodeInfo> inactiveOthers = nodes.stream()
                .filter(n -> n != primaryNode && !n.isActive())
                .toList();

        // Banner de Alerta Crítica si el nodo PRIMARY está INACTIVE (con placeholder reactivo si está oculto)
        if (!primaryActive) {
            String primaryDesc = primaryNode != null
                ? (primaryNode.getName() + " (" + primaryNode.getHost() + ":" + primaryNode.getPort() + ")")
                : "node-01";
            String errorDetail = (primaryNode != null && primaryNode.getLastError() != null && !primaryNode.getLastError().isBlank())
                ? "<br/><span style=\"font-size:0.8rem; color:#fecaca;\"><strong>Diagnóstico:</strong> " + escapeHtml(primaryNode.getLastError()) + "</span>"
                : "";
            Div primaryInactiveAlert = Div.of(
                RawHtml.of("<div id=\"banner-primary-inactive\" style=\"display:flex; align-items:flex-start; gap:0.85rem; background: rgba(239, 68, 68, 0.18); border: 2px solid #ef4444; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.25rem; box-shadow: 0 4px 14px rgba(239, 68, 68, 0.25);\">" +
                           "<span style=\"font-size:1.75rem; line-height:1;\">🚨</span>" +
                           "<div>" +
                           "<div style=\"font-size:1.05rem; font-weight:800; color:#ef4444; letter-spacing:0.01em;\">ESTADO INACTIVE • ESTADO OFFLINE: NODO PRIMARY DESCONECTADO</div>" +
                           "<div style=\"font-size:0.85rem; color:#fca5a5; margin-top:3px;\">El nodo maestro principal <strong>" + escapeHtml(primaryDesc) + "</strong> no responde a los latidos TCP. Las transacciones de escritura distribuida y el quórum Raft se encuentran suspendidos. Verifique el socket y los parámetros en <code>microprofile-config.properties</code>." + errorDetail + "</div>" +
                           "</div></div>")
            );
            rootChildren.add(primaryInactiveAlert);
        } else {
            rootChildren.add(Div.of(RawHtml.of("<div id=\"banner-primary-inactive\" style=\"display:none; align-items:flex-start; gap:0.85rem; background: rgba(239, 68, 68, 0.18); border: 2px solid #ef4444; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.25rem; box-shadow: 0 4px 14px rgba(239, 68, 68, 0.25);\"></div>")));
        }

        // Banner de Advertencia si hay Nodos Secundarios INACTIVE
        if (!inactiveOthers.isEmpty()) {
            StringBuilder inactiveNames = new StringBuilder();
            for (int i = 0; i < inactiveOthers.size(); i++) {
                ServerNodeInfo off = inactiveOthers.get(i);
                if (i > 0) inactiveNames.append(", ");
                inactiveNames.append("<strong>").append(off.getName()).append("</strong> (").append(off.getHost()).append(":").append(off.getPort()).append(")");
            }
            Div othersInactiveAlert = Div.of(
                RawHtml.of("<div id=\"banner-secondaries-inactive\" style=\"display:flex; align-items:flex-start; gap:0.85rem; background: rgba(245, 158, 11, 0.15); border: 1px solid #f59e0b; border-radius: 8px; padding: 0.85rem 1.15rem; margin-bottom: 1.25rem;\">" +
                           "<span style=\"font-size:1.6rem; line-height:1;\">⚠️</span>" +
                           "<div>" +
                           "<div style=\"font-size:0.95rem; font-weight:800; color:#f59e0b;\">ESTADO INACTIVE • ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS (" + inactiveOthers.size() + ")</div>" +
                           "<div style=\"font-size:0.82rem; color:#fde68a; margin-top:3px;\">Los siguientes nodos réplica no responden en su puerto de replicación: " + inactiveNames.toString() + ". El clúster opera con quórum reducido.</div>" +
                           "</div></div>")
            );
            rootChildren.add(othersInactiveAlert);
        } else {
            rootChildren.add(Div.of(RawHtml.of("<div id=\"banner-secondaries-inactive\" style=\"display:none; align-items:flex-start; gap:0.85rem; background: rgba(245, 158, 11, 0.15); border: 1px solid #f59e0b; border-radius: 8px; padding: 0.85rem 1.15rem; margin-bottom: 1.25rem;\"></div>")));
        }

        // Banner Informativo si todos los nodos están ACTIVE
        if (primaryActive && inactiveOthers.isEmpty()) {
            Div allActiveBanner = Div.of(
                RawHtml.of("<div id=\"banner-all-active\" style=\"display:flex; align-items:center; gap:0.6rem; background: rgba(16, 185, 129, 0.12); border: 1px solid rgba(16, 185, 129, 0.4); border-radius: 8px; padding: 0.75rem 1rem; margin-bottom: 1.25rem;\">" +
                           "<span style=\"font-size:1.2rem;\">✅</span>" +
                           "<span style=\"font-size:0.85rem; color:#34d399; font-weight:600;\">Todos los nodos del clúster (incluyendo PRIMARY y réplicas secundarias) se encuentran en estado ACTIVE con latencias normales y quórum óptimo.</span>" +
                           "</div>")
            );
            rootChildren.add(allActiveBanner);
        } else {
            rootChildren.add(Div.of(RawHtml.of("<div id=\"banner-all-active\" style=\"display:none; align-items:center; gap:0.6rem; background: rgba(16, 185, 129, 0.12); border: 1px solid rgba(16, 185, 129, 0.4); border-radius: 8px; padding: 0.75rem 1rem; margin-bottom: 1.25rem;\"><span style=\"font-size:1.2rem;\">✅</span><span style=\"font-size:0.85rem; color:#34d399; font-weight:600;\">Todos los nodos del clúster (incluyendo PRIMARY y réplicas secundarias) se encuentran en estado ACTIVE con latencias normales y quórum óptimo.</span></div>")));
        }

        // 2. Tarjetas KPI Superiores
        Div kpi1 = buildKpiCard("Nodos ACTIVE", activeCount + " / " + nodes.size(), "Quorum Raft: " + (activeCount >= (nodes.size()/2 + 1) ? "Óptimo (100%)" : "Degradado"), "#00d4ff", "kpi-active-nodes-val");
        Div kpi2 = buildKpiCard("Ingesta Global QPS", String.format("%,d", totalQps) + " ops/s", "Flujo multimodelo distribuido", "#10b981", "kpi-qps-val");
        Div kpi3 = buildKpiCard("Panama Direct Memory", String.format("%.2f GB", totalPanama / 1024.0), "Zero-GC Direct Foreign Memory", "#a855f7", "kpi-panama-val");
        Div kpi4 = buildKpiCard("Modo de Topología", plugin.isMultinodeActive() ? "MULTINODO RAFT" : "STANDALONE LOCAL", plugin.getMultinodeDisplay(), plugin.isMultinodeActive() ? "#00d4ff" : "#f59e0b", "kpi-topology-val");

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
        nodesGrid.modifier(new Modifier().attribute("id", "nodes-container-grid").style("display: grid; grid-template-columns: repeat(auto-fit, minmax(330px, 1fr)); gap: 1.25rem; margin-bottom: 2rem;"));
        rootChildren.add(nodesGrid);

        // 5. Tabla de Matriz de Replicación y Enrutamiento Raft
        Widget replicationMatrix = buildReplicationMatrix(nodes);
        rootChildren.add(replicationMatrix);

        // 6. Control y sondeo en tiempo real mediante WebSocket/SSE/REST Polling
        rootChildren.add(buildLifecycleControlScript());

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Div buildKpiCard(String title, String value, String sub, String accentColor, String valId) {
        Div t = Div.of(Text.of(title));
        t.modifier(new Modifier().cssClass("kpi-title"));

        Div v = Div.of(Text.of(value));
        Modifier vMod = new Modifier().cssClass("kpi-value").style("color:" + accentColor + ";");
        if (valId != null) vMod.attribute("id", valId);
        v.modifier(vMod);

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
                  <option value="PRIMARY">PRIMARY</option>
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
              <div style="display: flex; gap: 0.5rem;">
                <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Agregar Nodo</button>
                <a href="/cluster?action=check_nodes" class="app-btn-secondary" style="height: 38px; display: inline-flex; align-items: center;" title="Efectuar sondeo TCP en vivo">🔄 Probar Conectividad</a>
              </div>
            </form>
        """);

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
        return card;
    }

    private Div buildNodeCard(ServerNodeInfo node) {
        boolean isPrimary = "PRIMARY".equalsIgnoreCase(node.getRole()) || "LEADER".equalsIgnoreCase(node.getRole());
        String roleDisplay = isPrimary ? "PRIMARY" : node.getRole();
        String roleColor = isPrimary ? "#ffd700" : "#38bdf8";
        boolean isActive = node.isActive();
        String statusBg = isActive ? "rgba(16, 185, 129, 0.2)" : "rgba(239, 68, 68, 0.25)";
        String statusColor = isActive ? "#10b981" : "#ef4444";
        String statusLabel = isActive ? String.format(java.util.Locale.US, "● ACTIVE (%.1f ms)", node.getLatencyMs()) : "○ INACTIVE";

        Header nodeTitle = Header.of(4, "🖥️ " + node.getName());
        nodeTitle.modifier(new Modifier().style("margin: 0; font-size: 1.1rem; color: #ffffff;"));

        Badge roleBadge = Badge.of(roleDisplay);
        roleBadge.modifier(new Modifier()
            .attribute("id", "node-role-badge-" + node.getId())
            .style("background: rgba(0, 212, 255, 0.15); border: 1px solid " + roleColor + "; color: " + roleColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Badge statusBadge = Badge.of(statusLabel);
        statusBadge.modifier(new Modifier()
            .attribute("id", "node-badge-" + node.getId())
            .style("background: " + statusBg + "; border: 1px solid " + statusColor + "; color: " + statusColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Row headerRight = Row.of(roleBadge, statusBadge);
        headerRight.modifier(new Modifier().style("display: flex; gap: 0.4rem; align-items: center;"));

        Row cardHeader = Row.of(nodeTitle, headerRight);
        cardHeader.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem;"));

        Paragraph addressInfo = Paragraph.of("🌐 " + node.getHost() + ":" + node.getPort() + " | Raft Term: #" + node.getRaftTerm());
        addressInfo.modifier(new Modifier().style("font-size: 0.8rem; color: var(--text-muted); margin: 0 0 0.75rem 0;"));

        // Indicador de Estado Prominente dentro de la tarjeta
        Div nodeStatusBox;
        if (isActive) {
            nodeStatusBox = Div.of(RawHtml.of("<div style=\"background:rgba(16, 185, 129, 0.15); border:1px solid #10b981; border-radius:6px; padding:0.4rem 0.6rem; margin-bottom:0.75rem; font-size:0.8rem; color:#34d399; font-weight:700; display:flex; align-items:center; justify-content:space-between;\">" +
                    "<span>🟢 ESTADO: ACTIVE • 🟢 ESTADO: ONLINE</span><span style=\"font-weight:400; font-size:0.75rem;\">Latencia: " + String.format(java.util.Locale.US, "%.2f ms", node.getLatencyMs()) + "</span></div>"));
        } else {
            String roleOfflineMsg = isPrimary ? "🔴 NODO PRIMARY INACTIVE • 🔴 NODO PRIMARY OFFLINE (Inaccesible)" : "🔴 ESTADO: INACTIVE • 🔴 ESTADO: OFFLINE (Inaccesible)";
            String reason = (node.getLastError() != null && !node.getLastError().isBlank()) ? escapeHtml(node.getLastError()) : "Socket TCP no responde";
            nodeStatusBox = Div.of(RawHtml.of("<div style=\"background:rgba(239, 68, 68, 0.2); border:1px solid #ef4444; border-radius:6px; padding:0.45rem 0.65rem; margin-bottom:0.75rem; font-size:0.8rem; color:#ef4444; font-weight:800; display:flex; flex-direction:column; gap:2px;\">" +
                    "<div style=\"display:flex; align-items:center; justify-content:space-between;\"><span>" + roleOfflineMsg + "</span></div>" +
                    "<span style=\"font-weight:500; font-size:0.72rem; color:#fca5a5;\">" + reason + "</span></div>"));
        }
        nodeStatusBox.modifier(new Modifier().attribute("id", "node-status-box-" + node.getId()));

        double displayCpu = isActive ? node.getCpuPercent() : 0.0;
        long displayQps = isActive ? node.getQps() : 0L;
        String latencyText = isActive ? String.format(java.util.Locale.US, "%.2f ms", node.getLatencyMs()) : "N/A (INACTIVE)";

        RawHtml statsHtml = RawHtml.of(String.format(java.util.Locale.US, """
            <table style="width: 100%%; font-size: 0.82rem; border-collapse: collapse; margin-bottom: 1rem;">
              <tr><td style="padding: 4px 0; color: var(--text-muted);">CPU:</td><td style="text-align: right; font-weight: 700; color: #ffd700;">%.1f%%</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Heap JVM:</td><td style="text-align: right; font-weight: 700; color: #10b981;">%d MB / %d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Panama Direct:</td><td style="text-align: right; font-weight: 700; color: #00d4ff;">%d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Almacén SSTable:</td><td style="text-align: right; font-weight: 700; color: var(--text-main);">%,d MB</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Loom Virtual Threads:</td><td style="text-align: right; font-weight: 700; color: var(--text-main);">%d vthreads</td></tr>
              <tr><td style="padding: 4px 0; color: var(--text-muted);">Rendimiento QPS:</td><td style="text-align: right; font-weight: 700; color: #38bdf8;">%,d ops/s (%s)</td></tr>
            </table>
        """, displayCpu, isActive ? node.getHeapUsedMb() : 0L, node.getHeapMaxMb(), isActive ? node.getPanamaDirectMb() : 0L, node.getDiskUsedMb(), isActive ? node.getVirtualThreads() : 0, displayQps, latencyText));

        // Acciones sobre el nodo: Apagar / Encender en tiempo real vía LifecycleManager + Explorar + Expulsar
        String jsSafeId = node.getId().replace("'", "\\'");
        String jsSafeName = node.getName().replace("'", "\\'");
        JettraButton lifecycleBtn = isActive
            ? JettraButton.of("Apagar Nodo").severity(JettraButton.Severity.WARNING)
            : JettraButton.of("Encender Nodo").severity(JettraButton.Severity.PRIMARY);
        lifecycleBtn.modifier(new Modifier()
            .attribute("id", "node-lifecycle-btn-" + node.getId())
            .attribute("data-node-name", node.getName())
            .cssClass(isActive ? "app-btn-warning" : "app-btn-primary")
            .attribute("onclick", isActive
                ? "shutdownNode('" + jsSafeId + "', '" + jsSafeName + "', " + isPrimary + ")"
                : "startNode('" + jsSafeId + "', '" + jsSafeName + "', " + isPrimary + ")"));

        Link exploreBtn = Link.of("/explorer?node=" + node.getId(), "Ver Datos");
        exploreBtn.modifier(new Modifier().cssClass("app-btn-secondary"));

        List<Widget> actionWidgets = new ArrayList<>();
        actionWidgets.add(lifecycleBtn);
        actionWidgets.add(exploreBtn);

        if (!"node-01".equalsIgnoreCase(node.getId())) {
            Link deleteBtn = Link.of("/cluster?action=remove_node&nodeId=" + node.getId(), "Expulsar");
            deleteBtn.modifier(new Modifier().cssClass("app-btn-danger").attribute("onclick", "return confirm('¿Está seguro de expulsar este nodo del clúster?');"));
            actionWidgets.add(deleteBtn);
        }

        Row actionsRow = Row.of(actionWidgets.toArray(new Widget[0]));
        actionsRow.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; justify-content: flex-end;"));

        Div card = Div.of(cardHeader, addressInfo, nodeStatusBox, statsHtml, actionsRow);
        card.modifier(new Modifier()
            .attribute("id", "node-card-" + node.getId())
            .cssClass("ui-card")
            .style("border-top: 4px solid " + (isActive ? (isPrimary ? "#ffd700" : "#10b981") : "#ef4444") + ";"));
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
            boolean isPrimary = "PRIMARY".equalsIgnoreCase(n.getRole()) || "LEADER".equalsIgnoreCase(n.getRole());
            boolean active = n.isActive();
            String roleBadge = isPrimary 
                ? "<span style='color:#ffd700; font-weight:700;'>PRIMARY (Leader)</span>"
                : "<span style='color:#38bdf8;'>FOLLOWER (Sync)</span>";
            String statusBadge = active
                ? "<span style='color:#10b981; font-weight:700; background:rgba(16,185,129,0.15); padding:2px 8px; border-radius:4px; border:1px solid #10b981;'>● ACTIVE (" + String.format(java.util.Locale.US, "%.2f ms", n.getLatencyMs()) + ")</span>"
                : (isPrimary
                    ? "<span style='color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;'>○ INACTIVE • ○ OFFLINE (NODO PRIMARY CAÍDO)</span>"
                    : "<span style='color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;'>○ INACTIVE • ○ OFFLINE (Inaccesible)</span>");

            String latencyCol = active
                ? String.format(java.util.Locale.US, "%.2f ms", n.getLatencyMs())
                : "<span style='color:#ef4444; font-size:0.8rem;'>Sin respuesta</span>";
            String walCol = active
                ? "<span style='color:#10b981;'>COMMITTED (Raft Term #" + n.getRaftTerm() + ")</span>"
                : "<span style='color:#ef4444; font-weight:700;'>DISCONNECTED (Sin latido)</span>";

            table.append("<tr id='matrix-row-").append(escapeHtml(n.getId())).append("'>");
            table.append("<td><strong>").append(escapeHtml(n.getName())).append("</strong></td>");
            table.append("<td>").append(escapeHtml(n.getHost())).append(":").append(n.getPort()).append("</td>");
            table.append("<td id='matrix-role-").append(escapeHtml(n.getId())).append("'>").append(roleBadge).append("</td>");
            table.append("<td id='matrix-status-").append(escapeHtml(n.getId())).append("'>").append(statusBadge).append("</td>");
            table.append("<td id='matrix-latency-").append(escapeHtml(n.getId())).append("'>").append(latencyCol).append("</td>");
            table.append("<td id='matrix-wal-").append(escapeHtml(n.getId())).append("'>").append(walCol).append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(header, RawHtml.of(table.toString()));
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildLifecycleControlScript() {
        return RawHtml.of("""
            <div id="lifecycle-toast" style="display:none; position:fixed; bottom:1.25rem; right:1.25rem; z-index:1200; background:#0f172a; border:1px solid #334155; color:#f8fafc; padding:0.75rem 1rem; border-radius:8px; box-shadow:0 10px 25px rgba(0,0,0,0.45); font-size:0.85rem; max-width:380px;"></div>
            <script>
            function showLifecycleToast(text, color) {
                const t = document.getElementById('lifecycle-toast');
                if (!t) return;
                t.style.borderColor = color || '#00d4ff';
                t.style.display = 'block';
                t.innerHTML = text;
                clearTimeout(t._timer);
                t._timer = setTimeout(function() { t.style.display = 'none'; }, 4500);
            }

            function updateNodeStatusUI(nodeId, role, isPrimary, isActive, latencyMs, raftTerm, errorReason) {
                const roleBadge = document.getElementById('node-role-badge-' + nodeId);
                if (roleBadge) {
                    const roleColor = isPrimary ? '#ffd700' : '#38bdf8';
                    roleBadge.textContent = isPrimary ? 'PRIMARY' : (role || 'SECONDARY');
                    roleBadge.style.borderColor = roleColor;
                    roleBadge.style.color = roleColor;
                }
                const badge = document.getElementById('node-badge-' + nodeId);
                if (badge) {
                    badge.textContent = isActive ? ('● ACTIVE (' + (latencyMs > 0 ? latencyMs.toFixed(1) : '0.5') + ' ms)') : '○ INACTIVE';
                    badge.style.background = isActive ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.25)';
                    badge.style.borderColor = isActive ? '#10b981' : '#ef4444';
                    badge.style.color = isActive ? '#10b981' : '#ef4444';
                }
                const box = document.getElementById('node-status-box-' + nodeId);
                if (box) {
                    if (isActive) {
                        box.innerHTML = '<div style="background:rgba(16, 185, 129, 0.15); border:1px solid #10b981; border-radius:6px; padding:0.4rem 0.6rem; margin-bottom:0.75rem; font-size:0.8rem; color:#34d399; font-weight:700; display:flex; align-items:center; justify-content:space-between;">'
                            + '<span>🟢 ESTADO: ACTIVE • 🟢 ESTADO: ONLINE</span><span style="font-weight:400; font-size:0.75rem;">Latencia: ' + (latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms</span></div>';
                    } else {
                        const roleMsg = isPrimary ? '🔴 NODO PRIMARY INACTIVE • 🔴 NODO PRIMARY OFFLINE (Inaccesible)' : '🔴 ESTADO: INACTIVE • 🔴 ESTADO: OFFLINE (Inaccesible)';
                        box.innerHTML = '<div style="background:rgba(239, 68, 68, 0.2); border:1px solid #ef4444; border-radius:6px; padding:0.45rem 0.65rem; margin-bottom:0.75rem; font-size:0.8rem; color:#ef4444; font-weight:800; display:flex; flex-direction:column; gap:2px;">'
                            + '<div style="display:flex; align-items:center; justify-content:space-between;"><span>' + roleMsg + '</span></div>'
                            + '<span style="font-weight:500; font-size:0.72rem; color:#fca5a5;">' + (errorReason || 'Sin respuesta a latidos') + '</span></div>';
                    }
                }
                const card = document.getElementById('node-card-' + nodeId);
                if (card) {
                    card.style.borderTop = '4px solid ' + (isActive ? (isPrimary ? '#ffd700' : '#10b981') : '#ef4444');
                }
                const btn = document.getElementById('node-lifecycle-btn-' + nodeId);
                if (btn) {
                    btn.textContent = isActive ? 'Apagar Nodo' : 'Encender Nodo';
                    btn.className = isActive ? 'app-btn-warning' : 'app-btn-primary';
                    btn.disabled = false;
                    const safeName = btn.getAttribute('data-node-name') || nodeId;
                    btn.onclick = function() {
                        if (isActive) {
                            shutdownNode(nodeId, safeName, isPrimary);
                        } else {
                            startNode(nodeId, safeName, isPrimary);
                        }
                    };
                }
                const matrixRoleCell = document.getElementById('matrix-role-' + nodeId);
                if (matrixRoleCell) {
                    matrixRoleCell.innerHTML = isPrimary
                        ? "<span style='color:#ffd700; font-weight:700;'>PRIMARY (Leader)</span>"
                        : "<span style='color:#38bdf8;'>FOLLOWER (Sync)</span>";
                }
                const matrixCell = document.getElementById('matrix-status-' + nodeId);
                if (matrixCell) {
                    matrixCell.innerHTML = isActive
                        ? '<span style="color:#10b981; font-weight:700; background:rgba(16,185,129,0.15); padding:2px 8px; border-radius:4px; border:1px solid #10b981;">● ACTIVE (' + (latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms)</span>'
                        : '<span style="color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;">○ INACTIVE • ○ OFFLINE (' + (isPrimary ? 'NODO PRIMARY CAÍDO' : 'Inaccesible') + ')</span>';
                }
                const latCell = document.getElementById('matrix-latency-' + nodeId);
                if (latCell) {
                    latCell.innerHTML = isActive ? ((latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms') : '<span style="color:#ef4444; font-size:0.8rem;">Sin respuesta</span>';
                }
                const walCell = document.getElementById('matrix-wal-' + nodeId);
                if (walCell) {
                    const termVal = (typeof raftTerm !== 'undefined' && raftTerm !== null) ? raftTerm : 0;
                    walCell.innerHTML = isActive
                        ? "<span style='color:#10b981;'>COMMITTED (Raft Term #" + termVal + ")</span>"
                        : "<span style='color:#ef4444; font-weight:700;'>DISCONNECTED (Sin latido)</span>";
                }
            }

            let lastClusterNodeCount = null;
            let lastPrimaryNodeId = null;
            function applyClusterLiveData(data) {
                if (!data || !Array.isArray(data.nodes)) return;

                if (lastClusterNodeCount !== null && lastClusterNodeCount !== data.nodes.length) {
                    lastClusterNodeCount = data.nodes.length;
                    showLifecycleToast('🔄 Cambio en la topología del clúster detectado. Actualizando...', '#00d4ff');
                    setTimeout(function() { window.location.reload(); }, 400);
                    return;
                }
                lastClusterNodeCount = data.nodes.length;

                if (lastPrimaryNodeId && data.primaryNodeId && lastPrimaryNodeId !== data.primaryNodeId && data.primaryActive) {
                    showLifecycleToast('👑 Consenso Raft: Nuevo nodo PRIMARY elegido → <strong>' + data.primaryNodeId + '</strong>', '#ffd700');
                }
                if (data.primaryNodeId) {
                    lastPrimaryNodeId = data.primaryNodeId;
                }

                let anyPrimaryInactive = false;
                let anySecondaryInactive = false;
                let inactivePrimaryNode = null;
                let inactiveSecondaries = [];

                data.nodes.forEach(function(node) {
                    const isPrimary = (node.role === 'PRIMARY' || node.role === 'LEADER');
                    const isActive = (node.status === 'ACTIVE' || node.active === true || node.online === true);
                    if (!isActive) {
                        if (isPrimary) {
                            anyPrimaryInactive = true;
                            inactivePrimaryNode = node;
                        } else {
                            anySecondaryInactive = true;
                            inactiveSecondaries.push(node);
                        }
                    }
                    updateNodeStatusUI(node.id, node.role, isPrimary, isActive, node.latencyMs || 0, node.raftTerm || 0, node.lastError);
                });

                const kpiEl = document.getElementById('kpi-active-nodes-val');
                if (kpiEl && typeof data.activeCount !== 'undefined') {
                    kpiEl.textContent = data.activeCount + ' / ' + data.totalCount;
                }

                const primaryBanner = document.getElementById('banner-primary-inactive');
                if (primaryBanner) {
                    if (anyPrimaryInactive) {
                        const desc = inactivePrimaryNode ? (inactivePrimaryNode.name + ' (' + inactivePrimaryNode.host + ':' + inactivePrimaryNode.port + ')') : 'PRIMARY';
                        const errHtml = (inactivePrimaryNode && inactivePrimaryNode.lastError) ? '<br/><span style="font-size:0.8rem; color:#fecaca;"><strong>Diagnóstico:</strong> ' + inactivePrimaryNode.lastError + '</span>' : '';
                        primaryBanner.innerHTML = '<span style="font-size:1.75rem; line-height:1;">🚨</span><div><div style="font-size:1.05rem; font-weight:800; color:#ef4444; letter-spacing:0.01em;">ESTADO INACTIVE • ESTADO OFFLINE: NODO PRIMARY DESCONECTADO</div><div style="font-size:0.85rem; color:#fca5a5; margin-top:3px;">El nodo maestro principal <strong>' + desc + '</strong> no responde a los latidos TCP. Las transacciones de escritura distribuida y el quórum Raft se encuentran suspendidos.' + errHtml + '</div></div>';
                        primaryBanner.style.display = 'flex';
                    } else {
                        primaryBanner.style.display = 'none';
                    }
                }
                const secondaryBanner = document.getElementById('banner-secondaries-inactive');
                if (secondaryBanner) {
                    if (anySecondaryInactive) {
                        const names = inactiveSecondaries.map(function(n) { return '<strong>' + n.name + '</strong> (' + n.host + ':' + n.port + ')'; }).join(', ');
                        secondaryBanner.innerHTML = '<span style="font-size:1.6rem; line-height:1;">⚠️</span><div><div style="font-size:0.95rem; font-weight:800; color:#f59e0b;">ESTADO INACTIVE • ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS (' + inactiveSecondaries.length + ')</div><div style="font-size:0.82rem; color:#fde68a; margin-top:3px;">Los siguientes nodos réplica no responden en su puerto de replicación: ' + names + '. El clúster opera con quórum reducido.</div></div>';
                        secondaryBanner.style.display = 'flex';
                    } else {
                        secondaryBanner.style.display = 'none';
                    }
                }
                const allActiveBanner = document.getElementById('banner-all-active');
                if (allActiveBanner) {
                    allActiveBanner.style.display = (!anyPrimaryInactive && !anySecondaryInactive) ? 'flex' : 'none';
                }
            }
            window.applyClusterLiveData = applyClusterLiveData;

            async function pollClusterLiveStatus() {
                try {
                    const resp = await fetch('/api/cluster/status', { cache: 'no-store' });
                    if (!resp.ok) return;
                    const data = await resp.json();
                    applyClusterLiveData(data);
                } catch (e) {
                    // Ignorar errores transitorios de polling
                }
            }

            // Sondeo continuo en segundo plano + sincronización SSE inmediata sin recargar la página
            setInterval(pollClusterLiveStatus, 2000);
            window.pollClusterLiveStatus = pollClusterLiveStatus;

            async function shutdownNode(nodeId, nodeName, isPrimary) {
                const btn = document.getElementById('node-lifecycle-btn-' + nodeId);
                if (btn) {
                    btn.setAttribute('data-node-name', nodeName);
                    btn.disabled = true;
                    btn.textContent = 'Apagando...';
                }
                showLifecycleToast('⏳ Enviando señal de apagado a <strong>' + nodeName + '</strong>...', '#f59e0b');
                try {
                    const resp = await fetch('/api/cluster/nodes/shutdown', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ nodeId: nodeId })
                    });
                    const data = await resp.json();
                    if (data && (data.status === 'INACTIVE' || data.state === 'OFFLINE' || data.stopped || data.offlineVerified)) {
                        updateNodeStatusUI(nodeId, data.role || (isPrimary ? 'PRIMARY' : 'SECONDARY'), isPrimary, false, 0, 0, data.lastError || data.message);
                        const envHint = data.dockerContainer ? ('Docker [' + data.dockerContainer + ']') : (data.mechanism || 'LifecycleManager');
                        showLifecycleToast('🔴 <strong>' + nodeName + '</strong> pasó a estado <strong>INACTIVE / OFFLINE</strong> (' + envHint + ').', '#ef4444');
                        await pollClusterLiveStatus();
                    } else {
                        if (btn) { btn.disabled = false; btn.textContent = 'Apagar Nodo'; }
                        showLifecycleToast('⚠️ No se confirmó apagado de ' + nodeName, '#f59e0b');
                    }
                } catch (err) {
                    if (btn) { btn.disabled = false; btn.textContent = 'Apagar Nodo'; }
                    showLifecycleToast('❌ Error enviando apagado: ' + err.message, '#ef4444');
                }
            }

            async function startNode(nodeId, nodeName, isPrimary) {
                const btn = document.getElementById('node-lifecycle-btn-' + nodeId);
                if (btn) {
                    btn.setAttribute('data-node-name', nodeName);
                    btn.disabled = true;
                    btn.textContent = 'Verificando...';
                }
                showLifecycleToast('🔄 Verificando disponibilidad de <strong>' + nodeName + '</strong>...', '#00d4ff');
                try {
                    const resp = await fetch('/api/cluster/nodes/start', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ nodeId: nodeId })
                    });
                    const data = await resp.json();
                    const activeNow = data.active || data.online || (data.status === 'ACTIVE');
                    updateNodeStatusUI(nodeId, isPrimary ? 'PRIMARY' : 'SECONDARY', isPrimary, activeNow, 0.5, 0, data.lastError || data.message);
                    showLifecycleToast((activeNow ? '🟢 ' : '🔴 ') + (data.message || 'Verificación completada'), activeNow ? '#10b981' : '#ef4444');
                    await pollClusterLiveStatus();
                } catch (err) {
                    if (btn) { btn.disabled = false; btn.textContent = 'Encender Nodo'; }
                    showLifecycleToast('❌ Error al verificar nodo: ' + err.message, '#ef4444');
                }
            }
            </script>
            """);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

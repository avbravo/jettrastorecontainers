package io.jettra.store.explorer.pages.cluster;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.pages.template.FluxUi;
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
        String primaryBannerStyle = "display:" + (!primaryActive ? "flex" : "none") + "; align-items:flex-start; gap:0.85rem; background: rgba(239, 68, 68, 0.18); border: 2px solid #ef4444; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.25rem; box-shadow: 0 4px 14px rgba(239, 68, 68, 0.25);";
        Div primaryInactiveBanner = Div.of();
        if (!primaryActive) {
            String primaryDesc = primaryNode != null
                ? (primaryNode.getName() + " (" + primaryNode.getHost() + ":" + primaryNode.getPort() + ")")
                : "node-01";
            List<Widget> msgParts = new ArrayList<>();
            msgParts.add(FluxUi.coloredSpan("ESTADO INACTIVE • ESTADO OFFLINE: NODO PRIMARY DESCONECTADO",
                    "font-size:1.05rem; font-weight:800; color:#ef4444; letter-spacing:0.01em; display:block;"));
            msgParts.add(FluxUi.coloredSpan(
                    "El nodo maestro principal " + primaryDesc + " no responde a los latidos TCP. Las transacciones de escritura distribuida y el quórum Raft se encuentran suspendidos. Verifique el socket y los parámetros en microprofile-config.properties.",
                    "font-size:0.85rem; color:#fca5a5; margin-top:3px; display:block;"));
            if (primaryNode != null && primaryNode.getLastError() != null && !primaryNode.getLastError().isBlank()) {
                msgParts.add(FluxUi.coloredSpan("Diagnóstico: " + primaryNode.getLastError(),
                        "font-size:0.8rem; color:#fecaca; display:block; margin-top:2px;"));
            }
            primaryInactiveBanner = Div.of(
                    FluxUi.coloredSpan("🚨", "font-size:1.75rem; line-height:1;"),
                    Div.of(msgParts.toArray(new Widget[0]))
            );
        }
        primaryInactiveBanner.id("banner-primary-inactive");
        primaryInactiveBanner.modifier(new Modifier().attribute("id", "banner-primary-inactive").style(primaryBannerStyle));
        rootChildren.add(primaryInactiveBanner);

        // Banner de Advertencia si hay Nodos Secundarios INACTIVE
        String secBannerStyle = "display:" + (!inactiveOthers.isEmpty() ? "flex" : "none") + "; align-items:flex-start; gap:0.85rem; background: rgba(245, 158, 11, 0.15); border: 1px solid #f59e0b; border-radius: 8px; padding: 0.85rem 1.15rem; margin-bottom: 1.25rem;";
        Div secondariesInactiveBanner = Div.of();
        if (!inactiveOthers.isEmpty()) {
            String inactiveNames = inactiveOthers.stream()
                .map(off -> off.getName() + " (" + off.getHost() + ":" + off.getPort() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
            secondariesInactiveBanner = Div.of(
                    FluxUi.coloredSpan("⚠️", "font-size:1.6rem; line-height:1;"),
                    Div.of(
                            FluxUi.coloredSpan("ESTADO INACTIVE • ESTADO OFFLINE: NODOS SECUNDARIOS DESCONECTADOS (" + inactiveOthers.size() + ")",
                                    "font-size:0.95rem; font-weight:800; color:#f59e0b; display:block;"),
                            FluxUi.coloredSpan("Los siguientes nodos réplica no responden en su puerto de replicación: " + inactiveNames + ". El clúster opera con quórum reducido.",
                                    "font-size:0.82rem; color:#fde68a; margin-top:3px; display:block;")
                    )
            );
        }
        secondariesInactiveBanner.id("banner-secondaries-inactive");
        secondariesInactiveBanner.modifier(new Modifier().attribute("id", "banner-secondaries-inactive").style(secBannerStyle));
        rootChildren.add(secondariesInactiveBanner);

        // Banner Informativo si todos los nodos están ACTIVE
        String allActiveStyle = "display:" + (primaryActive && inactiveOthers.isEmpty() ? "flex" : "none") + "; align-items:center; gap:0.6rem; background: rgba(16, 185, 129, 0.12); border: 1px solid rgba(16, 185, 129, 0.4); border-radius: 8px; padding: 0.75rem 1rem; margin-bottom: 1.25rem;";
        Div allActiveBanner = Div.of(
                FluxUi.coloredSpan("✅", "font-size:1.2rem;"),
                FluxUi.coloredSpan("Todos los nodos del clúster (incluyendo PRIMARY y réplicas secundarias) se encuentran en estado ACTIVE con latencias normales y quórum óptimo.",
                        "font-size:0.85rem; color:#34d399; font-weight:600;")
        );
        allActiveBanner.id("banner-all-active");
        allActiveBanner.modifier(new Modifier().attribute("id", "banner-all-active").style(allActiveStyle));
        rootChildren.add(allActiveBanner);

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

        Widget portInput = FluxUi.textInput("nodePort", "50001", "", true);
        portInput.modifier(new Modifier().cssClass("app-input").attribute("type", "number").attribute("required", "required"));
        Widget ramInput = FluxUi.textInput("nodeRam", "2048", "", true);
        ramInput.modifier(new Modifier().cssClass("app-input").attribute("type", "number").attribute("required", "required"));
        Link checkBtn = FluxUi.linkButton("/cluster?action=check_nodes", "🔄 Probar Conectividad", "app-btn-secondary", "height: 38px; display: inline-flex; align-items: center;");
        checkBtn.modifier(new Modifier().cssClass("app-btn-secondary").style("height: 38px; display: inline-flex; align-items: center;")
                .attribute("title", "Efectuar sondeo TCP en vivo"));

        Form addNodeForm = Form.of(
                FluxUi.hidden("action", "add_node"),
                FluxUi.fieldColumn("ID del Nodo", FluxUi.textInput("nodeId", "", "node-05", true), "flex: 1; min-width: 140px;"),
                FluxUi.fieldColumn("Nombre del Nodo", FluxUi.textInput("nodeName", "", "node-05-replica", true), "flex: 1.5; min-width: 180px;"),
                FluxUi.fieldColumn("Rol Raft", FluxUi.dropdown("nodeRole", List.of("FOLLOWER", "PRIMARY", "LEADER", "EDGE"), "FOLLOWER"), "flex: 1; min-width: 130px;"),
                FluxUi.fieldColumn("Host / IP", FluxUi.textInput("nodeHost", "127.0.0.1", "", true), "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Puerto", portInput, "flex: 0.8; min-width: 90px;"),
                FluxUi.fieldColumn("Max RAM (MB)", ramInput, "flex: 0.8; min-width: 110px;"),
                Div.of(
                        FluxUi.submitButton("➕ Agregar Nodo", "app-btn-primary", "height: 38px;"),
                        checkBtn
                ).modifier(new Modifier().style("display: flex; gap: 0.5rem;"))
        ).action("/cluster").method("POST");
        addNodeForm.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Div card = Div.of(header, addNodeForm);
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
        roleBadge.id("node-role-badge-" + node.getId());
        roleBadge.modifier(new Modifier()
            .attribute("id", "node-role-badge-" + node.getId())
            .attribute("data-node-role-badge", node.getId())
            .style("background: rgba(0, 212, 255, 0.15); border: 1px solid " + roleColor + "; color: " + roleColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Badge statusBadge = Badge.of(statusLabel);
        statusBadge.id("node-badge-" + node.getId());
        statusBadge.modifier(new Modifier()
            .attribute("id", "node-badge-" + node.getId())
            .attribute("data-node-badge", node.getId())
            .style("background: " + statusBg + "; border: 1px solid " + statusColor + "; color: " + statusColor + "; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

        Row headerRight = Row.of(roleBadge, statusBadge);
        headerRight.modifier(new Modifier().style("display: flex; gap: 0.4rem; align-items: center;"));

        Row cardHeader = Row.of(nodeTitle, headerRight);
        cardHeader.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem;"));

        Paragraph addressInfo = Paragraph.of("🌐 " + node.getHost() + ":" + node.getPort() + " | Raft Term: #" + node.getRaftTerm());
        addressInfo.id("node-addr-" + node.getId());
        addressInfo.modifier(new Modifier()
            .attribute("id", "node-addr-" + node.getId())
            .attribute("data-node-addr", node.getId())
            .style("font-size: 0.8rem; color: var(--text-muted); margin: 0 0 0.75rem 0;"));

        // Indicador de Estado Prominente dentro de la tarjeta
        Div nodeStatusBox;
        if (isActive) {
            Widget inner = Div.of(
                    FluxUi.coloredSpan("🟢 ESTADO: ACTIVE • 🟢 ESTADO: ONLINE", "color:#34d399; font-weight:700;"),
                    FluxUi.coloredSpan(String.format(java.util.Locale.US, "Latencia: %.2f ms", node.getLatencyMs()), "font-weight:400; font-size:0.75rem;")
            ).modifier(new Modifier().style("background:rgba(16, 185, 129, 0.15); border:1px solid #10b981; border-radius:6px; padding:0.4rem 0.6rem; margin-bottom:0.75rem; font-size:0.8rem; color:#34d399; font-weight:700; display:flex; align-items:center; justify-content:space-between;"));
            nodeStatusBox = Div.of(inner);
        } else {
            String roleOfflineMsg = isPrimary ? "🔴 NODO PRIMARY INACTIVE • 🔴 NODO PRIMARY OFFLINE (Inaccesible)" : "🔴 ESTADO: INACTIVE • 🔴 ESTADO: OFFLINE (Inaccesible)";
            String reason = (node.getLastError() != null && !node.getLastError().isBlank()) ? node.getLastError() : "Socket TCP no responde";
            Widget inner = Div.of(
                    Div.of(FluxUi.coloredSpan(roleOfflineMsg, null)).modifier(new Modifier().style("display:flex; align-items:center; justify-content:space-between;")),
                    FluxUi.coloredSpan(reason, "font-weight:500; font-size:0.72rem; color:#fca5a5;")
            ).modifier(new Modifier().style("background:rgba(239, 68, 68, 0.2); border:1px solid #ef4444; border-radius:6px; padding:0.45rem 0.65rem; margin-bottom:0.75rem; font-size:0.8rem; color:#ef4444; font-weight:800; display:flex; flex-direction:column; gap:2px;"));
            nodeStatusBox = Div.of(inner);
        }
        nodeStatusBox.id("node-status-box-" + node.getId());
        nodeStatusBox.modifier(new Modifier()
            .attribute("id", "node-status-box-" + node.getId())
            .attribute("data-node-status-box", node.getId()));

        double displayCpu = isActive ? node.getCpuPercent() : 0.0;
        long displayQps = isActive ? node.getQps() : 0L;
        String latencyText = isActive ? String.format(java.util.Locale.US, "%.2f ms", node.getLatencyMs()) : "N/A (INACTIVE)";

        Widget statsBox = Div.of(
                nodeStatRow("CPU:", String.format(java.util.Locale.US, "%.1f%%", displayCpu), "node-cpu-", node.getId(), "#ffd700"),
                nodeStatRow("Heap JVM:", (isActive ? node.getHeapUsedMb() : 0L) + " MB / " + node.getHeapMaxMb() + " MB", "node-heap-", node.getId(), "#10b981"),
                nodeStatRow("Panama Direct:", (isActive ? node.getPanamaDirectMb() : 0L) + " MB", "node-panama-", node.getId(), "#00d4ff"),
                nodeStatRow("Almacén SSTable:", String.format(java.util.Locale.US, "%,d MB", node.getDiskUsedMb()), "node-disk-", node.getId(), "var(--text-main)"),
                nodeStatRow("Loom Virtual Threads:", (isActive ? node.getVirtualThreads() : 0) + " vthreads", "node-vthreads-", node.getId(), "var(--text-main)"),
                nodeStatRow("Rendimiento QPS:", String.format(java.util.Locale.US, "%,d ops/s (%s)", displayQps, latencyText), "node-qps-", node.getId(), "#38bdf8")
        ).modifier(new Modifier().style("width: 100%; font-size: 0.82rem; margin-bottom: 1rem;"));

        // Acciones sobre el nodo: Apagar / Encender en tiempo real vía LifecycleManager + Explorar + Expulsar
        String jsSafeId = node.getId().replace("'", "\\'");
        String jsSafeName = node.getName().replace("'", "\\'");
        JettraButton lifecycleBtn = isActive
            ? JettraButton.of("Apagar Nodo").severity(JettraButton.Severity.WARNING)
            : JettraButton.of("Actualizar Nodo").severity(JettraButton.Severity.PRIMARY);
        lifecycleBtn.id("node-lifecycle-btn-" + node.getId());
        lifecycleBtn.modifier(new Modifier()
            .attribute("id", "node-lifecycle-btn-" + node.getId())
            .attribute("data-node-lifecycle-btn", node.getId())
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
            if (isActive) {
                JettraButton blockedRemoveBtn = JettraButton.of("Expulsar (Detener primero)").severity(JettraButton.Severity.SECONDARY);
                blockedRemoveBtn.modifier(new Modifier()
                    .cssClass("app-btn-secondary")
                    .style("opacity: 0.6; cursor: not-allowed;")
                    .attribute("title", "No se puede remover un nodo activo (RUNNING). Primero debe detener el nodo y luego removerlo.")
                    .attribute("onclick", "alert('Operación bloqueada: El nodo " + jsSafeId + " está ACTIVO (RUNNING). Primero debe detenerlo con el botón Apagar Nodo y luego removerlo del clúster.'); return false;"));
                actionWidgets.add(blockedRemoveBtn);
            } else {
                Link deleteBtn = Link.of("/cluster?action=remove_node&nodeId=" + node.getId(), "Expulsar");
                deleteBtn.modifier(new Modifier()
                    .cssClass("app-btn-danger")
                    .attribute("onclick", "return confirm('¿Está seguro de remover el nodo detenido " + jsSafeId + " del clúster? Los demás nodos activos seguirán operando sin interrupciones.');"));
                actionWidgets.add(deleteBtn);
            }
        }

        Row actionsRow = Row.of(actionWidgets.toArray(new Widget[0]));
        actionsRow.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; justify-content: flex-end;"));

        Div card = Div.of(cardHeader, addressInfo, nodeStatusBox, statsBox, actionsRow);
        card.id("node-card-" + node.getId());
        card.modifier(new Modifier()
            .attribute("id", "node-card-" + node.getId())
            .attribute("data-node-card", node.getId())
            .cssClass("ui-card")
            .style("border-top: 4px solid " + (isActive ? (isPrimary ? "#ffd700" : "#10b981") : "#ef4444") + ";"));
        return card;
    }

    private static Widget nodeStatRow(String label, String value, String idPrefix, String nodeId, String color) {
        String dataAttr = "data-" + idPrefix.substring(0, idPrefix.length() - 1);
        Div valDiv = Div.of(Text.of(value));
        valDiv.modifier(new Modifier()
                .attribute("id", idPrefix + nodeId)
                .attribute(dataAttr, nodeId)
                .style("text-align: right; font-weight: 700; color: " + color + ";"));
        return Div.of(
                FluxUi.coloredSpan(label, "color: var(--text-muted);"),
                valDiv
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; padding: 4px 0;"));
    }

    private Widget buildReplicationMatrix(List<ServerNodeInfo> nodes) {
        Header header = Header.of(3, "🚚 Matriz de Replicación y Enrutamiento Raft Consensus");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        List<Widget> headers = List.of(
            Text.of("Nodo"),
            Text.of("Host:Puerto"),
            Text.of("Rol Quorum"),
            Text.of("Estado"),
            Text.of("Latencia Réplica"),
            Text.of("Sincronización WAL")
        );

        List<List<Widget>> rows = new java.util.ArrayList<>();
        for (ServerNodeInfo n : nodes) {
            boolean isPrimary = "PRIMARY".equalsIgnoreCase(n.getRole()) || "LEADER".equalsIgnoreCase(n.getRole());
            boolean active = n.isActive();
            Widget roleInner = isPrimary
                ? FluxUi.coloredSpan("PRIMARY (Leader)", "color:#ffd700; font-weight:700;")
                : FluxUi.coloredSpan("FOLLOWER (Sync)", "color:#38bdf8;");
            Widget statusInner = active
                ? FluxUi.coloredSpan("● ACTIVE (" + String.format(java.util.Locale.US, "%.2f ms", n.getLatencyMs()) + ")",
                        "color:#10b981; font-weight:700; background:rgba(16,185,129,0.15); padding:2px 8px; border-radius:4px; border:1px solid #10b981;")
                : (isPrimary
                    ? FluxUi.coloredSpan("○ INACTIVE • ○ OFFLINE (NODO PRIMARY CAÍDO)",
                            "color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;")
                    : FluxUi.coloredSpan("○ INACTIVE • ○ OFFLINE (Inaccesible)",
                            "color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;"));

            Widget latencyInner = active
                ? Text.of(String.format(java.util.Locale.US, "%.2f ms", n.getLatencyMs()))
                : FluxUi.coloredSpan("Sin respuesta", "color:#ef4444; font-size:0.8rem;");
            Widget walInner = active
                ? FluxUi.coloredSpan("COMMITTED (Raft Term #" + n.getRaftTerm() + ")", "color:#10b981;")
                : FluxUi.coloredSpan("DISCONNECTED (Sin latido)", "color:#ef4444; font-weight:700;");

            Widget nameCell = Div.of(Text.of(n.getName()))
                .modifier(new Modifier().style("font-weight: 700; color: var(--text-main);"));
            Widget hostCell = Text.of(n.getHost() + ":" + n.getPort());
            Widget roleCell = Div.of(roleInner)
                .modifier(new Modifier().attribute("id", "matrix-role-" + n.getId()));
            Widget statusCell = Div.of(statusInner)
                .modifier(new Modifier().attribute("id", "matrix-status-" + n.getId()));
            Widget latencyCell = Div.of(latencyInner)
                .modifier(new Modifier().attribute("id", "matrix-latency-" + n.getId()));
            Widget walCell = Div.of(walInner)
                .modifier(new Modifier().attribute("id", "matrix-wal-" + n.getId()));

            rows.add(List.of(nameCell, hostCell, roleCell, statusCell, latencyCell, walCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(header, datatable);
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildLifecycleControlScript() {
        Div toast = Div.of();
        toast.id("lifecycle-toast");
        toast.modifier(new Modifier().attribute("id", "lifecycle-toast")
                .style("display:none; position:fixed; bottom:1.25rem; right:1.25rem; z-index:1200; background:#0f172a; border:1px solid #334155; color:#f8fafc; padding:0.75rem 1rem; border-radius:8px; box-shadow:0 10px 25px rgba(0,0,0,0.45); font-size:0.85rem; max-width:380px;"));
        RawScript script = RawScript.of("""
            function showLifecycleToast(text, color) {
                const t = document.getElementById('lifecycle-toast');
                if (!t) return;
                t.style.borderColor = color || '#00d4ff';
                t.style.display = 'block';
                t.innerHTML = text;
                clearTimeout(t._timer);
                t._timer = setTimeout(function() { t.style.display = 'none'; }, 4500);
            }

            function getNodeElement(type, nodeId) {
                return document.getElementById(type + '-' + nodeId) || document.querySelector('[data-' + type + '="' + nodeId + '"]');
            }

            function createDynamicNodeCard(node, isPrimary, isActive) {
                const grid = document.getElementById('nodes-container-grid');
                if (!grid) return;
                const card = document.createElement('div');
                card.id = 'node-card-' + node.id;
                card.setAttribute('data-node-card', node.id);
                card.className = 'ui-card';
                card.style.cssText = 'border-top: 4px solid ' + (isActive ? (isPrimary ? '#ffd700' : '#10b981') : '#ef4444') + '; display: flex; flex-direction: column; justify-content: space-between; position: relative;';

                const roleColor = isPrimary ? '#ffd700' : '#38bdf8';
                const statusBg = isActive ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.25)';
                const statusColor = isActive ? '#10b981' : '#ef4444';
                const latStr = (node.latencyMs > 0 ? Number(node.latencyMs).toFixed(1) : '0.5');

                card.innerHTML = '<div'
                    + '><div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem;">'
                    + '<h4 style="margin: 0; font-size: 1.1rem; color: #ffffff;">🖥️ ' + (node.name || node.id) + '</h4>'
                    + '<div style="display: flex; gap: 0.4rem; align-items: center;">'
                    + '<span id="node-role-badge-' + node.id + '" data-node-role-badge="' + node.id + '" style="background: rgba(0, 212, 255, 0.15); border: 1px solid ' + roleColor + '; color: ' + roleColor + '; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;">' + (isPrimary ? 'PRIMARY' : (node.role || 'SECONDARY')) + '</span>'
                    + '<span id="node-badge-' + node.id + '" data-node-badge="' + node.id + '" style="background: ' + statusBg + '; border: 1px solid ' + statusColor + '; color: ' + statusColor + '; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;">' + (isActive ? '● ACTIVE (' + latStr + ' ms)' : '○ INACTIVE') + '</span>'
                    + '</div></div>'
                    + '<p id="node-addr-' + node.id + '" data-node-addr="' + node.id + '" style="font-size: 0.8rem; color: var(--text-muted); margin: 0 0 0.75rem 0;">🌐 ' + node.host + ':' + node.port + ' | Raft Term: #' + (node.raftTerm || 0) + '</p>'
                    + '<div id="node-status-box-' + node.id + '" data-node-status-box="' + node.id + '"></div>'
                    + '<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 0.6rem; font-size: 0.82rem; margin-bottom: 1rem;">'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">Uso CPU</span><strong id="node-cpu-' + node.id + '" data-node-cpu="' + node.id + '" style="color: var(--text-main); font-size: 0.95rem;">' + (Number(node.cpuPercent) || 0).toFixed(1) + '%</strong></div>'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">RAM Heap JVM</span><strong id="node-heap-' + node.id + '" data-node-heap="' + node.id + '" style="color: var(--text-main); font-size: 0.95rem;">' + (node.heapUsedMb || 0) + ' MB / ' + (node.heapMaxMb || 2048) + ' MB</strong></div>'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">Direct Panama RAM</span><strong id="node-panama-' + node.id + '" data-node-panama="' + node.id + '" style="color: #00d4ff; font-size: 0.95rem;">' + (node.panamaDirectMb || 0) + ' MB</strong></div>'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">Disco LSM Utilizado</span><strong id="node-disk-' + node.id + '" data-node-disk="' + node.id + '" style="color: var(--text-main); font-size: 0.95rem;">' + (node.diskUsedMb || 0) + ' MB</strong></div>'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">Hilos Virtuales Loom</span><strong id="node-vthreads-' + node.id + '" data-node-vthreads="' + node.id + '" style="color: var(--text-main); font-size: 0.95rem;">' + (node.virtualThreads || 0) + ' vthreads</strong></div>'
                    + '<div style="background: var(--bg-subtle); padding: 0.5rem 0.65rem; border-radius: 6px; border: 1px solid var(--border-color);"><span style="display: block; font-size: 0.72rem; color: var(--text-muted);">Operaciones (QPS / Ping)</span><strong id="node-qps-' + node.id + '" data-node-qps="' + node.id + '" style="color: var(--text-main); font-size: 0.95rem;">' + Number(node.qps || 0).toLocaleString() + ' ops/s</strong></div>'
                    + '</div></div>'
                    + '<div style="display: flex; gap: 0.5rem; align-items: center; border-top: 1px solid var(--border-color); padding-top: 0.85rem; justify-content: flex-end;">'
                    + '<button type="button" id="node-lifecycle-btn-' + node.id + '" data-node-lifecycle-btn="' + node.id + '" data-node-name="' + (node.name || node.id) + '" class="' + (isActive ? 'app-btn-warning' : 'app-btn-primary') + '">' + (isActive ? 'Apagar Nodo' : 'Actualizar Nodo') + '</button>'
                    + '</div>';
                grid.appendChild(card);
            }

            function updateNodeStatusUI(nodeId, role, isPrimary, isActive, latencyMs, raftTerm, errorReason, nodeStats) {
                // 1. Tarjeta: Badge de Rol Raft
                const roleBadge = getNodeElement('node-role-badge', nodeId);
                if (roleBadge) {
                    const roleColor = isPrimary ? '#ffd700' : '#38bdf8';
                    roleBadge.textContent = isPrimary ? 'PRIMARY' : (role || 'SECONDARY');
                    roleBadge.style.borderColor = roleColor;
                    roleBadge.style.color = roleColor;
                }

                // 2. Tarjeta: Badge de Estado (ACTIVE / INACTIVE)
                const badge = getNodeElement('node-badge', nodeId);
                if (badge) {
                    badge.textContent = isActive ? ('● ACTIVE (' + (latencyMs > 0 ? latencyMs.toFixed(1) : '0.5') + ' ms)') : '○ INACTIVE';
                    badge.style.background = isActive ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.25)';
                    badge.style.borderColor = isActive ? '#10b981' : '#ef4444';
                    badge.style.color = isActive ? '#10b981' : '#ef4444';
                }

                // 3. Tarjeta: Cuadro Prominente de Estado
                const box = getNodeElement('node-status-box', nodeId);
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

                // 4. Tarjeta: Borde superior y fondo reactivo
                const card = getNodeElement('node-card', nodeId);
                if (card) {
                    card.style.borderTop = '4px solid ' + (isActive ? (isPrimary ? '#ffd700' : '#10b981') : '#ef4444');
                }

                // 5. Tarjeta: Botón de Ciclo de Vida (Apagar / Encender)
                const btn = getNodeElement('node-lifecycle-btn', nodeId);
                if (btn) {
                    btn.textContent = isActive ? 'Apagar Nodo' : 'Actualizar Nodo';
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

                // 6. Tarjeta: Dirección y Raft Term
                const addrEl = getNodeElement('node-addr', nodeId);
                if (addrEl && typeof raftTerm !== 'undefined') {
                    const hostPort = nodeStats ? (nodeStats.host + ':' + nodeStats.port) : '';
                    addrEl.textContent = '🌐 ' + (hostPort || '127.0.0.1') + ' | Raft Term: #' + raftTerm;
                }

                // 7. Tarjeta: Métricas dinámicas en tiempo real
                if (nodeStats) {
                    const cpuEl = getNodeElement('node-cpu', nodeId);
                    if (cpuEl) {
                        cpuEl.textContent = isActive ? ((Number(nodeStats.cpuPercent) || 0.0).toFixed(1) + '%') : '0.0%';
                    }
                    const heapEl = getNodeElement('node-heap', nodeId);
                    if (heapEl && nodeStats.heapMaxMb) {
                        heapEl.textContent = (isActive ? (nodeStats.heapUsedMb || 0) : 0) + ' MB / ' + nodeStats.heapMaxMb + ' MB';
                    }
                    const panamaEl = getNodeElement('node-panama', nodeId);
                    if (panamaEl) {
                        panamaEl.textContent = (isActive ? (nodeStats.panamaDirectMb || 0) : 0) + ' MB';
                    }
                    const diskEl = getNodeElement('node-disk', nodeId);
                    if (diskEl && typeof nodeStats.diskUsedMb !== 'undefined') {
                        diskEl.textContent = Number(nodeStats.diskUsedMb).toLocaleString() + ' MB';
                    }
                    const vthreadsEl = getNodeElement('node-vthreads', nodeId);
                    if (vthreadsEl) {
                        vthreadsEl.textContent = (isActive ? (nodeStats.virtualThreads || 0) : 0) + ' vthreads';
                    }
                    const qpsEl = getNodeElement('node-qps', nodeId);
                    if (qpsEl) {
                        const latStr = isActive ? ((latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms') : 'N/A (INACTIVE)';
                        qpsEl.textContent = (isActive ? Number(nodeStats.qps || 0).toLocaleString() : 0) + ' ops/s (' + latStr + ')';
                    }
                }

                // 8. Matriz Raft: Sincronización idéntica y simultánea con la tabla
                const matrixRoleCell = getNodeElement('matrix-role', nodeId);
                if (matrixRoleCell) {
                    matrixRoleCell.innerHTML = isPrimary
                        ? "<span style='color:#ffd700; font-weight:700;'>PRIMARY (Leader)</span>"
                        : "<span style='color:#38bdf8;'>FOLLOWER (Sync)</span>";
                }
                const matrixCell = getNodeElement('matrix-status', nodeId);
                if (matrixCell) {
                    matrixCell.innerHTML = isActive
                        ? '<span style="color:#10b981; font-weight:700; background:rgba(16,185,129,0.15); padding:2px 8px; border-radius:4px; border:1px solid #10b981;">● ACTIVE (' + (latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms)</span>'
                        : '<span style="color:#ef4444; font-weight:800; background:rgba(239,68,68,0.22); padding:2px 8px; border-radius:4px; border:1px solid #ef4444;">○ INACTIVE • ○ OFFLINE (' + (isPrimary ? 'NODO PRIMARY CAÍDO' : 'Inaccesible') + ')</span>';
                }
                const latCell = getNodeElement('matrix-latency', nodeId);
                if (latCell) {
                    latCell.innerHTML = isActive ? ((latencyMs > 0 ? latencyMs.toFixed(2) : '0.10') + ' ms') : '<span style="color:#ef4444; font-size:0.8rem;">Sin respuesta</span>';
                }
                const walCell = getNodeElement('matrix-wal', nodeId);
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
                    if (!getNodeElement('node-card', node.id)) {
                        createDynamicNodeCard(node, isPrimary, isActive);
                    }
                    if (!isActive) {
                        if (isPrimary) {
                            anyPrimaryInactive = true;
                            inactivePrimaryNode = node;
                        } else {
                            anySecondaryInactive = true;
                            inactiveSecondaries.push(node);
                        }
                    }
                    updateNodeStatusUI(node.id, node.role, isPrimary, isActive, node.latencyMs || 0, node.raftTerm || 0, node.lastError, node);
                });

                // Actualizar KPIs superiores
                const kpiEl = document.getElementById('kpi-active-nodes-val');
                if (kpiEl && typeof data.activeCount !== 'undefined') {
                    kpiEl.textContent = data.activeCount + ' / ' + data.totalCount;
                }
                const kpiQps = document.getElementById('kpi-qps-val');
                if (kpiQps && typeof data.totalQps !== 'undefined') {
                    kpiQps.textContent = Number(data.totalQps).toLocaleString() + ' ops/s';
                }
                const kpiPanama = document.getElementById('kpi-panama-val');
                if (kpiPanama && typeof data.totalPanama !== 'undefined') {
                    kpiPanama.textContent = (Number(data.totalPanama) / 1024.0).toFixed(2) + ' GB';
                }

                // Actualizar Banners de Alerta
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

            // 1. Conexión reactiva en tiempo real mediante SSE (Server-Sent Events)
            if (window.EventSource) {
                try {
                    const sse = new EventSource('/api/cluster/events');
                    sse.addEventListener('cluster_status', function(e) {
                        try {
                            const liveData = JSON.parse(e.data);
                            applyClusterLiveData(liveData);
                        } catch (err) {}
                    });
                    sse.onerror = function() {
                        // El navegador reintenta automáticamente la conexión SSE
                    };
                } catch (e) {}
            }

            // 2. Sondeo periódico continuo de respaldo (cada 1000 ms) sincronizado con /api/cluster/status
            setInterval(pollClusterLiveStatus, 1000);
            window.pollClusterLiveStatus = pollClusterLiveStatus;

            // 3. Disparo de sincronización inmediata al renderizar la vista
            setTimeout(pollClusterLiveStatus, 100);

            async function shutdownNode(nodeId, nodeName, isPrimary) {
                const btn = getNodeElement('node-lifecycle-btn', nodeId);
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
                        updateNodeStatusUI(nodeId, data.role || (isPrimary ? 'PRIMARY' : 'SECONDARY'), isPrimary, false, 0, 0, data.lastError || data.message, data);
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
                const btn = getNodeElement('node-lifecycle-btn', nodeId);
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
                    updateNodeStatusUI(nodeId, isPrimary ? 'PRIMARY' : 'SECONDARY', isPrimary, activeNow, 0.5, 0, data.lastError || data.message, data);
                    showLifecycleToast((activeNow ? '🟢 ' : '🔴 ') + (data.message || 'Verificación completada'), activeNow ? '#10b981' : '#ef4444');
                    await pollClusterLiveStatus();
                } catch (err) {
                    if (btn) { btn.disabled = false; btn.textContent = 'Actualizar Nodo'; }
                    showLifecycleToast('❌ Error al verificar nodo: ' + err.message, '#ef4444');
                }
            }
            """);
        return Div.of(toast, script);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

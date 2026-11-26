package io.jettra.store.explorer.pages.connections;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.ConnectionProfile;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Gestor de Perfiles de Conexión a Clústeres JettraStore en JettraFlux.
 */
public class ConnectionManagerFluxView extends FluxView {

    private final String message;

    public ConnectionManagerFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ConnectionProfile active = plugin.getActiveConnection();
        List<ConnectionProfile> profiles = plugin.getConnectionProfiles();

        List<Widget> rootChildren = new ArrayList<>();

        // 1. Mensaje Flash
        if (message != null && !message.isBlank()) {
            boolean isErr = message.toLowerCase().contains("error") || message.toLowerCase().contains("inválid");
            String border = isErr ? "#ef4444" : "#00d4ff";
            String bg = isErr ? "rgba(239, 68, 68, 0.15)" : "rgba(0, 212, 255, 0.15)";
            String fg = isErr ? "#f87171" : "#00d4ff";
            Div flash = Div.of(Text.of((isErr ? "⚠️ " : "ℹ️ ") + message));
            flash.modifier(new Modifier()
                    .style("background: " + bg + "; border: 1px solid " + border + "; color: " + fg + "; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            rootChildren.add(flash);
        }

        // 1.5 Panel Principal RF-32: Conexión Activa del Clúster (conecction.config / cluster.ip.nodes) + Estado en Vivo desde PRIMARY
        rootChildren.add(buildPrimaryConnectionCard(plugin));

        // 2. Formulario para Crear / Actualizar Perfil de Conexión
        Widget addProfileCard = buildAddProfileCard();
        rootChildren.add(addProfileCard);

        // 3. Grid de Perfiles de Conexión
        Header gridHeader = Header.of(3, "🔌 Perfiles de Conexión Configurados");
        gridHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 1rem 0;"));
        rootChildren.add(gridHeader);

        List<Widget> cardList = new ArrayList<>();

        for (ConnectionProfile cp : profiles) {
            boolean isActive = active != null && active.getId().equalsIgnoreCase(cp.getId());
            String borderColor = isActive ? "#00d4ff" : "var(--border-subtle)";

            Header title = Header.of(4, "🔌 " + cp.getName());
            title.modifier(new Modifier().style("margin: 0; font-size: 1.05rem; color: #ffd700;"));

            List<Widget> badgeList = new ArrayList<>();
            if (cp.isDefault()) {
                Badge bDef = Badge.of("★ PREDETERMINADO");
                bDef.modifier(new Modifier().style("background: rgba(245, 158, 11, 0.2); border: 1px solid #f59e0b; color: #fbbf24; font-size: 0.7rem; padding: 0.1rem 0.4rem; border-radius: 9999px; font-weight: 700;"));
                badgeList.add(bDef);
            }
            if (isActive) {
                Badge bAct = Badge.of("● CONECTADO");
                bAct.modifier(new Modifier().style("background: rgba(16, 185, 129, 0.2); border: 1px solid #10b981; color: #10b981; font-size: 0.7rem; padding: 0.1rem 0.4rem; border-radius: 9999px; font-weight: 700;"));
                badgeList.add(bAct);
            }

            Div badges = Div.of(badgeList.toArray(new Widget[0]));
            badges.modifier(new Modifier().style("display: flex; gap: 0.35rem; align-items: center;"));

            Div headerRow = Div.of(title, badges);
            headerRow.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem;"));

            Widget idRow = Div.of(Text.of("ID: "), FluxUi.coloredSpan(cp.getId(), "color: var(--text-main); font-family: monospace;"))
                    .modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.35rem;"));
            Widget urlRow = Div.of(Text.of("URL TCP / Cluster: "), FluxUi.coloredSpan(cp.getUrl(), "color: #00d4ff; font-family: monospace;"))
                    .modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.35rem;"));
            Widget userRow = Div.of(Text.of("Usuario: "), FluxUi.coloredSpan(cp.getUsername(), "color: var(--text-main); font-family: monospace;"))
                    .modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin-bottom: 1rem;"));

            List<Widget> btnForms = new ArrayList<>();
            Form connectForm = Form.of(
                    FluxUi.hidden("action", "connect"),
                    FluxUi.hidden("id", cp.getId()),
                    FluxUi.submitButton("⚡ Conectar", "app-btn-primary", "padding: 0.35rem 0.75rem; font-size: 0.8rem;")
            ).action("/connections").method("POST");
            connectForm.modifier(new Modifier().style("display:inline;"));
            btnForms.add(connectForm);

            if (!cp.isDefault()) {
                Form defForm = Form.of(
                        FluxUi.hidden("action", "set_default"),
                        FluxUi.hidden("id", cp.getId()),
                        FluxUi.submitButton("Fijar Default", "app-btn-secondary", "padding:0.35rem 0.75rem; font-size:0.8rem;")
                ).action("/connections").method("POST");
                defForm.modifier(new Modifier().style("display:inline;"));
                btnForms.add(defForm);

                if (!isActive) {
                    Form delForm = Form.of(
                            FluxUi.hidden("action", "delete"),
                            FluxUi.hidden("id", cp.getId()),
                            FluxUi.submitButton("🗑️", "app-btn-danger", "padding:0.35rem 0.75rem; font-size:0.8rem;")
                    ).action("/connections").method("POST");
                    delForm.modifier(new Modifier().style("display:inline;").attribute("onsubmit", "return confirm('¿Eliminar perfil?');"));
                    btnForms.add(delForm);
                }
            }

            Widget actionsRow = Div.of(btnForms.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display: flex; gap: 0.5rem; flex-wrap: wrap;"));

            Div card = Div.of(headerRow, idRow, urlRow, userRow, actionsRow);
            card.modifier(new Modifier().cssClass("ui-card").style("border: 2px solid " + borderColor + ";"));
            cardList.add(card);
        }

        Div grid = Div.of(cardList.toArray(new Widget[0]));
        grid.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 1.25rem;"));
        rootChildren.add(grid);

        String liveScript = """
                (function() {
                  async function syncConnClusterState() {
                    try {
                      const res = await fetch('/api/cluster/status', { cache: 'no-store', credentials: 'same-origin' });
                      if (!res.ok) return;
                      const data = await res.json();
                      const epEl = document.getElementById('rf32-live-primary');
                      const verEl = document.getElementById('rf32-live-version');
                      const cntEl = document.getElementById('rf32-live-counts');
                      const nodesEl = document.getElementById('rf32-live-nodes');
                      if (epEl) epEl.textContent = (data.primaryNodeId || 'N/A') + (data.resolvedPrimaryEndpoint ? ' @ ' + data.resolvedPrimaryEndpoint : '');
                      if (verEl) verEl.textContent = 'v' + (data.clusterStatusVersion || 0);
                      if (cntEl) cntEl.textContent = (data.activeCount || 0) + ' / ' + (data.totalCount || 0) + ' ONLINE';
                      if (nodesEl && Array.isArray(data.nodes)) {
                        nodesEl.textContent = data.nodes.map(n => n.id + ' [' + n.role + ' | ' + n.host + ':' + n.port + ' | ' + n.state + ']').join('  •  ');
                      }
                    } catch (e) {}
                  }
                  if (window.EventSource) {
                    try {
                      const sse = new EventSource('/api/cluster/events');
                      sse.addEventListener('cluster_status', syncConnClusterState);
                    } catch (e) {}
                  }
                  setInterval(syncConnClusterState, 1500);
                })();
                """;
        rootChildren.add(RawScript.of(liveScript));

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Widget buildPrimaryConnectionCard(JettraStorePlugin plugin) {
        var connState = plugin.getClusterConnectionState();
        var snap = plugin.getLatestPrimaryClusterStatus();
        var primary = plugin.getPrimaryNode();
        String currentSeeds = connState != null ? connState.clusterIpNodes() : "127.0.0.1:50001";
        String currentUser = connState != null ? connState.username() : "admin";
        String sourceLabel = (connState != null && connState.loadedFromConnectionFile())
                ? "conecction.config (" + connState.connectionFile() + ")"
                : "microprofile-config.properties (cluster.ip.nodes)";
        String primaryText = snap != null
                ? snap.primaryNodeId() + " @ " + snap.primaryHost() + ":" + snap.primaryGrpcPort()
                : (primary != null ? primary.getId() + " @ " + primary.getHost() + ":" + primary.getPort() : "Sin PRIMARY detectado");
        long version = snap != null ? snap.stateVersion() : 0L;
        var allNodes = plugin.getServerNodes();
        long onlineCount = allNodes.stream().filter(io.jettra.store.explorer.model.ServerNodeInfo::isActive).count();

        StringBuilder nodesSummary = new StringBuilder();
        for (int i = 0; i < allNodes.size(); i++) {
            var n = allNodes.get(i);
            if (i > 0) nodesSummary.append("  •  ");
            nodesSummary.append(n.getId()).append(" [").append(n.getRole())
                    .append(" | ").append(n.getHost()).append(":").append(n.getPort())
                    .append(" | ").append(n.isActive() ? "ONLINE" : "OFFLINE").append("]");
        }

        Header header = Header.of(3, "🌐 Conexión Principal al Clúster (cluster.ip.nodes / conecction.config)");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0 0 0.65rem 0;"));

        Div metaRow = Div.of(
                Div.of(Text.of("Origen cargado: "), FluxUi.coloredSpan(sourceLabel, "color: #fbbf24; font-family: monospace;")),
                Div.of(Text.of("PRIMARY activo (cluster-state.config): "),
                        FluxUi.coloredSpan(primaryText, "color: #10b981; font-family: monospace; font-weight: 700;").modifier(new Modifier().attribute("id", "rf32-live-primary"))),
                Div.of(Text.of("Versión estado: "),
                        FluxUi.coloredSpan("v" + version, "color: #a78bfa; font-family: monospace;").modifier(new Modifier().attribute("id", "rf32-live-version")),
                        Text.of("  |  Nodos: "),
                        FluxUi.coloredSpan(onlineCount + " / " + allNodes.size() + " ONLINE", "color: var(--text-main); font-weight: 600;").modifier(new Modifier().attribute("id", "rf32-live-counts")))
        );
        metaRow.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.3rem; font-size: 0.85rem; margin-bottom: 0.9rem;"));

        Div liveNodesBox = Div.of(
                FluxUi.coloredSpan(nodesSummary.toString(), "font-family: monospace; font-size: 0.82rem; color: var(--text-main);")
                        .modifier(new Modifier().attribute("id", "rf32-live-nodes"))
        );
        liveNodesBox.modifier(new Modifier().style("padding: 0.6rem 0.8rem; background: rgba(15, 23, 42, 0.55); border: 1px solid var(--border-subtle); border-radius: 6px; margin-bottom: 1rem;"));

        Form connForm = Form.of(
                FluxUi.fieldColumn("Nodos semilla (host:port separados por coma)",
                        FluxUi.textInput("cluster_ip_nodes", currentSeeds, "127.0.0.1:50001", true),
                        "flex: 2.2; min-width: 260px;"),
                FluxUi.fieldColumn("Usuario Clúster",
                        FluxUi.textInput("username", currentUser, "admin", false),
                        "flex: 1; min-width: 140px;"),
                Div.of(
                        FluxUi.submitButton("⚡ Probar / Aplicar en Vivo", "app-btn-secondary", "height: 38px; margin-right: 0.5rem;")
                                .modifier(new Modifier().attribute("name", "action").attribute("value", "test_cluster_connection")),
                        FluxUi.submitButton("💾 Guardar en conecction.config y Aplicar", "app-btn-primary", "height: 38px;")
                                .modifier(new Modifier().attribute("name", "action").attribute("value", "save_cluster_connection"))
                ).modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.5rem;"))
        ).action("/connections").method("POST");
        connForm.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Div card = Div.of(header, metaRow, liveNodesBox, connForm);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border: 1px solid rgba(0, 212, 255, 0.45); background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildAddProfileCard() {
        Header header = Header.of(3, "➕ Registrar Perfil de Conexión a Clúster");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        Form form = Form.of(
                FluxUi.hidden("action", "save"),
                FluxUi.fieldColumn("ID del Perfil", FluxUi.textInput("id", "", "conn_prod_cluster", true), "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Nombre Descriptivo", FluxUi.textInput("name", "", "Producción Clúster Central", true), "flex: 1.5; min-width: 180px;"),
                FluxUi.fieldColumn("URL JettraStore TCP / Raft", FluxUi.textInput("url", "", "tcp://127.0.0.1:8765,127.0.0.1:8766", true), "flex: 2; min-width: 200px;"),
                FluxUi.fieldColumn("Usuario", FluxUi.textInput("username", "admin", "", true), "flex: 1; min-width: 120px;"),
                FluxUi.fieldColumn("Contraseña", FluxUi.passwordInput("password", "", true).value("admin123"), "flex: 1; min-width: 120px;"),
                Div.of(FluxUi.submitButton("➕ Guardar Perfil", "app-btn-primary", "height: 38px;"))
        ).action("/connections").method("POST");
        form.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Div card = Div.of(header, form);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);"));
        return card;
    }
}

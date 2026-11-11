package io.jettra.store.explorer.pages.template;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.server.core.JettraServer;
import io.jettra.server.core.storage.StorageMetrics;
import io.jettra.store.explorer.model.ConnectionProfile;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Plantilla Web JettraFlux para JettraStore Explorer.
 * Basada en la arquitectura y componentes de WebExample:
 * - Árbol jerárquico 100% de componentes JettraFlux (Div, Row, Column, Header, Button, Link, Badge, etc.)
 * - Diseño web responsive con menú hamburguesa para móviles y colapsable en escritorio.
 * - Conmutador de temas Claro / Oscuro con persistencia en localStorage y variables CSS dinámicas.
 * - Barra superior (TopBar) con métricas en vivo, modo de almacenamiento, conmutador de multinodo y estado de conexión.
 * - Navegación lateral organizada por áreas (Clúster & Telemetría, Catálogo & Motores, Gestión & Seguridad).
 */
public class StoreExplorerFluxTemplateView extends FluxView {

    private final String title;
    private final String activeSection;
    private final String storageMode;
    private final String username;
    private final Widget contentWidget;
    private final String extraCenterHtml;

    public StoreExplorerFluxTemplateView(String title, String activeSection, JettraServer server, String username, Widget contentWidget) {
        this(title, activeSection, resolveStorageMode(server), username, contentWidget, null);
    }

    public StoreExplorerFluxTemplateView(String title, String activeSection, String storageMode, String username, Widget contentWidget, String extraCenterHtml) {
        this.title = title != null ? title : "JettraStore Explorer";
        this.activeSection = activeSection != null ? activeSection : "cluster";
        this.storageMode = (storageMode != null && !storageMode.isBlank()) ? storageMode : "OFF_HEAP";
        this.username = (username != null && !username.isBlank()) ? username : "admin";
        this.contentWidget = contentWidget;
        this.extraCenterHtml = extraCenterHtml;
    }

    public static String resolveStorageMode(JettraServer server) {
        if (server != null && server.storage() != null) {
            StorageMetrics sm = server.storage().getMetrics();
            if (sm != null && sm.mode() != null) {
                return sm.mode().name();
            }
            if (server.storage().getMode() != null) {
                return server.storage().getMode().name();
            }
        }
        String prop = System.getProperty("storage.mode");
        if (prop == null || prop.isBlank()) prop = System.getProperty("jettra.storage.mode");
        if (prop == null || prop.isBlank()) prop = System.getenv("STORAGE_MODE");
        return (prop != null && !prop.isBlank()) ? prop.trim() : "OFF_HEAP";
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        // Sincronizar en tiempo real el estado TCP de los nodos antes de construir la barra superior y el contenido
        plugin.refreshAllNodeStatuses(true);

        // Estilos CSS y scripts reactivos encapsulados en un widget RawHtml
        Widget stylesAndScripts = RawHtml.of(getTemplateStylesAndScripts());

        // Backdrop para apertura de menú en dispositivos móviles
        Div backdrop = Div.of();
        backdrop.id("sidebarBackdrop");
        backdrop.modifier(new Modifier()
                .cssClass("sidebar-backdrop")
                .attribute("onclick", "toggleSidebar()"));

        // Barra superior (TopBar)
        Widget topbar = buildTopBar(plugin);

        // Contenedor principal: Sidebar lateral + Área central
        Widget layoutWrapper = buildLayoutWrapper(plugin);

        // Pie de página (Footer)
        Widget footer = buildFooter();

        Div pageRoot = Div.of(stylesAndScripts, backdrop, topbar, layoutWrapper, footer);
        pageRoot.modifier(new Modifier()
                .cssClass("app-body flux-template-body")
                .style("margin: 0; display: flex; flex-direction: column; min-height: 100vh; width: 100%;"));
        return pageRoot;
    }

    private Widget buildTopBar(JettraStorePlugin plugin) {
        // Botón hamburguesa
        Button hamburgerBtn = Button.of(
                Div.of().modifier(new Modifier().cssClass("hamburger-bar")),
                Div.of().modifier(new Modifier().cssClass("hamburger-bar")),
                Div.of().modifier(new Modifier().cssClass("hamburger-bar"))
        );
        hamburgerBtn.modifier(new Modifier()
                .cssClass("hamburger-btn")
                .attribute("type", "button")
                .attribute("onclick", "toggleSidebar()")
                .attribute("aria-label", "Alternar menú lateral")
                .attribute("title", "Colapsar / Expandir menú lateral"));

        // Insignia de marca JS (JettraStore)
        Div brandBadge = Div.of(Text.of("JS"));
        brandBadge.modifier(new Modifier().cssClass("flux-brand-badge"));

        // Título del estudio JettraStore con insignias de estado
        Div brandTitle = Div.of(
                Text.of("JettraStore Explorer").modifier(new Modifier().style("font-weight: 800; font-size: 1.15rem; letter-spacing: -0.02em; color: #ffffff;")),
                Badge.of("Loom Java 25 & Slotted-Pages").modifier(new Modifier().cssClass("hide-mobile").style("font-size: 0.72rem; font-weight: 700; background: rgba(0, 212, 255, 0.22); border: 1px solid rgba(0, 212, 255, 0.45); color: #00d4ff; padding: 0.15rem 0.55rem; border-radius: 9999px;"))
        );
        brandTitle.modifier(new Modifier().style("display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap;"));

        Row leftGroup = Row.of(hamburgerBtn, brandBadge, brandTitle);
        leftGroup.modifier(new Modifier().style("display: flex; align-items: center; gap: 0.75rem;"));

        // Estado del nodo PRIMARY y réplicas en vivo
        ServerNodeInfo primaryNode = plugin.getServerNodes().stream()
                .filter(n -> "PRIMARY".equalsIgnoreCase(n.getRole()) || "LEADER".equalsIgnoreCase(n.getRole()) || "node-01".equalsIgnoreCase(n.getId()))
                .findFirst()
                .orElse(null);
        boolean primaryOnline = primaryNode != null && primaryNode.isOnline();
        long offlineSecondaries = plugin.getServerNodes().stream()
                .filter(n -> n != primaryNode && !n.isOnline())
                .count();

        String primaryTooltip = primaryOnline
                ? "Nodo maestro PRIMARY en línea y receptivo (" + (primaryNode != null ? primaryNode.getHost() + ":" + primaryNode.getPort() : "") + ")"
                : "¡ALERTA! Nodo maestro PRIMARY desconectado (OFFLINE)" + (primaryNode != null && primaryNode.getLastError() != null ? " - " + primaryNode.getLastError() : "");

        Link primaryStatusBadge = Link.of(
                "/cluster",
                primaryOnline ? "● PRIMARY: ONLINE" : "○ PRIMARY: OFFLINE"
        );
        primaryStatusBadge.id("topbar-primary-status");
        primaryStatusBadge.modifier(new Modifier()
                .attribute("data-online", String.valueOf(primaryOnline))
                .attribute("title", primaryTooltip)
                .style(primaryOnline
                        ? "text-decoration:none; font-size:0.75rem; font-weight:700; padding:0.32rem 0.65rem; border-radius:9999px; display:inline-flex; align-items:center; gap:0.3rem; background:rgba(16,185,129,0.18); border:1px solid #10b981; color:#34d399;"
                        : "text-decoration:none; font-size:0.75rem; font-weight:800; padding:0.32rem 0.65rem; border-radius:9999px; display:inline-flex; align-items:center; gap:0.3rem; background:rgba(239,68,68,0.25); border:1px solid #ef4444; color:#ef4444;"));

        List<Widget> rightItems = new ArrayList<>();
        rightItems.add(primaryStatusBadge);

        if (offlineSecondaries > 0) {
            Link secBadge = Link.of("/cluster", "⚠️ " + offlineSecondaries + " RÉPLICAS OFFLINE");
            secBadge.modifier(new Modifier()
                    .cssClass("hide-mobile")
                    .attribute("title", offlineSecondaries + " nodos réplica se encuentran en estado OFFLINE")
                    .style("text-decoration:none; font-size:0.72rem; font-weight:700; padding:0.28rem 0.55rem; border-radius:9999px; display:inline-flex; align-items:center; background:rgba(245,158,11,0.2); border:1px solid #f59e0b; color:#fbbf24;"));
            rightItems.add(secBadge);
        }

        // Conmutador de Multinodo (Raft Quorum vs Mononodo Standalone)
        String multiClass = plugin.isMultinodeActive() ? "badge-multinode-on" : "badge-multinode-off";
        String multiIcon = plugin.isMultinodeActive() ? "🌐" : "💻";
        String multiLabel = plugin.isMultinodeActive() ? "MULTINODO: ON (Raft)" : "STANDALONE: OFF";
        Link multinodeToggle = Link.of(
                "/cluster?action=toggle_multinode",
                multiIcon + " " + multiLabel
        );
        multinodeToggle.modifier(new Modifier()
                .cssClass(multiClass)
                .attribute("title", "Clic para conmutar modo de topología clúster")
                .style("text-decoration:none; font-size:0.75rem; font-weight:700; padding:0.32rem 0.65rem; border-radius:9999px; display:inline-flex; align-items:center; gap:0.3rem;"));

        // Indicador de Modo de Almacenamiento
        Div storageBadge = Div.of(RawHtml.of("Modo: <strong style=\"color:#38bdf8;\">" + escapeHtml(storageMode) + "</strong>"));
        storageBadge.modifier(new Modifier()
                .cssClass("hide-mobile")
                .style("background: rgba(15, 23, 42, 0.55); border: 1px solid rgba(148, 163, 184, 0.25); color: #e2e8f0; font-size: 0.8rem; padding: 0.3rem 0.65rem; border-radius: 6px;"));

        // Conexión Activa
        ConnectionProfile conn = plugin.getActiveConnection();
        String connName = conn != null ? conn.getName() : "Local Clúster";
        Div connBadge = Div.of(Text.of("🔌 " + connName));
        connBadge.modifier(new Modifier()
                .cssClass("hide-mobile")
                .style("background: rgba(16, 185, 129, 0.15); border: 1px solid rgba(16, 185, 129, 0.35); color: #34d399; font-size: 0.8rem; padding: 0.3rem 0.65rem; border-radius: 6px; font-weight:600;"));

        // Conmutador de tema Claro / Oscuro
        Button themeBtn = Button.of(
                RawHtml.of("<span id=\"themeIcon\">🌙</span> <span id=\"themeLabel\" class=\"hide-mobile\">Oscuro</span>")
        );
        themeBtn.id("btnThemeToggle");
        themeBtn.modifier(new Modifier()
                .cssClass("theme-toggle-btn")
                .attribute("type", "button")
                .attribute("onclick", "toggleTheme()")
                .attribute("title", "Alternar Tema Claro / Oscuro"));

        // Usuario autenticado
        Div userInfo = Div.of(Text.of("👤 " + escapeHtml(username)));
        userInfo.modifier(new Modifier()
                .cssClass("hide-mobile")
                .style("font-size: 0.85rem; color: #f1f5f9; font-weight:600;"));

        // Enlace de cierre de sesión
        Link logoutLink = Link.of("/login?logout=true", "Salir");
        logoutLink.modifier(new Modifier()
                .cssClass("app-btn-danger")
                .style("text-decoration: none; font-size: 0.8rem; padding: 0.35rem 0.75rem; color: #ffffff; border-radius: 6px; font-weight: 600; display: inline-flex; align-items: center;"));

        rightItems.add(multinodeToggle);
        rightItems.add(storageBadge);
        rightItems.add(connBadge);
        rightItems.add(themeBtn);
        rightItems.add(userInfo);
        rightItems.add(logoutLink);

        Row rightGroup = Row.of(rightItems.toArray(new Widget[0]));
        rightGroup.modifier(new Modifier().cssClass("top-right-group"));

        Div topbar = Div.of(leftGroup, rightGroup);
        topbar.modifier(new Modifier().cssClass("ui-layout-top flux-layout-top"));
        return topbar;
    }

    private Widget buildLayoutWrapper(JettraStorePlugin plugin) {
        Widget sidebar = buildSidebar(plugin);
        Widget center = buildCenter();

        Div layoutWrapper = Div.of(sidebar, center);
        layoutWrapper.modifier(new Modifier().cssClass("ui-layout-wrapper flux-layout-wrapper"));
        return layoutWrapper;
    }

    private Widget buildSidebar(JettraStorePlugin plugin) {
        // Banner introductorio
        Div banner = Div.of(
                Div.of(Text.of("⚡ JettraStore Engine")).modifier(new Modifier().style("font-weight: 800; font-size: 0.88rem; color: #00d4ff; display: flex; align-items: center; gap: 0.4rem;")),
                Div.of(Text.of("Multi-Model DB Management Console")).modifier(new Modifier().style("font-size: 0.75rem; color: var(--text-muted); margin-top: 0.2rem;"))
        );
        banner.modifier(new Modifier().cssClass("flux-sidebar-banner"));

        // Grupo 1: Clúster y Telemetría
        Widget headingCluster = Div.of(Text.of("🌐 Clúster y Telemetría")).modifier(new Modifier().cssClass("flux-menu-heading"));
        Div navCluster = Div.of(
                createNavLink("/cluster", "🌐 Nodos del Clúster", isActive("cluster", "nodes")),
                createNavLink("/status", "🩺 Estatus y Telemetría", isActive("status", "telemetry")),
                createNavLink("/police", "🚨 Centinelas Police", isActive("police", "sentinels"))
        );
        navCluster.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.28rem; margin-bottom: 1.4rem;"));

        // Grupo 2: Catálogo y Motores de Datos
        Widget headingCatalog = Div.of(Text.of("🗄️ Catálogo y Motores")).modifier(new Modifier().cssClass("flux-menu-heading"));
        Div navCatalog = Div.of(
                createNavLink("/databases", "🗄️ Bases de Datos y Motores", isActive("databases", "engines")),
                createNavLink("/explorer", "📦 Explorador de Registros CRUD", isActive("explorer", "records")),
                createNavLink("/query", "💻 Consola SQL / JQL", isActive("query", "console"))
        );
        navCatalog.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.28rem; margin-bottom: 1.4rem;"));

        // Grupo 3: Gestión y Seguridad
        Widget headingAdmin = Div.of(Text.of("⚙️ Gestión y Seguridad")).modifier(new Modifier().cssClass("flux-menu-heading"));
        Div navAdmin = Div.of(
                createNavLink("/connections", "🔌 Conexiones de Base de Datos", isActive("connections")),
                createNavLink("/users", "👥 Seguridad y Roles de Usuario", isActive("users", "security")),
                createNavLink("/backups", "💾 Copias de Respaldo y Snapshots", isActive("backups")),
                createExternalNavLink("/swagger-ui", "📄 OpenAPI / Swagger UI")
        );
        navAdmin.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.28rem; margin-bottom: 1.4rem;"));

        // Grupo 4: Endpoints del Servidor
        Widget headingApi = Div.of(Text.of("🔗 API Rest & Métricas")).modifier(new Modifier().cssClass("flux-menu-heading"));
        Div navApi = Div.of(
                createExternalNavLink("/metrics", "📊 /metrics (JSON)"),
                createExternalNavLink("/health", "🩺 /health (JSON)"),
                createExternalNavLink("/openapi.json", "📑 /openapi.json")
        );
        navApi.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.25rem;"));

        Div sidebar = Div.of(banner, headingCluster, navCluster, headingCatalog, navCatalog, headingAdmin, navAdmin, headingApi, navApi);
        sidebar.id("appSidebar");
        sidebar.modifier(new Modifier().cssClass("ui-layout-left flux-layout-left"));
        return sidebar;
    }

    private Widget buildCenter() {
        // Cabecera de la barra de herramientas central
        Badge pillBadge = Badge.of("⚡ JETTRASTORE EXPLORER");
        pillBadge.modifier(new Modifier().cssClass("flux-pill-badge"));

        Header titleHeader = Header.of(1, title);
        titleHeader.modifier(new Modifier().style("font-size: 1.45rem; font-weight: 800; color: var(--text-main); margin: 0;"));

        Div titleRow = Div.of(pillBadge, titleHeader);
        titleRow.modifier(new Modifier().style("display: flex; align-items: center; gap: 0.55rem; flex-wrap: wrap;"));

        Paragraph subtitle = Paragraph.of("Gestor Web para la Base de Datos Multimodelo JettraStore en JettraServer");
        subtitle.modifier(new Modifier().style("color: var(--text-muted); margin: 0.25rem 0 0 0; font-size: 0.85rem;"));

        Div toolbarLeft = Div.of(titleRow, subtitle);

        // Barra de pestañas superiores de acceso rápido
        Div tabs = Div.of(
                createTabLink("/cluster", "🌐 Nodos", isActive("cluster", "nodes")),
                createTabLink("/status", "🩺 Estatus", isActive("status", "telemetry")),
                createTabLink("/databases", "🗄️ Bases de Datos", isActive("databases", "engines")),
                createTabLink("/explorer", "📦 Registros CRUD", isActive("explorer", "records")),
                createTabLink("/query", "💻 Consola SQL", isActive("query")),
                createTabLink("/connections", "🔌 Conexiones", isActive("connections")),
                createTabLink("/users", "👥 Usuarios", isActive("users")),
                createTabLink("/backups", "💾 Backups", isActive("backups"))
        );
        tabs.modifier(new Modifier().cssClass("flux-tabs"));

        Div toolbar = Div.of(toolbarLeft, tabs);
        toolbar.modifier(new Modifier().cssClass("flux-center-toolbar"));

        // Superficie de contenido que encapsula el widget hijo y el HTML complementario
        Widget innerContent;
        if (contentWidget != null && extraCenterHtml != null && !extraCenterHtml.isBlank()) {
            innerContent = Div.of(contentWidget, RawHtml.of(extraCenterHtml));
        } else if (contentWidget != null) {
            innerContent = contentWidget;
        } else if (extraCenterHtml != null && !extraCenterHtml.isBlank()) {
            innerContent = RawHtml.of(extraCenterHtml);
        } else {
            innerContent = Div.of();
        }

        Div contentSurface = Div.of(innerContent);
        contentSurface.id("fluxContentSurface");
        contentSurface.modifier(new Modifier().cssClass("ui-card flux-content-surface"));

        Div center = Div.of(toolbar, contentSurface);
        center.modifier(new Modifier().cssClass("ui-layout-center flux-layout-center"));
        return center;
    }

    private Widget buildFooter() {
        Div footerLeft = Div.of(Text.of("JettraStore Explorer • Powered by JettraFlux & JettraServer (Java 25 Virtual Threads) • Zero Manual HTML"));
        Div footerRight = Div.of(RawHtml.of("Estado: <span style=\"color: var(--success); font-weight: bold;\">● OPERATIVO</span> • Modo Persistencia: <strong>[" + escapeHtml(storageMode) + "]</strong>"));

        Div footer = Div.of(footerLeft, footerRight);
        footer.modifier(new Modifier().cssClass("ui-layout-footer flux-layout-footer"));
        return footer;
    }

    private Link createNavLink(String href, String label, boolean active) {
        Link link = Link.of(href, label);
        String classes = active ? "nav-link active" : "nav-link";
        link.modifier(new Modifier().cssClass(classes));
        return link;
    }

    private Link createExternalNavLink(String href, String label) {
        Link link = Link.of(href, label);
        link.target("_blank");
        link.modifier(new Modifier().cssClass("nav-link"));
        return link;
    }

    private Link createTabLink(String href, String label, boolean active) {
        Link link = Link.of(href, label);
        String classes = active ? "tab-pill active" : "tab-pill";
        link.modifier(new Modifier().cssClass(classes));
        return link;
    }

    private boolean isActive(String... keys) {
        for (String k : keys) {
            if (k != null && k.equalsIgnoreCase(activeSection)) {
                return true;
            }
        }
        return false;
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String getTemplateStylesAndScripts() {
        return """
          <style>
            :root, [data-theme="dark"] {
              --bg-body: #050b14;
              --bg-card: #0c1626;
              --bg-subtle: #132238;
              --bg-top: linear-gradient(90deg, #091a33 0%, #0d284f 50%, #071324 100%);
              --bg-sidebar: #091322;
              --bg-footer: #03080f;
              --text-main: #f8fafc;
              --text-muted: #94a3b8;
              --text-light: #38bdf8;
              --border-subtle: #1e3a5f;
              --primary: #00d4ff;
              --primary-hover: #00b4d8;
              --primary-light: rgba(0, 212, 255, 0.15);
              --success: #10b981;
              --success-light: rgba(16, 185, 129, 0.15);
              --warning: #f59e0b;
              --warning-light: rgba(245, 158, 11, 0.15);
              --danger: #ef4444;
              --danger-light: rgba(239, 68, 68, 0.15);
              --shadow-sm: 0 4px 16px rgba(0, 0, 0, 0.4);
              --sidebar-width: 275px;
            }

            [data-theme="white"] {
              --bg-body: #f0f6fc;
              --bg-card: #ffffff;
              --bg-subtle: #f1f5f9;
              --bg-top: linear-gradient(90deg, #0284c7 0%, #0369a1 60%, #0f172a 100%);
              --bg-sidebar: #ffffff;
              --bg-footer: #e2e8f0;
              --text-main: #0f172a;
              --text-muted: #475569;
              --text-light: #0284c7;
              --border-subtle: #cbd5e1;
              --primary: #0284c7;
              --primary-hover: #0369a1;
              --primary-light: #e0f2fe;
              --success: #059669;
              --success-light: #d1fae5;
              --warning: #d97706;
              --warning-light: #fef3c7;
              --danger: #dc2626;
              --danger-light: #fee2e2;
              --shadow-sm: 0 2px 8px rgba(2, 132, 199, 0.1);
              --sidebar-width: 275px;
            }

            * { box-sizing: border-box; }

            body.flux-template-body, .flux-template-body {
              font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              background-color: var(--bg-body) !important;
              color: var(--text-main) !important;
              transition: background-color 0.2s ease, color 0.2s ease;
            }

            #jettra-flux-root {
              min-height: 100vh;
              display: flex;
              flex-direction: column;
            }

            .flux-layout-top {
              height: 62px;
              background: var(--bg-top);
              color: #ffffff;
              display: flex;
              align-items: center;
              justify-content: space-between;
              padding: 0 1.25rem;
              position: sticky;
              top: 0;
              z-index: 100;
              box-shadow: 0 2px 10px rgba(0,0,0,0.3);
              border-bottom: 1px solid rgba(0, 212, 255, 0.25);
            }

            .flux-brand-badge {
              background: linear-gradient(135deg, #00d4ff, #2563eb);
              color: #ffffff;
              width: 38px;
              height: 38px;
              border-radius: 10px;
              display: flex;
              align-items: center;
              justify-content: center;
              font-weight: 900;
              font-size: 1rem;
              box-shadow: 0 2px 8px rgba(0, 212, 255, 0.45);
            }

            .top-right-group {
              display: flex;
              align-items: center;
              gap: 0.75rem;
            }

            .badge-multinode-on {
              background: rgba(16, 185, 129, 0.2);
              border: 1px solid #10b981;
              color: #34d399 !important;
              transition: all 0.2s ease;
            }
            .badge-multinode-on:hover {
              background: rgba(16, 185, 129, 0.35);
            }
            .badge-multinode-off {
              background: rgba(245, 158, 11, 0.2);
              border: 1px solid #f59e0b;
              color: #fbbf24 !important;
              transition: all 0.2s ease;
            }
            .badge-multinode-off:hover {
              background: rgba(245, 158, 11, 0.35);
            }

            .hamburger-btn {
              display: inline-flex !important;
              flex-direction: column !important;
              justify-content: space-around !important;
              width: 36px !important;
              height: 36px !important;
              background: rgba(255, 255, 255, 0.12) !important;
              border: 1px solid rgba(255, 255, 255, 0.25) !important;
              border-radius: 8px !important;
              cursor: pointer !important;
              padding: 8px !important;
              transition: background 0.2s !important;
            }
            .hamburger-btn:hover { background: rgba(255, 255, 255, 0.22) !important; }
            .hamburger-bar {
              width: 100%;
              height: 2px;
              background-color: #ffffff;
              border-radius: 2px;
              display: block;
            }

            .theme-toggle-btn {
              background: rgba(255, 255, 255, 0.14) !important;
              color: #ffffff !important;
              border: 1px solid rgba(255, 255, 255, 0.28) !important;
              padding: 0.35rem 0.75rem !important;
              border-radius: 9999px !important;
              cursor: pointer !important;
              font-size: 0.8rem !important;
              font-weight: 600 !important;
              display: inline-flex !important;
              align-items: center !important;
              gap: 0.35rem !important;
            }

            .flux-layout-wrapper {
              display: flex;
              flex: 1;
              position: relative;
            }

            .flux-layout-left {
              width: var(--sidebar-width);
              background: var(--bg-sidebar);
              border-right: 1px solid var(--border-subtle);
              padding: 1.25rem 1rem;
              flex-shrink: 0;
              transition: margin-left 0.28s ease, transform 0.28s ease, opacity 0.25s ease;
            }

            .flux-sidebar-banner {
              background: var(--primary-light);
              border: 1px solid rgba(0, 212, 255, 0.35);
              border-radius: 10px;
              padding: 0.75rem 0.85rem;
              margin-bottom: 1.25rem;
            }

            .flux-menu-heading {
              font-size: 0.72rem;
              text-transform: uppercase;
              font-weight: 800;
              color: var(--text-light);
              margin-bottom: 0.55rem;
              letter-spacing: 0.06em;
            }

            .nav-link {
              display: flex;
              align-items: center;
              gap: 0.5rem;
              padding: 0.55rem 0.8rem;
              border-radius: 8px;
              color: var(--text-muted);
              text-decoration: none;
              font-size: 0.88rem;
              font-weight: 500;
              transition: all 0.15s ease;
            }
            .nav-link:hover {
              background: var(--bg-subtle);
              color: var(--text-main);
            }
            .nav-link.active {
              background: var(--primary-light);
              color: var(--primary);
              font-weight: 700;
              border-left: 3px solid var(--primary);
            }

            .flux-layout-center {
              flex: 1;
              padding: 1.75rem;
              overflow-x: hidden;
              max-width: 1440px;
              margin: 0 auto;
              width: 100%;
            }

            .flux-center-toolbar {
              display: flex;
              justify-content: space-between;
              align-items: center;
              flex-wrap: wrap;
              gap: 1rem;
              margin-bottom: 1.5rem;
              padding-bottom: 1rem;
              border-bottom: 1px solid var(--border-subtle);
            }

            .flux-pill-badge {
              background: linear-gradient(135deg, #00d4ff, #2563eb);
              color: #ffffff;
              padding: 0.22rem 0.65rem;
              border-radius: 6px;
              font-size: 0.72rem;
              font-weight: 800;
              letter-spacing: 0.04em;
            }

            .flux-content-surface {
              background: var(--bg-card);
              padding: 1.75rem;
              border-radius: 14px;
              border: 1px solid var(--border-subtle);
              box-shadow: var(--shadow-sm);
            }

            .flux-tabs {
              display: flex;
              gap: 0.45rem;
              flex-wrap: wrap;
            }
            .tab-pill {
              padding: 0.4rem 0.8rem;
              border-radius: 9999px;
              font-size: 0.8rem;
              font-weight: 600;
              text-decoration: none;
              color: var(--text-muted);
              background: var(--bg-card);
              border: 1px solid var(--border-subtle);
              transition: all 0.15s ease;
            }
            .tab-pill:hover {
              color: var(--text-main);
              border-color: var(--primary);
            }
            .tab-pill.active {
              background: var(--primary);
              color: #050b14;
              font-weight: 700;
              border-color: var(--primary);
            }

            .flux-layout-footer {
              background: var(--bg-footer);
              border-top: 1px solid var(--border-subtle);
              color: var(--text-muted);
              padding: 0.75rem 1.5rem;
              font-size: 0.8rem;
              display: flex;
              justify-content: space-between;
              align-items: center;
              flex-wrap: wrap;
              gap: 0.5rem;
            }

            .kpi-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
              gap: 1.15rem;
              margin-bottom: 1.5rem;
            }
            .ui-card {
              background: var(--bg-card);
              border: 1px solid var(--border-subtle);
              border-radius: 12px;
              padding: 1.25rem;
            }
            .kpi-title { font-size: 0.78rem; text-transform: uppercase; color: var(--text-muted); font-weight: 700; }
            .kpi-value { font-size: 1.55rem; font-weight: 800; margin: 0.35rem 0; color: var(--text-main); }
            .kpi-sub { font-size: 0.8rem; color: var(--text-muted); }

            .app-input {
              width: 100%;
              padding: 0.55rem 0.75rem;
              border-radius: 6px;
              border: 1px solid var(--border-subtle);
              background: var(--bg-body);
              color: var(--text-main);
              font-size: 0.875rem;
              outline: none;
            }
            .app-input:focus {
              border-color: var(--primary);
            }
            .app-btn-primary {
              background: var(--primary);
              color: #050b14;
              border: none;
              padding: 0.55rem 1rem;
              border-radius: 6px;
              font-weight: 700;
              cursor: pointer;
              font-size: 0.875rem;
              display: inline-flex;
              align-items: center;
              gap: 0.4rem;
              text-decoration: none;
              transition: opacity 0.2s ease;
            }
            .app-btn-primary:hover { opacity: 0.9; }
            .app-btn-secondary {
              background: var(--bg-subtle);
              color: var(--text-main);
              border: 1px solid var(--border-subtle);
              padding: 0.5rem 0.95rem;
              border-radius: 6px;
              font-weight: 600;
              cursor: pointer;
              font-size: 0.85rem;
              text-decoration: none;
              display: inline-flex;
              align-items: center;
              gap: 0.4rem;
            }
            .app-btn-danger {
              background: var(--danger);
              color: #ffffff;
              border: none;
              padding: 0.45rem 0.85rem;
              border-radius: 6px;
              font-weight: 600;
              cursor: pointer;
              font-size: 0.82rem;
              text-decoration: none;
              display: inline-flex;
              align-items: center;
              gap: 0.3rem;
            }
            .app-btn-warning {
              background: var(--warning);
              color: #050b14;
              border: none;
              padding: 0.45rem 0.85rem;
              border-radius: 6px;
              font-weight: 700;
              cursor: pointer;
              font-size: 0.82rem;
              text-decoration: none;
              display: inline-flex;
              align-items: center;
              gap: 0.3rem;
            }

            .app-table {
              width: 100%;
              border-collapse: collapse;
              text-align: left;
              font-size: 0.875rem;
            }
            .app-table th {
              background: var(--bg-subtle);
              color: var(--text-muted);
              font-size: 0.75rem;
              text-transform: uppercase;
              padding: 0.75rem 1rem;
              border-bottom: 1px solid var(--border-subtle);
            }
            .app-table td {
              padding: 0.8rem 1rem;
              border-bottom: 1px solid var(--border-subtle);
            }
            .app-table tr:hover td {
              background: rgba(0, 212, 255, 0.04);
            }

            @media (min-width: 901px) {
              body.sidebar-collapsed .flux-layout-left {
                margin-left: calc(-1 * var(--sidebar-width));
                opacity: 0;
                pointer-events: none;
              }
            }
            @media (max-width: 900px) {
              .hide-mobile { display: none !important; }
              .flux-layout-left {
                position: fixed;
                top: 62px;
                left: 0;
                bottom: 0;
                z-index: 1001;
                transform: translateX(-100%);
                box-shadow: 4px 0 15px rgba(0,0,0,0.5);
              }
              body.mobile-sidebar-open .flux-layout-left {
                transform: translateX(0);
              }
              .sidebar-backdrop {
                display: none;
                position: fixed;
                inset: 62px 0 0 0;
                background: rgba(0,0,0,0.55);
                z-index: 1000;
              }
              body.mobile-sidebar-open .sidebar-backdrop {
                display: block;
              }
            }
          </style>
          <script>
            (function() {
              const savedTheme = localStorage.getItem('jettra-theme') || 'dark';
              document.documentElement.setAttribute('data-theme', savedTheme);
              if (document.body) {
                document.body.classList.add('flux-template-body', 'app-body');
              }
            })();

            function updateThemeButtonUI(theme) {
              const icon = document.getElementById('themeIcon');
              const label = document.getElementById('themeLabel');
              if (icon) icon.textContent = theme === 'white' ? '☀️' : '🌙';
              if (label) label.textContent = theme === 'white' ? 'Claro' : 'Oscuro';
            }

            function toggleTheme() {
              const current = document.documentElement.getAttribute('data-theme') || 'dark';
              const next = current === 'dark' ? 'white' : 'dark';
              document.documentElement.setAttribute('data-theme', next);
              localStorage.setItem('jettra-theme', next);
              document.cookie = "jettra_theme=" + next + "; path=/; max-age=31536000";
              updateThemeButtonUI(next);
            }

            function toggleSidebar() {
              const isMobile = window.innerWidth <= 900;
              if (isMobile) {
                document.body.classList.toggle('mobile-sidebar-open');
              } else {
                document.body.classList.toggle('sidebar-collapsed');
              }
            }

            window.addEventListener('resize', function() {
              if (window.innerWidth > 900) {
                document.body.classList.remove('mobile-sidebar-open');
              }
            });

            document.addEventListener('DOMContentLoaded', function() {
              const saved = localStorage.getItem('jettra-theme') || 'dark';
              updateThemeButtonUI(saved);

              let lastClusterSig = null;
              async function pollClusterHealth() {
                try {
                  const res = await fetch('/api/cluster/status', { cache: 'no-store' });
                  if (res.status === 401) {
                    window.location.href = '/login?error=session_expired';
                    return;
                  }
                  if (!res.ok) return;
                  const data = await res.json();
                  const badge = document.getElementById('topbar-primary-status');
                  if (badge) {
                    const prev = badge.getAttribute('data-online');
                    const curr = String(Boolean(data.primaryOnline));
                    if (prev !== curr) {
                      badge.setAttribute('data-online', curr);
                      if (data.primaryOnline) {
                        badge.textContent = '● PRIMARY: ONLINE';
                        badge.style.background = 'rgba(16,185,129,0.18)';
                        badge.style.borderColor = '#10b981';
                        badge.style.color = '#34d399';
                      } else {
                        badge.textContent = '○ PRIMARY: OFFLINE';
                        badge.style.background = 'rgba(239,68,68,0.25)';
                        badge.style.borderColor = '#ef4444';
                        badge.style.color = '#ef4444';
                      }
                    }
                  }
                  const sig = (data.nodes || []).map(n => n.id + ':' + n.online).join('|');
                  if (lastClusterSig === null) {
                    lastClusterSig = sig;
                  } else if (lastClusterSig !== sig) {
                    lastClusterSig = sig;
                    const path = window.location.pathname || '';
                    const active = document.activeElement;
                    const isTyping = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT');
                    if ((path === '/cluster' || path === '/dashboard') && !isTyping) {
                      window.location.reload();
                    }
                  }
                } catch (e) {
                  const badge = document.getElementById('topbar-primary-status');
                  if (badge) {
                    badge.textContent = '○ PRIMARY: OFFLINE';
                    badge.style.background = 'rgba(239,68,68,0.25)';
                    badge.style.borderColor = '#ef4444';
                    badge.style.color = '#ef4444';
                  }
                }
              }
              pollClusterHealth();
              setInterval(pollClusterHealth, 5000);
            });
          </script>
        """;
    }
}


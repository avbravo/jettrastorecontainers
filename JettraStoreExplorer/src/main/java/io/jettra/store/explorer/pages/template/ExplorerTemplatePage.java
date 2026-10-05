package io.jettra.store.explorer.pages.template;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.pages.FluxBaseHandler;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.PoliceTheme;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.model.ConnectionProfile;

import java.util.Map;

public abstract class ExplorerTemplatePage extends FluxBaseHandler {

    protected abstract Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme);

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String username = getLoggedUser(exchange);
        if (username == null || username.isBlank()) {
            try {
                redirect(exchange, "/login");
            } catch (Exception ignored) {}
            return Column.of();
        }

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();

        // Manejo de cambio de modo multinodo rápido vía query param ?toggle_multinode=true
        if ("true".equals(params.get("toggle_multinode"))) {
            plugin.setMultinodeActive(!plugin.isMultinodeActive());
            try {
                String path = exchange.getRequestURI().getPath();
                redirect(exchange, path);
            } catch (Exception ignored) {}
            return Column.of();
        }

        // Inyección de estilos y canvas radar interactivo de PoliceTheme
        Widget policeCss = Paragraph.of(PoliceTheme.Template.CustomCSS + "\n" + PoliceTheme.Template.CustomJS);

        // Sidebar Navigation
        WidgetLet clusterMenu = WidgetLet.of("Monitor de Clúster").icon(Icon.CHART_LINE).url(JettraServer.resolvePath("/cluster"));
        WidgetLet explorerMenu = WidgetLet.of("Explorador de Datos").icon("fas fa-database").url(JettraServer.resolvePath("/explorer"));
        WidgetLet queryMenu = WidgetLet.of("Consola SQL / JQL").icon("fas fa-terminal").url(JettraServer.resolvePath("/query"));
        WidgetLet connMenu = WidgetLet.of("Gestión Conexiones").icon("fas fa-plug").url(JettraServer.resolvePath("/connections"));
        WidgetLet secMenu = WidgetLet.of("Seguridad y Usuarios").icon("fas fa-shield-alt").url(JettraServer.resolvePath("/users"));
        WidgetLet policeMenu = WidgetLet.of("Centinelas Police").icon("fas fa-shield-virus").url(JettraServer.resolvePath("/police"));
        WidgetLet backupMenu = WidgetLet.of("Copias de Seguridad").icon(Icon.HISTORY).url(JettraServer.resolvePath("/backups"));

        Widget menu = Left.of(
            SidebarLogo.of("fas fa-shield-alt", "JettraStore 3D"),
            SidebarCategory.of("TELEMETRÍA Y COMANDO"),
            clusterMenu,
            explorerMenu,
            queryMenu,
            SidebarCategory.of("ADMINISTRACIÓN"),
            connMenu,
            secMenu,
            policeMenu,
            backupMenu
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("professional-left"));

        // Indicador de Multinodo (conmutador rápido interactivo)
        String multinodeClass = plugin.isMultinodeActive() ? "police-multinode-on" : "police-multinode-off";
        String multinodeIcon = plugin.isMultinodeActive() ? "fas fa-network-wired" : "fas fa-server";
        String multinodeText = plugin.isMultinodeActive() ? "MULTINODO: ON (Raft Quorum)" : "STANDALONE: OFF (Mononodo)";
        
        Widget multinodeBadge = Link.of(
            "?toggle_multinode=true",
            Span.of("<i class='" + multinodeIcon + "'></i> " + multinodeText)
        ).modifier(new io.jettra.flux.core.Modifier().cssClass(multinodeClass).style("text-decoration:none; cursor:pointer; font-weight:700;"));

        // Indicador de Conexión Activa
        ConnectionProfile activeConn = plugin.getActiveConnection();
        String connText = activeConn != null ? activeConn.getName() + " (" + activeConn.getUrl() + ")" : "Sin Conexión";
        Widget connBadge = Span.of("<span class='police-dot-active'></span> <i class='fas fa-plug'></i> " + connText)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-badge-blue"));

        // Evaluaciones de JettraPolice
        Widget evalBadge = Span.of("<i class='fas fa-shield-alt'></i> " + String.format("%,d", plugin.getTotalEvaluations()) + " Evals")
            .modifier(new io.jettra.flux.core.Modifier().cssClass("police-badge-gold"));

        // Perfil de Usuario con Menú de Cerrar Sesión
        Widget userAvatar = io.jettra.flux.widgets.Avatar.icon("fas fa-user-shield").shape("circle")
            .modifier(new io.jettra.flux.core.Modifier().style("background-color:#00d4ff; color:#05080c; font-weight:bold; margin-right:8px;"));
        Widget userTrigger = Row.of(
            userAvatar,
            Span.of(username).modifier(new io.jettra.flux.core.Modifier().style("font-weight:bold; color:#00d4ff;")),
            Icon.of("fas fa-caret-down").modifier(new io.jettra.flux.core.Modifier().style("margin-left:5px; color:#00d4ff;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("align-items:center; cursor:pointer;"));

        Widget profileMenu = ((io.jettra.flux.widgets.OverlayMenu) io.jettra.flux.widgets.OverlayMenu.of(
            WidgetLet.of("Cerrar Sesión").icon(Icon.SIGN_OUT_ALT).url(JettraServer.resolvePath("/login?logout=true"))
        ).trigger(userTrigger)).alignRight();

        // Barra Superior de Comando (TopBar)
        Widget topBar = Top.of(
            Row.of(
                ActionIcon.of(Icon.BARS + " top-bars-icon", "toggleSidebar()"),
                Header.of(4, "🛡️ JETTRASTORE POLICE 3D - CYBER COMMAND")
                    .modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("font-size:1.15rem; margin:0;"))
            ).modifier(new io.jettra.flux.core.Modifier().cssClass("top-left-section").style("align-items:center; gap:12px;")),
            Row.of(
                multinodeBadge,
                connBadge,
                evalBadge,
                ThemeChanged.of().current(currentTheme),
                profileMenu
            ).modifier(new io.jettra.flux.core.Modifier().cssClass("top-right-section").style("gap:12px; align-items:center;"))
        );

        // Contenido Central
        Widget centerContent = Column.of(
            policeCss,
            buildCenter(exchange, params, currentTheme)
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("professional-center espresso-center"));

        // Pie de Página
        Widget footerContent = Footer.of(
            Paragraph.of("🛡️ © 2026 JettraStore Explorer | JettraFlux & JettraStorePlugin | Motor Multimodelo Java 25 Virtual Threads & Panama FFM")
                .modifier(new io.jettra.flux.core.Modifier().style("color:#64748b; font-size:0.85rem; text-align:center;"))
        );

        Widget body = Dashboard.of(
            topBar,
            menu,
            centerContent,
            footerContent
        );

        return Scaffold.of().body(body);
    }
}

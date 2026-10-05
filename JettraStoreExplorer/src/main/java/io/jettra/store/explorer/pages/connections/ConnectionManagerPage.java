package io.jettra.store.explorer.pages.connections;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.ConnectionProfile;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.Map;

@Page(path = "/connections")
public class ConnectionManagerPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Gestor de Conexiones a JettraStore";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = params.get("_action");

        if ("connect".equals(action)) {
            String id = params.get("id");
            if (id != null) plugin.connectProfile(id);
            redirect(exchange, "/connections?msg=connected");
            return true;
        } else if ("set_default".equals(action)) {
            String id = params.get("id");
            if (id != null) plugin.setDefaultProfile(id);
            redirect(exchange, "/connections?msg=default_set");
            return true;
        } else if ("delete".equals(action)) {
            String id = params.get("id");
            if (id != null) plugin.deleteProfile(id);
            redirect(exchange, "/connections?msg=deleted");
            return true;
        } else if ("save".equals(action)) {
            String id = params.get("id");
            String name = params.get("name");
            String url = params.get("url");
            String username = params.get("username");
            String password = params.get("password");
            boolean isDef = "true".equalsIgnoreCase(params.get("is_default")) || "on".equalsIgnoreCase(params.get("is_default"));

            if (name != null && url != null) {
                ConnectionProfile cp = new ConnectionProfile(id, name, url, username, password, isDef);
                plugin.saveOrUpdateProfile(cp);
            }
            redirect(exchange, "/connections?msg=saved");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        ConnectionProfile active = plugin.getActiveConnection();

        StringBuilder cards = new StringBuilder();
        cards.append("<div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap:20px; margin-bottom:30px;'>");

        for (ConnectionProfile cp : plugin.getConnectionProfiles()) {
            boolean isActive = active != null && active.getId().equalsIgnoreCase(cp.getId());
            String border = isActive ? "border: 2px solid #00d4ff;" : "border: 1px solid rgba(0, 212, 255, 0.2);";
            String shadow = isActive ? "box-shadow: 0 0 20px rgba(0, 212, 255, 0.35);" : "";

            cards.append("<div style='background:rgba(10, 18, 28, 0.9); padding:20px; border-radius:10px; ").append(border).append(shadow).append("'>")
                 .append("<div style='display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;'>")
                 .append("  <div style='font-size:1.1rem; font-weight:700; color:#ffd700;'><i class='fas fa-plug'></i> ").append(cp.getName()).append("</div>")
                 .append("  <div style='display:flex; gap:6px;'>");

            if (cp.isDefault()) {
                cards.append("<span class='police-badge-gold'>★ PREDETERMINADO</span>");
            }
            if (isActive) {
                cards.append("<span class='police-badge-lime'>● CONECTADO</span>");
            }

            cards.append("  </div>")
                 .append("</div>")
                 .append("<div style='font-size:0.85rem; color:#94a3b8; margin-bottom:6px;'>ID: <code style='color:#e2e8f0;'>").append(cp.getId()).append("</code></div>")
                 .append("<div style='font-size:0.85rem; color:#94a3b8; margin-bottom:6px;'>URL TCP: <code style='color:#00d4ff;'>").append(cp.getUrl()).append("</code></div>")
                 .append("<div style='font-size:0.85rem; color:#94a3b8; margin-bottom:16px;'>Usuario: <code style='color:#e2e8f0;'>").append(cp.getUsername()).append("</code></div>")
                 .append("<div style='display:flex; gap:8px;'>")
                 .append("  <form method='POST' action='").append(JettraServer.resolvePath("/connections")).append("'>")
                 .append("    <input type='hidden' name='_action' value='connect'/>")
                 .append("    <input type='hidden' name='id' value='").append(cp.getId()).append("'/>")
                 .append("    <button type='submit' style='padding:6px 14px; background:#00d4ff; color:#05080c; border:none; border-radius:4px; font-weight:700; cursor:pointer;'><i class='fas fa-bolt'></i> Conectar en Caliente</button>")
                 .append("  </form>");

            if (!cp.isDefault()) {
                cards.append("  <form method='POST' action='").append(JettraServer.resolvePath("/connections")).append("'>")
                     .append("    <input type='hidden' name='_action' value='set_default'/>")
                     .append("    <input type='hidden' name='id' value='").append(cp.getId()).append("'/>")
                     .append("    <button type='submit' style='padding:6px 12px; background:#334155; color:#ffd700; border:none; border-radius:4px; font-weight:700; cursor:pointer;'>★ Por Defecto</button>")
                     .append("  </form>");
            }

            cards.append("  <form method='POST' action='").append(JettraServer.resolvePath("/connections")).append("' onsubmit='return confirm(\"¿Eliminar conexión?\");'>")
                 .append("    <input type='hidden' name='_action' value='delete'/>")
                 .append("    <input type='hidden' name='id' value='").append(cp.getId()).append("'/>")
                 .append("    <button type='submit' style='padding:6px 10px; background:rgba(239,68,68,0.15); color:#ef4444; border:1px solid #ef4444; border-radius:4px; cursor:pointer;'><i class='fas fa-trash'></i></button>")
                 .append("  </form>")
                 .append("</div>")
                 .append("</div>");
        }
        cards.append("</div>");

        // Formulario para Registrar Nueva Conexión
        Widget newConnForm = Card.of(Column.of(
            Header.of(4, "➕ Registrar Nueva Conexión a Clúster JettraStore").modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 16px 0; color:#00d4ff;")),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/connections") + "'>" +
                "  <input type='hidden' name='_action' value='save'/>" +
                "  <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:15px; margin-bottom:15px;'>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Identificador (ID):</label>" +
                "      <input type='text' name='id' placeholder='Ej: conn_edge_03' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Nombre del Perfil:</label>" +
                "      <input type='text' name='name' required placeholder='Ej: JettraStore Edge Node' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Dirección URL (TCP):</label>" +
                "      <input type='text' name='url' required placeholder='tcp://127.0.0.1:8765' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#00d4ff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Usuario:</label>" +
                "      <input type='text' name='username' required value='admin' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Contraseña:</label>" +
                "      <input type='password' name='password' required value='admin123' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "  </div>" +
                "  <div style='margin-bottom:15px;'>" +
                "    <label style='color:#e2e8f0; font-size:0.85rem; cursor:pointer;'>" +
                "      <input type='checkbox' name='is_default' value='true'/> Marcar como perfil predeterminado del sistema" +
                "    </label>" +
                "  </div>" +
                "  <button type='submit' style='padding:8px 24px; background:#22c55e; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>Guardar Conexión</button>" +
                "</form>"
            )
        )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:20px;"));

        return Column.of(
            Header.of(4, "🔌 Administrador de Conexiones a JettraStore").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")),
            Paragraph.of(cards.toString()),
            newConnForm
        );
    }
}

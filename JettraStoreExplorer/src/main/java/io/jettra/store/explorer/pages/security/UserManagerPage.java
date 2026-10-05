package io.jettra.store.explorer.pages.security;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Page(path = "/users")
public class UserManagerPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Seguridad y Gestión de Usuarios - JettraStore";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = params.get("_action");

        if ("save_user".equals(action)) {
            String username = params.get("username");
            String password = params.get("password");
            String desc = params.get("description");
            String globalRoleStr = params.get("global_role");

            if (username != null && !username.isBlank() && password != null) {
                JettraUser.GlobalRole gRole = JettraUser.GlobalRole.valueOf(globalRoleStr);
                JettraUser u = new JettraUser(username.trim(), password.trim(), desc, gRole);

                for (String db : plugin.getDatabaseNames()) {
                    String roleVal = params.get("db_role_" + db);
                    if (roleVal != null) {
                        try {
                            u.setRoleForDatabase(db, JettraDatabaseRole.valueOf(roleVal));
                        } catch (Exception ignored) {}
                    }
                }
                plugin.saveOrUpdateUser(u);
            }
            redirect(exchange, "/users?msg=user_saved");
            return true;
        } else if ("delete_user".equals(action)) {
            String username = params.get("username");
            if (username != null) {
                plugin.deleteUser(username);
            }
            redirect(exchange, "/users?msg=user_deleted");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<JettraUser> users = plugin.getUsers();
        List<String> dbs = plugin.getDatabaseNames();

        StringBuilder table = new StringBuilder();
        table.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px; margin-bottom:25px;'>")
             .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
             .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
             .append("<th style='padding:8px;'>Usuario</th><th style='padding:8px;'>Rol Global</th><th style='padding:8px;'>Descripción</th>");

        for (String db : dbs) {
            table.append("<th style='padding:8px;'>").append(db).append("</th>");
        }
        table.append("<th style='padding:8px; text-align:right;'>Acción</th></tr></thead><tbody>");

        for (JettraUser u : users) {
            String badgeClass = u.getGlobalRole() == JettraUser.GlobalRole.ADMIN ? "police-badge-gold" : "police-badge-blue";

            table.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                 .append("<td style='padding:8px; font-weight:700; color:#ffd700;'><i class='fas fa-user-shield'></i> ").append(u.getUsername()).append("</td>")
                 .append("<td style='padding:8px;'><span class='").append(badgeClass).append("'>").append(u.getGlobalRole().name()).append("</span></td>")
                 .append("<td style='padding:8px; color:#94a3b8;'>").append(u.getDescription()).append("</td>");

            for (String db : dbs) {
                JettraDatabaseRole r = u.getRoleForDatabase(db);
                String roleColor = switch (r) {
                    case ADMIN -> "police-badge-gold";
                    case READ_WRITE -> "police-badge-lime";
                    case READ_ONLY -> "police-badge-blue";
                    default -> "color:#64748b;";
                };
                String cell = r == JettraDatabaseRole.NONE ? "<span style='color:#64748b;'>NONE</span>" : "<span class='" + roleColor + "'>" + r.name() + "</span>";
                table.append("<td style='padding:8px;'>").append(cell).append("</td>");
            }

            table.append("<td style='padding:8px; text-align:right;'>");
            if (!"admin".equalsIgnoreCase(u.getUsername())) {
                table.append("<form method='POST' action='").append(JettraServer.resolvePath("/users"))
                     .append("' style='display:inline;' onsubmit='return confirm(\"¿Eliminar usuario ").append(u.getUsername()).append("?\");'>")
                     .append("<input type='hidden' name='_action' value='delete_user'/>")
                     .append("<input type='hidden' name='username' value='").append(u.getUsername()).append("'/>")
                     .append("<button type='submit' style='padding:4px 8px; background:rgba(239,68,68,0.15); color:#ef4444; border:1px solid #ef4444; border-radius:4px; cursor:pointer;'><i class='fas fa-trash'></i></button>")
                     .append("</form>");
            } else {
                table.append("<span style='color:#64748b; font-size:0.75rem;'>PROTEGIDO</span>");
            }
            table.append("</td></tr>");
        }
        table.append("</tbody></table></div>");

        // Formulario Crear Usuario
        StringBuilder dbPermInputs = new StringBuilder();
        for (String db : dbs) {
            dbPermInputs.append("<div style='margin-bottom:8px;'>")
                        .append("<label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:2px;'>Permiso para <b>").append(db).append("</b>:</label>")
                        .append("<select name='db_role_").append(db).append("' style='width:100%; padding:6px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#ffd700;'>")
                        .append("  <option value='READ_WRITE'>READ_WRITE (Lectura y Escritura)</option>")
                        .append("  <option value='READ_ONLY'>READ_ONLY (Solo Lectura)</option>")
                        .append("  <option value='ADMIN'>ADMIN (Control Total)</option>")
                        .append("  <option value='NONE'>NONE (Sin Acceso)</option>")
                        .append("</select></div>");
        }

        Widget newUserCard = Card.of(Column.of(
            Header.of(4, "➕ Registrar o Modificar Usuario del Clúster").modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 16px 0; color:#00d4ff;")),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/users") + "'>" +
                "  <input type='hidden' name='_action' value='save_user'/>" +
                "  <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:15px; margin-bottom:15px;'>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Nombre de Usuario:</label>" +
                "      <input type='text' name='username' required placeholder='Ej: analista_finanzas' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Contraseña:</label>" +
                "      <input type='password' name='password' required placeholder='••••••••' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Rol Global del Sistema:</label>" +
                "      <select name='global_role' style='width:100%; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#ffd700;'>" +
                "        <option value='OPERATOR'>OPERATOR (Operador de Ingesta)</option>" +
                "        <option value='DEVELOPER'>DEVELOPER (Desarrollador / Consultor)</option>" +
                "        <option value='ANALYST'>ANALYST (Analista)</option>" +
                "        <option value='AUDITOR'>AUDITOR (Auditor de Cumplimiento)</option>" +
                "        <option value='ADMIN'>ADMIN (Super Administrador)</option>" +
                "      </select>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Descripción / Cargo:</label>" +
                "      <input type='text' name='description' placeholder='Ej: Operador de Ingesta Fiscal' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "  </div>" +
                "  <div style='margin-bottom:15px; background:rgba(0,0,0,0.3); padding:12px; border-radius:6px;'>" +
                "    <div style='font-size:0.85rem; font-weight:700; color:#00d4ff; margin-bottom:8px;'>Matriz de Permisos por Base de Datos:</div>" +
                "    <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:10px;'>" +
                     dbPermInputs.toString() +
                "    </div>" +
                "  </div>" +
                "  <button type='submit' style='padding:8px 24px; background:#22c55e; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>Guardar Usuario</button>" +
                "</form>"
            )
        )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:20px;"));

        return Column.of(
            Header.of(4, "🛡️ Control de Acceso y Gestión de Usuarios JettraStore").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")),
            Paragraph.of(table.toString()),
            newUserCard
        );
    }
}

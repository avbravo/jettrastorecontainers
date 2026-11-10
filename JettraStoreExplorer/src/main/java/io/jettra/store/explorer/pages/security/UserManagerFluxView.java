package io.jettra.store.explorer.pages.security;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.List;

/**
 * Vista de Seguridad, Gestión de Usuarios y Roles de Base de Datos en JettraFlux.
 */
public class UserManagerFluxView extends FluxView {

    private final String message;

    public UserManagerFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<JettraUser> users = plugin.getUsers();
        List<String> dbs = plugin.getDatabaseNames();

        // 1. Mensaje Flash
        Widget flashWidget = null;
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

        // 2. Formulario para Crear / Editar Usuario
        Widget addUserCard = buildAddUserCard(dbs);

        // 3. Tabla de Usuarios y Roles
        Header tableHeader = Header.of(3, "👥 Usuarios Registrados y Permisos Granulares por Base de Datos");
        tableHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 1rem 0;"));

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Usuario</th><th>Rol Global</th><th>Descripción</th>");
        for (String db : dbs) {
            table.append("<th>").append(db).append("</th>");
        }
        table.append("<th style='text-align:right;'>Acción</th></tr></thead><tbody>");

        for (JettraUser u : users) {
            String roleColor = u.getGlobalRole() == JettraUser.GlobalRole.ADMIN ? "#ffd700" : "#38bdf8";

            table.append("<tr>");
            table.append("<td><strong style='color:#ffd700;'>👤 ").append(u.getUsername()).append("</strong></td>");
            table.append("<td><span style='background:rgba(255,255,255,0.08); border:1px solid ").append(roleColor).append("; color:").append(roleColor).append("; padding:2px 6px; border-radius:9999px; font-weight:700; font-size:0.75rem;'>")
                 .append(u.getGlobalRole().name()).append("</span></td>");
            table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(u.getDescription()).append("</td>");

            for (String db : dbs) {
                JettraDatabaseRole r = u.getRoleForDatabase(db);
                String dbRoleColor = switch (r) {
                    case ADMIN -> "#ffd700";
                    case READ_WRITE -> "#10b981";
                    case READ_ONLY -> "#38bdf8";
                    default -> "var(--text-muted)";
                };
                table.append("<td><span style='color:").append(dbRoleColor).append("; font-weight:600; font-size:0.8rem;'>")
                     .append(r.name()).append("</span></td>");
            }

            table.append("<td style='text-align:right;'>");
            if (!"admin".equalsIgnoreCase(u.getUsername())) {
                table.append("<form method='POST' action='/users' style='display:inline;'>")
                     .append("<input type='hidden' name='action' value='delete_user'/>")
                     .append("<input type='hidden' name='username' value='").append(u.getUsername()).append("'/>")
                     .append("<button type='submit' class='app-btn-danger' onclick=\"return confirm('¿Revocar usuario ");
                table.append(u.getUsername()).append("?');\">Revocar</button></form>");
            } else {
                table.append("<span style='color:var(--text-muted); font-size:0.75rem;'>Protegido</span>");
            }
            table.append("</td></tr>");
        }

        table.append("</tbody></table>");

        Div usersCard = Div.of(tableHeader, RawHtml.of(table.toString()));
        usersCard.modifier(new Modifier().cssClass("ui-card"));

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(addUserCard);
        rootChildren.add(usersCard);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Widget buildAddUserCard(List<String> dbs) {
        Header header = Header.of(3, "➕ Registrar / Actualizar Usuario");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        StringBuilder dbRoles = new StringBuilder();
        for (String db : dbs) {
            dbRoles.append("<div style='flex:1; min-width:140px;'>")
                   .append("<label style='font-size:0.75rem; color:var(--text-muted); font-weight:600; display:block; margin-bottom:0.25rem;'>").append(db).append("</label>")
                   .append("<select class='app-input' name='db_role_").append(db).append("'>")
                   .append("<option value='READ_WRITE'>READ_WRITE</option>")
                   .append("<option value='READ_ONLY'>READ_ONLY</option>")
                   .append("<option value='ADMIN'>ADMIN</option>")
                   .append("<option value='NONE'>NONE</option>")
                   .append("</select></div>");
        }

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="POST" action="/users">
              <input type="hidden" name="action" value="save_user" />
              <div style="display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1rem;">
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Usuario</label>
                  <input class="app-input" type="text" name="username" placeholder="developer01" required />
                </div>
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Contraseña</label>
                  <input class="app-input" type="password" name="password" placeholder="••••••••" required />
                </div>
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Rol Global</label>
                  <select class="app-input" name="globalRole">
                    <option value="USER">USER (Operador Estándar)</option>
                    <option value="ADMIN">ADMIN (Superadministrador)</option>
                    <option value="READ_ONLY">READ_ONLY (Auditor)</option>
                  </select>
                </div>
                <div style="flex: 2; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Descripción</label>
                  <input class="app-input" type="text" name="description" placeholder="Desarrollador backend área facturación" />
                </div>
              </div>

              <div style="font-size: 0.8rem; font-weight: 700; color: #00d4ff; margin-bottom: 0.5rem;">Permisos por Base de Datos:</div>
              <div style="display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1.25rem;">
                %s
              </div>

              <div style="display: flex; justify-content: flex-end;">
                <button type="submit" class="app-btn-primary">➕ Guardar Usuario</button>
              </div>
            </form>
        """, dbRoles.toString()));

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);"));
        return card;
    }
}

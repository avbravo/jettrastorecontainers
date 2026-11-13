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

    private final String loggedUsername;
    private final boolean isAdmin;
    private final JettraUser editUser;
    private final String message;

    public UserManagerFluxView(String message) {
        this(null, true, null, message);
    }

    public UserManagerFluxView(String loggedUsername, boolean isAdmin, JettraUser editUser, String message) {
        this.loggedUsername = loggedUsername;
        this.isAdmin = isAdmin;
        this.editUser = editUser;
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
            boolean isError = message.toLowerCase().contains("denegado") || message.toLowerCase().contains("permiso");
            String bg = isError ? "rgba(239, 68, 68, 0.15)" : "rgba(0, 212, 255, 0.15)";
            String border = isError ? "#ef4444" : "#00d4ff";
            String color = isError ? "#ef4444" : "#00d4ff";
            Div flash = Div.of(Text.of((isError ? "⛔ " : "ℹ️ ") + message));
            flash.modifier(new Modifier()
                    .style("background: " + bg + "; border: 1px solid " + border + "; color: " + color + "; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

        // 2. Formulario para Crear / Editar Usuario (Admin: todos; Usuario estándar: solo su propio perfil)
        Widget userFormCard = buildUserFormCard(dbs);

        // 3. Tabla de Usuarios y Roles
        Header tableHeader = Header.of(3, "👥 Usuarios Registrados y Permisos Granulares por Base de Datos");
        tableHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 1rem 0;"));

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Usuario</th><th>Rol Global</th><th>Descripción</th>");
        for (String db : dbs) {
            table.append("<th>").append(escapeHtml(db)).append("</th>");
        }
        table.append("<th style='text-align:right;'>Acción</th></tr></thead><tbody>");

        for (JettraUser u : users) {
            String roleColor = u.getGlobalRole() == JettraUser.GlobalRole.ADMIN ? "#ffd700" : "#38bdf8";
            boolean isSelf = loggedUsername != null && loggedUsername.equalsIgnoreCase(u.getUsername());
            boolean canEdit = isAdmin || isSelf;

            table.append("<tr>");
            table.append("<td><strong style='color:#ffd700;'>👤 ").append(escapeHtml(u.getUsername())).append("</strong>")
                 .append(isSelf ? " <span style='color:#10b981; font-size:0.72rem; font-weight:700;'>(Tú)</span>" : "")
                 .append("</td>");
            table.append("<td><span style='background:rgba(255,255,255,0.08); border:1px solid ").append(roleColor).append("; color:").append(roleColor).append("; padding:2px 6px; border-radius:9999px; font-weight:700; font-size:0.75rem;'>")
                 .append(u.getGlobalRole().name()).append("</span></td>");
            table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(escapeHtml(u.getDescription())).append("</td>");

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

            table.append("<td style='text-align:right; white-space:nowrap;'>");
            if (canEdit) {
                table.append("<a href='/users?edit=").append(encodeUrl(u.getUsername())).append("' class='app-btn-secondary' style='margin-right:6px; padding:0.35rem 0.65rem; font-size:0.78rem;'>✏️ Editar</a>");
            }
            if (isAdmin && !"admin".equalsIgnoreCase(u.getUsername())) {
                table.append("<form method='POST' action='/users' style='display:inline;'>")
                     .append("<input type='hidden' name='action' value='delete_user'/>")
                     .append("<input type='hidden' name='username' value='").append(escapeHtml(u.getUsername())).append("'/>")
                     .append("<button type='submit' class='app-btn-danger' onclick=\"return confirm('¿Revocar usuario ")
                     .append(escapeHtml(u.getUsername())).append("?');\">Revocar</button></form>");
            } else if (!canEdit) {
                table.append("<span style='color:var(--text-muted); font-size:0.75rem;'>Solo lectura</span>");
            } else if ("admin".equalsIgnoreCase(u.getUsername())) {
                table.append("<span style='color:var(--text-muted); font-size:0.75rem;'>Protegido</span>");
            }
            table.append("</td></tr>");
        }

        table.append("</tbody></table>");

        Div usersCard = Div.of(tableHeader, RawHtml.of(table.toString()));
        usersCard.modifier(new Modifier().cssClass("ui-card"));

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        if (userFormCard != null) rootChildren.add(userFormCard);
        rootChildren.add(usersCard);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Widget buildUserFormCard(List<String> dbs) {
        boolean isEditing = editUser != null;
        if (!isAdmin && !isEditing) {
            RawHtml info = RawHtml.of(String.format("""
                <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:0.75rem;">
                  <div style="font-size:0.88rem; color:var(--text-muted);">
                    🔐 Sesión estándar (<strong style="color:#00d4ff;">%s</strong>): puedes editar tus propios datos desde el botón <strong>✏️ Editar</strong> en la tabla. Solo usuarios con rol <strong style="color:#ffd700;">ADMIN</strong> pueden crear o editar otros usuarios.
                  </div>
                  %s
                </div>
            """, escapeHtml(loggedUsername != null ? loggedUsername : ""),
                 (loggedUsername != null && !loggedUsername.isBlank()
                     ? "<a href='/users?edit=" + encodeUrl(loggedUsername) + "' class='app-btn-primary'>✏️ Editar Mi Perfil</a>"
                     : "")));
            Div card = Div.of(info);
            card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
            return card;
        }

        String title = isEditing
                ? ("✏️ Editar Usuario: " + editUser.getUsername())
                : "➕ Registrar Nuevo Usuario";
        Header header = Header.of(3, title);
        header.modifier(new Modifier().style("font-size: 1.15rem; color: " + (isEditing ? "#ffd700" : "var(--text-main)") + "; margin: 0 0 0.85rem 0;"));

        StringBuilder dbRoles = new StringBuilder();
        for (String db : dbs) {
            JettraDatabaseRole currentDbRole = isEditing ? editUser.getRoleForDatabase(db) : JettraDatabaseRole.READ_WRITE;
            if (isAdmin) {
                dbRoles.append("<div style='flex:1; min-width:140px;'>")
                       .append("<label style='font-size:0.75rem; color:var(--text-muted); font-weight:600; display:block; margin-bottom:0.25rem;'>").append(escapeHtml(db)).append("</label>")
                       .append("<select class='app-input' name='db_role_").append(escapeHtml(db)).append("'>");
                for (JettraDatabaseRole opt : JettraDatabaseRole.values()) {
                    dbRoles.append("<option value='").append(opt.name()).append("'")
                           .append(opt == currentDbRole ? " selected" : "")
                           .append(">").append(opt.name()).append("</option>");
                }
                dbRoles.append("</select></div>");
            } else {
                dbRoles.append("<div style='flex:1; min-width:140px;'>")
                       .append("<label style='font-size:0.75rem; color:var(--text-muted); font-weight:600; display:block; margin-bottom:0.25rem;'>").append(escapeHtml(db)).append("</label>")
                       .append("<input class='app-input' type='text' value='").append(currentDbRole.name()).append("' disabled style='opacity:0.7;' />")
                       .append("</div>");
            }
        }

        StringBuilder roleOptions = new StringBuilder();
        JettraUser.GlobalRole currentGlobalRole = isEditing ? editUser.getGlobalRole() : JettraUser.GlobalRole.DEVELOPER;
        boolean lockGlobalRole = !isAdmin || (isEditing && "admin".equalsIgnoreCase(editUser.getUsername()));
        if (lockGlobalRole) {
            roleOptions.append("<input type='hidden' name='globalRole' value='").append(currentGlobalRole.name()).append("' />")
                       .append("<input class='app-input' type='text' value='").append(currentGlobalRole.name()).append("' disabled style='opacity:0.7;' />");
        } else {
            roleOptions.append("<select class='app-input' name='globalRole'>");
            for (JettraUser.GlobalRole gr : JettraUser.GlobalRole.values()) {
                roleOptions.append("<option value='").append(gr.name()).append("'")
                           .append(gr == currentGlobalRole ? " selected" : "")
                           .append(">").append(gr.name()).append(" (").append(gr.getDescription()).append(")</option>");
            }
            roleOptions.append("</select>");
        }

        String usernameField = isEditing
                ? "<input type='hidden' name='username' value='" + escapeHtml(editUser.getUsername()) + "' />"
                  + "<input type='hidden' name='editMode' value='true' />"
                  + "<input class='app-input' type='text' value='" + escapeHtml(editUser.getUsername()) + "' disabled style='opacity:0.7;' />"
                : "<input class='app-input' type='text' name='username' placeholder='developer01' required />";

        String passwordField = isEditing
                ? "<input class='app-input' type='password' name='password' placeholder='Dejar vacío para conservar actual' />"
                : "<input class='app-input' type='password' name='password' placeholder='••••••••' required />";

        String descVal = isEditing && editUser.getDescription() != null ? escapeHtml(editUser.getDescription()) : "";
        String cancelBtn = isEditing ? "<a href='/users' class='app-btn-secondary' style='margin-right:0.5rem;'>Cancelar</a>" : "";
        String submitLabel = isEditing ? "💾 Actualizar Usuario" : "➕ Guardar Usuario";

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="POST" action="/users">
              <input type="hidden" name="action" value="save_user" />
              <div style="display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1rem;">
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Usuario</label>
                  %s
                </div>
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Contraseña</label>
                  %s
                </div>
                <div style="flex: 1.2; min-width: 140px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Rol Global</label>
                  %s
                </div>
                <div style="flex: 2; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Descripción</label>
                  <input class="app-input" type="text" name="description" value="%s" placeholder="Desarrollador backend área facturación" />
                </div>
              </div>

              <div style="font-size: 0.8rem; font-weight: 700; color: #00d4ff; margin-bottom: 0.5rem;">Permisos por Base de Datos:</div>
              <div style="display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1.25rem;">
                %s
              </div>

              <div style="display: flex; justify-content: flex-end; align-items: center;">
                %s
                <button type="submit" class="app-btn-primary">%s</button>
              </div>
            </form>
        """, usernameField, passwordField, roleOptions.toString(), descVal, dbRoles.toString(), cancelBtn, submitLabel));

        Div card = Div.of(header, formHtml);
        String borderStyle = isEditing ? " border-left: 4px solid #ffd700;" : "";
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);" + borderStyle));
        return card;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String encodeUrl(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}

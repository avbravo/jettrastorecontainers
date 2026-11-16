package io.jettra.store.explorer.pages.security;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
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

        List<Widget> headers = new java.util.ArrayList<>();
        headers.add(Text.of("Usuario"));
        headers.add(Text.of("Rol Global"));
        headers.add(Text.of("Descripción"));
        for (String db : dbs) {
            headers.add(Text.of(db));
        }
        headers.add(Div.of(Text.of("Acción")).modifier(new Modifier().style("text-align:right;")));

        List<List<Widget>> rows = new java.util.ArrayList<>();
        for (JettraUser u : users) {
            String roleColor = u.getGlobalRole() == JettraUser.GlobalRole.ADMIN ? "#ffd700" : "#38bdf8";
            boolean isSelf = loggedUsername != null && loggedUsername.equalsIgnoreCase(u.getUsername());
            boolean canEdit = isAdmin || isSelf;

            String userLabel = "👤 " + u.getUsername() + (isSelf ? " (Tú)" : "");
            Widget userCell = Div.of(Text.of(userLabel))
                .modifier(new Modifier().style("font-weight: 700; color: " + (isSelf ? "#10b981" : "#ffd700") + ";"));
            Widget roleCell = Badge.of(u.getGlobalRole().name())
                .modifier(new Modifier().style("background: rgba(255,255,255,0.08); border: 1px solid " + roleColor + "; color: " + roleColor + "; font-size: 0.75rem; font-weight: 700;"));
            Widget descCell = Div.of(Text.of(u.getDescription() != null ? u.getDescription() : ""))
                .modifier(new Modifier().style("color: var(--text-muted); font-size: 0.8rem;"));

            List<Widget> rowCells = new java.util.ArrayList<>();
            rowCells.add(userCell);
            rowCells.add(roleCell);
            rowCells.add(descCell);

            for (String db : dbs) {
                JettraDatabaseRole r = u.getRoleForDatabase(db);
                String dbRoleColor = switch (r) {
                    case ADMIN -> "#ffd700";
                    case READ_WRITE -> "#10b981";
                    case READ_ONLY -> "#38bdf8";
                    default -> "var(--text-muted)";
                };
                rowCells.add(Div.of(Text.of(r.name()))
                    .modifier(new Modifier().style("color: " + dbRoleColor + "; font-weight: 600; font-size: 0.8rem;")));
            }

            String actionHtml;
            String editLink = canEdit
                ? "<a href='/users?edit=" + encodeUrl(u.getUsername()) + "' class='app-btn-secondary' style='margin-right:6px; padding:0.35rem 0.65rem; font-size:0.78rem;'>✏️ Editar</a>"
                : "";
            String deleteForm = (isAdmin && !"admin".equalsIgnoreCase(u.getUsername()))
                ? "<form method='POST' action='/users' style='display:inline;'>"
                  + "<input type='hidden' name='action' value='delete_user'/>"
                  + "<input type='hidden' name='username' value='" + escapeHtml(u.getUsername()) + "'/>"
                  + "<button type='submit' class='app-btn-danger' onclick=\"return confirm('¿Revocar usuario " + escapeHtml(u.getUsername()) + "?');\">Revocar</button></form>"
                : (!canEdit ? "<span style='color:var(--text-muted); font-size:0.75rem;'>Solo lectura</span>"
                            : ("admin".equalsIgnoreCase(u.getUsername()) ? "<span style='color:var(--text-muted); font-size:0.75rem;'>Protegido</span>" : ""));
            actionHtml = "<div style='text-align:right; white-space:nowrap;'>" + editLink + deleteForm + "</div>";
            rowCells.add(RawHtml.of(actionHtml));

            rows.add(rowCells);
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Div usersCard = Div.of(tableHeader, datatable);
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

        String dbRoles = dbs.stream().map(db -> {
            JettraDatabaseRole currentDbRole = isEditing ? editUser.getRoleForDatabase(db) : JettraDatabaseRole.READ_WRITE;
            if (isAdmin) {
                String options = java.util.Arrays.stream(JettraDatabaseRole.values())
                    .map(opt -> "<option value='" + opt.name() + "'" + (opt == currentDbRole ? " selected" : "") + ">" + opt.name() + "</option>")
                    .collect(java.util.stream.Collectors.joining());
                return "<div style='flex:1; min-width:140px;'>"
                     + "<label style='font-size:0.75rem; color:var(--text-muted); font-weight:600; display:block; margin-bottom:0.25rem;'>" + escapeHtml(db) + "</label>"
                     + "<select class='app-input' name='db_role_" + escapeHtml(db) + "'>" + options + "</select></div>";
            } else {
                return "<div style='flex:1; min-width:140px;'>"
                     + "<label style='font-size:0.75rem; color:var(--text-muted); font-weight:600; display:block; margin-bottom:0.25rem;'>" + escapeHtml(db) + "</label>"
                     + "<input class='app-input' type='text' value='" + currentDbRole.name() + "' disabled style='opacity:0.7;' /></div>";
            }
        }).collect(java.util.stream.Collectors.joining("\n"));

        String roleOptions;
        JettraUser.GlobalRole currentGlobalRole = isEditing ? editUser.getGlobalRole() : JettraUser.GlobalRole.DEVELOPER;
        boolean lockGlobalRole = !isAdmin || (isEditing && "admin".equalsIgnoreCase(editUser.getUsername()));
        if (lockGlobalRole) {
            roleOptions = "<input type='hidden' name='globalRole' value='" + currentGlobalRole.name() + "' />"
                        + "<input class='app-input' type='text' value='" + currentGlobalRole.name() + "' disabled style='opacity:0.7;' />";
        } else {
            String opts = java.util.Arrays.stream(JettraUser.GlobalRole.values())
                .map(gr -> "<option value='" + gr.name() + "'" + (gr == currentGlobalRole ? " selected" : "") + ">" + gr.name() + " (" + gr.getDescription() + ")</option>")
                .collect(java.util.stream.Collectors.joining());
            roleOptions = "<select class='app-input' name='globalRole'>" + opts + "</select>";
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

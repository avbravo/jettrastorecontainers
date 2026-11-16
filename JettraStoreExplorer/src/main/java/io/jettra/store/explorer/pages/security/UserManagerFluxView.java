package io.jettra.store.explorer.pages.security;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.Arrays;
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

            List<Widget> actionItems = new java.util.ArrayList<>();
            if (canEdit) {
                actionItems.add(FluxUi.linkButton("/users?edit=" + encodeUrl(u.getUsername()), "✏️ Editar", "app-btn-secondary",
                        "margin-right:6px; padding:0.35rem 0.65rem; font-size:0.78rem;"));
            }
            if (isAdmin && !"admin".equalsIgnoreCase(u.getUsername())) {
                Form delForm = Form.of(
                        FluxUi.hidden("action", "delete_user"),
                        FluxUi.hidden("username", u.getUsername()),
                        FluxUi.submitButton("Revocar", "app-btn-danger", null)
                ).action("/users").method("POST");
                delForm.modifier(new Modifier().style("display:inline;")
                        .attribute("onsubmit", "return confirm('¿Revocar usuario " + escapeHtml(u.getUsername()) + "?');"));
                actionItems.add(delForm);
            } else if (!canEdit) {
                actionItems.add(FluxUi.coloredSpan("Solo lectura", "color:var(--text-muted); font-size:0.75rem;"));
            } else if ("admin".equalsIgnoreCase(u.getUsername())) {
                actionItems.add(FluxUi.coloredSpan("Protegido", "color:var(--text-muted); font-size:0.75rem;"));
            }
            rowCells.add(Div.of(actionItems.toArray(new Widget[0]))
                    .modifier(new Modifier().style("text-align:right; white-space:nowrap;")));

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
            Widget msgDiv = Div.of(
                    Text.of("🔐 Sesión estándar ("),
                    FluxUi.coloredSpan(loggedUsername != null ? loggedUsername : "", "color:#00d4ff; font-weight:700;"),
                    Text.of("): puedes editar tus propios datos desde el botón "),
                    FluxUi.coloredSpan("✏️ Editar", "font-weight:700;"),
                    Text.of(" en la tabla. Solo usuarios con rol "),
                    FluxUi.coloredSpan("ADMIN", "color:#ffd700; font-weight:700;"),
                    Text.of(" pueden crear o editar otros usuarios.")
            ).modifier(new Modifier().style("font-size:0.88rem; color:var(--text-muted);"));

            List<Widget> infoChildren = new java.util.ArrayList<>();
            infoChildren.add(msgDiv);
            if (loggedUsername != null && !loggedUsername.isBlank()) {
                infoChildren.add(FluxUi.linkButton("/users?edit=" + encodeUrl(loggedUsername), "✏️ Editar Mi Perfil", "app-btn-primary", null));
            }
            Widget row = Div.of(infoChildren.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:0.75rem;"));
            Div card = Div.of(row);
            card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
            return card;
        }

        String title = isEditing
                ? ("✏️ Editar Usuario: " + editUser.getUsername())
                : "➕ Registrar Nuevo Usuario";
        Header header = Header.of(3, title);
        header.modifier(new Modifier().style("font-size: 1.15rem; color: " + (isEditing ? "#ffd700" : "var(--text-main)") + "; margin: 0 0 0.85rem 0;"));

        List<String> dbRoleOptions = Arrays.stream(JettraDatabaseRole.values()).map(Enum::name).toList();
        List<Widget> dbRoleCols = new java.util.ArrayList<>();
        for (String db : dbs) {
            JettraDatabaseRole currentDbRole = isEditing ? editUser.getRoleForDatabase(db) : JettraDatabaseRole.READ_WRITE;
            Widget ctrl = isAdmin
                    ? FluxUi.dropdown("db_role_" + db, dbRoleOptions, currentDbRole.name())
                    : FluxUi.disabledInput(currentDbRole.name());
            dbRoleCols.add(FluxUi.fieldColumn(db, ctrl, "flex:1; min-width:140px;"));
        }

        JettraUser.GlobalRole currentGlobalRole = isEditing ? editUser.getGlobalRole() : JettraUser.GlobalRole.DEVELOPER;
        boolean lockGlobalRole = !isAdmin || (isEditing && "admin".equalsIgnoreCase(editUser.getUsername()));
        Widget roleControl = lockGlobalRole
                ? Div.of(FluxUi.hidden("globalRole", currentGlobalRole.name()), FluxUi.disabledInput(currentGlobalRole.name()))
                : FluxUi.dropdown("globalRole", Arrays.stream(JettraUser.GlobalRole.values()).map(Enum::name).toList(), currentGlobalRole.name());

        Widget usernameControl = isEditing
                ? Div.of(FluxUi.hidden("username", editUser.getUsername()), FluxUi.hidden("editMode", "true"), FluxUi.disabledInput(editUser.getUsername()))
                : FluxUi.textInput("username", "", "developer01", true);

        Widget passwordControl = isEditing
                ? FluxUi.passwordInput("password", "Dejar vacío para conservar actual", false)
                : FluxUi.passwordInput("password", "••••••••", true);

        String descVal = isEditing && editUser.getDescription() != null ? editUser.getDescription() : "";
        Widget descControl = FluxUi.textInput("description", descVal, "Desarrollador backend área facturación", false);

        Div topFields = Div.of(
                FluxUi.fieldColumn("Usuario", usernameControl, "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Contraseña", passwordControl, "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Rol Global", roleControl, "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Descripción", descControl, "flex: 2; min-width: 200px;")
        );
        topFields.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1rem;"));

        Widget dbRolesTitle = Div.of(Text.of("Permisos por Base de Datos:"))
                .modifier(new Modifier().style("font-size: 0.8rem; font-weight: 700; color: #00d4ff; margin-bottom: 0.5rem;"));
        Widget dbRolesRow = Div.of(dbRoleCols.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; margin-bottom: 1.25rem;"));

        List<Widget> footerBtns = new java.util.ArrayList<>();
        if (isEditing) {
            footerBtns.add(FluxUi.linkButton("/users", "Cancelar", "app-btn-secondary", "margin-right:0.5rem;"));
        }
        footerBtns.add(FluxUi.submitButton(isEditing ? "💾 Actualizar Usuario" : "➕ Guardar Usuario", "app-btn-primary", null));
        Widget footerRow = Div.of(footerBtns.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: flex; justify-content: flex-end; align-items: center;"));

        Form form = Form.of(FluxUi.hidden("action", "save_user"), topFields, dbRolesTitle, dbRolesRow, footerRow)
                .action("/users").method("POST");

        Div card = Div.of(header, form);
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

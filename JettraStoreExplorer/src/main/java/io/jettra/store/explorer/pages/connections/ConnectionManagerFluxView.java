package io.jettra.store.explorer.pages.connections;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
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
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            rootChildren.add(flash);
        }

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

        return Div.of(rootChildren.toArray(new Widget[0]));
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

package io.jettra.store.explorer.pages.connections;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.ConnectionProfile;
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

            RawHtml info = RawHtml.of(String.format("""
                <div style="font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.35rem;">ID: <code style="color: var(--text-main);">%s</code></div>
                <div style="font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.35rem;">URL TCP / Cluster: <code style="color: #00d4ff;">%s</code></div>
                <div style="font-size: 0.82rem; color: var(--text-muted); margin-bottom: 1rem;">Usuario: <code style="color: var(--text-main);">%s</code></div>
                <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
                  <form method="POST" action="/connections" style="display:inline;">
                    <input type="hidden" name="action" value="connect" />
                    <input type="hidden" name="id" value="%s" />
                    <button type="submit" class="app-btn-primary" style="padding: 0.35rem 0.75rem; font-size: 0.8rem;">⚡ Conectar</button>
                  </form>
                  %s
                  %s
                </div>
            """, cp.getId(), cp.getUrl(), cp.getUsername(), cp.getId(),
                 (!cp.isDefault() ? "<form method='POST' action='/connections' style='display:inline;'><input type='hidden' name='action' value='set_default'/><input type='hidden' name='id' value='" + cp.getId() + "'/><button type='submit' class='app-btn-secondary' style='padding:0.35rem 0.75rem; font-size:0.8rem;'>Fijar Default</button></form>" : ""),
                 (!cp.isDefault() && !isActive ? "<form method='POST' action='/connections' style='display:inline;'><input type='hidden' name='action' value='delete'/><input type='hidden' name='id' value='" + cp.getId() + "'/><button type='submit' class='app-btn-danger' style='padding:0.35rem 0.75rem; font-size:0.8rem;' onclick=\"return confirm('¿Eliminar perfil?');\">🗑️</button></form>" : "")
            ));

            Div card = Div.of(headerRow, info);
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

        RawHtml formHtml = RawHtml.of("""
            <form method="POST" action="/connections" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
              <input type="hidden" name="action" value="save" />
              <div style="flex: 1.2; min-width: 140px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">ID del Perfil</label>
                <input class="app-input" type="text" name="id" placeholder="conn_prod_cluster" required />
              </div>
              <div style="flex: 1.5; min-width: 180px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Nombre Descriptivo</label>
                <input class="app-input" type="text" name="name" placeholder="Producción Clúster Central" required />
              </div>
              <div style="flex: 2; min-width: 200px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">URL JettraStore TCP / Raft</label>
                <input class="app-input" type="text" name="url" placeholder="tcp://127.0.0.1:8765,127.0.0.1:8766" required />
              </div>
              <div style="flex: 1; min-width: 120px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Usuario</label>
                <input class="app-input" type="text" name="username" value="admin" required />
              </div>
              <div style="flex: 1; min-width: 120px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Contraseña</label>
                <input class="app-input" type="password" name="password" value="admin123" required />
              </div>
              <div>
                <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Guardar Perfil</button>
              </div>
            </form>
        """);

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);"));
        return card;
    }
}

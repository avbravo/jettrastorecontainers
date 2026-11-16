package io.jettra.store.explorer.pages.police;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.PoliceIncident;
import io.jettra.store.explorer.model.PoliceSentinelInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Monitor de Centinelas y Guardianes IA JettraStorePolice en JettraFlux.
 */
public class PoliceMonitorFluxView extends FluxView {

    private final String message;

    public PoliceMonitorFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<PoliceSentinelInfo> sentinels = plugin.getPoliceSentinels();
        List<PoliceIncident> incidents = plugin.getPoliceIncidents();

        List<Widget> rootChildren = new ArrayList<>();

        // 1. Mensaje Flash
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            rootChildren.add(flash);
        }

        // 2. Barra de Comando y Patrullaje
        RawHtml controlHtml = RawHtml.of("""
            <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
              <div>
                <strong style="color: var(--text-main); font-size: 0.95rem;">Centro de Comando JettraStorePolice:</strong>
                <span style="color: var(--text-muted); font-size: 0.85rem; margin-left: 0.5rem;">Ejecute inspección heurística en caliente sobre todos los submundos de datos y nodos Raft.</span>
              </div>
              <form method="POST" action="/police" style="margin: 0;">
                <input type="hidden" name="action" value="patrol_scan" />
                <button type="submit" class="app-btn-primary">🚨 Ejecutar Patrullaje Heurístico General</button>
              </form>
            </div>
        """);
        Div controlCard = Div.of(controlHtml);
        controlCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
        rootChildren.add(controlCard);

        // 3. Grid de Centinelas
        Header gridHeader = Header.of(3, "🛡️ Centinelas IA Activos en el Clúster");
        gridHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 1rem 0;"));
        rootChildren.add(gridHeader);

        List<Widget> cards = new ArrayList<>();
        for (PoliceSentinelInfo s : sentinels) {
            Header name = Header.of(4, "🐾 " + s.getName());
            name.modifier(new Modifier().style("margin: 0; font-size: 1.05rem; color: " + s.getColor() + ";"));

            Badge statusBadge = Badge.of(s.getStatus() + " (" + s.getHealthScore() + "%)");
            statusBadge.modifier(new Modifier().style("background: rgba(16, 185, 129, 0.2); border: 1px solid #10b981; color: #10b981; font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 700;"));

            Div headerRow = Div.of(name, statusBadge);
            headerRow.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;"));

            Paragraph desc = Paragraph.of(s.getDescription());
            desc.modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin: 0 0 1rem 0;"));

            RawHtml meta = RawHtml.of(String.format("""
                <div style="display: flex; justify-content: space-between; font-size: 0.8rem; border-top: 1px solid var(--border-subtle); padding-top: 0.75rem;">
                  <div>Nodo: <strong style="color: #ffd700;">%s</strong></div>
                  <div>Salud: <strong style="color: #10b981;">%d%%</strong></div>
                  <div>Alertas: <strong style="color: %s;">%d</strong></div>
                </div>
            """, s.getAssignedNode(), s.getHealthScore(), (s.getTotalAlerts() > 0 ? "#ffd700" : "var(--text-muted)"), s.getTotalAlerts()));

            Div card = Div.of(headerRow, desc, meta);
            card.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid " + s.getColor() + ";"));
            cards.add(card);
        }

        Div grid = Div.of(cards.toArray(new Widget[0]));
        grid.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1.25rem; margin-bottom: 2rem;"));
        rootChildren.add(grid);

        // 4. Bitácora de Incidentes
        Header logHeader = Header.of(3, "📜 Historial de Incidentes y Resoluciones Automáticas");
        logHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        List<Widget> headers = List.of(
            Text.of("Hora"),
            Text.of("Centinela"),
            Text.of("Severidad"),
            Text.of("Descripción"),
            Text.of("Acción Aplicada")
        );

        List<List<Widget>> rows = new ArrayList<>();
        for (PoliceIncident inc : incidents) {
            String sevColor = "OK".equalsIgnoreCase(inc.getSeverity()) ? "#10b981" : ("WARN".equalsIgnoreCase(inc.getSeverity()) ? "#f59e0b" : "#ef4444");

            Widget timeCell = Div.of(Text.of(inc.getTimestamp()))
                .modifier(new Modifier().style("font-family: monospace; font-size: 0.85rem; color: #ffd700;"));
            Widget sentinelCell = Div.of(Text.of(inc.getSentinelName()))
                .modifier(new Modifier().style("font-weight: 700; color: var(--text-main);"));
            Widget sevCell = Badge.of(inc.getSeverity())
                .modifier(new Modifier().style("color: " + sevColor + "; border: 1px solid " + sevColor + "; background: transparent; font-weight: 700;"));
            Widget descCell = Text.of(inc.getEventDescription());
            Widget actionCell = Div.of(Text.of(inc.getActionTaken()))
                .modifier(new Modifier().style("color: var(--text-muted); font-size: 0.85rem;"));

            rows.add(List.of(timeCell, sentinelCell, sevCell, descCell, actionCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Div logCard = Div.of(logHeader, datatable);
        logCard.modifier(new Modifier().cssClass("ui-card"));
        rootChildren.add(logCard);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }
}

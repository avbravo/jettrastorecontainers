package io.jettra.store.explorer.pages.examples;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Span;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.JettraStorePlugin.ExampleDatabaseInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Vista JettraFlux para la administración de Bases de Datos de Ejemplo (/examples).
 * Permite listar, instalar y eliminar bases de datos de ejemplo bajo demanda.
 */
public class ExamplesFluxView extends FluxView {

    private final String message;

    public ExamplesFluxView() {
        this(null);
    }

    public ExamplesFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<ExampleDatabaseInfo> examples = plugin.getAvailableExampleDatabases();
        long installedCount = examples.stream().filter(ExampleDatabaseInfo::installed).count();
        long pendingCount = examples.size() - installedCount;

        List<Widget> root = new ArrayList<>();

        if (message != null && !message.isBlank()) {
            Div alert = Div.of(Paragraph.of(message).modifier(new Modifier().style("margin: 0; font-weight: 600; color: #34d399;")));
            alert.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1rem; border-left: 4px solid #10b981; background: rgba(16, 185, 129, 0.12); padding: 0.75rem 1rem;"));
            root.add(alert);
        }

        // 1. Resumen KPI
        Widget kpiGrid = Div.of(
                buildKpi("Ejemplos Disponibles", String.valueOf(examples.size()), "#38bdf8"),
                buildKpi("Instaladas", String.valueOf(installedCount), "#10b981"),
                buildKpi("No Instaladas", String.valueOf(pendingCount), "#f59e0b")
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-bottom: 1.25rem;"));
        root.add(kpiGrid);

        // 2. Cabecera y acciones globales
        Header title = Header.of(3, "📚 Administración de Bases de Datos de Ejemplo");
        title.modifier(new Modifier().style("margin: 0; font-size: 1.1rem; color: var(--text-main);"));

        Paragraph subtitle = Paragraph.of("JettraStore inicia limpio sin bases de ejemplo precargadas. Desde este formulario puedes instalar o eliminar cada base de datos de muestra bajo demanda.");
        subtitle.modifier(new Modifier().style("margin: 0.25rem 0 0 0; font-size: 0.83rem; color: var(--text-muted);"));

        Div titleBox = Div.of(title, subtitle);

        Form installAllForm = Form.of(
                FluxUi.hidden("action", "install_all"),
                FluxUi.submitButton("📥 Instalar Todas", "app-btn-primary", "height: 36px;")
        ).action("/examples").method("POST");

        Form deleteAllForm = Form.of(
                FluxUi.hidden("action", "delete_all"),
                FluxUi.submitButton("🗑️ Eliminar Todas", "app-btn-danger", "height: 36px;")
        ).action("/examples").method("POST");

        Row globalActions = Row.of(installAllForm, deleteAllForm);
        globalActions.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap;"));

        Row headerRow = Row.of(titleBox, globalActions);
        headerRow.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1rem;"));

        // 3. Listado de bases de ejemplo en tarjetas JettraFlux responsivas
        List<Widget> exampleCards = new ArrayList<>();
        for (ExampleDatabaseInfo ex : examples) {
            Header dbTitle = Header.of(4, "🗄️ " + ex.id());
            dbTitle.modifier(new Modifier().style("margin: 0; font-size: 1rem; color: var(--text-main);"));

            Badge statusBadge = ex.installed()
                    ? Badge.of("● INSTALADA")
                    : Badge.of("○ NO INSTALADA");
            statusBadge.modifier(new Modifier().style(ex.installed()
                    ? "background: rgba(16, 185, 129, 0.2); border: 1px solid #10b981; color: #10b981; font-size: 0.72rem; padding: 0.2rem 0.55rem; border-radius: 9999px; font-weight: 700;"
                    : "background: rgba(148, 163, 184, 0.18); border: 1px solid #94a3b8; color: #94a3b8; font-size: 0.72rem; padding: 0.2rem 0.55rem; border-radius: 9999px; font-weight: 700;"));

            Row cardTop = Row.of(dbTitle, statusBadge);
            cardTop.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; margin-bottom: 0.45rem; flex-wrap: wrap;"));

            Span dbSubtitle = Span.of(ex.title());
            dbSubtitle.modifier(new Modifier().style("font-size: 0.84rem; font-weight: 600; color: #38bdf8; display: block; margin-bottom: 0.35rem;"));

            Paragraph descCell = Paragraph.of(ex.description());
            descCell.modifier(new Modifier().style("margin: 0 0 0.65rem 0; font-size: 0.82rem; color: var(--text-muted);"));

            Badge enginesBadge = Badge.of("Motores: " + ex.enginesSummary());
            enginesBadge.modifier(new Modifier().style("background: rgba(56, 189, 248, 0.12); border: 1px solid #38bdf8; color: #38bdf8; font-size: 0.72rem; padding: 0.2rem 0.55rem; border-radius: 9999px; font-weight: 600; display: inline-block; margin-bottom: 0.85rem;"));

            List<Widget> rowActions = new ArrayList<>();
            if (!ex.installed()) {
                Form installForm = Form.of(
                        FluxUi.hidden("action", "install_example"),
                        FluxUi.hidden("exampleId", ex.id()),
                        FluxUi.submitButton("📥 Instalar", "app-btn-primary", "padding: 0.38rem 0.85rem; font-size: 0.82rem;")
                ).action("/examples").method("POST");
                rowActions.add(installForm);
            } else {
                Link exploreLink = FluxUi.linkButton("/explorer?db=" + ex.id(), "📦 Explorar", "app-btn-secondary", "padding: 0.38rem 0.75rem; font-size: 0.82rem;");
                Form deleteForm = Form.of(
                        FluxUi.hidden("action", "delete_example"),
                        FluxUi.hidden("exampleId", ex.id()),
                        FluxUi.submitButton("🗑️ Eliminar", "app-btn-danger", "padding: 0.38rem 0.85rem; font-size: 0.82rem;")
                ).action("/examples").method("POST");
                rowActions.add(exploreLink);
                rowActions.add(deleteForm);
            }
            Row actionsCell = Row.of(rowActions.toArray(new Widget[0]));
            actionsCell.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; justify-content: flex-end; flex-wrap: wrap;"));

            Div exCard = Div.of(cardTop, dbSubtitle, descCell, enginesBadge, actionsCell);
            exCard.modifier(new Modifier()
                    .cssClass("ui-card")
                    .style("background: var(--bg-subtle); border-top: 3px solid " + (ex.installed() ? "#10b981" : "#64748b") + "; padding: 1rem;"));
            exampleCards.add(exCard);
        }

        Div cardsGrid = Div.of(exampleCards.toArray(new Widget[0]));
        cardsGrid.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(290px, 1fr)); gap: 1rem;"));

        Div mainCard = Div.of(headerRow, cardsGrid);
        mainCard.modifier(new Modifier().cssClass("ui-card"));
        root.add(mainCard);

        return Div.of(root.toArray(new Widget[0]));
    }

    private Widget buildKpi(String label, String value, String color) {
        Span lbl = Span.of(label);
        lbl.modifier(new Modifier().style("font-size: 0.78rem; color: var(--text-muted); font-weight: 600; display: block; margin-bottom: 0.35rem;"));
        Span val = Span.of(value);
        val.modifier(new Modifier().style("font-size: 1.45rem; font-weight: 800; color: " + color + "; display: block;"));
        Div card = Div.of(lbl, val);
        card.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid " + color + "; padding: 0.9rem 1rem;"));
        return card;
    }
}

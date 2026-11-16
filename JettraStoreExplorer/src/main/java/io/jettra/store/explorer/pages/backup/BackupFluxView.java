package io.jettra.store.explorer.pages.backup;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.BackupSnapshotInfo;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.List;

/**
 * Gestión de Copias de Seguridad, Snapshots y Restauración Zero-Copy en JettraFlux.
 */
public class BackupFluxView extends FluxView {

    private final String message;

    public BackupFluxView(String message) {
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<BackupSnapshotInfo> snapshots = plugin.getBackupSnapshots();
        List<String> dbs = plugin.getDatabaseNames();

        // 1. Mensaje Flash
        Widget flashWidget = null;
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

        // 2. Formulario para Crear Nuevo Snapshot
        Widget createSnapshotCard = buildCreateSnapshotCard(dbs);

        // 3. Tabla de Snapshots
        Header tableHeader = Header.of(3, "💾 Snapshots y Copias de Seguridad Disponibles");
        tableHeader.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 1rem 0;"));

        List<Widget> headers = List.of(
            Text.of("ID Snapshot"),
            Text.of("Base de Datos"),
            Text.of("Motor"),
            Text.of("Fecha"),
            Text.of("Objetos"),
            Text.of("Tamaño"),
            Text.of("Ratio"),
            Text.of("Algoritmo"),
            Text.of("Firma SHA-256"),
            Div.of(Text.of("Acciones")).modifier(new Modifier().style("text-align:right;"))
        );

        List<List<Widget>> rows = new java.util.ArrayList<>();
        for (BackupSnapshotInfo b : snapshots) {
            String shaShort = b.getChecksumSha256().length() > 12 ? b.getChecksumSha256().substring(0, 12) + "..." : b.getChecksumSha256();

            Widget idCell = Div.of(Text.of(b.getId()))
                .modifier(new Modifier().style("font-family: monospace; font-weight: 700; color: #ffd700;"));
            Widget dbCell = Div.of(Text.of(b.getDatabaseName()))
                .modifier(new Modifier().style("font-weight: 700; color: var(--text-main);"));
            Widget engineCell = Badge.of(b.getEngineType())
                .modifier(new Modifier().style("background: rgba(255,255,255,0.06); border: 1px solid #00d4ff; color: #00d4ff; font-weight: 700; font-size: 0.72rem;"));
            Widget dateCell = Div.of(Text.of(b.getTimestamp()))
                .modifier(new Modifier().style("color: var(--text-muted); font-size: 0.8rem;"));
            Widget objCell = Text.of(String.format("%,d", b.getTotalObjects()));
            Widget sizeCell = Div.of(Text.of(b.getFormattedSize()))
                .modifier(new Modifier().style("color: #10b981; font-weight: 700;"));
            Widget ratioCell = Div.of(Text.of(String.format("%.2f:1", b.getCompressionRatio())))
                .modifier(new Modifier().style("color: #ffd700; font-weight: 700;"));
            Widget algoCell = Text.of(b.getCompressionAlgo());
            Widget shaCell = Div.of(Text.of(shaShort))
                .modifier(new Modifier().style("color: var(--text-muted); font-family: monospace; font-size: 0.75rem;").attribute("title", b.getChecksumSha256()));

            Form restoreForm = Form.of(
                    FluxUi.hidden("action", "restore"),
                    FluxUi.hidden("id", b.getId()),
                    FluxUi.hidden("target_db", b.getDatabaseName()),
                    FluxUi.submitButton("♻️ Restaurar", "app-btn-primary", "padding:0.3rem 0.65rem; font-size:0.78rem; margin-right:5px;")
            ).action("/backups").method("POST");
            restoreForm.modifier(new Modifier().style("display:inline;")
                    .attribute("onsubmit", "return confirm('¿Restaurar snapshot " + b.getId() + " en caliente?');"));

            Form deleteForm = Form.of(
                    FluxUi.hidden("action", "delete"),
                    FluxUi.hidden("id", b.getId()),
                    FluxUi.submitButton("🗑️", "app-btn-danger", "padding:0.3rem 0.65rem; font-size:0.78rem;")
            ).action("/backups").method("POST");
            deleteForm.modifier(new Modifier().style("display:inline;")
                    .attribute("onsubmit", "return confirm('¿Eliminar snapshot?');"));

            Widget actionCell = Div.of(restoreForm, deleteForm)
                    .modifier(new Modifier().style("text-align:right; white-space:nowrap;"));

            rows.add(List.of(idCell, dbCell, engineCell, dateCell, objCell, sizeCell, ratioCell, algoCell, shaCell, actionCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(tableHeader, datatable);
        card.modifier(new Modifier().cssClass("ui-card"));

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(createSnapshotCard);
        rootChildren.add(card);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Widget buildCreateSnapshotCard(List<String> dbs) {
        Header header = Header.of(3, "➕ Crear Nueva Copia de Seguridad Inmutable");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        Form form = Form.of(
                FluxUi.hidden("action", "create"),
                FluxUi.fieldColumn("Base de Datos Origen", FluxUi.dropdown("db_name", dbs, dbs.isEmpty() ? "" : dbs.get(0)), "flex: 1.5; min-width: 180px;"),
                FluxUi.fieldColumn("Algoritmo Compresión", FluxUi.dropdown("algo", List.of("ZSTD_SIMD", "LZ4_PANAMA", "GZIP_NATIVE"), "ZSTD_SIMD"), "flex: 1.2; min-width: 140px;"),
                FluxUi.fieldColumn("Ruta Destino", FluxUi.textInput("path", "", "~/jettra/backups/snapshot_autobkp.jbk", false), "flex: 2; min-width: 220px;"),
                Div.of(FluxUi.submitButton("💾 Generar Snapshot", "app-btn-primary", "height: 38px;"))
        ).action("/backups").method("POST");
        form.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Div card = Div.of(header, form);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);"));
        return card;
    }
}

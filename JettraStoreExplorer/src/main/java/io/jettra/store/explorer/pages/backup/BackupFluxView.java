package io.jettra.store.explorer.pages.backup;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.BackupSnapshotInfo;
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

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>ID Snapshot</th><th>Base de Datos</th><th>Motor</th><th>Fecha</th><th>Objetos</th><th>Tamaño</th><th>Ratio</th><th>Algoritmo</th><th>Firma SHA-256</th><th style='text-align:right;'>Acciones</th></tr></thead>");
        table.append("<tbody>");

        for (BackupSnapshotInfo b : snapshots) {
            String shaShort = b.getChecksumSha256().length() > 12 ? b.getChecksumSha256().substring(0, 12) + "..." : b.getChecksumSha256();

            table.append("<tr>");
            table.append("<td><code style='color:#ffd700; font-weight:700;'>").append(b.getId()).append("</code></td>");
            table.append("<td><strong>").append(b.getDatabaseName()).append("</strong></td>");
            table.append("<td><span style='background:rgba(255,255,255,0.06); border:1px solid #00d4ff; color:#00d4ff; padding:2px 6px; border-radius:9999px; font-weight:700; font-size:0.72rem;'>")
                 .append(b.getEngineType()).append("</span></td>");
            table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(b.getTimestamp()).append("</td>");
            table.append("<td>").append(String.format("%,d", b.getTotalObjects())).append("</td>");
            table.append("<td><strong style='color:#10b981;'>").append(b.getFormattedSize()).append("</strong></td>");
            table.append("<td><span style='color:#ffd700; font-weight:700;'>").append(String.format("%.2f:1", b.getCompressionRatio())).append("</span></td>");
            table.append("<td>").append(b.getCompressionAlgo()).append("</td>");
            table.append("<td><code style='color:var(--text-muted); font-size:0.75rem;' title='").append(b.getChecksumSha256()).append("'>").append(shaShort).append("</code></td>");
            table.append("<td style='text-align:right; white-space:nowrap;'>");
            table.append("<form method='POST' action='/backups' style='display:inline;' onsubmit=\"return confirm('¿Restaurar snapshot ");
            table.append(b.getId()).append(" en caliente?');\">");
            table.append("<input type='hidden' name='action' value='restore'/>");
            table.append("<input type='hidden' name='id' value='").append(b.getId()).append("'/>");
            table.append("<input type='hidden' name='target_db' value='").append(b.getDatabaseName()).append("'/>");
            table.append("<button type='submit' class='app-btn-primary' style='padding:0.3rem 0.65rem; font-size:0.78rem; margin-right:5px;'>♻️ Restaurar</button></form>");

            table.append("<form method='POST' action='/backups' style='display:inline;' onsubmit=\"return confirm('¿Eliminar snapshot?');\">");
            table.append("<input type='hidden' name='action' value='delete'/>");
            table.append("<input type='hidden' name='id' value='").append(b.getId()).append("'/>");
            table.append("<button type='submit' class='app-btn-danger' style='padding:0.3rem 0.65rem; font-size:0.78rem;'>🗑️</button></form>");
            table.append("</td></tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(tableHeader, RawHtml.of(table.toString()));
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

        StringBuilder dbOpts = new StringBuilder();
        for (String db : dbs) {
            dbOpts.append("<option value='").append(db).append("'>").append(db).append("</option>");
        }

        RawHtml formHtml = RawHtml.of(String.format("""
            <form method="POST" action="/backups" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
              <input type="hidden" name="action" value="create" />
              <div style="flex: 1.5; min-width: 180px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Base de Datos Origen</label>
                <select class="app-input" name="db_name">
                  %s
                </select>
              </div>
              <div style="flex: 1.2; min-width: 140px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Algoritmo Compresión</label>
                <select class="app-input" name="algo">
                  <option value="ZSTD_SIMD">ZSTD SIMD (Ultra Rápido)</option>
                  <option value="LZ4_PANAMA">LZ4 Panama Direct (Baja Latencia)</option>
                  <option value="GZIP_NATIVE">GZIP Native</option>
                </select>
              </div>
              <div style="flex: 2; min-width: 220px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Ruta Destino</label>
                <input class="app-input" type="text" name="path" placeholder="~/jettra/backups/snapshot_autobkp.jbk" />
              </div>
              <div>
                <button type="submit" class="app-btn-primary" style="height: 38px;">💾 Generar Snapshot</button>
              </div>
            </form>
        """, dbOpts.toString()));

        Div card = Div.of(header, formHtml);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; background: var(--bg-subtle);"));
        return card;
    }
}

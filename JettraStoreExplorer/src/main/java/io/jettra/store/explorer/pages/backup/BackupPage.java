package io.jettra.store.explorer.pages.backup;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.BackupSnapshotInfo;
import io.jettra.store.explorer.pages.template.ExplorerTemplatePage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Page(path = "/backups")
public class BackupPage extends ExplorerTemplatePage {

    @Override
    protected String getTitle() {
        return "Copias de Seguridad y Snapshots - JettraStore";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        String action = params.get("_action");

        if ("create".equals(action)) {
            String db = params.get("db_name");
            String algo = params.get("algo");
            String path = params.get("path");
            if (db != null) {
                plugin.createBackup(db, "MULTIMODEL_SNAPSHOT", algo, path);
            }
            redirect(exchange, "/backups?msg=created");
            return true;
        } else if ("restore".equals(action)) {
            String id = params.get("id");
            String targetDb = params.get("target_db");
            if (id != null) {
                plugin.restoreBackup(id, targetDb != null ? targetDb : "example_factura_db");
            }
            redirect(exchange, "/backups?msg=restored");
            return true;
        } else if ("delete".equals(action)) {
            String id = params.get("id");
            if (id != null) {
                plugin.deleteSnapshot(id);
            }
            redirect(exchange, "/backups?msg=deleted");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildCenter(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<BackupSnapshotInfo> snapshots = plugin.getBackupSnapshots();
        List<String> dbs = plugin.getDatabaseNames();

        StringBuilder table = new StringBuilder();
        table.append("<div style='overflow-x:auto; background:rgba(10, 18, 28, 0.85); border:1px solid rgba(0, 212, 255, 0.25); border-radius:8px; padding:15px; margin-bottom:25px;'>")
             .append("<table style='width:100%; border-collapse:collapse; font-size:0.85rem; color:#cbd5e1;'>")
             .append("<thead><tr style='border-bottom:1px solid rgba(0, 212, 255, 0.3); color:#00d4ff; text-align:left;'>")
             .append("<th style='padding:8px;'>ID Snapshot</th><th style='padding:8px;'>Base de Datos</th><th style='padding:8px;'>Motor</th><th style='padding:8px;'>Fecha Creación</th><th style='padding:8px;'>Objetos</th><th style='padding:8px;'>Tamaño</th><th style='padding:8px;'>Ratio</th><th style='padding:8px;'>Algoritmo</th><th style='padding:8px;'>Firma SHA-256</th><th style='padding:8px; text-align:right;'>Acciones</th>")
             .append("</tr></thead><tbody>");

        for (BackupSnapshotInfo b : snapshots) {
            table.append("<tr style='border-bottom:1px solid rgba(255,255,255,0.05);'>")
                 .append("<td style='padding:8px; font-weight:700; color:#ffd700; font-family:monospace;'>").append(b.getId()).append("</td>")
                 .append("<td style='padding:8px; font-weight:700;'>").append(b.getDatabaseName()).append("</td>")
                 .append("<td style='padding:8px;'><span class='police-badge-blue'>").append(b.getEngineType()).append("</span></td>")
                 .append("<td style='padding:8px; color:#94a3b8;'>").append(b.getTimestamp()).append("</td>")
                 .append("<td style='padding:8px;'>").append(String.format("%,d", b.getTotalObjects())).append("</td>")
                 .append("<td style='padding:8px; font-weight:700; color:#22c55e;'>").append(b.getFormattedSize()).append("</td>")
                 .append("<td style='padding:8px;'><span class='police-badge-gold'>").append(String.format("%.2f:1", b.getCompressionRatio())).append("</span></td>")
                 .append("<td style='padding:8px;'>").append(b.getCompressionAlgo()).append("</td>")
                 .append("<td style='padding:8px; font-family:monospace; font-size:0.75rem; color:#64748b;' title='").append(b.getChecksumSha256()).append("'>")
                 .append(b.getChecksumSha256().substring(0, Math.min(12, b.getChecksumSha256().length()))).append("...</td>")
                 .append("<td style='padding:8px; text-align:right; white-space:nowrap;'>")
                 .append("  <form method='POST' action='").append(JettraServer.resolvePath("/backups")).append("' style='display:inline;' onsubmit='return confirm(\"¿Restaurar snapshot ").append(b.getId()).append(" en caliente?\");'>")
                 .append("    <input type='hidden' name='_action' value='restore'/>")
                 .append("    <input type='hidden' name='id' value='").append(b.getId()).append("'/>")
                 .append("    <input type='hidden' name='target_db' value='").append(b.getDatabaseName()).append("'/>")
                 .append("    <button type='submit' style='padding:4px 10px; background:#00d4ff; color:#05080c; border:none; border-radius:4px; font-weight:700; cursor:pointer; margin-right:6px;'>♻️ Restaurar</button>")
                 .append("  </form>")
                 .append("  <form method='POST' action='").append(JettraServer.resolvePath("/backups")).append("' style='display:inline;' onsubmit='return confirm(\"¿Eliminar respaldo?\");'>")
                 .append("    <input type='hidden' name='_action' value='delete'/>")
                 .append("    <input type='hidden' name='id' value='").append(b.getId()).append("'/>")
                 .append("    <button type='submit' style='padding:4px 8px; background:rgba(239,68,68,0.15); color:#ef4444; border:1px solid #ef4444; border-radius:4px; cursor:pointer;'>🗑️</button>")
                 .append("  </form>")
                 .append("</td></tr>");
        }
        table.append("</tbody></table></div>");

        // Formulario Crear Snapshot
        StringBuilder dbOpts = new StringBuilder();
        for (String db : dbs) {
            dbOpts.append("<option value='").append(db).append("'>").append(db).append("</option>");
        }

        Widget createForm = Card.of(Column.of(
            Header.of(4, "➕ Crear Nueva Copia de Seguridad Instantánea (Snapshot)").modifier(new io.jettra.flux.core.Modifier().style("margin:0 0 16px 0; color:#00d4ff;")),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/backups") + "'>" +
                "  <input type='hidden' name='_action' value='create'/>" +
                "  <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:15px; margin-bottom:15px;'>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Base de Datos:</label>" +
                "      <select name='db_name' style='width:100%; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#ffd700;'>" +
                       dbOpts.toString() +
                "      </select>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Algoritmo de Compresión:</label>" +
                "      <select name='algo' style='width:100%; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#22c55e;'>" +
                "        <option value='ZSTD_SIMD'>ZSTD SIMD (Máxima Compresión ~3.4:1)</option>" +
                "        <option value='LZ4_PANAMA'>LZ4 Panama FFM (Ultra Rápido ~2.8:1)</option>" +
                "        <option value='GZIP_STANDARD'>GZIP Standard Deflate</option>" +
                "      </select>" +
                "    </div>" +
                "    <div>" +
                "      <label style='display:block; font-size:0.8rem; color:#94a3b8; margin-bottom:4px;'>Ruta Destino Archivo:</label>" +
                "      <input type='text' name='path' placeholder='~/jettra/backups/snapshot_manual.jbk' style='width:100%; box-sizing:border-box; padding:8px; background:#05080c; border:1px solid #334155; border-radius:4px; color:#fff;'/>" +
                "    </div>" +
                "  </div>" +
                "  <button type='submit' style='padding:8px 24px; background:#22c55e; color:#05080c; border:none; border-radius:6px; font-weight:700; cursor:pointer;'>Generar Respaldo Inmediato</button>" +
                "</form>"
            )
        )).modifier(new io.jettra.flux.core.Modifier().cssClass("police-card-glow").style("padding:20px;"));

        return Column.of(
            Header.of(4, "💾 Administrador de Copias de Seguridad y Snapshots de JettraStore").modifier(new io.jettra.flux.core.Modifier().cssClass("police-hud-title").style("margin-bottom:15px;")),
            Paragraph.of(table.toString()),
            createForm
        );
    }
}

package io.jettra.store.explorer.pages.query;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Accordion;
import io.jettra.flux.widgets.AccordionTab;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.HelpCategory;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.HelpCommand;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.ShellExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Consola Interactiva JettraStoreShell, JettraSQL y JettraQL para ejecución directa de comandos y consultas.
 */
public class QueryConsoleFluxView extends FluxView {

    private final String selectedDb;
    private final String selectedBucket;
    private final String queryText;

    public QueryConsoleFluxView(String selectedDb, String selectedBucket, String queryText) {
        this.selectedDb = selectedDb;
        this.selectedBucket = selectedBucket;
        this.queryText = queryText;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        JettraStoreShellExecutor shell = JettraStoreShellExecutor.getInstance();
        List<String> dbs = plugin.getDatabaseNames();

        String initialDb = (selectedDb != null && plugin.getDatabasesCatalog().containsKey(selectedDb))
                ? selectedDb
                : (dbs.isEmpty() ? "example_factura_db" : dbs.get(0));

        List<EngineBucketInfo> initialBuckets = plugin.getBuckets(initialDb);
        String rawBucket = selectedBucket;
        String initialBucket = (rawBucket != null && initialBuckets.stream().anyMatch(b -> b.getBucketName().equalsIgnoreCase(rawBucket)))
                ? rawBucket
                : (initialBuckets.isEmpty() ? "Facturas" : initialBuckets.get(0).getBucketName());

        String q = (queryText != null && !queryText.isBlank()) ? queryText : "total > 500";
        ShellExecutionResult shellResult = shell.execute(plugin, initialDb, initialBucket, q);

        // Si un comando USE <db> o CREATE DATABASE cambió el contexto activo, actualizar selectores
        String activeDb = plugin.getDatabasesCatalog().containsKey(shellResult.resolvedDb())
                ? shellResult.resolvedDb()
                : initialDb;
        List<EngineBucketInfo> buckets = plugin.getBuckets(activeDb);
        String activeBucket = buckets.stream()
                .anyMatch(b -> b.getBucketName().equalsIgnoreCase(shellResult.resolvedBucket()))
                ? shellResult.resolvedBucket()
                : (buckets.isEmpty() ? initialBucket : buckets.get(0).getBucketName());

        // Selectores HTML
        StringBuilder dbOpts = new StringBuilder();
        for (String db : plugin.getDatabaseNames()) {
            dbOpts.append("<option value='").append(escapeHtml(db)).append("' ")
                  .append(db.equalsIgnoreCase(activeDb) ? "selected" : "")
                  .append(">").append(escapeHtml(db)).append("</option>");
        }

        StringBuilder bucketOpts = new StringBuilder();
        for (EngineBucketInfo b : buckets) {
            bucketOpts.append("<option value='").append(escapeHtml(b.getBucketName())).append("' ")
                      .append(b.getBucketName().equalsIgnoreCase(activeBucket) ? "selected" : "")
                      .append(">").append(escapeHtml(b.getBucketName())).append(" [").append(escapeHtml(b.getEngineType())).append("]</option>");
        }

        // Cabecera con título y botón 'Ayuda' (Help) de JettraFlux
        Header header = Header.of(3, "⚡ Consola de Consulta Directa JettraSQL / JettraQL & JettraStoreShell");
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0;"));

        JettraButton helpBtn = JettraButton.of("❓ Ayuda (Help)")
            .severity(JettraButton.Severity.HELP)
            .type(JettraButton.Type.SOLID);
        helpBtn.modifier(new Modifier()
            .attribute("id", "queryHelpBtn")
            .cssClass("app-btn-secondary")
            .style("background: rgba(168, 85, 247, 0.2); border: 1px solid #a855f7; color: #e9d5ff; font-weight: 700;")
            .attribute("onclick", "openQueryHelpModal()"));

        Row headerRow = Row.of(header, helpBtn);
        headerRow.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap;"));

        RawHtml formHtml = RawHtml.of(String.format("""
            <form id="queryShellForm" method="POST" action="/query">
              <div style="display: flex; gap: 1rem; margin-bottom: 1rem; flex-wrap: wrap;">
                <div style="flex: 1; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">Base de Datos Activa:</label>
                  <select name="db" onchange="this.form.submit()" class="app-input" style="color: #ffd700; font-weight: 600;">
                    %s
                  </select>
                </div>
                <div style="flex: 1; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">Bucket / Motor Activo:</label>
                  <select name="bucket" onchange="this.form.submit()" class="app-input" style="color: #10b981; font-weight: 600;">
                    %s
                  </select>
                </div>
              </div>

              <div style="margin-bottom: 1rem;">
                <label style="font-size: 0.75rem; color: var(--text-muted); font-weight: 700; display: block; margin-bottom: 0.3rem;">
                  Prompt JettraStoreShell &gt; (Comandos Shell, JettraSQL, JQL, KV, Graph, Math, Stats):
                </label>
                <textarea id="queryCommandInput" name="query" rows="3" class="app-input" placeholder="Ej: SHOW DATABASES | SHOW NODES | SELECT * FROM Facturas WHERE total > 500 | MATH sqrt(144)" style="font-family: monospace; font-size: 0.95rem; color: #38bdf8; background: #020617;">%s</textarea>
              </div>

              <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem;">
                <div style="font-size: 0.78rem; color: var(--text-muted); display: flex; flex-wrap: wrap; gap: 6px; align-items: center;">
                  <span>Comandos rápidos:</span>
                  <button type="button" onclick="runShellExample('STATUS')" style="background: rgba(0,212,255,0.1); border:1px solid #00d4ff; color:#00d4ff; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;">STATUS</button>
                  <button type="button" onclick="runShellExample('SHOW NODES')" style="background: rgba(0,212,255,0.1); border:1px solid #00d4ff; color:#00d4ff; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;">SHOW NODES</button>
                  <button type="button" onclick="runShellExample('SHOW DATABASES')" style="background: rgba(0,212,255,0.1); border:1px solid #00d4ff; color:#00d4ff; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;">SHOW DATABASES</button>
                  <button type="button" onclick="runShellExample('total > 1000')" style="background: rgba(16,185,129,0.12); border:1px solid #10b981; color:#10b981; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;">total &gt; 1000</button>
                  <button type="button" onclick="runShellExample('MATH sqrt(144) + 8 * 2')" style="background: rgba(168,85,247,0.15); border:1px solid #a855f7; color:#e9d5ff; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;">MATH sqrt(144)</button>
                </div>
                <button type="submit" class="app-btn-primary">⚡ Ejecutar Comando / Consulta</button>
              </div>
            </form>
        """, dbOpts.toString(), bucketOpts.toString(), escapeHtml(q)));

        Div consoleCard = Div.of(headerRow, formHtml);
        consoleCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem;"));

        // Banner de resultado (Verde si éxito, Rojo si error de comando/sintaxis)
        String feedbackBg = shellResult.success() ? "rgba(16, 185, 129, 0.15)" : "rgba(239, 68, 68, 0.18)";
        String feedbackBorder = shellResult.success() ? "#10b981" : "#ef4444";
        String feedbackColor = shellResult.success() ? "#10b981" : "#fca5a5";
        String timingSuffix = String.format(Locale.US, " (ejecutado en %.2f ms / %d µs)", shellResult.elapsedMicros() / 1000.0, shellResult.elapsedMicros());

        Div feedback = Div.of(Text.of((shellResult.success() ? "⏱️ " : "❌ ") + shellResult.statusMessage() + timingSuffix));
        feedback.modifier(new Modifier().style("background: " + feedbackBg + "; border: 1px solid " + feedbackBorder + "; color: " + feedbackColor + "; padding: 0.65rem 1rem; border-radius: 8px; margin-bottom: 1rem; font-weight: 600; font-size: 0.85rem;"));

        // Salida formateada tipo terminal JettraStoreShell
        String terminalTextColor = shellResult.success() ? "#7dd3fc" : "#fca5a5";
        RawHtml terminalBox = RawHtml.of(
            "<div style='background: #020617; border: 1px solid " + feedbackBorder + "; border-radius: 8px; padding: 1rem; margin-bottom: 1.25rem; overflow-x: auto;'>"
            + "<div style='font-size: 0.75rem; color: var(--text-muted); margin-bottom: 0.45rem; font-family: monospace;'>"
            + "jettra-shell&gt; <strong style='color:#ffd700;'>" + escapeHtml(shellResult.executedCommand()) + "</strong>"
            + "</div>"
            + "<pre id='shellTerminalOutput' style='margin: 0; font-family: monospace; font-size: 0.84rem; line-height: 1.45; color: " + terminalTextColor + "; white-space: pre-wrap;'>"
            + escapeHtml(shellResult.terminalOutput())
            + "</pre></div>"
        );

        List<Widget> resultChildren = new ArrayList<>();
        resultChildren.add(feedback);
        resultChildren.add(terminalBox);

        // Si existen registros estructurados resultantes, renderizar además la tabla interactiva
        if (shellResult.records() != null && !shellResult.records().isEmpty()) {
            StringBuilder table = new StringBuilder();
            table.append("<table class=\"app-table\">");
            table.append("<thead><tr><th>ID</th><th>Resumen</th><th>Detalles JSON</th><th>Versión</th><th>Fecha</th></tr></thead>");
            table.append("<tbody>");
            for (EngineRecordInfo r : shellResult.records()) {
                String snippet = r.getDetails().replaceAll("\\s+", " ");
                if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";

                table.append("<tr>");
                table.append("<td><code style='color:#00d4ff; font-weight:700;'>").append(escapeHtml(r.getId())).append("</code></td>");
                table.append("<td><strong>").append(escapeHtml(r.getSummary())).append("</strong></td>");
                table.append("<td><code style='color:#38bdf8; font-size:0.8rem;'>").append(escapeHtml(snippet)).append("</code></td>");
                table.append("<td><span style='color:#ffd700; font-weight:700;'>v").append(r.getVersion()).append("</span></td>");
                table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(escapeHtml(r.getTimestamp())).append("</td>");
                table.append("</tr>");
            }
            table.append("</tbody></table>");
            resultChildren.add(RawHtml.of(table.toString()));
        }

        Div resultCard = Div.of(resultChildren.toArray(new Widget[0]));
        resultCard.modifier(new Modifier().cssClass("ui-card"));

        Modal helpModal = buildHelpModal(shell);
        RawHtml shellScripts = buildShellScripts();

        return Div.of(consoleCard, resultCard, helpModal, shellScripts);
    }

    private Modal buildHelpModal(JettraStoreShellExecutor shell) {
        Header title = Header.of(3, "📖 Guía Interactiva de Comandos — JettraStoreShell");
        title.modifier(new Modifier().style("margin: 0 0 0.45rem 0; font-size: 1.2rem; color: #00d4ff;"));

        Paragraph intro = Paragraph.of("Seleccione cualquier categoría para desplegar los comandos disponibles y haga clic en 'Usar Ejemplo' para cargarlos automáticamente en el prompt:");
        intro.modifier(new Modifier().style("margin: 0 0 1rem 0; font-size: 0.85rem; color: var(--text-muted);"));

        List<HelpCategory> cats = shell.getHelpCategories();
        AccordionTab[] tabs = new AccordionTab[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            HelpCategory cat = cats.get(i);
            StringBuilder rows = new StringBuilder();
            rows.append("<div style='display: flex; flex-direction: column; gap: 0.65rem; padding: 0.5rem 0;'>");
            for (HelpCommand hc : cat.commands()) {
                String jsExample = hc.example().replace("\\", "\\\\").replace("'", "\\'");
                rows.append("<div style='background: #020617; border: 1px solid #334155; border-radius: 6px; padding: 0.65rem 0.8rem; display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; flex-wrap: wrap;'>");
                rows.append("  <div style='flex: 1; min-width: 240px;'>");
                rows.append("    <div><code style='color:#ffd700; font-weight:700; font-size:0.85rem;'>").append(escapeHtml(hc.syntax())).append("</code></div>");
                rows.append("    <div style='font-size:0.78rem; color:#cbd5e1; margin-top:2px;'>").append(escapeHtml(hc.description())).append("</div>");
                rows.append("    <div style='font-size:0.76rem; color:#38bdf8; margin-top:4px; font-family:monospace;'>Ej: ").append(escapeHtml(hc.example())).append("</div>");
                rows.append("  </div>");
                rows.append("  <button type='button' onclick=\"applyHelpExample('").append(jsExample).append("')\" style='background: rgba(16,185,129,0.18); border:1px solid #10b981; color:#34d399; font-weight:700; font-size:0.76rem; padding:4px 10px; border-radius:6px; cursor:pointer;'>Usar Ejemplo</button>");
                rows.append("</div>");
            }
            rows.append("</div>");
            tabs[i] = AccordionTab.of(cat.icon() + " " + cat.title(), RawHtml.of(rows.toString())).active(i == 0);
        }

        Accordion accordion = Accordion.of(tabs);
        accordion.modifier(new Modifier().style("max-height: 55vh; overflow-y: auto; margin-bottom: 1rem;"));

        JettraButton closeBtn = JettraButton.of("Cerrar Ayuda")
            .severity(JettraButton.Severity.SECONDARY)
            .type(JettraButton.Type.OUTLINED);
        closeBtn.modifier(new Modifier()
            .attribute("id", "closeHelpModalBtn")
            .cssClass("app-btn-secondary")
            .attribute("onclick", "closeQueryHelpModal()"));

        Row footer = Row.of(closeBtn);
        footer.modifier(new Modifier().style("display: flex; justify-content: flex-end;"));

        Modal modal = Modal.of(title, intro, accordion, footer).open(false);
        modal.modifier(new Modifier()
            .attribute("id", "queryHelpModal")
            .style("background: #0f172a; border: 1px solid #a855f7; max-width: 780px; width: 94%; color: var(--text-main);"));
        return modal;
    }

    private RawHtml buildShellScripts() {
        return RawHtml.of("""
            <script>
            function openQueryHelpModal() {
                var modal = document.getElementById('queryHelpModal');
                if (modal) { modal.style.display = 'flex'; }
            }
            function closeQueryHelpModal() {
                var modal = document.getElementById('queryHelpModal');
                if (modal) { modal.style.display = 'none'; }
            }
            function runShellExample(cmd) {
                var input = document.getElementById('queryCommandInput');
                var form = document.getElementById('queryShellForm');
                if (input && form) {
                    input.value = cmd;
                    form.submit();
                }
            }
            function applyHelpExample(cmd) {
                var input = document.getElementById('queryCommandInput');
                if (input) {
                    input.value = cmd;
                    input.focus();
                }
                closeQueryHelpModal();
            }
            </script>
            """);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}

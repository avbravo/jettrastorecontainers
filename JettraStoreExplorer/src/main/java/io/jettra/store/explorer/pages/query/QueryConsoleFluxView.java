package io.jettra.store.explorer.pages.query;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Accordion;
import io.jettra.flux.widgets.AccordionTab;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Dropdown;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.flux.widgets.TextArea;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.HelpCategory;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.HelpCommand;
import io.jettra.store.explorer.plugin.JettraStoreShellExecutor.ShellExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
        String dbOpts = plugin.getDatabaseNames().stream()
                .map(db -> "<option value='" + escapeHtml(db) + "' " + (db.equalsIgnoreCase(activeDb) ? "selected" : "") + ">" + escapeHtml(db) + "</option>")
                .collect(Collectors.joining());

        String bucketOpts = buckets.stream()
                .map(b -> "<option value='" + escapeHtml(b.getBucketName()) + "' " + (b.getBucketName().equalsIgnoreCase(activeBucket) ? "selected" : "") + ">" + escapeHtml(b.getBucketName()) + " [" + escapeHtml(b.getEngineType()) + "]</option>")
                .collect(Collectors.joining());

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

        Dropdown dbSelect = FluxUi.dropdown("db", dbs, activeDb);
        dbSelect.modifier(new Modifier().cssClass("app-input").attribute("name", "db")
                .attribute("onchange", "this.form.submit()")
                .style("color: #ffd700; font-weight: 600;"));

        List<String> bucketNames = buckets.stream().map(EngineBucketInfo::getBucketName).toList();
        Dropdown bucketSelect = FluxUi.dropdown("bucket", bucketNames, activeBucket);
        bucketSelect.modifier(new Modifier().cssClass("app-input").attribute("name", "bucket")
                .attribute("onchange", "this.form.submit()")
                .style("color: #10b981; font-weight: 600;"));

        Widget selectorsRow = Div.of(
                FluxUi.fieldColumn("Base de Datos Activa:", dbSelect, "flex: 1; min-width: 200px;"),
                FluxUi.fieldColumn("Bucket / Motor Activo:", bucketSelect, "flex: 1; min-width: 200px;")
        ).modifier(new Modifier().style("display: flex; gap: 1rem; margin-bottom: 1rem; flex-wrap: wrap;"));

        TextArea cmdArea = FluxUi.textArea("query", q, 3, false, "font-size: 0.95rem; color: #38bdf8; background: #020617;");
        cmdArea.id("queryCommandInput");
        cmdArea.modifier(new Modifier().cssClass("app-input")
                .attribute("placeholder", "Ej: SHOW DATABASES | SHOW NODES | SELECT * FROM Facturas WHERE total > 500 | MATH sqrt(144)")
                .style("font-family: monospace; font-size: 0.95rem; color: #38bdf8; background: #020617;"));
        Widget cmdGroup = FluxUi.fieldColumn("Prompt JettraStoreShell > (Comandos Shell, JettraSQL, JQL, KV, Graph, Math, Stats):", cmdArea, "margin-bottom: 1rem;");

        Widget quickBtns = Div.of(
                Text.of("Comandos rápidos:"),
                quickCmdBtn("STATUS", "STATUS", "#00d4ff", "rgba(0,212,255,0.1)"),
                quickCmdBtn("SHOW NODES", "SHOW NODES", "#00d4ff", "rgba(0,212,255,0.1)"),
                quickCmdBtn("SHOW DATABASES", "SHOW DATABASES", "#00d4ff", "rgba(0,212,255,0.1)"),
                quickCmdBtn("total > 1000", "total > 1000", "#10b981", "rgba(16,185,129,0.12)"),
                quickCmdBtn("MATH sqrt(144)", "MATH sqrt(144) + 8 * 2", "#e9d5ff", "rgba(168,85,247,0.15)")
        ).modifier(new Modifier().style("font-size: 0.78rem; color: var(--text-muted); display: flex; flex-wrap: wrap; gap: 6px; align-items: center;"));

        Widget actionsBar = Div.of(quickBtns, FluxUi.submitButton("⚡ Ejecutar Comando / Consulta", "app-btn-primary", null))
                .modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem;"));

        Form queryForm = Form.of(selectorsRow, cmdGroup, actionsBar).action("/query").method("POST");
        queryForm.id("queryShellForm");

        Div consoleCard = Div.of(headerRow, queryForm);
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
        Widget promptLine = Div.of(
                Text.of("jettra-shell> "),
                FluxUi.coloredSpan(shellResult.executedCommand(), "color:#ffd700; font-weight:700;")
        ).modifier(new Modifier().style("font-size: 0.75rem; color: var(--text-muted); margin-bottom: 0.45rem; font-family: monospace;"));

        Widget termOut = FluxUi.preBlock(shellResult.terminalOutput(), "font-size: 0.84rem; line-height: 1.45; color: " + terminalTextColor + ";");
        termOut.id("shellTerminalOutput");

        Widget terminalBox = Div.of(promptLine, termOut)
                .modifier(new Modifier().style("background: #020617; border: 1px solid " + feedbackBorder + "; border-radius: 8px; padding: 1rem; margin-bottom: 1.25rem; overflow-x: auto;"));

        List<Widget> resultChildren = new ArrayList<>();
        resultChildren.add(feedback);
        resultChildren.add(terminalBox);

        // Si existen registros estructurados resultantes, renderizar además la tabla interactiva
        if (shellResult.records() != null && !shellResult.records().isEmpty()) {
            List<Widget> tableHeaders = List.of(
                Text.of("ID"),
                Text.of("Resumen"),
                Text.of("Detalles JSON"),
                Text.of("Versión"),
                Text.of("Fecha")
            );
            List<List<Widget>> rows = new ArrayList<>();
            for (EngineRecordInfo r : shellResult.records()) {
                String snippet = r.getDetails().replaceAll("\\s+", " ");
                if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";

                rows.add(List.of(
                    FluxUi.coloredSpan(r.getId(), "color:#00d4ff; font-weight:700; font-family:monospace;"),
                    FluxUi.coloredSpan(r.getSummary(), "font-weight:700;"),
                    FluxUi.coloredSpan(snippet, "color:#38bdf8; font-size:0.8rem; font-family:monospace;"),
                    FluxUi.coloredSpan("v" + r.getVersion(), "color:#ffd700; font-weight:700;"),
                    FluxUi.coloredSpan(r.getTimestamp(), "color:var(--text-muted); font-size:0.8rem;")
                ));
            }
            Datatable recordsTable = Datatable.ofWidgets(tableHeaders, rows);
            recordsTable.modifier(new Modifier().cssClass("app-table"));
            resultChildren.add(recordsTable);
        }

        Div resultCard = Div.of(resultChildren.toArray(new Widget[0]));
        resultCard.modifier(new Modifier().cssClass("ui-card"));

        Modal helpModal = buildHelpModal(shell);
        RawScript shellScripts = buildShellScripts();

        return Div.of(consoleCard, resultCard, helpModal, shellScripts);
    }

    private static Button quickCmdBtn(String label, String cmd, String color, String bg) {
        Button btn = Button.of(label);
        btn.modifier(new Modifier().attribute("type", "button")
                .attribute("onclick", "runShellExample('" + cmd + "')")
                .style("background: " + bg + "; border:1px solid " + color + "; color:" + color + "; border-radius:6px; padding:2px 8px; cursor:pointer; font-family:monospace;"));
        return btn;
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
            List<Widget> cmdItems = new ArrayList<>();
            for (HelpCommand hc : cat.commands()) {
                String jsExample = hc.example().replace("\\", "\\\\").replace("'", "\\'");
                Widget left = Div.of(
                        FluxUi.coloredSpan(hc.syntax(), "color:#ffd700; font-weight:700; font-size:0.85rem; font-family:monospace; display:block;"),
                        FluxUi.coloredSpan(hc.description(), "font-size:0.78rem; color:#cbd5e1; margin-top:2px; display:block;"),
                        FluxUi.coloredSpan("Ej: " + hc.example(), "font-size:0.76rem; color:#38bdf8; margin-top:4px; font-family:monospace; display:block;")
                ).modifier(new Modifier().style("flex: 1; min-width: 240px;"));

                Button useBtn = Button.of("Usar Ejemplo");
                useBtn.modifier(new Modifier().attribute("type", "button")
                        .attribute("onclick", "applyHelpExample('" + jsExample + "')")
                        .style("background: rgba(16,185,129,0.18); border:1px solid #10b981; color:#34d399; font-weight:700; font-size:0.76rem; padding:4px 10px; border-radius:6px; cursor:pointer;"));

                Widget item = Div.of(left, useBtn).modifier(new Modifier().style(
                        "background: #020617; border: 1px solid #334155; border-radius: 6px; padding: 0.65rem 0.8rem; display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; flex-wrap: wrap;"));
                cmdItems.add(item);
            }
            Div tabContent = Div.of(cmdItems.toArray(new Widget[0]));
            tabContent.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.65rem; padding: 0.5rem 0;"));
            tabs[i] = AccordionTab.of(cat.icon() + " " + cat.title(), tabContent).active(i == 0);
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

    private RawScript buildShellScripts() {
        return RawScript.of("""
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

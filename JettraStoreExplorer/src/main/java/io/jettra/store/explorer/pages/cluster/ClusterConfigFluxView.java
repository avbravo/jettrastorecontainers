package io.jettra.store.explorer.pages.cluster;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.flux.widgets.TextArea;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Vista exclusiva para el perfil Administrador en JettraFlux que permite:
 * - Visualizar el archivo cluster.config del nodo PRIMARY
 * - Editarlo y guardarlo con verificación de integridad y sin duplicados
 * - Ejecutar el botón SINCRONIZAR para distribuir cluster.config a todos los nodos del clúster
 */
public class ClusterConfigFluxView extends FluxView {

    private final boolean isAdmin;
    private final String flashMessage;
    private final List<String> syncResults;
    private final String draftContent;

    public ClusterConfigFluxView(boolean isAdmin, String flashMessage, List<String> syncResults, String draftContent) {
        this.isAdmin = isAdmin;
        this.flashMessage = flashMessage;
        this.syncResults = syncResults != null ? syncResults : List.of();
        this.draftContent = draftContent;
    }

    @Override
    public Widget build() {
        List<Widget> root = new ArrayList<>();

        if (!isAdmin) {
            Header deniedTitle = Header.of(3, "⛔ Acceso Restringido — Solo Perfil Administrador");
            deniedTitle.modifier(new Modifier().style("color: #ef4444; margin: 0 0 0.5rem 0; font-size: 1.15rem;"));
            Paragraph deniedMsg = Paragraph.of("La visualización, edición y replicación del archivo cluster-state.config del nodo PRIMARY está reservada exclusivamente para usuarios con rol global ADMIN.");
            deniedMsg.modifier(new Modifier().style("color: var(--text-muted); margin: 0 0 1rem 0;"));
            Link backLink = Link.of("/cluster", "← Volver a Nodos del Clúster");
            backLink.modifier(new Modifier().cssClass("app-btn-secondary"));
            Div deniedCard = Div.of(deniedTitle, deniedMsg, backLink);
            deniedCard.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid #ef4444;"));
            root.add(deniedCard);
            return Div.of(root.toArray(new Widget[0]));
        }

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        JettraStorePlugin.ClusterConfigSnapshot snapshot = plugin.fetchPrimaryClusterConfig();
        String editorContent = (draftContent != null && !draftContent.isBlank()) ? draftContent : snapshot.content();
        JettraConfigValidator.ValidationResult currentValidation = JettraConfigValidator.validateClusterConfigIntegrity(editorContent);

        if (flashMessage != null && !flashMessage.isBlank()) {
            Widget flash = FluxUi.flashBanner(flashMessage);
            if (flash != null) root.add(flash);
        }

        // 1. Cabecera y Metadatos del Origen (Nodo PRIMARY)
        Header title = Header.of(2, "📄 Estado Persistente del Clúster (cluster-state.config — Nodo PRIMARY)");
        title.modifier(new Modifier().style("margin: 0 0 0.35rem 0; font-size: 1.25rem; color: #00d4ff;"));

        Paragraph desc = Paragraph.of("Visualice y edite el archivo persistente cluster-state.config (inicializado opcionalmente desde cluster.config estático). Antes de guardar o distribuir se verifica la integridad completa para evitar IDs de nodos y puertos gRPC/REST duplicados.");
        desc.modifier(new Modifier().style("margin: 0 0 1rem 0; font-size: 0.85rem; color: var(--text-muted);"));

        String sourceLabel = snapshot.fromRemotePrimary()
                ? "🟢 Origen: Nodo PRIMARY [" + snapshot.sourceNodeId() + "] (" + snapshot.sourceAddress() + ")"
                : "📁 Origen: Archivo local (" + snapshot.sourceAddress() + ")";
        Badge sourceBadge = Badge.of(sourceLabel);
        sourceBadge.modifier(new Modifier().style(
                "padding: 0.3rem 0.7rem; border-radius: 9999px; font-size: 0.78rem; font-weight: 700; "
                + (snapshot.fromRemotePrimary()
                        ? "background: rgba(16,185,129,0.18); border: 1px solid #10b981; color: #34d399;"
                        : "background: rgba(245,158,11,0.18); border: 1px solid #f59e0b; color: #fbbf24;")));

        Badge integrityBadge = Badge.of(currentValidation.isValid()
                ? "✅ INTEGRIDAD VÁLIDA (" + snapshot.parsedNodes().size() + " nodos)"
                : "❌ ERROR DE INTEGRIDAD");
        integrityBadge.id("cluster-config-integrity-badge");
        integrityBadge.modifier(new Modifier().style(
                "padding: 0.3rem 0.7rem; border-radius: 9999px; font-size: 0.78rem; font-weight: 700; "
                + (currentValidation.isValid()
                        ? "background: rgba(16,185,129,0.18); border: 1px solid #10b981; color: #34d399;"
                        : "background: rgba(239,68,68,0.2); border: 1px solid #ef4444; color: #f87171;")));

        Link reloadLink = Link.of("/cluster-config?action=reload", "🔄 Recargar desde PRIMARY");
        reloadLink.modifier(new Modifier().cssClass("app-btn-secondary").style("font-size: 0.8rem;"));

        Row metaRow = Row.of(sourceBadge, integrityBadge, reloadLink);
        metaRow.modifier(new Modifier().style("display: flex; align-items: center; gap: 0.65rem; flex-wrap: wrap; margin-bottom: 1rem;"));

        List<Widget> editorCardChildren = new ArrayList<>();
        editorCardChildren.add(title);
        editorCardChildren.add(desc);
        editorCardChildren.add(metaRow);

        if (!currentValidation.isValid()) {
            List<Widget> errItems = new ArrayList<>();
            errItems.add(FluxUi.coloredSpan("⚠️ Problemas de integridad detectados en cluster-state.config:", "font-weight: 700; color: #ef4444; display: block; margin-bottom: 0.35rem;"));
            for (String err : currentValidation.getErrors()) {
                errItems.add(Div.of(Text.of("• " + err)).modifier(new Modifier().style("font-size: 0.82rem; color: #fca5a5;")));
            }
            Div errBox = Div.of(errItems.toArray(new Widget[0]));
            errBox.modifier(new Modifier().style("background: rgba(239,68,68,0.12); border: 1px solid #ef4444; border-radius: 8px; padding: 0.85rem 1rem; margin-bottom: 1rem;"));
            editorCardChildren.add(errBox);
        }

        // 2. Formulario de Edición y Botón SINCRONIZAR (JettraFlux Form nativo)
        TextArea configEditor = FluxUi.textArea(
                "clusterConfigContent",
                editorContent,
                18,
                true,
                "width: 100%; background: #020617; color: #e2e8f0; border: 1px solid #334155; padding: 0.85rem; line-height: 1.45;"
        );
        configEditor.id("clusterConfigEditor");

        Button saveBtn = Button.of("💾 Guardar y Replicar cluster-state.config");
        saveBtn.id("btnSaveClusterConfig");
        saveBtn.modifier(new Modifier()
                .cssClass("app-btn-primary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "save_config")
                .style("height: 40px; font-weight: 700;"));

        Button syncBtn = Button.of("🔄 SINCRONIZAR TODOS LOS NODOS");
        syncBtn.id("btnSyncClusterConfig");
        syncBtn.modifier(new Modifier()
                .cssClass("app-btn-primary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "sync_cluster_config")
                .style("height: 40px; font-weight: 800; background: #10b981; color: #050b14; border: none; letter-spacing: 0.03em;"));

        Row actionButtons = Row.of(saveBtn, syncBtn);
        actionButtons.modifier(new Modifier().style("display: flex; gap: 0.75rem; flex-wrap: wrap; margin-top: 0.9rem;"));

        Form editorForm = Form.of(
                FluxUi.fieldLabel("Contenido del archivo cluster-state.config del nodo PRIMARY (Editable solo por ADMIN)"),
                configEditor,
                actionButtons);
        editorForm.id("clusterConfigForm");
        editorForm.modifier(new Modifier().attribute("method", "POST").attribute("action", "/cluster-config"));
        editorCardChildren.add(editorForm);

        Div editorCard = Div.of(editorCardChildren.toArray(new Widget[0]));
        editorCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem;"));
        root.add(editorCard);

        // 3. Reporte de Sincronización si se ejecutó SINCRONIZAR
        if (!syncResults.isEmpty()) {
            Header syncTitle = Header.of(3, "📡 Resultado de Sincronización de Archivos de Configuración del Clúster");
            syncTitle.modifier(new Modifier().style("margin: 0 0 0.75rem 0; font-size: 1.05rem; color: #10b981;"));
            List<Widget> syncLines = new ArrayList<>();
            syncLines.add(syncTitle);
            for (String line : syncResults) {
                Div rowItem = Div.of(Text.of(line));
                rowItem.modifier(new Modifier().style("font-family: monospace; font-size: 0.84rem; padding: 0.4rem 0.65rem; background: var(--bg-subtle); border-radius: 6px; margin-bottom: 0.35rem; color: var(--text-main);"));
                syncLines.add(rowItem);
            }
            Div syncCard = Div.of(syncLines.toArray(new Widget[0]));
            syncCard.id("cluster-sync-report-card");
            syncCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border-left: 4px solid #10b981;"));
            root.add(syncCard);
        }

        // 4. Tabla Resumen de Nodos Parseados desde cluster-state.config (Datatable JettraFlux)
        Header tableHeader = Header.of(3, "📋 Nodos Detectados en cluster-state.config");
        tableHeader.modifier(new Modifier().style("margin: 0 0 0.85rem 0; font-size: 1.05rem; color: var(--text-main);"));

        List<Widget> headers = List.of(
                Text.of("#"),
                Text.of("ID de Nodo"),
                Text.of("Rol"),
                Text.of("Dirección IP / Host"),
                Text.of("Puerto gRPC"),
                Text.of("Puerto REST")
        );
        List<List<Widget>> rows = new ArrayList<>();
        for (JettraConfigValidator.ClusterNodeInfo n : snapshot.parsedNodes()) {
            boolean isPrim = "PRIMARY".equalsIgnoreCase(n.role());
            Badge roleBadge = Badge.of(n.role());
            roleBadge.modifier(new Modifier().style(
                    "padding: 0.2rem 0.55rem; border-radius: 9999px; font-size: 0.74rem; font-weight: 700; "
                    + (isPrim
                            ? "background: rgba(255,215,0,0.16); border: 1px solid #ffd700; color: #ffd700;"
                            : "background: rgba(0,212,255,0.14); border: 1px solid #00d4ff; color: #00d4ff;")));
            rows.add(List.of(
                    Text.of(n.nodeNumber()),
                    FluxUi.coloredSpan(n.id(), "font-weight: 700; color: #00d4ff;"),
                    roleBadge,
                    Text.of(n.ip()),
                    FluxUi.coloredSpan(String.valueOf(n.grpcPort()), "font-family: monospace; font-weight: 700;"),
                    FluxUi.coloredSpan(String.valueOf(n.restPort()), "font-family: monospace; font-weight: 700;")
            ));
        }

        Datatable nodesTable = Datatable.ofWidgets(headers, rows);
        nodesTable.modifier(new Modifier().cssClass("app-table"));

        Div tableCard = Div.of(tableHeader, nodesTable);
        tableCard.modifier(new Modifier().cssClass("ui-card"));
        root.add(tableCard);

        return Div.of(root.toArray(new Widget[0]));
    }
}

package io.jettra.store.explorer.pages.explorer;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Dropdown;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Span;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineRecordInfo;
import io.jettra.store.explorer.model.EngineRecordVersion;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Explorador de Registros con soporte completo de operaciones CRUD en JettraFlux.
 */
public class RecordExplorerFluxView extends FluxView {

    public static final int DEFAULT_PAGE_SIZE = 10;

    private final String activeDb;
    private final String activeBucket;
    private final String activeSection; // records, schema, indexes
    private final String searchQuery;
    private final String editRecordId;
    private final String viewRecordId;
    private final String historyRecordId;
    private final boolean showCreateForm;
    private final boolean showCreateBucketForm;
    private final boolean showEditSchemaForm;
    private final boolean showViewSchemaModal;
    private final String message;
    private final int requestedPage;
    private final int pageSize;

    public RecordExplorerFluxView(String activeDb, String activeBucket, String activeSection, String searchQuery,
                                  String editRecordId, String viewRecordId, String historyRecordId,
                                  boolean showCreateForm, boolean showCreateBucketForm,
                                  boolean showEditSchemaForm, boolean showViewSchemaModal,
                                  String message, int page, int pageSize) {
        this.activeDb = activeDb;
        this.activeBucket = activeBucket;
        this.activeSection = (activeSection != null && !activeSection.isBlank()) ? activeSection.trim().toLowerCase() : "records";
        this.searchQuery = searchQuery != null ? searchQuery : "";
        this.editRecordId = editRecordId;
        this.viewRecordId = viewRecordId;
        this.historyRecordId = historyRecordId;
        this.showCreateForm = showCreateForm;
        this.showCreateBucketForm = showCreateBucketForm;
        this.showEditSchemaForm = showEditSchemaForm;
        this.showViewSchemaModal = showViewSchemaModal;
        this.message = message;
        this.requestedPage = Math.max(1, page);
        this.pageSize = pageSize > 0 ? Math.min(100, pageSize) : DEFAULT_PAGE_SIZE;
    }

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, String historyRecordId,
                                  boolean showCreateForm, boolean showCreateBucketForm, String message, int page, int pageSize) {
        this(activeDb, activeBucket, "records", searchQuery, editRecordId, viewRecordId, historyRecordId,
             showCreateForm, showCreateBucketForm, false, false, message, page, pageSize);
    }

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, String historyRecordId,
                                  boolean showCreateForm, String message, int page, int pageSize) {
        this(activeDb, activeBucket, searchQuery, editRecordId, viewRecordId, historyRecordId,
             showCreateForm, false, message, page, pageSize);
    }

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, String historyRecordId,
                                  boolean showCreateForm, String message) {
        this(activeDb, activeBucket, searchQuery, editRecordId, viewRecordId, historyRecordId,
             showCreateForm, message, 1, DEFAULT_PAGE_SIZE);
    }

    public RecordExplorerFluxView(String activeDb, String activeBucket, String searchQuery,
                                  String editRecordId, String viewRecordId, boolean showCreateForm, String message) {
        this(activeDb, activeBucket, searchQuery, editRecordId, viewRecordId, null,
             showCreateForm, message, 1, DEFAULT_PAGE_SIZE);
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        List<String> dbs = plugin.getDatabaseNames();
        String currentDb = (activeDb != null && plugin.getDatabasesCatalog().containsKey(activeDb))
                ? activeDb
                : (dbs.isEmpty() ? "example_factura_db" : dbs.get(0));

        List<EngineBucketInfo> buckets = plugin.getBuckets(currentDb);
        String rawBucket = activeBucket;
        String currentBucket = (rawBucket != null && buckets.stream().anyMatch(b -> b.getBucketName().equalsIgnoreCase(rawBucket)))
                ? rawBucket
                : (buckets.isEmpty() ? "Facturas" : buckets.get(0).getBucketName());

        EngineBucketInfo bucketInfo = plugin.getBucket(currentDb, currentBucket);
        JettraStorePlugin.PagedRecordsResult pageResult = plugin.streamRecordsPage(currentDb, currentBucket, searchQuery, requestedPage, pageSize);
        List<EngineRecordInfo> pageRecords = pageResult.records();
        long totalRecords = pageResult.totalRecords();
        int totalPages = pageResult.totalPages();
        int currentPage = pageResult.page();
        boolean isStreamed = pageResult.streamed();

        // 1. Notificación Flash si existe
        Widget flashWidget = null;
        if (message != null && !message.isBlank()) {
            Widget flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

        // 2. Navegación jerárquica en Árbol (Base de Datos -> Tipo de Engine -> Bucket [NONE|DEFINED] -> Registros)
        Widget treeNav = buildHierarchyTree(plugin, dbs, currentDb, currentBucket);
        Widget dbSelector = buildDbSelector(dbs, currentDb);
        Widget bucketSelector = buildBucketSelector(buckets, currentDb, currentBucket);

        // 3. Barra de Búsqueda y Botones "+ Nuevo Bucket" / "+ Nuevo Registro"
        Widget searchAndActions = buildSearchToolbar(currentDb, currentBucket, searchQuery, totalRecords, currentPage, totalPages, pageSize, isStreamed);

        // 4. Panel Formulario Crear Bucket / CRUD Estructura / Crear / Editar Registro si está activo
        Widget formPanel = null;
        if (showCreateBucketForm) {
            formPanel = buildCreateBucketForm(currentDb, currentBucket);
        } else if (showEditSchemaForm) {
            formPanel = buildEditSchemaModal(currentDb, bucketInfo);
        } else if (showViewSchemaModal) {
            formPanel = buildViewSchemaModal(currentDb, bucketInfo);
        } else if (showCreateForm) {
            formPanel = buildCreateRecordForm(currentDb, currentBucket, bucketInfo);
        } else if (editRecordId != null && !editRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, editRecordId);
            if (rec != null) {
                formPanel = buildEditRecordForm(currentDb, currentBucket, bucketInfo, rec);
            }
        } else if (viewRecordId != null && !viewRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, viewRecordId);
            if (rec != null) {
                formPanel = buildViewRecordModal(currentDb, currentBucket, rec);
            }
        } else if (historyRecordId != null && !historyRecordId.isBlank()) {
            EngineRecordInfo rec = plugin.getRecord(currentDb, currentBucket, historyRecordId);
            if (rec != null) {
                formPanel = buildVersionHistoryPanel(currentDb, currentBucket, rec);
            }
        }

        // 5. Secciones del Bucket: Estructura (CRUD), Índices (NUMERIC/AUTO_INCREMENT) y Registros
        Widget recordsTable = buildRecordsTable(pageRecords, totalRecords, currentDb, currentBucket, searchQuery, currentPage, totalPages, pageSize, isStreamed);
        Widget schemaPanel = buildBucketSchemaPanel(currentDb, bucketInfo);
        Widget indexesPanel = buildBucketIndexesPanel(currentDb, bucketInfo);

        List<Widget> mainColChildren = new java.util.ArrayList<>();
        if (flashWidget != null) mainColChildren.add(flashWidget);
        mainColChildren.add(dbSelector);
        mainColChildren.add(bucketSelector);
        if (formPanel != null) {
            Modal dialog = Modal.of(formPanel).open(true);
            dialog.id("explorerActionDialog");
            mainColChildren.add(dialog);
        }
        if ("schema".equals(activeSection)) {
            mainColChildren.add(schemaPanel);
            mainColChildren.add(indexesPanel);
            mainColChildren.add(searchAndActions);
            mainColChildren.add(recordsTable);
        } else if ("indexes".equals(activeSection)) {
            mainColChildren.add(indexesPanel);
            mainColChildren.add(schemaPanel);
            mainColChildren.add(searchAndActions);
            mainColChildren.add(recordsTable);
        } else {
            mainColChildren.add(searchAndActions);
            mainColChildren.add(recordsTable);
            mainColChildren.add(schemaPanel);
            mainColChildren.add(indexesPanel);
        }

        Div mainCol = Div.of(mainColChildren.toArray(new Widget[0]));
        mainCol.modifier(new Modifier().cssClass("explorer-main-col").style("flex: 1; min-width: 0; overflow-x: auto;"));

        Widget responsiveCss = FluxUi.styleSheet("""
                .explorer-layout { display: flex; gap: 1.25rem; align-items: flex-start; flex-wrap: nowrap; width: 100%; box-sizing: border-box; }
                .explorer-tree-sidebar { width: 270px; flex-shrink: 0; position: sticky; top: 1rem; max-height: calc(100vh - 130px); overflow-y: auto; box-sizing: border-box; }
                .explorer-form-card { width: min(92vw, 720px); max-width: 100%; box-sizing: border-box; padding: 1.25rem; }
                .explorer-form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 0.9rem; margin-bottom: 1rem; }
                @media (max-width: 920px) {
                    .explorer-layout { flex-direction: column; }
                    .explorer-tree-sidebar { width: 100% !important; position: static !important; max-height: 260px !important; }
                }
                """);

        Div layout = Div.of(responsiveCss, treeNav, mainCol);
        layout.modifier(new Modifier().cssClass("explorer-layout"));
        return layout;
    }

    private Widget buildHierarchyTree(JettraStorePlugin plugin, List<String> dbs, String currentDb, String currentBucket) {
        List<Widget> treeNodes = new ArrayList<>();
        Header treeTitle = Header.of(4, "🌳 Explorador Jerárquico");
        treeTitle.modifier(new Modifier().style("margin: 0 0 0.75rem 0; color: #00d4ff; font-size: 0.95rem;"));
        treeNodes.add(treeTitle);

        Link newBucketBtn = Link.of("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&action=new_bucket", "+ Nuevo Bucket");
        newBucketBtn.modifier(new Modifier()
                .cssClass("app-btn-primary")
                .style("display: block; text-align: center; margin-bottom: 0.85rem; padding: 0.45rem 0.75rem; font-size: 0.82rem; text-decoration: none;"));
        treeNodes.add(newBucketBtn);

        for (String db : dbs) {
            boolean isCurrentDb = db.equalsIgnoreCase(currentDb);
            Link dbLink = Link.of("/explorer?db=" + db, (isCurrentDb ? "📂 " : "📁 ") + db);
            dbLink.modifier(new Modifier().style(
                    "display: block; padding: 0.35rem 0.5rem; border-radius: 6px; text-decoration: none; font-weight: 700; font-size: 0.84rem; "
                    + (isCurrentDb ? "background: rgba(0, 212, 255, 0.14); color: #00d4ff;" : "color: var(--text-main);")));
            treeNodes.add(dbLink);

            if (isCurrentDb) {
                Map<String, List<EngineBucketInfo>> byEngine = new java.util.LinkedHashMap<>();
                for (EngineBucketInfo b : plugin.getBuckets(db)) {
                    byEngine.computeIfAbsent(b.getEngineType(), k -> new ArrayList<>()).add(b);
                }
                for (var entry : byEngine.entrySet()) {
                    Div engHeader = Div.of(FluxUi.coloredSpan("⚙️ " + entry.getKey(), "font-size: 0.75rem; font-weight: 700; color: #ffd700;"));
                    engHeader.modifier(new Modifier().style("padding: 0.35rem 0 0.15rem 0.95rem;"));
                    treeNodes.add(engHeader);

                    for (EngineBucketInfo b : entry.getValue()) {
                        boolean activeB = b.getBucketName().equalsIgnoreCase(currentBucket);
                        String stTag = "DEFINED".equalsIgnoreCase(b.getStructureType()) ? "DEFINED" : "NONE";
                        Link bLink = Link.of("/explorer?db=" + db + "&bucket=" + b.getBucketName() + "&section=records",
                                (activeB ? "▾ 📦 " : "▸ 📦 ") + b.getBucketName() + " [" + stTag + "]");
                        bLink.modifier(new Modifier().style(
                                "display: block; padding: 0.3rem 0.5rem 0.3rem 1.35rem; border-radius: 5px; text-decoration: none; font-size: 0.8rem; "
                                + (activeB ? "background: rgba(16, 185, 129, 0.18); color: #10b981; font-weight: 700;" : "color: var(--text-main);")));
                        treeNodes.add(bLink);

                        // Subárbol explícito por Bucket: Estructura, Índices y Registros
                        if (activeB) {
                            String subBase = "display: block; padding: 0.22rem 0.45rem 0.22rem 0.75rem; border-radius: 5px; text-decoration: none; font-size: 0.76rem; margin-bottom: 2px; ";
                            Link schemaNode = Link.of("/explorer?db=" + db + "&bucket=" + b.getBucketName() + "&section=schema",
                                    "📐 Estructura (" + ("DEFINED".equalsIgnoreCase(stTag) ? b.getAttributes().size() + " campos" : "NONE") + ")");
                            schemaNode.modifier(new Modifier().style(subBase
                                    + ("schema".equals(activeSection) ? "background: rgba(0, 212, 255, 0.16); color: #00d4ff; font-weight: 700;" : "color: var(--text-muted);")));

                            Link indexesNode = Link.of("/explorer?db=" + db + "&bucket=" + b.getBucketName() + "&section=indexes",
                                    "⚡ Índices (" + b.getIndexes().size() + ")");
                            indexesNode.modifier(new Modifier().style(subBase
                                    + ("indexes".equals(activeSection) ? "background: rgba(168, 85, 247, 0.18); color: #c084fc; font-weight: 700;" : "color: var(--text-muted);")));

                            Link recordsNode = Link.of("/explorer?db=" + db + "&bucket=" + b.getBucketName() + "&section=records",
                                    "📄 Registros (" + b.getTotalObjects() + ")");
                            recordsNode.modifier(new Modifier().style(subBase
                                    + ("records".equals(activeSection) ? "background: rgba(16, 185, 129, 0.16); color: #34d399; font-weight: 700;" : "color: var(--text-muted);")));

                            Div subTree = Div.of(schemaNode, indexesNode, recordsNode);
                            subTree.modifier(new Modifier().style(
                                    "margin-left: 1.7rem; padding-left: 0.45rem; border-left: 1px dashed rgba(148, 163, 184, 0.4); margin-top: 2px; margin-bottom: 4px;"));
                            treeNodes.add(subTree);
                        }
                    }
                }
            }
        }

        Div sidebar = Div.of(treeNodes.toArray(new Widget[0]));
        sidebar.modifier(new Modifier()
                .cssClass("ui-card explorer-tree-sidebar")
                .style("padding: 1rem; border-left: 3px solid #00d4ff;"));
        return sidebar;
    }

    private Widget buildCreateBucketForm(String currentDb, String currentBucket) {
        Header title = Header.of(3, "📦 Crear Nuevo Bucket en '" + currentDb + "'");
        title.modifier(new Modifier().style("margin: 0 0 0.75rem 0; color: #00d4ff;"));

        Paragraph hint = Paragraph.of("Configure buckets ilimitados por cada engine. Elija estructura NONE (esquema libre) o DEFINED (validación estricta con atributos y jettra_rules).");
        hint.modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin-bottom: 1rem;"));

        String sampleDefinedJson = "[\n"
                + "  {\"atribute_name\": \"name\", \"type_atribute\": \"String\", \"jettra_rules\": \"NOT_NULL\", \"description\": \"nombre\"},\n"
                + "  {\"atribute_name\": \"siglas\", \"type_atribute\": \"String\", \"jettra_rules\": \"NOT_NULL\", \"description\": \"siglas\"}\n"
                + "]";

        Form form = Form.of(
                FluxUi.hidden("action", "create_bucket"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("bucket", currentBucket),
                FluxUi.fieldColumn("Nombre del Bucket", FluxUi.textInput("newBucketName", "", "Ej: Persona, Pais, Facturas", true), "margin-bottom: 0.75rem;"),
                FluxUi.fieldColumn("Tipo de Engine", FluxUi.dropdown("engineType",
                        List.of("DOCUMENT", "JAVA_RECORD", "KEY_VALUE", "VECTOR", "GRAPH", "TIMESERIES", "COLUMNAR", "GEOSPATIAL", "RELATIONAL"),
                        "DOCUMENT"), "margin-bottom: 0.75rem;"),
                FluxUi.fieldColumn("Tipo de Estructura", FluxUi.dropdown("structureType", List.of("NONE", "DEFINED"), "NONE"), "margin-bottom: 0.75rem;"),
                FluxUi.fieldColumn("Descripción", FluxUi.textInput("description", "", "Descripción opcional del bucket", false), "margin-bottom: 0.75rem;"),
                FluxUi.fieldColumn("Atributos JSON (solo si Estructura = DEFINED)", FluxUi.textArea("attributesJson", sampleDefinedJson, 6, false, ""), "margin-bottom: 0.75rem;"),
                Row.of(
                        FluxUi.submitButton("Crear Bucket", "app-btn-primary", ""),
                        FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket, "Cancelar", "app-btn-secondary", "")
                ).modifier(new Modifier().style("display: flex; gap: 0.75rem; justify-content: flex-end; margin-top: 1rem;"))
        ).action("/explorer").method("POST");

        Div card = Div.of(title, hint, form);
        card.modifier(new Modifier().style("padding: 1.25rem; min-width: 520px; max-width: 680px;"));
        return card;
    }

    private Widget buildEditSchemaModal(String currentDb, EngineBucketInfo bucketInfo) {
        String bucketName = bucketInfo != null ? bucketInfo.getBucketName() : activeBucket;
        boolean hasSchema = bucketInfo != null && "DEFINED".equalsIgnoreCase(bucketInfo.getStructureType()) && !bucketInfo.getAttributes().isEmpty();
        String existingJson = hasSchema
                ? io.jettra.store.core.JettraBucketSchema.toAttributesJson(bucketInfo.getAttributes())
                : "[\n"
                + "  {\"atribute_name\": \"codigo\", \"type_atribute\": \"Long\", \"jettra_rules\": \"AUTO_INCREMENT\", \"description\": \"Código secuencial\"},\n"
                + "  {\"atribute_name\": \"name\", \"type_atribute\": \"String\", \"jettra_rules\": \"NOT_NULL\", \"description\": \"Nombre\"},\n"
                + "  {\"atribute_name\": \"precio\", \"type_atribute\": \"Double\", \"jettra_rules\": \"POSITIVE\", \"description\": \"Valor numérico\"}\n"
                + "]";

        Header title = Header.of(3, (hasSchema ? "✏️ Editar Estructura DEFINED" : "➕ Crear Estructura DEFINED") + " • " + currentDb + "." + bucketName);
        title.modifier(new Modifier().style("margin: 0 0 0.75rem 0; color: #00d4ff;"));

        Form form = Form.of(
                FluxUi.hidden("action", "save_schema"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("bucket", bucketName),
                FluxUi.fieldColumn("Definición JSON de Atributos (atribute_name, type_atribute, jettra_rules, description)",
                        FluxUi.textArea("attributesJson", existingJson, 9, true, ""), "margin-bottom: 1rem;"),
                Row.of(
                        FluxUi.submitButton("💾 Guardar Estructura", "app-btn-primary", ""),
                        FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bucketName + "&section=schema", "Cancelar", "app-btn-secondary", "")
                ).modifier(new Modifier().style("display: flex; gap: 0.75rem; justify-content: flex-end;"))
        ).action("/explorer").method("POST");

        Div card = Div.of(title, form);
        card.modifier(new Modifier().cssClass("ui-card explorer-form-card").style("border-left: 4px solid #00d4ff;"));
        return card;
    }

    private Widget buildViewSchemaModal(String currentDb, EngineBucketInfo bucketInfo) {
        String bucketName = bucketInfo != null ? bucketInfo.getBucketName() : activeBucket;
        String json = (bucketInfo != null && !bucketInfo.getAttributes().isEmpty())
                ? io.jettra.store.core.JettraBucketSchema.toAttributesJson(bucketInfo.getAttributes())
                : "[]";
        Header title = Header.of(3, "👁️ Definición JSON de Estructura • " + currentDb + "." + bucketName);
        title.modifier(new Modifier().style("margin: 0 0 0.75rem 0; color: #00d4ff;"));
        Div card = Div.of(
                title,
                FluxUi.preBlock(json, "background: rgba(0,0,0,0.25); padding: 1rem; border-radius: 8px; margin-bottom: 1rem;"),
                Row.of(
                        FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bucketName + "&section=schema&action=edit_schema", "✏️ Editar Estructura", "app-btn-primary", ""),
                        FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bucketName + "&section=schema", "Cerrar", "app-btn-secondary", "")
                ).modifier(new Modifier().style("display: flex; gap: 0.75rem; justify-content: flex-end;"))
        );
        card.modifier(new Modifier().cssClass("ui-card explorer-form-card").style("border-left: 4px solid #10b981;"));
        return card;
    }

    private Widget buildBucketSchemaPanel(String currentDb, EngineBucketInfo bucketInfo) {
        if (bucketInfo == null) return Div.of();
        List<Widget> children = new ArrayList<>();
        boolean isDefined = "DEFINED".equalsIgnoreCase(bucketInfo.getStructureType()) && !bucketInfo.getAttributes().isEmpty();
        String bName = bucketInfo.getBucketName();

        Header h = Header.of(4, "📐 Estructura del Bucket '" + bName + "' [" + bucketInfo.getEngineType() + " • " + (isDefined ? "DEFINED" : "NONE") + "]");
        h.modifier(new Modifier().style("margin: 0; color: #00d4ff;"));

        List<Widget> crudBtns = new ArrayList<>();
        if (!isDefined) {
            crudBtns.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bName + "&section=schema&action=new_schema",
                    "➕ Crear Estructura (DEFINED)", "app-btn-primary", "padding: 0.35rem 0.75rem; font-size: 0.8rem;"));
        } else {
            crudBtns.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bName + "&section=schema&action=view_schema",
                    "👁️ Ver JSON", "app-btn-secondary", "padding: 0.35rem 0.7rem; font-size: 0.8rem;"));
            crudBtns.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bName + "&section=schema&action=edit_schema",
                    "✏️ Editar Estructura", "app-btn-primary", "padding: 0.35rem 0.7rem; font-size: 0.8rem;"));
            Link delSchema = FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + bName + "&section=schema&action=delete_schema",
                    "🗑️ Eliminar Estructura", "app-btn-danger", "padding: 0.35rem 0.7rem; font-size: 0.8rem;");
            delSchema.modifier(new Modifier().cssClass("app-btn-danger").style("padding: 0.35rem 0.7rem; font-size: 0.8rem;")
                    .attribute("onclick", "return confirm('¿Eliminar la estructura DEFINED de " + escapeHtml(bName) + " y volver a modo NONE?');"));
            crudBtns.add(delSchema);
        }

        Div headerRow = Div.of(h, Row.of(crudBtns.toArray(new Widget[0])).modifier(new Modifier().style("display: flex; gap: 0.5rem; flex-wrap: wrap;")));
        headerRow.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.85rem;"));
        children.add(headerRow);

        if (isDefined) {
            List<List<Widget>> attrRows = new ArrayList<>();
            for (var a : bucketInfo.getAttributes()) {
                attrRows.add(List.of(
                        Text.of(a.atribute_name()),
                        FluxUi.pillBadge(a.type_atribute(), "#00d4ff"),
                        FluxUi.pillBadge(a.jettra_rules().isBlank() ? "OPTIONAL" : a.jettra_rules(), "#ffd700"),
                        Text.of(a.description())
                ));
            }
            children.add(Datatable.ofWidgets(List.of(Text.of("Atributo"), Text.of("Tipo"), Text.of("Jettra Rules"), Text.of("Descripción")), attrRows));
        } else {
            Paragraph freeP = Paragraph.of("Estructura NONE (esquema flexible): admite registros con atributos libres sin restricción rígida. Use '➕ Crear Estructura (DEFINED)' para definir atributos y validaciones.");
            freeP.modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin: 0;"));
            children.add(freeP);
        }

        Div panel = Div.of(children.toArray(new Widget[0]));
        panel.modifier(new Modifier().cssClass("ui-card").style("margin-top: 1.25rem; padding: 1rem; border-top: 3px solid #00d4ff;"));
        return panel;
    }

    private Widget buildBucketIndexesPanel(String currentDb, EngineBucketInfo bucketInfo) {
        if (bucketInfo == null) return Div.of();
        List<Widget> children = new ArrayList<>();
        String bName = bucketInfo.getBucketName();

        Header h = Header.of(4, "⚡ Índices del Bucket '" + bName + "' (Soporte Estándar, NUMERIC y AUTO_INCREMENT)");
        h.modifier(new Modifier().style("margin: 0 0 0.75rem 0; color: #c084fc;"));
        children.add(h);

        List<List<Widget>> idxRows = new ArrayList<>();
        for (var idx : bucketInfo.getIndexes()) {
            Link dropIdx = Link.of("/explorer?db=" + currentDb + "&bucket=" + bName
                    + "&action=delete_bucket_index&indexName=" + idx.getName(), "Eliminar");
            dropIdx.modifier(new Modifier().cssClass("app-btn-danger").style("padding: 0.25rem 0.6rem; font-size: 0.75rem;"));
            String typeColor = "AUTO_INCREMENT".equalsIgnoreCase(idx.getType()) ? "#f59e0b"
                    : ("NUMERIC".equalsIgnoreCase(idx.getType()) ? "#38bdf8" : "#00d4ff");
            idxRows.add(List.of(
                    Text.of(idx.getName()),
                    Text.of(idx.getFields()),
                    FluxUi.pillBadge(idx.getType(), typeColor),
                    FluxUi.pillBadge(idx.isUnique() ? "UNIQUE" : "NON-UNIQUE", idx.isUnique() ? "#ffd700" : "#10b981"),
                    FluxUi.pillBadge(idx.getStatus(), "#10b981"),
                    dropIdx
            ));
        }
        if (!idxRows.isEmpty()) {
            children.add(Datatable.ofWidgets(List.of(Text.of("Índice"), Text.of("Campo(s)"), Text.of("Tipo"), Text.of("Unicidad"), Text.of("Estado"), Text.of("Acciones")), idxRows));
        }

        // Detectar campos del esquema (resaltando numéricos y AUTO_INCREMENT)
        List<String> schemaFieldOptions = new ArrayList<>();
        for (var a : bucketInfo.getAttributes()) {
            if (!a.atribute_name().isBlank()) {
                schemaFieldOptions.add(a.atribute_name());
            }
        }
        Widget fieldInput = schemaFieldOptions.isEmpty()
                ? FluxUi.textInput("indexField", "", "Campo(s) (ej: codigo, precio)", true)
                : Row.of(
                        FluxUi.dropdown("indexFieldSelect", schemaFieldOptions, schemaFieldOptions.get(0)),
                        FluxUi.textInput("indexField", "", "o campo personalizado", false)
                  ).modifier(new Modifier().style("display: flex; gap: 0.4rem; align-items: center;"));

        Form idxForm = Form.of(
                FluxUi.hidden("action", "create_bucket_index"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("bucket", bName),
                Row.of(
                        FluxUi.textInput("indexName", "", "Nombre índice (ej: idx_precio)", true),
                        fieldInput,
                        FluxUi.dropdown("indexType", List.of("BTREE", "HASH", "NUMERIC", "AUTO_INCREMENT", "SPARSE", "VECTOR_HNSW", "SPATIAL_R_TREE"), "NUMERIC"),
                        FluxUi.dropdown("indexUnique", List.of("false", "true"), "false"),
                        FluxUi.submitButton("+ Crear Índice", "app-btn-primary", "")
                ).modifier(new Modifier().style("display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; margin-top: 0.85rem;"))
        ).action("/explorer").method("POST");
        children.add(idxForm);

        Div panel = Div.of(children.toArray(new Widget[0]));
        panel.modifier(new Modifier().cssClass("ui-card").style("margin-top: 1.25rem; padding: 1rem; border-top: 3px solid #a855f7;"));
        return panel;
    }

    private Widget buildDbSelector(List<String> dbs, String currentDb) {
        List<Widget> links = new ArrayList<>();
        for (String db : dbs) {
            boolean active = db.equalsIgnoreCase(currentDb);
            String style = active
                ? "text-decoration: none; padding: 0.45rem 1rem; border-radius: 8px; font-size: 0.85rem; display: inline-flex; align-items: center; gap: 0.35rem; background: #00d4ff; color: #050b14; font-weight: 700; border: 1px solid #00d4ff;"
                : "text-decoration: none; padding: 0.45rem 1rem; border-radius: 8px; font-size: 0.85rem; display: inline-flex; align-items: center; gap: 0.35rem; background: var(--bg-card); color: var(--text-muted); border: 1px solid var(--border-subtle);";
            Link link = Link.of("/explorer?db=" + db, "🗄️ " + db);
            link.modifier(new Modifier().style(style));
            links.add(link);
        }
        Widget container = Div.of(links.toArray(new Widget[0]));
        container.modifier(new Modifier().style("display: flex; gap: 0.5rem; margin-bottom: 1rem; overflow-x: auto; padding-bottom: 4px;"));
        return container;
    }

    private Widget buildBucketSelector(List<EngineBucketInfo> buckets, String currentDb, String currentBucket) {
        List<Widget> bucketWidgets = new ArrayList<>();
        for (EngineBucketInfo b : buckets) {
            boolean active = b.getBucketName().equalsIgnoreCase(currentBucket);
            String engineColor = switch (b.getEngineType()) {
                case "DOCUMENT" -> "#00d4ff";
                case "RELATIONAL" -> "#ffd700";
                case "GRAPH" -> "#ec4899";
                case "VECTOR" -> "#a855f7";
                case "TIMESERIES" -> "#22c55e";
                case "KEY_VALUE" -> "#f97316";
                case "COLUMNAR" -> "#38bdf8";
                case "GEOSPATIAL" -> "#06b6d4";
                case "JAVA_RECORD" -> "#3b82f6";
                case "OBJECT" -> "#eab308";
                default -> "#94a3b8";
            };

            String border = active ? "border: 2px solid " + engineColor + ";" : "border: 1px solid var(--border-subtle);";
            String bg = active ? "background: rgba(0, 212, 255, 0.12);" : "background: var(--bg-subtle);";

            Link link = Link.of("/explorer?db=" + currentDb + "&bucket=" + b.getBucketName(),
                    FluxUi.coloredSpan(b.getEngineType(), "background: " + engineColor + "; color: #050b14; font-size: 0.7rem; font-weight: 800; padding: 0.15rem 0.45rem; border-radius: 9999px;"),
                    FluxUi.coloredSpan(b.getBucketName(), "color: var(--text-main); font-size: 0.9rem; font-weight: 700;"),
                    FluxUi.coloredSpan("(" + String.format("%,d", b.getTotalObjects()) + ")", "color: var(--text-muted); font-size: 0.78rem;")
            );
            link.modifier(new Modifier().style("text-decoration: none; padding: 0.6rem 1.1rem; border-radius: 10px; display: inline-flex; align-items: center; gap: 0.6rem; " + bg + border));
            bucketWidgets.add(link);
        }
        Widget container = Div.of(bucketWidgets.toArray(new Widget[0]));
        container.modifier(new Modifier().style("display: flex; gap: 0.5rem; margin-bottom: 1.5rem; flex-wrap: wrap;"));
        return container;
    }

    private Widget buildSearchToolbar(String currentDb, String currentBucket, String query,
                                      long totalRecords, int currentPage, int totalPages, int currentPageSize, boolean isStreamed) {
        List<Widget> searchItems = new ArrayList<>();
        searchItems.add(FluxUi.hidden("db", currentDb));
        searchItems.add(FluxUi.hidden("bucket", currentBucket));
        searchItems.add(FluxUi.hidden("size", String.valueOf(currentPageSize)));
        searchItems.add(FluxUi.textInput("q", query, "🔍 Buscar por ID, texto o valor JSON...", false));
        searchItems.add(FluxUi.submitButton("Buscar", "app-btn-secondary", null));
        if (query != null && !query.isBlank()) {
            searchItems.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&size=" + currentPageSize,
                    "Limpiar", "app-btn-secondary", null));
        }

        Form searchForm = Form.of(searchItems.toArray(new Widget[0])).action("/explorer").method("GET");
        searchForm.modifier(new Modifier().style("display: flex; gap: 0.5rem; flex: 1; max-width: 560px;"));

        List<Widget> rightItems = new ArrayList<>();
        if (isStreamed) {
            Span streamBadge = FluxUi.coloredSpan("⚡ Streaming por página",
                    "background:rgba(16,185,129,0.15); border:1px solid #10b981; color:#10b981; padding:2px 8px; border-radius:9999px; font-size:0.75rem; font-weight:700;");
            streamBadge.modifier(new Modifier().attribute("data-streaming", "page"));
            rightItems.add(streamBadge);
        }
        rightItems.add(FluxUi.coloredSpan(String.format("%,d registros encontrados • Página %,d de %,d", totalRecords, currentPage, totalPages),
                "font-size: 0.85rem; color: var(--text-muted); font-weight: 600;"));
        rightItems.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&action=new",
                "➕ Nuevo Registro (Create)", "app-btn-primary", null));

        Widget rightBox = Div.of(rightItems.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap;"));

        return Div.of(searchForm, rightBox)
                .modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1.25rem;"));
    }

    private Widget buildStructuredFieldsSection(EngineBucketInfo bucketInfo, Map<String, Object> existingValues) {
        List<Widget> fieldWidgets = new ArrayList<>();
        Paragraph banner = Paragraph.of("📐 Estructura DEFINED activa: complete los campos según su tipo y reglas (jettra_rules).");
        banner.modifier(new Modifier().style("font-size: 0.82rem; color: #10b981; margin: 0 0 0.75rem 0; font-weight: 600;"));
        fieldWidgets.add(banner);

        List<Widget> gridItems = new ArrayList<>();
        for (var attr : bucketInfo.getAttributes()) {
            String name = attr.atribute_name();
            String type = attr.type_atribute() != null ? attr.type_atribute() : "String";
            String rules = attr.jettra_rules() != null ? attr.jettra_rules() : "";
            String desc = attr.description() != null && !attr.description().isBlank() ? attr.description() : name;
            String upperRules = rules.toUpperCase();
            boolean isAutoInc = upperRules.contains("AUTO_INCREMENT") || upperRules.contains("AUTOINCREMENT");
            boolean isRequired = !isAutoInc && (upperRules.contains("NOT_NULL") || upperRules.contains("REQUIRED")
                    || upperRules.contains("NOT_EMPTY") || upperRules.contains("NOT_BLANK"));
            String val = existingValues != null && existingValues.get(name) != null ? String.valueOf(existingValues.get(name)) : "";

            String labelText = name + " (" + type + ")" + (isRequired ? " *" : "") + (isAutoInc ? " [AUTO_INCREMENT]" : "");
            Widget control;
            String lowerType = type.toLowerCase();
            if (lowerType.equals("boolean") || lowerType.equals("bool")) {
                control = FluxUi.dropdown("struct_" + name, List.of("true", "false"), val.isBlank() ? "true" : val);
            } else {
                String placeholder = isAutoInc ? "Autogenerado automáticamente si se omite" : (desc + (rules.isBlank() ? "" : " [" + rules + "]"));
                var input = FluxUi.textInput("struct_" + name, val, placeholder, isRequired);
                Modifier mod = new Modifier().cssClass("app-input");
                if (lowerType.equals("int") || lowerType.equals("integer") || lowerType.equals("long")) {
                    mod.attribute("type", "number").attribute("step", "1");
                } else if (lowerType.equals("double") || lowerType.equals("float") || lowerType.equals("decimal") || lowerType.equals("number")) {
                    mod.attribute("type", "number").attribute("step", "any");
                } else if (upperRules.contains("EMAIL")) {
                    mod.attribute("type", "email");
                } else {
                    mod.attribute("type", "text");
                }
                if (isRequired) mod.attribute("required", "required");
                if (upperRules.contains("POSITIVE")) mod.attribute("min", "0.0001");
                else if (upperRules.contains("NON_NEGATIVE")) mod.attribute("min", "0");
                input.modifier(mod);
                control = input;
            }
            gridItems.add(FluxUi.fieldColumn(labelText, control, null));
        }

        Div grid = Div.of(gridItems.toArray(new Widget[0]));
        grid.modifier(new Modifier().cssClass("explorer-form-grid"));
        fieldWidgets.add(grid);

        Div box = Div.of(fieldWidgets.toArray(new Widget[0]));
        box.modifier(new Modifier().style("background: rgba(16, 185, 129, 0.06); border: 1px solid rgba(16, 185, 129, 0.35); border-radius: 8px; padding: 0.9rem; margin-bottom: 1rem;"));
        return box;
    }

    private Widget buildCreateRecordForm(String currentDb, String currentBucket, EngineBucketInfo bucketInfo) {
        String engineType = bucketInfo != null ? bucketInfo.getEngineType() : "DOCUMENT";
        String normalizedEngine = EngineRecordRenderer.normalizeEngine(engineType);
        boolean isDefined = bucketInfo != null && "DEFINED".equalsIgnoreCase(bucketInfo.getStructureType()) && !bucketInfo.getAttributes().isEmpty();

        Header header = Header.of(3, "➕ Crear Nuevo Registro en [" + currentDb + " / " + currentBucket + "] • Motor: " + normalizedEngine
                + (isDefined ? " • Estructura: DEFINED" : " • Estructura: NONE"));
        header.modifier(new Modifier().style("font-size: 1.1rem; color: #00d4ff; margin: 0 0 1rem 0;"));

        Widget topGrid = Div.of(
                FluxUi.fieldColumn("Identificador Único (ID / Clave Primaria)", FluxUi.textInput("recordId", "", "REG-2026-001", true), null),
                FluxUi.fieldColumn("Descripción / Resumen Corto", FluxUi.textInput("summary", "", "Descripción breve del documento o entidad", true), null)
        ).modifier(new Modifier().cssClass("explorer-form-grid"));

        Widget fieldsWidget = isDefined
                ? buildStructuredFieldsSection(bucketInfo, null)
                : EngineRecordRenderer.renderCreateFieldsWidget(normalizedEngine);

        Widget btns = Div.of(
                FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket, "Cancelar", "app-btn-secondary", null),
                FluxUi.submitButton("💾 Guardar Registro", "app-btn-primary", null)
        ).modifier(new Modifier().style("display: flex; gap: 0.75rem; justify-content: flex-end; margin-top: 0.75rem;"));

        Form form = Form.of(
                FluxUi.hidden("action", "save_new"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("bucket", currentBucket),
                topGrid, fieldsWidget, btns
        ).action("/explorer").method("POST");

        Widget card = Div.of(header, form);
        card.modifier(new Modifier().cssClass("ui-card explorer-form-card").style("border-left: 4px solid #00d4ff; background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildEditRecordForm(String currentDb, String currentBucket, EngineBucketInfo bucketInfo, EngineRecordInfo rec) {
        boolean isDefined = bucketInfo != null && "DEFINED".equalsIgnoreCase(bucketInfo.getStructureType()) && !bucketInfo.getAttributes().isEmpty();
        Header header = Header.of(3, "✏️ Modificar Registro [" + rec.getId() + "] (v" + rec.getVersion() + ") • Motor: " + rec.getEngineType());
        header.modifier(new Modifier().style("font-size: 1.1rem; color: #ffd700; margin: 0 0 1rem 0;"));

        Widget topGrid = Div.of(
                FluxUi.fieldColumn("Identificador (Solo Lectura)", FluxUi.disabledInput(rec.getId()), null),
                FluxUi.fieldColumn("Descripción / Resumen Corto", FluxUi.textInput("summary", rec.getSummary(), "", true), null)
        ).modifier(new Modifier().cssClass("explorer-form-grid"));

        Widget fieldsWidget;
        if (isDefined) {
            Map<String, Object> existingMap = new java.util.LinkedHashMap<>();
            if (rec.getDetails() != null && !rec.getDetails().isBlank()) {
                try {
                    Object parsed = new io.jettra.json.JettraJson().parse(rec.getDetails().trim());
                    if (parsed instanceof io.jettra.json.JsonObject jo) existingMap.putAll(jo.getMap());
                } catch (Exception ignored) {}
            }
            fieldsWidget = buildStructuredFieldsSection(bucketInfo, existingMap);
        } else {
            fieldsWidget = EngineRecordRenderer.renderEditFieldsWidget(rec);
        }

        Widget btns = Div.of(
                FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket, "Cancelar", "app-btn-secondary", null),
                FluxUi.submitButton("💾 Actualizar Registro", "app-btn-primary", "background:#ffd700; color:#050b14;")
        ).modifier(new Modifier().style("display: flex; gap: 0.75rem; justify-content: flex-end; margin-top: 0.75rem;"));

        Form form = Form.of(
                FluxUi.hidden("action", "save_edit"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("bucket", currentBucket),
                FluxUi.hidden("recordId", rec.getId()),
                topGrid, fieldsWidget, btns
        ).action("/explorer").method("POST");

        Widget card = Div.of(header, form);
        card.modifier(new Modifier().cssClass("ui-card explorer-form-card").style("border-left: 4px solid #ffd700; background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildViewRecordModal(String currentDb, String currentBucket, EngineRecordInfo rec) {
        Header header = Header.of(3, "👁️ Inspección de Registro [" + rec.getId() + "] • " + rec.getEngineType());
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #00d4ff; margin: 0 0 0.85rem 0;"));

        Paragraph meta = Paragraph.of("Versión: v" + rec.getVersion() + " • Última modificación: " + rec.getTimestamp() + " • Bucket: " + rec.getBucketName() + " • Almacenamiento: " + rec.getEngineType());
        meta.modifier(new Modifier().style("font-size: 0.82rem; color: var(--text-muted); margin: 0 0 1rem 0;"));

        Widget engineViewerWidget = EngineRecordRenderer.renderViewWidget(rec);
        Widget btns = Div.of(
                FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&edit_id=" + rec.getId(),
                        "✏️ Editar Este Registro", "app-btn-secondary", null),
                FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket,
                        "Cerrar Visor", "app-btn-primary", null)
        ).modifier(new Modifier().style("display: flex; justify-content: flex-end; gap: 0.5rem;"));

        Widget card = Div.of(header, meta, engineViewerWidget, btns);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border: 2px solid #00d4ff; background: var(--bg-card);"));
        return card;
    }

    private Widget buildRecordsTable(List<EngineRecordInfo> pageRecords, long totalRecords, String currentDb, String currentBucket,
                                     String query, int currentPage, int totalPages, int currentPageSize, boolean isStreamed) {
        if (pageRecords.isEmpty() || totalRecords <= 0) {
            return Div.of(
                Header.of(4, "No hay registros disponibles en este bucket.").modifier(new Modifier().style("color: var(--text-muted); margin: 2rem 0; text-align: center;"))
            );
        }

        long startRecord = ((long) (currentPage - 1) * currentPageSize) + 1L;
        long endRecord = Math.min(startRecord + pageRecords.size() - 1L, totalRecords);

        String queryParam = (query != null && !query.isBlank()) ? "&q=" + encodeUrlParam(query) : "";
        String pageContextParams = "&page=" + currentPage + "&size=" + currentPageSize + queryParam;

        List<Widget> headers = List.of(
            Text.of("ID"),
            Text.of("Motor"),
            Text.of("Resumen"),
            Text.of("Versión"),
            Text.of("Fecha Mutación"),
            Text.of("Acciones")
        );

        List<List<Widget>> rows = new ArrayList<>();
        for (EngineRecordInfo r : pageRecords) {
            String engineColor = switch (r.getEngineType()) {
                case "DOCUMENT" -> "#00d4ff";
                case "RELATIONAL" -> "#ffd700";
                case "GRAPH" -> "#ec4899";
                case "VECTOR" -> "#a855f7";
                case "TIMESERIES" -> "#22c55e";
                case "KEY_VALUE" -> "#f97316";
                case "COLUMNAR" -> "#38bdf8";
                case "GEOSPATIAL" -> "#06b6d4";
                case "JAVA_RECORD" -> "#3b82f6";
                case "OBJECT" -> "#eab308";
                default -> "#94a3b8";
            };

            Link viewLink = FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&view_id=" + r.getId() + pageContextParams,
                    "👁️ Ver", "app-btn-secondary", "margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem;");
            Link editLink = FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&edit_id=" + r.getId() + pageContextParams,
                    "✏️ Editar", "app-btn-secondary", "margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem;");
            Link histLink = FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + currentBucket + "&history_id=" + r.getId() + pageContextParams,
                    "🕒 Versiones", "app-btn-secondary", "margin-right:5px; padding:0.3rem 0.6rem; font-size:0.78rem; background:rgba(16,185,129,0.15); border-color:#10b981; color:#10b981;");
            Link delLink = FluxUi.linkButton("/explorer?action=delete_record&db=" + currentDb + "&bucket=" + currentBucket + "&recordId=" + r.getId() + pageContextParams,
                    "🗑️", "app-btn-danger", "padding:0.3rem 0.6rem; font-size:0.78rem;");
            delLink.modifier(new Modifier().cssClass("app-btn-danger").style("padding:0.3rem 0.6rem; font-size:0.78rem;")
                    .attribute("onclick", "return confirm('¿Seguro de eliminar el registro " + escapeHtml(r.getId()) + "?');"));

            rows.add(List.of(
                FluxUi.coloredSpan(r.getId(), "color:#00d4ff; font-weight:700; font-family:monospace;"),
                FluxUi.pillBadge(r.getEngineType(), engineColor),
                FluxUi.coloredSpan(r.getSummary(), "font-weight:700;"),
                FluxUi.coloredSpan("v" + r.getVersion(), "color:#ffd700; font-weight:600;"),
                FluxUi.coloredSpan(r.getTimestamp(), "color:var(--text-muted); font-size:0.8rem;"),
                Div.of(viewLink, editLink, histLink, delLink)
            ));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Widget paginationWidget = buildPaginationWidget(currentDb, currentBucket, query, currentPage, totalPages, currentPageSize, startRecord, endRecord, totalRecords, isStreamed);

        Widget card = Div.of(datatable, paginationWidget);
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildPaginationWidget(String currentDb, String currentBucket, String query,
                                         int currentPage, int totalPages, int currentPageSize,
                                         long startRecord, long endRecord, long totalRecords, boolean isStreamed) {
        boolean hasPrev = currentPage > 1;
        boolean hasNext = currentPage < totalPages;

        String btnBaseStyle = "text-decoration: none; padding: 0.35rem 0.7rem; border-radius: 6px; font-size: 0.8rem; font-weight: 600; display: inline-flex; align-items: center; gap: 0.25rem; ";
        String activeNavStyle = btnBaseStyle + "background: var(--bg-subtle); color: var(--text-main); border: 1px solid var(--border-subtle); cursor: pointer;";
        String disabledNavStyle = btnBaseStyle + "background: rgba(255,255,255,0.03); color: var(--text-muted); border: 1px solid rgba(255,255,255,0.06); opacity: 0.45; pointer-events: none;";

        List<Widget> navWidgets = new ArrayList<>();
        if (hasPrev) {
            navWidgets.add(Link.of(buildPageUrl(currentDb, currentBucket, query, 1, currentPageSize), "⏮ Primera")
                    .modifier(new Modifier().style(activeNavStyle).attribute("data-page", "1").attribute("title", "Primera Página")));
            navWidgets.add(Link.of(buildPageUrl(currentDb, currentBucket, query, currentPage - 1, currentPageSize), "◀ Anterior")
                    .modifier(new Modifier().style(activeNavStyle).attribute("data-page", String.valueOf(currentPage - 1)).attribute("title", "Página Anterior")));
        } else {
            navWidgets.add(FluxUi.coloredSpan("⏮ Primera", disabledNavStyle));
            navWidgets.add(FluxUi.coloredSpan("◀ Anterior", disabledNavStyle));
        }

        int startPage = Math.max(1, currentPage - 2);
        int endPage = Math.min(totalPages, startPage + 4);
        if (endPage - startPage < 4) {
            startPage = Math.max(1, endPage - 4);
        }

        for (int p = startPage; p <= endPage; p++) {
            if (p == currentPage) {
                Span cur = FluxUi.coloredSpan(String.valueOf(p), btnBaseStyle + "background: #00d4ff; color: #050b14; font-weight: 800; border: 1px solid #00d4ff;");
                cur.modifier(new Modifier().attribute("data-current-page", String.valueOf(p)));
                navWidgets.add(cur);
            } else {
                navWidgets.add(Link.of(buildPageUrl(currentDb, currentBucket, query, p, currentPageSize), String.valueOf(p))
                        .modifier(new Modifier().style(activeNavStyle).attribute("data-page", String.valueOf(p))));
            }
        }

        if (hasNext) {
            navWidgets.add(Link.of(buildPageUrl(currentDb, currentBucket, query, currentPage + 1, currentPageSize), "Siguiente ▶")
                    .modifier(new Modifier().style(activeNavStyle).attribute("data-page", String.valueOf(currentPage + 1)).attribute("title", "Página Siguiente")));
            navWidgets.add(Link.of(buildPageUrl(currentDb, currentBucket, query, totalPages, currentPageSize), "Última ⏭")
                    .modifier(new Modifier().style(activeNavStyle).attribute("data-page", String.valueOf(totalPages)).attribute("title", "Última Página")));
        } else {
            navWidgets.add(FluxUi.coloredSpan("Siguiente ▶", disabledNavStyle));
            navWidgets.add(FluxUi.coloredSpan("Última ⏭", disabledNavStyle));
        }

        Dropdown sizeDropdown = FluxUi.dropdown("size", List.of("5", "10", "20", "50"), String.valueOf(currentPageSize));
        sizeDropdown.modifier(new Modifier().cssClass("app-input").attribute("name", "size")
                .style("width: auto; padding: 0.25rem 0.5rem; height: 30px; font-size: 0.8rem;")
                .attribute("onchange", "this.form.submit()"));

        List<Widget> sizeFormItems = new ArrayList<>();
        sizeFormItems.add(FluxUi.hidden("db", currentDb));
        sizeFormItems.add(FluxUi.hidden("bucket", currentBucket));
        if (query != null && !query.isBlank()) sizeFormItems.add(FluxUi.hidden("q", query));
        sizeFormItems.add(FluxUi.hidden("page", "1"));
        sizeFormItems.add(FluxUi.coloredSpan("Filas por página:", "font-size: 0.78rem; color: var(--text-muted);"));
        sizeFormItems.add(sizeDropdown);
        Form sizeForm = Form.of(sizeFormItems.toArray(new Widget[0])).action("/explorer").method("GET");
        sizeForm.modifier(new Modifier().style("display: inline-flex; align-items: center; gap: 0.4rem; margin: 0;"));

        Widget pageNumInput = FluxUi.textInput("page", String.valueOf(currentPage), "", false);
        pageNumInput.modifier(new Modifier().cssClass("app-input").attribute("type", "number")
                .attribute("min", "1").attribute("max", String.valueOf(totalPages))
                .style("width: 90px; padding: 0.2rem 0.45rem; height: 30px; font-size: 0.8rem;"));

        List<Widget> jumpFormItems = new ArrayList<>();
        jumpFormItems.add(FluxUi.hidden("db", currentDb));
        jumpFormItems.add(FluxUi.hidden("bucket", currentBucket));
        jumpFormItems.add(FluxUi.hidden("size", String.valueOf(currentPageSize)));
        if (query != null && !query.isBlank()) jumpFormItems.add(FluxUi.hidden("q", query));
        jumpFormItems.add(FluxUi.coloredSpan("Ir a pág.:", "font-size: 0.78rem; color: var(--text-muted);"));
        jumpFormItems.add(pageNumInput);
        jumpFormItems.add(FluxUi.submitButton("Ir", "app-btn-secondary", "padding: 0.25rem 0.55rem; height: 30px; font-size: 0.78rem;"));
        Form jumpForm = Form.of(jumpFormItems.toArray(new Widget[0])).action("/explorer").method("GET");
        jumpForm.modifier(new Modifier().style("display: inline-flex; align-items: center; gap: 0.35rem; margin: 0;"));

        List<Widget> leftItems = new ArrayList<>();
        leftItems.add(FluxUi.coloredSpan(
                String.format("Mostrando %,d - %,d de %,d registros (Página %,d de %,d)", startRecord, endRecord, totalRecords, currentPage, totalPages),
                "color: var(--text-muted);"));
        if (isStreamed) {
            leftItems.add(FluxUi.coloredSpan("[Streaming activo: solo página en memoria]", "font-size: 0.75rem; color: #10b981;"));
        }
        leftItems.add(sizeForm);
        leftItems.add(jumpForm);

        Widget leftBox = Div.of(leftItems.toArray(new Widget[0]))
                .modifier(new Modifier().style("display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; font-size: 0.84rem; color: var(--text-muted);"));
        Widget rightBox = Div.of(navWidgets.toArray(new Widget[0]))
                .modifier(new Modifier().cssClass("pagination-controls").style("display: flex; align-items: center; gap: 0.35rem; flex-wrap: wrap;"));

        Widget bar = Div.of(leftBox, rightBox);
        bar.modifier(new Modifier().cssClass("explorer-pagination-bar")
                .attribute("data-pagination", "explorer-records")
                .attribute("data-total-records", String.valueOf(totalRecords))
                .attribute("data-total-pages", String.valueOf(totalPages))
                .style("display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.85rem; margin-top: 1.1rem; padding-top: 0.9rem; border-top: 1px solid var(--border-subtle);"));
        return bar;
    }

    private String buildPageUrl(String db, String bucket, String query, int page, int size) {
        String base = "/explorer?db=" + encodeUrlParam(db)
                + "&bucket=" + encodeUrlParam(bucket)
                + "&page=" + page
                + "&size=" + size;
        return (query != null && !query.isBlank()) ? base + "&q=" + encodeUrlParam(query) : base;
    }

    private static String encodeUrlParam(String val) {
        if (val == null) return "";
        return java.net.URLEncoder.encode(val, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Widget buildVersionHistoryPanel(String currentDb, String currentBucket, EngineRecordInfo rec) {
        Header header = Header.of(3, "🕒 Historial de Versiones del Registro [" + rec.getId() + "] • " + rec.getEngineType());
        header.modifier(new Modifier().style("font-size: 1.15rem; color: #10b981; margin: 0 0 0.5rem 0;"));

        Paragraph sub = Paragraph.of("Versión activa actual: v" + rec.getVersion() + " • Total versiones registradas: " + rec.getVersionHistory().size() + " • Bucket: " + rec.getBucketName());
        sub.modifier(new Modifier().style("font-size: 0.85rem; color: var(--text-muted); margin: 0 0 1.25rem 0;"));

        List<Widget> versionItems = new ArrayList<>();
        for (EngineRecordVersion v : rec.getVersionHistory()) {
            boolean isCurrent = (v.getVersion() == rec.getVersion());
            boolean isRootV1 = (v.getVersion() == 1L);
            String borderColor = isCurrent ? "#10b981" : (isRootV1 ? "#38bdf8" : "var(--border-subtle)");
            String badgeBg = isCurrent ? "rgba(16, 185, 129, 0.2)" : (isRootV1 ? "rgba(56, 189, 248, 0.16)" : "rgba(255, 255, 255, 0.08)");
            String badgeColor = isCurrent ? "#10b981" : (isRootV1 ? "#38bdf8" : "#94a3b8");

            List<Widget> leftMeta = new ArrayList<>();
            leftMeta.add(FluxUi.coloredSpan("v" + v.getVersion(),
                    "background: " + badgeBg + "; color: " + badgeColor + "; font-weight: 800; font-size: 0.85rem; padding: 2px 8px; border-radius: 6px; border: 1px solid " + badgeColor + ";"));
            if (isCurrent) {
                leftMeta.add(FluxUi.coloredSpan("[VERSIÓN ACTIVA EN MEMORIA]", "color: #10b981; font-size: 0.75rem; font-weight: 700;"));
            }
            if (isRootV1) {
                leftMeta.add(FluxUi.coloredSpan("[VERSIÓN INICIAL v1]", "color: #38bdf8; font-size: 0.72rem; font-weight: 700;"));
            }
            leftMeta.add(FluxUi.coloredSpan(v.getSummary(), "color: var(--text-main); font-size: 0.92rem; font-weight: 700;"));

            Widget leftRow = Div.of(leftMeta.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap;"));
            Widget rightRow = Div.of(Text.of("👤 " + v.getAuthor() + " • 🕒 " + v.getTimestamp()))
                    .modifier(new Modifier().style("font-size: 0.78rem; color: var(--text-muted);"));

            Widget headerRow = Div.of(leftRow, rightRow)
                    .modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;"));

            List<Widget> cardParts = new ArrayList<>();
            cardParts.add(headerRow);
            if (v.getChangeNote() != null && !v.getChangeNote().isBlank()) {
                cardParts.add(Div.of(Text.of("📝 " + v.getChangeNote()))
                        .modifier(new Modifier().style("font-size: 0.8rem; color: #38bdf8; margin-bottom: 0.6rem; font-style: italic;")));
            }
            cardParts.add(Div.of(EngineRecordRenderer.renderVersionViewWidget(rec, v))
                    .modifier(new Modifier().style("margin-bottom: 0.75rem; max-height: 280px; overflow-y: auto;")));

            Widget restoreBtn;
            if (!isCurrent) {
                long nextVersion = Math.max(rec.getVersion(), v.getVersion()) + 1L;
                Button btn = Button.of("♻️ Restaurar esta Versión (v" + v.getVersion() + ")");
                btn.modifier(new Modifier().cssClass("app-btn-primary")
                        .style("background: #10b981; color: #050b14; font-weight: 700; padding: 0.35rem 0.85rem; font-size: 0.8rem;")
                        .attribute("type", "button")
                        .attribute("data-db", currentDb)
                        .attribute("data-bucket", currentBucket)
                        .attribute("data-record-id", rec.getId())
                        .attribute("data-version", String.valueOf(v.getVersion()))
                        .attribute("data-next-version", String.valueOf(nextVersion))
                        .attribute("data-summary", v.getSummary())
                        .attribute("onclick", "openRestoreModalFromBtn(this)"));
                restoreBtn = btn;
            } else {
                restoreBtn = FluxUi.coloredSpan("✓ Estado Vigente", "font-size: 0.8rem; color: #10b981; font-weight: 600;");
            }
            cardParts.add(Div.of(restoreBtn).modifier(new Modifier().style("display: flex; justify-content: flex-end;")));

            Widget itemCard = Div.of(cardParts.toArray(new Widget[0]));
            itemCard.modifier(new Modifier().style("border: 1px solid " + borderColor + "; background: var(--bg-card); border-radius: 8px; padding: 1rem; position: relative;"));
            versionItems.add(itemCard);
        }

        Widget versionList = Div.of(versionItems.toArray(new Widget[0]));
        versionList.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 1rem; margin-bottom: 1.25rem;"));

        Link closeHistoryLink = Link.of("/explorer?db=" + currentDb + "&bucket=" + currentBucket, "Cerrar Historial");
        closeHistoryLink.modifier(new Modifier().cssClass("app-btn-secondary"));
        Widget closeBar = Div.of(closeHistoryLink);
        closeBar.modifier(new Modifier().style("display: flex; justify-content: flex-end;"));

        Form restoreForm = Form.of(
                FluxUi.hidden("action", "restore_version"),
                FluxUi.hidden("db", currentDb).id("restoreDbInput"),
                FluxUi.hidden("bucket", currentBucket).id("restoreBucketInput"),
                FluxUi.hidden("recordId", rec.getId()).id("restoreRecordIdInput"),
                FluxUi.hidden("version", "").id("restoreVersionInput")
        ).action("/explorer").method("POST");
        restoreForm.id("restoreVersionForm");
        restoreForm.modifier(new Modifier().style("display:none;"));

        RawScript restoreScript = RawScript.of("""
            function openRestoreModalFromBtn(btn) {
                var db = btn.getAttribute('data-db') || '';
                var bucket = btn.getAttribute('data-bucket') || '';
                var recordId = btn.getAttribute('data-record-id') || '';
                var version = btn.getAttribute('data-version') || '';
                var nextVersion = btn.getAttribute('data-next-version') || '';
                var summary = btn.getAttribute('data-summary') || '';
                document.getElementById('restoreDbInput').value = db;
                document.getElementById('restoreBucketInput').value = bucket;
                document.getElementById('restoreRecordIdInput').value = recordId;
                document.getElementById('restoreVersionInput').value = version;
                var promptEl = document.getElementById('restoreModalPrompt');
                if (promptEl) promptEl.textContent = '¿Desea restaurar el registro ' + recordId + ' a la versión v' + version + '?';
                var detailsEl = document.getElementById('restoreModalDetails');
                if (detailsEl) detailsEl.textContent = 'Base / Bucket: ' + db + ' / ' + bucket + ' | Versión Objetivo: v' + version + ' — ' + summary + ' | Se generará v' + nextVersion;
                var modal = document.getElementById('restoreVersionModal');
                if (modal) modal.style.display = 'flex';
            }
            function closeRestoreModal() {
                var modal = document.getElementById('restoreVersionModal');
                if (modal) modal.style.display = 'none';
            }
            function confirmRestoreVersion() {
                var form = document.getElementById('restoreVersionForm');
                if (form) form.submit();
            }
            window.addEventListener('click', function(e) {
                var modal = document.getElementById('restoreVersionModal');
                if (modal && e.target === modal) modal.style.display = 'none';
            });
            """);

        Modal restoreModal = buildRestoreConfirmationModal();

        Widget card = Div.of(header, sub, versionList, closeBar, restoreForm, restoreScript, restoreModal);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; border: 2px solid #10b981; background: var(--bg-subtle);"));
        return card;
    }

    private Modal buildRestoreConfirmationModal() {
        Header modalTitle = Header.of(3, "♻️ Confirmar Restauración de Versión");
        modalTitle.modifier(new Modifier().style("margin: 0 0 0.75rem 0; font-size: 1.15rem; color: #10b981;"));

        Paragraph modalPrompt = Paragraph.of("¿Desea restaurar el registro seleccionado a esta versión histórica?");
        modalPrompt.modifier(new Modifier().attribute("id", "restoreModalPrompt").style("margin: 0 0 0.75rem 0; font-size: 0.95rem; color: var(--text-main); font-weight: 600;"));

        Widget detailsBox = Div.of(Text.of(""));
        detailsBox.modifier(new Modifier()
            .attribute("id", "restoreModalDetails")
            .style("background: rgba(15, 23, 42, 0.85); border: 1px solid #334155; border-radius: 8px; padding: 0.85rem; margin-bottom: 1.25rem; font-size: 0.84rem; color: #cbd5e1; line-height: 1.55;"));

        JettraButton cancelBtn = JettraButton.of("Cancelar")
            .severity(JettraButton.Severity.SECONDARY)
            .type(JettraButton.Type.OUTLINED);
        cancelBtn.modifier(new Modifier()
            .attribute("id", "cancelRestoreBtn")
            .cssClass("app-btn-secondary")
            .attribute("onclick", "closeRestoreModal()"));

        JettraButton confirmBtn = JettraButton.of("Confirmar Restauración")
            .severity(JettraButton.Severity.SUCCESS)
            .type(JettraButton.Type.SOLID);
        confirmBtn.modifier(new Modifier()
            .attribute("id", "confirmRestoreBtn")
            .cssClass("app-btn-primary")
            .style("background: #10b981; color: #050b14; font-weight: 700; border: none;")
            .attribute("onclick", "confirmRestoreVersion()"));

        Row actionsRow = Row.of(cancelBtn, confirmBtn);
        actionsRow.modifier(new Modifier().style("display: flex; justify-content: flex-end; gap: 0.75rem;"));

        Modal modal = Modal.of(modalTitle, modalPrompt, detailsBox, actionsRow).open(false);
        modal.id("restoreVersionModal");
        modal.modifier(new Modifier().attribute("id", "restoreVersionModal").style("z-index: 3100 !important;"));
        return modal;
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

package io.jettra.store.explorer.pages.databases;

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
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineIndexInfo;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vista de Administración de Bases de Datos y Motores Multimodelo en JettraFlux.
 */
public class DatabasesFluxView extends FluxView {

    private static final List<String> ALL_ENGINES = List.of(
            "DOCUMENT", "KEY_VALUE", "GRAPH", "TIMESERIES", "GEOSPATIAL",
            "JAVA_RECORD", "VECTOR", "COLUMNAR", "OBJECT", "RELATIONAL"
    );

    private static final String TYPE_PRESET_PLACEHOLDER = "-- Tipo sugerido --";

    public static final List<String> STRUCTURE_TYPES = List.of(
            "String", "Integer", "Long", "Double", "BigDecimal", "Boolean",
            "Instant", "LocalDate", "LocalDateTime", "UUID",
            "List", "ArrayList", "Set", "Map"
    );

    private static final List<String> JETTRA_RULES_PRESETS = List.of(
            "NONE", "NOT_NULL", "REQUIRED", "NOT_BLANK", "UNIQUE", "AUTO_INCREMENT",
            "POSITIVE", "NON_NEGATIVE", "EMAIL", "MIN:0", "MAX:100",
            "MIN_LENGTH:3", "MAX_LENGTH:255", "NOT_NULL,UNIQUE", "NOT_NULL,NOT_BLANK",
            "NOT_NULL,POSITIVE", "REGEX:^[A-Z0-9-]+$"
    );

    private final String selectedDb;
    private final String manageUsersDb;
    private final String manageIndexesDb;
    private final String manageIndexesBucket;
    private final String editIndexName;
    private final boolean manageEngines;
    private final boolean showAddBucketDialog;
    private final String editBucketName;
    private final String addBucketEngine;
    private final boolean isAdmin;
    private final String message;

    public DatabasesFluxView(String selectedDb, String message) {
        this(selectedDb, null, null, null, null, false, false, null, null, true, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, boolean isAdmin, String message) {
        this(selectedDb, manageUsersDb, null, null, null, false, false, null, null, isAdmin, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, boolean manageEngines, boolean isAdmin, String message) {
        this(selectedDb, manageUsersDb, null, null, null, manageEngines, false, null, null, isAdmin, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, String manageIndexesDb, String manageIndexesBucket,
                             boolean manageEngines, boolean isAdmin, String message) {
        this(selectedDb, manageUsersDb, manageIndexesDb, manageIndexesBucket, null, manageEngines, false, null, null, isAdmin, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, String manageIndexesDb, String manageIndexesBucket,
                             String editIndexName, boolean manageEngines, boolean showAddBucketDialog,
                             String editBucketName, String addBucketEngine, boolean isAdmin, String message) {
        this.selectedDb = selectedDb;
        this.manageUsersDb = manageUsersDb;
        this.manageIndexesDb = manageIndexesDb;
        this.manageIndexesBucket = manageIndexesBucket;
        this.editIndexName = editIndexName;
        this.manageEngines = manageEngines;
        this.showAddBucketDialog = showAddBucketDialog;
        this.editBucketName = editBucketName;
        this.addBucketEngine = addBucketEngine;
        this.isAdmin = isAdmin;
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        plugin.syncDatabasesFromPrimary(true);
        Map<String, List<EngineBucketInfo>> catalog = plugin.getDatabasesCatalog();
        List<String> dbNames = plugin.getDatabaseNames();

        String currentDb = (selectedDb != null && catalog.containsKey(selectedDb)) 
            ? selectedDb 
            : (dbNames.isEmpty() ? "" : dbNames.get(0));

        // Métricas Globales de Catálogo
        int totalDatabases = catalog.size();
        int totalBuckets = catalog.values().stream().mapToInt(List::size).sum();
        long totalObjects = catalog.values().stream()
            .flatMap(List::stream)
            .mapToLong(EngineBucketInfo::getTotalObjects)
            .sum();
        long totalBytes = catalog.values().stream()
            .flatMap(List::stream)
            .mapToLong(EngineBucketInfo::getTotalBytes)
            .sum();

        // 1. Mensaje Flash si existe (errores en rojo, éxitos en cian)
        Widget flashWidget = FluxUi.flashBanner(message);

        // 2. Tarjetas KPI
        Div kpi1 = buildKpiCard("Bases de Datos", String.valueOf(totalDatabases), "Catálogo activo", "#00d4ff");
        Div kpi2 = buildKpiCard("Motores / Buckets", String.valueOf(totalBuckets), "Multimodelo distribuido", "#10b981");
        Div kpi3 = buildKpiCard("Objetos / Registros", String.format("%,d", totalObjects), "Entidades indexadas", "#ffd700");
        Div kpi4 = buildKpiCard("Volumen de Almacén", formatBytes(totalBytes), "Direct Memory & SSTable", "#a855f7");

        Div kpiGrid = Div.of(kpi1, kpi2, kpi3, kpi4);
        kpiGrid.modifier(new Modifier().cssClass("kpi-grid"));

        // 3. Formulario para Crear Nueva Base de Datos
        Widget createDbCard = buildCreateDbCard();

        // 4. Tabla de Catálogo de Bases de Datos
        Widget databasesTable = buildDatabasesTable(catalog, currentDb);

        // 4.5 Panel de Administración de Usuarios por Base de Datos (Solo ADMIN) como diálogo modal encima del formulario
        Widget dbUsersSection = null;
        if (isAdmin && manageUsersDb != null && !manageUsersDb.isBlank() && catalog.containsKey(manageUsersDb)) {
            Modal dbUsersModal = Modal.of(buildDatabaseUsersSection(plugin, manageUsersDb)).open(true);
            dbUsersModal.id("dbUsersDialog");
            dbUsersSection = dbUsersModal;
        }

        // 5. Diálogo Modal de Motores / Buckets para la BD seleccionada (al dar clic en Administrar Motores)
        Widget enginesSection = null;
        Widget bucketFormOverlayModal = null;
        if (manageEngines) {
            Modal enginesModal = Modal.of(buildEnginesSection(plugin, currentDb)).open(true);
            enginesModal.id("dbEnginesDialog");
            enginesSection = enginesModal;

            EngineBucketInfo editingBucket = (editBucketName != null && !editBucketName.isBlank())
                    ? plugin.findBucketExact(currentDb, editBucketName)
                    : null;
            if (editingBucket != null) {
                Modal editModal = Modal.of(buildEditBucketModalCard(currentDb, editingBucket)).open(true);
                editModal.id("editBucketModal");
                editModal.modifier(new Modifier().style("z-index: 1200;"));
                bucketFormOverlayModal = editModal;
            } else if (showAddBucketDialog) {
                Modal addModal = Modal.of(buildAddBucketModalCard(currentDb)).open(true);
                addModal.id("addBucketModal");
                addModal.modifier(new Modifier().style("z-index: 1200;"));
                bucketFormOverlayModal = addModal;
            }
        }

        // 6. Diálogo Modal de Índices por Base de Datos y Bucket (al dar clic en Administrar Índices)
        Widget indexesSection = null;
        if (manageIndexesDb != null && !manageIndexesDb.isBlank() && catalog.containsKey(manageIndexesDb)) {
            Modal indexesModal = Modal.of(buildDatabaseIndexesSection(plugin, manageIndexesDb, manageIndexesBucket)).open(true);
            indexesModal.id("dbIndexesDialog");
            indexesSection = indexesModal;
        }

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(kpiGrid);
        rootChildren.add(createDbCard);
        rootChildren.add(databasesTable);
        if (dbUsersSection != null) rootChildren.add(dbUsersSection);
        if (enginesSection != null) rootChildren.add(enginesSection);
        if (bucketFormOverlayModal != null) rootChildren.add(bucketFormOverlayModal);
        if (indexesSection != null) rootChildren.add(indexesSection);

        return Div.of(rootChildren.toArray(new Widget[0]));
    }

    private Div buildKpiCard(String title, String value, String sub, String accentColor) {
        Div t = Div.of(Text.of(title));
        t.modifier(new Modifier().cssClass("kpi-title"));

        Div v = Div.of(Text.of(value));
        v.modifier(new Modifier().cssClass("kpi-value").style("color:" + accentColor + ";"));

        Div s = Div.of(Text.of(sub));
        s.modifier(new Modifier().cssClass("kpi-sub"));

        Div card = Div.of(t, v, s);
        card.modifier(new Modifier().cssClass("ui-card").style("border-top: 3px solid " + accentColor + ";"));
        return card;
    }

    private Widget buildCreateDbCard() {
        Header header = Header.of(3, "➕ Crear Nueva Base de Datos");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.5rem 0;"));

        Widget infoNote = Div.of(
                Text.of("⚡ Toda base de datos creada habilita automáticamente soporte nativo para los "),
                FluxUi.coloredSpan("9 motores (engines) de JettraStore", "color: #00d4ff; font-weight: 700;"),
                Text.of(": DOCUMENT, KEY_VALUE, COLUMNAR, TIMESERIES, GEOSPATIAL, GRAPH, VECTOR, JAVA_RECORD y OBJECT.")
        ).modifier(new Modifier().style("font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.85rem;"));

        Form form = Form.of(
                FluxUi.hidden("action", "create_db"),
                FluxUi.fieldColumn("Nombre de la Base de Datos", FluxUi.textInput("dbName", "", "ecommerce_core_db", true), "flex: 1.5; min-width: 220px;"),
                FluxUi.fieldColumn("Descripción", FluxUi.textInput("description", "", "Base de datos empresarial multimodelo para gestión de inventarios", false), "flex: 2.5; min-width: 260px;"),
                Div.of(FluxUi.submitButton("➕ Crear Base de Datos", "app-btn-primary", "height: 38px;"))
        ).action("/databases").method("POST");
        form.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Div card = Div.of(header, infoNote, form);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.5rem; background: var(--bg-subtle);"));
        return card;
    }

    private Widget buildDatabasesTable(Map<String, List<EngineBucketInfo>> catalog, String currentDb) {
        Header header = Header.of(3, "🗄️ Catálogo Maestro de Bases de Datos");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        List<Widget> headers = List.of(
            Text.of("Base de Datos"),
            Text.of("Motores / Buckets"),
            Text.of("Total Registros"),
            Text.of("Espacio en Memoria/Disco"),
            Text.of("Acciones")
        );

        List<List<Widget>> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, List<EngineBucketInfo>> entry : catalog.entrySet()) {
            String db = entry.getKey();
            List<EngineBucketInfo> buckets = entry.getValue();
            boolean isSelected = db.equalsIgnoreCase(currentDb);

            long dbObjects = buckets.stream().mapToLong(EngineBucketInfo::getTotalObjects).sum();
            long dbBytes = buckets.stream().mapToLong(EngineBucketInfo::getTotalBytes).sum();

            List<Widget> dbCellItems = new ArrayList<>();
            dbCellItems.add(FluxUi.coloredSpan("📁 " + db, "color:#00d4ff; font-weight:700;"));
            if (isSelected) {
                dbCellItems.add(FluxUi.coloredSpan(" [SELECCIONADA]", "color:#ffd700; font-size:0.75rem; font-weight:700;"));
            }
            Widget dbCell = Div.of(dbCellItems.toArray(new Widget[0]));
            Widget bucketsCell = Text.of(buckets.size() + " motores");
            Widget objCell = Text.of(String.format("%,d", dbObjects));
            Widget sizeCell = Text.of(formatBytes(dbBytes));

            List<Widget> actionsList = new ArrayList<>();
            actionsList.add(FluxUi.linkButton("/databases?db=" + db + "&manage_engines=true", "⚙️ Administrar Motores", "app-btn-secondary", "margin-right:6px;"));
            actionsList.add(FluxUi.linkButton("/databases?db=" + db + "&manage_indexes_db=" + db, "⚡ Administrar Índices", "app-btn-secondary", "margin-right:6px;"));
            if (isAdmin) {
                actionsList.add(FluxUi.linkButton("/databases?db=" + db + "&manage_users_db=" + db, "👥 Administrar Usuarios", "app-btn-secondary", "margin-right:6px;"));
            } else {
                Widget locked = FluxUi.coloredSpan("🔒 Administrar Usuarios", "margin-right:6px; opacity:0.45; cursor:not-allowed;");
                locked.modifier(new Modifier().cssClass("app-btn-secondary").style("margin-right:6px; opacity:0.45; cursor:not-allowed;")
                        .attribute("title", "Opción habilitada únicamente para usuarios con rol ADMIN"));
                actionsList.add(locked);
            }
            actionsList.add(FluxUi.linkButton("/explorer?db=" + db, "📦 Ver Registros", "app-btn-primary", "margin-right:6px;"));
            if (!"system_metadata_db".equalsIgnoreCase(db)) {
                Widget delLink = FluxUi.linkButton("/databases?action=delete_db&db=" + db, "🗑️", "app-btn-danger", null);
                delLink.modifier(new Modifier().cssClass("app-btn-danger")
                        .attribute("onclick", "return confirm('¿Seguro de eliminar la base de datos " + db + "?');"));
                actionsList.add(delLink);
            }
            Widget actionsCell = Div.of(actionsList.toArray(new Widget[0]));

            rows.add(List.of(dbCell, bucketsCell, objCell, sizeCell, actionsCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(header, datatable);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem;"));
        return card;
    }

    private Widget buildDatabaseUsersSection(JettraStorePlugin plugin, String targetDb) {
        List<JettraUser> allUsers = plugin.getUsers();
        Header header = Header.of(3, "👥 Administración de Usuarios de la Base de Datos [" + targetDb + "]");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: #ffd700; margin: 0 0 0.75rem 0;"));

        List<String> assignableUsernames = allUsers.stream()
                .filter(u -> u.getGlobalRole() != JettraUser.GlobalRole.ADMIN)
                .map(JettraUser::getUsername)
                .toList();

        Widget addFormWidget;
        if (!assignableUsernames.isEmpty()) {
            Form addForm = Form.of(
                    FluxUi.hidden("action", "add_db_user"),
                    FluxUi.hidden("db", targetDb),
                    FluxUi.fieldColumn("Usuario", FluxUi.dropdown("username", assignableUsernames, assignableUsernames.get(0)), "flex: 1.5; min-width: 180px;"),
                    FluxUi.fieldColumn("Rol en " + targetDb, FluxUi.dropdown("role", List.of("READ_WRITE", "READ_ONLY", "ADMIN"), "READ_WRITE"), "flex: 1.2; min-width: 160px;"),
                    Div.of(
                            FluxUi.submitButton("➕ Añadir Usuario a la BD", "app-btn-primary", "height: 38px;"),
                            FluxUi.linkButton("/databases?db=" + targetDb, "Cerrar", "app-btn-secondary", "height: 38px; display:inline-flex; align-items:center;")
                    ).modifier(new Modifier().style("display:flex; gap:0.5rem;"))
            ).action("/databases").method("POST");
            addForm.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end; background: var(--bg-subtle); padding: 1rem; border-radius: 8px; margin-bottom: 1.1rem;"));
            addFormWidget = addForm;
        } else {
            Widget msgRow = Div.of(
                    Div.of(
                            FluxUi.coloredSpan("No hay usuarios adicionales no-admin registrados. Crea usuarios en ", "font-size:0.85rem; color:var(--text-muted);"),
                            Link.of("/users", "/users").modifier(new Modifier().style("color:#00d4ff; font-size:0.85rem;")),
                            FluxUi.coloredSpan(" para asignarlos a esta base de datos.", "font-size:0.85rem; color:var(--text-muted);")
                    ),
                    FluxUi.linkButton("/databases?db=" + targetDb, "Cerrar", "app-btn-secondary", null)
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; background:var(--bg-subtle); padding:0.85rem 1rem; border-radius:8px; margin-bottom:1.1rem;"));
            addFormWidget = msgRow;
        }

        List<Widget> userTableHeaders = List.of(
            Text.of("Usuario"),
            Text.of("Rol Global"),
            Text.of("Rol en " + targetDb),
            Div.of(Text.of("Acción")).modifier(new Modifier().style("text-align:right;"))
        );

        List<List<Widget>> userRows = new ArrayList<>();
        for (JettraUser u : allUsers) {
            JettraDatabaseRole dbRole = u.getRoleForDatabase(targetDb);
            if (dbRole == JettraDatabaseRole.NONE && u.getGlobalRole() != JettraUser.GlobalRole.ADMIN) {
                continue;
            }
            Widget actionCell;
            if (u.getGlobalRole() == JettraUser.GlobalRole.ADMIN) {
                actionCell = FluxUi.coloredSpan("Superadmin Global", "color:var(--text-muted); font-size:0.75rem;");
            } else {
                Form remForm = Form.of(
                        FluxUi.hidden("action", "remove_db_user"),
                        FluxUi.hidden("db", targetDb),
                        FluxUi.hidden("username", u.getUsername()),
                        FluxUi.submitButton("➖ Remover", "app-btn-danger", null)
                ).action("/databases").method("POST");
                remForm.modifier(new Modifier().style("display:inline;"));
                actionCell = remForm;
            }

            userRows.add(List.of(
                FluxUi.coloredSpan("👤 " + u.getUsername(), "color:#ffd700; font-weight:700;"),
                FluxUi.coloredSpan(u.getGlobalRole().name(), "color:#38bdf8; font-weight:700; font-size:0.78rem;"),
                FluxUi.coloredSpan(dbRole.name(), "color:#10b981; font-weight:700; font-size:0.8rem;"),
                Div.of(actionCell).modifier(new Modifier().style("text-align:right;"))
            ));
        }

        Datatable userDatatable = Datatable.ofWidgets(userTableHeaders, userRows);
        userDatatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(header, addFormWidget, userDatatable);
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem; border-left: 4px solid #ffd700;"));
        return card;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String engineColor(String engineType) {
        if (engineType == null) return "#94a3b8";
        return switch (engineType.toUpperCase()) {
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
    }

    private static String engineStructureHint(String engineType) {
        if (engineType == null) return "Admite estructura DEFINED (estricta) o NONE (esquema libre).";
        return switch (engineType.toUpperCase()) {
            case "DOCUMENT" -> "DOCUMENT: admite documentos JSON anidados, colecciones List<>, Set<>, ArrayList<> y objetos de usuario.";
            case "JAVA_RECORD" -> "JAVA_RECORD: ideal para records inmutables tipados (String, Long, BigDecimal, Instant, UUID, List<T>, Set<T>) con reglas @Id / AUTO_INCREMENT.";
            case "KEY_VALUE" -> "KEY_VALUE: optimizado para pares clave-valor de baja latencia (puede usar NONE o DEFINED con clave y valor tipado).";
            case "GRAPH" -> "GRAPH: recomendado definir atributos de nodos/aristas (source, target, rel, weight) o propiedades libres.";
            case "TIMESERIES" -> "TIMESERIES: recomendado incluir Instant/LocalDateTime y métricas numéricas (Double/BigDecimal) con reglas POSITIVE/NON_NEGATIVE.";
            case "GEOSPATIAL" -> "GEOSPATIAL: recomendado incluir lat (Double), lng (Double), altitud (Double) y zona (String).";
            case "VECTOR" -> "VECTOR: recomendado incluir embedding (List<Double> / ArrayList<Double>) y dims (Integer).";
            case "COLUMNAR", "RELATIONAL" -> engineType.toUpperCase() + ": recomendado modo DEFINED con columnas tipadas estrictas y reglas NOT_NULL / UNIQUE.";
            case "OBJECT" -> "OBJECT: soporta clases y objetos de dominio definidos por el usuario junto con colecciones genéricas.";
            default -> "Admite estructura DEFINED (validación estricta) o NONE (esquema libre).";
        };
    }

    private Widget buildStructureFieldsTable(String tableId, List<io.jettra.store.core.JettraBucketSchema.AttributeRule> existingAttrs) {
        List<Widget> headers = List.of(
                Text.of("nombre"),
                Text.of("tipo"),
                Text.of("rules"),
                Text.of("descripcion"),
                Text.of("Acción")
        );

        List<io.jettra.store.core.JettraBucketSchema.AttributeRule> rowsData = new ArrayList<>();
        if (existingAttrs != null && !existingAttrs.isEmpty()) {
            rowsData.addAll(existingAttrs);
            rowsData.add(new io.jettra.store.core.JettraBucketSchema.AttributeRule("", "String", "", ""));
        } else {
            rowsData.add(new io.jettra.store.core.JettraBucketSchema.AttributeRule("codigo", "Long", "AUTO_INCREMENT", "Identificador secuencial"));
            rowsData.add(new io.jettra.store.core.JettraBucketSchema.AttributeRule("nombre", "String", "NOT_NULL,NOT_BLANK", "Nombre principal"));
            rowsData.add(new io.jettra.store.core.JettraBucketSchema.AttributeRule("etiquetas", "List<String>", "", "Lista de etiquetas"));
            rowsData.add(new io.jettra.store.core.JettraBucketSchema.AttributeRule("", "String", "", ""));
        }

        List<String> typeSelectOptions = new ArrayList<>();
        typeSelectOptions.add(TYPE_PRESET_PLACEHOLDER);
        typeSelectOptions.addAll(STRUCTURE_TYPES);

        List<List<Widget>> tableRows = new ArrayList<>();
        for (int i = 0; i < rowsData.size(); i++) {
            var attr = rowsData.get(i);
            String declaredType = (attr.type_atribute() != null && !attr.type_atribute().isBlank()) ? attr.type_atribute().trim() : "String";
            String selectedPreset = STRUCTURE_TYPES.stream()
                    .filter(t -> t.equalsIgnoreCase(declaredType))
                    .findFirst()
                    .orElse(TYPE_PRESET_PLACEHOLDER);

            var typeSelect = FluxUi.dropdown("attr_type_select_" + i, typeSelectOptions, selectedPreset);
            typeSelect.modifier(new Modifier()
                    .cssClass("app-input")
                    .attribute("name", "attr_type_select_" + i)
                    .attribute("data-attr-type-select", "true")
                    .attribute("onchange", "jettraApplyTypePreset(this)"));

            var typeInput = FluxUi.textInput("attr_type_" + i, declaredType, "Ej: String, Cliente, List<MiRecord>", false);
            typeInput.modifier(new Modifier()
                    .cssClass("app-input")
                    .attribute("type", "text")
                    .attribute("data-attr-type-input", "true"));

            Widget typeCell = Div.of(
                    typeSelect,
                    typeInput
            ).modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.25rem; min-width: 185px;"));

            String curRules = attr.jettra_rules() != null ? attr.jettra_rules() : "";
            String selectedRulePreset = JETTRA_RULES_PRESETS.stream()
                    .filter(r -> r.equalsIgnoreCase(curRules))
                    .findFirst().orElse("NONE");
            var rulesSelect = FluxUi.dropdown("attr_rules_select_" + i, JETTRA_RULES_PRESETS, selectedRulePreset);
            rulesSelect.modifier(new Modifier()
                    .cssClass("app-input")
                    .attribute("name", "attr_rules_select_" + i)
                    .attribute("onchange", "jettraApplyRulePreset(this)"));
            Widget rulesCell = Div.of(
                    rulesSelect,
                    FluxUi.textInput("attr_rules_" + i, curRules, "Personalizar reglas (ej: NOT_NULL, MIN:1)", false)
            ).modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.25rem; min-width: 180px;"));

            Button removeRowBtn = Button.of("🗑️ Remover Fila");
            removeRowBtn.modifier(new Modifier()
                    .cssClass("app-btn-danger")
                    .attribute("type", "button")
                    .attribute("onclick", "jettraRemoveStructureRow(this)")
                    .style("padding: 0.28rem 0.55rem; font-size: 0.74rem; white-space: nowrap;"));

            tableRows.add(List.of(
                    FluxUi.textInput("attr_name_" + i, attr.atribute_name(), "nombreCampo", false),
                    typeCell,
                    rulesCell,
                    FluxUi.textInput("attr_desc_" + i, attr.description(), "Descripción del campo", false),
                    removeRowBtn
            ));
        }

        Datatable table = Datatable.ofWidgets(headers, tableRows);
        table.id(tableId);
        table.modifier(new Modifier().cssClass("app-table").attribute("data-structure-table", "true"));

        Button addRowBtn = Button.of("➕ Añadir Fila");
        addRowBtn.modifier(new Modifier()
                .cssClass("app-btn-secondary")
                .attribute("type", "button")
                .attribute("onclick", "jettraAddStructureRow('" + tableId + "')")
                .style("margin-top: 0.55rem; padding: 0.32rem 0.75rem; font-size: 0.78rem;"));

        RawScript script = RawScript.of("""
                function jettraApplyTypePreset(sel) {
                  if (!sel || !sel.parentElement) return;
                  var input = sel.parentElement.querySelector('input[data-attr-type-input="true"]');
                  if (!input) return;
                  var v = (sel.value || '').trim();
                  if (!v || v === '-- Tipo sugerido --') return;
                  input.value = v;
                  input.focus();
                }
                function jettraApplyRulePreset(sel) {
                  if (!sel || !sel.parentElement) return;
                  var input = sel.parentElement.querySelector('input[name^="attr_rules_"]');
                  if (!input) return;
                  var v = sel.value || '';
                  if (!v || v === 'NONE') return;
                  var current = (input.value || '').trim();
                  if (!current) {
                    input.value = v;
                  } else if (current.toUpperCase().indexOf(v.toUpperCase()) === -1) {
                    input.value = current + ',' + v;
                  }
                }
                function jettraRemoveStructureRow(btn) {
                  var tr = btn ? btn.closest('tr') : null;
                  if (!tr) return;
                  var tbody = tr.parentElement;
                  if (tbody && tbody.querySelectorAll('tr').length > 1) {
                    tr.remove();
                  } else if (tr) {
                    tr.querySelectorAll('input').forEach(function(inp){ inp.value = ''; });
                  }
                }
                function jettraAddStructureRow(tableId) {
                  var tbl = document.getElementById(tableId);
                  if (!tbl) return;
                  var tbody = tbl.querySelector('tbody');
                  if (!tbody) return;
                  var idx = tbody.querySelectorAll('tr').length;
                  var tr = document.createElement('tr');
                  var typeOpts = ['-- Tipo sugerido --','String','Integer','Long','Double','BigDecimal','Boolean','Instant','LocalDate','LocalDateTime','UUID','List','ArrayList','Set','Map']
                    .map(function(o){ return '<option value="' + o + '">' + o + '</option>'; }).join('');
                  var ruleOpts = ['NONE','NOT_NULL','REQUIRED','NOT_BLANK','UNIQUE','AUTO_INCREMENT','POSITIVE','NON_NEGATIVE','EMAIL','MIN:0','MAX:100','MIN_LENGTH:3','MAX_LENGTH:255','NOT_NULL,UNIQUE','NOT_NULL,NOT_BLANK','NOT_NULL,POSITIVE','REGEX:^[A-Z0-9-]+$']
                    .map(function(r){ return '<option value="' + r + '">' + r + '</option>'; }).join('');
                  tr.innerHTML =
                    '<td><input type="text" class="app-input" name="attr_name_' + idx + '" placeholder="nombreCampo"></td>' +
                    '<td><div style="display:flex;flex-direction:column;gap:0.25rem;min-width:185px;">' +
                      '<select class="app-input" name="attr_type_select_' + idx + '" data-attr-type-select="true" onchange="jettraApplyTypePreset(this)">' + typeOpts + '</select>' +
                      '<input type="text" class="app-input" name="attr_type_' + idx + '" data-attr-type-input="true" value="String" placeholder="Ej: String, Cliente, List&lt;MiRecord&gt;">' +
                    '</div></td>' +
                    '<td><div style="display:flex;flex-direction:column;gap:0.25rem;min-width:180px;">' +
                      '<select class="app-input" name="attr_rules_select_' + idx + '" onchange="jettraApplyRulePreset(this)">' + ruleOpts + '</select>' +
                      '<input type="text" class="app-input" name="attr_rules_' + idx + '" placeholder="Personalizar reglas (ej: NOT_NULL, MIN:1)">' +
                    '</div></td>' +
                    '<td><input type="text" class="app-input" name="attr_desc_' + idx + '" placeholder="Descripción del campo"></td>' +
                    '<td><button type="button" class="app-btn-danger" onclick="jettraRemoveStructureRow(this)" style="padding:0.28rem 0.55rem;font-size:0.74rem;white-space:nowrap;">🗑️ Remover Fila</button></td>';
                  tbody.appendChild(tr);
                }
                """);

        return Div.of(
                FluxUi.coloredSpan("📐 Definición de Estructura (tabla: nombre | tipo | rules | descripcion) — requerida solo si Modo = DEFINED:",
                        "font-size: 0.78rem; font-weight: 700; color: #00d4ff; display: block; margin-bottom: 0.45rem;"),
                table,
                addRowBtn,
                script
        ).modifier(new Modifier().style("margin-top: 0.85rem; padding: 0.75rem; background: rgba(0, 212, 255, 0.05); border: 1px solid rgba(0, 212, 255, 0.25); border-radius: 8px; overflow-x: auto;"));
    }

    private Widget buildAddBucketModalCard(String currentDb) {
        String selectedEngine = (addBucketEngine != null && ALL_ENGINES.stream().anyMatch(e -> e.equalsIgnoreCase(addBucketEngine)))
                ? addBucketEngine.trim().toUpperCase()
                : "DOCUMENT";

        Header title = Header.of(3, "➕ Añadir Nuevo Motor / Bucket en [" + currentDb + "]");
        title.modifier(new Modifier().style("font-size: 1.08rem; color: #00d4ff; margin: 0;"));
        Widget headerBar = Div.of(
                title,
                FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true", "✖ Cerrar", "app-btn-secondary", null)
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 0.85rem; flex-wrap: wrap;"));

        Form bucketForm = Form.of(
                FluxUi.hidden("action", "add_bucket"),
                FluxUi.hidden("db", currentDb),
                Div.of(
                        FluxUi.fieldColumn("Nombre del Bucket", FluxUi.textInput("bucketName", "", "AuditLog", true), "flex: 1.4; min-width: 160px;"),
                        FluxUi.fieldColumn("Tipo de Motor (Engine)", FluxUi.dropdown("engineType", ALL_ENGINES, selectedEngine), "flex: 1.2; min-width: 150px;"),
                        FluxUi.fieldColumn("Modo de Estructura", FluxUi.dropdown("structureType", List.of("NONE", "DEFINED"), "NONE"), "flex: 1; min-width: 140px;"),
                        FluxUi.fieldColumn("Descripción del Almacén", FluxUi.textInput("description", "", "Colección de logs de auditoría transaccional", false), "flex: 2; min-width: 200px;")
                ).modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end;")),
                FluxUi.coloredSpan("ℹ️ " + engineStructureHint(selectedEngine),
                        "font-size: 0.76rem; color: var(--text-muted); display: block; margin-top: 0.5rem;"),
                buildStructureFieldsTable("addBucketStructureTable", List.of()),
                Div.of(
                        FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true", "Cancelar", "app-btn-secondary", null),
                        FluxUi.submitButton("➕ Añadir Bucket al Engine", "app-btn-primary", "height: 38px;")
                ).modifier(new Modifier().style("display: flex; gap: 0.6rem; justify-content: flex-end; margin-top: 0.85rem;"))
        ).action("/databases").method("POST");

        Div card = Div.of(headerBar, bucketForm);
        card.modifier(new Modifier()
                .cssClass("ui-card")
                .attribute("data-add-bucket-modal", "true")
                .style("width: min(94vw, 960px); max-width: 100%; border-left: 4px solid #00d4ff;"));
        return card;
    }

    private Widget buildEditBucketModalCard(String currentDb, EngineBucketInfo editingBucket) {
        String curMode = "DEFINED".equalsIgnoreCase(editingBucket.getStructureType()) ? "DEFINED" : "NONE";

        Header title = Header.of(3, "✏️ Editar Bucket [" + editingBucket.getBucketName() + "] • Engine: " + editingBucket.getEngineType());
        title.modifier(new Modifier().style("font-size: 1.08rem; color: #ffd700; margin: 0;"));
        Widget headerBar = Div.of(
                title,
                FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true", "✖ Cerrar", "app-btn-secondary", null)
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 0.85rem; flex-wrap: wrap;"));

        Form editForm = Form.of(
                FluxUi.hidden("action", "edit_bucket"),
                FluxUi.hidden("db", currentDb),
                FluxUi.hidden("originalBucketName", editingBucket.getBucketName()),
                Div.of(
                        FluxUi.fieldColumn("Nombre del Bucket", FluxUi.textInput("bucketName", editingBucket.getBucketName(), "Nombre bucket", true), "flex: 1.4; min-width: 160px;"),
                        FluxUi.fieldColumn("Engine (Inmutable)", FluxUi.disabledInput(editingBucket.getEngineType()), "flex: 1; min-width: 140px;"),
                        FluxUi.fieldColumn("Modo de Estructura", FluxUi.dropdown("structureType", List.of("NONE", "DEFINED"), curMode), "flex: 1; min-width: 140px;"),
                        FluxUi.fieldColumn("Descripción", FluxUi.textInput("description", editingBucket.getDescription(), "Descripción", false), "flex: 2; min-width: 200px;")
                ).modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end;")),
                FluxUi.coloredSpan("ℹ️ " + engineStructureHint(editingBucket.getEngineType()),
                        "font-size: 0.76rem; color: var(--text-muted); display: block; margin-top: 0.5rem;"),
                buildStructureFieldsTable("editBucketStructureTable", editingBucket.getAttributes()),
                Div.of(
                        FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true", "Cancelar", "app-btn-secondary", null),
                        FluxUi.submitButton("💾 Guardar Cambios del Bucket", "app-btn-primary", null)
                ).modifier(new Modifier().style("display: flex; gap: 0.6rem; justify-content: flex-end; margin-top: 0.85rem;"))
        ).action("/databases").method("POST");

        Div card = Div.of(headerBar, editForm);
        card.modifier(new Modifier()
                .cssClass("ui-card")
                .attribute("data-edit-bucket-form", editingBucket.getBucketName())
                .style("width: min(94vw, 960px); max-width: 100%; border-left: 4px solid #ffd700;"));
        return card;
    }

    private Widget buildEnginesSection(JettraStorePlugin plugin, String currentDb) {
        List<EngineBucketInfo> buckets = plugin.getBuckets(currentDb);

        Header header = Header.of(3, "⚙️ Motores de Almacenamiento y Buckets en [" + currentDb + "]");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0;"));
        Widget headerActions = Div.of(
                FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true&add_bucket=true",
                        "➕ Añadir Nuevo Motor / Bucket", "app-btn-primary", "padding: 0.4rem 0.8rem; font-size: 0.82rem;"),
                FluxUi.linkButton("/databases?db=" + currentDb, "✖ Cerrar", "app-btn-secondary", null)
        ).modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap;"));
        Widget headerBar = Div.of(header, headerActions)
                .modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap;"));

        List<Widget> modalChildren = new ArrayList<>();
        modalChildren.add(headerBar);

        // Agrupar Buckets por Engine de Almacenamiento (sin incrustar el formulario en la parte superior)
        Map<String, List<EngineBucketInfo>> byEngine = new LinkedHashMap<>();
        for (String eng : ALL_ENGINES) {
            byEngine.put(eng, new ArrayList<>());
        }
        for (EngineBucketInfo b : buckets) {
            String engKey = b.getEngineType() != null ? b.getEngineType().toUpperCase() : "DOCUMENT";
            byEngine.computeIfAbsent(engKey, k -> new ArrayList<>()).add(b);
        }

        List<Widget> bucketHeaders = List.of(
                Text.of("Bucket / Descripción"),
                Text.of("Estructura"),
                Text.of("Objetos / Capacidad"),
                Text.of("Índices Activos"),
                Div.of(Text.of("Acciones")).modifier(new Modifier().style("text-align:right;"))
        );

        for (Map.Entry<String, List<EngineBucketInfo>> entry : byEngine.entrySet()) {
            String engName = entry.getKey();
            List<EngineBucketInfo> engBuckets = entry.getValue();
            String eColor = engineColor(engName);

            Widget engBar = Div.of(
                    Div.of(
                            FluxUi.pillBadge("⚙️ " + engName, eColor),
                            FluxUi.coloredSpan(" " + engBuckets.size() + " bucket(s)", "font-size: 0.78rem; color: var(--text-muted); font-weight: 700; margin-left: 0.45rem;")
                    ),
                    FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true&add_bucket_engine=" + engName,
                            "➕ Añadir Bucket en " + engName, "app-btn-secondary", "padding: 0.25rem 0.6rem; font-size: 0.74rem;")
            ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.55rem;"));

            Widget engBody;
            if (engBuckets.isEmpty()) {
                Paragraph emptyEng = Paragraph.of("Sin buckets registrados en " + engName + ".");
                emptyEng.modifier(new Modifier().style("font-size: 0.76rem; color: var(--text-muted); margin: 0;"));
                engBody = emptyEng;
            } else {
                List<List<Widget>> bucketRows = new ArrayList<>();
                for (EngineBucketInfo b : engBuckets) {
                    boolean isDefined = "DEFINED".equalsIgnoreCase(b.getStructureType()) && !b.getAttributes().isEmpty();
                    Widget structureBadge = isDefined
                            ? FluxUi.pillBadge("DEFINED (" + b.getAttributes().size() + " campos)", "#10b981")
                            : FluxUi.pillBadge("NONE (Libre)", "#94a3b8");

                    Widget idxCell;
                    if (b.getIndexes().isEmpty()) {
                        idxCell = FluxUi.coloredSpan("Sin índices", "color:var(--text-muted); font-size:0.75rem;");
                    } else {
                        List<Widget> idxChips = new ArrayList<>();
                        for (EngineIndexInfo idx : b.getIndexes()) {
                            idxChips.add(FluxUi.coloredSpan(idx.getName() + " [" + idx.getType() + "]",
                                    "background:rgba(255,255,255,0.06); padding:2px 6px; border-radius:4px; font-size:0.72rem; font-family:monospace; display:inline-block;"));
                        }
                        idxCell = Div.of(idxChips.toArray(new Widget[0]))
                                .modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:4px; max-width:240px;"));
                    }

                    List<Widget> bucketActions = new ArrayList<>();
                    bucketActions.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + b.getBucketName(),
                            "📦 Registros", "app-btn-primary", "padding:0.28rem 0.55rem; font-size:0.75rem;"));
                    bucketActions.add(FluxUi.linkButton("/databases?db=" + currentDb + "&manage_engines=true&edit_bucket=" + b.getBucketName(),
                            "✏️ Editar", "app-btn-secondary", "padding:0.28rem 0.55rem; font-size:0.75rem;"));
                    if (buckets.size() > 1) {
                        Widget delBucket = FluxUi.linkButton("/databases?action=delete_bucket&db=" + currentDb + "&bucket=" + b.getBucketName(),
                                "🗑️ Remover", "app-btn-danger", "padding:0.28rem 0.55rem; font-size:0.75rem;");
                        delBucket.modifier(new Modifier().cssClass("app-btn-danger").style("padding:0.28rem 0.55rem; font-size:0.75rem;")
                                .attribute("onclick", "return confirm('¿Seguro de remover el bucket " + escapeHtml(b.getBucketName()) + " y sus índices/registros asociados?');"));
                        bucketActions.add(delBucket);
                    }

                    Widget bucketDescCell = Div.of(
                            FluxUi.coloredSpan(b.getBucketName(), "font-weight:700; display:block; color:var(--text-main);"),
                            FluxUi.coloredSpan(b.getDescription(), "color:var(--text-muted); font-size:0.74rem; display:block; margin-top:2px;")
                    );
                    Widget statsCell = Div.of(
                            FluxUi.coloredSpan(String.format("%,d", b.getTotalObjects()), "font-weight:700; display:block; color:var(--text-main);"),
                            FluxUi.coloredSpan(b.getFormattedSize(), "color:var(--text-muted); font-size:0.74rem; display:block;")
                    );
                    Widget actionsCell = Div.of(bucketActions.toArray(new Widget[0]))
                            .modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; gap:0.35rem; white-space:nowrap;"));

                    bucketRows.add(List.of(bucketDescCell, structureBadge, statsCell, idxCell, actionsCell));
                }
                Datatable engTable = Datatable.ofWidgets(bucketHeaders, bucketRows);
                engTable.modifier(new Modifier().cssClass("app-table"));
                engBody = engTable;
            }

            Div engGroup = Div.of(engBar, engBody);
            engGroup.modifier(new Modifier()
                    .attribute("data-engine-group", engName)
                    .style("padding: 0.75rem 0.9rem; margin-bottom: 0.85rem; border-radius: 8px; background: rgba(255,255,255,0.02); border: 1px solid var(--border-subtle); border-left: 3px solid " + eColor + ";"));
            modalChildren.add(engGroup);
        }

        Div card = Div.of(modalChildren.toArray(new Widget[0]));
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildDatabaseIndexesSection(JettraStorePlugin plugin, String targetDb, String selectedBucket) {
        List<EngineBucketInfo> buckets = plugin.getBuckets(targetDb);
        String activeBucketName = (selectedBucket != null && buckets.stream().anyMatch(b -> b.getBucketName().equalsIgnoreCase(selectedBucket)))
                ? selectedBucket
                : (buckets.isEmpty() ? "" : buckets.get(0).getBucketName());

        Header header = Header.of(3, "⚡ Administración de Índices en [" + targetDb + "]");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: #00d4ff; margin: 0;"));
        Widget headerBar = Div.of(
                header,
                FluxUi.linkButton("/databases?db=" + targetDb, "✖ Cerrar", "app-btn-secondary", null)
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 0.85rem; flex-wrap: wrap;"));

        Widget strictRuleBanner = Div.of(Text.of(
                "🔒 Regla estricta: Los índices nunca se asignan directamente a un Engine. Seleccione un Bucket concreto dentro de su Engine para consultar, añadir, editar o eliminar índices."
        )).modifier(new Modifier()
                .attribute("data-strict-bucket-index-rule", "true")
                .style("background: rgba(245, 158, 11, 0.12); border: 1px solid rgba(245, 158, 11, 0.45); color: #fbbf24; padding: 0.6rem 0.85rem; border-radius: 8px; font-size: 0.8rem; font-weight: 600; margin-bottom: 1rem;"));

        List<Widget> children = new ArrayList<>();
        children.add(headerBar);
        children.add(strictRuleBanner);

        // Agrupar Buckets por Engine
        Map<String, List<EngineBucketInfo>> byEngine = new LinkedHashMap<>();
        for (EngineBucketInfo b : buckets) {
            String engKey = b.getEngineType() != null ? b.getEngineType().toUpperCase() : "DOCUMENT";
            byEngine.computeIfAbsent(engKey, k -> new ArrayList<>()).add(b);
        }

        EngineBucketInfo activeBucket = null;
        for (Map.Entry<String, List<EngineBucketInfo>> entry : byEngine.entrySet()) {
            String engName = entry.getKey();
            List<EngineBucketInfo> engBuckets = entry.getValue();
            String eColor = engineColor(engName);

            List<Widget> bucketChips = new ArrayList<>();
            for (EngineBucketInfo b : engBuckets) {
                boolean isCurrent = b.getBucketName().equalsIgnoreCase(activeBucketName);
                if (isCurrent) activeBucket = b;
                boolean isDefined = "DEFINED".equalsIgnoreCase(b.getStructureType()) && !b.getAttributes().isEmpty();
                Link bBtn = Link.of(
                        "/databases?db=" + targetDb + "&manage_indexes_db=" + targetDb + "&index_bucket=" + b.getBucketName(),
                        (isCurrent ? "✓ 📦 " : "📦 ") + b.getBucketName() + " [" + (isDefined ? "DEFINED" : "NONE") + " • " + b.getIndexes().size() + " idx]"
                );
                bBtn.modifier(new Modifier()
                        .cssClass(isCurrent ? "app-btn-primary" : "app-btn-secondary")
                        .attribute("data-index-bucket", b.getBucketName())
                        .style("padding: 0.32rem 0.7rem; font-size: 0.78rem; text-decoration: none;"));
                bucketChips.add(bBtn);
            }

            Div engBox = Div.of(
                    Div.of(
                            FluxUi.pillBadge("⚙️ " + engName, eColor),
                            FluxUi.coloredSpan(" Buckets disponibles para indexar:", "font-size: 0.76rem; color: var(--text-muted); margin-left: 0.45rem;")
                    ).modifier(new Modifier().style("margin-bottom: 0.45rem;")),
                    Div.of(bucketChips.toArray(new Widget[0]))
                            .modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.45rem;"))
            );
            engBox.modifier(new Modifier()
                    .attribute("data-index-engine", engName)
                    .style("padding: 0.65rem 0.85rem; margin-bottom: 0.65rem; border-radius: 8px; background: var(--bg-subtle); border-left: 3px solid " + eColor + ";"));
            children.add(engBox);
        }

        if (activeBucket != null) {
            boolean isDefined = "DEFINED".equalsIgnoreCase(activeBucket.getStructureType()) && !activeBucket.getAttributes().isEmpty();
            Header idxHeader = Header.of(4, "📋 Índices del Bucket [" + activeBucket.getBucketName() + "] • Engine: " + activeBucket.getEngineType()
                    + " • Estructura: " + (isDefined ? "DEFINED" : "NONE"));
            idxHeader.modifier(new Modifier().style("font-size: 1rem; color: #ffd700; margin: 0.85rem 0 0.55rem 0;"));
            children.add(idxHeader);

            List<JettraStorePlugin.IndexableFieldInfo> detectedFields = plugin.detectIndexableFields(activeBucket);
            List<Widget> numericBadges = new ArrayList<>();
            boolean hasAutoField = false;
            boolean hasNumericField = false;
            List<String> fieldOptions = new ArrayList<>();
            for (var f : detectedFields) {
                fieldOptions.add(f.fieldName());
                if (f.autoIncrement()) {
                    hasAutoField = true;
                    numericBadges.add(FluxUi.pillBadge("🔢 " + f.fieldName() + " (" + f.dataType() + " • AUTO_INCREMENT)", "#f59e0b"));
                } else if (f.numeric()) {
                    hasNumericField = true;
                    numericBadges.add(FluxUi.pillBadge("#️⃣ " + f.fieldName() + " (" + f.dataType() + " • NUMERIC)", "#38bdf8"));
                }
            }
            if (!numericBadges.isEmpty()) {
                List<Widget> stripItems = new ArrayList<>();
                stripItems.add(FluxUi.coloredSpan("Campos numéricos / autoincrementables detectados:",
                        "font-size: 0.76rem; color: var(--text-muted); font-weight: 700;"));
                stripItems.addAll(numericBadges);
                Div strip = Div.of(stripItems.toArray(new Widget[0]));
                strip.modifier(new Modifier()
                        .attribute("data-detected-numeric-fields", "true")
                        .style("display: flex; align-items: center; gap: 0.45rem; flex-wrap: wrap; background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.25); border-radius: 8px; padding: 0.55rem 0.75rem; margin-bottom: 0.85rem;"));
                children.add(strip);
            }

            List<String> indexTypes = List.of(
                    "BTREE", "HASH", "NUMERIC", "AUTO_INCREMENT", "NUMBER", "SEQUENTIAL", "FULLTEXT",
                    "VECTOR_HNSW", "GRAPH_ADJACENCY", "TIMESERIES_BRIN", "SPATIAL_R_TREE"
            );

            // Formulario de Edición de Índice si se seleccionó edit_index
            EngineIndexInfo editingIdx = (editIndexName != null && !editIndexName.isBlank())
                    ? activeBucket.getIndexes().stream().filter(i -> i.getName().equalsIgnoreCase(editIndexName)).findFirst().orElse(null)
                    : null;
            if (editingIdx != null) {
                Form editIdxForm = Form.of(
                        FluxUi.hidden("action", "edit_index"),
                        FluxUi.hidden("db", targetDb),
                        FluxUi.hidden("bucket", activeBucket.getBucketName()),
                        FluxUi.hidden("originalIndexName", editingIdx.getName()),
                        FluxUi.fieldColumn("Nombre del Índice", FluxUi.textInput("indexName", editingIdx.getName(), "idx_campo", true), "flex: 1.1; min-width: 150px;"),
                        FluxUi.fieldColumn("Campos", FluxUi.textInput("fields", editingIdx.getFields(), "campo1, campo2", true), "flex: 1.5; min-width: 170px;"),
                        FluxUi.fieldColumn("Tipo de Índice", FluxUi.dropdown("indexType", indexTypes, editingIdx.getType()), "flex: 1.1; min-width: 145px;"),
                        FluxUi.fieldColumn("Único", FluxUi.dropdown("unique", List.of("NO", "SI"), editingIdx.isUnique() ? "SI" : "NO"), "flex: 0.7; min-width: 90px;"),
                        Div.of(
                                FluxUi.submitButton("💾 Guardar Índice", "app-btn-primary", "height: 38px;"),
                                FluxUi.linkButton("/databases?db=" + targetDb + "&manage_indexes_db=" + targetDb + "&index_bucket=" + activeBucket.getBucketName(),
                                        "Cancelar", "app-btn-secondary", "height: 38px; display:inline-flex; align-items:center;")
                        ).modifier(new Modifier().style("display: flex; gap: 0.45rem;"))
                ).action("/databases").method("POST");
                editIdxForm.modifier(new Modifier()
                        .attribute("data-edit-index-form", editingIdx.getName())
                        .style("display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end; background: rgba(255, 215, 0, 0.08); border: 1px solid #ffd700; padding: 0.9rem; border-radius: 8px; margin-bottom: 1rem;"));
                children.add(editIdxForm);
            }

            String defaultIdxType = hasAutoField ? "AUTO_INCREMENT" : (hasNumericField ? "NUMERIC" : "BTREE");
            Widget fieldsControl = fieldOptions.isEmpty()
                    ? FluxUi.textInput("fields", "", "campo1, campo2", true)
                    : Div.of(
                            FluxUi.dropdown("fieldSelect", fieldOptions, fieldOptions.get(0)),
                            FluxUi.textInput("fields", "", "o campo(s) personalizados", false)
                      ).modifier(new Modifier().style("display: flex; gap: 0.4rem; align-items: center;"));

            Form addIndexForm = Form.of(
                    FluxUi.hidden("action", "add_index"),
                    FluxUi.hidden("db", targetDb),
                    FluxUi.hidden("bucket", activeBucket.getBucketName()),
                    FluxUi.fieldColumn("Nombre del Índice", FluxUi.textInput("indexName", "", "idx_campo", true), "flex: 1.1; min-width: 150px;"),
                    FluxUi.fieldColumn("Campos del Bucket", fieldsControl, "flex: 1.7; min-width: 220px;"),
                    FluxUi.fieldColumn("Tipo de Índice", FluxUi.dropdown("indexType", indexTypes, defaultIdxType), "flex: 1.1; min-width: 145px;"),
                    FluxUi.fieldColumn("Único", FluxUi.dropdown("unique", List.of("NO", "SI"), hasAutoField ? "SI" : "NO"), "flex: 0.7; min-width: 90px;"),
                    Div.of(FluxUi.submitButton("➕ Crear Índice", "app-btn-primary", "height: 38px;"))
            ).action("/databases").method("POST");
            addIndexForm.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end; background: var(--bg-subtle); padding: 0.9rem; border-radius: 8px; margin-bottom: 1rem;"));
            children.add(addIndexForm);

            List<Widget> idxHeaders = List.of(
                    Text.of("Índice"),
                    Text.of("Campos"),
                    Text.of("Tipo"),
                    Text.of("Único"),
                    Text.of("Estado"),
                    Div.of(Text.of("Acciones")).modifier(new Modifier().style("text-align:right;"))
            );

            List<List<Widget>> idxRows = new ArrayList<>();
            for (EngineIndexInfo idx : activeBucket.getIndexes()) {
                Link editIdxLink = FluxUi.linkButton(
                        "/databases?db=" + targetDb + "&manage_indexes_db=" + targetDb + "&index_bucket=" + activeBucket.getBucketName() + "&edit_index=" + idx.getName(),
                        "✏️ Editar", "app-btn-secondary", "padding:0.25rem 0.55rem; font-size:0.75rem; margin-right:5px;");

                Form rebuildForm = Form.of(
                        FluxUi.hidden("action", "rebuild_index"),
                        FluxUi.hidden("db", targetDb),
                        FluxUi.hidden("bucket", activeBucket.getBucketName()),
                        FluxUi.hidden("indexName", idx.getName()),
                        FluxUi.submitButton("🔄 Reconstruir", "app-btn-secondary", "padding:0.25rem 0.6rem; font-size:0.75rem; margin-right:5px;")
                ).action("/databases").method("POST");
                rebuildForm.modifier(new Modifier().style("display:inline;"));

                Form delIdxForm = Form.of(
                        FluxUi.hidden("action", "delete_index"),
                        FluxUi.hidden("db", targetDb),
                        FluxUi.hidden("bucket", activeBucket.getBucketName()),
                        FluxUi.hidden("indexName", idx.getName()),
                        FluxUi.submitButton("🗑️", "app-btn-danger", "padding:0.25rem 0.6rem; font-size:0.75rem;")
                ).action("/databases").method("POST");
                delIdxForm.modifier(new Modifier().style("display:inline;"));

                String idxTypeUpper = idx.getType() != null ? idx.getType().toUpperCase() : "BTREE";
                String badgeColor = ("AUTO_INCREMENT".equals(idxTypeUpper) || "SEQUENTIAL".equals(idxTypeUpper)) ? "#f59e0b"
                        : (("NUMERIC".equals(idxTypeUpper) || "NUMBER".equals(idxTypeUpper)) ? "#38bdf8" : "#00d4ff");

                idxRows.add(List.of(
                        FluxUi.coloredSpan(idx.getName(), "font-family:monospace; font-weight:700; color:#00d4ff;"),
                        FluxUi.coloredSpan(idx.getFields(), "font-family:monospace; color:#ffd700;"),
                        FluxUi.pillBadge(idxTypeUpper, badgeColor),
                        Text.of(idx.isUnique() ? "SÍ" : "NO"),
                        FluxUi.pillBadge(idx.getStatus(), "#10b981"),
                        Div.of(editIdxLink, rebuildForm, delIdxForm).modifier(new Modifier().style("text-align:right; white-space:nowrap;"))
                ));
            }

            Datatable idxTable = Datatable.ofWidgets(idxHeaders, idxRows);
            idxTable.modifier(new Modifier().cssClass("app-table"));
            children.add(idxTable);
        }

        Div card = Div.of(children.toArray(new Widget[0]));
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

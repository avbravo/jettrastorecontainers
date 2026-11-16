package io.jettra.store.explorer.pages.databases;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Form;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Modal;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineIndexInfo;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vista de Administración de Bases de Datos y Motores Multimodelo en JettraFlux.
 */
public class DatabasesFluxView extends FluxView {

    private final String selectedDb;
    private final String manageUsersDb;
    private final String manageIndexesDb;
    private final String manageIndexesBucket;
    private final boolean manageEngines;
    private final boolean isAdmin;
    private final String message;

    public DatabasesFluxView(String selectedDb, String message) {
        this(selectedDb, null, null, null, false, true, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, boolean isAdmin, String message) {
        this(selectedDb, manageUsersDb, null, null, false, isAdmin, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, boolean manageEngines, boolean isAdmin, String message) {
        this(selectedDb, manageUsersDb, null, null, manageEngines, isAdmin, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, String manageIndexesDb, String manageIndexesBucket,
                             boolean manageEngines, boolean isAdmin, String message) {
        this.selectedDb = selectedDb;
        this.manageUsersDb = manageUsersDb;
        this.manageIndexesDb = manageIndexesDb;
        this.manageIndexesBucket = manageIndexesBucket;
        this.manageEngines = manageEngines;
        this.isAdmin = isAdmin;
        this.message = message;
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        Map<String, List<EngineBucketInfo>> catalog = plugin.getDatabasesCatalog();
        List<String> dbNames = plugin.getDatabaseNames();

        String currentDb = (selectedDb != null && catalog.containsKey(selectedDb)) 
            ? selectedDb 
            : (dbNames.isEmpty() ? "example_factura_db" : dbNames.get(0));

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

        // 1. Mensaje Flash si existe
        Widget flashWidget = null;
        if (message != null && !message.isBlank()) {
            Div flash = Div.of(Text.of("ℹ️ " + message));
            flash.modifier(new Modifier()
                    .style("background: rgba(0, 212, 255, 0.15); border: 1px solid #00d4ff; color: #00d4ff; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
            flashWidget = flash;
        }

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
        if (manageEngines) {
            Modal enginesModal = Modal.of(buildEnginesSection(plugin, currentDb)).open(true);
            enginesModal.id("dbEnginesDialog");
            enginesSection = enginesModal;
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

    private Widget buildEnginesSection(JettraStorePlugin plugin, String currentDb) {
        List<EngineBucketInfo> buckets = plugin.getBuckets(currentDb);

        Header header = Header.of(3, "⚙️ Motores de Almacenamiento y Buckets en [" + currentDb + "]");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0;"));
        Widget headerBar = Div.of(
                header,
                FluxUi.linkButton("/databases?db=" + currentDb, "✖ Cerrar", "app-btn-secondary", null)
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 0.85rem; flex-wrap: wrap;"));

        // Formulario para Añadir Motor / Bucket a la BD seleccionada
        List<String> engineOptions = List.of(
                "DOCUMENT", "RELATIONAL", "KEY_VALUE", "GRAPH", "VECTOR",
                "TIMESERIES", "COLUMNAR", "GEOSPATIAL", "JAVA_RECORD", "OBJECT"
        );
        Form bucketForm = Form.of(
                FluxUi.hidden("action", "add_bucket"),
                FluxUi.hidden("db", currentDb),
                FluxUi.fieldColumn("Nombre del Bucket", FluxUi.textInput("bucketName", "", "AuditLog", true), "flex: 1.5; min-width: 160px;"),
                FluxUi.fieldColumn("Tipo de Motor", FluxUi.dropdown("engineType", engineOptions, "DOCUMENT"), "flex: 1.5; min-width: 160px;"),
                FluxUi.fieldColumn("Descripción del Almacén", FluxUi.textInput("description", "", "Colección de logs de auditoría transaccional", false), "flex: 2; min-width: 200px;"),
                Div.of(FluxUi.submitButton("➕ Añadir Motor", "app-btn-primary", "height: 38px;"))
        ).action("/databases").method("POST");
        bucketForm.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;"));

        Widget addBucketForm = Div.of(
                Div.of(Text.of("➕ Añadir Nuevo Motor / Bucket a " + currentDb))
                        .modifier(new Modifier().style("font-weight: 700; font-size: 0.9rem; margin-bottom: 0.75rem; color: #00d4ff;")),
                bucketForm
        ).modifier(new Modifier().style("background: var(--bg-subtle); padding: 1rem; border-radius: 8px; margin-bottom: 1.25rem;"));

        List<Widget> bucketHeaders = List.of(
            Text.of("Bucket / Almacén"),
            Text.of("Tipo de Motor"),
            Text.of("Objetos"),
            Text.of("Capacidad"),
            Text.of("Índices Activos"),
            Text.of("Descripción"),
            Text.of("Acciones")
        );

        List<List<Widget>> bucketRows = new ArrayList<>();
        for (EngineBucketInfo b : buckets) {
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

            Widget idxCell;
            if (b.getIndexes().isEmpty()) {
                idxCell = FluxUi.coloredSpan("Sin índices", "color:var(--text-muted); font-size:0.75rem;");
            } else {
                List<Widget> idxChips = new ArrayList<>();
                for (EngineIndexInfo idx : b.getIndexes()) {
                    idxChips.add(FluxUi.coloredSpan(idx.getName() + " [" + idx.getType() + "]",
                            "background:rgba(255,255,255,0.06); padding:2px 5px; border-radius:4px; font-size:0.75rem; margin-right:4px; font-family:monospace;"));
                }
                idxCell = Div.of(idxChips.toArray(new Widget[0]));
            }

            List<Widget> bucketActions = new ArrayList<>();
            bucketActions.add(FluxUi.linkButton("/explorer?db=" + currentDb + "&bucket=" + b.getBucketName(), "📦 Registros", "app-btn-primary", "margin-right:6px;"));
            if (buckets.size() > 1) {
                Widget delBucket = FluxUi.linkButton("/databases?action=delete_bucket&db=" + currentDb + "&bucket=" + b.getBucketName(), "🗑️", "app-btn-danger", null);
                delBucket.modifier(new Modifier().cssClass("app-btn-danger")
                        .attribute("onclick", "return confirm('¿Seguro de eliminar el bucket " + b.getBucketName() + "?');"));
                bucketActions.add(delBucket);
            }

            bucketRows.add(List.of(
                FluxUi.coloredSpan(b.getBucketName(), "font-weight:700;"),
                FluxUi.pillBadge(b.getEngineType(), engineColor),
                Text.of(String.format("%,d", b.getTotalObjects())),
                Text.of(b.getFormattedSize()),
                idxCell,
                FluxUi.coloredSpan(b.getDescription(), "color:var(--text-muted); font-size:0.8rem;"),
                Div.of(bucketActions.toArray(new Widget[0]))
            ));
        }

        Datatable bucketDatatable = Datatable.ofWidgets(bucketHeaders, bucketRows);
        bucketDatatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(headerBar, addBucketForm, bucketDatatable);
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
        ).modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap;"));

        List<Widget> bucketHeaders = List.of(
                Text.of("Bucket / Almacén"),
                Text.of("Motor"),
                Text.of("Índices"),
                Div.of(Text.of("Acción")).modifier(new Modifier().style("text-align:right;"))
        );

        List<List<Widget>> bucketRows = new ArrayList<>();
        EngineBucketInfo activeBucket = null;
        for (EngineBucketInfo b : buckets) {
            boolean isCurrent = b.getBucketName().equalsIgnoreCase(activeBucketName);
            if (isCurrent) activeBucket = b;

            Widget bucketNameCell = Div.of(
                    FluxUi.coloredSpan("📦 " + b.getBucketName(), isCurrent ? "color:#ffd700; font-weight:800;" : "font-weight:700;")
            );
            Widget engineCell = FluxUi.pillBadge(b.getEngineType(), "#00d4ff");
            Widget countCell = Text.of(b.getIndexes().size() + " índice(s)");
            Widget manageBtn = FluxUi.linkButton(
                    "/databases?db=" + targetDb + "&manage_indexes_db=" + targetDb + "&index_bucket=" + b.getBucketName(),
                    isCurrent ? "✓ Seleccionado" : "⚡ Administrar Índices",
                    isCurrent ? "app-btn-primary" : "app-btn-secondary",
                    "padding: 0.3rem 0.7rem; font-size: 0.78rem;");

            bucketRows.add(List.of(bucketNameCell, engineCell, countCell, Div.of(manageBtn).modifier(new Modifier().style("text-align:right;"))));
        }

        Datatable bucketsTable = Datatable.ofWidgets(bucketHeaders, bucketRows);
        bucketsTable.modifier(new Modifier().cssClass("app-table").style("margin-bottom: 1.25rem;"));

        List<Widget> children = new ArrayList<>();
        children.add(headerBar);
        children.add(FluxUi.coloredSpan("Buckets creados en " + targetDb + " (seleccione un bucket para administrar sus índices):",
                "font-size: 0.82rem; color: var(--text-muted); display: block; margin-bottom: 0.5rem;"));
        children.add(bucketsTable);

        if (activeBucket != null) {
            Header idxHeader = Header.of(4, "📋 Índices del Bucket [" + activeBucket.getBucketName() + "] • " + activeBucket.getEngineType());
            idxHeader.modifier(new Modifier().style("font-size: 1rem; color: #ffd700; margin: 0 0 0.75rem 0;"));
            children.add(idxHeader);

            List<String> indexTypes = List.of(
                    "BTREE", "HASH", "NUMBER", "SEQUENTIAL", "FULLTEXT",
                    "VECTOR_HNSW", "GRAPH_ADJACENCY", "TIMESERIES_BRIN", "SPATIAL_R_TREE"
            );
            Form addIndexForm = Form.of(
                    FluxUi.hidden("action", "add_index"),
                    FluxUi.hidden("db", targetDb),
                    FluxUi.hidden("bucket", activeBucket.getBucketName()),
                    FluxUi.fieldColumn("Nombre del Índice", FluxUi.textInput("indexName", "", "idx_campo", true), "flex: 1.2; min-width: 150px;"),
                    FluxUi.fieldColumn("Campos", FluxUi.textInput("fields", "", "campo1, campo2", true), "flex: 1.5; min-width: 170px;"),
                    FluxUi.fieldColumn("Tipo de Índice", FluxUi.dropdown("indexType", indexTypes, "BTREE"), "flex: 1.1; min-width: 140px;"),
                    FluxUi.fieldColumn("Único", FluxUi.dropdown("unique", List.of("NO", "SI"), "NO"), "flex: 0.7; min-width: 90px;"),
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

                idxRows.add(List.of(
                        FluxUi.coloredSpan(idx.getName(), "font-family:monospace; font-weight:700; color:#00d4ff;"),
                        FluxUi.coloredSpan(idx.getFields(), "font-family:monospace; color:#ffd700;"),
                        FluxUi.pillBadge(idx.getType(), "#38bdf8"),
                        Text.of(idx.isUnique() ? "SÍ" : "NO"),
                        FluxUi.pillBadge(idx.getStatus(), "#10b981"),
                        Div.of(rebuildForm, delIdxForm).modifier(new Modifier().style("text-align:right; white-space:nowrap;"))
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

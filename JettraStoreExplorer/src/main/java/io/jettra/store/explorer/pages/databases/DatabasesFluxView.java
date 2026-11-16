package io.jettra.store.explorer.pages.databases;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Datatable;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Text;
import io.jettra.store.explorer.model.EngineBucketInfo;
import io.jettra.store.explorer.model.EngineIndexInfo;
import io.jettra.store.explorer.model.JettraDatabaseRole;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Vista de Administración de Bases de Datos y Motores Multimodelo en JettraFlux.
 */
public class DatabasesFluxView extends FluxView {

    private final String selectedDb;
    private final String manageUsersDb;
    private final boolean isAdmin;
    private final String message;

    public DatabasesFluxView(String selectedDb, String message) {
        this(selectedDb, null, true, message);
    }

    public DatabasesFluxView(String selectedDb, String manageUsersDb, boolean isAdmin, String message) {
        this.selectedDb = selectedDb;
        this.manageUsersDb = manageUsersDb;
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

        // 4.5 Panel de Administración de Usuarios por Base de Datos (Solo ADMIN)
        Widget dbUsersSection = null;
        if (isAdmin && manageUsersDb != null && !manageUsersDb.isBlank() && catalog.containsKey(manageUsersDb)) {
            dbUsersSection = buildDatabaseUsersSection(plugin, manageUsersDb);
        }

        // 5. Sección Detallada de Motores / Buckets para la BD seleccionada
        Widget enginesSection = buildEnginesSection(plugin, currentDb);

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(kpiGrid);
        rootChildren.add(createDbCard);
        rootChildren.add(databasesTable);
        if (dbUsersSection != null) rootChildren.add(dbUsersSection);
        rootChildren.add(enginesSection);

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

        RawHtml formHtml = RawHtml.of("""
            <div style="font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.85rem;">
              ⚡ Toda base de datos creada habilita automáticamente soporte nativo para los <strong style="color: #00d4ff;">9 motores (engines) de JettraStore</strong>:
              <code>DOCUMENT</code>, <code>KEY_VALUE</code>, <code>COLUMNAR</code>, <code>TIMESERIES</code>, <code>GEOSPATIAL</code>, <code>GRAPH</code>, <code>VECTOR</code>, <code>JAVA_RECORD</code> y <code>OBJECT</code>.
            </div>
            <form method="POST" action="/databases" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
              <input type="hidden" name="action" value="create_db" />
              <div style="flex: 1.5; min-width: 220px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Nombre de la Base de Datos</label>
                <input class="app-input" type="text" name="dbName" placeholder="ecommerce_core_db" required />
              </div>
              <div style="flex: 2.5; min-width: 260px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Descripción</label>
                <input class="app-input" type="text" name="description" placeholder="Base de datos empresarial multimodelo para gestión de inventarios" />
              </div>
              <div>
                <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Crear Base de Datos</button>
              </div>
            </form>
        """);

        Div card = Div.of(header, formHtml);
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

            Widget dbCell = Div.of(
                RawHtml.of("<strong style='color:#00d4ff;'>📁 " + db + "</strong>" + (isSelected ? " <span style='color:#ffd700; font-size:0.75rem;'>[SELECCIONADA]</span>" : ""))
            );
            Widget bucketsCell = Text.of(buckets.size() + " motores");
            Widget objCell = Text.of(String.format("%,d", dbObjects));
            Widget sizeCell = Text.of(formatBytes(dbBytes));

            String manageUsersBtn = isAdmin
                ? "<a href='/databases?db=" + db + "&manage_users_db=" + db + "' class='app-btn-secondary' style='margin-right:6px;'>👥 Administrar Usuarios</a>"
                : "<span class='app-btn-secondary' style='margin-right:6px; opacity:0.45; cursor:not-allowed;' title='Opción habilitada únicamente para usuarios con rol ADMIN'>🔒 Administrar Usuarios</span>";
            String deleteBtn = !"system_metadata_db".equalsIgnoreCase(db)
                ? "<a href='/databases?action=delete_db&db=" + db + "' class='app-btn-danger' onclick=\"return confirm('¿Seguro de eliminar la base de datos " + db + "?');\">🗑️</a>"
                : "";
            String actionsHtml = "<div>"
                + "<a href='/databases?db=" + db + "' class='app-btn-secondary' style='margin-right:6px;'>⚙️ Administrar Motores</a>"
                + manageUsersBtn
                + "<a href='/explorer?db=" + db + "' class='app-btn-primary' style='margin-right:6px;'>📦 Ver Registros</a>"
                + deleteBtn
                + "</div>";
            Widget actionsCell = RawHtml.of(actionsHtml);

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

        String userOptions = allUsers.stream()
                .filter(u -> u.getGlobalRole() != JettraUser.GlobalRole.ADMIN)
                .map(u -> "<option value='" + escapeHtml(u.getUsername()) + "'>" + escapeHtml(u.getUsername()) + " (" + u.getGlobalRole().name() + ")</option>")
                .collect(Collectors.joining());

        long assignableCount = allUsers.stream()
                .filter(u -> u.getGlobalRole() != JettraUser.GlobalRole.ADMIN)
                .count();

        String addFormHtml = assignableCount > 0
                ? String.format("""
                    <form method="POST" action="/databases" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end; background: var(--bg-subtle); padding: 1rem; border-radius: 8px; margin-bottom: 1.1rem;">
                      <input type="hidden" name="action" value="add_db_user" />
                      <input type="hidden" name="db" value="%s" />
                      <div style="flex: 1.5; min-width: 180px;">
                        <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Usuario</label>
                        <select class="app-input" name="username">%s</select>
                      </div>
                      <div style="flex: 1.2; min-width: 160px;">
                        <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Rol en %s</label>
                        <select class="app-input" name="role">
                          <option value="READ_WRITE">READ_WRITE</option>
                          <option value="READ_ONLY">READ_ONLY</option>
                          <option value="ADMIN">ADMIN</option>
                        </select>
                      </div>
                      <div style="display:flex; gap:0.5rem;">
                        <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Añadir Usuario a la BD</button>
                        <a href="/databases?db=%s" class="app-btn-secondary" style="height: 38px; display:inline-flex; align-items:center;">Cerrar</a>
                      </div>
                    </form>
                """, escapeHtml(targetDb), userOptions, escapeHtml(targetDb), escapeHtml(targetDb))
                : String.format("""
                    <div style="display:flex; justify-content:space-between; align-items:center; background:var(--bg-subtle); padding:0.85rem 1rem; border-radius:8px; margin-bottom:1.1rem;">
                      <span style="font-size:0.85rem; color:var(--text-muted);">No hay usuarios adicionales no-admin registrados. Crea usuarios en <a href="/users" style="color:#00d4ff;">/users</a> para asignarlos a esta base de datos.</span>
                      <a href="/databases?db=%s" class="app-btn-secondary">Cerrar</a>
                    </div>
                """, escapeHtml(targetDb));

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
                actionCell = Div.of(RawHtml.of("<span style='color:var(--text-muted); font-size:0.75rem;'>Superadmin Global</span>"));
            } else {
                actionCell = Div.of(RawHtml.of(String.format("""
                    <form method='POST' action='/databases' style='display:inline;'>
                      <input type='hidden' name='action' value='remove_db_user'/>
                      <input type='hidden' name='db' value='%s'/>
                      <input type='hidden' name='username' value='%s'/>
                      <button type='submit' class='app-btn-danger'>➖ Remover</button>
                    </form>
                """, escapeHtml(targetDb), escapeHtml(u.getUsername()))));
            }

            userRows.add(List.of(
                Div.of(RawHtml.of("<strong style='color:#ffd700;'>👤 " + escapeHtml(u.getUsername()) + "</strong>")),
                Div.of(RawHtml.of("<span style='color:#38bdf8; font-weight:700; font-size:0.78rem;'>" + u.getGlobalRole().name() + "</span>")),
                Div.of(RawHtml.of("<span style='color:#10b981; font-weight:700; font-size:0.8rem;'>" + dbRole.name() + "</span>")),
                Div.of(actionCell).modifier(new Modifier().style("text-align:right;"))
            ));
        }

        Datatable userDatatable = Datatable.ofWidgets(userTableHeaders, userRows);
        userDatatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(header, RawHtml.of(addFormHtml), userDatatable);
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
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        // Formulario para Añadir Motor / Bucket a la BD seleccionada
        RawHtml addBucketForm = RawHtml.of(String.format("""
            <div style="background: var(--bg-subtle); padding: 1rem; border-radius: 8px; margin-bottom: 1.25rem;">
              <div style="font-weight: 700; font-size: 0.9rem; margin-bottom: 0.75rem; color: #00d4ff;">➕ Añadir Nuevo Motor / Bucket a %s</div>
              <form method="POST" action="/databases" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
                <input type="hidden" name="action" value="add_bucket" />
                <input type="hidden" name="db" value="%s" />
                <div style="flex: 1.5; min-width: 160px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Nombre del Bucket</label>
                  <input class="app-input" type="text" name="bucketName" placeholder="AuditLog" required />
                </div>
                <div style="flex: 1.5; min-width: 160px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Tipo de Motor</label>
                  <select class="app-input" name="engineType">
                    <option value="DOCUMENT">DOCUMENT (Colección JSON)</option>
                    <option value="RELATIONAL">RELATIONAL (Tabular)</option>
                    <option value="KEY_VALUE">KEY_VALUE (Cache Clave-Valor)</option>
                    <option value="GRAPH">GRAPH (Grafo de Conocimiento)</option>
                    <option value="VECTOR">VECTOR (HNSW Embeddings)</option>
                    <option value="TIMESERIES">TIMESERIES (Serie Temporal)</option>
                    <option value="COLUMNAR">COLUMNAR (Columnar)</option>
                    <option value="GEOSPATIAL">GEOSPATIAL (GeoJSON & R-Tree)</option>
                    <option value="JAVA_RECORD">JAVA_RECORD (Java Record Inmutable)</option>
                    <option value="OBJECT">OBJECT (Objeto / Entidad POJO)</option>
                  </select>
                </div>
                <div style="flex: 2; min-width: 200px;">
                  <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Descripción del Almacén</label>
                  <input class="app-input" type="text" name="description" placeholder="Colección de logs de auditoría transaccional" />
                </div>
                <div>
                  <button type="submit" class="app-btn-primary" style="height: 38px;">➕ Añadir Motor</button>
                </div>
              </form>
            </div>
        """, currentDb, currentDb));

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

            String indexesStr = b.getIndexes().isEmpty()
                ? "<span style='color:var(--text-muted); font-size:0.75rem;'>Sin índices</span>"
                : b.getIndexes().stream()
                    .map(idx -> "<code style='background:rgba(255,255,255,0.06); padding:2px 5px; border-radius:4px; font-size:0.75rem; margin-right:4px;'>" + escapeHtml(idx.getName()) + " [" + escapeHtml(idx.getType()) + "]</code>")
                    .collect(Collectors.joining());

            String actionHtml = "<a href='/explorer?db=" + escapeHtml(currentDb) + "&bucket=" + escapeHtml(b.getBucketName()) + "' class='app-btn-primary' style='margin-right:6px;'>📦 Registros</a>"
                + (buckets.size() > 1 ? "<a href='/databases?action=delete_bucket&db=" + escapeHtml(currentDb) + "&bucket=" + escapeHtml(b.getBucketName()) + "' class='app-btn-danger' onclick=\"return confirm('¿Seguro de eliminar el bucket " + escapeHtml(b.getBucketName()) + "?');\">🗑️</a>" : "");

            bucketRows.add(List.of(
                Div.of(RawHtml.of("<strong>" + escapeHtml(b.getBucketName()) + "</strong>")),
                Div.of(RawHtml.of("<span style='background:rgba(255,255,255,0.08); border:1px solid " + engineColor + "; color:" + engineColor + "; padding:3px 8px; border-radius:9999px; font-weight:700; font-size:0.75rem;'>" + escapeHtml(b.getEngineType()) + "</span>")),
                Text.of(String.format("%,d", b.getTotalObjects())),
                Text.of(b.getFormattedSize()),
                Div.of(RawHtml.of(indexesStr)),
                Div.of(RawHtml.of("<span style='color:var(--text-muted); font-size:0.8rem;'>" + escapeHtml(b.getDescription()) + "</span>")),
                Div.of(RawHtml.of(actionHtml))
            ));
        }

        Datatable bucketDatatable = Datatable.ofWidgets(bucketHeaders, bucketRows);
        bucketDatatable.modifier(new Modifier().cssClass("app-table"));

        Div card = Div.of(header, addBucketForm, bucketDatatable);
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

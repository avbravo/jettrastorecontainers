package io.jettra.store.explorer.pages.databases;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
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
import java.util.List;
import java.util.Map;

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

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Base de Datos</th><th>Motores / Buckets</th><th>Total Registros</th><th>Espacio en Memoria/Disco</th><th>Acciones</th></tr></thead>");
        table.append("<tbody>");

        for (Map.Entry<String, List<EngineBucketInfo>> entry : catalog.entrySet()) {
            String db = entry.getKey();
            List<EngineBucketInfo> buckets = entry.getValue();
            boolean isSelected = db.equalsIgnoreCase(currentDb);

            long dbObjects = buckets.stream().mapToLong(EngineBucketInfo::getTotalObjects).sum();
            long dbBytes = buckets.stream().mapToLong(EngineBucketInfo::getTotalBytes).sum();

            table.append("<tr style='").append(isSelected ? "background: rgba(0, 212, 255, 0.08); font-weight: 600;" : "").append("'>");
            table.append("<td><strong style='color:#00d4ff;'>📁 ").append(db).append("</strong>").append(isSelected ? " <span style='color:#ffd700; font-size:0.75rem;'>[SELECCIONADA]</span>" : "").append("</td>");
            table.append("<td>").append(buckets.size()).append(" motores</td>");
            table.append("<td>").append(String.format("%,d", dbObjects)).append("</td>");
            table.append("<td>").append(formatBytes(dbBytes)).append("</td>");
            table.append("<td>");
            table.append("<a href='/databases?db=").append(db).append("' class='app-btn-secondary' style='margin-right:6px;'>⚙️ Administrar Motores</a>");
            if (isAdmin) {
                table.append("<a href='/databases?db=").append(db).append("&manage_users_db=").append(db).append("' class='app-btn-secondary' style='margin-right:6px;'>👥 Administrar Usuarios</a>");
            } else {
                table.append("<span class='app-btn-secondary' style='margin-right:6px; opacity:0.45; cursor:not-allowed;' title='Opción habilitada únicamente para usuarios con rol ADMIN'>🔒 Administrar Usuarios</span>");
            }
            table.append("<a href='/explorer?db=").append(db).append("' class='app-btn-primary' style='margin-right:6px;'>📦 Ver Registros</a>");
            if (!"system_metadata_db".equalsIgnoreCase(db)) {
                table.append("<a href='/databases?action=delete_db&db=").append(db).append("' class='app-btn-danger' onclick=\"return confirm('¿Seguro de eliminar la base de datos ").append(db).append("?');\">🗑️</a>");
            }
            table.append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(header, RawHtml.of(table.toString()));
        card.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 2rem;"));
        return card;
    }

    private Widget buildDatabaseUsersSection(JettraStorePlugin plugin, String targetDb) {
        List<JettraUser> allUsers = plugin.getUsers();
        Header header = Header.of(3, "👥 Administración de Usuarios de la Base de Datos [" + targetDb + "]");
        header.modifier(new Modifier().style("font-size: 1.1rem; color: #ffd700; margin: 0 0 0.75rem 0;"));

        StringBuilder userOptions = new StringBuilder();
        int assignableCount = 0;
        for (JettraUser u : allUsers) {
            if (u.getGlobalRole() != JettraUser.GlobalRole.ADMIN) {
                userOptions.append("<option value='").append(escapeHtml(u.getUsername())).append("'>")
                           .append(escapeHtml(u.getUsername())).append(" (").append(u.getGlobalRole().name()).append(")</option>");
                assignableCount++;
            }
        }

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
                """, escapeHtml(targetDb), userOptions.toString(), escapeHtml(targetDb), escapeHtml(targetDb))
                : String.format("""
                    <div style="display:flex; justify-content:space-between; align-items:center; background:var(--bg-subtle); padding:0.85rem 1rem; border-radius:8px; margin-bottom:1.1rem;">
                      <span style="font-size:0.85rem; color:var(--text-muted);">No hay usuarios adicionales no-admin registrados. Crea usuarios en <a href="/users" style="color:#00d4ff;">/users</a> para asignarlos a esta base de datos.</span>
                      <a href="/databases?db=%s" class="app-btn-secondary">Cerrar</a>
                    </div>
                """, escapeHtml(targetDb));

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Usuario</th><th>Rol Global</th><th>Rol en ").append(escapeHtml(targetDb)).append("</th><th style='text-align:right;'>Acción</th></tr></thead><tbody>");

        for (JettraUser u : allUsers) {
            JettraDatabaseRole dbRole = u.getRoleForDatabase(targetDb);
            if (dbRole == JettraDatabaseRole.NONE && u.getGlobalRole() != JettraUser.GlobalRole.ADMIN) {
                continue;
            }
            table.append("<tr>");
            table.append("<td><strong style='color:#ffd700;'>👤 ").append(escapeHtml(u.getUsername())).append("</strong></td>");
            table.append("<td><span style='color:#38bdf8; font-weight:700; font-size:0.78rem;'>").append(u.getGlobalRole().name()).append("</span></td>");
            table.append("<td><span style='color:#10b981; font-weight:700; font-size:0.8rem;'>").append(dbRole.name()).append("</span></td>");
            table.append("<td style='text-align:right;'>");
            if (u.getGlobalRole() == JettraUser.GlobalRole.ADMIN) {
                table.append("<span style='color:var(--text-muted); font-size:0.75rem;'>Superadmin Global</span>");
            } else {
                table.append("<form method='POST' action='/databases' style='display:inline;'>")
                     .append("<input type='hidden' name='action' value='remove_db_user'/>")
                     .append("<input type='hidden' name='db' value='").append(escapeHtml(targetDb)).append("'/>")
                     .append("<input type='hidden' name='username' value='").append(escapeHtml(u.getUsername())).append("'/>")
                     .append("<button type='submit' class='app-btn-danger'>➖ Remover</button></form>");
            }
            table.append("</td></tr>");
        }
        table.append("</tbody></table>");

        Div card = Div.of(header, RawHtml.of(addFormHtml), RawHtml.of(table.toString()));
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

        StringBuilder table = new StringBuilder();
        table.append("<table class=\"app-table\">");
        table.append("<thead><tr><th>Bucket / Almacén</th><th>Tipo de Motor</th><th>Objetos</th><th>Capacidad</th><th>Índices Activos</th><th>Descripción</th><th>Acciones</th></tr></thead>");
        table.append("<tbody>");

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

            StringBuilder indexesStr = new StringBuilder();
            if (b.getIndexes().isEmpty()) {
                indexesStr.append("<span style='color:var(--text-muted); font-size:0.75rem;'>Sin índices</span>");
            } else {
                for (EngineIndexInfo idx : b.getIndexes()) {
                    indexesStr.append("<code style='background:rgba(255,255,255,0.06); padding:2px 5px; border-radius:4px; font-size:0.75rem; margin-right:4px;'>")
                              .append(idx.getName()).append(" [").append(idx.getType()).append("]</code>");
                }
            }

            table.append("<tr>");
            table.append("<td><strong>").append(b.getBucketName()).append("</strong></td>");
            table.append("<td><span style='background:rgba(255,255,255,0.08); border:1px solid ").append(engineColor)
                 .append("; color:").append(engineColor).append("; padding:3px 8px; border-radius:9999px; font-weight:700; font-size:0.75rem;'>")
                 .append(b.getEngineType()).append("</span></td>");
            table.append("<td>").append(String.format("%,d", b.getTotalObjects())).append("</td>");
            table.append("<td>").append(b.getFormattedSize()).append("</td>");
            table.append("<td>").append(indexesStr).append("</td>");
            table.append("<td style='color:var(--text-muted); font-size:0.8rem;'>").append(b.getDescription()).append("</td>");
            table.append("<td>");
            table.append("<a href='/explorer?db=").append(currentDb).append("&bucket=").append(b.getBucketName()).append("' class='app-btn-primary' style='margin-right:6px;'>📦 Registros</a>");
            if (buckets.size() > 1) {
                table.append("<a href='/databases?action=delete_bucket&db=").append(currentDb).append("&bucket=").append(b.getBucketName()).append("' class='app-btn-danger' onclick=\"return confirm('¿Seguro de eliminar el bucket ").append(b.getBucketName()).append("?');\">🗑️</a>");
            }
            table.append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");

        Div card = Div.of(header, addBucketForm, RawHtml.of(table.toString()));
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

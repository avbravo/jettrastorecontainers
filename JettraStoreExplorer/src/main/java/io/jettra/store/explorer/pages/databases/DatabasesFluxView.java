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
import io.jettra.store.explorer.plugin.JettraStorePlugin;
import java.util.List;
import java.util.Map;

/**
 * Vista de Administración de Bases de Datos y Motores Multimodelo en JettraFlux.
 */
public class DatabasesFluxView extends FluxView {

    private final String selectedDb;
    private final String message;

    public DatabasesFluxView(String selectedDb, String message) {
        this.selectedDb = selectedDb;
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

        // 5. Sección Detallada de Motores / Buckets para la BD seleccionada
        Widget enginesSection = buildEnginesSection(plugin, currentDb);

        List<Widget> rootChildren = new java.util.ArrayList<>();
        if (flashWidget != null) rootChildren.add(flashWidget);
        rootChildren.add(kpiGrid);
        rootChildren.add(createDbCard);
        rootChildren.add(databasesTable);
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
        header.modifier(new Modifier().style("font-size: 1.1rem; color: var(--text-main); margin: 0 0 0.85rem 0;"));

        RawHtml formHtml = RawHtml.of("""
            <form method="POST" action="/databases" style="display: flex; flex-wrap: wrap; gap: 0.85rem; align-items: flex-end;">
              <input type="hidden" name="action" value="create_db" />
              <div style="flex: 1.5; min-width: 180px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Nombre de la Base de Datos</label>
                <input class="app-input" type="text" name="dbName" placeholder="ecommerce_core_db" required />
              </div>
              <div style="flex: 1.2; min-width: 140px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Bucket / Colección Inicial</label>
                <input class="app-input" type="text" name="defaultBucket" placeholder="Productos" required />
              </div>
              <div style="flex: 1.2; min-width: 150px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Motor de Almacenamiento</label>
                <select class="app-input" name="engineType">
                  <option value="DOCUMENT">DOCUMENT (JSON / BSON)</option>
                  <option value="RELATIONAL">RELATIONAL (Tabular / SQL)</option>
                  <option value="KEY_VALUE">KEY_VALUE (Fast Memory Cache)</option>
                  <option value="GRAPH">GRAPH (Nodos & Vértices)</option>
                  <option value="VECTOR">VECTOR (HNSW Embeddings)</option>
                  <option value="TIMESERIES">TIMESERIES (IoT & Métricas)</option>
                  <option value="COLUMNAR">COLUMNAR (Analítica OLAP)</option>
                  <option value="GEOSPATIAL">GEOSPATIAL (GeoJSON & R-Tree Spatial)</option>
                  <option value="JAVA_RECORD">JAVA_RECORD (Java 17+ Records / DTOs)</option>
                  <option value="OBJECT">OBJECT (Java Serialized / POJO Entity)</option>
                </select>
              </div>
              <div style="flex: 2; min-width: 200px;">
                <label style="font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;">Descripción</label>
                <input class="app-input" type="text" name="description" placeholder="Base de datos empresarial para gestión de inventarios" />
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

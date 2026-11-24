package io.jettra.store.explorer.pages.migrator;

import io.jettra.driver.migrator.*;
import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.store.explorer.pages.template.FluxUi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vista reactiva JettraFlux para el Migrador de PostgreSQL a JettraStore y Generador de Proyecto Backend (RF-17).
 * Proporciona formulario de conexión, introspección de esquemas y tipos, selección interactiva de bases de datos
 * y esquemas de PostgreSQL, monitoreo en vivo de migración con preservación de precisión decimal (BigDecimal)
 * y fechas ISO-8601, y descarga del proyecto backend en .zip.
 */
public class MigratorFluxView extends FluxView {

    private final PostgresConnectionConfig config;
    private final List<String> discoveredDatabases;
    private final List<String> discoveredSchemas;
    private final PostgresIntrospectionResult introspection;
    private final MigrationProgress progress;
    private final String flashMessage;
    private final boolean isErrorFlash;
    private final boolean canDownloadZip;

    public MigratorFluxView(PostgresConnectionConfig config,
                            List<String> discoveredDatabases,
                            List<String> discoveredSchemas,
                            PostgresIntrospectionResult introspection,
                            MigrationProgress progress,
                            String flashMessage,
                            boolean isErrorFlash,
                            boolean canDownloadZip) {
        this.config = config != null ? config : new PostgresConnectionConfig();
        this.discoveredDatabases = discoveredDatabases != null ? discoveredDatabases : Collections.emptyList();
        this.discoveredSchemas = discoveredSchemas != null ? discoveredSchemas : Collections.emptyList();
        this.introspection = introspection;
        this.progress = progress;
        this.flashMessage = flashMessage;
        this.isErrorFlash = isErrorFlash;
        this.canDownloadZip = canDownloadZip;
    }

    public MigratorFluxView(PostgresConnectionConfig config, PostgresIntrospectionResult introspection,
                            MigrationProgress progress, String flashMessage, boolean isErrorFlash, boolean canDownloadZip) {
        this(config, Collections.emptyList(), Collections.emptyList(), introspection, progress, flashMessage, isErrorFlash, canDownloadZip);
    }

    @Override
    public Widget build() {
        List<Widget> root = new ArrayList<>();

        // Encabezado de la página
        Header mainTitle = Header.of(2, "🐘 Migrador PostgreSQL a JettraStore y Generador Backend");
        mainTitle.modifier(new Modifier().style("margin: 0 0 0.4rem 0; font-size: 1.5rem; font-weight: 800; color: var(--text-main);"));
        Paragraph subtitle = Paragraph.of("Inspeccione esquemas de PostgreSQL, replique automáticamente la arquitectura en Buckets e Índices de JettraStore, migre datos conservando fidelidad de tipos (BigDecimal y fechas ISO-8601) y descargue un backend funcional en Java 25+ empacado en .zip.");
        subtitle.modifier(new Modifier().style("color: var(--text-muted); margin: 0 0 1.2rem 0; font-size: 0.92rem; line-height: 1.5;"));
        root.add(Div.of(mainTitle, subtitle));

        // Mensaje Flash
        if (flashMessage != null && !flashMessage.isBlank()) {
            Div alert = Div.of(Text.of((isErrorFlash ? "❌ " : "✅ ") + flashMessage));
            alert.modifier(new Modifier()
                    .cssClass(isErrorFlash ? "app-alert-danger" : "app-alert-success")
                    .style("margin-bottom: 1.2rem; padding: 0.85rem 1.2rem; border-radius: 8px; font-weight: 600; font-size: 0.95rem;"));
            root.add(alert);
        }

        // Formulario de Conexión y Parámetros
        root.add(buildConnectionFormCard());

        // Panel de Descarga y Progreso de Migración
        if (progress != null) {
            root.add(buildProgressCard());
        }

        // Panel de Resultados de Introspección de Esquema
        if (introspection != null && introspection.isSuccess()) {
            root.add(buildIntrospectionCard());
        } else if (introspection != null && !introspection.isSuccess()) {
            Div errBox = Div.of(
                    Header.of(4, "❌ Error al conectar o analizar PostgreSQL").modifier(new Modifier().style("color: #ef4444; margin: 0 0 0.5rem 0;")),
                    Paragraph.of(introspection.getErrorMessage()).modifier(new Modifier().style("color: var(--text-main); font-family: monospace;"))
            );
            errBox.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid #ef4444; margin-top: 1.4rem;"));
            root.add(errBox);
        }

        Div container = Div.of(root.toArray(new Widget[0]));
        container.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 1.4rem; padding-bottom: 2rem;"));
        return container;
    }

    private Widget buildConnectionFormCard() {
        Header cardTitle = Header.of(3, "🔌 Parámetros de Conexión PostgreSQL y Destino JettraStore");
        cardTitle.modifier(new Modifier().style("margin: 0 0 1rem 0; font-size: 1.15rem; font-weight: 700; color: var(--text-main);"));

        // Fila 1: Host, Puerto, Base de datos origen, Esquema
        Div colHost = FluxUi.fieldColumn("Servidor Host PostgreSQL", FluxUi.textInput("host", config.getHost(), "127.0.0.1", true), null);
        Div colPort = FluxUi.fieldColumn("Puerto", FluxUi.textInput("port", String.valueOf(config.getPort()), "5432", true), null);

        // Selector o campo de Base de Datos Origen
        Div colDb;
        if (!discoveredDatabases.isEmpty()) {
            List<String> dbOpts = new ArrayList<>(discoveredDatabases);
            String currentDb = config.getDatabase();
            if (currentDb == null || currentDb.isBlank() || !dbOpts.contains(currentDb)) {
                currentDb = dbOpts.get(0);
            }
            Widget dbWidget = FluxUi.dropdown("database", dbOpts, currentDb);
            colDb = FluxUi.fieldColumn("Base de Datos Origen (" + discoveredDatabases.size() + " detectadas)", dbWidget, null);
        } else {
            String dbVal = (config.getDatabase() != null && !config.getDatabase().isBlank()) ? config.getDatabase() : "postgres";
            colDb = FluxUi.fieldColumn("Base de Datos Origen", FluxUi.textInput("database", dbVal, "postgres", true), null);
        }

        // Selector o campo de Esquema PostgreSQL
        Div colSchema;
        if (!discoveredSchemas.isEmpty()) {
            List<String> scOpts = new ArrayList<>(discoveredSchemas);
            String currentSc = config.getSchema();
            if (currentSc == null || currentSc.isBlank() || !scOpts.contains(currentSc)) {
                currentSc = scOpts.contains("public") ? "public" : scOpts.get(0);
            }
            Widget scWidget = FluxUi.dropdown("schema", scOpts, currentSc);
            colSchema = FluxUi.fieldColumn("Esquema (" + discoveredSchemas.size() + " detectados)", scWidget, null);
        } else {
            String scVal = (config.getSchema() != null && !config.getSchema().isBlank()) ? config.getSchema() : "public";
            colSchema = FluxUi.fieldColumn("Esquema PostgreSQL", FluxUi.textInput("schema", scVal, "public", true), null);
        }

        Div row1 = Div.of(colHost, colPort, colDb, colSchema);
        row1.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-bottom: 1rem;"));

        // Panel visual informativo de Bases de Datos y Esquemas descubiertos
        Div discoveryPanel = null;
        if (!discoveredDatabases.isEmpty()) {
            List<Widget> badges = new ArrayList<>();
            badges.add(Div.of(Text.of("🗄️ Bases en PostgreSQL:")).modifier(new Modifier().style("font-size: 0.85rem; font-weight: 700; margin-right: 0.5rem; color: var(--text-main); align-self: center;")));
            String currentDb = config.getDatabase();
            for (String dbName : discoveredDatabases) {
                boolean isSelected = dbName.equalsIgnoreCase(currentDb);
                Badge b = Badge.of((isSelected ? "⭐ " : "📦 ") + dbName + (isSelected ? " [Seleccionada]" : ""));
                b.modifier(new Modifier().style(isSelected
                        ? "background: #0284c7; color: white; font-weight: 700; padding: 0.35rem 0.75rem; border-radius: 6px; font-size: 0.82rem;"
                        : "background: var(--bg-hover); color: var(--text-muted); padding: 0.35rem 0.75rem; border-radius: 6px; font-size: 0.82rem; border: 1px solid var(--border-color);"));
                badges.add(b);
            }
            if (!discoveredSchemas.isEmpty()) {
                badges.add(Div.of(Text.of(" | 📑 Esquemas en " + (currentDb != null ? currentDb : "BD") + ":")).modifier(new Modifier().style("font-size: 0.85rem; font-weight: 700; margin: 0 0.5rem; color: var(--text-main); align-self: center;")));
                String currentSchema = config.getSchema();
                for (String scName : discoveredSchemas) {
                    boolean isSelected = scName.equalsIgnoreCase(currentSchema);
                    Badge b = Badge.of((isSelected ? "✔ " : "") + scName);
                    b.modifier(new Modifier().style(isSelected
                            ? "background: #10b981; color: white; font-weight: 700; padding: 0.3rem 0.6rem; border-radius: 6px; font-size: 0.8rem;"
                            : "background: var(--bg-hover); color: var(--text-muted); padding: 0.3rem 0.6rem; border-radius: 6px; font-size: 0.8rem; border: 1px solid var(--border-color);"));
                    badges.add(b);
                }
            }
            discoveryPanel = Div.of(badges.toArray(new Widget[0]));
            discoveryPanel.modifier(new Modifier().style("display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; margin-bottom: 1.2rem; padding: 0.8rem 1rem; background: var(--bg-hover); border-radius: 8px; border: 1px solid var(--border-color);"));
        }

        // Fila 2: Usuario, Contraseña, Base de datos JettraStore destino, Motor JettraStore
        Div colUser = FluxUi.fieldColumn("Usuario PostgreSQL", FluxUi.textInput("user", config.getUser(), "postgres", true), null);
        Div colPass = FluxUi.fieldColumn("Contraseña", FluxUi.passwordInput("password", "••••••••", false), null);
        Div colTargetDb = FluxUi.fieldColumn("Base de Datos Destino JettraStore", FluxUi.textInput("targetDatabase", config.getTargetDatabase(), "migrated_db", true), null);
        Div colTargetEngine = FluxUi.fieldColumn("Motor Destino JettraStore", FluxUi.dropdown("targetEngine", List.of("DOCUMENT", "JAVA_RECORD"), config.getTargetEngine()), null);

        Div row2 = Div.of(colUser, colPass, colTargetDb, colTargetEngine);
        row2.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-bottom: 1rem;"));

        // Fila 3: Paquete Backend, Opciones (SSL, Generar Backend)
        Div colPkg = FluxUi.fieldColumn("Paquete Java Backend (.zip)", FluxUi.textInput("backendPackageName", config.getBackendPackageName(), "com.example.jettra.backend", false), null);

        TextInput sslInput = FluxUi.textInput("ssl", config.isSsl() ? "true" : "false", "", false);
        sslInput.modifier(new Modifier().attribute("type", "checkbox").style("margin-left: 0.5rem;"));
        Div checkSsl = Div.of(
                FluxUi.fieldLabel("Opciones de Red"),
                Div.of(Text.of("🔒 Conexión SSL"), sslInput).modifier(new Modifier().style("display: flex; align-items: center; height: 38px; font-size: 0.88rem; font-weight: 600;"))
        );

        TextInput backendInput = FluxUi.textInput("generateBackend", config.isGenerateBackend() ? "true" : "false", "", false);
        backendInput.modifier(new Modifier().attribute("type", "checkbox").style("margin-left: 0.5rem;"));
        Div checkBackend = Div.of(
                FluxUi.fieldLabel("Artefacto"),
                Div.of(Text.of("📦 Generar Backend (.zip)"), backendInput).modifier(new Modifier().style("display: flex; align-items: center; height: 38px; font-size: 0.88rem; font-weight: 600;"))
        );

        Div row3 = Div.of(colPkg, checkSsl, checkBackend);
        row3.modifier(new Modifier().style("display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 1rem; margin-bottom: 1.5rem; align-items: flex-end;"));

        // Botones de Acción
        Button btnConnect = Button.of("🔌 Conectar y Listar BDs");
        btnConnect.modifier(new Modifier()
                .cssClass("app-btn-secondary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "connect")
                .style("padding: 0.75rem 1.4rem; font-size: 0.95rem; font-weight: 700; cursor: pointer; background: #059669; color: white; border: none; border-radius: 6px;"));

        Button btnAnalyze = Button.of("🔍 Analizar Esquema");
        btnAnalyze.modifier(new Modifier()
                .cssClass("app-btn-secondary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "analyze")
                .style("padding: 0.75rem 1.4rem; font-size: 0.95rem; font-weight: 700; cursor: pointer; background: #0284c7; color: white; border: none; border-radius: 6px;"));

        Button btnMigrate = Button.of("🚀 Iniciar Migración Completa");
        btnMigrate.modifier(new Modifier()
                .cssClass("app-btn-primary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "migrate")
                .style("padding: 0.75rem 1.6rem; font-size: 0.95rem; font-weight: 700; cursor: pointer; background: #4f46e5; color: white; border: none; border-radius: 6px;"));

        Button btnDryRun = Button.of("🧪 Simulación (Dry-Run)");
        btnDryRun.modifier(new Modifier()
                .cssClass("app-btn-secondary")
                .attribute("type", "submit")
                .attribute("name", "action")
                .attribute("value", "dry-run")
                .style("padding: 0.75rem 1.2rem; font-size: 0.95rem; font-weight: 600; cursor: pointer; border-radius: 6px;"));

        Div buttonsRow = Div.of(btnConnect, btnAnalyze, btnMigrate, btnDryRun);
        buttonsRow.modifier(new Modifier().style("display: flex; gap: 1rem; flex-wrap: wrap; align-items: center; padding-top: 0.5rem; border-top: 1px solid var(--border-color);"));

        Form form = (discoveryPanel != null)
                ? Form.of(row1, discoveryPanel, row2, row3, buttonsRow).action("/migrator").method("POST")
                : Form.of(row1, row2, row3, buttonsRow).action("/migrator").method("POST");

        Div card = Div.of(cardTitle, form);
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Widget buildProgressCard() {
        Header title = Header.of(3, "📊 Estado de Ejecución y Métricas de Migración");
        title.modifier(new Modifier().style("margin: 0 0 1rem 0; font-size: 1.15rem; font-weight: 700;"));

        // Badges de métricas
        Div badgeTables = Div.of(
                Div.of(Text.of("Tablas Migradas")).modifier(new Modifier().style("font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase;")),
                Div.of(Text.of(progress.getTablesMigrated() + " / " + progress.getTotalTables())).modifier(new Modifier().style("font-size: 1.3rem; font-weight: 800; color: #0284c7;"))
        );
        badgeTables.modifier(new Modifier().style("padding: 0.8rem; background: var(--bg-hover); border-radius: 8px; text-align: center;"));

        Div badgeRecords = Div.of(
                Div.of(Text.of("Registros Transferidos")).modifier(new Modifier().style("font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase;")),
                Div.of(Text.of(progress.getRecordsMigrated() + " / " + progress.getTotalRecords())).modifier(new Modifier().style("font-size: 1.3rem; font-weight: 800; color: #10b981;"))
        );
        badgeRecords.modifier(new Modifier().style("padding: 0.8rem; background: var(--bg-hover); border-radius: 8px; text-align: center;"));

        Div badgeTime = Div.of(
                Div.of(Text.of("Tiempo")).modifier(new Modifier().style("font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase;")),
                Div.of(Text.of(progress.getElapsedMs() + " ms")).modifier(new Modifier().style("font-size: 1.3rem; font-weight: 800; color: var(--text-main);"))
        );
        badgeTime.modifier(new Modifier().style("padding: 0.8rem; background: var(--bg-hover); border-radius: 8px; text-align: center;"));

        Div metricsRow = Div.of(badgeTables, badgeRecords, badgeTime);
        metricsRow.modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 1rem; margin-bottom: 1.2rem;"));

        // Barra de progreso
        int pct = progress.getPercentComplete();
        Div progressBar = Div.of();
        progressBar.modifier(new Modifier().style("width: " + pct + "%; height: 100%; background: linear-gradient(90deg, #0284c7, #10b981); border-radius: 6px; transition: width 0.3s ease;"));
        Div progressTrack = Div.of(progressBar);
        progressTrack.modifier(new Modifier().style("width: 100%; height: 12px; background: var(--border-color); border-radius: 6px; overflow: hidden; margin-bottom: 1rem;"));

        // Botón de Descarga ZIP si está listo
        List<Widget> actionRow = new ArrayList<>();
        if (canDownloadZip || progress.getGeneratedZipBytes() != null) {
            Link downloadLink = Link.of("/api/migrator/download", "📦 Descargar Proyecto Backend (.zip)");
            downloadLink.modifier(new Modifier()
                    .cssClass("app-btn-primary")
                    .style("background: #10b981; color: white; padding: 0.8rem 1.6rem; font-size: 1rem; font-weight: 700; text-decoration: none; border-radius: 8px; display: inline-flex; align-items: center; gap: 0.5rem;"));
            actionRow.add(downloadLink);
        }

        // Terminal de logs
        StringBuilder logSb = new StringBuilder();
        for (String log : progress.getLogs()) {
            logSb.append(log).append("\n");
        }
        TextArea logArea = TextArea.of(logSb.toString());
        logArea.modifier(new Modifier()
                .attribute("readonly", "true")
                .style("width: 100%; height: 180px; font-family: monospace; font-size: 0.84rem; background: #0f172a; color: #38bdf8; padding: 0.8rem; border-radius: 8px; border: 1px solid #1e293b; resize: vertical; box-sizing: border-box;"));

        List<Widget> cardContent = new ArrayList<>();
        cardContent.add(title);
        cardContent.add(metricsRow);
        cardContent.add(progressTrack);
        if (!actionRow.isEmpty()) {
            Div downloadContainer = Div.of(actionRow.toArray(new Widget[0]));
            downloadContainer.modifier(new Modifier().style("margin-bottom: 1.2rem;"));
            cardContent.add(downloadContainer);
        }
        cardContent.add(Div.of(Text.of("📜 Consola de Actividad")).modifier(new Modifier().style("font-size: 0.85rem; font-weight: 700; margin-bottom: 0.4rem; color: var(--text-muted);")));
        cardContent.add(logArea);

        Div card = Div.of(cardContent.toArray(new Widget[0]));
        card.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid #10b981;"));
        return card;
    }

    private Widget buildIntrospectionCard() {
        Header title = Header.of(3, "🗂️ Estructura y Mapeo de Tablas PostgreSQL a JettraStore");
        title.modifier(new Modifier().style("margin: 0 0 1rem 0; font-size: 1.15rem; font-weight: 700;"));

        List<Widget> tableCards = new ArrayList<>();
        for (PostgresTableMeta table : introspection.getTables()) {
            Div tableCard = buildTableDetailCard(table);
            tableCards.add(tableCard);
        }

        Div tablesContainer = Div.of(tableCards.toArray(new Widget[0]));
        tablesContainer.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 1rem;"));

        Div card = Div.of(title, tablesContainer);
        card.modifier(new Modifier().cssClass("ui-card"));
        return card;
    }

    private Div buildTableDetailCard(PostgresTableMeta table) {
        Header tblHeader = Header.of(4, "📋 Tabla: " + table.getTableName() + " ➔ Record: " + table.getJavaClassName());
        tblHeader.modifier(new Modifier().style("margin: 0 0 0.5rem 0; font-size: 1rem; font-weight: 700; color: #0284c7;"));

        Paragraph summary = Paragraph.of(table.getRowCount() + " registros estimados | " + table.getColumns().size() + " columnas | " + table.getIndexes().size() + " índices");
        summary.modifier(new Modifier().style("margin: 0 0 0.8rem 0; font-size: 0.84rem; color: var(--text-muted);"));

        // Tabla de Columnas
        List<Widget> rows = new ArrayList<>();
        // Header row
        Div thRow = Div.of(
                Div.of(Text.of("Columna")).modifier(new Modifier().style("font-weight: 700;")),
                Div.of(Text.of("Tipo PostgreSQL")).modifier(new Modifier().style("font-weight: 700;")),
                Div.of(Text.of("Tipo Java")).modifier(new Modifier().style("font-weight: 700;")),
                Div.of(Text.of("Tipo JettraStore")).modifier(new Modifier().style("font-weight: 700;")),
                Div.of(Text.of("PK / Nulo")).modifier(new Modifier().style("font-weight: 700;")),
                Div.of(Text.of("Regla JettraRules")).modifier(new Modifier().style("font-weight: 700;"))
        );
        thRow.modifier(new Modifier().style("display: grid; grid-template-columns: 2fr 1.5fr 1.5fr 1.5fr 1fr 2fr; gap: 0.5rem; padding: 0.4rem 0.6rem; background: var(--bg-hover); font-size: 0.82rem; border-radius: 4px;"));
        rows.add(thRow);

        for (PostgresColumnMeta col : table.getColumns()) {
            String pkNull = (col.isPrimaryKey() ? "🔑 PK " : "") + (col.isNullable() ? "NULL" : "NOT NULL");
            Div r = Div.of(
                    Div.of(Text.of(col.getColumnName())).modifier(new Modifier().style("font-family: monospace; font-weight: 600;")),
                    Div.of(Text.of(col.getDataType())).modifier(new Modifier().style("color: var(--text-muted);")),
                    Div.of(Text.of(col.getJavaType())).modifier(new Modifier().style("color: #10b981; font-weight: 600;")),
                    Div.of(Text.of(col.getJettraStoreType())).modifier(new Modifier().style("color: #0284c7; font-weight: 600;")),
                    Div.of(Text.of(pkNull)).modifier(new Modifier().style("font-size: 0.78rem; font-weight: 600;")),
                    Div.of(Text.of(col.getJettraRuleAnnotation() != null ? col.getJettraRuleAnnotation().replace("\n", " ") : "-")).modifier(new Modifier().style("color: #f59e0b; font-size: 0.8rem;"))
            );
            r.modifier(new Modifier().style("display: grid; grid-template-columns: 2fr 1.5fr 1.5fr 1.5fr 1fr 2fr; gap: 0.5rem; padding: 0.4rem 0.6rem; border-bottom: 1px solid var(--border-color); font-size: 0.82rem; align-items: center;"));
            rows.add(r);
        }

        Div tableWrapper = Div.of(rows.toArray(new Widget[0]));
        tableWrapper.modifier(new Modifier().style("display: flex; flex-direction: column; gap: 0.2rem; margin-bottom: 0.8rem;"));

        // Índices detectados
        List<Widget> idxBadges = new ArrayList<>();
        idxBadges.add(Div.of(Text.of("Índices equivalentes:")).modifier(new Modifier().style("font-size: 0.82rem; font-weight: 600; margin-right: 0.5rem;")));
        for (PostgresIndexMeta idx : table.getIndexes()) {
            Badge b = Badge.of("📌 " + idx.getIndexName() + " (" + idx.getColumnName() + " ➔ " + idx.getIndexType() + ")");
            b.modifier(new Modifier().style("background: #0284c7; color: white; font-size: 0.75rem; padding: 0.2rem 0.5rem; border-radius: 4px;"));
            idxBadges.add(b);
        }
        Div idxRow = Div.of(idxBadges.toArray(new Widget[0]));
        idxRow.modifier(new Modifier().style("display: flex; gap: 0.4rem; flex-wrap: wrap; align-items: center;"));

        Div card = Div.of(tblHeader, summary, tableWrapper, idxRow);
        card.modifier(new Modifier().style("padding: 1rem; border: 1px solid var(--border-color); border-radius: 8px; background: var(--bg-card);"));
        return card;
    }
}

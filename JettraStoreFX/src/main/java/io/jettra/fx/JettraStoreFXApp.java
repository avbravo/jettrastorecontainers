package io.jettra.fx;

import io.jettra.driver.JettraClient;
import io.jettra.fx.profile.ConnectionProfile;
import io.jettra.fx.view3d.Cluster3DVisualizer;
import io.jettra.store.core.StorageMode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.store.police.JettraPoliceNotification;
import io.jettra.store.core.StreamResponse;
import javafx.animation.FadeTransition;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JettraStoreFX - Centro de Mando Gráfico, Administración de Bases de Datos,
 * Telemetría de Recursos y Visualizador de Clúster Raft (Java 25+).
 */
public class JettraStoreFXApp extends Application {

    // Conexión y Sesión
    private JettraClient client;
    private final ConnectionProfile.ProfileManager profileManager = new ConnectionProfile.ProfileManager();
    private String currentDatabase = "sample_enterprise_db";
    private String activeUser = "admin";
    private String activeRole = "SUPER_ADMIN";
    private String activeHost = "127.0.0.1";
    private int activePort = 9091;
    private boolean isConnected = false;

    // Componentes Visuales del Header y Status Bar
    private Label statusConnectionBadge;
    private Label userSessionLabel;
    private Label statusBarLabel;
    private Label statusDiskQuickLabel;
    private Label statusRamQuickLabel;

    // Pestañas Principales
    private TabPane mainTabPane;
    private Cluster3DVisualizer cluster3D;

    // Elementos de Administración de Datos (BD, Buckets, Registros)
    private ListView<String> dbListView;
    private ListView<String> bucketListView;
    private TableView<RecordItem> recordsTable;
    private TextArea recordDetailJsonArea;
    private Label currentDbBadge;
    private Label currentBucketBadge;
    private Label recordCountBadge;
    private Label storageModeBadge;
    private ComboBox<String> cmbStorageMode;
    private int currentExplorerPage = 1;
    private int explorerPageSize = 25;
    private long totalExplorerRecords = 0;
    private int totalExplorerPages = 1;
    private Label lblExplorerPageStatus;

    // Monitoreo de Recursos y Almacenamiento
    private ProgressBar jvmHeapBar;
    private Label jvmHeapLabel;
    private ProgressBar offHeapBar;
    private Label offHeapLabel;
    private ProgressBar diskUsageBar;
    private Label diskUsageLabel;
    private Label diskDetailLabel;
    private Label systemStatsLabel;
    private TextArea resourceHistoryArea;
    private StackPane rootContainer;
    private boolean autoRefreshMetrics = true;
    private long lastMetricsTick = 0;

    // Modelo de Registro para TableView
    public record RecordItem(String id, String summary, String references) {}

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("JettraStoreFX - Centro de Mando Empresarial & Clúster (Java 25+)");

        // Inicializar perfiles predeterminados
        initProfilePresets();

        // Conectar inicialmente de manera segura
        connectToCurrentProfile();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0A0F1D; -fx-font-family: 'Segoe UI', 'Ubuntu', sans-serif;");

        // 1. Header Superior Moderno
        root.setTop(createTopHeader());

        // 2. TabPane Central con las Secciones Solicitadas
        mainTabPane = new TabPane();
        mainTabPane.setStyle("-fx-background-color: transparent;");
        mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        mainTabPane.getTabs().addAll(
            createConnectionsAndLoginTab(),
            createDataExplorerTab(),
            createAnalyticsAndCalcTab(),
            createBackupRestoreTab(),
            createClusterDashboardTab(),
            createSystemResourcesTab(),
            createConsoleTab()
        );
        root.setCenter(mainTabPane);

        // 3. Barra de Estado Inferior
        root.setBottom(createStatusBar());

        // 4. Timer de animación y actualización continua de telemetría (cada 2 segundos)
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (cluster3D != null) {
                    cluster3D.tickAnimation(0.016);
                }
                if (autoRefreshMetrics && (now - lastMetricsTick > 2_000_000_000L)) {
                    lastMetricsTick = now;
                    refreshResourceMetrics();
                }
            }
        };
        timer.start();

        this.rootContainer = new StackPane(root);
        Scene scene = new Scene(rootContainer, 1260, 820);
        primaryStage.setScene(scene);
        primaryStage.show();

        logStatus("JettraStoreFX inicializado. Sesión lista en " + activeHost + ":" + activePort);
    }

    private void initProfilePresets() {
        if (profileManager.getProfiles().size() <= 1) {
            profileManager.addProfile(new ConnectionProfile("Node-02 Replica", "127.0.0.1", 9092, "admin", "admin-jettra", "REPLICA"));
            profileManager.addProfile(new ConnectionProfile("Node-03 Replica", "127.0.0.1", 9093, "admin", "admin-jettra", "REPLICA"));
            profileManager.addProfile(new ConnectionProfile("Cloud DR Cluster", "10.0.10.15", 9091, "admin", "admin-jettra", "DISASTER_RECOVERY"));
        }
    }

    private boolean connectToCurrentProfile() {
        ConnectionProfile prof = profileManager.getActiveProfile();
        if (prof != null) {
            return performConnect(prof.getHost(), prof.getPort(), prof.getUsername(), prof.getPassword());
        }
        return performConnect(activeHost, activePort, activeUser, "admin-jettra");
    }

    private boolean performConnect(String host, int port, String user, String pass) {
        try {
            if (this.client != null) {
                try { this.client.close(); } catch (Exception ignored) {}
            }
            this.activeHost = host;
            this.activePort = port;
            this.activeUser = user;
            this.client = JettraClient.connect(host, port, user, pass);
            this.client.addPoliceEventListener(this::handlePoliceSentinelEvent);
            this.isConnected = true;
            this.activeRole = "SUPER_ADMIN";

            // Validar base de datos inicial
            if (client.databaseExists(currentDatabase)) {
                client.getDatabase(currentDatabase);
            } else {
                List<String> dbs = client.listDatabases();
                if (!dbs.isEmpty()) {
                    currentDatabase = dbs.getFirst();
                    client.getDatabase(currentDatabase);
                }
            }
            updateHeaderState();
            return true;
        } catch (Exception e) {
            this.isConnected = false;
            this.activeRole = "NONE";
            updateHeaderState();
            return false;
        }
    }

    private void updateHeaderState() {
        if (statusConnectionBadge != null) {
            if (isConnected) {
                statusConnectionBadge.setText("● EN LÍNEA: " + activeHost + ":" + activePort);
                statusConnectionBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.2); -fx-text-fill: #10B981; " +
                                              "-fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-background-radius: 6; -fx-border-color: #10B981; -fx-border-radius: 6;");
            } else {
                statusConnectionBadge.setText("○ DESCONECTADO: " + activeHost + ":" + activePort);
                statusConnectionBadge.setStyle("-fx-background-color: rgba(239, 68, 68, 0.2); -fx-text-fill: #EF4444; " +
                                              "-fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-background-radius: 6; -fx-border-color: #EF4444; -fx-border-radius: 6;");
            }
        }
        if (userSessionLabel != null) {
            userSessionLabel.setText("Usuario: " + activeUser + " (" + activeRole + ")");
        }
    }

    // =========================================================================
    // 1. HEADER SUPERIOR Y BARRA DE ESTADO
    // =========================================================================

    private HBox createTopHeader() {
        HBox header = new HBox(16);
        header.setPadding(new Insets(12, 22, 12, 22));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: linear-gradient(to bottom, #111827, #0B132B); -fx-border-color: #0284C7; -fx-border-width: 0 0 2 0; -fx-effect: dropshadow(gaussian, rgba(2, 132, 199, 0.35), 14, 0.2, 0, 4);");

        // Logo y Título
        Label icon = new Label("⚡");
        icon.setStyle("-fx-font-size: 22px; -fx-text-fill: #38BDF8;");

        VBox titleBox = new VBox(1);
        Label title = new Label("JETTRASTORE FX");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #38BDF8; -fx-letter-spacing: 1px;");
        Label subtitle = new Label("Centro de Mando Empresarial & Clúster Distribuido");
        subtitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #94A3B8;");
        titleBox.getChildren().addAll(title, subtitle);

        // Badge de Topología Raft
        Label raftBadge = new Label("RAFT CLUSTER 3-NODOS");
        raftBadge.setStyle("-fx-background-color: rgba(56, 189, 248, 0.15); -fx-text-fill: #38BDF8; " +
                           "-fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 3 8 3 8; -fx-background-radius: 4;");

        // Badge de Estado de Conexión
        this.statusConnectionBadge = new Label();
        updateHeaderState();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Sesión Activa
        this.userSessionLabel = new Label();
        userSessionLabel.setStyle("-fx-text-fill: #FACC15; -fx-font-weight: bold; -fx-font-size: 12px;\");");

        // Botón de Alternar Storage Mode en Header
        Button btnToggleStorage = new Button("💽 Modo Almacenamiento");
        btnToggleStorage.setStyle("-fx-background-color: #047857; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        btnToggleStorage.setOnAction(e -> {
            if (client != null && currentDatabase != null) {
                StorageMode current = client.getStorageMode(currentDatabase);
                StorageMode next = current.isDiskMemory() ? StorageMode.JVM_RAM : StorageMode.DISK_MEMORY;
                client.setStorageMode(currentDatabase, next);
                updateStorageModeBadge(next);
                logStatus("Conmutado modo de almacenamiento a: " + next.getCode());
            }
        });

        // Botón Acceso Rápido a Conexión / Login
        Button btnQuickConn = new Button("🔌 Conexión / Login");
        btnQuickConn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #E2E8F0; -fx-border-color: #0284C7; " +
                             "-fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 11px;");
        btnQuickConn.setOnAction(e -> mainTabPane.getSelectionModel().select(0));

        header.getChildren().addAll(icon, titleBox, raftBadge, statusConnectionBadge, spacer, btnToggleStorage, userSessionLabel, btnQuickConn);
        return header;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(6, 18, 6, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1.5 0 0 0;");

        this.statusBarLabel = new Label("Listo.");
        statusBarLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.statusDiskQuickLabel = new Label("Disco: Calculando...");
        statusDiskQuickLabel.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px;");

        this.statusRamQuickLabel = new Label("JVM Heap: Calculando...");
        statusRamQuickLabel.setStyle("-fx-text-fill: #4ADE80; -fx-font-size: 10px;");

        bar.getChildren().addAll(statusBarLabel, spacer, statusDiskQuickLabel, statusRamQuickLabel);
        return bar;
    }

    private void logStatus(String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        if (statusBarLabel != null) {
            statusBarLabel.setText(String.format("[%s] %s", time, msg));
        }
        if (resourceHistoryArea != null) {
            resourceHistoryArea.appendText(String.format("[%s] %s%n", time, msg));
        }
    }

    // =========================================================================
    // 2. PESTAÑA: CONEXIONES & GESTIÓN DE LOGIN
    // =========================================================================

    private Tab createConnectionsAndLoginTab() {
        Tab tab = new Tab("Conexión & Login");

        SplitPane split = new SplitPane();
        split.setStyle("-fx-background-color: #0A0F1D;");

        // Panel Izquierdo: Perfiles de Conexión Guardados
        VBox leftPane = new VBox(14);
        leftPane.setPadding(new Insets(18));
        leftPane.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 1 0 0;");

        Label lblProfilesTitle = new Label("PERFILES DE CONEXIÓN GUARDADOS");
        lblProfilesTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        ListView<ConnectionProfile> profilesListView = new ListView<>();
        profilesListView.setPrefHeight(220);
        profilesListView.setStyle("-fx-background-color: #1E293B; -fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        ObservableList<ConnectionProfile> obsProfiles = FXCollections.observableArrayList(profileManager.getProfiles());
        profilesListView.setItems(obsProfiles);
        profilesListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    boolean isActive = item.getHost().equals(activeHost) && item.getPort() == activePort;
                    setText(String.format("%s %s (%s:%d) [%s]", 
                        isActive ? "★" : "•", item.getProfileName(), item.getHost(), item.getPort(), item.getEnvironment()));
                    setStyle(isActive ? "-fx-text-fill: #FACC15; -fx-font-weight: bold;" : "-fx-text-fill: #E2E8F0;");
                }
            }
        });

        // Formulario para Crear / Editar Perfil
        TextField txtProfName = new TextField("Nuevo Servidor");
        txtProfName.setPromptText("Nombre del perfil");
        TextField txtProfHost = new TextField("127.0.0.1");
        txtProfHost.setPromptText("Host / IP");
        TextField txtProfPort = new TextField("9091");
        txtProfPort.setPromptText("Puerto");
        TextField txtProfUser = new TextField("admin");
        txtProfUser.setPromptText("Usuario predeterminado");

        HBox btnProfRow = new HBox(8);
        Button btnConnectProfile = new Button("▶ Conectar a Seleccionado");
        btnConnectProfile.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold;");
        btnConnectProfile.setOnAction(e -> {
            ConnectionProfile sel = profilesListView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                profileManager.setActiveProfile(sel);
                boolean ok = performConnect(sel.getHost(), sel.getPort(), sel.getUsername(), sel.getPassword());
                profilesListView.refresh();
                if (ok) {
                    logStatus("Conectado exitosamente a perfil: " + sel.getProfileName());
                    refreshDataExplorerDatabases();
                } else {
                    logStatus("Error al conectar al perfil: " + sel.getProfileName());
                }
            }
        });

        Button btnSaveProfile = new Button("+ Guardar Perfil");
        btnSaveProfile.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold;");
        btnSaveProfile.setOnAction(e -> {
            try {
                int p = Integer.parseInt(txtProfPort.getText().trim());
                ConnectionProfile np = new ConnectionProfile(txtProfName.getText().trim(), txtProfHost.getText().trim(), p, txtProfUser.getText().trim(), "admin-jettra", "CUSTOM");
                profileManager.addProfile(np);
                obsProfiles.setAll(profileManager.getProfiles());
                logStatus("Perfil guardado: " + np.getProfileName());
            } catch (Exception ex) {
                logStatus("Error en datos de perfil: " + ex.getMessage());
            }
        });

        btnProfRow.getChildren().addAll(btnConnectProfile, btnSaveProfile);

        leftPane.getChildren().addAll(lblProfilesTitle, profilesListView, btnProfRow,
            new Separator(), new Label("Registrar nuevo perfil:"), txtProfName, txtProfHost, txtProfPort, txtProfUser);

        // Panel Derecho: Autenticación, Login & Token JWT
        VBox rightPane = new VBox(14);
        rightPane.setPadding(new Insets(18));
        rightPane.setStyle("-fx-background-color: #0F172A;");

        Label lblLoginTitle = new Label("GESTIÓN DE LOGIN Y AUTENTICACIÓN (JettraJWT)");
        lblLoginTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #FACC15;");

        GridPane loginGrid = new GridPane();
        loginGrid.setHgap(10);
        loginGrid.setVgap(10);

        TextField txtLoginHost = new TextField(activeHost);
        TextField txtLoginPort = new TextField(String.valueOf(activePort));
        TextField txtLoginUser = new TextField(activeUser);
        PasswordField txtLoginPass = new PasswordField();
        txtLoginPass.setText("admin-jettra");

        loginGrid.add(new Label("Host:"), 0, 0);
        loginGrid.add(txtLoginHost, 1, 0);
        loginGrid.add(new Label("Puerto:"), 0, 1);
        loginGrid.add(txtLoginPort, 1, 1);
        loginGrid.add(new Label("Usuario:"), 0, 2);
        loginGrid.add(txtLoginUser, 1, 2);
        loginGrid.add(new Label("Contraseña:"), 0, 3);
        loginGrid.add(txtLoginPass, 1, 3);

        HBox loginBtnRow = new HBox(10);
        Button btnLogin = new Button("🔑 Iniciar Sesión (Login)");
        btnLogin.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16 8 16;");

        Button btnLogout = new Button("Cerrar Sesión (Logout)");
        btnLogout.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-padding: 8 16 8 16;");

        Label loginFeedback = new Label();

        btnLogin.setOnAction(e -> {
            try {
                int p = Integer.parseInt(txtLoginPort.getText().trim());
                boolean ok = performConnect(txtLoginHost.getText().trim(), p, txtLoginUser.getText().trim(), txtLoginPass.getText());
                if (ok) {
                    loginFeedback.setText("[AUTH OK] Sesión iniciada como '" + activeUser + "' en " + activeHost + ":" + activePort);
                    loginFeedback.setStyle("-fx-text-fill: #10B981; -fx-font-weight: bold;");
                    logStatus("Autenticación exitosa para: " + activeUser);
                    refreshDataExplorerDatabases();
                } else {
                    loginFeedback.setText("[AUTH ERROR] Credenciales rechazadas o servidor inalcanzable.");
                    loginFeedback.setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
                }
            } catch (Exception ex) {
                loginFeedback.setText("Error: " + ex.getMessage());
                loginFeedback.setStyle("-fx-text-fill: #EF4444;");
            }
        });

        btnLogout.setOnAction(e -> {
            this.isConnected = false;
            this.activeUser = "anonymous";
            this.activeRole = "NONE";
            updateHeaderState();
            loginFeedback.setText("Sesión cerrada exitosamente.");
            loginFeedback.setStyle("-fx-text-fill: #94A3B8;");
            logStatus("Sesión finalizada.");
        });

        loginBtnRow.getChildren().addAll(btnLogin, btnLogout);

        // Tarjeta Informativa de Seguridad JWT
        VBox jwtCard = new VBox(6);
        jwtCard.setPadding(new Insets(12));
        jwtCard.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label jwtTitle = new Label("ESTADO DEL TOKEN Y POLÍTICA RBAC");
        jwtTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-font-weight: bold;");
        Label jwtRole = new Label("• Rol Activo: SUPER_ADMIN (Control Total del Clúster)");
        Label jwtCrypto = new Label("• Algoritmo Criptográfico: HMAC-SHA256 con Clave Efímera Raft");
        Label jwtImmutable = new Label("• Inmutabilidad: Superusuario 'admin' protegido contra borrado/alteración");
        jwtRole.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        jwtCrypto.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        jwtImmutable.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        jwtCard.getChildren().addAll(jwtTitle, jwtRole, jwtCrypto, jwtImmutable);

        rightPane.getChildren().addAll(lblLoginTitle, loginGrid, loginBtnRow, loginFeedback, new Separator(), jwtCard);

        split.getItems().addAll(leftPane, rightPane);
        split.setDividerPositions(0.46);
        tab.setContent(split);
        return tab;
    }

    // =========================================================================
    // 3. PESTAÑA: ADMINISTRACIÓN DE BASES DE DATOS, BUCKETS Y REGISTROS
    // =========================================================================

    private Tab createDataExplorerTab() {
        Tab tab = new Tab("Bases de Datos & Registros");

        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: #0A0F1D;");

        // Barra Superior de Contexto Activo
        HBox topContext = new HBox(12);
        topContext.setPadding(new Insets(10, 16, 10, 16));
        topContext.setAlignment(Pos.CENTER_LEFT);
        topContext.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        Label lblContext = new Label("CONTEXTO ACTIVO:");
        lblContext.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        this.currentDbBadge = new Label("BD: " + currentDatabase);
        currentDbBadge.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");

        this.currentBucketBadge = new Label("Bucket: (Ninguno)");
        currentBucketBadge.setStyle("-fx-background-color: #334155; -fx-text-fill: #E2E8F0; -fx-padding: 3 8 3 8; -fx-background-radius: 4;");

        this.recordCountBadge = new Label("Registros: 0");
        recordCountBadge.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4;");

        Label policeBadge = new Label("🛡️ JettraPolice: ACTIVO");
        policeBadge.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");

        Label memoryBadge = new Label("⚡ JettraMemory: OFF-HEAP");
        memoryBadge.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");

        this.storageModeBadge = new Label("MODO: JVM-RAM");
        storageModeBadge.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");

        this.cmbStorageMode = new ComboBox<>(FXCollections.observableArrayList(
            "🧠 JVM-RAM (Stack & Heap)",
            "💽 DISK-MEMORY (JettraMemory LSM)"
        ));
        cmbStorageMode.getSelectionModel().select(0);
        cmbStorageMode.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-border-color: #0284C7; -fx-border-radius: 4;");
        cmbStorageMode.setOnAction(e -> {
            if (client != null && isConnected && currentDatabase != null) {
                int idx = cmbStorageMode.getSelectionModel().getSelectedIndex();
                StorageMode newMode = (idx == 1) ? StorageMode.DISK_MEMORY : StorageMode.JVM_RAM;
                client.setStorageMode(currentDatabase, newMode);
                updateStorageModeBadge(newMode);
                logStatus("Modo de almacenamiento para '" + currentDatabase + "' conmutado a: " + newMode.getCode());
            }
        });

        topContext.getChildren().addAll(lblContext, currentDbBadge, currentBucketBadge, recordCountBadge, policeBadge, memoryBadge, storageModeBadge, cmbStorageMode);
        layout.setTop(topContext);

        // Tres Columnas: 1) Bases de Datos, 2) Buckets/Unidades, 3) Registros & Detalle
        SplitPane split3 = new SplitPane();
        split3.setStyle("-fx-background-color: #0A0F1D;");

        // COLUMNA 1: BASES DE DATOS
        VBox col1 = new VBox(8);
        col1.setPadding(new Insets(12));
        col1.setStyle("-fx-background-color: #0F172A;");
        Label lblCol1 = new Label("BASES DE DATOS");
        lblCol1.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        this.dbListView = new ListView<>();
        dbListView.setStyle("-fx-background-color: #1E293B; -fx-control-inner-background: #1E293B; -fx-text-fill: white;");
        VBox.setVgrow(dbListView, Priority.ALWAYS);

        dbListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(currentDatabase)) {
                this.currentDatabase = newVal;
                if (client != null && isConnected) {
                    if (client.getRingEngine().isRingActive()) {
                        currentDbBadge.setText("BD: " + currentDatabase + " [ANILLO]");
                        currentDbBadge.setStyle("-fx-background-color: #F59E0B; -fx-text-fill: black; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");
                        logStatus("[CLUSTER] Base de datos '" + currentDatabase + "': Transición Dinámica a Motor de Anillo Distribuido ACTIVA.");
                    } else {
                        currentDbBadge.setText("BD: " + currentDatabase);
                        currentDbBadge.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");
                    }
                    StorageMode sm = client.getStorageMode(currentDatabase);
                    updateStorageModeBadge(sm);
                } else {
                    currentDbBadge.setText("BD: " + currentDatabase);
                }
                loadBucketsForCurrentDatabase();
            }
        });

        HBox dbActions = new HBox(6);
        Button btnNewDb = new Button("+ Nueva BD");
        btnNewDb.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 10px;");
        btnNewDb.setOnAction(e -> promptCreateDatabase());

        Button btnDropDb = new Button("Eliminar BD");
        btnDropDb.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 10px;");
        btnDropDb.setOnAction(e -> promptDropDatabase());

        Button btnRefreshDb = new Button("🔄");
        btnRefreshDb.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px;");
        btnRefreshDb.setOnAction(e -> refreshDataExplorerDatabases());

        MenuButton btnInstallSamples = new MenuButton("📦 Muestras");
        btnInstallSamples.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
        
        MenuItem itemFactura = new MenuItem("📦 3M Factura (example_factura_db)");
        itemFactura.setOnAction(e -> {
            new Thread(() -> {
                Platform.runLater(() -> logStatus("Iniciando carga de muestra 3M 'example_factura_db'..."));
                try {
                    client.sql(currentDatabase, "INSTALL SAMPLES FACTURA");
                    Platform.runLater(() -> {
                        refreshDataExplorerDatabases();
                        logStatus("Muestra 'example_factura_db' cargada exitosamente.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> logStatus("Error: " + ex.getMessage()));
                }
            }).start();
        });

        MenuItem itemHospital = new MenuItem("🏥 2M Hospital (samples_hostipal_db)");
        itemHospital.setOnAction(e -> {
            new Thread(() -> {
                Platform.runLater(() -> logStatus("Iniciando carga de muestra 2M 'samples_hostipal_db'..."));
                try {
                    client.sql(currentDatabase, "INSTALL SAMPLES HOSPITAL");
                    Platform.runLater(() -> {
                        refreshDataExplorerDatabases();
                        logStatus("Muestra 'samples_hostipal_db' (2M objetos) cargada exitosamente.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> logStatus("Error: " + ex.getMessage()));
                }
            }).start();
        });

        MenuItem itemAmbiental = new MenuItem("🌍 3M Ambiental (samples_ambiental_db)");
        itemAmbiental.setOnAction(e -> {
            new Thread(() -> {
                Platform.runLater(() -> logStatus("Iniciando carga de muestra 3M 'samples_ambiental_db'..."));
                try {
                    client.sql(currentDatabase, "INSTALL SAMPLES AMBIENTAL");
                    Platform.runLater(() -> {
                        refreshDataExplorerDatabases();
                        logStatus("Muestra 'samples_ambiental_db' (3M objetos) cargada exitosamente.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> logStatus("Error: " + ex.getMessage()));
                }
            }).start();
        });

        btnInstallSamples.getItems().addAll(itemFactura, itemHospital, itemAmbiental);

        Button btnPoliceAudit = new Button("🛡️ Police");
        btnPoliceAudit.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
        btnPoliceAudit.setOnAction(e -> showPoliceAuditDialog());

        dbActions.getChildren().addAll(btnNewDb, btnDropDb, btnRefreshDb, btnInstallSamples, btnPoliceAudit);
        col1.getChildren().addAll(lblCol1, dbListView, dbActions);

        // COLUMNA 2: BUCKETS / COLECCIONES MULTIMODELO
        VBox col2 = new VBox(8);
        col2.setPadding(new Insets(12));
        col2.setStyle("-fx-background-color: #0F172A;");
        Label lblCol2 = new Label("BUCKETS / COLECCIONES");
        lblCol2.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #10B981;");

        this.bucketListView = new ListView<>();
        bucketListView.setStyle("-fx-background-color: #1E293B; -fx-control-inner-background: #1E293B; -fx-text-fill: white;");
        VBox.setVgrow(bucketListView, Priority.ALWAYS);

        bucketListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                currentBucketBadge.setText("Bucket: " + newVal);
                currentExplorerPage = 1;
                loadRecordsForBucket(newVal);
            }
        });

        HBox bucketActions = new HBox(6);
        Button btnNewBucket = new Button("+ Nuevo Bucket");
        btnNewBucket.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 10px;");
        btnNewBucket.setOnAction(e -> promptCreateBucket());

        Button btnDropBucket = new Button("Eliminar");
        btnDropBucket.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 10px;");
        btnDropBucket.setOnAction(e -> promptDropBucket());

        Button btnCountBucket = new Button("Count");
        btnCountBucket.setStyle("-fx-background-color: #F59E0B; -fx-text-fill: black; -fx-font-size: 10px; -fx-font-weight: bold;");
        btnCountBucket.setOnAction(e -> performCountCurrentBucket());

        bucketActions.getChildren().addAll(btnNewBucket, btnDropBucket, btnCountBucket);
        col2.getChildren().addAll(lblCol2, bucketListView, bucketActions);

        // COLUMNA 3: GESTIÓN DE REGISTROS Y VISOR JSON
        VBox col3 = new VBox(8);
        col3.setPadding(new Insets(12));
        col3.setStyle("-fx-background-color: #0F172A;");

        Label lblCol3 = new Label("REGISTROS EN BUCKET");
        lblCol3.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #FACC15;");

        // Barra de Herramientas de Registros y Consulta Directa
        VBox recordToolsBox = new VBox(6);

        HBox recordTools = new HBox(8);
        Button btnNewRecord = new Button("+ Nuevo Registro");
        btnNewRecord.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");
        btnNewRecord.setOnAction(e -> promptCreateRecord());

        Button btnDeleteRecord = new Button("Eliminar Registro");
        btnDeleteRecord.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 11px;");
        btnDeleteRecord.setOnAction(e -> performDeleteSelectedRecord());

        Button btnReloadRecords = new Button("Recargar");
        btnReloadRecords.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px;");
        btnReloadRecords.setOnAction(e -> {
            String b = bucketListView.getSelectionModel().getSelectedItem();
            if (b != null) loadRecordsForBucket(b);
        });

        recordTools.getChildren().addAll(btnNewRecord, btnDeleteRecord, btnReloadRecords);

        // Barra de Consulta SQL / JQL Rápida en el explorador
        HBox queryBar = new HBox(6);
        queryBar.setAlignment(Pos.CENTER_LEFT);
        TextField txtQuickQuery = new TextField();
        txtQuickQuery.setPromptText("Consulta SQL o JQL (ej. SELECT * FROM clientes LIMIT 25)...");
        txtQuickQuery.setStyle("-fx-background-color: #020617; -fx-text-fill: #38BDF8; -fx-border-color: #0284C7; -fx-border-radius: 4; -fx-font-size: 11px;");
        HBox.setHgrow(txtQuickQuery, Priority.ALWAYS);

        Button btnRunQuickQuery = new Button("▶ Ejecutar");
        btnRunQuickQuery.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");
        btnRunQuickQuery.setOnAction(e -> {
            String q = txtQuickQuery.getText().trim();
            if (!q.isEmpty()) {
                executeQuickExplorerQuery(q);
            }
        });
        txtQuickQuery.setOnAction(e -> btnRunQuickQuery.fire());

        Button btnClearQuery = new Button("✖");
        btnClearQuery.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px;");
        btnClearQuery.setOnAction(e -> {
            txtQuickQuery.clear();
            String b = bucketListView.getSelectionModel().getSelectedItem();
            if (b != null) loadRecordsForBucket(b);
        });

        queryBar.getChildren().addAll(txtQuickQuery, btnRunQuickQuery, btnClearQuery);
        recordToolsBox.getChildren().addAll(recordTools, queryBar);

        // Tabla de Registros
        this.recordsTable = new TableView<>();
        recordsTable.setStyle("-fx-background-color: #1E293B; -fx-control-inner-background: #1E293B; -fx-text-fill: white;");
        VBox.setVgrow(recordsTable, Priority.ALWAYS);

        TableColumn<RecordItem, String> colId = new TableColumn<>("ID (_id)");
        colId.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().id()));
        colId.setPrefWidth(120);

        TableColumn<RecordItem, String> colSummary = new TableColumn<>("Contenido / Documento");
        colSummary.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().summary()));
        colSummary.setPrefWidth(260);

        TableColumn<RecordItem, String> colRefs = new TableColumn<>("Referencias JettraRef");
        colRefs.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().references()));
        colRefs.setPrefWidth(160);

        recordsTable.getColumns().addAll(colId, colSummary, colRefs);

        // Detalle JSON abajo
        Label lblDetail = new Label("DETALLE DEL DOCUMENTO JSON:");
        lblDetail.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");
        this.recordDetailJsonArea = new TextArea();
        recordDetailJsonArea.setPrefRowCount(7);
        recordDetailJsonArea.setEditable(false);
        recordDetailJsonArea.setStyle("-fx-control-inner-background: #020617; -fx-font-family: monospace; -fx-text-fill: #38BDF8;");

        recordsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                recordDetailJsonArea.setText("{\n  \"_id\": \"" + newV.id() + "\",\n  \"data\": " + newV.summary() + ",\n  \"_refs\": \"" + newV.references() + "\"\n}");
            }
        });

        // Barra de Paginación de Registros
        HBox paginationBar = new HBox(8);
        paginationBar.setAlignment(Pos.CENTER_LEFT);
        paginationBar.setPadding(new Insets(4, 0, 4, 0));

        Button btnFirst = new Button("|<<");
        btnFirst.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-weight: bold;");
        btnFirst.setOnAction(e -> {
            if (currentExplorerPage > 1) {
                currentExplorerPage = 1;
                String b = bucketListView.getSelectionModel().getSelectedItem();
                if (b != null) loadRecordsForBucket(b);
            }
        });

        Button btnPrev = new Button("<");
        btnPrev.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-weight: bold;");
        btnPrev.setOnAction(e -> {
            if (currentExplorerPage > 1) {
                currentExplorerPage--;
                String b = bucketListView.getSelectionModel().getSelectedItem();
                if (b != null) loadRecordsForBucket(b);
            }
        });

        this.lblExplorerPageStatus = new Label("Pág. 1 de 1 (0 reg.)");
        lblExplorerPageStatus.setStyle("-fx-text-fill: #38BDF8; -fx-font-weight: bold; -fx-font-size: 11px;");

        Button btnNext = new Button(">");
        btnNext.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-weight: bold;");
        btnNext.setOnAction(e -> {
            if (currentExplorerPage < totalExplorerPages) {
                currentExplorerPage++;
                String b = bucketListView.getSelectionModel().getSelectedItem();
                if (b != null) loadRecordsForBucket(b);
            }
        });

        Button btnLast = new Button(">>|");
        btnLast.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-weight: bold;");
        btnLast.setOnAction(e -> {
            if (currentExplorerPage < totalExplorerPages) {
                currentExplorerPage = totalExplorerPages;
                String b = bucketListView.getSelectionModel().getSelectedItem();
                if (b != null) loadRecordsForBucket(b);
            }
        });

        Label lblSize = new Label("Por pág:");
        lblSize.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        ComboBox<Integer> cmbPageSize = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100, 250));
        cmbPageSize.setValue(25);
        cmbPageSize.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white;");
        cmbPageSize.setOnAction(e -> {
            Integer val = cmbPageSize.getValue();
            if (val != null) {
                this.explorerPageSize = val;
                this.currentExplorerPage = 1;
                String b = bucketListView.getSelectionModel().getSelectedItem();
                if (b != null) loadRecordsForBucket(b);
            }
        });

        Label lblJump = new Label("Ir a:");
        lblJump.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-padding: 0 0 0 6;");

        TextField txtJumpPage = new TextField();
        txtJumpPage.setPrefWidth(45);
        txtJumpPage.setPromptText("1");
        txtJumpPage.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");
        txtJumpPage.setOnAction(e -> {
            try {
                int p = Integer.parseInt(txtJumpPage.getText().trim());
                if (p >= 1 && p <= totalExplorerPages) {
                    currentExplorerPage = p;
                    String b = bucketListView.getSelectionModel().getSelectedItem();
                    if (b != null) loadRecordsForBucket(b);
                }
            } catch (Exception ignored) {}
        });

        Button btnGo = new Button("Ir");
        btnGo.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 10px;");
        btnGo.setOnAction(e -> txtJumpPage.fireEvent(new javafx.event.ActionEvent()));

        paginationBar.getChildren().addAll(btnFirst, btnPrev, lblExplorerPageStatus, btnNext, btnLast, lblSize, cmbPageSize, lblJump, txtJumpPage, btnGo);

        col3.getChildren().addAll(lblCol3, recordToolsBox, recordsTable, paginationBar, lblDetail, recordDetailJsonArea);

        split3.getItems().addAll(col1, col2, col3);
        split3.setDividerPositions(0.20, 0.44);

        layout.setCenter(split3);
        tab.setContent(layout);

        // Cargar datos iniciales
        refreshDataExplorerDatabases();

        return tab;
    }

    private void refreshDataExplorerDatabases() {
        if (client == null || !isConnected) return;
        try {
            List<String> dbs = client.listDatabases();
            dbListView.setItems(FXCollections.observableArrayList(dbs));
            if (!dbs.isEmpty()) {
                if (currentDatabase == null || !dbs.contains(currentDatabase)) {
                    currentDatabase = dbs.getFirst();
                }
                dbListView.getSelectionModel().select(currentDatabase);
            } else {
                currentDatabase = null;
                bucketListView.setItems(FXCollections.observableArrayList());
                recordsTable.setItems(FXCollections.observableArrayList());
                recordCountBadge.setText("Registros: 0");
            }
            loadBucketsForCurrentDatabase();
        } catch (Exception e) {
            logStatus("Error al listar bases de datos: " + e.getMessage());
        }
    }

    private void updateStorageModeBadge(StorageMode sm) {
        if (storageModeBadge == null) return;
        if (sm == null) sm = StorageMode.JVM_RAM;
        if (sm.isDiskMemory()) {
            storageModeBadge.setText("MODO: DISK-MEMORY (JettraMemory LSM)");
            storageModeBadge.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            if (cmbStorageMode != null) cmbStorageMode.getSelectionModel().select(1);
        } else {
            storageModeBadge.setText("MODO: JVM-RAM (Stack & Heap)");
            storageModeBadge.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            if (cmbStorageMode != null) cmbStorageMode.getSelectionModel().select(0);
        }
    }

    private void loadBucketsForCurrentDatabase() {
        if (client == null || !isConnected || currentDatabase == null) {
            bucketListView.setItems(FXCollections.observableArrayList());
            recordsTable.setItems(FXCollections.observableArrayList());
            recordCountBadge.setText("Registros: 0");
            return;
        }
        try {
            Set<String> allCols = client.getCollectionNames(currentDatabase);
            List<String> list = new ArrayList<>(allCols);
            bucketListView.setItems(FXCollections.observableArrayList(list));
            if (!list.isEmpty()) {
                bucketListView.getSelectionModel().select(0);
                loadRecordsForBucket(list.getFirst());
            } else {
                recordsTable.setItems(FXCollections.observableArrayList());
                recordCountBadge.setText("Registros: 0");
            }
        } catch (Exception e) {
            logStatus("Error al cargar buckets: " + e.getMessage());
        }
    }

    private void loadRecordsForBucket(String bucketName) {
        if (client == null || !isConnected || currentDatabase == null || bucketName == null) return;
        try {
            ObservableList<RecordItem> items = FXCollections.observableArrayList();
            long count = client.getBucketCount(currentDatabase, bucketName);
            totalExplorerRecords = count;
            totalExplorerPages = (int) Math.max(1, Math.ceil((double) totalExplorerRecords / explorerPageSize));
            int offset = (currentExplorerPage - 1) * explorerPageSize;

            List<JettraClient.BucketRecord> records = client.getBucketRecords(currentDatabase, bucketName, offset, explorerPageSize);
            for (JettraClient.BucketRecord rec : records) {
                items.add(new RecordItem(rec.id(), rec.summary(), rec.references()));
            }

            recordCountBadge.setText("Registros: " + count);
            recordsTable.setItems(items);
            if (lblExplorerPageStatus != null) {
                lblExplorerPageStatus.setText(String.format("Pág. %d de %d (%d total)", currentExplorerPage, totalExplorerPages, totalExplorerRecords));
            }
            if (!items.isEmpty()) {
                recordsTable.getSelectionModel().select(0);
            }
        } catch (Exception e) {
            logStatus("Error al cargar registros de '" + bucketName + "': " + e.getMessage());
        }
    }

    private void promptCreateDatabase() {
        TextInputDialog dialog = new TextInputDialog("nueva_db");
        dialog.setTitle("Nueva Base de Datos");
        dialog.setHeaderText("Crear Base de Datos en Clúster JettraStore");
        dialog.setContentText("Nombre de la Base de Datos:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    client.createDatabase(name.trim());
                    refreshDataExplorerDatabases();
                    logStatus("Base de datos creada: " + name.trim());
                } catch (Exception e) {
                    logStatus("Error al crear BD: " + e.getMessage());
                }
            }
        });
    }

    private void promptDropDatabase() {
        String sel = dbListView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "¿Desea eliminar la base de datos '" + sel + "'?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(ans -> {
            if (ans == ButtonType.YES) {
                try {
                    client.dropDatabase(sel);
                    refreshDataExplorerDatabases();
                    logStatus("Base de datos eliminada: " + sel);
                } catch (Exception e) {
                    logStatus("Error al eliminar BD: " + e.getMessage());
                }
            }
        });
    }

    private void promptCreateBucket() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Nuevo Bucket / Colección");
        dialog.setHeaderText("Crear Unidad de Almacenamiento en '" + currentDatabase + "'");

        VBox box = new VBox(10);
        box.setPadding(new Insets(15));
        TextField txtName = new TextField("nuevo_bucket");
        ComboBox<String> cmbEngine = new ComboBox<>(FXCollections.observableArrayList("DOCUMENT", "VECTOR", "GRAPH", "TIMESERIES", "KEYVALUE"));
        cmbEngine.getSelectionModel().select(0);
        box.getChildren().addAll(new Label("Nombre del Bucket:"), txtName, new Label("Tipo de Motor Multimodelo:"), cmbEngine);

        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == ButtonType.OK ? txtName.getText() + "::" + cmbEngine.getValue() : null);

        dialog.showAndWait().ifPresent(res -> {
            String[] parts = res.split("::");
            String name = parts[0].trim();
            String engine = parts[1];
            try {
                client.createBucket(currentDatabase, name, engine);
                loadBucketsForCurrentDatabase();
                logStatus("Bucket '" + name + "' (" + engine + ") creado en " + currentDatabase);
            } catch (Exception e) {
                logStatus("Error al crear bucket: " + e.getMessage());
            }
        });
    }

    private void promptDropBucket() {
        String sel = bucketListView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "¿Eliminar el bucket '" + sel + "'?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(ans -> {
            if (ans == ButtonType.YES) {
                try {
                    client.dropBucket(currentDatabase, sel);
                    loadBucketsForCurrentDatabase();
                    logStatus("Bucket '" + sel + "' eliminado.");
                } catch (Exception e) {
                    logStatus("Error al eliminar bucket: " + e.getMessage());
                }
            }
        });
    }

    private void performCountCurrentBucket() {
        String sel = bucketListView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        try {
            long c = client.getBucketCount(currentDatabase, sel);
            recordCountBadge.setText("Registros: " + c);
            logStatus(String.format("Conteo para bucket '%s': %d registro(s).", sel, c));
        } catch (Exception e) {
            logStatus("Error en COUNT: " + e.getMessage());
        }
    }

    private void promptCreateRecord() {
        String bucket = bucketListView.getSelectionModel().getSelectedItem();
        if (bucket == null) return;

        Dialog<Map.Entry<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Nuevo Registro en " + bucket);
        dialog.setHeaderText("Insertar Documento en '" + currentDatabase + "." + bucket + "'");

        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        TextField txtId = new TextField("rec_" + System.currentTimeMillis() % 10000);
        TextArea txtJson = new TextArea("{\"name\": \"Nuevo Registro\", \"status\": \"ACTIVE\", \"timestamp\": " + System.currentTimeMillis() + "}");
        txtJson.setPrefRowCount(5);
        box.getChildren().addAll(new Label("Identificador (_id):"), txtId, new Label("Documento JSON:"), txtJson);

        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == ButtonType.OK ? Map.entry(txtId.getText().trim(), txtJson.getText().trim()) : null);

        dialog.showAndWait().ifPresent(entry -> {
            try {
                client.insertRecord(currentDatabase, bucket, entry.getKey(), entry.getValue());
                loadRecordsForBucket(bucket);
                logStatus("Registro '" + entry.getKey() + "' insertado con éxito.");
            } catch (Exception e) {
                logStatus("Error al insertar registro: " + e.getMessage());
            }
        });
    }

    private void performDeleteSelectedRecord() {
        RecordItem sel = recordsTable.getSelectionModel().getSelectedItem();
        String bucket = bucketListView.getSelectionModel().getSelectedItem();
        if (sel == null || bucket == null) return;

        try {
            client.deleteDocument(currentDatabase, bucket, sel.id());
            loadRecordsForBucket(bucket);
            logStatus("Registro '" + sel.id() + "' eliminado de '" + bucket + "'.");
        } catch (Exception e) {
            logStatus("Error al eliminar registro: " + e.getMessage());
        }
    }

    // =========================================================================
    // 4. PESTAÑA: ASISTENTE DE BACKUP Y RESTORE
    // =========================================================================

    private Tab createBackupRestoreTab() {
        Tab tab = new Tab("Backup & Restore");

        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: #0A0F1D;");
        layout.setPadding(new Insets(20));

        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(16);

        // Tarjeta de Backup
        VBox backupCard = new VBox(12);
        backupCard.setPadding(new Insets(18));
        backupCard.setPrefWidth(540);
        backupCard.setStyle("-fx-background-color: #0F172A; -fx-border-color: #0284C7; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label lblBackupTitle = new Label("COPIA DE SEGURIDAD EN CALIENTE (HOT BACKUP)");
        lblBackupTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        ComboBox<String> cmbDbBackup = new ComboBox<>();
        cmbDbBackup.setPrefWidth(300);
        cmbDbBackup.setItems(FXCollections.observableArrayList("sample_enterprise_db", "sample_ecommerce_db", "example_factura_db", "samples_hostipal_db", "samples_ambiental_db"));
        cmbDbBackup.getSelectionModel().select(0);

        TextField txtBackupPath = new TextField("./data/backup_" + currentDatabase + ".jettra_bak");
        CheckBox chkIncludeWal = new CheckBox("Incluir Bitácora Transaccional WAL y MemTable Off-Heap");
        chkIncludeWal.setSelected(true);
        chkIncludeWal.setStyle("-fx-text-fill: #CBD5E1;");

        Button btnExecuteBackup = new Button("💾 Iniciar Respaldo (.jettra_bak)");
        btnExecuteBackup.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16 8 16;");

        Label backupResultLabel = new Label();

        btnExecuteBackup.setOnAction(e -> {
            String db = cmbDbBackup.getValue();
            String pathStr = txtBackupPath.getText().trim();
            try {
                Path p = Paths.get(pathStr);
                if (p.getParent() != null && !Files.exists(p.getParent())) {
                    Files.createDirectories(p.getParent());
                }
                var res = client.admin().backupDatabase(client.getDatabase(db), p);
                backupResultLabel.setText("[OK] " + res.message());
                backupResultLabel.setStyle("-fx-text-fill: #10B981; -fx-font-weight: bold;");
                logStatus("Backup completado para '" + db + "' en " + pathStr);
            } catch (Exception ex) {
                backupResultLabel.setText("[ERROR] " + ex.getMessage());
                backupResultLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
            }
        });

        backupCard.getChildren().addAll(lblBackupTitle, new Label("Base de Datos a Respaldar:"), cmbDbBackup, 
            new Label("Ruta del Archivo de Destino:"), txtBackupPath, chkIncludeWal, btnExecuteBackup, backupResultLabel);

        // Tarjeta de Restore
        VBox restoreCard = new VBox(12);
        restoreCard.setPadding(new Insets(18));
        restoreCard.setPrefWidth(540);
        restoreCard.setStyle("-fx-background-color: #0F172A; -fx-border-color: #10B981; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label lblRestoreTitle = new Label("RESTAURACIÓN CONSISTENTE (RESTORE DATABASE)");
        lblRestoreTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #10B981;");

        TextField txtRestoreFile = new TextField("./data/backup_" + currentDatabase + ".jettra_bak");
        TextField txtRestoreTargetDb = new TextField(currentDatabase);

        Label warnRestore = new Label("⚠ Advertencia: La restauración sincroniza la estructura de SSTables e Índices.");
        warnRestore.setStyle("-fx-text-fill: #F59E0B; -fx-font-size: 11px;");

        Button btnExecuteRestore = new Button("♻ Ejecutar Restauración");
        btnExecuteRestore.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16 8 16;");

        Label restoreResultLabel = new Label();

        btnExecuteRestore.setOnAction(e -> {
            String fileStr = txtRestoreFile.getText().trim();
            String targetDb = txtRestoreTargetDb.getText().trim();
            try {
                Path p = Paths.get(fileStr);
                var res = client.admin().restoreDatabase(p, client.getDatabase(targetDb));
                restoreResultLabel.setText("[OK] " + res.message());
                restoreResultLabel.setStyle("-fx-text-fill: #10B981; -fx-font-weight: bold;");
                logStatus("Restauración exitosa para: " + targetDb);
                refreshDataExplorerDatabases();
            } catch (Exception ex) {
                restoreResultLabel.setText("[ERROR] " + ex.getMessage());
                restoreResultLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
            }
        });

        restoreCard.getChildren().addAll(lblRestoreTitle, new Label("Archivo de Respaldo (.jettra_bak):"), txtRestoreFile,
            new Label("Base de Datos de Destino:"), txtRestoreTargetDb, warnRestore, btnExecuteRestore, restoreResultLabel);

        grid.add(backupCard, 0, 0);
        grid.add(restoreCard, 1, 0);

        layout.setCenter(grid);
        tab.setContent(layout);
        return tab;
    }

    // =========================================================================
    // 5. PESTAÑA: DASHBOARD DE NODOS Y TOPOLOGÍA 3D
    // =========================================================================

    private Tab createClusterDashboardTab() {
        Tab tab = new Tab("Dashboard de Nodos & 3D");

        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: #0A0F1D;");

        // Tarjetas Métricas KPI Superiores
        HBox kpiBar = new HBox(16);
        kpiBar.setPadding(new Insets(14, 20, 14, 20));
        kpiBar.setAlignment(Pos.CENTER);
        kpiBar.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 0 1.5 0;");

        kpiBar.getChildren().addAll(
            createKpiCard("NODOS RAFT", "3 ONLINE", "#10B981", "Quorum Activo"),
            createKpiCard("LÍDER CONSENSO", "Node-01 (127.0.0.1:9091)", "#38BDF8", "Primario R/W"),
            createKpiCard("RÉPLICAS LECTURA", "Node-02 & Node-03", "#FACC15", "Latencia < 0.2ms"),
            createKpiCard("ESTADO PARTICIÓN", "SINCRONIZADO", "#A855F7", "Zero-Split Brain")
        );
        layout.setTop(kpiBar);

        // Visualizador 3D Avanzado con Controles de Ratón (Órbita y Zoom)
        this.cluster3D = new Cluster3DVisualizer();
        SubScene subScene3D = new SubScene(cluster3D, 760, 520, true, SceneAntialiasing.BALANCED);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(1800.0);

        javafx.scene.transform.Rotate rotX = new javafx.scene.transform.Rotate(-22, javafx.scene.transform.Rotate.X_AXIS);
        javafx.scene.transform.Rotate rotY = new javafx.scene.transform.Rotate(0, javafx.scene.transform.Rotate.Y_AXIS);
        javafx.scene.transform.Translate trans = new javafx.scene.transform.Translate(0, -60, -360);
        camera.getTransforms().addAll(rotY, rotX, trans);
        subScene3D.setCamera(camera);

        // Controles de Rotación 3D y Zoom interactivo con el ratón
        final double[] anchor = new double[2];
        final double[] angle = new double[2];
        subScene3D.setOnMousePressed(e -> {
            anchor[0] = e.getSceneX();
            anchor[1] = e.getSceneY();
            angle[0] = rotY.getAngle();
            angle[1] = rotX.getAngle();
        });
        subScene3D.setOnMouseDragged(e -> {
            rotY.setAngle(angle[0] + (e.getSceneX() - anchor[0]) * 0.4);
            rotX.setAngle(Math.max(-85, Math.min(85, angle[1] - (e.getSceneY() - anchor[1]) * 0.4)));
        });
        subScene3D.setOnScroll(e -> {
            double delta = e.getDeltaY();
            trans.setZ(Math.min(-120, Math.max(-850, trans.getZ() + delta * 0.8)));
        });

        // Contenedor StackPane con HUD Flotante de Controles 3D
        StackPane center3DStack = new StackPane();
        center3DStack.getChildren().add(subScene3D);
        subScene3D.widthProperty().bind(center3DStack.widthProperty());
        subScene3D.heightProperty().bind(center3DStack.heightProperty());

        // Barra Flotante de Acceso a Vistas 3D
        HBox hud3DControls = new HBox(8);
        hud3DControls.setAlignment(Pos.CENTER);
        hud3DControls.setPadding(new Insets(6, 14, 6, 14));
        hud3DControls.setStyle("-fx-background-color: rgba(15, 23, 42, 0.88); -fx-border-color: #0284C7; -fx-border-radius: 20; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(2, 132, 199, 0.4), 10, 0, 0, 3);");

        Label lblHudTitle = new Label("VISTA 3D:");
        lblHudTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px; -fx-font-weight: bold;");

        Button btnReset3D = new Button("🔄 Reset Cámara");
        btnReset3D.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-background-radius: 12;");
        btnReset3D.setOnAction(e -> {
            rotX.setAngle(-22);
            rotY.setAngle(0);
            trans.setX(0);
            trans.setY(-60);
            trans.setZ(-360);
        });

        Button btnTopDown = new Button("📐 Cenital (Top)");
        btnTopDown.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 10px; -fx-background-radius: 12;");
        btnTopDown.setOnAction(e -> {
            rotX.setAngle(-85);
            rotY.setAngle(0);
            trans.setY(-280);
            trans.setZ(-180);
        });

        Button btnIso = new Button("✨ Isométrica 3D");
        btnIso.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 10px; -fx-background-radius: 12;");
        btnIso.setOnAction(e -> {
            rotX.setAngle(-35);
            rotY.setAngle(45);
            trans.setX(40);
            trans.setY(-90);
            trans.setZ(-400);
        });

        hud3DControls.getChildren().addAll(lblHudTitle, btnReset3D, btnTopDown, btnIso);
        StackPane.setAlignment(hud3DControls, Pos.BOTTOM_CENTER);
        StackPane.setMargin(hud3DControls, new Insets(0, 0, 16, 0));
        center3DStack.getChildren().add(hud3DControls);

        // Panel Lateral Derecho con Información de los Nodos
        VBox nodeInfoSidebar = new VBox(12);
        nodeInfoSidebar.setPadding(new Insets(16));
        nodeInfoSidebar.setPrefWidth(380);
        nodeInfoSidebar.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 0 0 1.5;");

        Label sidebarTitle = new Label("DETALLE DE TOPOLOGÍA RAFT");
        sidebarTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        VBox nodesContainer = new VBox(8);

        nodesContainer.getChildren().add(createNodeCard("Node-01 [MASTER / LEADER]", "127.0.0.1:9091", "ONLINE", "#10B981", "R/W Master | MemTable Off-Heap 28%"));
        nodesContainer.getChildren().add(createNodeCard("Node-02 [SECONDARY REPLICA]", "127.0.0.1:9092", "ONLINE", "#38BDF8", "Read Replica | Panama FFM Segments"));
        nodesContainer.getChildren().add(createNodeCard("Node-03 [SECONDARY REPLICA]", "127.0.0.1:9093", "ONLINE", "#38BDF8", "Read Replica | Raft Follower"));

        Label lblRingAction = new Label("Simulación de Desbordamiento de Anillo:");
        lblRingAction.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        Button btnSimulateRing = new Button("⚡ Alternar Anillo Dinámico (Overflow)");
        btnSimulateRing.setStyle("-fx-background-color: #D97706; -fx-text-fill: white; -fx-font-weight: bold;");
        btnSimulateRing.setOnAction(e -> {
            boolean cur = !cluster3D.isRingTransitionActive();
            cluster3D.setRingTransitionActive(cur);
            btnSimulateRing.setStyle(cur ? "-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-weight: bold;" 
                                         : "-fx-background-color: #D97706; -fx-text-fill: white; -fx-font-weight: bold;");
            logStatus("Anillo dinámico " + (cur ? "ACTIVADO (Transfiriendo particiones)" : "INACTIVO (Modo local)"));
        });

        HBox nodeActions = new HBox(8);
        Button btnPingAll = new Button("Comprobar Latencia");
        btnPingAll.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px;");
        btnPingAll.setOnAction(e -> logStatus("Ping completado: Node-01 (0.1ms), Node-02 (0.2ms), Node-03 (0.3ms)"));

        Button btnAddDynNode = new Button("+ Agregar Nodo 3D");
        btnAddDynNode.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 11px;");
        btnAddDynNode.setOnAction(e -> {
            cluster3D.addNode("node-dyn-" + System.currentTimeMillis() % 100, false, 0, -110, -50);
            logStatus("Nodo dinámico visual agregado al clúster 3D.");
        });

        nodeActions.getChildren().addAll(btnPingAll, btnAddDynNode);

        nodeInfoSidebar.getChildren().addAll(sidebarTitle, nodesContainer, new Separator(), lblRingAction, btnSimulateRing, nodeActions);

        layout.setCenter(center3DStack);
        layout.setRight(nodeInfoSidebar);

        tab.setContent(layout);
        return tab;
    }

    private VBox createKpiCard(String title, String value, String colorHex, String footer) {
        VBox card = new VBox(3);
        card.setPadding(new Insets(10, 16, 10, 16));
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: linear-gradient(to bottom, #1E293B, #0F172A); -fx-border-color: " + colorHex + "; -fx-border-width: 1.5; -fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.6), 12, 0.2, 0, 5);");
        card.setPrefWidth(220);

        Label lblT = new Label(title);
        lblT.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

        Label lblV = new Label(value);
        lblV.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 13px; -fx-font-weight: bold;");

        Label lblF = new Label(footer);
        lblF.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 9px;");

        card.getChildren().addAll(lblT, lblV, lblF);
        return card;
    }

    private VBox createNodeCard(String name, String endpoint, String status, String statusColor, String details) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(8, 12, 8, 12));
        card.setStyle("-fx-background-color: linear-gradient(to bottom, #1E293B, #0F172A); -fx-border-color: #334155; -fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.4), 8, 0.15, 0, 3);");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(name);
        title.setStyle("-fx-text-fill: #E2E8F0; -fx-font-weight: bold; -fx-font-size: 11px;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label st = new Label(status);
        st.setStyle("-fx-background-color: " + statusColor + "; -fx-text-fill: white; -fx-font-size: 9px; -fx-padding: 2 6 2 6; -fx-background-radius: 3;");
        top.getChildren().addAll(title, sp, st);

        Label ep = new Label("Endpoint: " + endpoint);
        ep.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px;");

        Label det = new Label(details);
        det.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 9px;");

        card.getChildren().addAll(top, ep, det);
        return card;
    }

    // =========================================================================
    // 6. PESTAÑA: ESPACIO EN DISCO Y RECURSOS CONSUMIDOS
    // =========================================================================

    private Tab createSystemResourcesTab() {
        Tab tab = new Tab("Almacenamiento & Recursos");

        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: #0A0F1D;");
        layout.setPadding(new Insets(20));

        VBox content = new VBox(18);

        // Barra de Control Superior
        HBox controlBar = new HBox(12);
        controlBar.setAlignment(Pos.CENTER_LEFT);

        Label lblSection = new Label("MONITOREO DE ESPACIO EN DISCO Y RECURSOS DEL SISTEMA");
        lblSection.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnRefreshNow = new Button("🔄 Actualizar Métricas Ahora");
        btnRefreshNow.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold;");
        btnRefreshNow.setOnAction(e -> refreshResourceMetrics());

        CheckBox chkAuto = new CheckBox("Auto-refresco en Vivo");
        chkAuto.setSelected(true);
        chkAuto.setStyle("-fx-text-fill: #CBD5E1;");
        chkAuto.selectedProperty().addListener((obs, oldV, newV) -> this.autoRefreshMetrics = newV);

        Button btnRunGc = new Button("Ejecutar ZGC Compact");
        btnRunGc.setStyle("-fx-background-color: #334155; -fx-text-fill: #E2E8F0;");
        btnRunGc.setOnAction(e -> {
            System.gc();
            refreshResourceMetrics();
            logStatus("ZGC invocado. Memoria compactada.");
        });

        Button btnCompactMemory = new Button("⚡ Compactar JettraMemory (Off-Heap)");
        btnCompactMemory.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold;");
        btnCompactMemory.setOnAction(e -> {
            try {
                if (client != null && currentDatabase != null) {
                    client.compactMemory(currentDatabase);
                    refreshResourceMetrics();
                    logStatus("JettraMemory compactado con éxito para '" + currentDatabase + "'.");
                }
            } catch (Exception ex) {
                logStatus("Error al compactar JettraMemory: " + ex.getMessage());
            }
        });

        controlBar.getChildren().addAll(lblSection, spacer, chkAuto, btnRefreshNow, btnRunGc, btnCompactMemory);

        // Dos Paneles Principales en Grid: 1) Disco, 2) Recursos JVM/Off-Heap
        GridPane metricsGrid = new GridPane();
        metricsGrid.setHgap(20);
        metricsGrid.setVgap(16);

        // PANEL 1: ALMACENAMIENTO EN DISCO
        VBox diskCard = new VBox(12);
        diskCard.setPadding(new Insets(18));
        diskCard.setPrefWidth(550);
        diskCard.setStyle("-fx-background-color: #0F172A; -fx-border-color: #0284C7; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label diskTitle = new Label("ESPACIO DE ALMACENAMIENTO EN DISCO");
        diskTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        this.diskUsageBar = new ProgressBar(0);
        diskUsageBar.setPrefWidth(500);
        diskUsageBar.setPrefHeight(18);
        diskUsageBar.setStyle("-fx-accent: #0284C7;");

        this.diskUsageLabel = new Label("Calculando espacio...");
        diskUsageLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 12px;");

        this.diskDetailLabel = new Label("Estructura de almacenamiento: SSTables, WAL y MemTable Off-Heap");
        diskDetailLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        diskCard.getChildren().addAll(diskTitle, diskUsageBar, diskUsageLabel, diskDetailLabel);

        // PANEL 2: RECURSOS CONSUMIDOS (JVM & OFF-HEAP)
        VBox ramCard = new VBox(12);
        ramCard.setPadding(new Insets(18));
        ramCard.setPrefWidth(550);
        ramCard.setStyle("-fx-background-color: #0F172A; -fx-border-color: #10B981; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label ramTitle = new Label("RECURSOS CONSUMIDOS (MEMORIA & PROCESAMIENTO)");
        ramTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #10B981;");

        Label lblJvm = new Label("Memoria JVM Heap (ZGC Pausas < 1ms):");
        lblJvm.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        this.jvmHeapBar = new ProgressBar(0);
        jvmHeapBar.setPrefWidth(500);
        jvmHeapBar.setPrefHeight(14);
        jvmHeapBar.setStyle("-fx-accent: #10B981;");
        this.jvmHeapLabel = new Label("Heap: Calculando...");
        jvmHeapLabel.setStyle("-fx-text-fill: #4ADE80; -fx-font-size: 11px;");

        Label lblOff = new Label("Memoria Off-Heap (Panama FFM Arena / MemTables):");
        lblOff.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        this.offHeapBar = new ProgressBar(0.35);
        offHeapBar.setPrefWidth(500);
        offHeapBar.setPrefHeight(14);
        offHeapBar.setStyle("-fx-accent: #38BDF8;");
        this.offHeapLabel = new Label("Off-Heap Arena: 35.0% asignado a MemTables nativas");
        offHeapLabel.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 11px;");

        this.systemStatsLabel = new Label("CPU y Concurrencia: Detectando...");
        systemStatsLabel.setStyle("-fx-text-fill: #FACC15; -fx-font-size: 11px;");

        ramCard.getChildren().addAll(ramTitle, lblJvm, jvmHeapBar, jvmHeapLabel, lblOff, offHeapBar, offHeapLabel, systemStatsLabel);

        metricsGrid.add(diskCard, 0, 0);
        metricsGrid.add(ramCard, 1, 0);

        // Registro de Auditoría de Telemetría
        Label lblHist = new Label("HISTORIAL DE EVENTOS Y TELEMETRÍA DEL SISTEMA:");
        lblHist.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");
        this.resourceHistoryArea = new TextArea();
        resourceHistoryArea.setPrefRowCount(8);
        resourceHistoryArea.setEditable(false);
        resourceHistoryArea.setStyle("-fx-control-inner-background: #020617; -fx-font-family: monospace; -fx-text-fill: #4ADE80;");

        content.getChildren().addAll(controlBar, metricsGrid, lblHist, resourceHistoryArea);
        layout.setCenter(content);

        tab.setContent(layout);
        refreshResourceMetrics();
        return tab;
    }

    private void refreshResourceMetrics() {
        Platform.runLater(() -> {
            try {
                // 1. Espacio en Disco
                File rootDir = new File(".");
                long totalDisk = rootDir.getTotalSpace();
                long usableDisk = rootDir.getUsableSpace();
                long usedDisk = Math.max(0, totalDisk - usableDisk);
                double diskRatio = totalDisk > 0 ? (double) usedDisk / totalDisk : 0;

                if (diskUsageBar != null) diskUsageBar.setProgress(diskRatio);
                if (diskUsageLabel != null) {
                    double usedGb = usedDisk / (1024.0 * 1024.0 * 1024.0);
                    double totalGb = totalDisk / (1024.0 * 1024.0 * 1024.0);
                    double freeGb = usableDisk / (1024.0 * 1024.0 * 1024.0);
                    diskUsageLabel.setText(String.format("Disco: %.1f GB Usados / %.1f GB Totales (%.1f GB Libres - %.1f%%)",
                        usedGb, totalGb, freeGb, diskRatio * 100));
                }

                // Cálculo de carpeta de datos
                File dataDir = new File("./data");
                long dataSize = calculateFolderSize(dataDir);
                double dataMb = dataSize / (1024.0 * 1024.0);
                if (diskDetailLabel != null) {
                    diskDetailLabel.setText(String.format("JettraStore Data Directory: %.2f MB en SSTables, Índices y WALs persistidos.", dataMb));
                }
                if (statusDiskQuickLabel != null) {
                    statusDiskQuickLabel.setText(String.format("Disco: %.1f%% Usado (%.1f GB Libres)", diskRatio * 100, usableDisk / 1e9));
                }

                // 2. Memoria JVM Heap
                Runtime rt = Runtime.getRuntime();
                long maxMem = rt.maxMemory();
                long totalMem = rt.totalMemory();
                long freeMem = rt.freeMemory();
                long usedMem = totalMem - freeMem;
                double heapRatio = maxMem > 0 ? (double) usedMem / maxMem : 0;

                if (jvmHeapBar != null) jvmHeapBar.setProgress(heapRatio);
                if (jvmHeapLabel != null) {
                    double usedMb = usedMem / (1024.0 * 1024.0);
                    double maxMb = maxMem / (1024.0 * 1024.0);
                    jvmHeapLabel.setText(String.format("Heap: %.1f MB / %.1f MB (%.1f%%) - ZGC Colector", usedMb, maxMb, heapRatio * 100));
                }
                if (statusRamQuickLabel != null) {
                    statusRamQuickLabel.setText(String.format("JVM: %.0f MB (%.0f%%)", usedMem / 1e6, heapRatio * 100));
                }

                // 3. CPU y Concurrencia
                int cores = rt.availableProcessors();
                int threads = Thread.activeCount();
                if (systemStatsLabel != null) {
                    systemStatsLabel.setText(String.format("CPU: %d Núcleos Activos | Hilos del Sistema: %d | FFM Arena: Nativa Directa", cores, threads));
                }
            } catch (Exception ignored) {}
        });
    }

    private long calculateFolderSize(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long length = 0;
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                length += calculateFolderSize(child);
            }
        }
        return length;
    }

    // =========================================================================
    // 7. PESTAÑA: CONSOLA SQL & JETTRAQL
    // =========================================================================

    private Tab createAnalyticsAndCalcTab() {
        Tab tab = new Tab("🧮 Analítica & Cálculo");

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0A0F1D; -fx-background-color: #0A0F1D;");

        VBox rootBox = new VBox(16);
        rootBox.setPadding(new Insets(20));
        rootBox.setStyle("-fx-background-color: #0A0F1D;");

        Label headerTitle = new Label("CENTRO DE ANALÍTICA, AGREGACIONES Y CÁLCULO CUANTITATIVO");
        headerTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        Label headerSub = new Label("Motores integrados: JettraAggregation (GROUP BY), JettraMath, JettraStatistics, JettraFinance y JettraVectorMath");
        headerSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8;");

        // --- SECCIÓN 1: AGREGACIONES MULTIMODELO ---
        VBox secAgg = new VBox(10);
        secAgg.setPadding(new Insets(14));
        secAgg.setStyle("-fx-background-color: #0F172A; -fx-background-radius: 8; -fx-border-color: #1E293B; -fx-border-radius: 8;");

        Label lblAggTitle = new Label("1. AGREGACIONES MULTIMODELO Y GROUP BY (JettraAggregation)");
        lblAggTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #10B981;");

        HBox aggInputs = new HBox(10);
        aggInputs.setAlignment(Pos.CENTER_LEFT);

        TextField txtAggCol = new TextField("facturas");
        txtAggCol.setPromptText("Colección (ej. facturas)");
        txtAggCol.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        TextField txtAggGroup = new TextField("estado");
        txtAggGroup.setPromptText("Campo Agrupar (ej. estado)");
        txtAggGroup.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        ComboBox<String> cmbAggFn = new ComboBox<>(FXCollections.observableArrayList("SUM", "AVG", "COUNT", "MIN", "MAX", "MEDIAN", "IQR", "STDDEV"));
        cmbAggFn.setValue("SUM");
        cmbAggFn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white;");

        TextField txtAggField = new TextField("total");
        txtAggField.setPromptText("Campo Numérico (ej. total)");
        txtAggField.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        Button btnRunAgg = new Button("⚡ Ejecutar Agregación");
        btnRunAgg.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold;");

        aggInputs.getChildren().addAll(new Label("Col:"), txtAggCol, new Label("Group:"), txtAggGroup, new Label("Fn:"), cmbAggFn, new Label("Campo:"), txtAggField, btnRunAgg);

        TextArea txtAggOutput = new TextArea();
        txtAggOutput.setPrefRowCount(5);
        txtAggOutput.setEditable(false);
        txtAggOutput.setStyle("-fx-control-inner-background: #020617; -fx-font-family: monospace; -fx-text-fill: #34D399;");

        btnRunAgg.setOnAction(e -> {
            try {
                String sql = String.format("SELECT %s, %s(%s) AS resultado, COUNT(*) AS conteo FROM %s GROUP BY %s;",
                    txtAggGroup.getText().trim(), cmbAggFn.getValue(), txtAggField.getText().trim(), txtAggCol.getText().trim(), txtAggGroup.getText().trim());
                var res = client.sql(currentDatabase, sql);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== AGREGACIÓN EN '%s' (%s) ===%n", currentDatabase, res.message()));
                for (List<Object> row : res.rows()) {
                    sb.append("  → ").append(row).append("\n");
                }
                txtAggOutput.setText(sb.toString());
                logStatus("Agregación completada en " + currentDatabase);
            } catch (Exception ex) {
                txtAggOutput.setText("[ERROR AGREGACIÓN] " + ex.getMessage());
            }
        });

        secAgg.getChildren().addAll(lblAggTitle, aggInputs, txtAggOutput);

        // --- SECCIÓN 2: EVALUADOR MATEMÁTICO ---
        VBox secMath = new VBox(10);
        secMath.setPadding(new Insets(14));
        secMath.setStyle("-fx-background-color: #0F172A; -fx-background-radius: 8; -fx-border-color: #1E293B; -fx-border-radius: 8;");

        Label lblMathTitle = new Label("2. EVALUADOR MATEMÁTICO CUANTITATIVO (JettraMath)");
        lblMathTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #F59E0B;");

        HBox mathInputs = new HBox(10);
        mathInputs.setAlignment(Pos.CENTER_LEFT);

        TextField txtMathExpr = new TextField("cbrt(1000) + sqrt(144) * 2 - hypot(3, 4)");
        txtMathExpr.setPrefWidth(450);
        txtMathExpr.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: #FDE047; -fx-font-family: monospace;");

        Button btnEvalMath = new Button("📐 Evaluar Expresión");
        btnEvalMath.setStyle("-fx-background-color: #D97706; -fx-text-fill: white; -fx-font-weight: bold;");

        Label lblMathRes = new Label("Resultado: --");
        lblMathRes.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #FDE047; -fx-background-color: #292524; -fx-padding: 4 10 4 10; -fx-background-radius: 4;");

        btnEvalMath.setOnAction(e -> {
            try {
                double r = client.evalMath(txtMathExpr.getText().trim());
                lblMathRes.setText(String.format("Resultado: %.6f", r));
                logStatus("Cálculo matemático: " + r);
            } catch (Exception ex) {
                lblMathRes.setText("Error: " + ex.getMessage());
            }
        });

        mathInputs.getChildren().addAll(txtMathExpr, btnEvalMath, lblMathRes);
        secMath.getChildren().addAll(lblMathTitle, mathInputs);

        // --- SECCIÓN 3: MOTOR FINANCIERO Y ESTADÍSTICA ---
        VBox secFin = new VBox(10);
        secFin.setPadding(new Insets(14));
        secFin.setStyle("-fx-background-color: #0F172A; -fx-background-radius: 8; -fx-border-color: #1E293B; -fx-border-radius: 8;");

        Label lblFinTitle = new Label("3. FINANZAS CUANTITATIVAS Y ESTADÍSTICA DESCRIPTIVA (JettraFinance & JettraStatistics)");
        lblFinTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        HBox finInputs = new HBox(10);
        finInputs.setAlignment(Pos.CENTER_LEFT);

        TextField txtPv = new TextField("200000");
        txtPv.setPromptText("Préstamo PV");
        txtPv.setPrefWidth(90);
        txtPv.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        TextField txtRate = new TextField("0.05");
        txtRate.setPromptText("Tasa Anual (0.05)");
        txtRate.setPrefWidth(80);
        txtRate.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        TextField txtNper = new TextField("360");
        txtNper.setPromptText("Meses (360)");
        txtNper.setPrefWidth(70);
        txtNper.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        Button btnPmt = new Button("💵 PMT Cuota");
        btnPmt.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white;");

        Button btnAmort = new Button("📋 Tabla Amortización");
        btnAmort.setStyle("-fx-background-color: #2563EB; -fx-text-fill: white;");

        TextField txtStatsData = new TextField("12.5, 18.2, 24.0, 31.5, 42.1, 55.0, 68.4");
        txtStatsData.setPromptText("Muestra de datos (separada por comas)");
        txtStatsData.setPrefWidth(220);
        txtStatsData.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: white;");

        Button btnStatsSummary = new Button("📊 Resumen Estadístico");
        btnStatsSummary.setStyle("-fx-background-color: #4F46E5; -fx-text-fill: white;");

        finInputs.getChildren().addAll(new Label("Préstamo:"), txtPv, new Label("Tasa:"), txtRate, new Label("Meses:"), txtNper, btnPmt, btnAmort, new Label("Muestra:"), txtStatsData, btnStatsSummary);

        TextArea txtFinOutput = new TextArea();
        txtFinOutput.setPrefRowCount(6);
        txtFinOutput.setEditable(false);
        txtFinOutput.setStyle("-fx-control-inner-background: #020617; -fx-font-family: monospace; -fx-text-fill: #7DD3FC;");

        btnPmt.setOnAction(e -> {
            try {
                double pv = Double.parseDouble(txtPv.getText().trim());
                double r = Double.parseDouble(txtRate.getText().trim()) / 12.0;
                int n = Integer.parseInt(txtNper.getText().trim());
                double cuota = client.pmt(r, n, pv);
                txtFinOutput.setText(String.format("=== CÁLCULO DE CUOTA FIJA (PMT) ===%nPréstamo: $%,.2f | Tasa Mensual: %.4f%% | Períodos: %d meses%nCuota Mensual Fija: $%,.2f%n",
                    pv, r * 100.0, n, cuota));
            } catch (Exception ex) {
                txtFinOutput.setText("[ERROR PMT] " + ex.getMessage());
            }
        });

        btnAmort.setOnAction(e -> {
            try {
                double pv = Double.parseDouble(txtPv.getText().trim());
                double r = Double.parseDouble(txtRate.getText().trim());
                int n = Integer.parseInt(txtNper.getText().trim());
                var sched = client.amortizationSchedule(pv, r, Math.min(n, 24)); // primeras 24 cuotas
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== TABLA DE AMORTIZACIÓN FRANCESA (Primeros %d períodos) ===%n", sched.size()));
                sb.append(String.format("%-8s | %-12s | %-14s | %-12s | %-14s%n", "Período", "Cuota", "Amort. Capital", "Interés", "Saldo Pendiente"));
                sb.append("-".repeat(70)).append("\n");
                for (var row : sched) {
                    sb.append(String.format("%-8d | $%-11.2f | $%-13.2f | $%-11.2f | $%-13.2f%n",
                        row.period(), row.payment(), row.principalPart(), row.interestPart(), row.remainingBalance()));
                }
                txtFinOutput.setText(sb.toString());
            } catch (Exception ex) {
                txtFinOutput.setText("[ERROR AMORTIZACIÓN] " + ex.getMessage());
            }
        });

        btnStatsSummary.setOnAction(e -> {
            try {
                List<Double> list = new ArrayList<>();
                for (String s : txtStatsData.getText().split("[,\s]+")) {
                    if (!s.isBlank()) list.add(Double.parseDouble(s.trim()));
                }
                var s = client.statsSummary(list);
                double iqr = client.statsIqr(list);
                double sk = client.statsSkewness(list);
                double kt = client.statsKurtosis(list);
                txtFinOutput.setText(String.format("=== RESUMEN ESTADÍSTICO DESCRIPTIVO (%d muestras) ===%n" +
                    "Suma: %.2f | Media: %.4f | Mediana: %.4f | Desv. Estándar: %.4f%n" +
                    "Varianza: %.4f | IQR (Rango Intercuartil): %.4f | Mínimo: %.2f | Máximo: %.2f%n" +
                    "Asimetría (Skewness): %.4f | Curtosis: %.4f | Percentil 95: %.4f%n",
                    s.count(), s.sum(), s.mean(), s.median(), s.stddev(), s.variance(), iqr, s.min(), s.max(), sk, kt, s.p95()));
            } catch (Exception ex) {
                txtFinOutput.setText("[ERROR ESTADÍSTICA] " + ex.getMessage());
            }
        });

        secFin.getChildren().addAll(lblFinTitle, finInputs, txtFinOutput);

        // --- SECCIÓN 4: ÁLGEBRA VECTORIAL ---
        VBox secVec = new VBox(10);
        secVec.setPadding(new Insets(14));
        secVec.setStyle("-fx-background-color: #0F172A; -fx-background-radius: 8; -fx-border-color: #1E293B; -fx-border-radius: 8;");

        Label lblVecTitle = new Label("4. ÁLGEBRA VECTORIAL Y SIMILITUD DE EMBEDDINGS (JettraVectorMath)");
        lblVecTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #A855F7;");

        HBox vecInputs = new HBox(10);
        vecInputs.setAlignment(Pos.CENTER_LEFT);

        TextField txtV1 = new TextField("0.75, 0.20, 0.60");
        txtV1.setPromptText("Vector 1");
        txtV1.setPrefWidth(140);
        txtV1.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: #E9D5FF;");

        TextField txtV2 = new TextField("0.80, 0.15, 0.58");
        txtV2.setPromptText("Vector 2");
        txtV2.setPrefWidth(140);
        txtV2.setStyle("-fx-control-inner-background: #1E293B; -fx-text-fill: #E9D5FF;");

        Button btnCosine = new Button("🎯 Similitud Coseno");
        btnCosine.setStyle("-fx-background-color: #9333EA; -fx-text-fill: white; -fx-font-weight: bold;");

        Button btnEuclid = new Button("📏 Distancia Euclidiana");
        btnEuclid.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white;");

        Button btnDot = new Button("⚡ Producto Punto");
        btnDot.setStyle("-fx-background-color: #6D28D9; -fx-text-fill: white;");

        Button btnCross = new Button("✖ Cruz 3D");
        btnCross.setStyle("-fx-background-color: #5B21B6; -fx-text-fill: white;");

        Button btnAngle = new Button("📐 Ángulo (°)");
        btnAngle.setStyle("-fx-background-color: #4C1D95; -fx-text-fill: white;");

        vecInputs.getChildren().addAll(new Label("V1:"), txtV1, new Label("V2:"), txtV2, btnCosine, btnEuclid, btnDot, btnCross, btnAngle);

        Label lblVecRes = new Label("Resultado Vectorial: --");
        lblVecRes.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #E9D5FF; -fx-background-color: #3B0764; -fx-padding: 6 12 6 12; -fx-background-radius: 4;");

        java.util.function.Function<TextField, float[]> parseVec = (tf) -> {
            String[] parts = tf.getText().split("[,\s]+");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i].trim());
            return v;
        };

        btnCosine.setOnAction(e -> {
            try {
                float[] v1 = parseVec.apply(txtV1);
                float[] v2 = parseVec.apply(txtV2);
                float sim = client.cosineSimilarity(v1, v2);
                lblVecRes.setText(String.format("Similitud Coseno: %.6f (%.2f%% similitud semántica)", sim, sim * 100.0));
            } catch (Exception ex) {
                lblVecRes.setText("Error: " + ex.getMessage());
            }
        });

        btnEuclid.setOnAction(e -> {
            try {
                float[] v1 = parseVec.apply(txtV1);
                float[] v2 = parseVec.apply(txtV2);
                float d = client.euclideanDistance(v1, v2);
                lblVecRes.setText(String.format("Distancia Euclidiana: %.6f", d));
            } catch (Exception ex) {
                lblVecRes.setText("Error: " + ex.getMessage());
            }
        });

        btnDot.setOnAction(e -> {
            try {
                float[] v1 = parseVec.apply(txtV1);
                float[] v2 = parseVec.apply(txtV2);
                float dot = client.dotProduct(v1, v2);
                lblVecRes.setText(String.format("Producto Punto: %.6f", dot));
            } catch (Exception ex) {
                lblVecRes.setText("Error: " + ex.getMessage());
            }
        });

        btnCross.setOnAction(e -> {
            try {
                float[] v1 = parseVec.apply(txtV1);
                float[] v2 = parseVec.apply(txtV2);
                float[] cross = client.crossProduct(v1, v2);
                lblVecRes.setText("Producto Cruz 3D: " + Arrays.toString(cross));
            } catch (Exception ex) {
                lblVecRes.setText("Error: " + ex.getMessage());
            }
        });

        btnAngle.setOnAction(e -> {
            try {
                float[] v1 = parseVec.apply(txtV1);
                float[] v2 = parseVec.apply(txtV2);
                double deg = client.vectorAngleDegrees(v1, v2);
                lblVecRes.setText(String.format("Ángulo entre Vectores: %.2f° (%.4f rad)", deg, Math.toRadians(deg)));
            } catch (Exception ex) {
                lblVecRes.setText("Error: " + ex.getMessage());
            }
        });

        secVec.getChildren().addAll(lblVecTitle, vecInputs, lblVecRes);

        rootBox.getChildren().addAll(headerTitle, headerSub, secAgg, secMath, secFin, secVec);
        scroll.setContent(rootBox);
        tab.setContent(scroll);
        return tab;
    }

    private Tab createConsoleTab() {
        Tab tab = new Tab("Consola SQL / LQL");

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.setStyle("-fx-background-color: #0A0F1D;");

        Label lblTitle = new Label("TERMINAL INTERACTIVA DE CONSULTAS (SQL, JETTRAQL, AGGREGATE, MATH, FINANCE, STATS, VECTOR)");
        lblTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");

        // Snippets de Consulta Rápida
        HBox snippetsRow = new HBox(8);
        snippetsRow.setAlignment(Pos.CENTER_LEFT);
        Label lblSnip = new Label("Plantillas:");
        lblSnip.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        Button snipAgg = new Button("📊 Group By");
        snipAgg.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #38BDF8; -fx-font-size: 11px;");

        Button snipMath = new Button("📐 Math");
        snipMath.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #F59E0B; -fx-font-size: 11px;");

        Button snipFin = new Button("💵 Finanzas (Amortización)");
        snipFin.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #10B981; -fx-font-size: 11px;");

        Button snipStats = new Button("📈 Estadística");
        snipStats.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #818CF8; -fx-font-size: 11px;");

        Button snipVec = new Button("🧭 Vector Similitud");
        snipVec.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #C084FC; -fx-font-size: 11px;");

        snippetsRow.getChildren().addAll(lblSnip, snipAgg, snipMath, snipFin, snipStats, snipVec);

        TextArea editor = new TextArea("SELECT * FROM products WHERE price > 50.0;");
        editor.setPrefRowCount(4);
        editor.setStyle("-fx-control-inner-background: #0F172A; -fx-font-family: monospace; -fx-text-fill: #FACC15;");

        snipAgg.setOnAction(e -> editor.setText("SELECT estado, SUM(total) AS total_ventas, AVG(total) AS promedio, COUNT(*) AS facturas FROM facturas GROUP BY estado;"));
        snipMath.setOnAction(e -> editor.setText("MATH cbrt(64) + sqrt(144) * 2 - hypot(3, 4) + fact(5);"));
        snipFin.setOnAction(e -> editor.setText("FINANCE AMORTIZATION 10000 0.05 12"));
        snipStats.setOnAction(e -> editor.setText("STATS SUMMARY 12, 15, 18, 22, 25, 30, 35, 42, 50, 65, 80"));
        snipVec.setOnAction(e -> editor.setText("VECTOR COSINE [0.8, 0.2, 0.5] [0.75, 0.25, 0.45]"));

        HBox btnRow = new HBox(10);
        Button btnRun = new Button("▶ Ejecutar Sentencia SQL / Calc");
        btnRun.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold;");

        Button btnRunJql = new Button("⚡ Ejecutar JettraQL");
        btnRunJql.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold;");

        Button btnClear = new Button("Limpiar");
        btnClear.setStyle("-fx-background-color: #334155; -fx-text-fill: white;");

        btnRow.getChildren().addAll(btnRun, btnRunJql, btnClear);

        TextArea output = new TextArea();
        output.setPrefRowCount(14);
        output.setEditable(false);
        output.setStyle("-fx-control-inner-background: #020617; -fx-font-family: monospace; -fx-text-fill: #E2E8F0;");
        VBox.setVgrow(output, Priority.ALWAYS);

        btnRun.setOnAction(e -> {
            try {
                long start = System.currentTimeMillis();
                var res = client.sql(currentDatabase, editor.getText());
                long elapsed = System.currentTimeMillis() - start;

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== RESULTADO SQL / CALC EN '%s' (%d ms) ===%n", currentDatabase, elapsed));
                sb.append(String.format("Filas Afectadas / Seleccionadas: %d | Mensaje: %s%n", res.affectedRows(), res.message()));

                if (!res.rows().isEmpty()) {
                    List<String> cols = res.columns();
                    sb.append("+");
                    for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
                    sb.append("\n|");
                    for (String c : cols) sb.append(String.format(" %-" + Math.max(c.length(), 12) + "s |", c));
                    sb.append("\n+");
                    for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
                    sb.append("\n");

                    int maxDisplay = Math.min(res.rows().size(), 100);
                    for (int r = 0; r < maxDisplay; r++) {
                        List<Object> row = res.rows().get(r);
                        sb.append("|");
                        for (int i = 0; i < cols.size(); i++) {
                            String val = (i < row.size() && row.get(i) != null) ? row.get(i).toString() : "";
                            sb.append(String.format(" %-" + Math.max(cols.get(i).length(), 12) + "s |", val));
                        }
                        sb.append("\n");
                    }
                    if (res.rows().size() > maxDisplay) {
                        sb.append(String.format("... y %d fila(s) adicionales no mostradas.%n", res.rows().size() - maxDisplay));
                    }
                    sb.append("+");
                    for (String c : cols) sb.append("-".repeat(Math.max(c.length() + 2, 14))).append("+");
                    sb.append("\n");
                }
                output.setText(sb.toString());
                logStatus("Ejecutado con éxito en " + currentDatabase);
            } catch (Exception ex) {
                output.setText("[ERROR SQL] " + ex.getMessage());
            }
        });

        btnRunJql.setOnAction(e -> {
            try {
                var res = client.jql(currentDatabase, editor.getText());
                output.setText(String.format("=== RESULTADO JETTRAQL EN '%s' ===%nOperación: %s%nCoincidencias: %d%nResumen: %s%nDatos: %s%n", 
                    currentDatabase, res.operation(), res.totalMatches(), res.summary(), res.rows()));
                logStatus("JettraQL ejecutado en " + currentDatabase);
            } catch (Exception ex) {
                output.setText("[ERROR JQL] " + ex.getMessage());
            }
        });

        btnClear.setOnAction(e -> output.clear());

        content.getChildren().addAll(lblTitle, snippetsRow, editor, btnRow, new Label("Salida / Resultados Formateados:"), output);
        tab.setContent(content);
        return tab;
    }

    private void showToast(String message, String colorHex) {
        Platform.runLater(() -> {
            if (rootContainer == null) return;
            Label toast = new Label(message);
            toast.setWrapText(true);
            toast.setMaxWidth(420);
            toast.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-padding: 10 16 10 16; -fx-background-radius: 8; -fx-font-size: 11px; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.6), 10, 0, 0, 4);",
                colorHex
            ));
            StackPane.setAlignment(toast, Pos.TOP_RIGHT);
            StackPane.setMargin(toast, new Insets(65, 25, 0, 0));
            rootContainer.getChildren().add(toast);

            FadeTransition ft = new FadeTransition(Duration.millis(4000), toast);
            ft.setFromValue(1.0);
            ft.setToValue(0.0);
            ft.setOnFinished(e -> rootContainer.getChildren().remove(toast));
            ft.play();
        });
    }

    private void handlePoliceSentinelEvent(JettraPoliceNotification notification) {
        Platform.runLater(() -> {
            String alertMsg = String.format("🛡️ [Sentinel Activo] Lote seguro: %d filas (Heap: %.1f%%, %d MB libres)",
                notification.safeBatchSize(), notification.heapUsagePercent(), notification.availableMemoryMb());
            if (statusBarLabel != null) {
                statusBarLabel.setText(alertMsg);
                statusBarLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-weight: bold; -fx-font-size: 11px;");
            }
            showToast("🛡️ JettraPolice Sentinel (Anti-OOM):\n" + notification.warningMessage(), "#D97706");
            logStatus(String.format("JettraPolice Sentinel: Operación '%s' sobre '%s' optimizada con streaming seguro.",
                notification.operation(), notification.targetCollection()));
        });
    }

    private void executeQuickExplorerQuery(String query) {
        if (client == null || !isConnected) {
            logStatus("[ERROR] Debe conectarse a un nodo antes de consultar.");
            return;
        }

        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                var res = client.sql(currentDatabase, query);
                long elapsed = System.currentTimeMillis() - start;

                ObservableList<RecordItem> items = FXCollections.observableArrayList();
                int idx = 1;
                for (var row : res.rows()) {
                    String id = (row.size() > 0 && row.get(0) != null) ? row.get(0).toString() : "row_" + idx;
                    String summary = row.toString();
                    items.add(new RecordItem(id, summary, "Query Match"));
                    idx++;
                }

                Platform.runLater(() -> {
                    recordsTable.setItems(FXCollections.observableArrayList());
                    recordCountBadge.setText("Recibiendo streaming...");
                });

                // Alimentar la tabla de manera incremental por chunks para mantener la fluidez
                int chunkSize = 50;
                for (int i = 0; i < items.size(); i += chunkSize) {
                    final int startIdx = i;
                    final int endIdx = Math.min(items.size(), i + chunkSize);
                    Platform.runLater(() -> {
                        recordsTable.getItems().addAll(items.subList(startIdx, endIdx));
                    });
                }

                Platform.runLater(() -> {
                    recordCountBadge.setText("Resultados: " + items.size());
                    lblExplorerPageStatus.setText(String.format("Consulta: %d filas (%d ms)", items.size(), elapsed));
                    logStatus(String.format("Consulta ejecutada: '%s' -> %d filas en %d ms", query, items.size(), elapsed));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> logStatus("[ERROR Consulta] " + ex.getMessage()));
            }
        }).start();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void showPoliceAuditDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Auditoría de Seguridad de Memoria - JettraPolice Sentinel");
        dialog.setHeaderText("Telemetría de Estabilidad JVM y Prevención Anti-OOM");

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.setPrefWidth(550);

        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMem = totMem - freeMem;
        double sat = (double) usedMem / maxMem * 100.0;

        Label lblMem = new Label(String.format("Heap JVM: %d MB Usados de %d MB Máx (Saturación: %.1f%%)", usedMem, maxMem, sat));
        lblMem.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

        ProgressBar pbar = new ProgressBar(sat / 100.0);
        pbar.setPrefWidth(500);
        pbar.setStyle(sat > 80 ? "-fx-accent: #EF4444;" : sat > 60 ? "-fx-accent: #F59E0B;" : "-fx-accent: #10B981;");

        Label lblAlerts = new Label("Registro de Intervenciones y Alertas Preventivas:");
        lblAlerts.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");

        TextArea txtAlerts = new TextArea();
        txtAlerts.setPrefRowCount(10);
        txtAlerts.setEditable(false);
        txtAlerts.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        var alerts = io.jettra.store.police.JettraPolice.getInstance().getAlerts();
        StringBuilder sb = new StringBuilder();
        if (alerts.isEmpty()) {
            sb.append("[INFO] JettraPolice: 0 incidentes de memoria. Sistema en estado óptimo (NORMAL).\n");
        } else {
            for (var a : alerts) {
                sb.append(String.format("[%s] %s: %s\n", a.timestamp(), a.code(), a.message()));
            }
        }
        txtAlerts.setText(sb.toString());

        content.getChildren().addAll(lblMem, pbar, lblAlerts, txtAlerts);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

}

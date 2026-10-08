package io.jettra.core.three.d;

import java.util.*;
import io.jettra.core.three.d.explorer.EngineIndexInfo;
import io.jettra.core.three.d.explorer.RecordVersion;
import io.jettra.core.three.d.explorer.RecordFieldInfo;
import io.jettra.core.three.d.backup.BackupManager;
import io.jettra.core.three.d.backup.BackupSnapshot;
import io.jettra.core.three.d.config.ClusterConfigLoader;

import static com.raylib.Raylib.*;
import com.raylib.Color;
import com.raylib.Vector3;
import com.raylib.Vector2;
import com.raylib.Rectangle;
import com.raylib.Camera3D;
import com.raylib.Font;

import io.jettra.core.three.d.model.Artifact;
import io.jettra.core.three.d.model.HumanEntity;
import io.jettra.core.three.d.model.MegaProject;
import io.jettra.core.three.d.model.Thought;
import io.jettra.core.three.d.model.WorldEvent;
import io.jettra.core.three.d.model.ServerNode3D;
import io.jettra.core.three.d.model.DatabaseInfo3D;
import io.jettra.core.three.d.police.JettraStorePoliceMonitor;
import io.jettra.core.three.d.voice.JettraVoiceNarrator;
import io.jettra.core.three.d.config.ConnectionManager;
import io.jettra.core.three.d.config.ConnectionProfile;
import io.jettra.core.three.d.model.ClusterDataTraffic;
import io.jettra.core.three.d.model.JettraLiveSession;
import io.jettra.core.three.d.model.JettraPoliceAgent;
import io.jettra.core.three.d.model.UserZoneGroup;
import io.jettra.store.cluster.ClusterNode;
import com.raylib.BoundingBox;
import com.raylib.Ray;
import com.raylib.RayCollision;
import static com.raylib.Raylib.ConfigFlags.*;
import static com.raylib.Raylib.KeyboardKey.*;
import static com.raylib.Raylib.MouseButton.*;
import static com.raylib.Raylib.CameraProjection.*;
import static com.raylib.Raylib.CameraMode.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import io.jettra.core.three.d.security.UserManager;
import io.jettra.core.three.d.security.JettraUser;
import io.jettra.core.three.d.security.JettraDatabaseRole;
import io.jettra.core.three.d.security.DatabasePermission;
import io.jettra.core.three.d.explorer.EngineDataCatalog;
import io.jettra.core.three.d.explorer.EngineBucket;
import io.jettra.core.three.d.explorer.EngineRecord;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;

public class Jettra3DApp {
    private static final int SCREEN_WIDTH = 1280;
    private static final int SCREEN_HEIGHT = 720;

    private Font mainFont;
    private Camera3D camera;
    private List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private List<HumanEntity> entities = new CopyOnWriteArrayList<>();
    private List<MegaProject> megaProjects = new CopyOnWriteArrayList<>();
    private List<Thought> thoughts = new CopyOnWriteArrayList<>();
    private List<WorldEvent> worldEvents = new CopyOnWriteArrayList<>();

    private float worldTime = 0;

    private void triggerWorldEvent(String text, int r, int g, int b) {
        WorldEvent ev = new WorldEvent(text, worldTime, r, g, b);
        worldEvents.add(ev);
        if (worldEvents.size() > 60) {
            worldEvents.remove(0);
        }
        if (voiceEnabled) {
            JettraVoiceNarrator.getInstance().speak(text);
        }
    }

    // Panel de Gestión de Conexiones a JettraStore
    private boolean showConnectionModal = false;

    // --- GESTIÓN DE USUARIOS Y ROLES (USER MANAGEMENT PANEL) ---
    private boolean showUserManagerModal = false;
    private String formUserId = "";
    private String formUsername = "dev_jettra";
    private String formPassword = "password123";
    private String formFullName = "Desarrollador Multimodelo JettraStore";
    private String formEmail = "dev@jettra.io";
    private JettraUser.GlobalRole formGlobalRole = JettraUser.GlobalRole.DEVELOPER;
    private Map<String, JettraDatabaseRole> formDbRoles = new LinkedHashMap<>();
    private int activeUserField = 0; // 0: Ninguno, 1: Username, 2: Password, 3: FullName, 4: Email
    private String selectedUserListId = "usr_003";
    private String userStatusFeedback = "";
    private float userStatusFeedbackTimer = 0f;

    // --- EXPLORADOR DE OBJETOS POR ENGINE (ENGINE EXPLORER PANEL) ---
    private boolean showEngineExplorerModal = false;
    private boolean explorerMaximized = false;
    private int explorerActiveTab = 0; // 0: REGISTROS Y OBJETOS, 1: ADMINISTRACIÓN DE ÍNDICES
    private String selectedExplorerDb = "example_factura_db";
    private String selectedEngineType = "DOCUMENT";
    private String selectedBucketName = "facturas";
    private int explorerPageIndex = 0;
    private int explorerPageSize = 5;
    private Set<String> expandedEngines = new HashSet<>(Set.of("DOCUMENT", "GRAPH", "VECTOR"));
    private EngineRecord selectedExplorerRecord = null;

    // Filtro y Buscador JettraQL / JettraSQL
    private String explorerFilterText = "";
    private String explorerSearchQuery = "";
    private boolean explorerQueryIsSql = false; // false: JettraQL, true: JettraSQL
    private boolean explorerHasActiveQuery = false;
    private List<EngineRecord> explorerFilteredRecords = new ArrayList<>();
    private String explorerQueryFeedback = "";
    private float explorerQueryFeedbackTimer = 0f;
    private boolean showExplorerQueryHelp = false;
    private int explorerActiveInputFocus = 0; // 0: ninguno, 1: filtro rápido, 2: buscador JQL/SQL

    // Operaciones CRUD de Registros (Agregar, Editar, Eliminar)
    // Panel de Respaldos y Restauración (Backup & Restore)
    private boolean showBackupModal = false;
    private int backupActiveTab = 0; // 0: CREAR, 1: RESTAURAR
    private String backupSelectedDb = "example_factura_db";
    private String backupSelectedEngine = "MULTIMODEL_SNAPSHOT";
    private String backupSelectedAlgo = "ZSTD_SIMD";
    private String backupSelectedSnapshotId = "BKP-FAC-20261001-0800";
    private String backupTargetRestoreDb = "example_factura_db";
    private boolean backupOverwrite = true;

    // Visualizador de Registros Multimodelo adaptado por Engine
    private boolean showRecordViewModal = false;
    private int recordViewScroll = 0;
    private List<RecordFieldInfo> formRecordFields = new ArrayList<>();

    private boolean showRecordOperationModal = false;
    private String recordOpMode = ""; // "ADD", "EDIT", "DELETE_CONFIRM"
    private String formRecordId = "";
    private String formRecordSummary = "";
    private String formRecordDetails = "";
    private int formRecordActiveField = 1; // 1: ID, 2: Summary, 3: Details
    private String recordOpFeedback = "";
    private float recordOpFeedbackTimer = 0f;

    // Restauración de Versiones de Registro
    private boolean showVersionHistoryModal = false;
    private int selectedVersionNumber = -1;
    private String versionHistoryFeedback = "";
    private float versionHistoryFeedbackTimer = 0f;

    // Administración de Índices
    private boolean showIndexOpModal = false;
    private String indexOpMode = ""; // "CREATE", "EDIT", "DROP_CONFIRM"
    private String formIndexName = "";
    private String formIndexField = "";
    private String formIndexType = "BTREE";
    private boolean formIndexUnique = false;
    private int formIndexActiveField = 1; // 1: Name, 2: Field
    private EngineIndexInfo selectedIndexInfo = null;
    private String indexOpFeedback = "";
    private float indexOpFeedbackTimer = 0f;

    // Timer de sincronización dinámica de entidades con telemetría del servidor en tiempo real
    private float entitySyncTimer = 0f;
    private boolean innerPanelLocked = true;
    private String formConnId = "";
    private String formConnName = "JettraStore Local Master";
    private String formConnUrl = "tcp://127.0.0.1:8765";
    private String formConnUsername = "admin";
    private String formConnPassword = "admin";
    private boolean formConnIsDefault = true;
    private int activeConnField = 0; // 0: Ninguno, 1: Nombre, 2: URL, 3: Usuario, 4: Password
    private String selectedProfileId = "";
    private String connStatusFeedback = "";
    private float connStatusFeedbackTimer = 0f;
    private int selectedAgentIndex = -1;
    private int selectedArtifactIndex = -1;
    private boolean followMode = false;
    private boolean directorMode = false;
    private float directorTimer = 0;
    private float planeScale = 1.0f;
    private boolean isAnchored = true;
    private boolean cameraLocked = false;
    private boolean showHelp = true;
    private boolean showConfigModal = false;
    private boolean voiceEnabled = true;

    // Config Toggles
    private boolean configEnabledApps = true;
    private boolean configEnabledInternet = true;
    private boolean configEnabledFiles = true;
    private boolean configEnabledLife = true;
    private boolean configEnabledSocial = true;
    
    // Chat & Knowledge UI
    private boolean showChat = false;
    private String chatInput = "";
    private List<String> chatHistory = new java.util.ArrayList<>();
    private List<KnowledgeEntry> allKnowledge = new java.util.ArrayList<>();
    private List<KnowledgeEntry> topKnowledge = new java.util.ArrayList<>();
    private float topKnowledgeUpdateTimer = 0;

    private float howlTimer = 0;
    private float globalSaveTimer = 60.0f; // Save state every 60 seconds
    private float pdfScanTimer = 10.0f;
    private float timeScale = 1.0f;
    private int weatherMode = 0; // 0: Sunny, 1: Night, 2: Storm
    private boolean sfxEnabled = true;

    // JettraStore Cluster Monitoring & Police Sentinel
    private JettraStorePoliceMonitor policeMonitor;

    // Modos de mundo e inmersión submundos (Nodos / Bases de Datos)
    public enum WorldMode {
        MAIN_WORLD,
        EXPANDING_TRANSITION,
        INNER_NODE_WORLD,
        EXITING_TRANSITION
    }

    private WorldMode worldMode = WorldMode.MAIN_WORLD;
    private ServerNode3D expandedNode = null;
    private DatabaseInfo3D selectedDatabase = null;
    private float transitionTimer = 0f;
    private float innerWorldTime = 0f;
    private Vector3 savedMainCameraPos = new Vector3().x(35).y(35).z(35);
    private Vector3 savedMainCameraTarget = new Vector3().x(0).y(0).z(0);
    private float savedMainCameraFov = 45f;
    private ServerNode3D selectedServerNode = null;
    private boolean showNodeInspectorModal = false;
    private HumanEntity policeSentinelEntity = null;
    private float policePatrolTimer = 0f;
    private int currentPatrolTargetNodeIndex = 0;

    public void run() {
        setConfigFlags(FLAG_WINDOW_RESIZABLE);
        initWindow(SCREEN_WIDTH, SCREEN_HEIGHT, "Jettra 3D Core - Java 25 Native");
        maximizeWindow();
        setTargetFPS(60);

        initAudioDevice();
        initSfx();

        try {
            int count = 512;
            java.nio.IntBuffer codepoints = java.nio.ByteBuffer.allocateDirect(count * Integer.BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .asIntBuffer();
            for (int i = 0; i < count; i++) {
                codepoints.put(i, 32 + i);
            }
            mainFont = loadFontEx("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf", 22, codepoints, count);
            if (mainFont != null && mainFont.glyphCount() > 0) {
                setTextureFilter(mainFont.texture(), 1); // 1 = FILTER_TRILINEAR
            }
        } catch (Throwable t) {
            mainFont = loadFont("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf");
        }

        initCamera();
        initPoliceMonitor();
        initPopulation();

        while (!windowShouldClose()) {
            float dt = getFrameTime();
            float scaledDt = dt * timeScale;
            worldTime += scaledDt;

            handleInput();
            update(scaledDt);
            draw();
        }

        if (policeMonitor != null) {
            policeMonitor.close();
        }
        closeAudioDevice();
        closeWindow();
    }

    private void initCamera() {
        camera = new Camera3D();
        camera.position(new Vector3().x(35).y(35).z(35));
        camera.target(new Vector3().x(0).y(0).z(0));
        camera.up(new Vector3().x(0).y(1).z(0));
        camera.fovy(45);
        camera.projection(CAMERA_PERSPECTIVE);
    }
    private void handleInput() {
        if (showConfigModal) return;

        // Atajo 'K': Abrir / Cerrar Panel de Gestión de Conexiones
        if (isKeyPressed(KEY_K)) {
            showConnectionModal = !showConnectionModal;
            if (showConnectionModal) {
                loadSelectedProfileIntoForm();
            }
        }

        // Atajo 'U': Abrir / Cerrar Panel de Gestión de Usuarios y Roles
        if (isKeyPressed(KEY_U)) {
            showUserManagerModal = !showUserManagerModal;
            if (showUserManagerModal) {
                initUserFormWithSelection();
            }
        }

        // Atajo 'B': Abrir / Cerrar Panel de Respaldos y Restauración
        if (isKeyPressed(KEY_B)) {
            showBackupModal = !showBackupModal;
        }

        // Atajo 'E': Abrir / Cerrar Explorador Multimodelo de Motores y Registros
        if (isKeyPressed(KEY_E)) {
            showEngineExplorerModal = !showEngineExplorerModal;
            if (showBackupModal) {
            if (isKeyPressed(KEY_ESCAPE)) {
                showBackupModal = false;
            }
            return;
        }

        if (showEngineExplorerModal) {
                openEngineExplorerModal(selectedDatabase != null ? selectedDatabase.getId() : "example_factura_db");
            }
        }

        // Atajo 'R': Resetear el mundo e interactuar con JettraStore en tiempo real
        if (isKeyPressed(KEY_R) && worldMode == WorldMode.MAIN_WORLD) {
            resetWorldWithJettraStore();
        }

        if (isKeyPressed(KEY_TAB)) showHelp = !showHelp;
        if (isKeyPressed(KEY_C)) {
            initCamera();
            followMode = false;
            cameraLocked = false;
        }
        if (isKeyPressed(KEY_F)) {
            followMode = !followMode;
            if (followMode && selectedAgentIndex == -1 && !entities.isEmpty()) {
                selectedAgentIndex = 0;
            }
        }
        if (isKeyPressed(KEY_L)) isAnchored = !isAnchored;
        
        // Chat Toggle
        if (isKeyPressed(KEY_ENTER)) {
            if (showChat && !chatInput.trim().isEmpty()) {
                sendChatMessage(chatInput);
                chatInput = "";
            } else {
                showChat = !showChat;
            }
        }

        if (showEngineExplorerModal) {
            if (showRecordViewModal) {
                if (isKeyPressed(KEY_ESCAPE)) showRecordViewModal = false;
                return;
            }
            if (showExplorerQueryHelp) {
                if (isKeyPressed(KEY_ESCAPE)) showExplorerQueryHelp = false;
                return;
            }
            if (showVersionHistoryModal) {
                if (isKeyPressed(KEY_ESCAPE)) showVersionHistoryModal = false;
                return;
            }
            if (showRecordOperationModal) {
                if (isKeyPressed(KEY_ESCAPE)) {
                    showRecordOperationModal = false;
                }
                if (isKeyPressed(KEY_TAB)) {
                    formRecordActiveField = (formRecordActiveField >= 3) ? 1 : formRecordActiveField + 1;
                }
                int key = getCharPressed();
                while (key > 0) {
                    if ((key >= 32) && (key <= 126)) {
                        char c = (char) key;
                        switch (formRecordActiveField) {
                            case 1 -> formRecordId += c;
                            case 2 -> formRecordSummary += c;
                            case 3 -> formRecordDetails += c;
                        }
                    }
                    key = getCharPressed();
                }
                if (isKeyPressed(KEY_BACKSPACE)) {
                    switch (formRecordActiveField) {
                        case 1 -> { if (!formRecordId.isEmpty()) formRecordId = formRecordId.substring(0, formRecordId.length() - 1); }
                        case 2 -> { if (!formRecordSummary.isEmpty()) formRecordSummary = formRecordSummary.substring(0, formRecordSummary.length() - 1); }
                        case 3 -> { if (!formRecordDetails.isEmpty()) formRecordDetails = formRecordDetails.substring(0, formRecordDetails.length() - 1); }
                    }
                }
                if (isKeyPressed(KEY_ENTER) && formRecordActiveField == 3) {
                    formRecordDetails += "\n";
                }
                return;
            }
            if (showIndexOpModal) {
                if (isKeyPressed(KEY_ESCAPE)) {
                    showIndexOpModal = false;
                }
                if (isKeyPressed(KEY_TAB)) {
                    formIndexActiveField = (formIndexActiveField == 1) ? 2 : 1;
                }
                int key = getCharPressed();
                while (key > 0) {
                    if ((key >= 32) && (key <= 126)) {
                        char c = (char) key;
                        switch (formIndexActiveField) {
                            case 1 -> formIndexName += c;
                            case 2 -> formIndexField += c;
                        }
                    }
                    key = getCharPressed();
                }
                if (isKeyPressed(KEY_BACKSPACE)) {
                    switch (formIndexActiveField) {
                        case 1 -> { if (!formIndexName.isEmpty()) formIndexName = formIndexName.substring(0, formIndexName.length() - 1); }
                        case 2 -> { if (!formIndexField.isEmpty()) formIndexField = formIndexField.substring(0, formIndexField.length() - 1); }
                    }
                }
                return;
            }

            if (isKeyPressed(KEY_ESCAPE)) {
                showEngineExplorerModal = false;
                return;
            }

            if (explorerActiveInputFocus == 1) { // Quick Filter
                int key = getCharPressed();
                while (key > 0) {
                    if ((key >= 32) && (key <= 126)) {
                        explorerFilterText += (char) key;
                    }
                    key = getCharPressed();
                }
                if (isKeyPressed(KEY_BACKSPACE) && !explorerFilterText.isEmpty()) {
                    explorerFilterText = explorerFilterText.substring(0, explorerFilterText.length() - 1);
                }
            } else if (explorerActiveInputFocus == 2) { // Search Query (JQL/SQL)
                int key = getCharPressed();
                while (key > 0) {
                    if ((key >= 32) && (key <= 126)) {
                        explorerSearchQuery += (char) key;
                    }
                    key = getCharPressed();
                }
                if (isKeyPressed(KEY_BACKSPACE) && !explorerSearchQuery.isEmpty()) {
                    explorerSearchQuery = explorerSearchQuery.substring(0, explorerSearchQuery.length() - 1);
                }
                if (isKeyPressed(KEY_ENTER)) {
                    executeExplorerQuery();
                }
            }
            return;
        }

        if (showConnectionModal) {
            if (isKeyPressed(KEY_ESCAPE)) {
                showConnectionModal = false;
            }
            if (isKeyPressed(KEY_TAB)) {
                activeConnField = (activeConnField >= 4) ? 1 : activeConnField + 1;
            }
            int key = getCharPressed();
            while (key > 0) {
                if ((key >= 32) && (key <= 126)) {
                    char c = (char) key;
                    switch (activeConnField) {
                        case 1 -> formConnName += c;
                        case 2 -> formConnUrl += c;
                        case 3 -> formConnUsername += c;
                        case 4 -> formConnPassword += c;
                    }
                }
                key = getCharPressed();
            }
            if (isKeyPressed(KEY_BACKSPACE)) {
                switch (activeConnField) {
                    case 1 -> { if (!formConnName.isEmpty()) formConnName = formConnName.substring(0, formConnName.length() - 1); }
                    case 2 -> { if (!formConnUrl.isEmpty()) formConnUrl = formConnUrl.substring(0, formConnUrl.length() - 1); }
                    case 3 -> { if (!formConnUsername.isEmpty()) formConnUsername = formConnUsername.substring(0, formConnUsername.length() - 1); }
                    case 4 -> { if (!formConnPassword.isEmpty()) formConnPassword = formConnPassword.substring(0, formConnPassword.length() - 1); }
                }
            }
            return;
        }

        if (showChat) {
            int key = getCharPressed();
            while (key > 0) {
                if ((key >= 32) && (key <= 125)) {
                    chatInput += (char)key;
                }
                key = getCharPressed();
            }
            if (isKeyPressed(KEY_BACKSPACE) && !chatInput.isEmpty()) {
                chatInput = chatInput.substring(0, chatInput.length() - 1);
            }
        }

        float wheel = getMouseWheelMove();
        if (wheel != 0 && !(worldMode == WorldMode.INNER_NODE_WORLD && innerPanelLocked)) {
            camera.fovy(camera.fovy() - wheel * 2);
            if (camera.fovy() < 5) camera.fovy(5);
            if (camera.fovy() > 120) camera.fovy(120);
        }

        // --- MODO MUNDO PRINCIPAL: CLIC IZQUIERDO PARA EXPANDIR NODO ---
        if (worldMode == WorldMode.MAIN_WORLD) {
            if (isMouseButtonPressed(MOUSE_BUTTON_LEFT) && !showConfigModal && !showNodeInspectorModal && !showChat) {
                Vector2 mouse = getMousePosition();
                Ray ray = getScreenToWorldRay(mouse, camera);
                if (policeMonitor != null) {
                    float minDist = Float.MAX_VALUE;
                    ServerNode3D hitNode = null;
                    for (ServerNode3D node : policeMonitor.getServerNodes()) {
                        RayCollision col = getRayCollisionBox(ray, node.getBoundingBox());
                        if (col.hit() && col.distance() < minDist) {
                            minDist = col.distance();
                            hitNode = node;
                        }
                    }
                    if (hitNode != null) {
                        startNodeExpansion(hitNode);
                        return;
                    }
                }
            }
        }

        // --- MODO MUNDO INTERIOR DEL NODO: CLIC EN PUERTA O EN BASES DE DATOS ---
        if (worldMode == WorldMode.INNER_NODE_WORLD) {
            if (isKeyPressed(KEY_ESCAPE) || isKeyPressed(KEY_BACKSPACE) || isKeyPressed(KEY_P)) {
                if (selectedDatabase != null) {
                    selectedDatabase = null;
                } else {
                    startExitTransition();
                }
                return;
            }

            if (isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                Vector2 mouse = getMousePosition();

                // 1. Botón cerrar detalle de base de datos
                if (selectedDatabase != null) {
                    Rectangle detailCloseRec = new Rectangle().x(getScreenWidth() / 2 + 200).y(120).width(30).height(30);
                    if (checkCollisionPointRec(mouse, detailCloseRec)) {
                        selectedDatabase = null;
                        return;
                    }
                }

                // 2. Botón superior: Atravesar Puerta y Salir
                Rectangle topExitBtnRec = new Rectangle().x(getScreenWidth() - 410).y(12).width(390).height(36);
                if (checkCollisionPointRec(mouse, topExitBtnRec)) {
                    startExitTransition();
                    return;
                }

                // 3. Botón desconectar / reconectar nodo en panel de recursos
                if (expandedNode != null) {
                    Rectangle toggleRec = new Rectangle().x(35).y(515).width(350).height(32);
                    if (checkCollisionPointRec(mouse, toggleRec)) {
                        expandedNode.toggleOffline();
                        worldEvents.add(new WorldEvent(
                            "JettraStorePolice: Servidor " + expandedNode.getId() + " conmutado a " + (expandedNode.isOnline() ? "EN LÍNEA" : "FUERA DE SERVICIO"),
                            worldTime, expandedNode.isOnline() ? 50 : 255, expandedNode.isOnline() ? 255 : 50, 50));
                        return;
                    }
                }

                // 4. Raycasting en el Mundo 3D
                Ray ray = getScreenToWorldRay(mouse, camera);

                // Clic en la Puerta Dimensional de Regreso
                RayCollision doorCol = getRayCollisionBox(ray, getDoorBoundingBox());
                if (doorCol.hit()) {
                    startExitTransition();
                    return;
                }

                // Clic en alguna Base de Datos 3D
                if (expandedNode != null) {
                    float minDbDist = Float.MAX_VALUE;
                    DatabaseInfo3D hitDb = null;
                    for (DatabaseInfo3D db : expandedNode.getDatabases()) {
                        RayCollision dbCol = getRayCollisionBox(ray, db.getBoundingBox());
                        if (dbCol.hit() && dbCol.distance() < minDbDist) {
                            minDbDist = dbCol.distance();
                            hitDb = db;
                        }
                    }
                    if (hitDb != null) {
                        selectedDatabase = hitDb;
                        return;
                    }
                }
            }
        }

        // Clic Derecho en Bases de Datos (Mundo Interior) o en Nodos (Mundo Principal)
        if (isMouseButtonPressed(MOUSE_BUTTON_RIGHT)) {
            Vector2 mouse = getMousePosition();
            Ray ray = getScreenToWorldRay(mouse, camera);

            // En el mundo interior del nodo, clic derecho en base de datos abre el Explorador de Motores
            if (worldMode == WorldMode.INNER_NODE_WORLD && expandedNode != null) {
                float minDbDist = Float.MAX_VALUE;
                DatabaseInfo3D hitDb = null;
                for (DatabaseInfo3D db : expandedNode.getDatabases()) {
                    RayCollision dbCol = getRayCollisionBox(ray, db.getBoundingBox());
                    if (dbCol.hit() && dbCol.distance() < minDbDist) {
                        minDbDist = dbCol.distance();
                        hitDb = db;
                    }
                }
                if (hitDb != null) {
                    openEngineExplorerModal(hitDb.getId());
                    return;
                }
            }
            ServerNode3D clicked = null;
            float minDistance = Float.MAX_VALUE;

            if (policeMonitor != null) {
                for (ServerNode3D node : policeMonitor.getServerNodes()) {
                    RayCollision col = getRayCollisionBox(ray, node.getBoundingBox());
                    if (col.hit() && col.distance() < minDistance) {
                        minDistance = col.distance();
                        clicked = node;
                    }
                }
            }

            if (clicked != null) {
                selectedServerNode = clicked;
                showNodeInspectorModal = true;
            } else if (showNodeInspectorModal) {
                Rectangle modalRec = new Rectangle()
                    .x((getScreenWidth() - 560) / 2)
                    .y((getScreenHeight() - 480) / 2)
                    .width(560)
                    .height(530);
                if (!checkCollisionPointRec(mouse, modalRec)) {
                    showNodeInspectorModal = false;
                }
            }
        }

        if (isKeyPressed(KEY_ESCAPE) && showNodeInspectorModal) {
            showNodeInspectorModal = false;
        }

        if (isKeyPressed(KEY_N) && policeMonitor != null) {
            List<ServerNode3D> nodes = policeMonitor.getServerNodes();
            if (!nodes.isEmpty()) {
                if (selectedServerNode == null) {
                    selectedServerNode = nodes.get(0);
                } else {
                    int idx = nodes.indexOf(selectedServerNode);
                    selectedServerNode = nodes.get((idx + 1) % nodes.size());
                }
                showNodeInspectorModal = true;
            }
        }
    }

    private void update(float dt) {
        if (worldMode == WorldMode.EXPANDING_TRANSITION) {
            transitionTimer -= dt;
            if (transitionTimer <= 0) {
                worldMode = WorldMode.INNER_NODE_WORLD;
                camera.position(new Vector3().x(0).y(4.5f).z(12f));
                camera.target(new Vector3().x(0).y(2.0f).z(0));
                camera.fovy(50);
                worldEvents.add(new WorldEvent(
                    "¡Bienvenido al Mundo Interior del Servidor '" + expandedNode.getId() + "'!",
                    worldTime, 0, 255, 180));
            }
            return;
        }

        if (worldMode == WorldMode.EXITING_TRANSITION) {
            transitionTimer -= dt;
            if (transitionTimer <= 0) {
                worldMode = WorldMode.MAIN_WORLD;
                camera.position(new Vector3().x(savedMainCameraPos.x()).y(savedMainCameraPos.y()).z(savedMainCameraPos.z()));
                camera.target(new Vector3().x(savedMainCameraTarget.x()).y(savedMainCameraTarget.y()).z(savedMainCameraTarget.z()));
                camera.fovy(savedMainCameraFov);
                expandedNode = null;
                selectedDatabase = null;
                worldEvents.add(new WorldEvent("Retornado al Mundo Principal de la Ciudad.", worldTime, 100, 255, 100));
            }
            return;
        }

        if (worldMode == WorldMode.INNER_NODE_WORLD) {
            innerWorldTime += dt;
            if (!cameraLocked && !innerPanelLocked) {
                updateCamera(camera, CAMERA_FREE);
            }
            if (expandedNode != null) {
                for (DatabaseInfo3D db : expandedNode.getDatabases()) {
                    db.setPulsePhase(db.getPulsePhase() + dt * 2.5f);
                }
            }

            // Proximidad a la Puerta: pulsar 'E' para atravesarla
            float cdx = camera.position().x();
            float cdz = camera.position().z() - (-11.0f);
            if ((cdx * cdx + cdz * cdz) < 6.0f && isKeyPressed(KEY_E)) {
                startExitTransition();
            }
            return;
        }

        if (followMode && selectedAgentIndex != -1 && selectedAgentIndex < entities.size()) {
            HumanEntity e = entities.get(selectedAgentIndex);
            camera.target(new Vector3().x(e.x).y(e.y).z(e.z));
            camera.position(new Vector3().x(e.x + 10).y(e.y + 10).z(e.z + 10));
        } else if (!cameraLocked && !isAnchored) {
            updateCamera(camera, CAMERA_FREE);
        }

        updateEntities(dt);
        updateThoughts(dt);
        updatePolicePatrol(dt);
        
        topKnowledgeUpdateTimer += dt;
        if (topKnowledgeUpdateTimer > 2.0f) {
            updateTopKnowledge();
            topKnowledgeUpdateTimer = 0;
        }

        if (directorMode) {
            updateDirectorMode(dt);
        }

        updateSfx();
        
        if (howlTimer > 0) howlTimer -= dt;
        checkPackSafety(dt);

        globalSaveTimer -= dt;
        if (globalSaveTimer <= 0) {
            saveWorldState();
            globalSaveTimer = 60.0f;
        }

        pdfScanTimer -= dt;
        if (pdfScanTimer <= 0) {
            ingestPdfs();
            pdfScanTimer = 10.0f;
        }
    }

    private void checkPackSafety(float dt) {
        if (weatherMode == 2 && howlTimer <= 0) {
            long total = 0;
            long sheltered = 0;
            HumanEntity jettra = null;
            
            for (HumanEntity e : entities) {
                if (e.name.contains("Jettra")) { jettra = e; continue; }
                if (e.isCar || e.isAnimal) continue;
                total++;
                if ("SHELTERED".equals(e.action)) sheltered++;
            }
            
            if (total > 0 && sheltered == total && jettra != null) {
                // Reward agents for successful survival tactics
                for (HumanEntity re : entities) {
                    if ("SHELTERED".equals(re.action) && !re.name.contains("Jettra")) {
                        re.intelligence += 2;
                        re.currentThought = "He aprendido a sobrevivir (+2 Intel)";
                        re.thoughtTimer = 4.0f;
                        final HumanEntity fRe = re;
                        Thought t = new Thought();
                        t.content = "LEARNED: SURVIVAL (+2 Intel)";
                        t.owner = fRe; t.x = fRe.x; t.y = fRe.y + 3.0f; t.z = fRe.z;
                        t.timer = 4.0f; t.isThought = false;
                        thoughts.add(t);
                    }
                }

                final String msg = "¡AWOOOOOOOOOO! La manada está a salvo de la tormenta.";
                final HumanEntity fJettra = jettra;
                jettra.currentThought = msg;
                jettra.thoughtTimer = 6.0f;
                Thought t = new Thought();
                t.content = msg; t.owner = fJettra;
                t.x = fJettra.x; t.y = fJettra.y + 4.0f; t.z = fJettra.z;
                t.timer = 6.0f; t.isThought = false;
                thoughts.add(t);
                worldEvents.add(new WorldEvent("Aullido de Jettra: Supervivencia Exitosa", worldTime, 255, 255, 0));
                howlTimer = 40.0f; // Relax for 40s
            }
        }
    }

    private void ingestPdfs() {
        if (!configEnabledFiles) return;
        java.io.File dir = new java.io.File("memory/pdfs");
        if (!dir.exists()) dir.mkdirs();
        java.io.File processedDir = new java.io.File("memory/pdfs/processed");
        if (!processedDir.exists()) processedDir.mkdirs();

        java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) return;

        for (java.io.File pdf : files) {
            try (PDDocument document = PDDocument.load(pdf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document).toLowerCase();
                String topic = pdf.getName().replace(".pdf", "");
                
                KnowledgeEntry entry = new KnowledgeEntry();
                entry.topic = "Conocimiento de " + topic;
                entry.source = "Archivo PDF: " + pdf.getName();
                entry.x = (float)(Math.random() * 80 - 40);
                entry.y = 5.0f;
                entry.z = (float)(Math.random() * 80 - 40);
                entry.confidence = 0.95;
                entry.keywords = new String[]{topic, "pdf", "lectura"};
                
                if (allKnowledge == null) allKnowledge = new java.util.ArrayList<>();
                allKnowledge.add(entry);
                
                // Save knowledge globally
                try {
                    java.io.File kFile = new java.io.File("memory/world/knowledge.json");
                    com.fasterxml.jackson.databind.ObjectMapper tempMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    tempMapper.writerWithDefaultPrettyPrinter().writeValue(kFile, allKnowledge);
                } catch (Exception e) {}

                worldEvents.add(new WorldEvent("PDF Asimilado: " + pdf.getName(), worldTime, 255, 215, 0));

                if (text.contains("salud") || text.contains("medicina") || text.contains("cura") || text.contains("health")) {
                    for (HumanEntity e : entities) {
                        e.health = Math.min(100, e.health + 30);
                        e.infectionLevel = Math.max(0, e.infectionLevel - 20);
                    }
                    worldEvents.add(new WorldEvent("Avance Médico aplicado", worldTime, 0, 255, 100));
                }
                if (text.contains("construcción") || text.contains("arquitectura") || text.contains("ingeniería")) {
                    for (MegaProject p : megaProjects) {
                        if (!p.finished) p.progress += 0.2f;
                    }
                    worldEvents.add(new WorldEvent("MegaProyectos acelerados", worldTime, 255, 200, 0));
                }
                if (text.contains("psicología") || text.contains("sociedad") || text.contains("comunidad")) {
                    for (HumanEntity e : entities) {
                        e.mood = Math.min(100, e.mood + 25);
                    }
                    worldEvents.add(new WorldEvent("Bienestar Social incrementado", worldTime, 255, 100, 255));
                }

                pdf.renameTo(new java.io.File(processedDir, pdf.getName()));
            } catch (Exception ex) {
                System.err.println("Error procesando PDF " + pdf.getName() + ": " + ex.getMessage());
            }
        }
    }

    private void updateEntities(float dt) {
        entitySyncTimer += dt;
        if (entitySyncTimer >= 1.0f) {
            entitySyncTimer = 0f;
            syncDynamicEntitiesWithPoliceMonitor();
        }

        // Telemetría y cinemática en tiempo real sincronizada con JettraStore
        if (policeMonitor != null) {
            // A. Personas (Usuarios conectados caminando entre sus edificios de zona y los nodos)
            for (JettraLiveSession session : policeMonitor.getLiveSessions()) {
                session.advance(dt);
                HumanEntity person = findEntityByName(session.getUsername());
                if (person != null) {
                    UserZoneGroup zone = policeMonitor.getZoneById(session.getZoneId());
                    ServerNode3D node = policeMonitor.getNodeById(session.getTargetNodeId());
                    if (zone != null && node != null) {
                        float bx = zone.getBuildingX(); float bz = zone.getBuildingZ();
                        float nx = node.getX(); float nz = node.getZ();
                        float p = session.getProgress();
                        float newX = bx + (nx - bx) * p;
                        float newZ = bz + (nz - bz) * p;

                        float dx = newX - person.x;
                        float dz = newZ - person.z;
                        if (Math.abs(dx) > 0.001f || Math.abs(dz) > 0.001f) {
                            person.rotation = (float) Math.atan2(dx, dz) * (180.0f / (float) Math.PI);
                        }
                        person.x = newX;
                        person.z = newZ;
                        person.action = (session.getPhase() == JettraLiveSession.SessionPhase.EXECUTING_QUERY) ? "QUERYING" : "WALKING";
                        person.currentThought = session.getCurrentOperation();
                        person.thoughtTimer = 4.0f;
                    }
                }
            }

            // B. Perros (Agentes Caninos JettraPolice patrullando en tiempo real sus nodos asignados)
            for (JettraPoliceAgent agent : policeMonitor.getActivePoliceAgents()) {
                agent.advance(dt);
                HumanEntity dog = findEntityByName(agent.getName());
                if (dog != null) {
                    ServerNode3D node = policeMonitor.getNodeById(agent.getTargetNodeId());
                    if (node != null) {
                        float radius = 3.6f;
                        float ang = agent.getPatrolAngle();
                        float targetX = node.getX() + (float) Math.cos(ang) * radius;
                        float targetZ = node.getZ() + (float) Math.sin(ang) * radius;

                        float forwardAngle = ang + (float)(Math.PI / 2.0);
                        dog.rotation = forwardAngle * (180.0f / (float) Math.PI);
                        dog.x = targetX;
                        dog.z = targetZ;
                        dog.action = "PATROLLING";
                        dog.currentThought = agent.getCurrentMission();
                        dog.thoughtTimer = 4.0f;

                        if (agent.isAlertActive()) {
                            dog.r = 255; dog.g = 50; dog.b = 50;
                        } else if (dog.isJettraMascot) {
                            dog.r = 255; dog.g = 215; dog.b = 0;
                        } else {
                            dog.r = 30; dog.g = 144; dog.b = 255;
                        }
                    }
                }
            }

            // C. Camiones (Tráfico de datos real entre nodos del clúster)
            for (ClusterDataTraffic traffic : policeMonitor.getActiveTraffic()) {
                traffic.advance(dt);
                HumanEntity truck = findEntityByName(traffic.getName());
                if (truck != null) {
                    ServerNode3D src = policeMonitor.getNodeById(traffic.getSourceNodeId());
                    ServerNode3D tgt = policeMonitor.getNodeById(traffic.getTargetNodeId());
                    if (src != null && tgt != null) {
                        float sx = src.getX(); float sz = src.getZ();
                        float tx = tgt.getX(); float tz = tgt.getZ();
                        float p = traffic.getProgress();
                        truck.x = sx + (tx - sx) * p;
                        truck.z = sz + (tz - sz) * p;

                        float dirX = traffic.isReversing() ? (sx - tx) : (tx - sx);
                        float dirZ = traffic.isReversing() ? (sz - tz) : (tz - sz);
                        truck.rotation = (float) Math.atan2(dirX, dirZ) * (180.0f / (float) Math.PI);

                        truck.dataPayload = traffic.getPayloadSummary();
                        truck.currentThought = "🚚 " + traffic.getPayloadSummary();
                        truck.thoughtTimer = 4.0f;
                    }
                }
            }
        }

        boolean isNight = (weatherMode == 1);
        int schoolCount = 0; int hospitalCount = 0;
        int aliveCount = 0; float globalHealth = 0;
        for (Artifact a : artifacts) {
            if (a.type == 1) schoolCount++;
            else if (a.type == 4) hospitalCount++;
        }
        for (HumanEntity e : entities) {
            if (!e.isDead && !e.name.contains("Jettra")) { aliveCount++; globalHealth += e.health; }
        }
        if (aliveCount > 0) globalHealth /= aliveCount;
        
        for (HumanEntity e : entities) {
            if (e == policeSentinelEntity) {
                continue; // El policía centinela JettraStorePolice se actualiza en updatePolicePatrol(dt)
            }
            if (e.isDead && (e.name != null && !e.name.contains("Jettra"))) continue;

            // --- PHYSICAL (PECS) ---
            if (!e.isCar) {
                float baseRate = e.metabolismRate * dt;
                
                // Aging: 1 year per ~15 mins real time (approx 1/900 year per second)
                if (worldTime % 1.0 < dt) {
                    e.age = (int)(20 + (worldTime / 600.0f)); // Simple aging for demo
                }

                // Metabolism
                e.hunger = Math.max(0, e.hunger - baseRate * (isNight ? 0.3f : 0.6f));
                e.thirst = Math.max(0, e.thirst - baseRate * (isNight ? 0.4f : 0.8f));
                e.energy = Math.max(0, e.energy - baseRate * ("IDLE".equals(e.action) ? 0.2f : 1.0f));
                
                // Disease Spread
                if (e.infectionLevel > 0) {
                    e.infectionLevel += baseRate * 2.0f;
                    e.health -= baseRate * 0.5f;
                    if (e.health < 20 && e.thoughtTimer <= 0) {
                        e.currentThought = "Me siento muy enfermo...";
                        e.thoughtTimer = 5.0f;
                    }
                }

                // Personality Impact on Emotional State (Neuroticism)
                float moodDecay = baseRate * (0.1f + e.neuroticism * 0.5f);
                e.mood -= moodDecay;

                // Recovery
                if ("SHELTERED".equals(e.action) || "RESTING".equals(e.action)) {
                    e.energy = Math.min(100, e.energy + baseRate * 5.0f);
                    e.stamina = Math.min(100, e.stamina + baseRate * 3.0f);
                    if (isNight) e.mood = Math.min(100, e.mood + baseRate);
                }
                // Hospital Aura
                for (Artifact a : artifacts) {
                    if (a.type == 4) {
                        float dSq = (a.x - e.x)*(a.x - e.x) + (a.z - e.z)*(a.z - e.z);
                        if (dSq < 100.0f) {
                            e.health = Math.min(100, e.health + baseRate * 10.0f);
                            e.infectionLevel = Math.max(0, e.infectionLevel - baseRate * 10.0f);
                        }
                    }
                }

                // Health checks
                if (e.hunger < 10 || e.thirst < 10 || e.energy < 5) {
                    e.health -= baseRate * 1.5f;
                }
                e.health = Math.max(0, Math.min(100, e.health));
                
                if (e.health <= 0 && !e.name.contains("Jettra")) {
                    e.isDead = true;
                    e.action = "DEAD";
                    worldEvents.add(new WorldEvent(e.name + " ha fallecido por causas naturales", worldTime, 200, 0, 0));
                    continue;
                }
            }

            // --- COGNITIVE (PECS) - Goal Selection ---
            if (!e.isCar && !e.isDead) {
                if (e.hunger < 30) e.currentGoal = "BUSCAR_COMIDA";
                else if (e.thirst < 30) e.currentGoal = "BUSCAR_AGUA";
                else if (e.energy < 30 || isNight) e.currentGoal = "BUSCAR_REFUGIO";
                else if (e.mood < 40) e.currentGoal = "SOCIALIZAR";
                else if (e.age >= 18 && e.spouse.isEmpty() && Math.random() < 0.05) e.currentGoal = "BUSCAR_PAREJA";
                else if (!e.spouse.isEmpty() && e.children.size() < 2 && Math.random() < 0.02) e.currentGoal = "FORMAR_FAMILIA";
                else if (!e.hasHome && e.energy > 50) e.currentGoal = "BUILDING_HOME";
                else if (aliveCount > 10 && schoolCount == 0 && e.energy > 60 && e.intelligence > 0.5f) e.currentGoal = "BUILDING_SCHOOL";
                else if (globalHealth < 60 && hospitalCount == 0 && e.energy > 60) e.currentGoal = "BUILDING_HOSPITAL";
                else if (e.energy > 60 && Math.random() < 0.15) e.currentGoal = "TRABAJAR";
                else if (e.energy > 70 && Math.random() < 0.15) e.currentGoal = "PRACTICAR_DEPORTE";
                else e.currentGoal = "EXPLORAR";
                
                // --- CONVERSATIONAL FLUIDITY (BABBLE) ---
                if (e.thoughtTimer <= 0 && !e.name.contains("Jettra") && Math.random() < 0.01) {
                    if ("TRABAJAR".equals(e.currentGoal)) {
                        String[] work = {"Procesando datos del sistema.", "Construyendo el futuro de Jettra.", "Trabajar dignifica al agente.", "Produciendo recursos numéricos."};
                        e.currentThought = work[(int)(Math.random() * work.length)];
                        e.thoughtTimer = 5.0f;
                    } else if ("PRACTICAR_DEPORTE".equals(e.currentGoal)) {
                        String[] sport = {"¡Un, dos, un, dos!", "Aumentando mi stamina basal.", "Correr despeja la red neuronal.", "Mejorando mi estatus físico."};
                        e.currentThought = sport[(int)(Math.random() * sport.length)];
                        e.thoughtTimer = 5.0f;
                    } else if ("BUSCAR_PAREJA".equals(e.currentGoal)) {
                        e.currentThought = "Espero encontrar a un igual compatible...";
                        e.thoughtTimer = 5.0f;
                    } else if ("FORMAR_FAMILIA".equals(e.currentGoal)) {
                        e.currentThought = "Pensando en nuestra descendencia con " + e.spouse;
                        e.thoughtTimer = 5.0f;
                    } else if (e.health > 80 && e.mood > 70) {
                        String[] happy = {"Me siento genial hoy.", "¡Qué buen día para prosperar!", "Nuestra red es fuerte.", "La energía fluye."};
                        e.currentThought = happy[(int)(Math.random() * happy.length)];
                        e.thoughtTimer = 5.0f;
                    } else if (e.health < 40) {
                        String[] sick = {"El dolor es insoportable...", "Necesito curarme o reseteo.", "Mi energía vital se desvanece.", "Ayuda sistémica..."};
                        e.currentThought = sick[(int)(Math.random() * sick.length)];
                        e.thoughtTimer = 5.0f;
                    } else if (e.mood < 40) {
                        String[] sad = {"Todo parece tan sombrío...", "Extraño a los míos.", "La soledad abruma mis rutinas.", "¿Cuál es el propósito del loop?"};
                        e.currentThought = sad[(int)(Math.random() * sad.length)];
                        e.thoughtTimer = 5.0f;
                    } else if ("EXPLORAR".equals(e.currentGoal)) {
                        String[] explore = {"Hay tanto plano por calcular...", "Buscando nuevos vectores.", "Investigando el horizonte 3D.", "Bip. Bip. Mapeando entorno."};
                        e.currentThought = explore[(int)(Math.random() * explore.length)];
                        e.thoughtTimer = 5.0f;
                    }
                }
            }

            // --- MOVEMENT & ENVIRONMENTAL REACTION ---
            float envSpeed = isNight ? 0.5f : 1.0f;
            if (weatherMode == 2) envSpeed *= 0.7f;

            float dx = e.targetX - e.x;
            float dz = e.targetZ - e.z;
            float dist = (float)Math.sqrt(dx*dx + dz*dz);

            if (dist > 0.1f && !Float.isNaN(dist)) {
                float speed = (e.isCar ? 8.0f : 2.0f) * envSpeed;
                e.x += (dx / dist) * speed * dt;
                e.z += (dz / dist) * speed * dt;
                e.rotation = (float)Math.atan2(dx, dz) * (180.0f / (float)Math.PI);
                
                // Autonomous Driving Activation
                if (dist > 80.0f && !e.isCar && !e.name.contains("Jettra") && e.energy > 20 && Math.random() < 0.05) {
                    e.isCar = true; e.action = "DRIVING";
                    if (Math.random() < 0.5) { e.r=255; e.g=50; e.b=50; } else { e.r=50; e.g=50; e.b=255; }
                } else if (!e.isCar && !e.isMachine && !e.name.contains("Jettra")) {
                    e.action = "WALKING";
                }
                // Road Laying
                if (e.isCar && Math.random() < 0.05) {
                    Artifact road = new Artifact();
                    road.x = e.x; road.y = -0.04f; road.z = e.z; road.type = 5;
                    road.r = 60; road.g = 60; road.b = 60; road.a = 255;
                    artifacts.add(road);
                }
            } else if (!Float.isNaN(dist)) {
                if (e.isCar) { e.isCar = false; e.r = 200; e.g = 200; e.b = 200; }
                if (!e.currentGoal.startsWith("BUILDING_")) {
                    e.isMachine = false;
                    e.action = "IDLE";
                }
                
                // Goal-specific target selection
                if (e.currentGoal.startsWith("BUILDING_")) {
                    e.action = "BUILDING";
                    e.isMachine = true;
                    e.buildTimer += dt;
                    if (e.buildTimer > 5.0f) {
                        Artifact art = new Artifact();
                        art.x = e.x; art.y = 0; art.z = e.z; art.a = 255;
                        if ("BUILDING_HOME".equals(e.currentGoal)) {
                            art.type = 0; art.r = 200; art.g = 200; art.b = 200;
                            e.hasHome = true; e.homeX = e.x; e.homeY = 0; e.homeZ = e.z;
                            worldEvents.add(new WorldEvent(e.name + " construyó un hogar", worldTime, 0, 255, 100));
                        } else if ("BUILDING_SCHOOL".equals(e.currentGoal)) {
                            art.type = 1; art.r = 100; art.g = 100; art.b = 255;
                            worldEvents.add(new WorldEvent(e.name + " inauguró una Escuela", worldTime, 0, 100, 255));
                        } else if ("BUILDING_HOSPITAL".equals(e.currentGoal)) {
                            art.type = 4; art.r = 255; art.g = 255; art.b = 255;
                            worldEvents.add(new WorldEvent(e.name + " construyó un Hospital", worldTime, 0, 255, 100));
                        }
                        artifacts.add(art);
                        e.isMachine = false;
                        e.buildTimer = 0; e.energy -= 20; e.currentGoal = "EXPLORAR";
                    }
                } else if (Math.random() < 0.02) {
                    if ("BUSCAR_REFUGIO".equals(e.currentGoal) && e.hasHome) {
                        e.targetX = e.homeX; e.targetZ = e.homeZ;
                    } else if ("BUSCAR_REFUGIO".equals(e.currentGoal) || "BUSCAR_COMIDA".equals(e.currentGoal) || "SOCIALIZAR".equals(e.currentGoal)) {
                        // Find nearest house/school/hospital
                        Artifact best = null; float mDS = Float.MAX_VALUE;
                        for(Artifact a : artifacts) {
                            if (a.type == 5) continue;
                            float d = (a.x-e.x)*(a.x-e.x)+(a.z-e.z)*(a.z-e.z);
                            if (d < mDS) { mDS = d; best = a; }
                        }
                        if (best != null) { e.targetX = best.x; e.targetZ = best.z; }
                    } else {
                        e.targetX = (float)(Math.random()*160-80);
                        e.targetZ = (float)(Math.random()*160-80);
                    }
                }
                if (dist < 1.0f && "BUSCAR_REFUGIO".equals(e.currentGoal)) {
                    e.action = "SHELTERED";
                }
            }

            // --- SOCIAL (PECS) & LEADERSHIP ---
            if (!e.isCar && !e.isDead && worldTime % 4.0 < dt) {
                for (HumanEntity other : entities) {
                    if (other == e || other.isDead) continue;
                    float d2 = (other.x-e.x)*(other.x-e.x) + (other.z-e.z)*(other.z-e.z);
                    if (d2 < 25.0f) { // Interaction range
                        // Disease spread
                        if (e.infectionLevel > 30 && Math.random() < 0.1) other.infectionLevel = 1;

                        // Social interaction
                        if ("SOCIALIZAR".equals(e.currentGoal)) {
                            e.mood = Math.min(100, e.mood + 5.0f * (1.0f + e.extraversion));
                            if (e.thoughtTimer <= 0 && Math.random() < 0.2) {
                                e.currentThought = "Hablando con " + other.name;
                                e.thoughtTimer = 3.0f;
                            }
                        }

                        // Romance & Family
                        if ("BUSCAR_PAREJA".equals(e.currentGoal) && "BUSCAR_PAREJA".equals(other.currentGoal) && (e.spouse == null || e.spouse.isEmpty()) && (other.spouse == null || other.spouse.isEmpty())) {
                            if (e.age >= 18 && other.age >= 18 && Math.random() < 0.2) {
                                e.spouse = other.name;
                                other.spouse = e.name;
                                e.currentGoal = "SOCIALIZAR"; other.currentGoal = "SOCIALIZAR";
                                worldEvents.add(new WorldEvent(e.name + " y " + other.name + " son ahora pareja", worldTime, 255, 105, 180));
                            }
                        }
                        if ("FORMAR_FAMILIA".equals(e.currentGoal) && e.spouse != null && !e.spouse.isEmpty() && e.spouse.equals(other.name)) {
                            if (Math.random() < 0.05) {
                                String childName = "Hijo-" + (int)(Math.random()*1000);
                                e.children.add(childName); other.children.add(childName);
                                generateEntity(childName, e.x, e.y, e.z, false, false, false);
                                worldEvents.add(new WorldEvent("Nueva vida: " + childName + " nació de " + e.name, worldTime, 100, 255, 100));
                                e.currentGoal = "SOCIALIZAR";
                            }
                        }

                        // Leadership influence (Jettra Wolf)
                        if (e.name.contains("Jettra") && Math.random() < 0.1) {
                            other.intelligence = Math.min(1.0f, other.intelligence + 0.01f);
                            other.mood = Math.min(100, other.mood + 10.0f);
                        }
                    }
                }
            }

            // --- PERSISTENCE & LEARNING ---
            if (!e.isCar && !e.isDead && Math.random() < 0.0005) {
                saveKnowledge(e, "Observación Directa", "Evolución de la Especie");
            }
        }
    }

    private void updateThoughts(float dt) {
        entities.removeIf(e -> {
            if (e.thoughtTimer > 0) {
                e.thoughtTimer -= dt;
            }
            return false;
        });

        thoughts.removeIf(t -> {
            t.timer -= dt;
            return t.timer <= 0;
        });
    }

    private void updateDirectorMode(float dt) {
        directorTimer += dt;
        if (directorTimer > 8.0f || selectedAgentIndex == -1) {
            directorTimer = 0;
            for (int i = 0; i < entities.size(); i++) {
                HumanEntity e = entities.get(i);
                if ("Building".equals(e.action) || "Socializing".equals(e.action)) {
                    selectedAgentIndex = i;
                    followMode = true;
                    break;
                }
            }
        }
    }

    private void draw() {
        beginDrawing();
        
        Color skyColor = switch(weatherMode) {
            case 1 -> new Color().r((byte)2).g((byte)2).b((byte)5).a((byte)255); // Night
            case 2 -> new Color().r((byte)30).g((byte)35).b((byte)45).a((byte)255); // Storm
            default -> new Color().r((byte)5).g((byte)5).b((byte)12).a((byte)255); // Default
        };
        clearBackground(skyColor);

        if (worldMode == WorldMode.MAIN_WORLD || worldMode == WorldMode.EXPANDING_TRANSITION) {
            beginMode3D(camera);
            drawCartesianPlane();

            drawArtifacts();
            drawEntities();
            drawThoughts();
            drawKnowledgeBase();
            drawServerClusterNodes();

            if (weatherMode == 2) { // Draw Rain
                for(int j=0; j<100; j++) {
                    float rx = (float)(Math.random()*80-40);
                    float rz = (float)(Math.random()*80-40);
                    float ry = (float)(Math.random()*20);
                    drawLine3D(new Vector3().x(rx).y(ry).z(rz), new Vector3().x(rx).y(ry-0.5f).z(rz), SKYBLUE);
                }
            }

            if (worldMode == WorldMode.EXPANDING_TRANSITION) {
                drawNodeExpansionEffect3D();
            }

            endMode3D();

            drawUI();
            drawServerLabelsAndHUD();

            if (showHelp) drawHelpOverlay();
            if (showConfigModal) drawConfigModal();
            if (showConnectionModal) drawConnectionManagerModal();
            if (showUserManagerModal) drawUserManagerModal();
            if (showEngineExplorerModal) drawEngineExplorerModal();
            if (showChat) drawChatWindow();
            if (showNodeInspectorModal) drawNodeInspectorModal();
            
            drawTopKnowledgePanel();
            drawJettraStatusTooltip();

            if (worldMode == WorldMode.EXPANDING_TRANSITION) {
                drawExpandingTransitionOverlay();
            }
        } else {
            // MUNDO INTERIOR DEL NODO (Y TRANSICIÓN DE RETORNO)
            clearBackground(new Color().r((byte)4).g((byte)6).b((byte)16).a((byte)255));

            beginMode3D(camera);
            drawInnerNodeWorld3D();

            if (worldMode == WorldMode.EXITING_TRANSITION) {
                drawExitingPortalEffect3D();
            }

            endMode3D();

            drawInnerNodeWorldHUD();

            if (selectedDatabase != null) {
                drawDatabaseDetailModal();
            }
            if (showUserManagerModal) drawUserManagerModal();
            if (showEngineExplorerModal) drawEngineExplorerModal();

            if (worldMode == WorldMode.EXITING_TRANSITION) {
                drawExitingTransitionOverlay();
            }
        }

        endDrawing();
    }

    private void drawArtifacts() {
        for (Artifact a : artifacts) {
            Vector3 pos = new Vector3().x(a.x).y(a.y).z(a.z);
            Color color = new Color().r((byte)a.r).g((byte)a.g).b((byte)a.b).a((byte)a.a);
            
            switch (a.type) {
                case 0 -> { // House
                    drawCube(pos, 2, 2, 2, color);
                    drawCubeWires(pos, 2, 2, 2, BLACK);
                    drawCylinder(new Vector3().x(pos.x()).y(pos.y() + 1).z(pos.z()), 0, 1.5f, 2, 4, MAROON);
                }
                case 1 -> { // School
                    drawCube(pos, 4, 3, 4, color);
                    drawCubeWires(pos, 4, 3, 4, BLACK);
                    drawCube(new Vector3().x(pos.x()).y(pos.y() + 2).z(pos.z()), 1, 2, 1, DARKGRAY);
                }
                case 2 -> { // City Center
                    drawCube(pos, 5, 2, 5, color);
                    drawSphere(new Vector3().x(pos.x()).y(pos.y() + 2).z(pos.z()), 1.5f, GOLD);
                }
                case 3 -> { // Radio
                    drawCylinder(pos, 0.5f, 0.5f, 5, 8, DARKGRAY);
                    if (( (int)(worldTime * 2) % 2) == 0) {
                        drawSphere(new Vector3().x(pos.x()).y(pos.y() + 5).z(pos.z()), 0.3f, RED);
                    }
                }
                case 4 -> { // Hospital
                    drawCube(pos, 4, 3, 4, WHITE);
                    drawCubeWires(pos, 4, 3, 4, BLACK);
                    drawCube(new Vector3().x(pos.x()).y(pos.y() + 1.6f).z(pos.z()), 1f, 2f, 0.1f, RED);
                    drawCube(new Vector3().x(pos.x()).y(pos.y() + 1.6f).z(pos.z()), 2f, 1f, 0.1f, RED);
                }
                case 5 -> { // Road Plate
                    drawCube(new Vector3().x(pos.x()).y(-0.04f).z(pos.z()), 2f, 0.02f, 2f, color);
                }
            }
        }
        
        for (MegaProject p : megaProjects) {
            if (p.finished) continue;
            Vector3 pos = new Vector3().x(p.x).y(p.y).z(p.z);
            drawCubeWires(pos, 2.1f, 2.1f, 2.1f, YELLOW);
            drawCube(pos, 2.0f, 2.0f * p.progress, 2.0f, fade(SKYBLUE, 0.5f));
        }
    }

    private void drawEntities() {
        int i = 0;
        for (HumanEntity e : entities) {
            if (e.isDead && !e.name.contains("Jettra")) {
                i++;
                continue;
            }
            if (Float.isNaN(e.x) || Float.isNaN(e.y) || Float.isNaN(e.z)) {
                e.x = 0; e.y = 0; e.z = 0;
            }
            Vector3 pos = new Vector3().x(e.x).y(e.y).z(e.z);
            Color color = new Color().r((byte)e.r).g((byte)e.g).b((byte)e.b).a((byte)255);

            if (e.isWolf) {
                drawWolf(pos, e.rotation, color);
            } else if (e.isMachine) {
                // Tractor body
                drawCube(new Vector3().x(pos.x()).y(pos.y()+0.5f).z(pos.z()), 2.0f, 1.0f, 1.5f, YELLOW);
                drawCubeWires(new Vector3().x(pos.x()).y(pos.y()+0.5f).z(pos.z()), 2.0f, 1.0f, 1.5f, BLACK);
                // Tractor cabin
                drawCube(new Vector3().x(pos.x()-0.5f).y(pos.y()+1.5f).z(pos.z()), 1.0f, 1.0f, 1.0f, fade(BLACK, 0.8f));
                // Wheels
                drawCylinderEx(new Vector3().x(pos.x()-1f).y(pos.y()+0.5f).z(pos.z()-1.0f), new Vector3().x(pos.x()-1f).y(pos.y()+0.5f).z(pos.z()+1.0f), 0.6f, 0.6f, 12, DARKGRAY);
                drawCylinderEx(new Vector3().x(pos.x()+0.8f).y(pos.y()+0.3f).z(pos.z()-0.9f), new Vector3().x(pos.x()+0.8f).y(pos.y()+0.3f).z(pos.z()+0.9f), 0.4f, 0.4f, 12, DARKGRAY);
                // Crane Arm
                drawCylinderEx(new Vector3().x(pos.x()+1f).y(pos.y()+0.5f).z(pos.z()), new Vector3().x(pos.x()+3.0f).y(pos.y()+2.0f).z(pos.z()), 0.15f, 0.15f, 8, BLACK);
            } else if (e.isCar) {
                // Car Chassis
                drawCube(new Vector3().x(pos.x()).y(pos.y()+0.4f).z(pos.z()), 2.5f, 0.6f, 1.2f, color);
                drawCubeWires(new Vector3().x(pos.x()).y(pos.y()+0.4f).z(pos.z()), 2.5f, 0.6f, 1.2f, BLACK);
                // Car Cabin
                drawCube(new Vector3().x(pos.x()-0.2f).y(pos.y()+1.0f).z(pos.z()), 1.2f, 0.6f, 1.1f, fade(RAYWHITE, 0.9f));
                // Agent Driver Head
                drawSphere(new Vector3().x(pos.x()-0.2f).y(pos.y()+1.0f).z(pos.z()), 0.35f, color);
                // Wheels
                drawCylinderEx(new Vector3().x(pos.x()-0.8f).y(pos.y()+0.2f).z(pos.z()-0.7f), new Vector3().x(pos.x()-0.8f).y(pos.y()+0.2f).z(pos.z()+0.7f), 0.3f, 0.3f, 12, BLACK);
                drawCylinderEx(new Vector3().x(pos.x()+0.8f).y(pos.y()+0.2f).z(pos.z()-0.7f), new Vector3().x(pos.x()+0.8f).y(pos.y()+0.2f).z(pos.z()+0.7f), 0.3f, 0.3f, 12, BLACK);
            } else {
                // Human Shape
                float walkBounce = ("WALKING".equals(e.action)) ? (float)Math.sin(worldTime * 20.0f) * 0.1f : 0.0f;
                float legSwing = ("WALKING".equals(e.action)) ? (float)Math.sin(worldTime * 15.0f) * 0.3f : 0.0f;
                // Torso
                drawCube(new Vector3().x(pos.x()).y(pos.y() + 1.2f + walkBounce).z(pos.z()), 0.6f, 0.8f, 0.4f, color);
                drawCubeWires(new Vector3().x(pos.x()).y(pos.y() + 1.2f + walkBounce).z(pos.z()), 0.6f, 0.8f, 0.4f, BLACK);
                // Head
                drawSphere(new Vector3().x(pos.x()).y(pos.y() + 1.8f + walkBounce).z(pos.z()), 0.3f, color);
                // Legs
                drawCube(new Vector3().x(pos.x()-0.15f).y(pos.y() + 0.4f).z(pos.z() + legSwing), 0.2f, 0.8f, 0.2f, DARKGRAY);
                drawCube(new Vector3().x(pos.x()+0.15f).y(pos.y() + 0.4f).z(pos.z() - legSwing), 0.2f, 0.8f, 0.2f, DARKGRAY);
                // Arms
                drawCube(new Vector3().x(pos.x()-0.4f).y(pos.y() + 1.2f + walkBounce).z(pos.z() - legSwing), 0.15f, 0.7f, 0.15f, color);
                drawCube(new Vector3().x(pos.x()+0.4f).y(pos.y() + 1.2f + walkBounce).z(pos.z() + legSwing), 0.15f, 0.7f, 0.15f, color);
            }

            if (selectedAgentIndex == i) {
                drawCircle3D(new Vector3().x(pos.x()).y(pos.y() + 0.01f).z(pos.z()), 1.5f, new Vector3().x(1).y(0).z(0), 90, LIME);
            }

            // Name Tag
            Vector2 screenPos = getWorldToScreen(new Vector3().x(pos.x()).y(pos.y() + 2.5f).z(pos.z()), camera);
            drawLegibleText(e.name, (int)screenPos.x() - measureLegibleText(e.name, 12)/2, (int)screenPos.y(), 12, RAYWHITE);
            i++;
        }
    }

    private void drawThoughts() {
        // Render thoughts from the global list
        for (Thought t : thoughts) {
            drawThoughtBubble(t.content, t.owner, t.x, t.y, t.z, t.timer, t.isThought);
        }
        
        // Render current thoughts from entities if not already in list
        for (HumanEntity e : entities) {
            if (e.thoughtTimer > 0 && e.currentThought != null && !e.currentThought.isEmpty()) {
                // Check if this entity already has a thought in the global list to avoid overlaps
                boolean alreadyShown = false;
                for (Thought t : thoughts) {
                    if (t.owner == e) { alreadyShown = true; break; }
                }
                if (!alreadyShown) {
                    drawThoughtBubble(e.currentThought, e, e.x, e.y + (e.isWolf ? 1.5f : 3.0f), e.z, e.thoughtTimer, true);
                }
            }
        }
    }

    private void drawThoughtBubble(String content, HumanEntity owner, float x, float y, float z, float timer, boolean isThought) {
        Vector3 worldPos = (owner != null) ? new Vector3().x(owner.x).y(owner.y + (owner.isWolf ? 1.5f : 3.0f)).z(owner.z) 
                                           : new Vector3().x(x).y(y).z(z);
        Vector2 screenPos = getWorldToScreen(worldPos, camera);
        
        if (screenPos.x() > 0 && screenPos.x() < getScreenWidth() && screenPos.y() > 0 && screenPos.y() < getScreenHeight()) {
            boolean isJettra = owner != null && owner.name.contains("Jettra");
            String label = isThought ? "[Pensando...]" : "[Hablando]";
            int fontSize = isJettra ? 18 : 16;
            int textW = measureText(content, fontSize);
            
            float alpha = Math.min(1.0f, timer); // Fade out
            Color bubbleColor = isJettra ? fade(GOLD, 0.8f * alpha) : (isThought ? fade(PURPLE, 0.7f * alpha) : fade(DARKBLUE, 0.8f * alpha));
            Color textColor = isJettra ? fade(BLACK, alpha) : fade(RAYWHITE, alpha);
            Color labelColor = isJettra ? fade(MAROON, alpha) : fade(GOLD, alpha);
            Color borderColor = isJettra ? fade(WHITE, 0.9f * alpha) : fade(RAYWHITE, 0.3f * alpha);

            // Bubble popup background
            drawRectangleRounded(new Rectangle().x(screenPos.x() - textW/2.0f - 10).y(screenPos.y() - 60).width(textW + 20).height(45), 0.4f, 8, bubbleColor);
            drawRectangleRoundedLines(new Rectangle().x(screenPos.x() - textW/2.0f - 10).y(screenPos.y() - 60).width(textW + 20).height(45), 0.4f, 8, borderColor);
            
            // Triangle pointer
            drawTriangle(new Vector2().x(screenPos.x()).y(screenPos.y() - 15),
                         new Vector2().x(screenPos.x() - 10).y(screenPos.y() - 25),
                         new Vector2().x(screenPos.x() + 10).y(screenPos.y() - 25), bubbleColor);

            drawLegibleText(label, (int)(screenPos.x() - textW/2.0f), (int)screenPos.y() - 55, 12, labelColor);
            drawLegibleText(content, (int)(screenPos.x() - textW/2.0f), (int)screenPos.y() - 40, fontSize, textColor);
            if (owner != null && owner.currentGoal != null) {
                drawLegibleText("Meta: " + owner.currentGoal, (int)(screenPos.x() - textW/2.0f), (int)screenPos.y() - 28, 10, GOLD);
            }
        }
    }

    private void drawCartesianPlane() {
        // Ground Plane
        Color floorColor = (weatherMode == 2) ? DARKGRAY : new Color().r((byte)25).g((byte)25).b((byte)45).a((byte)255);
        drawPlane(new Vector3().x(0).y(-0.05f).z(0), new Vector2().x(200).y(200), floorColor);
        
        // Extend Grid
        drawGrid(100, 1.0f);
        
        // Standard Cartesian Axes (Red X, Green Y, Blue Z)
        drawLine3D(new Vector3().x(-100).y(0.01f).z(0), new Vector3().x(100).y(0.01f).z(0), RED);   // X Axis
        drawLine3D(new Vector3().x(0).y(-100).z(0), new Vector3().x(0).y(100).z(0), GREEN);    // Y Axis
        drawLine3D(new Vector3().x(0).y(0.01f).z(-100), new Vector3().x(0).y(0.01f).z(100), BLUE); // Z Axis
        
        // Origin Marker
        drawSphere(new Vector3().x(0).y(0).z(0), 0.25f, GOLD);
    }

    private String cleanTextForDisplay(String text) {
        if (text == null || text.isEmpty()) return "";
        return text
            .replace("🌳", "[ENGINES] ")
            .replace("🗄️", "[DB] ")
            .replace("🗄", "[DB] ")
            .replace("📦", "[OBJ] ")
            .replace("⚙️", "[IDX] ")
            .replace("⚙", "[IDX] ")
            .replace("🔍", "[BUSCAR] ")
            .replace("➕", "[+] ")
            .replace("✏️", "[EDIT] ")
            .replace("✏", "[EDIT] ")
            .replace("🗑️", "[ELIM] ")
            .replace("🗑", "[ELIM] ")
            .replace("⏪", "[VERS] ")
            .replace("💾", "[GUARDAR] ")
            .replace("📍", ">> ")
            .replace("🔑", "[ID] ")
            .replace("🏢", "[SEDE] ")
            .replace("🏥", "[HOSP] ")
            .replace("🌿", "[IOT] ")
            .replace("🏛️", "[DATA] ")
            .replace("🏛", "[DATA] ")
            .replace("🚚", "[TRAFICO] ")
            .replace("🐾", "[K9] ")
            .replace("🚨", "[ALERTA] ")
            .replace("🛡️", "[POLICE] ")
            .replace("🛡", "[POLICE] ")
            .replace("🔊", "[VOZ] ")
            .replace("🔇", "[MUTE] ")
            .replace("🔌", "[CONN] ")
            .replace("👥", "[USERS] ")
            .replace("🔄", "[RESET] ")
            .replace("🎯", "[MAP] ")
            .replace("💬", "[CHAT] ")
            .replace("❓", "(?) ")
            .replace("ℹ️", "(i) ")
            .replace("ℹ", "(i) ")
            .replace("🗖", "[MAX]")
            .replace("🗗", "[MIN]")
            .replace("⟲", "<==");
    }

    private void drawLegibleText(String text, int x, int y, int fontSize, Color color) {
        if (text == null || text.isEmpty()) return;
        String clean = cleanTextForDisplay(text);
        if (mainFont != null) {
            drawTextEx(mainFont, clean, new Vector2().x(x).y(y), (float)fontSize, 1.0f, color);
        } else {
            drawText(clean, x, y, fontSize, color);
        }
    }

    private int measureLegibleText(String text, int fontSize) {
        if (text == null || text.isEmpty()) return 0;
        String clean = cleanTextForDisplay(text);
        if (mainFont != null) {
            Vector2 size = measureTextEx(mainFont, clean, (float)fontSize, 1.0f);
            return (int)size.x();
        } else {
            return measureText(clean, fontSize);
        }
    }

    private void drawUI() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();

        // Right Sidebar
        drawRectangle(sw - 200, 0, 200, sh, fade(DARKGRAY, 0.9f));
        drawLine(sw - 200, 0, sw - 200, sh, RAYWHITE);

        drawText("JETTRA CORE", sw - 190, 20, 20, GOLD);
        drawText("JAVA 25 EDITION", sw - 190, 45, 10, SKYBLUE);

        // Buttons organizados y alineados sin solapamientos
        int by = 65;
        int bh = 24;
        int bGap = 4;

        if (guiButton(sw - 190, by, 180, bh, "🔌 CONEXIONES", GOLD)) {
            showConnectionModal = !showConnectionModal;
            if (showConnectionModal) {
                loadSelectedProfileIntoForm();
            }
        }
        by += bh + bGap; // 93

        if (guiButton(sw - 190, by, 180, bh, "👥 USUARIOS", SKYBLUE)) {
            showUserManagerModal = !showUserManagerModal;
            if (showUserManagerModal) {
                initUserFormWithSelection();
            }
        }
        by += bh + bGap; // 121

        if (guiButton(sw - 190, by, 180, bh, "🌳 EXPLORADOR ENGINES", LIME)) {
            showEngineExplorerModal = !showEngineExplorerModal;
            if (showEngineExplorerModal) {
                openEngineExplorerModal(selectedDatabase != null ? selectedDatabase.getId() : "example_factura_db");
            }
        }
        by += bh + bGap; // 149

        if (guiButton(sw - 190, by, 180, bh, "🖥️ CLÚSTER JETTRA", SKYBLUE)) {
            if (selectedServerNode == null && policeMonitor != null && !policeMonitor.getServerNodes().isEmpty()) {
                selectedServerNode = policeMonitor.getServerNodes().get(0);
            }
            showNodeInspectorModal = !showNodeInspectorModal;
        }
        by += bh + bGap; // 177

        if (guiButton(sw - 190, by, 180, bh, "🔄 RESET JETTRASTORE", RED)) {
            resetWorldWithJettraStore();
        }
        by += bh + bGap; // 205

        if (guiButton(sw - 190, by, 180, bh, "💾 SAVE STATE", LIME)) {
            saveWorldState();
        }
        by += bh + bGap; // 233

        if (guiButton(sw - 190, by, 180, bh, "⚙️ CONFIGURACIÓN", BLUE)) {
            showConfigModal = !showConfigModal;
            if (showConfigModal) {
                initCamera();
                followMode = false;
                cameraLocked = true;
            } else {
                cameraLocked = false;
            }
        }
        by += bh + bGap; // 261

        if (guiButton(sw - 190, by, 180, bh, directorMode ? "DIRECTOR: ON" : "DIRECTOR: OFF", directorMode ? ORANGE : GRAY)) {
            directorMode = !directorMode;
            if (directorMode) followMode = true;
        }
        by += bh + bGap; // 289

        if (guiButton(sw - 190, by, 88, bh, isAnchored ? "PLN: LOCK" : "PLN: FREE", isAnchored ? RED : GRAY)) {
            isAnchored = !isAnchored;
        }
        if (guiButton(sw - 98, by, 88, bh, "CENTER MAP", DARKGRAY)) {
            initCamera();
            followMode = false;
            cameraLocked = false;
        }
        by += bh + bGap; // 317

        if (guiButton(sw - 190, by, 180, bh, showChat ? "CERRAR CHAT" : "ABRIR CHAT", PURPLE)) {
            showChat = !showChat;
        }
        by += bh + bGap; // 345

        if (guiButton(sw - 190, by, 88, bh, "ZOOM +", GRAY)) {
            camera.fovy(Math.max(5, camera.fovy() - 5));
        }
        if (guiButton(sw - 98, by, 88, bh, "ZOOM -", GRAY)) {
            camera.fovy(Math.min(120, camera.fovy() + 5));
        }
        by += bh + bGap; // 373

        if (guiButton(sw - 190, by, 180, bh, voiceEnabled ? "🔊 VOZ: ACTIVA" : "🔇 VOZ: MUTE", voiceEnabled ? LIME : RED)) {
            voiceEnabled = !voiceEnabled;
            JettraVoiceNarrator.getInstance().setEnabled(voiceEnabled);
            triggerWorldEvent("Voz " + (voiceEnabled ? "activada" : "desactivada"), 200, 200, 0);
        }
        by += bh + bGap; // 401

        if (guiButton(sw - 190, by, 88, bh, sfxEnabled ? "SFX: ON" : "SFX: OFF", sfxEnabled ? LIME : RED)) {
            sfxEnabled = !sfxEnabled;
            worldEvents.add(new WorldEvent("Efectos " + (sfxEnabled ? "activados" : "desactivados"), worldTime, 100, 255, 100));
        }
        if (guiButton(sw - 98, by, 88, bh, "SALIR", DARKGRAY)) {
            closeWindow();
            System.exit(0);
        }
        by += bh + bGap; // 429

        // Selected Info
        int infoY = 445;
        if (selectedAgentIndex != -1 && selectedAgentIndex < entities.size()) {
            HumanEntity e = entities.get(selectedAgentIndex);
            drawLegibleText("AGENT: " + e.name, sw - 190, infoY, 15, RAYWHITE);
            drawLegibleText("GOAL: " + e.currentGoal, sw - 190, infoY + 20, 11, GOLD);
            drawLegibleText("MOOD: " + (int)e.mood + "% (" + (e.mood > 50 ? "Feliz" : "Estresado") + ")", sw - 190, infoY + 35, 11, fade(PURPLE, 0.8f));
            drawLegibleText("HEALTH: " + (int)e.health + "%", sw - 190, infoY + 50, 11, fade(RED, 0.8f));
            drawLegibleText("HUNGER: " + (int)e.hunger + "%", sw - 190, infoY + 65, 11, fade(ORANGE, 0.8f));
            drawLegibleText("THIRST: " + (int)e.thirst + "%", sw - 190, infoY + 80, 11, fade(SKYBLUE, 0.8f));
            
            // Personality (Big Five)
            int py = infoY + 105;
            drawLegibleText("PERSONALIDAD:", sw - 190, py, 12, SKYBLUE);
            drawLegibleText("- Openness: " + String.format("%.2f", e.openness), sw - 180, py + 15, 10, RAYWHITE);
            drawLegibleText("- Conscien: " + String.format("%.2f", e.conscientiousness), sw - 180, py + 27, 10, RAYWHITE);
            drawLegibleText("- Extraver: " + String.format("%.2f", e.extraversion), sw - 180, py + 39, 10, RAYWHITE);
            drawLegibleText("- Agreeabl: " + String.format("%.2f", e.agreeableness), sw - 180, py + 51, 10, RAYWHITE);
            drawLegibleText("- Neurotic: " + String.format("%.2f", e.neuroticism), sw - 180, py + 63, 10, RAYWHITE);

            if (!e.spouse.isEmpty()) {
                drawLegibleText("SPOUSE: " + e.spouse, sw - 190, py + 80, 11, PINK);
            }
            if (e.infectionLevel > 0) {
                drawLegibleText("INFECTION: " + (int)e.infectionLevel + "%", sw - 190, py + 95, 11, LIME);
            }
        }

        // Live Feed
        drawLiveFeed(sw, sh);
        // History Log
        drawEventLog();
    }

    private boolean guiButton(int x, int y, int w, int h, String text, Color baseColor) {
        Vector2 mouse = getMousePosition();
        Rectangle rec = new Rectangle().x(x).y(y).width(w).height(h);
        boolean hovered = checkCollisionPointRec(mouse, rec);
        boolean clicked = hovered && isMouseButtonPressed(MOUSE_BUTTON_LEFT);

        drawRectangleRounded(rec, 0.2f, 8, hovered ? fade(baseColor, 0.8f) : fade(baseColor, 0.6f));
        drawRectangleRoundedLines(rec, 0.2f, 8, hovered ? WHITE : fade(WHITE, 0.4f));

        int fontSize = 14;
        int tw = measureLegibleText(text, fontSize);
        drawLegibleText(text, x + (w - tw)/2, y + (h - fontSize)/2, fontSize, RAYWHITE);

        return clicked;
    }

    private void drawLiveFeed(int sw, int sh) {
        int fy = sh - 160;
        drawRectangle(sw - 350, fy, 140, 100, fade(BLACK, 0.6f));
        drawLegibleText("LIVE FEED", sw - 340, fy + 5, 13, GOLD);
        
        for (int j = 0; j < Math.min(5, worldEvents.size()); j++) {
            WorldEvent ev = worldEvents.get(worldEvents.size() - 1 - j);
            drawLegibleText("> " + ev.message, sw - 340, fy + 25 + (j * 14), 11, new Color().r((byte)ev.r).g((byte)ev.g).b((byte)ev.b).a((byte)ev.a));
        }
    }

    private void drawEventLog() {
        int w = 600; int h = 300;
        int x = 15;
        int y = getScreenHeight() - h - 15;
        drawRectangle(x, y, w, h, fade(BLACK, 0.85f));
        drawRectangleLines(x, y, w, h, GOLD);
        drawLegibleText("LOG DE EVENTOS DEL SISTEMA", x + 10, y + 10, 14, GOLD);
        
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        for (int j = 0; j < Math.min(15, worldEvents.size()); j++) {
            WorldEvent ev = worldEvents.get(worldEvents.size() - 1 - j);
            String timeStr = sdf.format(new java.util.Date((long)(ev.timestamp * 1000)));
            drawLegibleText("[" + timeStr + "] " + ev.message, x + 15, y + 30 + (j * 16), 11, new Color().r((byte)ev.r).g((byte)ev.g).b((byte)ev.b).a((byte)ev.a));
        }
    }

    private void drawHelpOverlay() {
        drawRectangle(15, 15, 285, 245, fade(BLACK, 0.75f));
        drawRectangleLines(15, 15, 285, 245, GOLD);
        drawLegibleText("CONTROLES DE CÁMARA", 20, 20, 14, GOLD);
        drawLegibleText("- Teclas: W,S,A,D,Q,E", 25, 45, 12, RAYWHITE);
        drawLegibleText("- Mouse: Click Derecho Girar", 25, 60, 12, RAYWHITE);
        drawLegibleText("- Rueda Mouse: Zoom +/-", 25, 75, 12, RAYWHITE);
        drawLegibleText("- C: Cambiar Cámara / Reset", 25, 90, 12, RAYWHITE);
        drawLegibleText("- F: Modo Seguir Agente", 25, 105, 12, RAYWHITE);
        drawLegibleText("- L: Lock/Unlock Plano", 25, 120, 12, RAYWHITE);
        drawLegibleText("- Esc: Cerrar App", 25, 135, 12, RAYWHITE);
        drawLegibleText("- Tab: Toggle esta Ayuda", 25, 150, 12, RAYWHITE);
        drawLegibleText("- Enter: Abrir/Cerrar Chat", 25, 165, 12, LIME);
        drawLegibleText("- Clic Derecho en Nodo: Ver Recursos", 25, 180, 11, GOLD);
        drawLegibleText("- Tecla N: Ciclar Servidores y Abrir Inspector", 25, 195, 11, GOLD);
        drawLegibleText("- Tecla K: Gestión de Conexiones JettraStore", 25, 210, 11, LIME);
        drawLegibleText("- Tecla R: Sincronizar Mundo en Tiempo Real", 25, 225, 11, SKYBLUE);
    }

    private void drawConfigModal() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.4f));
        
        int mw = 440; int mh = 390;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;
        
        drawRectangle(mx, my, mw, mh, DARKGRAY);
        drawRectangleLines(mx, my, mw, mh, RAYWHITE);
        drawText("CONFIGURACIÓN DE AUTONOMÍA", mx + 20, my + 20, 20, GOLD);

        configEnabledApps = guiToggle(mx + 20, my + 60, "Auto-abrir Aplicaciones (VLC/Chrome)", configEnabledApps);
        configEnabledInternet = guiToggle(mx + 20, my + 90, "Búsqueda de Aprendizaje en Internet", configEnabledInternet);
        configEnabledFiles = guiToggle(mx + 20, my + 120, "Estudio de Archivos Locales (PDF/MD)", configEnabledFiles);
        configEnabledLife = guiToggle(mx + 20, my + 150, "Población Silvestre (Animales/Autos)", configEnabledLife);
        configEnabledSocial = guiToggle(mx + 20, my + 180, "Simulación Social Avanzada", configEnabledSocial);

        boolean currentMultinode = (policeMonitor != null && policeMonitor.isMultinodeActive());
        boolean newMultinode = guiToggle(mx + 20, my + 210, "cluster.multinode.active (" + (currentMultinode ? "ON - Distribución Activa" : "OFF - Modo Local") + ")", currentMultinode);
        if (newMultinode != currentMultinode && policeMonitor != null) {
            policeMonitor.setMultinodeActive(newMultinode);
            worldEvents.add(new WorldEvent(
                "JettraStorePolice: cluster.multinode.active cambiado a " + (newMultinode ? "ON" : "OFF"),
                worldTime, newMultinode ? 60 : 255, newMultinode ? 255 : 120, 60));
        }

        if (guiButton(mx + mw - 120, my + mh - 50, 100, 30, "CERRAR", GRAY)) {
            showConfigModal = false;
        }
    }

    private boolean guiToggle(int x, int y, String text, boolean value) {
        if (guiButton(x, y, 20, 20, value ? "X" : " ", value ? LIME : GRAY)) {
            value = !value;
        }
        drawText(text, x + 30, y + 5, 12, RAYWHITE);
        return value;
    }
    
    private void initPopulation() {
        resetWorldWithJettraStore();
    }

    private void resetWorldWithJettraStore() {
        entities.clear();
        artifacts.clear();
        megaProjects.clear();

        if (policeMonitor == null) {
            policeMonitor = new JettraStorePoliceMonitor();
        }

        // 1. Edificios (Lugares donde se conectan los usuarios, agrupados por zonas cercanas)
        for (UserZoneGroup zone : policeMonitor.getUserZones()) {
            artifacts.add(new Artifact(
                zone.getBuildingName(),
                zone.getPrimaryDatabase(),
                zone.getBuildingDescription(),
                zone.getBuildingType(),
                zone.getBuildingX(), zone.getBuildingY(), zone.getBuildingZ(),
                zone.getColorR(), zone.getColorG(), zone.getColorB(),
                zone.getConnectedUserCount() + " usuarios activos | " + zone.getSubnetPrefix() + ".x"
            ));
        }

        // 2. Oficial Supervisor JettraStorePolice
        if (policeSentinelEntity == null) {
            policeSentinelEntity = new HumanEntity();
            policeSentinelEntity.name = "JettraStorePolice";
            policeSentinelEntity.action = "PATROLLING";
            policeSentinelEntity.job = "Centinela Supervisor de Servidores JettraStore";
            policeSentinelEntity.r = 30; policeSentinelEntity.g = 144; policeSentinelEntity.b = 255;
            policeSentinelEntity.isPoliceOfficer = true;
        }
        policeSentinelEntity.x = -14.0f; policeSentinelEntity.y = 0.0f; policeSentinelEntity.z = -8.5f;
        policeSentinelEntity.targetX = -14.0f; policeSentinelEntity.targetZ = -8.5f; policeSentinelEntity.targetY = 0.0f;
        policeSentinelEntity.currentThought = "🛡️ JettraStorePolice: Supervisando estabilidad Heap y quórum Raft...";
        policeSentinelEntity.thoughtTimer = 6.0f;
        entities.add(policeSentinelEntity);

        // 3. Perros: Agentes JettraPolice que se activan en JettraStore
        for (JettraPoliceAgent agent : policeMonitor.getActivePoliceAgents()) {
            HumanEntity k9 = new HumanEntity();
            k9.name = agent.getName();
            k9.isWolf = true;
            k9.isPoliceK9 = true;
            if (agent.getName().contains("Alpha")) k9.isJettraMascot = true;
            k9.action = "PATROLLING";
            ServerNode3D node = policeMonitor.getNodeById(agent.getTargetNodeId());
            float nx = (node != null) ? node.getX() : 0f;
            float nz = (node != null) ? node.getZ() : -8f;
            k9.x = nx + 3.0f; k9.y = 0; k9.z = nz;
            k9.targetX = nx; k9.targetZ = nz;
            if (agent.isAlertActive()) {
                k9.r = 255; k9.g = 50; k9.b = 50;
            } else if (k9.isJettraMascot) {
                k9.r = 255; k9.g = 215; k9.b = 0;
            } else {
                k9.r = 30; k9.g = 144; k9.b = 255;
            }
            k9.currentThought = agent.getCurrentMission();
            k9.thoughtTimer = 6.0f;
            entities.add(k9);
        }

        // 4. Camiones: Tráfico de datos en tiempo real analizando el clúster
        for (ClusterDataTraffic traffic : policeMonitor.getActiveTraffic()) {
            HumanEntity truck = new HumanEntity();
            truck.name = traffic.getName();
            truck.isCar = true;
            truck.action = "DRIVING";
            ServerNode3D src = policeMonitor.getNodeById(traffic.getSourceNodeId());
            ServerNode3D tgt = policeMonitor.getNodeById(traffic.getTargetNodeId());
            float sx = (src != null) ? src.getX() : 0f;
            float sz = (src != null) ? src.getZ() : -8f;
            float tx = (tgt != null) ? tgt.getX() : 14f;
            float tz = (tgt != null) ? tgt.getZ() : -8f;
            truck.x = sx; truck.y = 0; truck.z = sz;
            truck.targetX = tx; truck.targetZ = tz;
            truck.dataPayload = traffic.getPayloadSummary();
            truck.connectedDatabase = traffic.getSourceNodeId() + " -> " + traffic.getTargetNodeId();
            truck.r = 255; truck.g = 180; truck.b = 40;
            truck.currentThought = "🚚 " + traffic.getPayloadSummary();
            truck.thoughtTimer = 5.0f;
            entities.add(truck);
        }

        // 5. Personas: Usuarios conectados en tiempo real a JettraStore
        for (JettraLiveSession session : policeMonitor.getLiveSessions()) {
            UserZoneGroup zone = policeMonitor.getZoneById(session.getZoneId());
            ServerNode3D node = policeMonitor.getNodeById(session.getTargetNodeId());
            HumanEntity u = new HumanEntity();
            u.name = session.getUsername();
            u.connectedDatabase = session.getDatabase();
            u.connectionFacility = (zone != null) ? zone.getBuildingName() : "Sede Central";
            u.targetServer = session.getTargetNodeId();
            u.job = "Usuario Conectado (" + session.getClientIp() + ")";
            u.action = "WALKING";
            if (zone != null) {
                u.r = zone.getColorR(); u.g = zone.getColorG(); u.b = zone.getColorB();
                u.x = zone.getBuildingX() + (float)(Math.random() * 2 - 1);
                u.y = 0;
                u.z = zone.getBuildingZ() + (float)(Math.random() * 2 - 1);
            }
            if (node != null) {
                u.targetX = node.getX();
                u.targetZ = node.getZ();
            }
            u.currentThought = session.getCurrentOperation();
            u.thoughtTimer = 5.0f;
            entities.add(u);
        }

        String connName = (policeMonitor.getCurrentProfile() != null) ? policeMonitor.getCurrentProfile().getName() : "Local";
        triggerWorldEvent("Mundo sincronizado en tiempo real con [" + connName + "]: Edificios por zonas, usuarios en vivo, tráfico de clúster y agentes JettraPolice activos.", 0, 255, 180);
    }

    private boolean loadWorldState() {
        try {
            java.io.File file = new java.io.File("memory/world/world_state.json");
            if (file.exists()) {
                WorldState state = mapper.readValue(file, WorldState.class);
                entities.clear();
                entities.addAll(state.entities);
                artifacts.clear();
                artifacts.addAll(state.artifacts);
                megaProjects.clear();
                megaProjects.addAll(state.megaProjects);
                worldTime = state.worldTime;
                return true;
            }
        } catch (Exception ex) {
            System.err.println("Error loading world state: " + ex.getMessage());
        }
        return false;
    }

    private void drawKnowledgeBase() {
        for (KnowledgeEntry k : allKnowledge) {
            Vector3 pos = new Vector3().x(k.x).y(k.y + 0.5f).z(k.z);
            // Floating glowing sphere for knowledge
            float pulse = (float)Math.sin(worldTime * 2.0f) * 0.1f;
            drawSphere(pos, 0.2f + pulse, fade(GOLD, 0.6f));
            drawSphereWires(pos, 0.25f + pulse, 8, 8, fade(SKYBLUE, 0.4f));
        }
    }

    private void drawWolf(Vector3 pos, float rotDeg, Color color) {
        // Wolf Model - Native Shapes
        float x = pos.x(); float y = pos.y(); float z = pos.z();
        float rotRad = rotDeg * ( (float)Math.PI / 180.0f);
        
        // Body (Capsule/Cylinder)
        drawCapsule(new Vector3().x(x).y(y + 0.5f).z(z), 
                   new Vector3().x(x + (float)Math.sin(rotRad)*1.2f).y(y + 0.6f).z(z + (float)Math.cos(rotRad)*1.2f), 
                   0.5f, 8, 8, DARKGRAY);

        // Legs (4 cylinders)
        float legOff = 0.3f;
        drawCylinder(new Vector3().x(x - legOff).y(y).z(z - legOff), 0.1f, 0.1f, 0.6f, 6, DARKGRAY);
        drawCylinder(new Vector3().x(x + legOff).y(y).z(z - legOff), 0.1f, 0.1f, 0.6f, 6, DARKGRAY);
        drawCylinder(new Vector3().x(x - legOff + (float)Math.sin(rotRad)).y(y).z(z - legOff + (float)Math.cos(rotRad)), 0.1f, 0.1f, 0.6f, 6, DARKGRAY);
        drawCylinder(new Vector3().x(x + legOff + (float)Math.sin(rotRad)).y(y).z(z + legOff + (float)Math.cos(rotRad)), 0.1f, 0.1f, 0.6f, 6, DARKGRAY);

        // Head (Sphere + Snout)
        float headX = x + (float)Math.sin(rotRad)*1.6f;
        float headZ = z + (float)Math.cos(rotRad)*1.6f;
        drawSphere(new Vector3().x(headX).y(y + 1.2f).z(headZ), 0.45f, DARKGRAY);
        drawCylinder(new Vector3().x(headX + (float)Math.sin(rotRad)*0.3f).y(y + 1.1f).z(headZ + (float)Math.cos(rotRad)*0.3f), 
                     0.2f, 0.1f, 0.4f, 6, BLACK); // Snout
        
        // Ears (Triangles represent ears)
        drawSphere(new Vector3().x(headX).y(y + 1.6f).z(headZ + 0.15f), 0.15f, LIGHTGRAY); // Ear L
        drawSphere(new Vector3().x(headX).y(y + 1.6f).z(headZ - 0.15f), 0.15f, LIGHTGRAY); // Ear R

        // Tail
        drawCapsule(new Vector3().x(x).y(y + 0.6f).z(z), 
                   new Vector3().x(x - (float)Math.sin(rotRad)*0.8f).y(y + 0.8f).z(z - (float)Math.cos(rotRad)*0.8f), 
                   0.15f, 4, 4, DARKGRAY);
    }

    private void generateEntity(String name, float x, float y, float z, boolean isWolf, boolean isAnimal, boolean isCar) {
        HumanEntity e = new HumanEntity();
        e.name = name;
        e.x = x; e.y = y; e.z = z;
        e.targetX = x; e.targetY = y; e.targetZ = z;
        e.isWolf = isWolf; e.isAnimal = isAnimal; e.isCar = isCar;
        e.energy = 100;
        e.action = "IDLE";
        e.r = (int)(Math.random()*255); e.g = (int)(Math.random()*255); e.b = (int)(Math.random()*255);
        entities.add(e);
    }

    private static class KnowledgeEntry {
        public long timestamp;
        public String agent;
        public String source;
        public String topic;
        public float x, y, z;
        public double confidence;
        public String context;
        public String[] keywords;
    }

    private com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private void saveKnowledge(HumanEntity agent, String source, String topic) {
        try {
            java.io.File dir = new java.io.File("memory/world");
            if (!dir.exists()) dir.mkdirs();
            
            KnowledgeEntry entry = new KnowledgeEntry();
            entry.timestamp = System.currentTimeMillis();
            entry.agent = agent.name;
            entry.source = source;
            entry.topic = topic;
            entry.x = agent.x; entry.y = agent.y; entry.z = agent.z;
            entry.confidence = 0.7 + (Math.random() * 0.3); // High confidence
            entry.context = agent.action;
            entry.keywords = new String[]{topic.toLowerCase().replace(" ", "-"), "jettra-learning", source.toLowerCase()};

            java.io.File file = new java.io.File("memory/world/knowledge.json");
            java.util.List<KnowledgeEntry> list;
            if (file.exists()) {
                list = mapper.readValue(file, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<KnowledgeEntry>>() {});
            } else {
                list = new java.util.ArrayList<>();
            }

            // --- KNOWLEDGE FUSION LOGIC ---
            boolean found = false;
            for (int i = 0; i < list.size(); i++) {
                KnowledgeEntry existing = list.get(i);
                if (existing.topic.equalsIgnoreCase(entry.topic)) {
                    found = true;
                    // Keep the one with highest confidence
                    if (entry.confidence > existing.confidence) {
                        list.set(i, entry);
                    }
                    break;
                }
            }
            if (!found) {
                list.add(entry);
            }
            // ------------------------------
            allKnowledge = list;
            updateTopKnowledge();

            mapper.writerWithDefaultPrettyPrinter().writeValue(file, list);

        } catch (java.io.IOException ex) {
            System.err.println("Error guardando conocimiento JSON: " + ex.getMessage());
        }
    }

    private void updateTopKnowledge() {
        if (allKnowledge == null || allKnowledge.isEmpty()) {
            java.io.File file = new java.io.File("memory/world/knowledge.json");
            if (file.exists()) {
                try {
                    allKnowledge = mapper.readValue(file, new com.fasterxml.jackson.core.type.TypeReference<List<KnowledgeEntry>>() {});
                } catch (Exception e) {}
            }
        }
        
        if (allKnowledge != null) {
            allKnowledge.sort((a, b) -> Double.compare(b.confidence, a.confidence));
            topKnowledge = allKnowledge.stream().limit(5).collect(java.util.stream.Collectors.toList());
        }
    }

    private void drawTopKnowledgePanel() {
        int x = SCREEN_WIDTH/2 - 200;
        int y = 10;
        drawRectangleRounded(new Rectangle().x(x).y(y).width(400).height(110), 0.2f, 8, fade(DARKGRAY, 0.8f));
        drawRectangleRoundedLines(new Rectangle().x(x).y(y).width(400).height(110), 0.2f, 8, fade(GOLD, 0.5f));
        drawText("TOP 5 CONOCIMIENTO CRÍTICO (MAX CONFIDENCE)", x + 10, y + 10, 10, GOLD);
        
        for (int i = 0; i < topKnowledge.size(); i++) {
            KnowledgeEntry k = topKnowledge.get(i);
            String txt = (i+1) + ". " + k.topic + " (" + String.format("%.2f", k.confidence) + ")";
            drawText(txt, x + 15, y + 30 + (i * 15), 11, RAYWHITE);
        }
    }

    private void drawChatWindow() {
        int w = 400; int h = 250;
        int x = 280; int y = SCREEN_HEIGHT - h - 10;
        drawRectangleRounded(new Rectangle().x(x).y(y).width(w).height(h), 0.1f, 8, fade(BLACK, 0.8f));
        drawRectangleRoundedLines(new Rectangle().x(x).y(y).width(w).height(h), 0.1f, 8, SKYBLUE);
        drawText("CHAT CON JETTRA WOLF (EL LÍDER)", x + 15, y + 15, 14, SKYBLUE);
        
        // History
        for (int i = 0; i < chatHistory.size(); i++) {
            if (i > 8) break;
            drawText("> " + chatHistory.get(chatHistory.size() - 1 - i), x + 15, y + h - 60 - (i * 18), 12, RAYWHITE);
        }

        // Input Box
        drawRectangle(x + 10, y + h - 35, w - 20, 25, DARKGRAY);
        drawRectangleLines(x + 10, y + h - 35, w - 20, 25, RAYWHITE);
        drawText(chatInput + "_", x + 20, y + h - 28, 12, LIME);
        drawText("[ENTER] para enviar", x + 150, y + 20, 10, GRAY);
    }

    private String getRandomResponse(String type) {
        String[] greetings = {"Saludos, creador. Escucho tus órdenes.", "La manada te saluda.", "Aquí el Lobo Jettra.", "Mi conocimiento está a tu disposición."};
        String[] confirms = {"¡Entendido! Mis agentes ya están en ello.", "Como ordenes. Ejecutando mandato.", "Hecho. La red neutral lo sabe.", "La orden ha sido asimilada."};
        String[] weather = {"Alterando la atmósfera.", "Cambiando las variables climáticas.", "Modificando el entorno visible.", "Que cambien los cielos de este mundo."};
        String[] clean = {"Limpiando el plano existencial.", "Todo polvo estelar y artefacto obsoleto ha sido purgado.", "Mundo purificado por el líder.", "He reiniciado las líneas de tiempo de los proyectos."};
        String[] dunno = {"Esa información no está en mis datos cartesianos.", "Mis agentes aún no han aprendido eso.", "Los vectores apuntan a lo desconocido... no lo sé.", "Interesante pregunta, pero carezco de esos cálculos."};

        java.util.Random r = new java.util.Random();
        if ("GREETING".equals(type)) return greetings[r.nextInt(greetings.length)];
        if ("CONFIRM".equals(type)) return confirms[r.nextInt(confirms.length)];
        if ("WEATHER".equals(type)) return weather[r.nextInt(weather.length)];
        if ("CLEAN".equals(type)) return clean[r.nextInt(clean.length)];
        return dunno[r.nextInt(dunno.length)];
    }

    private void sendChatMessage(String msg) {
        chatHistory.add("Tú: " + msg);
        for (HumanEntity e : entities) {
            if (e.name.contains("Jettra")) {
                String res = getRandomResponse("UNKNOWN");
                String lowerMsg = msg.toLowerCase();
                
                if (lowerMsg.matches(".*\\b(hola|saludos|buenas|hey)\\b.*")) res = getRandomResponse("GREETING");
                if (lowerMsg.matches(".*\\b(construye|haz|crea|edifica)\\b.*")) res = getRandomResponse("CONFIRM");
                if (lowerMsg.matches(".*\\b(quien eres|tu nombre)\\b.*")) res = "Soy Jettra Wolf, el guía alfa de esta simulación 3D.";

                // Knowledge Search
                if (res.equals(getRandomResponse("UNKNOWN")) || res.startsWith("Soy Jettra")) {
                    try {
                        java.io.File file = new java.io.File("memory/world/knowledge.json");
                        if (file.exists()) {
                            List<KnowledgeEntry> all = mapper.readValue(file, new com.fasterxml.jackson.core.type.TypeReference<List<KnowledgeEntry>>() {});
                            boolean foundInfo = false;
                            for (KnowledgeEntry k : all) {
                                if (lowerMsg.contains(k.topic.toLowerCase()) || 
                                   (k.keywords != null && java.util.Arrays.stream(k.keywords).anyMatch(lowerMsg::contains))) {
                                    res = "He procesado '" + k.topic + "' desde " + k.source + ". Mis agentes se benefician de ello.";
                                    foundInfo = true;
                                    break;
                                }
                            }
                            if (!foundInfo && lowerMsg.matches(".*\\b(aprender|sabes|conocimiento)\\b.*")) {
                                res = "Estamos asimilando datos de todo tu universo local. Dame PDFs y aprenderé más.";
                            }
                        }
                    } catch (Exception ex) {}
                }

                // Physical Commands
                if (lowerMsg.matches(".*\\b(teletransportar|mover|viajar|centro)\\b.*")) {
                    res = "Teletransporte cuántico activado. Vuelvan a casa.";
                    for (HumanEntity target : entities) {
                        if (!target.name.contains("Jettra")) {
                            target.x = 0; target.z = 0; target.targetX = 0; target.targetZ = 0;
                        }
                    }
                    worldEvents.add(new WorldEvent("Teletransporte masivo activado por Jettra", worldTime, 255, 255, 0));
                }
                if (lowerMsg.matches(".*\\b(poblacion|agentes|crear)\\b.*")) {
                    res = getRandomResponse("CONFIRM") + " Población aumentada.";
                    for(int i=0; i<5; i++) generateEntity("Sentry-" + (int)(Math.random()*1000), (float)(Math.random()*20-10), 0, (float)(Math.random()*20-10), false, false, false);
                }
                if (lowerMsg.matches(".*\\b(limpiar|borrar|eliminar|quitar)\\b.*")) {
                    res = getRandomResponse("CLEAN");
                    artifacts.clear();
                    megaProjects.clear();
                    worldEvents.add(new WorldEvent(res, worldTime, 100, 100, 255));
                }
                if (lowerMsg.matches(".*\\b(acelerar|rapido|tiempo)\\b.*")) {
                    timeScale = (timeScale == 1.0f) ? 5.0f : 1.0f;
                    res = "He ajustado las manecillas del reloj a " + timeScale + "x.";
                    worldEvents.add(new WorldEvent("Manipulación temporal: " + res, worldTime, 0, 255, 200));
                }
                if (lowerMsg.matches(".*\\b(clima|tiempo atmosferico|llover|sol|noche|dia|día)\\b.*")) {
                    weatherMode = (weatherMode + 1) % 3;
                    String[] m = {"Soleado", "Nocturno", "Tormentoso"};
                    res = getRandomResponse("WEATHER") + " Ahora estamos en modo " + m[weatherMode] + ".";
                    worldEvents.add(new WorldEvent("Cambio climático a " + m[weatherMode], worldTime, 200, 0, 255));
                }
                if (lowerMsg.matches(".*\\b(estado|reporte|como estan|salud global)\\b.*")) {
                    int vivos = 0; int muertos = 0; float saludMedia = 0;
                    for (HumanEntity a : entities) {
                        if (a.isDead) muertos++; else { vivos++; saludMedia += a.health; }
                    }
                    if (vivos > 0) saludMedia /= vivos;
                    String contextual = (saludMedia > 60) ? "La manada prospera bajo mi guía." : "La manada está sufriendo. Sus métricas vitales caen.";
                    res = contextual + " " + vivos + " vivos, " + muertos + " fallecidos. " + megaProjects.size() + " proyectos.";
                }
                                
                final String finalRes = res;
                chatHistory.add("Jettra: " + finalRes);
                e.currentThought = finalRes;
                e.thoughtTimer = 5.0f;
                Thought t = new Thought();
                t.content = finalRes; t.owner = e; t.x = e.x; t.y = e.y + 3.5f; t.z = e.z;
                t.timer = 5.0f; t.isThought = false;
                thoughts.add(t);
                break;
            }
        }
        if (chatHistory.size() > 20) chatHistory.remove(0);
    }

    private void initSfx() {
        // Here we could loadSound("rain.wav") or generate procedural waves.
        // For simplicity and to avoid crashes with missing files or JNA-Wave complex buffers, 
        // we'll use a simulation with world events that emit a 'play' signal.
    }

    private void updateSfx() {
        if (!sfxEnabled) return;
        // In reality, this would play/stop a loop based on weatherMode.
    }

    private static class WorldState {
        public List<HumanEntity> entities;
        public List<Artifact> artifacts;
        public List<MegaProject> megaProjects;
        public float worldTime;
    }

    private void saveWorldState() {
        try {
            java.io.File dir = new java.io.File("memory/world");
            if (!dir.exists()) dir.mkdirs();

            WorldState state = new WorldState();
            state.entities = new java.util.ArrayList<>(entities);
            state.artifacts = new java.util.ArrayList<>(artifacts);
            state.megaProjects = new java.util.ArrayList<>(megaProjects);
            state.worldTime = worldTime;

            java.io.File file = new java.io.File("memory/world/world_state.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
            worldEvents.add(new WorldEvent("Mundo guardado automáticamente", worldTime, 0, 255, 100));
        } catch (Exception ex) {
            System.err.println("Error saving world state: " + ex.getMessage());
            worldEvents.add(new WorldEvent("Error guardando mundo", worldTime, 255, 0, 0));
        }
    }

    private void drawJettraStatusTooltip() {
        HumanEntity jettra = null;
        for (HumanEntity e : entities) {
            if (e.name.contains("Jettra")) { jettra = e; break; }
        }
        if (jettra == null) return;

        int x = 10;
        int y = 200;
        int w = 280;
        int h = 100;

        drawRectangleRounded(new Rectangle().x(x).y(y).width(w).height(h), 0.2f, 8, fade(DARKGRAY, 0.9f));
        drawRectangleRoundedLines(new Rectangle().x(x).y(y).width(w).height(h), 0.2f, 8, GOLD);
        
        drawText("VOZ DEL LÍDER: " + jettra.name.toUpperCase(), x + 10, y + 10, 12, GOLD);
        drawText("ACCIÓN: " + jettra.action, x + 10, y + 30, 10, RAYWHITE);
        drawText("Población Total: " + entities.size(), x + 10, y + 45, 10, SKYBLUE);
        drawText("Proyectos Activos: " + megaProjects.size(), x + 140, y + 45, 10, SKYBLUE);
        
        if (jettra.currentThought != null && jettra.thoughtTimer > 0) {
            String thought = jettra.currentThought;
            int maxChars = 40;
            if (thought.length() > maxChars) thought = thought.substring(0, maxChars-3) + "...";
            drawText("PENSANDO: " + thought, x + 10, y + 70, 11, LIME);
        } else {
            drawText("PENSANDO: Vigilando el conocimiento...", x + 10, y + 70, 11, GRAY);
        }
    }

    public static void main(String[] args) {
        new Jettra3DApp().run();
    }

    private void initPoliceMonitor() {
        this.policeMonitor = new JettraStorePoliceMonitor();

        // Agente Centinela JettraStorePolice
        policeSentinelEntity = new HumanEntity();
        policeSentinelEntity.name = "JettraStorePolice";
        policeSentinelEntity.action = "PATROLLING";
        policeSentinelEntity.job = "Centinela Supervisor de Servidores JettraStore";
        policeSentinelEntity.r = 30;
        policeSentinelEntity.g = 144;
        policeSentinelEntity.b = 255;
        policeSentinelEntity.x = -14.0f;
        policeSentinelEntity.y = 0.0f;
        policeSentinelEntity.z = -8.5f;
        policeSentinelEntity.targetX = -14.0f;
        policeSentinelEntity.targetZ = -8.5f;
        policeSentinelEntity.targetY = 0.0f;
        policeSentinelEntity.currentThought = "🛡️ JettraStorePolice: Iniciando inspección de nodos y supervisión de Heap...";
        policeSentinelEntity.thoughtTimer = 6.0f;
        policeSentinelEntity.currentGoal = "Supervisión de Nodos y Estabilidad Heap";
        entities.add(policeSentinelEntity);

        worldEvents.add(new WorldEvent("JettraStorePolice: Centinela de Clúster activado.", worldTime, 30, 144, 255));
    }

    private void updatePolicePatrol(float dt) {
        if (policeMonitor == null) return;
        List<ServerNode3D> nodes = policeMonitor.getServerNodes();
        if (nodes.isEmpty()) return;

        for (ServerNode3D node : nodes) {
            node.setPulsePhase(node.getPulsePhase() + dt * 2.5f);
        }

        if (policeSentinelEntity == null) return;

        policePatrolTimer += dt;
        if (policePatrolTimer >= 5.0f) {
            policePatrolTimer = 0f;
            currentPatrolTargetNodeIndex = (currentPatrolTargetNodeIndex + 1) % nodes.size();
            ServerNode3D target = nodes.get(currentPatrolTargetNodeIndex);
            policeSentinelEntity.targetX = target.getX();
            policeSentinelEntity.targetZ = target.getZ() + 3.2f;
            policeSentinelEntity.targetY = 0f;

            if (target.isOnline()) {
                String thought = String.format("🛡️ JettraStorePolice: Servidor '%s' SALUDABLE (RAM %.0f%%)",
                    target.getId(), target.getHeapSaturationPercent());
                policeSentinelEntity.currentThought = thought;
                policeSentinelEntity.thoughtTimer = 4.5f;
            } else {
                String alert = String.format("🚨 ¡ALERTA POLICIAL! Servidor '%s' FUERA DE SERVICIO", target.getId());
                policeSentinelEntity.currentThought = alert;
                policeSentinelEntity.thoughtTimer = 5.0f;
                worldEvents.add(new WorldEvent("JettraStorePolice: Servidor '" + target.getId() + "' FUERA DE SERVICIO", worldTime, 255, 50, 50));
            }
        }

        float dx = policeSentinelEntity.targetX - policeSentinelEntity.x;
        float dz = policeSentinelEntity.targetZ - policeSentinelEntity.z;
        float dist = (float)Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.4f) {
            policeSentinelEntity.action = "WALKING";
            float speed = 3.5f * dt;
            policeSentinelEntity.x += (dx / dist) * Math.min(speed, dist);
            policeSentinelEntity.z += (dz / dist) * Math.min(speed, dist);
            policeSentinelEntity.rotation = (float)Math.toDegrees(Math.atan2(dx, dz));
        } else {
            policeSentinelEntity.action = "PATROLLING";
        }
    }

    private void drawServerClusterNodes() {
        if (policeMonitor == null) return;
        List<ServerNode3D> nodes = policeMonitor.getServerNodes();

        // 1. Líneas de transmisión de datos entre nodos: SOLO se muestran activas cuando hay transmisión real
        for (int i = 0; i < nodes.size(); i++) {
            ServerNode3D current = nodes.get(i);
            ServerNode3D next = nodes.get((i + 1) % nodes.size());
            Vector3 startPos = new Vector3().x(current.getX()).y(0.3f).z(current.getZ());
            Vector3 endPos = new Vector3().x(next.getX()).y(0.3f).z(next.getZ());

            boolean bothOnline = current.isOnline() && next.isOnline();
            boolean isTransmitting = bothOnline && policeMonitor.isTransferActiveBetween(current.getId(), next.getId());

            if (isTransmitting) {
                // Haz de luz y pulso activo solo durante transmisión real de datos entre nodos
                Color lineColor = fade(GOLD, 0.90f);
                drawLine3D(startPos, endPos, lineColor);

                float progress = policeMonitor.getTransferProgressBetween(current.getId(), next.getId());
                float px = current.getX() + (next.getX() - current.getX()) * progress;
                float pz = current.getZ() + (next.getZ() - current.getZ()) * progress;
                drawSphere(new Vector3().x(px).y(0.38f).z(pz), 0.30f, LIME);
            } else if (!bothOnline) {
                // Enlace interrumpido / nodo desconectado
                drawLine3D(startPos, endPos, fade(RED, 0.25f));
            } else {
                // Enlace en reposo (sin transmisión activa de datos entre estos nodos)
                drawLine3D(startPos, endPos, fade(DARKGRAY, 0.20f));
            }
        }

        // 2. Gabinetes 3D de servidores (Server Racks)
        for (ServerNode3D node : nodes) {
            float x = node.getX();
            float y = node.getY();
            float z = node.getZ();
            float w = node.getWidth();
            float h = node.getHeight();
            float d = node.getDepth();
            boolean online = node.isOnline();

            // Pedestal Base
            Vector3 basePos = new Vector3().x(x).y(y + 0.2f).z(z);
            drawCube(basePos, w + 0.8f, 0.4f, d + 0.8f, new Color().r((byte)30).g((byte)35).b((byte)45).a((byte)255));
            drawCubeWires(basePos, w + 0.8f, 0.4f, d + 0.8f, online ? SKYBLUE : RED);

            // Chasis
            Vector3 bodyPos = new Vector3().x(x).y(y + h / 2.0f + 0.4f).z(z);
            Color chassisColor = online 
                ? new Color().r((byte)18).g((byte)24).b((byte)36).a((byte)255)
                : new Color().r((byte)55).g((byte)18).b((byte)22).a((byte)255);
            drawCube(bodyPos, w, h, d, chassisColor);

            Color wireColor = online ? SKYBLUE : RED;
            drawCubeWires(bodyPos, w, h, d, wireColor);

            // Ranuras Rack y LEDs
            int rackUnits = 5;
            float unitHeight = (h - 0.8f) / rackUnits;
            for (int r = 0; r < rackUnits; r++) {
                float slotY = y + 0.8f + r * unitHeight + unitHeight / 2.0f;
                Vector3 slotPos = new Vector3().x(x).y(slotY).z(z + d / 2.0f + 0.05f);
                drawCube(slotPos, w - 0.4f, unitHeight * 0.7f, 0.1f, new Color().r((byte)10).g((byte)12).b((byte)18).a((byte)255));

                float ledX1 = x - (w / 2.0f) + 0.5f;
                float ledX2 = ledX1 + 0.4f;
                Vector3 led1 = new Vector3().x(ledX1).y(slotY).z(z + d / 2.0f + 0.12f);
                Vector3 led2 = new Vector3().x(ledX2).y(slotY).z(z + d / 2.0f + 0.12f);

                if (online) {
                    drawSphere(led1, 0.08f, LIME);
                    boolean blink = ((int)(worldTime * 4.0f + r) % 2 == 0);
                    drawSphere(led2, 0.08f, blink ? SKYBLUE : DARKGRAY);
                } else {
                    boolean blinkRed = ((int)(worldTime * 2.0f) % 2 == 0);
                    drawSphere(led1, 0.09f, blinkRed ? RED : MAROON);
                    drawSphere(led2, 0.09f, blinkRed ? ORANGE : BLACK);
                }
            }

            // Baliza Superior
            float beaconY = y + h + 0.8f;
            Vector3 beaconPos = new Vector3().x(x).y(beaconY).z(z);
            if (online) {
                Color beaconColor = node.getRole() == ClusterNode.Role.PRIMARY ? GOLD : LIME;
                float pulse = (float)Math.sin(worldTime * 3.0f + x) * 0.08f;
                drawSphere(beaconPos, 0.35f + pulse, beaconColor);
                drawSphereWires(beaconPos, 0.45f + pulse, 8, 8, SKYBLUE);
            } else {
                boolean alertBlink = ((int)(worldTime * 3.0f) % 2 == 0);
                drawCube(beaconPos, 0.6f, 0.6f, 0.6f, alertBlink ? RED : MAROON);
                drawCubeWires(beaconPos, 0.8f, 0.8f, 0.8f, RED);
            }

            if (node == selectedServerNode) {
                drawCubeWires(bodyPos, w + 0.3f, h + 0.3f, d + 0.3f, YELLOW);
            }
        }
    }

    private void drawServerLabelsAndHUD() {
        if (policeMonitor == null) return;
        int sw = getScreenWidth();
        int sh = getScreenHeight();

        // 1. Barra superior de telemetría de clúster JettraStore
        drawRectangle(20, 10, sw - 240, 36, fade(BLACK, 0.75f));
        drawRectangleLines(20, 10, sw - 240, 36, GOLD);
        String procTxt = (policeMonitor != null) ? String.format("%,d", policeMonitor.getProcessedObjectsTotal()) : "8,250,000";
        String iopsTxt = (policeMonitor != null) ? String.format("%,d", policeMonitor.getProcessedObjectsPerSecond()) : "35,000";
        int uCount = (policeMonitor != null) ? policeMonitor.getLiveSessions().size() : 0;
        int bCount = (policeMonitor != null) ? policeMonitor.getUserZones().size() : 0;
        int tCount = (policeMonitor != null) ? policeMonitor.getActiveTraffic().size() : 0;
        int dCount = (policeMonitor != null) ? policeMonitor.getActivePoliceAgents().size() : 0;

        boolean isMulti = (policeMonitor != null && policeMonitor.isMultinodeActive());
        String monitorTitle = isMulti ? "JETTRASTORE CLUSTER MONITOR [MULTINODO: ON]" : "JETTRASTORE STANDALONE MONITOR [MULTINODO: OFF]";
        drawLegibleText(monitorTitle, 30, 15, 12, isMulti ? GOLD : ORANGE);
        drawLegibleText("⚡ " + procTxt + " OBJETOS EN TIEMPO REAL (" + iopsTxt + " OPS/S) | 👥 " + uCount + " PERSONAS | 🏢 " + bCount + " EDIFICIOS | 🚚 " + tCount + " CAMIONES | 🐕 " + dCount + " PERROS", 30, 28, 10, SKYBLUE);

        int curX = 300;
        for (ServerNode3D node : policeMonitor.getServerNodes()) {
            boolean isOnline = node.isOnline();
            Color stColor = isOnline ? LIME : RED;
            String label = String.format("%s [%s]: %s", 
                node.getId(), 
                node.getRole() == ClusterNode.Role.PRIMARY ? "LEADER" : "REPLICA",
                isOnline ? "ONLINE" : "OFFLINE");
            int lw = measureLegibleText(label, 12);
            drawRectangle(curX - 5, 15, lw + 22, 24, fade(BLACK, 0.6f));
            drawRectangleLines(curX - 5, 15, lw + 22, 24, stColor);
            drawCircle(curX + 4, 27, 4.0f, stColor);
            drawLegibleText(label, curX + 14, 21, 12, stColor);
            curX += lw + 32;
        }

        drawLegibleText("Click Derecho en Nodo: Ver Recursos | 'N': Ciclar", sw - 560, 22, 11, RAYWHITE);

        // 2. Badges flotantes 3D sobre cada servidor
        for (ServerNode3D node : policeMonitor.getServerNodes()) {
            Vector3 top3D = new Vector3().x(node.getX()).y(node.getY() + node.getHeight() + 1.6f).z(node.getZ());
            Vector2 screenPos = getWorldToScreen(top3D, camera);
            int sx = (int)screenPos.x();
            int sy = (int)screenPos.y();

            if (sx > 20 && sx < sw - 220 && sy > 40 && sy < sh - 20) {
                String title = node.getName();
                String status = node.isOnline() ? "● EN LÍNEA" : "▲ FUERA DE SERVICIO";
                Color stColor = node.isOnline() ? LIME : RED;

                int bw = Math.max(measureLegibleText(title, 12), measureLegibleText(status, 11)) + 18;
                int bh = 38;
                int bx = sx - bw / 2;
                int by = sy - bh;

                drawRectangleRounded(new Rectangle().x(bx).y(by).width(bw).height(bh), 0.25f, 6, fade(BLACK, 0.8f));
                drawRectangleRoundedLines(new Rectangle().x(bx).y(by).width(bw).height(bh), 0.25f, 6, stColor);

                drawLegibleText(title, bx + (bw - measureLegibleText(title, 12)) / 2, by + 4, 12, RAYWHITE);
                drawLegibleText(status, bx + (bw - measureLegibleText(status, 11)) / 2, by + 20, 11, stColor);
            }
        }
    }

    private void drawNodeInspectorModal() {
        if (!showNodeInspectorModal || selectedServerNode == null) return;

        int sw = getScreenWidth();
        int sh = getScreenHeight();

        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.45f));

        int mw = 560;
        int mh = 480;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;

        Rectangle modalRec = new Rectangle().x(mx).y(my).width(mw).height(mh);
        drawRectangleRounded(modalRec, 0.08f, 10, new Color().r((byte)14).g((byte)20).b((byte)32).a((byte)250));
        drawRectangleRoundedLines(modalRec, 0.08f, 10, selectedServerNode.isOnline() ? GOLD : RED);

        drawText("INSPECTOR DE NODO JETTRASTORE", mx + 25, my + 20, 18, GOLD);
        drawLegibleText("Servidor: " + selectedServerNode.getName() + " (" + selectedServerNode.getHost() + ":" + selectedServerNode.getPort() + ")", 
            mx + 25, my + 46, 12, SKYBLUE);

        boolean isMulti = (policeMonitor != null && policeMonitor.isMultinodeActive());
        int mbw = 185;
        int mbx = mx + mw - 45 - mbw - 10;
        int mby = my + 17;
        Rectangle multiRec = new Rectangle().x(mbx).y(mby).width(mbw).height(24);
        Color multiBg = isMulti ? new Color().r((byte)15).g((byte)45).b((byte)25).a((byte)240) : new Color().r((byte)55).g((byte)30).b((byte)15).a((byte)240);
        drawRectangleRounded(multiRec, 0.3f, 4, multiBg);
        drawRectangleRoundedLines(multiRec, 0.3f, 4, isMulti ? LIME : ORANGE);
        drawLegibleText(isMulti ? "MULTINODO: ON (Raft)" : "MULTINODO: OFF (Local)", mbx + 10, mby + 5, 11, isMulti ? LIME : ORANGE);

        if (guiButton(mx + mw - 45, my + 15, 30, 25, "X", RED)) {
            showNodeInspectorModal = false;
        }

        int cy = my + 75;
        boolean online = selectedServerNode.isOnline();
        Color stBannerColor = online ? new Color().r((byte)20).g((byte)60).b((byte)30).a((byte)255) 
                                     : new Color().r((byte)80).g((byte)20).b((byte)25).a((byte)255);
        Rectangle stRec = new Rectangle().x(mx + 25).y(cy).width(mw - 50).height(32);
        drawRectangleRounded(stRec, 0.2f, 6, stBannerColor);
        drawRectangleRoundedLines(stRec, 0.2f, 6, online ? LIME : RED);

        String stText = online 
            ? "● ESTADO: EN LÍNEA (RUNNING) - Latencia: " + selectedServerNode.getLatencyMs() + " ms | Rol: " + selectedServerNode.getRole() + " (" + selectedServerNode.getRaftState() + ")"
            : "▲ ESTADO: FUERA DE SERVICIO (OFFLINE) - " + selectedServerNode.getStatusMessage();
        drawLegibleText(stText, mx + 35, cy + 9, 12, online ? LIME : RED);

        cy += 45;
        drawRectangle(mx + 25, cy, mw - 50, 48, fade(BLACK, 0.5f));
        drawRectangleLines(mx + 25, cy, mw - 50, 48, online ? SKYBLUE : RED);
        drawLegibleText("🛡️ JettraStorePolice (Evaluación de Estabilidad & Heap):", mx + 35, cy + 6, 11, GOLD);
        drawLegibleText(selectedServerNode.getPoliceDiagnosis(), mx + 35, cy + 24, 11, online ? RAYWHITE : RED);

        cy += 60;
        drawText("RECURSOS CONSUMIDOS:", mx + 25, cy, 14, GOLD);
        cy += 22;

        long hUsed = selectedServerNode.getHeapUsedMb();
        long hMax = selectedServerNode.getHeapMaxMb();
        double satPct = selectedServerNode.getHeapSaturationPercent();
        drawLegibleText(String.format("• Memoria Heap JVM: %d MB / %d MB (Máx: %d MB) - Saturación: %.1f%%",
            hUsed, selectedServerNode.getHeapTotalMb(), hMax, satPct), mx + 30, cy, 12, RAYWHITE);
        cy += 18;

        int barW = mw - 60;
        int barH = 12;
        drawRectangle(mx + 30, cy, barW, barH, DARKGRAY);
        Color barColor = satPct > 85 ? RED : (satPct > 70 ? YELLOW : LIME);
        int fillW = online ? (int)((satPct / 100.0) * barW) : 0;
        drawRectangle(mx + 30, cy, Math.min(barW, Math.max(0, fillW)), barH, barColor);
        drawRectangleLines(mx + 30, cy, barW, barH, WHITE);
        cy += 20;

        drawLegibleText("• Memoria Panama FFM Off-Heap: " + selectedServerNode.getPanamaDirectMemMb() + " MB (Arena Cero-Copia Directa)", mx + 30, cy, 12, RAYWHITE);
        cy += 18;
        drawLegibleText("• Procesador Loom: " + selectedServerNode.getCpuCores() + " Cores CPU | " + selectedServerNode.getActiveVirtualThreads() + " Virtual Threads I/O", mx + 30, cy, 12, RAYWHITE);
        cy += 18;
        drawLegibleText(String.format("• Almacenamiento LSM: MemTable %d MB | SSTables %.2f MB (%d archivos .jettra)",
            selectedServerNode.getMemTableMb(), (selectedServerNode.getDiskSSTablesBytes() / (1024.0 * 1024.0)), selectedServerNode.getDiskFilesCount()),
            mx + 30, cy, 12, RAYWHITE);
        cy += 18;
        drawLegibleText("• Latidos y Telemetría: Último heartbeat recibido hace " + (System.currentTimeMillis() - selectedServerNode.getLastHeartbeat()) + " ms", mx + 30, cy, 12, RAYWHITE);
        cy += 18;
        drawLegibleText("• Modo Distribución: cluster.multinode.active = " + (isMulti ? "ON (Consenso & Replicación Activos)" : "OFF (Operación Estricta Servidor Local)"), mx + 30, cy, 12, isMulti ? LIME : ORANGE);

        cy += 35;
        String toggleBtnText = online 
            ? "DESCONECTAR (SIMULAR FUERA DE SERVICIO)" 
            : "RECONECTAR (ACTIVAR EN LÍNEA)";
        Color toggleBtnColor = online ? RED : LIME;

        // Botón para expandir y entrar al submundo del nodo
        if (guiButton(mx + 30, cy - 42, mw - 60, 32, "🌐 EXPANDIR Y ENTRAR AL MUNDO DEL SERVIDOR (INTERIOR)", SKYBLUE)) {
            startNodeExpansion(selectedServerNode);
            return;
        }

        if (guiButton(mx + 30, cy, 320, 32, toggleBtnText, toggleBtnColor)) {
            selectedServerNode.toggleOffline();
            worldEvents.add(new WorldEvent(
                "JettraStorePolice: Servidor " + selectedServerNode.getId() + " conmutado a " + (selectedServerNode.isOnline() ? "EN LÍNEA" : "FUERA DE SERVICIO"),
                worldTime, online ? 255 : 0, online ? 60 : 255, 60));
        }

        if (guiButton(mx + 365, cy, 160, 32, "CERRAR [ESC]", DARKGRAY)) {
            showNodeInspectorModal = false;
        }
    }


    // =========================================================================
    // IMPLEMENTACIÓN DEL MUNDO INTERIOR DEL NODO, BASES DE DATOS Y PUERTA 3D
    // =========================================================================

    private BoundingBox getDoorBoundingBox() {
        Vector3 min = new Vector3().x(-2.8f).y(0f).z(-12.5f);
        Vector3 max = new Vector3().x(2.8f).y(6.0f).z(-9.5f);
        return new BoundingBox(min, max);
    }

    private void startNodeExpansion(ServerNode3D node) {
        this.expandedNode = node;
        this.selectedDatabase = null;
        this.worldMode = WorldMode.EXPANDING_TRANSITION;
        this.transitionTimer = 0.85f;
        this.showNodeInspectorModal = false;
        this.savedMainCameraPos = new Vector3().x(camera.position().x()).y(camera.position().y()).z(camera.position().z());
        this.savedMainCameraTarget = new Vector3().x(camera.target().x()).y(camera.target().y()).z(camera.target().z());
        this.savedMainCameraFov = camera.fovy();
        worldEvents.add(new WorldEvent(
            "Expandiendo dimensión interior del servidor '" + node.getId() + "'...",
            worldTime, 0, 220, 255));
    }

    private void startExitTransition() {
        this.worldMode = WorldMode.EXITING_TRANSITION;
        this.transitionTimer = 0.85f;
        this.selectedDatabase = null;
        worldEvents.add(new WorldEvent(
            "Atravesando Puerta Dimensional... Retornando al Mundo Principal.",
            worldTime, 255, 215, 0));
    }

    private void drawInnerNodeWorld3D() {
        if (expandedNode == null) return;
        boolean online = expandedNode.isOnline();

        // 1. Piso Ciberespacial
        drawGrid(40, 1.2f);
        for (int r = 4; r <= 16; r += 4) {
            Color ringCol = online ? fade(SKYBLUE, 0.45f) : fade(RED, 0.45f);
            drawCircle3D(new Vector3().x(0).y(0.02f).z(0), r, new Vector3().x(1).y(0).z(0), 90.0f, ringCol);
        }

        // Plataforma central
        drawCylinder(new Vector3().x(0).y(0.05f).z(0), 3.2f, 3.5f, 0.1f, 24, DARKGRAY);
        drawCylinderWires(new Vector3().x(0).y(0.05f).z(0), 3.2f, 3.5f, 0.1f, 24, online ? SKYBLUE : RED);

        // 2. Columnas perimetrales de memoria y almacenamiento (Bancos de Servidores Tron)
        for (int i = 0; i < 8; i++) {
            double angle = (i / 8.0) * Math.PI * 2.0;
            float px = (float)(Math.cos(angle) * 16.5);
            float pz = (float)(Math.sin(angle) * 16.5);
            Vector3 pillarPos = new Vector3().x(px).y(4.0f).z(pz);
            drawCube(pillarPos, 1.4f, 8.0f, 1.4f, new Color().r((byte)15).g((byte)20).b((byte)35).a((byte)255));
            drawCubeWires(pillarPos, 1.42f, 8.02f, 1.42f, online ? DARKBLUE : MAROON);

            // Luces LED de actividad en las columnas
            for (int l = 0; l < 5; l++) {
                float ly = 1.0f + l * 1.4f;
                Color ledCol = ((int)(innerWorldTime * 4 + i + l) % 2 == 0) ? (online ? LIME : RED) : DARKGRAY;
                drawSphere(new Vector3().x(px).y(ly).z(pz + 0.72f), 0.12f, ledCol);
            }
        }

        // 3. Bases de Datos 3D Alojadas en este Nodo
        for (DatabaseInfo3D db : expandedNode.getDatabases()) {
            Vector3 basePos = new Vector3().x(db.getX()).y(0.25f).z(db.getZ());
            Color dbColor = new Color().r((byte)db.getR()).g((byte)db.getG()).b((byte)db.getB()).a((byte)255);
            if (!online) {
                dbColor = RED;
            }

            // Pedestal metálico cilíndrico
            drawCylinder(basePos, db.getRadius(), db.getRadius() * 1.15f, 0.5f, 20, DARKGRAY);
            drawCylinderWires(basePos, db.getRadius(), db.getRadius() * 1.15f, 0.5f, 20, dbColor);

            // Núcleo cristalino flotante y rotando
            float coreY = basePos.y() + 1.8f + (float)Math.sin(innerWorldTime * 2.2f + db.getPulsePhase()) * 0.25f;
            Vector3 corePos = new Vector3().x(db.getX()).y(coreY).z(db.getZ());

            drawCube(corePos, 1.2f, 1.2f, 1.2f, dbColor);
            drawCubeWires(corePos, 1.22f, 1.22f, 1.22f, WHITE);

            // Anillos orbitales giratorios de datos
            float orbitAngle = innerWorldTime * 45.0f + db.getPulsePhase() * 10.0f;
            drawCircle3D(corePos, 1.8f, new Vector3().x(1).y(1).z(0), orbitAngle, fade(dbColor, 0.65f));

            // Si está seleccionada, resaltar caja delimitadora
            if (db == selectedDatabase) {
                drawBoundingBox(db.getBoundingBox(), GOLD);
            }

            // Rayo de haz conectando la base con la plataforma central
            drawLine3D(new Vector3().x(0).y(0.1f).z(0), basePos, fade(dbColor, 0.5f));
        }

        // 4. LA PUERTA AL MUNDO PRINCIPAL (Return Portal Gate)
        drawReturnDoor3D(online);
    }

    private void drawReturnDoor3D(boolean online) {
        float doorZ = -11.0f;
        Color archFrameColor = new Color().r((byte)20).g((byte)25).b((byte)45).a((byte)255);
        Color neonColor = online ? SKYBLUE : RED;

        // Columnas laterales de la puerta
        Vector3 leftCol = new Vector3().x(-2.6f).y(3.0f).z(doorZ);
        Vector3 rightCol = new Vector3().x(2.6f).y(3.0f).z(doorZ);
        drawCube(leftCol, 0.7f, 6.0f, 0.7f, archFrameColor);
        drawCubeWires(leftCol, 0.72f, 6.02f, 0.72f, neonColor);
        drawCube(rightCol, 0.7f, 6.0f, 0.7f, archFrameColor);
        drawCubeWires(rightCol, 0.72f, 6.02f, 0.72f, neonColor);

        // Viga superior del arco
        Vector3 topBeam = new Vector3().x(0f).y(6.1f).z(doorZ);
        drawCube(topBeam, 6.0f, 0.6f, 0.8f, archFrameColor);
        drawCubeWires(topBeam, 6.02f, 0.62f, 0.82f, GOLD);

        // Umbral luminoso en el piso
        Vector3 floorStep = new Vector3().x(0f).y(0.08f).z(doorZ);
        drawCube(floorStep, 5.4f, 0.16f, 2.4f, fade(SKYBLUE, 0.35f));
        drawCubeWires(floorStep, 5.42f, 0.18f, 2.42f, WHITE);

        // Vórtice de energía giratorio en el centro del portal
        float pAngle = innerWorldTime * 55.0f;
        Vector3 portalCenter = new Vector3().x(0f).y(3.0f).z(doorZ);
        drawCircle3D(portalCenter, 2.2f, new Vector3().x(0).y(0).z(1), pAngle, fade(SKYBLUE, 0.5f));
        drawCircle3D(portalCenter, 1.5f, new Vector3().x(0).y(0).z(1), -pAngle * 1.4f, fade(MAGENTA, 0.65f));
        drawCircle3D(portalCenter, 0.8f, new Vector3().x(0).y(0).z(1), pAngle * 2.2f, fade(GOLD, 0.8f));

        // Letrero 3D flotante sobre la puerta
        drawBoundingBox(getDoorBoundingBox(), fade(neonColor, 0.25f));
    }

    private void drawInnerNodeWorldHUD() {
        if (expandedNode == null) return;
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        boolean online = expandedNode.isOnline();

        // 1. Barra Superior con Identidad del Nodo y Botón para Atravesar la Puerta
        drawRectangle(0, 0, screenW, 56, fade(new Color().r((byte)10).g((byte)15).b((byte)30).a((byte)255), 0.92f));
        drawRectangleLines(0, 0, screenW, 56, online ? SKYBLUE : RED);

        drawText("🌐 MUNDO INTERIOR: SERVIDOR " + expandedNode.getId().toUpperCase(), 25, 12, 17, GOLD);
        drawLegibleText("HOST: " + expandedNode.getHost() + " | PUERTO: " + expandedNode.getPort() + " | ROL: " + expandedNode.getRole() + " (" + expandedNode.getRaftState() + ")",
            25, 34, 12, SKYBLUE);

        // Botón destacado: Atravesar Puerta y Salir
        Rectangle exitBtnRec = new Rectangle().x(screenW - 410).y(10).width(390).height(36);
        boolean hoverExit = checkCollisionPointRec(getMousePosition(), exitBtnRec);
        drawRectangleRounded(exitBtnRec, 0.2f, 6, hoverExit ? GOLD : DARKBLUE);
        drawRectangleRoundedLines(exitBtnRec, 0.2f, 6, hoverExit ? WHITE : SKYBLUE);
        drawLegibleText("🚪 ATRAVESAR PUERTA - SALIR AL MUNDO PRINCIPAL [ESC]", screenW - 395, 20, 11, hoverExit ? BLACK : RAYWHITE);

        // 2. Panel Izquierdo: RECURSOS CONSUMIDOS EN ESTE NODO
        int px = 20, py = 70, pw = 380, ph = 490;
        Rectangle panelRec = new Rectangle().x(px).y(py).width(pw).height(ph);
        drawRectangleRounded(panelRec, 0.05f, 8, fade(new Color().r((byte)12).g((byte)16).b((byte)28).a((byte)255), 0.90f));
        drawRectangleRoundedLines(panelRec, 0.05f, 8, online ? SKYBLUE : RED);

        drawText("RECURSOS CONSUMIDOS EN EL NODO", px + 16, py + 14, 14, GOLD);
        drawLegibleText("HOST: " + expandedNode.getHost() + " | Puerto: " + expandedNode.getPort() + " | Telemetría en Vivo", px + 16, py + 34, 11, SKYBLUE);

        int cy = py + 55;
        // Badge Estado
        Color badgeBg = online ? new Color().r((byte)20).g((byte)60).b((byte)30).a((byte)255) : new Color().r((byte)80).g((byte)20).b((byte)25).a((byte)255);
        Rectangle bRec = new Rectangle().x(px + 16).y(cy).width(pw - 32).height(26);
        drawRectangleRounded(bRec, 0.2f, 4, badgeBg);
        drawRectangleRoundedLines(bRec, 0.2f, 4, online ? LIME : RED);
        drawLegibleText(online ? "● EN LÍNEA [" + expandedNode.getHost() + "] - LATENCIA: " + expandedNode.getLatencyMs() + " ms" : "▲ FUERA DE SERVICIO (OFFLINE - " + expandedNode.getHost() + ")",
            px + 26, cy + 6, 11, online ? LIME : RED);

        cy += 35;
        // Dictamen Policial
        drawRectangle(px + 16, cy, pw - 32, 42, fade(BLACK, 0.5f));
        drawRectangleLines(px + 16, cy, pw - 32, 42, online ? SKYBLUE : RED);
        drawLegibleText("🛡️ JettraStorePolice:", px + 22, cy + 4, 10, GOLD);
        drawLegibleText(expandedNode.getPoliceDiagnosis(), px + 22, cy + 20, 10, online ? RAYWHITE : RED);

        cy += 50;
        // Métricas de Memoria Heap
        long hUsed = expandedNode.getHeapUsedMb();
        long hMax = expandedNode.getHeapMaxMb();
        double satPct = expandedNode.getHeapSaturationPercent();
        drawLegibleText(String.format("• Memoria Heap JVM: %d MB / %d MB (%.1f%%)", hUsed, hMax, satPct), px + 16, cy, 11, RAYWHITE);
        cy += 16;
        int barW = pw - 32;
        int barH = 10;
        drawRectangle(px + 16, cy, barW, barH, DARKGRAY);
        Color barColor = satPct > 85 ? RED : (satPct > 70 ? YELLOW : LIME);
        int fillW = online ? (int)((satPct / 100.0) * barW) : 0;
        drawRectangle(px + 16, cy, Math.min(barW, Math.max(0, fillW)), barH, barColor);
        drawRectangleLines(px + 16, cy, barW, barH, WHITE);

        cy += 20;
        drawLegibleText("• Memoria Off-Heap (Panama FFM): " + expandedNode.getPanamaDirectMemMb() + " MB directos", px + 16, cy, 11, RAYWHITE);
        cy += 18;
        drawLegibleText("• Virtual Threads (Loom): " + expandedNode.getActiveVirtualThreads() + " hilos | " + expandedNode.getCpuCores() + " Cores CPU", px + 16, cy, 11, RAYWHITE);
        cy += 18;
        drawLegibleText(String.format("• Almacenamiento LSM: MemTable %d MB | SSTables %.1f MB",
            expandedNode.getMemTableMb(), (expandedNode.getDiskSSTablesBytes() / (1024.0 * 1024.0))), px + 16, cy, 11, RAYWHITE);
        cy += 18;
        boolean isMultiNode = (policeMonitor != null && policeMonitor.isMultinodeActive());
        String quorumText = isMultiNode 
            ? "• Quórum Raft: Sincronizado vía JettraDriver (Multinodo ON)" 
            : "• Modo Multinodo: OFF (Servidor local sin distribución de datos)";
        drawLegibleText(quorumText, px + 16, cy, 11, online ? SKYBLUE : RED);

        cy += 30;
        // Botón desconectar / reconectar
        String tText = online ? "DESCONECTAR (SIMULAR CAÍDA)" : "RECONECTAR (ACTIVAR SERVICIO)";
        Color tColor = online ? RED : LIME;
        Rectangle toggleBtn = new Rectangle().x(px + 16).y(cy).width(pw - 32).height(32);
        boolean hoverToggle = checkCollisionPointRec(getMousePosition(), toggleBtn);
        drawRectangleRounded(toggleBtn, 0.2f, 4, hoverToggle ? WHITE : tColor);
        drawLegibleText(tText, px + 35, cy + 8, 11, hoverToggle ? BLACK : WHITE);

        // --- PANEL DE CONTROL PEQUEÑO DEL MUNDO INTERIOR: BLOQUEAR, ACERCAR, ALEJAR, SALIR ---
        int ctrlX = px;
        int ctrlY = py + ph + 12;
        int ctrlW = pw;
        int ctrlH = 68;

        drawRectangleRounded(new Rectangle().x(ctrlX).y(ctrlY).width(ctrlW).height(ctrlH), 0.15f, 6,
            fade(new Color().r((byte)15).g((byte)20).b((byte)35).a((byte)255), 0.94f));
        drawRectangleRoundedLines(new Rectangle().x(ctrlX).y(ctrlY).width(ctrlW).height(ctrlH), 0.15f, 6,
            innerPanelLocked ? RED : GOLD);

        drawLegibleText("🕹️ CONTROL DE PANEL Y NAVEGACIÓN", ctrlX + 12, ctrlY + 8, 10, innerPanelLocked ? RED : GOLD);
        if (innerPanelLocked) {
            drawLegibleText("🔒 [BLOQUEADO]", ctrlX + ctrlW - 100, ctrlY + 8, 10, RED);
        }

        // Botón 1: Bloquear / Desbloquear plano cartesiano y panel
        String lockText = innerPanelLocked ? "DESBLOQUEAR" : "BLOQUEAR";
        Color lockColor = innerPanelLocked ? RED : SKYBLUE;
        if (guiButton(ctrlX + 10, ctrlY + 26, 115, 28, lockText, lockColor)) {
            innerPanelLocked = !innerPanelLocked;
            worldEvents.add(new WorldEvent(
                "Plano Cartesiano de Mundo Interior: " + (innerPanelLocked ? "BLOQUEADO" : "DESBLOQUEADO"),
                worldTime, innerPanelLocked ? 255 : 50, innerPanelLocked ? 50 : 255, 100));
        }

        // Botón 2: Acercar
        if (guiButton(ctrlX + 100, ctrlY + 26, 82, 28, "🔍+ ACERCAR", BLUE)) {
            camera.fovy(Math.max(10.0f, camera.fovy() - 5.0f));
        }

        // Botón 3: Alejar
        if (guiButton(ctrlX + 187, ctrlY + 26, 82, 28, "🔍- ALEJAR", BLUE)) {
            camera.fovy(Math.min(95.0f, camera.fovy() + 5.0f));
        }

        // Botón 4: Salir
        if (guiButton(ctrlX + 274, ctrlY + 26, 96, 28, "🚪 SALIR", RED)) {
            startExitTransition();
        }

        // 3. Resumen de Bases de Datos Flotantes (Proyectadas en 2D sobre los pedestales)
        for (DatabaseInfo3D db : expandedNode.getDatabases()) {
            Vector3 label3D = new Vector3().x(db.getX()).y(db.getY() + 3.4f).z(db.getZ());
            Vector2 screenPos = getWorldToScreen(label3D, camera);
            if (screenPos.x() > 0 && screenPos.x() < screenW && screenPos.y() > 0 && screenPos.y() < screenH) {
                int lx = (int)screenPos.x() - 100;
                int ly = (int)screenPos.y() - 25;
                drawRectangle(lx, ly, 200, 48, fade(BLACK, 0.82f));
                drawRectangleLines(lx, ly, 200, 48, new Color().r((byte)db.getR()).g((byte)db.getG()).b((byte)db.getB()).a((byte)255));
                drawLegibleText(db.getId(), lx + 8, ly + 5, 11, GOLD);
                drawLegibleText(db.getTotalObjects() + " objs | " + db.getSizeFormatted(), lx + 8, ly + 20, 10, RAYWHITE);
                drawLegibleText(online ? "● " + db.getStatus() : "▲ DESCONECTADA", lx + 8, ly + 33, 9, online ? LIME : RED);
            }
        }

        // 4. Puerta al Mundo Principal (Etiqueta 2D sobre la puerta 3D)
        Vector3 doorLabelPos = new Vector3().x(0f).y(6.8f).z(-11.0f);
        Vector2 dScreen = getWorldToScreen(doorLabelPos, camera);
        if (dScreen.x() > 0 && dScreen.x() < screenW && dScreen.y() > 0 && dScreen.y() < screenH) {
            int dx = (int)dScreen.x() - 150;
            int dy = (int)dScreen.y() - 15;
            drawRectangle(dx, dy, 300, 32, fade(DARKBLUE, 0.88f));
            drawRectangleLines(dx, dy, 300, 32, GOLD);
            drawLegibleText("🚪 PUERTA AL MUNDO PRINCIPAL (CLIC / ESC)", dx + 18, dy + 8, 11, GOLD);
        }

        // 5. Barra Inferior de Información y Ayuda
        drawRectangle(0, screenH - 42, screenW, 42, fade(BLACK, 0.85f));
        drawRectangleLines(0, screenH - 42, screenW, 42, DARKGRAY);
        drawLegibleText("ℹ️ Haz clic sobre cualquier Base de Datos 3D para inspeccionar sus Buckets | Haz clic en la Puerta 3D o presiona ESC para regresar",
            30, screenH - 28, 11, RAYWHITE);
    }

    private void drawDatabaseDetailModal() {
        if (selectedDatabase == null) return;
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();

        int dw = 520, dh = 460;
        int dx = (screenW - dw) / 2 + 100;
        int dy = (screenH - dh) / 2;

        Rectangle dRec = new Rectangle().x(dx).y(dy).width(dw).height(dh);
        drawRectangleRounded(dRec, 0.06f, 8, fade(new Color().r((byte)14).g((byte)18).b((byte)32).a((byte)255), 0.95f));
        drawRectangleRoundedLines(dRec, 0.06f, 8, GOLD);

        drawText("FICHA TÉCNICA DE BASE DE DATOS", dx + 24, dy + 18, 16, GOLD);
        drawLegibleText(selectedDatabase.getTitle() + " (" + selectedDatabase.getId() + ")", dx + 24, dy + 42, 12, SKYBLUE);

        // Botón Cerrar
        if (guiButton(dx + dw - 42, dy + 15, 28, 25, "X", RED)) {
            selectedDatabase = null;
            return;
        }

        int cy = dy + 70;
        drawLegibleText("• Descripción: " + selectedDatabase.getDescription(), dx + 24, cy, 11, RAYWHITE);
        cy += 20;
        drawLegibleText("• Motor de Datos: " + selectedDatabase.getEngineType(), dx + 24, cy, 11, RAYWHITE);
        cy += 20;
        drawLegibleText("• Objetos Totales: " + String.format("%,d", selectedDatabase.getTotalObjects()) + " registros", dx + 24, cy, 11, RAYWHITE);
        cy += 20;
        drawLegibleText("• Tamaño en Almacenamiento: " + selectedDatabase.getSizeFormatted(), dx + 24, cy, 11, RAYWHITE);
        cy += 20;
        drawLegibleText("• Operaciones I/O: " + String.format("%,d", selectedDatabase.getIops()) + " IOPS en tiempo real", dx + 24, cy, 11, RAYWHITE);
        cy += 20;
        drawLegibleText("• Estado de Replicación: " + selectedDatabase.getStatus(), dx + 24, cy, 11, LIME);

        cy += 30;
        drawText("BUCKETS ESPECIALIZADOS (" + selectedDatabase.getBucketsCount() + "):", dx + 24, cy, 13, GOLD);
        cy += 22;

        int row = 0;
        for (String b : selectedDatabase.getBuckets()) {
            drawRectangle(dx + 24, cy + row * 22, dw - 48, 20, fade(BLACK, 0.4f));
            drawRectangleLines(dx + 24, cy + row * 22, dw - 48, 20, DARKBLUE);
            drawLegibleText("  ▸ " + b, dx + 30, cy + row * 22 + 4, 11, SKYBLUE);
            row++;
        }

        cy += row * 22 + 15;
        if (guiButton(dx + 24, cy, 230, 32, "🌳 EXPLORAR ENGINES (CLIC DERECHO)", GOLD)) {
            openEngineExplorerModal(selectedDatabase.getId());
        }
        if (guiButton(dx + 265, cy, 230, 32, "CERRAR DETALLE [ESC]", DARKGRAY)) {
            selectedDatabase = null;
        }
    }

    private void drawNodeExpansionEffect3D() {
        if (expandedNode == null) return;
        float progress = 1.0f - (transitionTimer / 0.85f);
        float radius = progress * 14.0f;
        Vector3 center = new Vector3().x(expandedNode.getX()).y(expandedNode.getY() + 2.5f).z(expandedNode.getZ());
        drawSphereWires(center, radius, 12, 12, fade(SKYBLUE, 0.7f));
        drawCircle3D(center, radius * 1.2f, new Vector3().x(0).y(1).z(0), progress * 180.0f, fade(GOLD, 0.8f));
    }

    private void drawExitingPortalEffect3D() {
        float progress = 1.0f - (transitionTimer / 0.85f);
        float radius = progress * 15.0f;
        Vector3 portalCenter = new Vector3().x(0f).y(3.0f).z(-11.0f);
        drawSphereWires(portalCenter, radius, 14, 14, fade(GOLD, 0.75f));
        drawCircle3D(portalCenter, radius * 1.2f, new Vector3().x(0).y(0).z(1), progress * 360.0f, fade(SKYBLUE, 0.9f));
    }

    private void drawExpandingTransitionOverlay() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        float alpha = (1.0f - (transitionTimer / 0.85f));
        drawRectangle(0, 0, sw, sh, fade(BLACK, alpha * 0.75f));
        drawText("EXPANDIENDO MUNDO INTERIOR DEL NODO...", sw / 2 - 240, sh / 2 - 20, 22, GOLD);
        drawLegibleText("Iniciando espacio cuántico y bases de datos alojadas...", sw / 2 - 180, sh / 2 + 15, 13, SKYBLUE);
    }

    private void drawExitingTransitionOverlay() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        float alpha = (1.0f - (transitionTimer / 0.85f));
        drawRectangle(0, 0, sw, sh, fade(BLACK, alpha * 0.75f));
        drawText("ATRAVESANDO PUERTA DIMENSIONAL...", sw / 2 - 210, sh / 2 - 20, 22, SKYBLUE);
        drawLegibleText("Regresando a la vista macro del clúster...", sw / 2 - 140, sh / 2 + 15, 13, GOLD);
    }

    private void loadSelectedProfileIntoForm() {
        if (policeMonitor == null) return;
        ConnectionProfile cur = policeMonitor.getCurrentProfile();
        if (cur != null) {
            formConnId = cur.getId();
            formConnName = cur.getName();
            formConnUrl = cur.getUrl();
            formConnUsername = cur.getUsername();
            formConnPassword = cur.getPassword();
            formConnIsDefault = cur.isDefault();
            selectedProfileId = cur.getId();
        }
    }

    private HumanEntity findEntityByName(String name) {
        if (name == null) return null;
        for (HumanEntity e : entities) {
            if (name.equalsIgnoreCase(e.name)) return e;
        }
        return null;
    }

    private void drawInteractiveInput(int x, int y, int w, int h, String value, int fieldId, boolean isPassword) {
        Rectangle rec = new Rectangle().x(x).y(y).width(w).height(h);
        Vector2 mouse = getMousePosition();
        boolean hovered = checkCollisionPointRec(mouse, rec);
        boolean isActive = (activeConnField == fieldId);

        if (hovered && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            activeConnField = fieldId;
        }

        Color bg = isActive ? new Color().r((byte)30).g((byte)45).b((byte)75).a((byte)255)
                            : (hovered ? new Color().r((byte)25).g((byte)32).b((byte)52).a((byte)255)
                                       : new Color().r((byte)15).g((byte)20).b((byte)35).a((byte)255));

        drawRectangleRounded(rec, 0.15f, 4, bg);
        drawRectangleRoundedLines(rec, 0.15f, 4, isActive ? GOLD : (hovered ? SKYBLUE : DARKGRAY));

        String display = isPassword ? "•".repeat(value.length()) : value;
        if (isActive && ((int)(worldTime * 2) % 2 == 0)) {
            display += "_";
        }
        drawLegibleText(display, x + 8, y + 8, 12, RAYWHITE);
    }

    private void drawConnectionManagerModal() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();

        // Fondo oscurecido semi-transparente
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.65f));

        int mw = 840;
        int mh = 530;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;

        // Ventana principal con estilo ciberespacial y borde dorado
        drawRectangle(mx, my, mw, mh, new Color().r((byte)18).g((byte)22).b((byte)35).a((byte)250));
        drawRectangleLines(mx, my, mw, mh, GOLD);

        // Barra de Título
        drawRectangle(mx, my, mw, 45, new Color().r((byte)26).g((byte)32).b((byte)50).a((byte)255));
        drawLine(mx, my + 45, mx + mw, my + 45, GOLD);
        drawLegibleText("🔌 GESTIÓN DE CONEXIONES JETTRASTORE (TIEMPO REAL)", mx + 20, my + 14, 18, GOLD);

        ConnectionProfile activeProfile = (policeMonitor != null) ? policeMonitor.getCurrentProfile() : null;
        String activeBadge = (activeProfile != null) ? "[ACTIVA: " + activeProfile.getName() + "]" : "[DESCONECTADO]";
        drawLegibleText(activeBadge, mx + mw - measureLegibleText(activeBadge, 13) - 20, my + 16, 13, LIME);

        // Divisor vertical
        int col1W = 340;
        int col2X = mx + col1W + 25;
        int col2W = mw - col1W - 45;
        drawLine(mx + col1W + 10, my + 55, mx + col1W + 10, my + mh - 55, fade(GRAY, 0.4f));

        // --- COLUMNA IZQUIERDA: LISTA DE CONEXIONES REGISTRADAS ---
        drawLegibleText("LISTA DE CONEXIONES REGISTRADAS", mx + 20, my + 55, 13, SKYBLUE);
        drawLegibleText("Seleccione una conexión para editar o conectar:", mx + 20, my + 72, 10, LIGHTGRAY);

        List<ConnectionProfile> profiles = (policeMonitor != null) ? policeMonitor.getConnectionManager().getProfiles() : List.of();
        int listY = my + 92;
        int cardH = 68;

        for (int i = 0; i < Math.min(5, profiles.size()); i++) {
            ConnectionProfile p = profiles.get(i);
            boolean isSelected = p.getId().equals(selectedProfileId);
            boolean isCurrentActive = (activeProfile != null && p.getId().equals(activeProfile.getId()));

            Rectangle cardRec = new Rectangle().x(mx + 20).y(listY + (i * (cardH + 6))).width(col1W - 20).height(cardH);
            Vector2 mouse = getMousePosition();
            boolean hovered = checkCollisionPointRec(mouse, cardRec);

            Color cardBg = isSelected ? new Color().r((byte)35).g((byte)55).b((byte)90).a((byte)240)
                                      : (hovered ? new Color().r((byte)28).g((byte)36).b((byte)58).a((byte)220)
                                                 : new Color().r((byte)22).g((byte)28).b((byte)45).a((byte)200));

            drawRectangleRounded(cardRec, 0.15f, 6, cardBg);
            drawRectangleRoundedLines(cardRec, 0.15f, 6, isSelected ? GOLD : (hovered ? SKYBLUE : fade(GRAY, 0.4f)));

            // Nombre y URL
            drawLegibleText(p.getName(), (int)cardRec.x() + 10, (int)cardRec.y() + 8, 14, isSelected ? GOLD : RAYWHITE);
            drawLegibleText("URL: " + p.getUrl(), (int)cardRec.x() + 10, (int)cardRec.y() + 27, 11, SKYBLUE);
            drawLegibleText("User: " + p.getUsername(), (int)cardRec.x() + 10, (int)cardRec.y() + 45, 10, LIGHTGRAY);

            // Badges
            int badgeX = (int)(cardRec.x() + cardRec.width() - 95);
            if (p.isDefault()) {
                drawRectangle(badgeX, (int)cardRec.y() + 6, 85, 16, fade(GOLD, 0.25f));
                drawRectangleLines(badgeX, (int)cardRec.y() + 6, 85, 16, GOLD);
                drawLegibleText("★ DEFAULT", badgeX + 8, (int)cardRec.y() + 8, 10, GOLD);
            }
            if (isCurrentActive) {
                int activeY = (int)cardRec.y() + (p.isDefault() ? 26 : 6);
                drawRectangle(badgeX, activeY, 85, 16, fade(LIME, 0.25f));
                drawRectangleLines(badgeX, activeY, 85, 16, LIME);
                drawLegibleText("● EN LÍNEA", badgeX + 8, activeY + 2, 10, LIME);
            }

            // Clic para seleccionar
            if (hovered && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                selectedProfileId = p.getId();
                formConnId = p.getId();
                formConnName = p.getName();
                formConnUrl = p.getUrl();
                formConnUsername = p.getUsername();
                formConnPassword = p.getPassword();
                formConnIsDefault = p.isDefault();
                activeConnField = 0;
            }
        }

        // Botón Nueva Conexión
        if (guiButton(mx + 20, my + mh - 50, col1W - 20, 32, "+ NUEVA CONEXIÓN", new Color().r((byte)30).g((byte)120).b((byte)80).a((byte)255))) {
            formConnId = "";
            formConnName = "Nuevo Clúster JettraStore";
            formConnUrl = "tcp://127.0.0.1:8765";
            formConnUsername = "admin";
            formConnPassword = "";
            formConnIsDefault = profiles.isEmpty();
            selectedProfileId = "";
            activeConnField = 1;
            connStatusFeedback = "Formulario listo para nueva conexión.";
            connStatusFeedbackTimer = 3.0f;
        }

        // --- COLUMNA DERECHA: FORMULARIO DE DETALLES Y EDICIÓN ---
        drawLegibleText("DATOS DE LA CONEXIÓN (URL, CREDENCIALES)", col2X, my + 55, 13, GOLD);
        drawLegibleText("Haga clic en un campo o use TAB para editar:", col2X, my + 72, 10, LIGHTGRAY);

        int formY = my + 92;
        int inputH = 30;

        // Campo 1: Nombre
        drawLegibleText("Nombre Descriptivo:", col2X, formY, 11, RAYWHITE);
        drawInteractiveInput(col2X, formY + 16, col2W, inputH, formConnName, 1, false);

        // Campo 2: URL
        formY += 56;
        drawLegibleText("URL de la Base de Datos (ej. tcp://127.0.0.1:8765 o jettra://localhost:9091):", col2X, formY, 11, RAYWHITE);
        drawInteractiveInput(col2X, formY + 16, col2W, inputH, formConnUrl, 2, false);

        // Campo 3: Username
        formY += 56;
        drawLegibleText("Usuario (Username):", col2X, formY, 11, RAYWHITE);
        drawInteractiveInput(col2X, formY + 16, col2W, inputH, formConnUsername, 3, false);

        // Campo 4: Password
        formY += 56;
        drawLegibleText("Contraseña (Password):", col2X, formY, 11, RAYWHITE);
        drawInteractiveInput(col2X, formY + 16, col2W, inputH, formConnPassword, 4, true);

        // Checkbox: Default
        formY += 56;
        Rectangle chkRec = new Rectangle().x(col2X).y(formY).width(20).height(20);
        if (checkCollisionPointRec(getMousePosition(), chkRec) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formConnIsDefault = !formConnIsDefault;
        }
        drawRectangleRounded(chkRec, 0.2f, 4, formConnIsDefault ? GOLD : DARKGRAY);
        drawRectangleRoundedLines(chkRec, 0.2f, 4, WHITE);
        if (formConnIsDefault) {
            drawLegibleText("✓", col2X + 4, formY + 2, 14, BLACK);
        }
        drawLegibleText("Usar como conexión predeterminada al iniciar la aplicación", col2X + 28, formY + 3, 11, formConnIsDefault ? GOLD : RAYWHITE);

        // Botones de acción del formulario
        int btnY = my + mh - 50;

        // Botón 1: Guardar
        if (guiButton(col2X, btnY, 95, 32, "💾 GUARDAR", LIME)) {
            if (policeMonitor != null) {
                String idToSave = (formConnId != null && !formConnId.isEmpty()) ? formConnId : "conn_" + System.currentTimeMillis();
                ConnectionProfile toSave = new ConnectionProfile(
                    idToSave, formConnName, formConnUrl, formConnUsername, formConnPassword, formConnIsDefault
                );
                policeMonitor.getConnectionManager().saveOrUpdate(toSave);
                selectedProfileId = idToSave;
                formConnId = idToSave;
                connStatusFeedback = "¡Conexión '" + formConnName + "' guardada!";
                connStatusFeedbackTimer = 4.0f;
                triggerWorldEvent("Perfil de conexión guardado: " + formConnName, 50, 255, 100);
            }
        }

        // Botón 2: Conectar Ahora (Cambiar conexión activa en tiempo real)
        if (guiButton(col2X + 105, btnY, 115, 32, "⚡ CONECTAR", SKYBLUE)) {
            if (policeMonitor != null) {
                String idToSave = (formConnId != null && !formConnId.isEmpty()) ? formConnId : "conn_" + System.currentTimeMillis();
                ConnectionProfile toConn = new ConnectionProfile(
                    idToSave, formConnName, formConnUrl, formConnUsername, formConnPassword, formConnIsDefault
                );
                policeMonitor.getConnectionManager().saveOrUpdate(toConn);
                policeMonitor.switchConnection(toConn);
                selectedProfileId = idToSave;
                formConnId = idToSave;
                resetWorldWithJettraStore();
                connStatusFeedback = "¡Conectado a " + toConn.getName() + "!";
                connStatusFeedbackTimer = 4.0f;
                triggerWorldEvent("Conexión conmutada en tiempo real a " + toConn.getName() + " (" + toConn.getUrl() + ")", 0, 220, 255);
            }
        }

        // Botón 3: Predeterminada
        if (guiButton(col2X + 230, btnY, 120, 32, "★ DEFAULT", GOLD)) {
            if (policeMonitor != null && formConnId != null && !formConnId.isEmpty()) {
                policeMonitor.getConnectionManager().setDefault(formConnId);
                formConnIsDefault = true;
                connStatusFeedback = "Marcada como predeterminada.";
                connStatusFeedbackTimer = 4.0f;
                triggerWorldEvent("Conexión predeterminada establecida: " + formConnName, 255, 215, 0);
            }
        }

        // Botón 4: Eliminar
        if (guiButton(col2X + 360, btnY, 85, 32, "🗑️ BORRAR", RED)) {
            if (policeMonitor != null && formConnId != null && !formConnId.isEmpty()) {
                policeMonitor.getConnectionManager().delete(formConnId);
                loadSelectedProfileIntoForm();
                connStatusFeedback = "Conexión eliminada.";
                connStatusFeedbackTimer = 4.0f;
                triggerWorldEvent("Conexión eliminada del registro.", 255, 100, 100);
            }
        }

        // Botón Cerrar (Esquina superior derecha)
        Rectangle closeBtnRec = new Rectangle().x(mx + mw - 38).y(my + 10).width(26).height(26);
        boolean closeHover = checkCollisionPointRec(getMousePosition(), closeBtnRec);
        drawRectangleRounded(closeBtnRec, 0.2f, 4, closeHover ? RED : DARKGRAY);
        drawLegibleText("X", (int)closeBtnRec.x() + 8, (int)closeBtnRec.y() + 5, 14, RAYWHITE);
        if (closeHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showConnectionModal = false;
        }

        // Mensaje de Feedback
        if (connStatusFeedbackTimer > 0) {
            connStatusFeedbackTimer -= getFrameTime();
            drawRectangle(col2X, my + mh - 90, col2W, 26, new Color().r((byte)20).g((byte)45).b((byte)30).a((byte)220));
            drawRectangleLines(col2X, my + mh - 90, col2W, 26, LIME);
            drawLegibleText("✔ " + connStatusFeedback, col2X + 10, my + mh - 84, 11, LIME);
        }
    }

    // =========================================================================
    // IMPLEMENTACIÓN: GESTIÓN DE USUARIOS Y ROLES (USER MANAGEMENT)
    // =========================================================================
    private void initUserFormWithSelection() {
        UserManager um = UserManager.getInstance();
        java.util.Optional<JettraUser> opt = um.findById(selectedUserListId);
        if (opt.isEmpty() && !um.getUsers().isEmpty()) {
            opt = java.util.Optional.of(um.getUsers().get(0));
        }
        if (opt.isPresent()) {
            JettraUser u = opt.get();
            formUserId = u.getId();
            formUsername = u.getUsername();
            formPassword = u.getPassword();
            formFullName = u.getDescription();
            formEmail = u.getUsername() + "@jettra.io";
            formGlobalRole = u.getGlobalRole();
            selectedUserListId = u.getId();
            formDbRoles.clear();
            for (String db : List.of("example_factura_db", "samples_hostipal_db", "samples_ambiental_db", "system_metadata_db")) {
                formDbRoles.put(db, u.getRoleForDatabase(db));
            }
        }
    }

    private void drawUserManagerModal() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();

        // Fondo oscurecido
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.70f));

        int mw = 880;
        int mh = 550;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;

        drawRectangle(mx, my, mw, mh, new Color().r((byte)18).g((byte)22).b((byte)35).a((byte)250));
        drawRectangleLines(mx, my, mw, mh, GOLD);

        // Barra de Título
        drawRectangle(mx, my, mw, 45, new Color().r((byte)26).g((byte)32).b((byte)50).a((byte)255));
        drawLine(mx, my + 45, mx + mw, my + 45, GOLD);
        drawLegibleText("👥 GESTIÓN DE USUARIOS Y ROLES MULTI-BASE DE DATOS (JETTRASTORE)", mx + 20, my + 14, 16, GOLD);

        // Botón Cerrar (X)
        Rectangle closeBtnRec = new Rectangle().x(mx + mw - 38).y(my + 10).width(26).height(26);
        boolean closeHover = checkCollisionPointRec(getMousePosition(), closeBtnRec);
        drawRectangleRounded(closeBtnRec, 0.2f, 4, closeHover ? RED : DARKGRAY);
        drawLegibleText("X", (int)closeBtnRec.x() + 8, (int)closeBtnRec.y() + 5, 14, RAYWHITE);
        if (closeHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showUserManagerModal = false;
            return;
        }

        int col1W = 310;
        int col2X = mx + col1W + 25;
        int col2W = mw - col1W - 45;
        drawLine(mx + col1W + 10, my + 55, mx + col1W + 10, my + mh - 55, fade(GRAY, 0.4f));

        // --- COLUMNA IZQUIERDA: LISTA DE USUARIOS ---
        drawLegibleText("USUARIOS JETTRASTORE", mx + 20, my + 55, 13, SKYBLUE);
        drawLegibleText("Seleccione un usuario para editar permisos:", mx + 20, my + 72, 10, LIGHTGRAY);

        List<JettraUser> users = UserManager.getInstance().getUsers();
        int listY = my + 92;
        int cardH = 58;

        for (int i = 0; i < Math.min(6, users.size()); i++) {
            JettraUser u = users.get(i);
            boolean isSelected = u.getId().equals(selectedUserListId);
            Rectangle cardRec = new Rectangle().x(mx + 20).y(listY + (i * (cardH + 6))).width(col1W - 20).height(cardH);
            Vector2 mouse = getMousePosition();
            boolean hovered = checkCollisionPointRec(mouse, cardRec);

            Color cardBg = isSelected ? new Color().r((byte)35).g((byte)55).b((byte)90).a((byte)240)
                                      : (hovered ? new Color().r((byte)28).g((byte)36).b((byte)58).a((byte)220)
                                                 : new Color().r((byte)22).g((byte)28).b((byte)45).a((byte)200));

            drawRectangleRounded(cardRec, 0.15f, 6, cardBg);
            drawRectangleRoundedLines(cardRec, 0.15f, 6, isSelected ? GOLD : (hovered ? SKYBLUE : fade(GRAY, 0.4f)));

            drawLegibleText(u.getUsername(), (int)cardRec.x() + 10, (int)cardRec.y() + 6, 13, isSelected ? GOLD : RAYWHITE);
            drawLegibleText(u.getDescription(), (int)cardRec.x() + 10, (int)cardRec.y() + 24, 10, LIGHTGRAY);

            // Badge Rol Global
            int badgeX = (int)(cardRec.x() + cardRec.width() - 88);
            drawRectangle(badgeX, (int)cardRec.y() + 6, 80, 16, fade(GOLD, 0.25f));
            drawRectangleLines(badgeX, (int)cardRec.y() + 6, 80, 16, GOLD);
            drawLegibleText(u.getGlobalRole().name(), badgeX + 4, (int)cardRec.y() + 8, 9, GOLD);

            // Cantidad de DBs con acceso
            long allowedDbs = u.getDatabasePermissions().stream().filter(p -> p.getRole() != JettraDatabaseRole.NONE).count();
            drawLegibleText("Acceso a " + allowedDbs + " bases de datos", (int)cardRec.x() + 10, (int)cardRec.y() + 40, 10, SKYBLUE);

            if (hovered && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                selectedUserListId = u.getId();
                initUserFormWithSelection();
                activeUserField = 0;
            }
        }

        // Botón Crear Nuevo Usuario
        if (guiButton(mx + 20, my + mh - 50, col1W - 20, 32, "+ NUEVO USUARIO", new Color().r((byte)30).g((byte)120).b((byte)80).a((byte)255))) {
            formUserId = "usr_" + System.currentTimeMillis();
            formUsername = "nuevo_usuario";
            formPassword = "password123";
            formFullName = "Nuevo Usuario Multimodelo";
            formEmail = "usuario@jettra.io";
            formGlobalRole = JettraUser.GlobalRole.DEVELOPER;
            selectedUserListId = "";
            formDbRoles.clear();
            formDbRoles.put("example_factura_db", JettraDatabaseRole.READ_WRITE);
            formDbRoles.put("samples_hostipal_db", JettraDatabaseRole.READ_ONLY);
            formDbRoles.put("samples_ambiental_db", JettraDatabaseRole.NONE);
            formDbRoles.put("system_metadata_db", JettraDatabaseRole.NONE);
            activeUserField = 1;
            userStatusFeedback = "Formulario listo para nuevo usuario.";
            userStatusFeedbackTimer = 3.0f;
        }

        // --- COLUMNA DERECHA: FORMULARIO Y ROLES POR BASE DE DATOS ---
        drawLegibleText("FICHA DE USUARIO & ROLES ASIGNADOS", col2X, my + 55, 13, GOLD);

        int fy = my + 80;
        int inputH = 26;

        // Fila 1: Username & Password
        drawLegibleText("Username:", col2X, fy, 10, RAYWHITE);
        drawInteractiveInputUser(col2X, fy + 14, 210, inputH, formUsername, 1, false);

        drawLegibleText("Password:", col2X + 225, fy, 10, RAYWHITE);
        drawInteractiveInputUser(col2X + 225, fy + 14, 210, inputH, formPassword, 2, true);

        // Fila 2: Nombre Completo y Email
        fy += 46;
        drawLegibleText("Nombre Completo / Descripción:", col2X, fy, 10, RAYWHITE);
        drawInteractiveInputUser(col2X, fy + 14, 210, inputH, formFullName, 3, false);

        drawLegibleText("Correo Electrónico:", col2X + 225, fy, 10, RAYWHITE);
        drawInteractiveInputUser(col2X + 225, fy + 14, 210, inputH, formEmail, 4, false);

        // Fila 3: Selector de Rol Global
        fy += 46;
        drawLegibleText("Rol Global JettraStore:", col2X, fy, 10, RAYWHITE);
        fy += 15;
        JettraUser.GlobalRole[] roles = JettraUser.GlobalRole.values();
        int rBtnW = col2W / roles.length;
        for (int ri = 0; ri < roles.length; ri++) {
            JettraUser.GlobalRole gr = roles[ri];
            boolean isCurRole = (formGlobalRole == gr);
            Rectangle rRec = new Rectangle().x(col2X + ri * rBtnW).y(fy).width(rBtnW - 4).height(24);
            boolean rHov = checkCollisionPointRec(getMousePosition(), rRec);
            drawRectangleRounded(rRec, 0.2f, 4, isCurRole ? fade(GOLD, 0.8f) : (rHov ? fade(SKYBLUE, 0.6f) : fade(DARKGRAY, 0.5f)));
            drawRectangleRoundedLines(rRec, 0.2f, 4, isCurRole ? WHITE : fade(WHITE, 0.3f));
            drawLegibleText(gr.name(), (int)rRec.x() + 4, (int)rRec.y() + 5, 9, isCurRole ? BLACK : RAYWHITE);
            if (rHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                formGlobalRole = gr;
            }
        }

        // Fila 4: Matriz de Roles Asignados a Bases de Datos
        fy += 34;
        drawLegibleText("PERMISOS Y ROLES POR BASE DE DATOS:", col2X, fy, 11, GOLD);
        drawLegibleText("(Haga clic en el botón de rol para alternar: NONE -> READ_ONLY -> READ_WRITE -> ADMIN)", col2X, fy + 15, 9, LIGHTGRAY);
        fy += 30;

        List<String> dbs = List.of("example_factura_db", "samples_hostipal_db", "samples_ambiental_db", "system_metadata_db");
        for (String db : dbs) {
            JettraDatabaseRole curRole = formDbRoles.getOrDefault(db, JettraDatabaseRole.NONE);

            Rectangle rowRec = new Rectangle().x(col2X).y(fy).width(col2W).height(26);
            drawRectangle(col2X, fy, col2W, 26, fade(BLACK, 0.3f));
            drawRectangleLines(col2X, fy, col2W, 26, fade(DARKBLUE, 0.5f));

            drawLegibleText("🗄️ " + db, col2X + 8, fy + 6, 11, RAYWHITE);

            // Botón interactivo de rol
            int rBoxW = 140;
            int rBoxX = col2X + col2W - rBoxW - 6;
            Rectangle rBoxRec = new Rectangle().x(rBoxX).y(fy + 2).width(rBoxW).height(22);
            boolean rBoxHov = checkCollisionPointRec(getMousePosition(), rBoxRec);

            Color roleCol = switch (curRole) {
                case ADMIN -> GOLD;
                case READ_WRITE -> LIME;
                case READ_ONLY -> SKYBLUE;
                case NONE -> GRAY;
            };

            drawRectangleRounded(rBoxRec, 0.2f, 4, fade(roleCol, rBoxHov ? 0.4f : 0.2f));
            drawRectangleRoundedLines(rBoxRec, 0.2f, 4, roleCol);
            drawLegibleText(curRole.name(), rBoxX + 12, fy + 5, 10, roleCol);

            if (rBoxHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                JettraDatabaseRole nextRole = switch (curRole) {
                    case NONE -> JettraDatabaseRole.READ_ONLY;
                    case READ_ONLY -> JettraDatabaseRole.READ_WRITE;
                    case READ_WRITE -> JettraDatabaseRole.ADMIN;
                    case ADMIN -> JettraDatabaseRole.NONE;
                };
                formDbRoles.put(db, nextRole);
            }

            fy += 30;
        }

        // Botones de Acción (Guardar, Eliminar)
        int btnY = my + mh - 50;

        if (guiButton(col2X, btnY, 150, 32, "💾 GUARDAR USUARIO", LIME)) {
            UserManager um = UserManager.getInstance();
            if (formUsername.trim().isEmpty()) {
                userStatusFeedback = "El nombre de usuario no puede estar vacío.";
                userStatusFeedbackTimer = 3.0f;
            } else {
                java.util.Optional<JettraUser> existingOpt = um.findById(formUserId);
                JettraUser userToSave;
                if (existingOpt.isPresent()) {
                    userToSave = existingOpt.get();
                    userToSave.setUsername(formUsername);
                    userToSave.setPassword(formPassword);
                    userToSave.setDescription(formFullName);
                    userToSave.setGlobalRole(formGlobalRole);
                } else {
                    userToSave = new JettraUser(formUsername, formPassword, formFullName, formGlobalRole);
                    if (formUserId != null && !formUserId.isEmpty()) {
                        userToSave.setId(formUserId);
                    }
                }
                for (Map.Entry<String, JettraDatabaseRole> entry : formDbRoles.entrySet()) {
                    userToSave.setRoleForDatabase(entry.getKey(), entry.getValue());
                }
                um.saveOrUpdate(userToSave);
                selectedUserListId = userToSave.getId();
                formUserId = userToSave.getId();
                userStatusFeedback = "¡Usuario '" + formUsername + "' guardado correctamente!";
                userStatusFeedbackTimer = 4.0f;
                triggerWorldEvent("Seguridad JettraStore: Usuario [" + formUsername + "] actualizado con rol " + formGlobalRole.name(), 50, 255, 100);
            }
        }

        if (guiButton(col2X + 160, btnY, 140, 32, "🗑️ ELIMINAR", RED)) {
            UserManager um = UserManager.getInstance();
            if ("usr_001".equals(formUserId) || "admin".equalsIgnoreCase(formUsername)) {
                userStatusFeedback = "No se puede eliminar el usuario administrador raíz.";
                userStatusFeedbackTimer = 4.0f;
            } else if (formUsername != null && !formUsername.isEmpty()) {
                um.delete(formUsername);
                userStatusFeedback = "Usuario eliminado.";
                userStatusFeedbackTimer = 3.0f;
                initUserFormWithSelection();
                triggerWorldEvent("Seguridad: Usuario eliminado de JettraStore.", 255, 100, 100);
            }
        }

        if (guiButton(col2X + 310, btnY, 130, 32, "CERRAR [ESC]", DARKGRAY)) {
            showUserManagerModal = false;
        }

        // Mensaje de feedback
        if (userStatusFeedbackTimer > 0) {
            userStatusFeedbackTimer -= getFrameTime();
            drawRectangle(col2X, my + mh - 86, col2W, 24, new Color().r((byte)20).g((byte)45).b((byte)30).a((byte)220));
            drawRectangleLines(col2X, my + mh - 86, col2W, 24, LIME);
            drawLegibleText("✔ " + userStatusFeedback, col2X + 10, my + mh - 81, 11, LIME);
        }
    }

    private void drawInteractiveInputUser(int x, int y, int w, int h, String value, int fieldId, boolean isPassword) {
        Rectangle rec = new Rectangle().x(x).y(y).width(w).height(h);
        Vector2 mouse = getMousePosition();
        boolean hovered = checkCollisionPointRec(mouse, rec);
        boolean isActive = (activeUserField == fieldId);

        if (hovered && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            activeUserField = fieldId;
        }

        Color bg = isActive ? new Color().r((byte)30).g((byte)45).b((byte)75).a((byte)255)
                            : (hovered ? new Color().r((byte)25).g((byte)32).b((byte)52).a((byte)255)
                                       : new Color().r((byte)15).g((byte)20).b((byte)35).a((byte)255));

        drawRectangleRounded(rec, 0.15f, 4, bg);
        drawRectangleRoundedLines(rec, 0.15f, 4, isActive ? GOLD : (hovered ? SKYBLUE : DARKGRAY));

        String display = isPassword ? "•".repeat(value.length()) : value;
        if (isActive && ((int)(worldTime * 2) % 2 == 0)) {
            display += "_";
        }
        drawLegibleText(display, x + 6, y + 6, 11, RAYWHITE);
    }

    // =========================================================================
    // IMPLEMENTACIÓN: EXPLORADOR MULTIMODELO POR ENGINE (ENGINE EXPLORER)
    // =========================================================================
    private void syncDynamicEntitiesWithPoliceMonitor() {
        if (policeMonitor == null) return;

        // 1. Sincronizar Personas (Usuarios conectados en tiempo real procesando objetos)
        Set<String> activeSessionNames = new HashSet<>();
        for (JettraLiveSession session : policeMonitor.getLiveSessions()) {
            activeSessionNames.add(session.getUsername());
            HumanEntity person = findEntityByName(session.getUsername());
            if (person == null) {
                UserZoneGroup zone = policeMonitor.getZoneById(session.getZoneId());
                ServerNode3D node = policeMonitor.getNodeById(session.getTargetNodeId());
                HumanEntity u = new HumanEntity();
                u.name = session.getUsername();
                u.connectedDatabase = session.getDatabase();
                u.connectionFacility = (zone != null) ? zone.getBuildingName() : "Sede Central";
                u.targetServer = session.getTargetNodeId();
                u.job = "Usuario Conectado (" + session.getClientIp() + ")";
                u.action = "WALKING";
                if (zone != null) {
                    u.r = zone.getColorR(); u.g = zone.getColorG(); u.b = zone.getColorB();
                    u.x = zone.getBuildingX() + (float)(Math.random() * 2 - 1);
                    u.y = 0;
                    u.z = zone.getBuildingZ() + (float)(Math.random() * 2 - 1);
                }
                if (node != null) {
                    u.targetX = node.getX();
                    u.targetZ = node.getZ();
                }
                u.currentThought = session.getCurrentOperation();
                u.thoughtTimer = 5.0f;
                entities.add(u);
            }
        }
        entities.removeIf(e -> !e.isWolf && !e.isCar && !e.isPoliceOfficer && e.job != null && e.job.startsWith("Usuario Conectado") && !activeSessionNames.contains(e.name));

        // 2. Sincronizar Perros (Agentes Caninos JettraPolice reactivos)
        Set<String> activeAgentNames = new HashSet<>();
        for (JettraPoliceAgent agent : policeMonitor.getActivePoliceAgents()) {
            activeAgentNames.add(agent.getName());
            HumanEntity dog = findEntityByName(agent.getName());
            if (dog == null) {
                HumanEntity k9 = new HumanEntity();
                k9.name = agent.getName();
                k9.isWolf = true;
                k9.isPoliceK9 = true;
                if (agent.getName().contains("Alpha")) k9.isJettraMascot = true;
                k9.action = "PATROLLING";
                ServerNode3D node = policeMonitor.getNodeById(agent.getTargetNodeId());
                float nx = (node != null) ? node.getX() : 0f;
                float nz = (node != null) ? node.getZ() : -8f;
                k9.x = nx + 3.0f; k9.y = 0; k9.z = nz;
                k9.targetX = nx; k9.targetZ = nz;
                k9.r = 30; k9.g = 144; k9.b = 255;
                k9.currentThought = agent.getCurrentMission();
                k9.thoughtTimer = 6.0f;
                entities.add(k9);
            }
        }
        entities.removeIf(e -> e.isPoliceK9 && !activeAgentNames.contains(e.name));

        // 3. Sincronizar Camiones (Tráfico y lotes de datos entre nodos del clúster)
        Set<String> activeTrafficNames = new HashSet<>();
        for (ClusterDataTraffic traffic : policeMonitor.getActiveTraffic()) {
            activeTrafficNames.add(traffic.getName());
            HumanEntity truck = findEntityByName(traffic.getName());
            if (truck == null) {
                HumanEntity tr = new HumanEntity();
                tr.name = traffic.getName();
                tr.isCar = true;
                tr.action = "DRIVING";
                ServerNode3D src = policeMonitor.getNodeById(traffic.getSourceNodeId());
                ServerNode3D tgt = policeMonitor.getNodeById(traffic.getTargetNodeId());
                float sx = (src != null) ? src.getX() : 0f;
                float sz = (src != null) ? src.getZ() : -8f;
                float tx = (tgt != null) ? tgt.getX() : 14f;
                float tz = (tgt != null) ? tgt.getZ() : -8f;
                tr.x = sx; tr.y = 0; tr.z = sz;
                tr.targetX = tx; tr.targetZ = tz;
                tr.dataPayload = traffic.getPayloadSummary();
                tr.connectedDatabase = traffic.getSourceNodeId() + " -> " + traffic.getTargetNodeId();
                tr.r = 255; tr.g = 180; tr.b = 40;
                tr.currentThought = "🚚 " + traffic.getPayloadSummary();
                tr.thoughtTimer = 5.0f;
                entities.add(tr);
            }
        }
        entities.removeIf(e -> e.isCar && !activeTrafficNames.contains(e.name));
    }


    private void drawSimpleInputField(int x, int y, int w, int h, String value, boolean isActive, boolean isPassword) {
        Rectangle rec = new Rectangle().x(x).y(y).width(w).height(h);
        Vector2 mouse = getMousePosition();
        boolean hovered = checkCollisionPointRec(mouse, rec);

        Color bg = isActive ? new Color().r((byte)30).g((byte)45).b((byte)75).a((byte)255)
                            : (hovered ? new Color().r((byte)22).g((byte)28).b((byte)48).a((byte)255)
                                       : new Color().r((byte)15).g((byte)20).b((byte)35).a((byte)255));

        drawRectangleRounded(rec, 0.15f, 4, bg);
        drawRectangleRoundedLines(rec, 0.15f, 4, isActive ? GOLD : (hovered ? SKYBLUE : DARKGRAY));

        String display = (value != null) ? value : "";
        if (isActive && ((int)(worldTime * 2) % 2 == 0)) {
            display += "_";
        }
        drawLegibleText(display, x + 6, y + 6, 11, RAYWHITE);
    }

    private void openEngineExplorerModal(String dbId) {
        // En el panel Explorador multimodelo solo debe mostrar la base de datos seleccionada
        if (selectedDatabase != null) {
            selectedExplorerDb = selectedDatabase.getId();
        } else if (dbId != null && !dbId.isBlank()) {
            selectedExplorerDb = dbId;
        } else {
            selectedExplorerDb = "example_factura_db";
        }
        selectedEngineType = "DOCUMENT";
        showRecordViewModal = false;
        explorerActiveTab = 0;
        explorerFilterText = "";
        explorerSearchQuery = "";
        explorerHasActiveQuery = false;
        explorerFilteredRecords.clear();
        explorerActiveInputFocus = 0;
        showRecordOperationModal = false;
        showVersionHistoryModal = false;
        showIndexOpModal = false;
        showExplorerQueryHelp = false;

        List<EngineBucket> buckets = EngineDataCatalog.getInstance().getBucketsForDatabase(selectedExplorerDb);
        if (!buckets.isEmpty()) {
            selectedBucketName = buckets.get(0).getBucketName();
            selectedEngineType = buckets.get(0).getEngineType();
            List<EngineRecord> recs = EngineDataCatalog.getInstance().getPaginatedRecords(selectedExplorerDb, selectedEngineType, selectedBucketName, 0, explorerPageSize);
            selectedExplorerRecord = recs.isEmpty() ? null : recs.get(0);
        }
        explorerPageIndex = 0;
        showEngineExplorerModal = true;
    }

    private void executeExplorerQuery() {
        if (explorerSearchQuery == null || explorerSearchQuery.trim().isEmpty()) {
            explorerHasActiveQuery = false;
            explorerFilteredRecords.clear();
            explorerQueryFeedback = "Filtro limpiado. Mostrando todos los registros del bucket.";
            explorerQueryFeedbackTimer = 3.0f;
            return;
        }

        EngineDataCatalog.QueryResult res = EngineDataCatalog.getInstance().executeQuery(
            selectedExplorerDb, selectedEngineType, selectedBucketName, explorerSearchQuery, explorerQueryIsSql
        );
        explorerFilteredRecords = new ArrayList<>(res.records());
        explorerHasActiveQuery = true;
        explorerPageIndex = 0;
        if (!explorerFilteredRecords.isEmpty()) {
            selectedExplorerRecord = explorerFilteredRecords.get(0);
        }
        explorerQueryFeedback = res.summaryMessage();
        explorerQueryFeedbackTimer = 5.0f;
    }

    private void drawEngineExplorerModal() {
        int sw = getScreenWidth();
        int sh = getScreenHeight();

        // Fondo oscuro semitransparente
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.78f));

        // Dimensiones con soporte de Zoom (Maximizar / Restaurar)
        int mw = explorerMaximized ? (sw - 30) : 1060;
        int mh = explorerMaximized ? (sh - 30) : 640;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;
        explorerPageSize = explorerMaximized ? 8 : 5;

        // Marco del panel
        drawRectangle(mx, my, mw, mh, new Color().r((byte)16).g((byte)20).b((byte)32).a((byte)252));
        drawRectangleLines(mx, my, mw, mh, GOLD);

        // Barra de Título (Header)
        drawRectangle(mx, my, mw, 45, new Color().r((byte)24).g((byte)30).b((byte)48).a((byte)255));
        drawLine(mx, my + 45, mx + mw, my + 45, GOLD);

        drawLegibleText("🌳 EXPLORADOR MULTIMODELO DE OBJETOS POR ENGINE", mx + 16, my + 10, 14, GOLD);
        // Exclusivamente la base de datos seleccionada
        drawLegibleText("🗄️ BASE DE DATOS SELECCIONADA: [" + selectedExplorerDb.toUpperCase() + "]", mx + 16, my + 28, 10, SKYBLUE);

        // Ruta jerárquica: <nombre-base-datos><engine><bucket-contenedor><registro>
        String currentRecId = (selectedExplorerRecord != null) ? selectedExplorerRecord.getId() : "registro";
        String hierarchyPath = "<" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + "><" + currentRecId + ">";
        drawLegibleText("📍 ESQUEMA: " + hierarchyPath, mx + 380, my + 14, 11, LIME);

        // Botón Zoom (Maximizar / Minimizar)
        Rectangle zoomBtnRec = new Rectangle().x(mx + mw - 70).y(my + 10).width(26).height(26);
        boolean zoomHover = checkCollisionPointRec(getMousePosition(), zoomBtnRec);
        drawRectangleRounded(zoomBtnRec, 0.2f, 4, zoomHover ? SKYBLUE : DARKGRAY);
        drawLegibleText(explorerMaximized ? "🗗" : "🗖", (int)zoomBtnRec.x() + 6, (int)zoomBtnRec.y() + 5, 14, RAYWHITE);
        if (zoomHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerMaximized = !explorerMaximized;
        }

        // Botón Cerrar (X)
        Rectangle closeBtnRec = new Rectangle().x(mx + mw - 38).y(my + 10).width(26).height(26);
        boolean closeHover = checkCollisionPointRec(getMousePosition(), closeBtnRec);
        drawRectangleRounded(closeBtnRec, 0.2f, 4, closeHover ? RED : DARKGRAY);
        drawLegibleText("X", (int)closeBtnRec.x() + 8, (int)closeBtnRec.y() + 5, 14, RAYWHITE);
        if (closeHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showEngineExplorerModal = false;
            return;
        }

        // Pestañas Superiores de Vista: [ 📦 REGISTROS Y OBJETOS ] / [ ⚙️ ADMINISTRACIÓN DE ÍNDICES ]
        int tabY = my + 50;
        Rectangle tabRecs = new Rectangle().x(mx + 20).y(tabY).width(260).height(26);
        boolean tabRecsHov = checkCollisionPointRec(getMousePosition(), tabRecs);
        drawRectangleRounded(tabRecs, 0.2f, 4, (explorerActiveTab == 0) ? fade(GOLD, 0.85f) : (tabRecsHov ? fade(SKYBLUE, 0.5f) : fade(DARKGRAY, 0.4f)));
        drawRectangleRoundedLines(tabRecs, 0.2f, 4, (explorerActiveTab == 0) ? WHITE : fade(WHITE, 0.3f));
        drawLegibleText("📦 REGISTROS Y OBJETOS (CRUD)", (int)tabRecs.x() + 14, (int)tabRecs.y() + 6, 11, (explorerActiveTab == 0) ? BLACK : RAYWHITE);
        if (tabRecsHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerActiveTab = 0;
        }

        Rectangle tabIdx = new Rectangle().x(mx + 290).y(tabY).width(280).height(26);
        boolean tabIdxHov = checkCollisionPointRec(getMousePosition(), tabIdx);
        drawRectangleRounded(tabIdx, 0.2f, 4, (explorerActiveTab == 1) ? fade(GOLD, 0.85f) : (tabIdxHov ? fade(SKYBLUE, 0.5f) : fade(DARKGRAY, 0.4f)));
        drawRectangleRoundedLines(tabIdx, 0.2f, 4, (explorerActiveTab == 1) ? WHITE : fade(WHITE, 0.3f));
        drawLegibleText("⚙️ ADMINISTRACIÓN DE ÍNDICES", (int)tabIdx.x() + 14, (int)tabIdx.y() + 6, 11, (explorerActiveTab == 1) ? BLACK : RAYWHITE);
        if (tabIdxHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerActiveTab = 1;
        }

        // Feedback / Notificación de consulta si está activa
        if (explorerQueryFeedbackTimer > 0) {
            explorerQueryFeedbackTimer -= 0.016f;
            drawLegibleText("ℹ️ " + explorerQueryFeedback, mx + 590, tabY + 7, 10, LIME);
        }

        // Layout de Columnas
        int col1W = 270;
        int col2X = mx + col1W + 18;
        int col2W = mw - col1W - 36;
        drawLine(mx + col1W + 8, my + 82, mx + col1W + 8, my + mh - 15, fade(GRAY, 0.4f));

        EngineDataCatalog catalog = EngineDataCatalog.getInstance();

        // =====================================================================
        // COLUMNA IZQUIERDA: Árbol de Motores (Engines) y Buckets
        // =====================================================================
        drawLegibleText("MOTORES DE " + selectedExplorerDb.toUpperCase(), mx + 20, my + 84, 11, SKYBLUE);
        List<String> engines = catalog.getSupportedEngines(selectedExplorerDb);

        int treeY = my + 104;
        for (String et : engines) {
            boolean isExpanded = expandedEngines.contains(et);
            List<EngineBucket> bucketsOfEngine = catalog.getBucketsByEngine(selectedExplorerDb, et);

            Rectangle engRec = new Rectangle().x(mx + 20).y(treeY).width(col1W - 20).height(22);
            boolean engHov = checkCollisionPointRec(getMousePosition(), engRec);
            drawRectangle(mx + 20, treeY, col1W - 20, 22, fade(BLACK, engHov ? 0.5f : 0.25f));
            drawRectangleLines(mx + 20, treeY, col1W - 20, 22, isExpanded ? GOLD : fade(GRAY, 0.4f));

            String icon = isExpanded ? "▼" : "▶";
            drawLegibleText(icon + " [" + et + "] (" + bucketsOfEngine.size() + ")", mx + 26, treeY + 4, 11, isExpanded ? GOLD : RAYWHITE);

            if (engHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                if (isExpanded) expandedEngines.remove(et);
                else expandedEngines.add(et);
            }
            treeY += 24;

            if (isExpanded) {
                for (EngineBucket b : bucketsOfEngine) {
                    boolean isBucketSel = (selectedEngineType.equals(et) && selectedBucketName.equals(b.getBucketName()));
                    Rectangle bRec = new Rectangle().x(mx + 34).y(treeY).width(col1W - 34).height(20);
                    boolean bHov = checkCollisionPointRec(getMousePosition(), bRec);

                    drawRectangle(mx + 34, treeY, col1W - 34, 20, isBucketSel ? fade(BLUE, 0.6f) : (bHov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.15f)));
                    drawLegibleText("  ▸ " + b.getBucketName() + " (" + String.format("%,d", b.getTotalObjects()) + ")", mx + 38, treeY + 3, 10, isBucketSel ? GOLD : SKYBLUE);

                    if (bHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                        selectedEngineType = et;
                        selectedBucketName = b.getBucketName();
                        explorerPageIndex = 0;
                        explorerHasActiveQuery = false;
                        explorerFilteredRecords.clear();
                        List<EngineRecord> recs = catalog.getPaginatedRecords(selectedExplorerDb, selectedEngineType, selectedBucketName, 0, explorerPageSize);
                        selectedExplorerRecord = recs.isEmpty() ? null : recs.get(0);
                    }
                    treeY += 21;
                }
            }
        }

        // =====================================================================
        // COLUMNA DERECHA: Pestaña 0 (REGISTROS Y OBJETOS) o Pestaña 1 (ÍNDICES)
        // =====================================================================
        if (explorerActiveTab == 0) {
            drawExplorerRecordsTab(catalog, col2X, my, col2W, mh);
        } else {
            drawExplorerIndexesTab(catalog, col2X, my, col2W, mh);
        }

        // Sub-modales superpuestos
        if (showRecordViewModal) {
            drawRecordViewModal(sw, sh);
        } else if (showRecordOperationModal) {
            drawRecordOperationModal(sw, sh);
        } else if (showVersionHistoryModal) {
            drawVersionHistoryModal(sw, sh);
        } else if (showIndexOpModal) {
            drawIndexOpModal(sw, sh);
        } else if (showExplorerQueryHelp) {
            drawQueryHelpModal(sw, sh);
        }
    }

    private void drawExplorerRecordsTab(EngineDataCatalog catalog, int col2X, int my, int col2W, int mh) {
        // 1. Barra de Búsqueda y Filtrado (JettraQL & JettraSQL)
        int searchY = my + 82;

        // Selector JettraQL vs JettraSQL
        Rectangle modeRec = new Rectangle().x(col2X).y(searchY).width(90).height(24);
        boolean modeHov = checkCollisionPointRec(getMousePosition(), modeRec);
        drawRectangleRounded(modeRec, 0.2f, 4, explorerQueryIsSql ? fade(GOLD, 0.8f) : fade(BLUE, 0.7f));
        drawLegibleText(explorerQueryIsSql ? "🔍 J-SQL" : "🔍 J-QL", (int)modeRec.x() + 10, (int)modeRec.y() + 5, 11, BLACK);
        if (modeHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerQueryIsSql = !explorerQueryIsSql;
        }

        // Caja de Entrada de Búsqueda Query
        int qBoxW = col2W - 325;
        Rectangle qRec = new Rectangle().x(col2X + 96).y(searchY).width(qBoxW).height(24);
        boolean qHov = checkCollisionPointRec(getMousePosition(), qRec);
        drawRectangleRounded(qRec, 0.15f, 4, (explorerActiveInputFocus == 2) ? new Color().r((byte)20).g((byte)30).b((byte)50).a((byte)255) : fade(BLACK, 0.4f));
        drawRectangleRoundedLines(qRec, 0.15f, 4, (explorerActiveInputFocus == 2) ? GOLD : (qHov ? SKYBLUE : DARKGRAY));
        String qDisp = explorerSearchQuery.isEmpty() ? (explorerQueryIsSql ? "Ej: SELECT * FROM facturas WHERE total > 500" : "Ej: FROM facturas WHERE total > 500") : explorerSearchQuery;
        drawLegibleText(qDisp, (int)qRec.x() + 6, (int)qRec.y() + 5, 10, explorerSearchQuery.isEmpty() ? GRAY : RAYWHITE);
        if (qHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerActiveInputFocus = 2;
        }

        // Botón Ejecutar
        Rectangle execRec = new Rectangle().x(col2X + 96 + qBoxW + 6).y(searchY).width(68).height(24);
        boolean execHov = checkCollisionPointRec(getMousePosition(), execRec);
        drawRectangleRounded(execRec, 0.2f, 4, execHov ? fade(LIME, 0.8f) : fade(GREEN, 0.6f));
        drawLegibleText("BUSCAR", (int)execRec.x() + 10, (int)execRec.y() + 5, 10, BLACK);
        if (execHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            executeExplorerQuery();
        }

        // Botón Limpiar Filtro
        Rectangle clrRec = new Rectangle().x(col2X + 96 + qBoxW + 78).y(searchY).width(64).height(24);
        boolean clrHov = checkCollisionPointRec(getMousePosition(), clrRec);
        drawRectangleRounded(clrRec, 0.2f, 4, clrHov ? fade(RED, 0.8f) : fade(DARKGRAY, 0.6f));
        drawLegibleText("LIMPIAR", (int)clrRec.x() + 8, (int)clrRec.y() + 5, 10, RAYWHITE);
        if (clrHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            explorerSearchQuery = "";
            explorerFilterText = "";
            explorerHasActiveQuery = false;
            explorerFilteredRecords.clear();
        }

        // Botón Ayuda JettraQL / SQL
        Rectangle helpRec = new Rectangle().x(col2X + 96 + qBoxW + 146).y(searchY).width(75).height(24);
        boolean helpHov = checkCollisionPointRec(getMousePosition(), helpRec);
        drawRectangleRounded(helpRec, 0.2f, 4, helpHov ? fade(PURPLE, 0.8f) : fade(DARKPURPLE, 0.6f));
        drawLegibleText("❓ AYUDA", (int)helpRec.x() + 10, (int)helpRec.y() + 5, 10, RAYWHITE);
        if (helpHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showExplorerQueryHelp = true;
        }

        // 2. Barra de Operaciones de Registro: Ver, Agregar, Editar, Eliminar, Restaurar Versiones
        int opsBarY = my + 112;
        int btnW = 90;

        // Botón VER (👁️ VISUALIZAR)
        if (guiButton(col2X, opsBarY, btnW, 24, "👁️ VER", (selectedExplorerRecord != null) ? SKYBLUE : DARKGRAY)) {
            if (selectedExplorerRecord != null) openViewRecordModal();
        }

        // Botón AGREGAR
        if (guiButton(col2X + (btnW + 6), opsBarY, btnW, 24, "➕ AGREGAR", GREEN)) {
            openAddRecordModal();
        }

        // Botón EDITAR
        if (guiButton(col2X + (btnW + 6) * 2, opsBarY, btnW, 24, "✏️ EDITAR", (selectedExplorerRecord != null) ? SKYBLUE : DARKGRAY)) {
            if (selectedExplorerRecord != null) openEditRecordModal();
        }

        // Botón ELIMINAR
        if (guiButton(col2X + (btnW + 6) * 3, opsBarY, btnW, 24, "🗑️ ELIMINAR", (selectedExplorerRecord != null) ? RED : DARKGRAY)) {
            if (selectedExplorerRecord != null) openDeleteRecordModal();
        }

        // Botón RESTAURAR VERSIONES
        if (guiButton(col2X + (btnW + 6) * 4, opsBarY, 130, 24, "⏪ VERSIONES", (selectedExplorerRecord != null) ? GOLD : DARKGRAY)) {
            if (selectedExplorerRecord != null) {
                showVersionHistoryModal = true;
                selectedVersionNumber = selectedExplorerRecord.getCurrentVersion();
            }
        }

        // Filtro rápido de texto en resultados
        int filterX = col2X + (btnW + 6) * 4 + 138;
        int filterW = col2W - (filterX - col2X);
        if (filterW > 80) {
            Rectangle fltRec = new Rectangle().x(filterX).y(opsBarY).width(filterW).height(24);
            boolean fltHov = checkCollisionPointRec(getMousePosition(), fltRec);
            drawRectangleRounded(fltRec, 0.15f, 4, (explorerActiveInputFocus == 1) ? new Color().r((byte)20).g((byte)30).b((byte)50).a((byte)255) : fade(BLACK, 0.4f));
            drawRectangleRoundedLines(fltRec, 0.15f, 4, (explorerActiveInputFocus == 1) ? GOLD : (fltHov ? SKYBLUE : DARKGRAY));
            String fltDisp = explorerFilterText.isEmpty() ? "Filtro rápido..." : explorerFilterText;
            drawLegibleText(fltDisp, (int)fltRec.x() + 6, (int)fltRec.y() + 5, 10, explorerFilterText.isEmpty() ? GRAY : RAYWHITE);
            if (fltHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                explorerActiveInputFocus = 1;
            }
        }

        // 3. Obtención y Paginación de Registros (Paginando hasta 1,000,025 registros)
        EngineBucket currentBucket = catalog.getBucket(selectedExplorerDb, selectedEngineType, selectedBucketName);
        long bucketTotalObjects = (currentBucket != null) ? currentBucket.getTotalObjects() : 0L;

        List<EngineRecord> pageRecords;
        int totalPages;
        long displayTotalRecords;

        if (explorerHasActiveQuery || !explorerFilterText.isEmpty()) {
            List<EngineRecord> sourceList = explorerHasActiveQuery ? explorerFilteredRecords : (currentBucket != null ? currentBucket.getSampleRecords() : Collections.emptyList());
            if (!explorerFilterText.isEmpty()) {
                String lowerFlt = explorerFilterText.toLowerCase();
                sourceList = sourceList.stream()
                    .filter(r -> r.getId().toLowerCase().contains(lowerFlt) || r.getSummary().toLowerCase().contains(lowerFlt) || r.getDetails().toLowerCase().contains(lowerFlt))
                    .toList();
            }
            displayTotalRecords = sourceList.size();
            totalPages = Math.max(1, (int) Math.ceil((double) displayTotalRecords / (double) explorerPageSize));
            int startIdx = Math.max(0, explorerPageIndex * explorerPageSize);
            int endIdx = Math.min((int)displayTotalRecords, startIdx + explorerPageSize);
            pageRecords = (startIdx < displayTotalRecords) ? sourceList.subList(startIdx, endIdx) : Collections.emptyList();
        } else {
            displayTotalRecords = bucketTotalObjects;
            totalPages = Math.max(1, (int) Math.ceil((double) bucketTotalObjects / (double) explorerPageSize));
            pageRecords = catalog.getRecordsForPage(selectedExplorerDb, selectedEngineType, selectedBucketName, explorerPageIndex, explorerPageSize);
        }

        // Barra de Paginación Rápida Multirango
        int pageBarY = my + 142;
        int pbx = col2X;

        // Botón Inicio |◄
        if (guiButton(pbx, pageBarY, 32, 22, "|◄", (explorerPageIndex > 0) ? SKYBLUE : DARKGRAY)) {
            explorerPageIndex = 0;
            if (!pageRecords.isEmpty()) selectedExplorerRecord = pageRecords.get(0);
        }
        pbx += 36;

        // Botón Anterior ◄
        if (guiButton(pbx, pageBarY, 74, 22, "◄ ANT", (explorerPageIndex > 0) ? SKYBLUE : DARKGRAY)) {
            if (explorerPageIndex > 0) {
                explorerPageIndex--;
                pageRecords = catalog.getRecordsForPage(selectedExplorerDb, selectedEngineType, selectedBucketName, explorerPageIndex, explorerPageSize);
                if (!pageRecords.isEmpty()) selectedExplorerRecord = pageRecords.get(0);
            }
        }
        pbx += 78;

        // Etiqueta de Página
        String pageInfo = String.format("Página %,d / %,d (%,d registros)", (explorerPageIndex + 1), totalPages, displayTotalRecords);
        drawLegibleText(pageInfo, pbx + 6, pageBarY + 4, 11, RAYWHITE);
        int labelWidth = measureLegibleText(pageInfo, 11) + 14;
        pbx += Math.max(170, labelWidth);

        // Botón Siguiente ►
        if (guiButton(pbx, pageBarY, 74, 22, "SIG ►", (explorerPageIndex + 1 < totalPages) ? SKYBLUE : DARKGRAY)) {
            if (explorerPageIndex + 1 < totalPages) {
                explorerPageIndex++;
                pageRecords = catalog.getRecordsForPage(selectedExplorerDb, selectedEngineType, selectedBucketName, explorerPageIndex, explorerPageSize);
                if (!pageRecords.isEmpty()) selectedExplorerRecord = pageRecords.get(0);
            }
        }
        pbx += 78;

        // Botón +100 Páginas
        if (guiButton(pbx, pageBarY, 60, 22, "+100►", (explorerPageIndex + 100 < totalPages) ? GOLD : DARKGRAY)) {
            explorerPageIndex = Math.min(totalPages - 1, explorerPageIndex + 100);
            pageRecords = catalog.getRecordsForPage(selectedExplorerDb, selectedEngineType, selectedBucketName, explorerPageIndex, explorerPageSize);
            if (!pageRecords.isEmpty()) selectedExplorerRecord = pageRecords.get(0);
        }
        pbx += 64;

        // Botón Fin ►|
        if (guiButton(pbx, pageBarY, 32, 22, "►|", (explorerPageIndex + 1 < totalPages) ? SKYBLUE : DARKGRAY)) {
            explorerPageIndex = totalPages - 1;
            pageRecords = catalog.getRecordsForPage(selectedExplorerDb, selectedEngineType, selectedBucketName, explorerPageIndex, explorerPageSize);
            if (!pageRecords.isEmpty()) selectedExplorerRecord = pageRecords.get(0);
        }

        // 4. Lista de Registros (Cards)
        int recListY = my + 170;
        int rCardH = 42;

        for (int ri = 0; ri < pageRecords.size(); ri++) {
            EngineRecord rec = pageRecords.get(ri);
            boolean isSelRec = (selectedExplorerRecord != null && selectedExplorerRecord.getId().equals(rec.getId()));

            Rectangle rRec = new Rectangle().x(col2X).y(recListY + ri * (rCardH + 4)).width(col2W).height(rCardH);
            boolean rHov = checkCollisionPointRec(getMousePosition(), rRec);

            Color rBg = isSelRec ? new Color().r((byte)30).g((byte)50).b((byte)80).a((byte)230)
                                 : (rHov ? new Color().r((byte)24).g((byte)32).b((byte)50).a((byte)200)
                                        : new Color().r((byte)18).g((byte)22).b((byte)36).a((byte)180));

            drawRectangleRounded(rRec, 0.15f, 4, rBg);
            drawRectangleRoundedLines(rRec, 0.15f, 4, isSelRec ? GOLD : (rHov ? SKYBLUE : fade(GRAY, 0.4f)));

            drawLegibleText("🔑 " + rec.getId(), col2X + 8, (int)rRec.y() + 4, 11, isSelRec ? GOLD : SKYBLUE);
            drawLegibleText("v" + rec.getCurrentVersion() + " | " + rec.getTimestamp(), col2X + col2W - 170, (int)rRec.y() + 4, 9, LIGHTGRAY);

            String snip = rec.getSummary();
            if (snip.length() > 68) snip = snip.substring(0, 68) + "...";
            drawLegibleText(snip, col2X + 8, (int)rRec.y() + 20, 10, RAYWHITE);

            Rectangle cardViewBtn = new Rectangle().x(col2X + col2W - 55).y((int)rRec.y() + 18).width(48).height(19);
            boolean cvHov = checkCollisionPointRec(getMousePosition(), cardViewBtn);
            drawRectangleRounded(cardViewBtn, 0.2f, 3, cvHov ? SKYBLUE : fade(DARKGRAY, 0.6f));
            drawLegibleText("👁️ VER", (int)cardViewBtn.x() + 4, (int)cardViewBtn.y() + 3, 9, cvHov ? BLACK : RAYWHITE);
            if (cvHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                selectedExplorerRecord = rec;
                openViewRecordModal();
            }

            if (rHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                selectedExplorerRecord = rec;
            }
        }

        // 5. Inspector de Registro / Payload Multimodelo Adaptado
        int inspH = explorerMaximized ? 220 : 145;
        int inspY = my + mh - inspH - 12;
        drawRectangle(col2X, inspY, col2W, inspH, new Color().r((byte)10).g((byte)14).b((byte)24).a((byte)245));
        drawRectangleLines(col2X, inspY, col2W, inspH, GOLD);

        drawLegibleText("INSPECTOR DE PAYLOAD / REGISTRO MULTIMODELO:", col2X + 10, inspY + 8, 10, GOLD);
        if (selectedExplorerRecord != null) {
            String formatTag = "<" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + "><" + selectedExplorerRecord.getId() + ">";
            drawLegibleText("Esquema: " + formatTag + " | Versión: v" + selectedExplorerRecord.getCurrentVersion(), col2X + 10, inspY + 24, 10, SKYBLUE);

            Rectangle inspViewBtn = new Rectangle().x(col2X + col2W - 170).y(inspY + 6).width(160).height(20);
            boolean ivHov = checkCollisionPointRec(getMousePosition(), inspViewBtn);
            drawRectangleRounded(inspViewBtn, 0.2f, 3, ivHov ? GOLD : fade(BLUE, 0.7f));
            drawLegibleText("👁️ VER DETALLADO", (int)inspViewBtn.x() + 10, (int)inspViewBtn.y() + 4, 10, ivHov ? BLACK : RAYWHITE);
            if (ivHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                openViewRecordModal();
            }

            if ("JAVA_RECORD".equals(selectedEngineType)) {
                List<RecordFieldInfo> rFields = selectedExplorerRecord.getRecordFields();
                int itx = col2X + 10;
                int ity = inspY + 40;
                int itw = col2W - 20;
                int ic1 = 140, ic2 = 90, ic3 = 150;
                int ic4 = Math.max(120, itw - ic1 - ic2 - ic3);

                drawRectangle(itx, ity, itw, 20, new Color().r((byte)22).g((byte)30).b((byte)50).a((byte)255));
                drawRectangleLines(itx, ity, itw, 20, DARKGRAY);
                drawLegibleText("PROPIEDAD", itx + 6, ity + 4, 9, GOLD);
                drawLegibleText("TIPO", itx + ic1 + 6, ity + 4, 9, GOLD);
                drawLegibleText("VALOR", itx + ic1 + ic2 + 6, ity + 4, 9, GOLD);
                drawLegibleText("REGLAS JETTRARULES", itx + ic1 + ic2 + ic3 + 6, ity + 4, 9, GOLD);

                int maxRows = explorerMaximized ? 7 : 4;
                for (int fi = 0; fi < Math.min(maxRows, rFields.size()); fi++) {
                    RecordFieldInfo rf = rFields.get(fi);
                    int ry = ity + 22 + (fi * 18);
                    drawRectangle(itx, ry, itw, 17, (fi % 2 == 0) ? fade(BLACK, 0.35f) : fade(DARKGRAY, 0.2f));
                    drawLegibleText(rf.getProperty(), itx + 6, ry + 2, 9, SKYBLUE);
                    drawLegibleText(rf.getType(), itx + ic1 + 6, ry + 2, 9, GOLD);
                    drawLegibleText(rf.getValue(), itx + ic1 + ic2 + 6, ry + 2, 9, LIME);
                    drawLegibleText("🛡️ " + rf.getJettraRules(), itx + ic1 + ic2 + ic3 + 6, ry + 2, 9, YELLOW);
                }
            } else {
                Color textColor = switch (selectedEngineType) {
                    case "DOCUMENT" -> LIME;
                    case "GRAPH" -> SKYBLUE;
                    case "VECTOR" -> new Color().r((byte)0).g((byte)255).b((byte)230).a((byte)255);
                    case "KEYVALUE" -> MAGENTA;
                    case "TIMESERIES" -> ORANGE;
                    case "GEOSPATIAL" -> GREEN;
                    case "COLUMNAR" -> YELLOW;
                    default -> RAYWHITE;
                };

                String[] lines = selectedExplorerRecord.getDetails().split("\n");
                int maxLines = explorerMaximized ? 10 : 5;
                for (int li = 0; li < Math.min(maxLines, lines.length); li++) {
                    drawLegibleText(lines[li], col2X + 12, inspY + 42 + (li * 16), 10, textColor);
                }
            }
        } else {
            drawLegibleText("(Seleccione un registro arriba para inspeccionar su estructura)", col2X + 12, inspY + 40, 11, GRAY);
        }
    }

    private void drawExplorerIndexesTab(EngineDataCatalog catalog, int col2X, int my, int col2W, int mh) {
        int barY = my + 82;
        drawLegibleText("⚙️ ADMINISTRACIÓN DE ÍNDICES: <" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + ">", col2X, barY + 5, 12, GOLD);

        // Botones de Operación de Índices
        int btnW = 130;
        int opsY = my + 112;
        if (guiButton(col2X, opsY, btnW, 24, "➕ CREAR ÍNDICE", GREEN)) {
            openCreateIndexModal();
        }
        if (guiButton(col2X + btnW + 10, opsY, 150, 24, "✏️ RECONSTRUIR", (selectedIndexInfo != null) ? SKYBLUE : DARKGRAY)) {
            if (selectedIndexInfo != null) openEditIndexModal();
        }
        if (guiButton(col2X + btnW * 2 + 30, opsY, 140, 24, "🗑️ DROP ÍNDICE", (selectedIndexInfo != null) ? RED : DARKGRAY)) {
            if (selectedIndexInfo != null) openDropIndexModal();
        }

        // Tabla de Índices
        List<EngineIndexInfo> idxList = catalog.getIndexes(selectedExplorerDb, selectedEngineType, selectedBucketName);
        int tableY = my + 148;

        // Encabezados de tabla
        drawRectangle(col2X, tableY, col2W, 24, new Color().r((byte)24).g((byte)32).b((byte)52).a((byte)255));
        drawRectangleLines(col2X, tableY, col2W, 24, DARKGRAY);
        drawLegibleText("NOMBRE ÍNDICE", col2X + 10, tableY + 5, 10, GOLD);
        drawLegibleText("CAMPO", col2X + 180, tableY + 5, 10, GOLD);
        drawLegibleText("TIPO DE ÍNDICE", col2X + 290, tableY + 5, 10, GOLD);
        drawLegibleText("ÚNICO", col2X + 410, tableY + 5, 10, GOLD);
        drawLegibleText("ENTRADAS", col2X + 470, tableY + 5, 10, GOLD);
        drawLegibleText("ESTADO", col2X + 560, tableY + 5, 10, GOLD);

        int rowY = tableY + 28;
        int rowH = 26;
        for (EngineIndexInfo info : idxList) {
            boolean isSel = (selectedIndexInfo != null && selectedIndexInfo.getName().equalsIgnoreCase(info.getName()));
            Rectangle rRec = new Rectangle().x(col2X).y(rowY).width(col2W).height(rowH);
            boolean rHov = checkCollisionPointRec(getMousePosition(), rRec);

            drawRectangleRounded(rRec, 0.1f, 4, isSel ? fade(BLUE, 0.6f) : (rHov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.25f)));
            drawRectangleRoundedLines(rRec, 0.1f, 4, isSel ? GOLD : fade(GRAY, 0.3f));

            drawLegibleText("🏷️ " + info.getName(), col2X + 10, rowY + 6, 10, isSel ? GOLD : RAYWHITE);
            drawLegibleText(info.getField(), col2X + 180, rowY + 6, 10, SKYBLUE);
            drawLegibleText(info.getType(), col2X + 290, rowY + 6, 10, LIME);
            drawLegibleText(info.isUnique() ? "SÍ" : "NO", col2X + 410, rowY + 6, 10, info.isUnique() ? YELLOW : GRAY);
            drawLegibleText(String.format("%,d", info.getEntriesCount()), col2X + 470, rowY + 6, 10, LIGHTGRAY);
            drawLegibleText(info.getStatus(), col2X + 560, rowY + 6, 10, LIME);

            if (rHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                selectedIndexInfo = info;
            }
            rowY += rowH + 4;
        }

        // Cuadro de Explicación de Índices
        int boxY = my + mh - 130;
        drawRectangle(col2X, boxY, col2W, 115, new Color().r((byte)12).g((byte)16).b((byte)28).a((byte)245));
        drawRectangleLines(col2X, boxY, col2W, 115, GOLD);
        drawLegibleText("ESTRATEGIA DE INDEXACIÓN EN JETTRASTORE (JettraCollections UnifiedMap):", col2X + 12, boxY + 8, 10, GOLD);
        drawLegibleText("• Almacenamiento Zero-Set Compacto: Sin cajas superfluas para evitar presión de Garbage Collector.", col2X + 12, boxY + 26, 10, LIGHTGRAY);
        drawLegibleText("• Soporta índices Primarios Hash, B-Tree O(log n), HNSW para Embeddings 3D/HD y R-Tree Geoespacial.", col2X + 12, boxY + 44, 10, LIGHTGRAY);
        drawLegibleText("• Capacidad inicial calculada dinámicamente y streaming lazy para eliminar errores OutOfMemoryError.", col2X + 12, boxY + 62, 10, LIGHTGRAY);
        drawLegibleText("• Sincronización atómica multihilo directa con Panama Direct Memory.", col2X + 12, boxY + 80, 10, SKYBLUE);
    }

    // =========================================================================
    // SUB-MODAL 1: Operaciones CRUD por Registro adaptadas al Engine
    private void openViewRecordModal() {
        if (selectedExplorerRecord == null) return;
        showRecordViewModal = true;
        recordViewScroll = 0;
    }

    private void openAddRecordModal() {
        recordOpMode = "ADD";
        formRecordId = generateIdForEngine(selectedEngineType);
        formRecordSummary = generateSummaryForEngine(selectedEngineType, formRecordId);
        formRecordActiveField = 1;
        if ("JAVA_RECORD".equals(selectedEngineType)) {
            formRecordFields = RecordFieldInfo.createDefaultFacturaFields(System.currentTimeMillis() % 1000);
            formRecordDetails = RecordFieldInfo.buildRecordDetails(selectedBucketName + "Record", formRecordFields);
        } else {
            formRecordDetails = applyTemplateForEngine(selectedEngineType, selectedBucketName, formRecordId);
            formRecordFields.clear();
        }
        showRecordOperationModal = true;
    }

    private void openEditRecordModal() {
        if (selectedExplorerRecord == null) return;
        recordOpMode = "EDIT";
        formRecordId = selectedExplorerRecord.getId();
        formRecordSummary = selectedExplorerRecord.getSummary();
        formRecordDetails = selectedExplorerRecord.getDetails();
        formRecordActiveField = 2;
        if ("JAVA_RECORD".equals(selectedEngineType)) {
            formRecordFields = selectedExplorerRecord.getRecordFields();
            if (formRecordFields.isEmpty()) {
                formRecordFields = RecordFieldInfo.parseFromDetails(selectedExplorerRecord.getDetails(), selectedExplorerRecord.getSummary(), formRecordId);
            }
        } else {
            formRecordFields.clear();
        }
        showRecordOperationModal = true;
    }

    private void openDeleteRecordModal() {
        if (selectedExplorerRecord == null) return;
        recordOpMode = "DELETE_CONFIRM";
        formRecordId = selectedExplorerRecord.getId();
        showRecordOperationModal = true;
    }

    private String generateIdForEngine(String engine) {
        long rnd = System.currentTimeMillis() % 10000;
        return switch (engine) {
            case "DOCUMENT" -> String.format("DOC-2026-%04d", rnd);
            case "JAVA_RECORD" -> String.format("REC-STRUCT-%04d", rnd);
            case "GRAPH" -> String.format("EDGE-TOPOLOGY-%04d", rnd);
            case "VECTOR" -> String.format("VEC-EMBED-%04d", rnd);
            case "KEYVALUE" -> String.format("cache:item:%04d", rnd);
            case "TIMESERIES" -> String.format("TS-METRIC-%04d", rnd);
            case "GEOSPATIAL" -> String.format("GEO-NODE-%04d", rnd);
            case "COLUMNAR" -> String.format("COL-CHUNK-%04d", rnd);
            default -> String.format("OBJ-%04d", rnd);
        };
    }

    private String generateSummaryForEngine(String engine, String id) {
        return switch (engine) {
            case "DOCUMENT" -> "Documento JSON registrado en " + selectedBucketName;
            case "JAVA_RECORD" -> "Instancia Java Record In-Memory Panama Struct (" + selectedBucketName + ")";
            case "GRAPH" -> "Arista topológica de grafo (" + id + ")";
            case "VECTOR" -> "Embedding vectorial multidimensional Cosine HD";
            case "KEYVALUE" -> "Par Clave-Valor en memoria nativa sin GC";
            case "TIMESERIES" -> "Muestra de serie temporal con timestamp";
            case "GEOSPATIAL" -> "Coordenadas espaciales R-Tree (" + id + ")";
            case "COLUMNAR" -> "Chunk columnar comprimido ZSTD con agregados";
            default -> "Registro genérico multimodelo";
        };
    }

    private String applyTemplateForEngine(String engine, String bucket, String id) {
        return switch (engine) {
            case "DOCUMENT" -> "{\n  \"id\": \"" + id + "\",\n  \"emisor\": \"Corp Global SA\",\n  \"total\": 1250.00,\n  \"estado\": \"TIMBRADO_VALIDADO\"\n}";
            case "JAVA_RECORD" -> "public record " + Character.toUpperCase(bucket.charAt(0)) + bucket.substring(1) + "Record(\n  long folio,\n  String itemSku,\n  double precio,\n  double tasaImpuesto,\n  long offHeapOffset\n) {\n  // Instancia Panama FFM\n}";
            case "GRAPH" -> "GraphEdge: {\n  \"sourceVertex\": \"VERTEX_SRC\",\n  \"targetVertex\": \"VERTEX_TGT\",\n  \"relationship\": \"CONECTA_A\",\n  \"weight\": 1.0\n}";
            case "VECTOR" -> "VectorEmbedding {\n  \"id\": \"" + id + "\",\n  \"dimensions\": 3,\n  \"coordinates\": [0.250, -0.750, 0.450],\n  \"metric\": \"COSINE\"\n}";
            case "KEYVALUE" -> "KeyValueEntry {\n  \"key\": \"" + id + "\",\n  \"value\": \"HASH_TOKEN_DATA\",\n  \"timeToLiveSeconds\": 3600\n}";
            case "TIMESERIES" -> "TimeSeriesPoint {\n  \"id\": \"" + id + "\",\n  \"metric\": \"METRICA_VALOR\",\n  \"value\": 145.8,\n  \"unit\": \"ppm\"\n}";
            case "GEOSPATIAL" -> "GeoPoint {\n  \"id\": \"" + id + "\",\n  \"latitude\": 19.4326,\n  \"longitude\": -99.1332,\n  \"radioKm\": 5.0\n}";
            case "COLUMNAR" -> "ColumnChunk {\n  \"chunkId\": 101,\n  \"rowCount\": 10000,\n  \"compression\": \"ZSTD_SIMD\"\n}";
            default -> "{\n  \"id\": \"" + id + "\"\n}";
        };
    }

    private void drawRecordOperationModal(int sw, int sh) {
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.85f));

        int dw = "JAVA_RECORD".equals(selectedEngineType) ? 780 : 660;
        int dh = 520;
        int dx = (sw - dw) / 2;
        int dy = (sh - dh) / 2;

        drawRectangle(dx, dy, dw, dh, new Color().r((byte)20).g((byte)24).b((byte)38).a((byte)255));
        drawRectangleLines(dx, dy, dw, dh, GOLD);

        String title = recordOpMode.equals("ADD") ? "➕ AGREGAR NUEVO REGISTRO (" + selectedEngineType + ")"
                     : (recordOpMode.equals("EDIT") ? "✏️ EDITAR REGISTRO (" + selectedEngineType + ")" : "🗑️ CONFIRMAR ELIMINACIÓN");
        drawLegibleText(title, dx + 20, dy + 16, 14, GOLD);

        String pathFmt = "<" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + "><" + formRecordId + ">";
        drawLegibleText("ESQUEMA: " + pathFmt, dx + 20, dy + 36, 11, LIME);

        if (recordOpMode.equals("DELETE_CONFIRM")) {
            drawLegibleText("¿Está seguro de eliminar el registro '" + formRecordId + "' del bucket '" + selectedBucketName + "'?", dx + 30, dy + 120, 12, RAYWHITE);
            drawLegibleText("Esta acción eliminará el objeto en tiempo real del motor " + selectedEngineType + ".", dx + 30, dy + 150, 11, LIGHTGRAY);

            if (guiButton(dx + 50, dy + 220, 180, 32, "🗑️ SÍ, ELIMINAR", RED)) {
                EngineDataCatalog.getInstance().deleteRecord(selectedExplorerDb, selectedEngineType, selectedBucketName, formRecordId);
                selectedExplorerRecord = null;
                showRecordOperationModal = false;
            }
            if (guiButton(dx + 260, dy + 220, 160, 32, "CANCELAR", DARKGRAY)) {
                showRecordOperationModal = false;
            }
            return;
        }

        // Formulario de Edición o Agregado
        drawLegibleText("Identificador / ID (Clave primaria):", dx + 20, dy + 60, 10, SKYBLUE);
        drawSimpleInputField(dx + 20, dy + 76, dw - 40, 24, formRecordId, formRecordActiveField == 1, false);
        if (checkCollisionPointRec(getMousePosition(), new Rectangle().x(dx + 20).y(dy + 76).width(dw - 40).height(24)) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formRecordActiveField = 1;
        }

        drawLegibleText("Resumen / Metadatos rápidos:", dx + 20, dy + 104, 10, SKYBLUE);
        drawSimpleInputField(dx + 20, dy + 120, dw - 40, 24, formRecordSummary, formRecordActiveField == 2, false);
        if (checkCollisionPointRec(getMousePosition(), new Rectangle().x(dx + 20).y(dy + 120).width(dw - 40).height(24)) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formRecordActiveField = 2;
        }

        // SECCIÓN ADAPTADA POR ENGINE
        if ("JAVA_RECORD".equals(selectedEngineType)) {
            drawLegibleText("📋 CAMPOS DEL RECORD Y VALIDACIONES JETTRARULES (In-Memory Panama Struct):", dx + 20, dy + 148, 10, GOLD);

            int tx = dx + 20;
            int ty = dy + 166;
            int tw = dw - 40;
            int c1 = 140, c2 = 90, c3 = 160;
            int c4 = tw - c1 - c2 - c3;

            // Encabezado de tabla
            drawRectangle(tx, ty, tw, 22, new Color().r((byte)28).g((byte)36).b((byte)56).a((byte)255));
            drawRectangleLines(tx, ty, tw, 22, DARKGRAY);
            drawLegibleText("PROPIEDAD", tx + 6, ty + 4, 10, GOLD);
            drawLegibleText("TIPO", tx + c1 + 6, ty + 4, 10, GOLD);
            drawLegibleText("VALOR", tx + c1 + c2 + 6, ty + 4, 10, GOLD);
            drawLegibleText("REGLAS JETTRARULES", tx + c1 + c2 + c3 + 6, ty + 4, 10, GOLD);

            int maxEditRows = 7;
            for (int i = 0; i < Math.min(maxEditRows, formRecordFields.size()); i++) {
                RecordFieldInfo rf = formRecordFields.get(i);
                int ry = ty + 24 + (i * 22);
                drawRectangle(tx, ry, tw, 20, (i % 2 == 0) ? fade(BLACK, 0.35f) : fade(DARKGRAY, 0.2f));

                drawLegibleText(rf.getProperty(), tx + 6, ry + 4, 10, SKYBLUE);
                drawLegibleText(rf.getType(), tx + c1 + 6, ry + 4, 10, GOLD);
                drawLegibleText(rf.getValue(), tx + c1 + c2 + 6, ry + 4, 10, LIME);
                drawLegibleText("🛡️ " + rf.getJettraRules(), tx + c1 + c2 + c3 + 6, ry + 4, 10, YELLOW);
            }

            int btnRowY = ty + 24 + (Math.min(maxEditRows, formRecordFields.size()) * 22) + 6;
            if (guiButton(tx, btnRowY, 150, 24, "➕ AÑADIR CAMPO", BLUE)) {
                int nextIdx = formRecordFields.size() + 1;
                formRecordFields.add(new RecordFieldInfo("campo_" + nextIdx, "String", "valor_" + nextIdx, "@NotBlank @NotNull"));
            }
            if (formRecordFields.size() > 1 && guiButton(tx + 160, btnRowY, 140, 24, "🗑️ QUITAR CAMPO", RED)) {
                formRecordFields.remove(formRecordFields.size() - 1);
            }
            if (guiButton(tx + 310, btnRowY, 200, 24, "🛡️ ADJUNTAR @DecimalMin", PURPLE)) {
                if (!formRecordFields.isEmpty()) {
                    RecordFieldInfo last = formRecordFields.get(formRecordFields.size() - 1);
                    last.setJettraRules(last.getJettraRules() + " @DecimalMin(\"0.01\")");
                }
            }
            if (guiButton(tx + 520, btnRowY, 210, 24, "📋 CARGAR PLANTILLA RECORD", DARKPURPLE)) {
                formRecordFields = RecordFieldInfo.createDefaultFacturaFields(System.currentTimeMillis() % 1000);
            }
        } else {
            String payloadLabel = switch (selectedEngineType) {
                case "DOCUMENT" -> "Payload Documento (Formato JSON Estructurado):";
                case "GRAPH" -> "Definición de Grafo (Vértices, Aristas y Propiedades):";
                case "VECTOR" -> "Embedding Vectorial (Coordenadas y Métrica Cosine):";
                case "KEYVALUE" -> "Par Clave-Valor en Memoria Nativa Directa:";
                case "TIMESERIES" -> "Punto de Serie Temporal de Alta Precisión:";
                case "GEOSPATIAL" -> "Coordenadas Espaciales Geoespaciales:";
                case "COLUMNAR" -> "Definición de Chunk Columnar Comprimido:";
                default -> "Contenido del Registro:";
            };
            drawLegibleText(payloadLabel, dx + 20, dy + 150, 10, GOLD);

            Rectangle boxRec = new Rectangle().x(dx + 20).y(dy + 168).width(dw - 40).height(210);
            drawRectangleRounded(boxRec, 0.1f, 4, (formRecordActiveField == 3) ? new Color().r((byte)10).g((byte)15).b((byte)28).a((byte)255) : new Color().r((byte)14).g((byte)18).b((byte)30).a((byte)255));
            drawRectangleRoundedLines(boxRec, 0.1f, 4, (formRecordActiveField == 3) ? GOLD : DARKGRAY);
            if (checkCollisionPointRec(getMousePosition(), boxRec) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                formRecordActiveField = 3;
            }

            String[] dLines = formRecordDetails.split("\n");
            for (int i = 0; i < Math.min(10, dLines.length); i++) {
                drawLegibleText(dLines[i], dx + 28, dy + 176 + (i * 18), 11, LIME);
            }

            if (guiButton(dx + 20, dy + 390, 180, 26, "📋 CARGAR PLANTILLA", PURPLE)) {
                formRecordDetails = applyTemplateForEngine(selectedEngineType, selectedBucketName, formRecordId);
            }
        }

        // Botón Guardar
        int saveY = dy + dh - 46;
        if (guiButton(dx + dw - 240, saveY, 110, 32, "💾 GUARDAR", GREEN)) {
            if ("JAVA_RECORD".equals(selectedEngineType)) {
                formRecordDetails = RecordFieldInfo.buildRecordDetails(selectedBucketName + "Record", formRecordFields);
            }
            if (recordOpMode.equals("ADD")) {
                EngineRecord newRec = new EngineRecord(formRecordId, selectedEngineType, selectedBucketName, formRecordSummary, formRecordDetails, null, formRecordFields);
                EngineDataCatalog.getInstance().addRecord(selectedExplorerDb, selectedEngineType, selectedBucketName, newRec);
                selectedExplorerRecord = newRec;
            } else {
                EngineDataCatalog.getInstance().updateRecord(selectedExplorerDb, selectedEngineType, selectedBucketName, formRecordId, formRecordSummary, formRecordDetails, "Edición de registro y reglas JettraRules", formRecordFields);
            }
            showRecordOperationModal = false;
        }

        // Botón Cancelar
        if (guiButton(dx + dw - 120, saveY, 100, 32, "CANCELAR", DARKGRAY)) {
            showRecordOperationModal = false;
        }
    }

    // =========================================================================
    // SUB-MODAL 2: Restauración de Versiones de Registro
    // =========================================================================
    private void drawVersionHistoryModal(int sw, int sh) {
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.85f));

        int dw = 860;
        int dh = 520;
        int dx = (sw - dw) / 2;
        int dy = (sh - dh) / 2;

        drawRectangle(dx, dy, dw, dh, new Color().r((byte)18).g((byte)22).b((byte)36).a((byte)255));
        drawRectangleLines(dx, dy, dw, dh, GOLD);

        drawLegibleText("⏪ HISTORIAL Y RESTAURACIÓN DE VERSIONES", dx + 20, dy + 16, 14, GOLD);
        if (selectedExplorerRecord != null) {
            String pathFmt = "<" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + "><" + selectedExplorerRecord.getId() + ">";
            drawLegibleText("Registro: " + pathFmt + " | Versión Actual: v" + selectedExplorerRecord.getCurrentVersion(), dx + 20, dy + 36, 11, SKYBLUE);

            List<RecordVersion> hist = selectedExplorerRecord.getVersionHistory();
            int listW = 260;
            int vListY = dy + 68;

            drawLegibleText("VERSIONES DISPONIBLES (" + hist.size() + "):", dx + 20, vListY, 11, GOLD);
            vListY += 18;

            for (RecordVersion rv : hist) {
                boolean isSelVer = (selectedVersionNumber == rv.version());
                Rectangle vRec = new Rectangle().x(dx + 20).y(vListY).width(listW).height(46);
                boolean vHov = checkCollisionPointRec(getMousePosition(), vRec);

                drawRectangleRounded(vRec, 0.15f, 4, isSelVer ? fade(BLUE, 0.7f) : (vHov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.3f)));
                drawRectangleRoundedLines(vRec, 0.15f, 4, isSelVer ? GOLD : DARKGRAY);

                String vLabel = "v" + rv.version() + " " + (rv.version() == selectedExplorerRecord.getCurrentVersion() ? "[ACTUAL]" : "");
                drawLegibleText(vLabel, dx + 26, vListY + 4, 11, isSelVer ? GOLD : RAYWHITE);
                drawLegibleText(rv.timestamp(), dx + 110, vListY + 4, 9, LIGHTGRAY);
                drawLegibleText(rv.operationNote(), dx + 26, vListY + 22, 10, SKYBLUE);

                if (vHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                    selectedVersionNumber = rv.version();
                }
                vListY += 52;
            }

            // Vista previa adaptada según Engine
            int prevX = dx + listW + 30;
            int prevW = dw - listW - 50;
            drawLegibleText("DETALLE DE LA VERSIÓN SELECCIONADA (v" + selectedVersionNumber + ") - ENGINE: " + selectedEngineType, prevX, dy + 68, 11, GOLD);

            RecordVersion targetRv = selectedExplorerRecord.getVersion(selectedVersionNumber);
            Rectangle prevBox = new Rectangle().x(prevX).y(dy + 88).width(prevW).height(320);
            drawRectangleRounded(prevBox, 0.1f, 4, new Color().r((byte)10).g((byte)14).b((byte)24).a((byte)255));
            drawRectangleRoundedLines(prevBox, 0.1f, 4, GOLD);

            if (targetRv != null) {
                drawLegibleText("Resumen: " + targetRv.summary(), prevX + 10, dy + 96, 10, RAYWHITE);

                if ("JAVA_RECORD".equals(selectedEngineType)) {
                    List<RecordFieldInfo> vFields = targetRv.safeFields();
                    if (vFields.isEmpty()) {
                        vFields = RecordFieldInfo.parseFromDetails(targetRv.details(), targetRv.summary(), selectedExplorerRecord.getId());
                    }
                    int vtx = prevX + 10;
                    int vty = dy + 118;
                    int vtw = prevW - 20;
                    int vc1 = 120, vc2 = 80, vc3 = 130;
                    int vc4 = vtw - vc1 - vc2 - vc3;

                    drawRectangle(vtx, vty, vtw, 22, new Color().r((byte)22).g((byte)30).b((byte)50).a((byte)255));
                    drawLegibleText("PROPIEDAD", vtx + 6, vty + 4, 9, GOLD);
                    drawLegibleText("TIPO", vtx + vc1 + 6, vty + 4, 9, GOLD);
                    drawLegibleText("VALOR", vtx + vc1 + vc2 + 6, vty + 4, 9, GOLD);
                    drawLegibleText("REGLAS JETTRARULES", vtx + vc1 + vc2 + vc3 + 6, vty + 4, 9, GOLD);

                    for (int fi = 0; fi < Math.min(8, vFields.size()); fi++) {
                        RecordFieldInfo rf = vFields.get(fi);
                        int ry = vty + 24 + (fi * 20);
                        drawRectangle(vtx, ry, vtw, 18, (fi % 2 == 0) ? fade(BLACK, 0.35f) : fade(DARKGRAY, 0.2f));
                        drawLegibleText(rf.getProperty(), vtx + 6, ry + 2, 9, SKYBLUE);
                        drawLegibleText(rf.getType(), vtx + vc1 + 6, ry + 2, 9, GOLD);
                        drawLegibleText(rf.getValue(), vtx + vc1 + vc2 + 6, ry + 2, 9, LIME);
                        drawLegibleText("🛡️ " + rf.getJettraRules(), vtx + vc1 + vc2 + vc3 + 6, ry + 2, 9, YELLOW);
                    }
                } else {
                    String[] pLines = targetRv.details().split("\n");
                    for (int i = 0; i < Math.min(13, pLines.length); i++) {
                        drawLegibleText(pLines[i], prevX + 10, dy + 118 + (i * 18), 10, LIME);
                    }
                }
            }

            if (guiButton(prevX, dy + 420, 240, 32, "⟲ RESTAURAR ESTA VERSIÓN", LIME)) {
                if (targetRv != null) {
                    EngineDataCatalog.getInstance().restoreRecordVersion(
                        selectedExplorerDb, selectedEngineType, selectedBucketName, selectedExplorerRecord.getId(), targetRv.version()
                    );
                    selectedVersionNumber = selectedExplorerRecord.getCurrentVersion();
                    showVersionHistoryModal = false;
                }
            }
        }

        if (guiButton(dx + dw - 120, dy + dh - 45, 100, 30, "CERRAR", DARKGRAY)) {
            showVersionHistoryModal = false;
        }
    }

    // =========================================================================
    // SUB-MODAL DEDICADO: Visualizador de Registro Adaptado por Engine
    // =========================================================================
    private void drawRecordViewModal(int sw, int sh) {
        if (selectedExplorerRecord == null) {
            showRecordViewModal = false;
            return;
        }

        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.85f));

        int dw = Math.min(980, sw - 40);
        int dh = Math.min(620, sh - 40);
        int dx = (sw - dw) / 2;
        int dy = (sh - dh) / 2;

        drawRectangle(dx, dy, dw, dh, new Color().r((byte)14).g((byte)18).b((byte)30).a((byte)255));
        drawRectangleLines(dx, dy, dw, dh, GOLD);

        // Barra de Título
        drawRectangle(dx, dy, dw, 46, new Color().r((byte)22).g((byte)28).b((byte)46).a((byte)255));
        drawLine(dx, dy + 46, dx + dw, dy + 46, GOLD);

        drawLegibleText("👁️ VISUALIZADOR DE REGISTRO MULTIMODELO", dx + 20, dy + 12, 14, GOLD);

        // Botón Cerrar (X)
        Rectangle closeBtnRec = new Rectangle().x(dx + dw - 36).y(dy + 10).width(26).height(26);
        boolean closeHover = checkCollisionPointRec(getMousePosition(), closeBtnRec);
        drawRectangleRounded(closeBtnRec, 0.2f, 4, closeHover ? RED : DARKGRAY);
        drawLegibleText("X", (int)closeBtnRec.x() + 8, (int)closeBtnRec.y() + 5, 14, RAYWHITE);
        if (closeHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showRecordViewModal = false;
            return;
        }

        // Subheader de Metadatos
        String pathFmt = "<" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + "><" + selectedExplorerRecord.getId() + ">";
        drawLegibleText("📍 ESQUEMA: " + pathFmt, dx + 20, dy + 56, 11, LIME);
        drawLegibleText("Versión: v" + selectedExplorerRecord.getCurrentVersion() + " | Registro: " + selectedExplorerRecord.getId() + " | Timestamp: " + selectedExplorerRecord.getTimestamp(), dx + 20, dy + 74, 10, SKYBLUE);

        // Badge de Engine
        Rectangle badgeRec = new Rectangle().x(dx + dw - 180).y(dy + 56).width(160).height(24);
        Color engColor = switch (selectedEngineType) {
            case "DOCUMENT" -> LIME;
            case "JAVA_RECORD" -> GOLD;
            case "GRAPH" -> SKYBLUE;
            case "VECTOR" -> new Color().r((byte)0).g((byte)255).b((byte)230).a((byte)255);
            case "KEYVALUE" -> MAGENTA;
            case "TIMESERIES" -> ORANGE;
            case "GEOSPATIAL" -> GREEN;
            case "COLUMNAR" -> YELLOW;
            default -> RAYWHITE;
        };
        drawRectangleRounded(badgeRec, 0.2f, 4, fade(engColor, 0.25f));
        drawRectangleRoundedLines(badgeRec, 0.2f, 4, engColor);
        drawLegibleText("ENGINE: " + selectedEngineType, (int)badgeRec.x() + 10, (int)badgeRec.y() + 5, 10, engColor);

        // ÁREA DE CONTENIDO ADAPTADA SEGÚN EL ENGINE SELECCIONADO
        int contentY = dy + 100;
        int contentW = dw - 40;
        int contentH = dh - 160;

        if ("JAVA_RECORD".equals(selectedEngineType)) {
            drawJavaRecordView(dx + 20, contentY, contentW, contentH);
        } else if ("DOCUMENT".equals(selectedEngineType)) {
            drawDocumentJsonView(dx + 20, contentY, contentW, contentH);
        } else if ("GRAPH".equals(selectedEngineType)) {
            drawGraphView(dx + 20, contentY, contentW, contentH);
        } else if ("VECTOR".equals(selectedEngineType)) {
            drawVectorView(dx + 20, contentY, contentW, contentH);
        } else if ("KEYVALUE".equals(selectedEngineType)) {
            drawKeyValueView(dx + 20, contentY, contentW, contentH);
        } else if ("TIMESERIES".equals(selectedEngineType)) {
            drawTimeSeriesView(dx + 20, contentY, contentW, contentH);
        } else if ("GEOSPATIAL".equals(selectedEngineType)) {
            drawGeospatialView(dx + 20, contentY, contentW, contentH);
        } else if ("COLUMNAR".equals(selectedEngineType)) {
            drawColumnarView(dx + 20, contentY, contentW, contentH);
        } else {
            drawGenericView(dx + 20, contentY, contentW, contentH);
        }

        // BARRA INFERIOR DE ACCIONES RÁPIDAS
        int bottomY = dy + dh - 48;
        drawLine(dx, bottomY - 6, dx + dw, bottomY - 6, fade(GRAY, 0.3f));

        if (guiButton(dx + 20, bottomY, 150, 32, "✏️ EDITAR", SKYBLUE)) {
            showRecordViewModal = false;
            openEditRecordModal();
        }

        if (guiButton(dx + 180, bottomY, 170, 32, "⏪ VERSIONES", GOLD)) {
            showRecordViewModal = false;
            showVersionHistoryModal = true;
            selectedVersionNumber = selectedExplorerRecord.getCurrentVersion();
        }

        if (guiButton(dx + dw - 120, bottomY, 100, 32, "CERRAR", DARKGRAY)) {
            showRecordViewModal = false;
        }
    }

    // 1. Vista Adaptada: JAVA RECORD (Tabla de Propiedad, Tipo, Valor y Reglas JettraRules)
    private void drawJavaRecordView(int cx, int cy, int cw, int ch) {
        drawLegibleText("📋 TABLA ESTRUCTURADA DE CAMPOS Y VALIDACIONES JETTRARULES (Zero-Set In-Memory Panama Struct):", cx, cy, 11, GOLD);

        List<RecordFieldInfo> fields = selectedExplorerRecord.getRecordFields();
        int ty = cy + 24;
        int c1 = 150, c2 = 110, c3 = 190;
        int c4 = cw - c1 - c2 - c3;

        // Encabezado de la Tabla
        drawRectangle(cx, ty, cw, 26, new Color().r((byte)22).g((byte)30).b((byte)52).a((byte)255));
        drawRectangleLines(cx, ty, cw, 26, GOLD);
        drawLegibleText("PROPIEDAD", cx + 8, ty + 6, 11, GOLD);
        drawLegibleText("TIPO", cx + c1 + 8, ty + 6, 11, GOLD);
        drawLegibleText("VALOR", cx + c1 + c2 + 8, ty + 6, 11, GOLD);
        drawLegibleText("REGLAS JETTRARULES", cx + c1 + c2 + c3 + 8, ty + 6, 11, GOLD);

        // Filas de la Tabla
        int rowH = 26;
        int currentY = ty + 28;
        for (int i = 0; i < Math.min(8, fields.size()); i++) {
            RecordFieldInfo f = fields.get(i);
            Rectangle rRec = new Rectangle().x(cx).y(currentY).width(cw).height(rowH);
            boolean rHov = checkCollisionPointRec(getMousePosition(), rRec);

            drawRectangle(cx, currentY, cw, rowH, (i % 2 == 0) ? fade(BLACK, 0.35f) : fade(DARKGRAY, 0.2f));
            if (rHov) drawRectangleLines(cx, currentY, cw, rowH, SKYBLUE);

            drawLegibleText(f.getProperty(), cx + 8, currentY + 6, 11, SKYBLUE);
            drawLegibleText(f.getType(), cx + c1 + 8, currentY + 6, 11, GOLD);
            drawLegibleText(f.getValue(), cx + c1 + c2 + 8, currentY + 6, 11, LIME);
            drawLegibleText("🛡️ " + f.getJettraRules(), cx + c1 + c2 + c3 + 8, currentY + 6, 11, YELLOW);

            currentY += rowH + 2;
        }

        // Panel de información JettraRules
        int infoY = currentY + 10;
        int infoH = ch - (infoY - cy);
        if (infoH > 60) {
            drawRectangle(cx, infoY, cw, infoH, new Color().r((byte)10).g((byte)14).b((byte)24).a((byte)255));
            drawRectangleLines(cx, infoY, cw, infoH, fade(GOLD, 0.6f));

            drawLegibleText("🛡️ MOTOR DE REGLAS JETTRARULES (io.jettra.rules.validations):", cx + 12, infoY + 8, 10, GOLD);
            drawLegibleText("• Reglas validadas: @NotNull, @NotBlank, @Min, @DecimalMin, @Pattern, @AssertTrue sin overhead de Garbage Collector.", cx + 12, infoY + 26, 10, LIGHTGRAY);
            drawLegibleText("• Arquitectura: Estructura nativa Panama Foreign Function & Memory (FFM) mapeada directamente a heap nativo off-heap.", cx + 12, infoY + 44, 10, SKYBLUE);

            if (infoH > 100) {
                drawLegibleText("Código Java Record compilado:", cx + 12, infoY + 64, 10, RAYWHITE);
                String codePreview = RecordFieldInfo.buildRecordDetails(selectedBucketName + "Record", fields);
                String[] cLines = codePreview.split("\n");
                for (int ci = 0; ci < Math.min(3, cLines.length); ci++) {
                    drawLegibleText(cLines[ci], cx + 24, infoY + 82 + (ci * 16), 10, LIME);
                }
            }
        }
    }

    // 2. Vista Adaptada: DOCUMENT (JSON Formateado y Resaltado)
    private void drawDocumentJsonView(int cx, int cy, int cw, int ch) {
        drawLegibleText("📄 DOCUMENTO JSON OFF-HEAP ESTRUCTURADO (JSON Engine / UTF-8 Direct Memory):", cx, cy, 11, LIME);

        Rectangle jsonBox = new Rectangle().x(cx).y(cy + 22).width(cw).height(ch - 30);
        drawRectangleRounded(jsonBox, 0.1f, 4, new Color().r((byte)10).g((byte)14).b((byte)24).a((byte)255));
        drawRectangleRoundedLines(jsonBox, 0.1f, 4, LIME);

        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(18, lines.length); i++) {
            String line = lines[i];
            Color lineCol = RAYWHITE;
            if (line.contains(":") && line.contains("\"")) {
                lineCol = SKYBLUE;
            } else if (line.contains("true") || line.contains("false")) {
                lineCol = MAGENTA;
            } else if (line.matches(".*\\d+.*")) {
                lineCol = LIME;
            }
            drawLegibleText(String.format("%2d | %s", i + 1, line), cx + 14, cy + 34 + (i * 18), 11, lineCol);
        }
    }

    // 3. Vista Adaptada: GRAPH (Topología Vértices y Aristas)
    private void drawGraphView(int cx, int cy, int cw, int ch) {
        drawLegibleText("🕸️ TOPOLOGÍA DE GRAFO (ARISTAS, VÉRTICES Y PESOS):", cx, cy, 11, SKYBLUE);

        int cardY = cy + 24;
        Rectangle gBox = new Rectangle().x(cx).y(cardY).width(cw).height(120);
        drawRectangleRounded(gBox, 0.15f, 4, new Color().r((byte)16).g((byte)22).b((byte)38).a((byte)255));
        drawRectangleRoundedLines(gBox, 0.15f, 4, SKYBLUE);

        // Diagrama visual: [ Vértice Origen ] ──( Relación )──> [ Vértice Destino ]
        drawRectangleRounded(new Rectangle().x(cx + 20).y(cardY + 35).width(190).height(48), 0.2f, 4, fade(BLUE, 0.6f));
        drawLegibleText("⚪ VÉRTICE ORIGEN", cx + 30, cardY + 42, 10, GOLD);
        drawLegibleText("VERTEX_SRC_CLI", cx + 30, cardY + 60, 11, RAYWHITE);

        drawLine(cx + 215, cardY + 59, cx + 465, cardY + 59, GOLD);
        drawLegibleText("──[ EMITE_PAGO (Peso: 1.0) ]──►", cx + 225, cardY + 44, 11, YELLOW);

        drawRectangleRounded(new Rectangle().x(cx + 470).y(cardY + 35).width(190).height(48), 0.2f, 4, fade(GREEN, 0.6f));
        drawLegibleText("🟢 VÉRTICE DESTINO", cx + 480, cardY + 42, 10, GOLD);
        drawLegibleText("VERTEX_TGT_FAC", cx + 480, cardY + 60, 11, RAYWHITE);

        // Propiedades de la Arista
        int detY = cardY + 130;
        drawLegibleText("PROPIEDADES Y METADATOS DE LA ARISTA DE GRAFO:", cx, detY, 11, GOLD);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(8, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, detY + 20 + (i * 18), 10, SKYBLUE);
        }
    }

    // 4. Vista Adaptada: VECTOR (Embeddings Cosine 3D/HD)
    private void drawVectorView(int cx, int cy, int cw, int ch) {
        drawLegibleText("🧠 EMBEDDING VECTORIAL (Indexación Cosine 3D/HD HNSW):", cx, cy, 11, new Color().r((byte)0).g((byte)255).b((byte)230).a((byte)255));

        int vBoxY = cy + 24;
        Rectangle vBox = new Rectangle().x(cx).y(vBoxY).width(cw).height(100);
        drawRectangleRounded(vBox, 0.15f, 4, new Color().r((byte)16).g((byte)26).b((byte)38).a((byte)255));
        drawRectangleRoundedLines(vBox, 0.15f, 4, new Color().r((byte)0).g((byte)255).b((byte)230).a((byte)255));

        drawLegibleText("DIMENSIONES: 3D / 768D | MÉTRICA DE DISTANCIA: COSINE SIMILARITY | ÍNDICE: HNSW", cx + 16, vBoxY + 14, 11, GOLD);
        drawLegibleText("COORDENADAS: [ 0.2500, -0.7500, 0.4500 ] | CLUSTER ASIGNADO: Grupo_2", cx + 16, vBoxY + 36, 11, RAYWHITE);

        // Barra de Similitud Visual
        drawLegibleText("Similitud / Confianza: 98.5%", cx + 16, vBoxY + 60, 10, LIME);
        Rectangle barBg = new Rectangle().x(cx + 180).y(vBoxY + 60).width(300).height(16);
        drawRectangleRounded(barBg, 0.2f, 3, fade(DARKGRAY, 0.5f));
        drawRectangleRounded(new Rectangle().x(cx + 180).y(vBoxY + 60).width(280).height(16), 0.2f, 3, LIME);

        int detY = vBoxY + 112;
        drawLegibleText("PAYLOAD RAW DEL EMBEDDING:", cx, detY, 11, GOLD);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(8, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, detY + 20 + (i * 18), 10, new Color().r((byte)0).g((byte)255).b((byte)230).a((byte)255));
        }
    }

    // 5. Vista Adaptada: KEYVALUE
    private void drawKeyValueView(int cx, int cy, int cw, int ch) {
        drawLegibleText("🔑 ENTRADA CLAVE-VALOR (Memoria Nativa Panama Direct Memory):", cx, cy, 11, MAGENTA);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, cy + 28 + (i * 20), 11, MAGENTA);
        }
    }

    // 6. Vista Adaptada: TIMESERIES
    private void drawTimeSeriesView(int cx, int cy, int cw, int ch) {
        drawLegibleText("⏱️ MUESTRA DE SERIE TEMPORAL (Resolución en Nanosegundos):", cx, cy, 11, ORANGE);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, cy + 28 + (i * 20), 11, ORANGE);
        }
    }

    // 7. Vista Adaptada: GEOSPATIAL
    private void drawGeospatialView(int cx, int cy, int cw, int ch) {
        drawLegibleText("🌍 COORDENADAS GEOESPACIALES (Índice Espacial R-Tree):", cx, cy, 11, GREEN);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, cy + 28 + (i * 20), 11, GREEN);
        }
    }

    // 8. Vista Adaptada: COLUMNAR
    private void drawColumnarView(int cx, int cy, int cw, int ch) {
        drawLegibleText("📊 CHUNK COLUMNAR COMPRIMIDO (ZSTD / SIMD Aggregations):", cx, cy, 11, YELLOW);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, cy + 28 + (i * 20), 11, YELLOW);
        }
    }

    // 9. Vista Genérica
    private void drawGenericView(int cx, int cy, int cw, int ch) {
        drawLegibleText("📦 REGISTRO MULTIMODELO:", cx, cy, 11, RAYWHITE);
        String[] lines = selectedExplorerRecord.getDetails().split("\n");
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            drawLegibleText(lines[i], cx + 12, cy + 28 + (i * 18), 10, RAYWHITE);
        }
    }

    // SUB-MODAL 3: Administración de Índices (Crear, Editar, Eliminar)
    // =========================================================================
    private void openCreateIndexModal() {
        indexOpMode = "CREATE";
        formIndexName = "idx_" + selectedBucketName + "_field";
        formIndexField = "campo";
        formIndexType = "BTREE";
        formIndexUnique = false;
        formIndexActiveField = 1;
        showIndexOpModal = true;
    }

    private void openEditIndexModal() {
        if (selectedIndexInfo == null) return;
        indexOpMode = "EDIT";
        formIndexName = selectedIndexInfo.getName();
        formIndexField = selectedIndexInfo.getField();
        formIndexType = selectedIndexInfo.getType();
        formIndexUnique = selectedIndexInfo.isUnique();
        formIndexActiveField = 2;
        showIndexOpModal = true;
    }

    private void openDropIndexModal() {
        if (selectedIndexInfo == null) return;
        indexOpMode = "DROP_CONFIRM";
        formIndexName = selectedIndexInfo.getName();
        showIndexOpModal = true;
    }

    private void drawIndexOpModal(int sw, int sh) {
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.85f));

        int dw = 520;
        int dh = 380;
        int dx = (sw - dw) / 2;
        int dy = (sh - dh) / 2;

        drawRectangle(dx, dy, dw, dh, new Color().r((byte)20).g((byte)25).b((byte)40).a((byte)255));
        drawRectangleLines(dx, dy, dw, dh, GOLD);

        String title = indexOpMode.equals("CREATE") ? "➕ CREAR ÍNDICE DE ACCESO RÁPIDO"
                     : (indexOpMode.equals("EDIT") ? "✏️ RECONSTRUIR / EDITAR ÍNDICE" : "🗑️ CONFIRMAR DROP ÍNDICE");
        drawLegibleText(title, dx + 20, dy + 16, 13, GOLD);
        drawLegibleText("Bucket destino: <" + selectedExplorerDb + "><" + selectedEngineType + "><" + selectedBucketName + ">", dx + 20, dy + 36, 11, SKYBLUE);

        if (indexOpMode.equals("DROP_CONFIRM")) {
            drawLegibleText("¿Confirma la eliminación (DROP) del índice '" + formIndexName + "'?", dx + 30, dy + 110, 12, RAYWHITE);
            drawLegibleText("El motor " + selectedEngineType + " pasará a escaneo secuencial para este campo.", dx + 30, dy + 140, 11, LIGHTGRAY);

            if (guiButton(dx + 50, dy + 220, 180, 32, "🗑️ SÍ, DROP ÍNDICE", RED)) {
                EngineDataCatalog.getInstance().deleteIndex(selectedExplorerDb, selectedEngineType, selectedBucketName, formIndexName);
                selectedIndexInfo = null;
                showIndexOpModal = false;
            }
            if (guiButton(dx + 260, dy + 220, 160, 32, "CANCELAR", DARKGRAY)) {
                showIndexOpModal = false;
            }
            return;
        }

        drawLegibleText("Nombre del Índice:", dx + 20, dy + 68, 10, SKYBLUE);
        drawSimpleInputField(dx + 20, dy + 84, dw - 40, 26, formIndexName, formIndexActiveField == 1, false);
        if (checkCollisionPointRec(getMousePosition(), new Rectangle().x(dx + 20).y(dy + 84).width(dw - 40).height(26)) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formIndexActiveField = 1;
        }

        drawLegibleText("Campo destino en los registros:", dx + 20, dy + 118, 10, SKYBLUE);
        drawSimpleInputField(dx + 20, dy + 134, dw - 40, 26, formIndexField, formIndexActiveField == 2, false);
        if (checkCollisionPointRec(getMousePosition(), new Rectangle().x(dx + 20).y(dy + 134).width(dw - 40).height(26)) && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formIndexActiveField = 2;
        }

        drawLegibleText("Tipo de Índice Especializado:", dx + 20, dy + 168, 10, GOLD);
        String[] types = {"BTREE", "PRIMARY_HASH", "HNSW_VECTOR", "RTREE_SPATIAL", "BITMAP", "FULLTEXT"};
        int tyX = dx + 20;
        int tyY = dy + 188;
        for (String t : types) {
            boolean isCur = formIndexType.equalsIgnoreCase(t);
            Rectangle tRec = new Rectangle().x(tyX).y(tyY).width(75).height(22);
            boolean tHov = checkCollisionPointRec(getMousePosition(), tRec);
            drawRectangleRounded(tRec, 0.2f, 4, isCur ? fade(GOLD, 0.8f) : (tHov ? fade(SKYBLUE, 0.5f) : fade(DARKGRAY, 0.4f)));
            drawLegibleText(t, tyX + 4, tyY + 4, 8, isCur ? BLACK : RAYWHITE);
            if (tHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                formIndexType = t;
            }
            tyX += 80;
        }

        drawLegibleText("¿Índice Único?:", dx + 20, dy + 225, 10, SKYBLUE);
        Rectangle uRec = new Rectangle().x(dx + 120).y(dy + 222).width(80).height(22);
        boolean uHov = checkCollisionPointRec(getMousePosition(), uRec);
        drawRectangleRounded(uRec, 0.2f, 4, formIndexUnique ? fade(GREEN, 0.7f) : fade(DARKGRAY, 0.6f));
        drawLegibleText(formIndexUnique ? "SÍ (ÚNICO)" : "NO", (int)uRec.x() + 10, (int)uRec.y() + 4, 10, RAYWHITE);
        if (uHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            formIndexUnique = !formIndexUnique;
        }

        if (guiButton(dx + dw - 240, dy + dh - 50, 110, 32, "💾 GUARDAR", GREEN)) {
            if (indexOpMode.equals("CREATE")) {
                EngineIndexInfo idx = new EngineIndexInfo(formIndexName, selectedEngineType, selectedBucketName, formIndexField, formIndexType, formIndexUnique, 100_000L);
                EngineDataCatalog.getInstance().addIndex(selectedExplorerDb, selectedEngineType, selectedBucketName, idx);
                selectedIndexInfo = idx;
            } else {
                EngineDataCatalog.getInstance().updateIndex(selectedExplorerDb, selectedEngineType, selectedBucketName, formIndexName, formIndexField, formIndexType, formIndexUnique);
            }
            showIndexOpModal = false;
        }

        if (guiButton(dx + dw - 120, dy + dh - 50, 100, 32, "CANCELAR", DARKGRAY)) {
            showIndexOpModal = false;
        }
    }

    // =========================================================================
    // SUB-MODAL 4: Ayuda de Búsqueda y Condiciones (JettraQL & JettraSQL)
    // =========================================================================
    private void drawQueryHelpModal(int sw, int sh) {
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.85f));

        int dw = 700;
        int dh = 500;
        int dx = (sw - dw) / 2;
        int dy = (sh - dh) / 2;

        drawRectangle(dx, dy, dw, dh, new Color().r((byte)18).g((byte)22).b((byte)36).a((byte)255));
        drawRectangleLines(dx, dy, dw, dh, GOLD);

        drawLegibleText("❓ GUÍA DE CONDICIONES Y BÚSQUEDA (JettraQL & JettraSQL)", dx + 20, dy + 16, 14, GOLD);
        drawLegibleText("Sintaxis de consultas para el motor multimodelo de JettraStore:", dx + 20, dy + 36, 11, SKYBLUE);

        int cy = dy + 62;
        drawLegibleText("1. Sintaxis JettraSQL:", dx + 20, cy, 11, GOLD);
        drawLegibleText("   • SELECT * FROM <bucket> WHERE <campo> <operador> <valor>", dx + 20, cy + 18, 10, LIGHTGRAY);
        drawLegibleText("   • Ejemplo: SELECT * FROM facturas WHERE total > 500", dx + 20, cy + 34, 10, LIME);
        if (guiButton(dx + dw - 170, cy + 24, 140, 22, "PROBAR EJEMPLO", BLUE)) {
            explorerQueryIsSql = true;
            explorerSearchQuery = "SELECT * FROM facturas WHERE total > 500";
            showExplorerQueryHelp = false;
            executeExplorerQuery();
        }

        cy += 60;
        drawLegibleText("2. Sintaxis JettraQL:", dx + 20, cy, 11, GOLD);
        drawLegibleText("   • FROM <bucket> WHERE <campo> = <valor>", dx + 20, cy + 18, 10, LIGHTGRAY);
        drawLegibleText("   • FIND WHERE estado == 'TIMBRADO_VALIDADO'", dx + 20, cy + 34, 10, LIME);
        drawLegibleText("   • MATCH (source)-[EMITE_PAGO]->(target) [Para Grafos]", dx + 20, cy + 50, 10, SKYBLUE);
        drawLegibleText("   • VECTOR SIMILARITY [0.12, 0.45, 0.78] TOP 5 [Para Vectores]", dx + 20, cy + 66, 10, MAGENTA);
        if (guiButton(dx + dw - 170, cy + 24, 140, 22, "PROBAR JQL", PURPLE)) {
            explorerQueryIsSql = false;
            explorerSearchQuery = "FROM facturas WHERE total > 200";
            showExplorerQueryHelp = false;
            executeExplorerQuery();
        }

        cy += 95;
        drawLegibleText("3. Operadores de Condición Soportados:", dx + 20, cy, 11, GOLD);
        drawLegibleText("   • Comparación: =, ==, !=, >, >=, <, <=", dx + 20, cy + 18, 10, RAYWHITE);
        drawLegibleText("   • Coincidencia de texto: LIKE 'texto' (búsqueda parcial insensible a mayúsculas)", dx + 20, cy + 34, 10, RAYWHITE);
        drawLegibleText("   • Búsqueda libre: Cualquier término o ID filtra directamente el contenido JSON/payload.", dx + 20, cy + 50, 10, RAYWHITE);

        if (guiButton(dx + dw - 120, dy + dh - 42, 100, 28, "ENTENDIDO", DARKGRAY)) {
            showExplorerQueryHelp = false;
        }
    }


    // =========================================================================
    // PANEL DE RESPALDOS Y RESTAURACIÓN (BACKUP & RESTORE)
    // =========================================================================
    private void drawBackupModal(int sw, int sh) {
        drawRectangle(0, 0, sw, sh, fade(BLACK, 0.80f));

        int mw = 940;
        int mh = 560;
        int mx = (sw - mw) / 2;
        int my = (sh - mh) / 2;

        drawRectangle(mx, my, mw, mh, new Color().r((byte)16).g((byte)22).b((byte)36).a((byte)255));
        drawRectangleLines(mx, my, mw, mh, GOLD);

        // Barra de Título
        drawRectangle(mx, my, mw, 45, new Color().r((byte)24).g((byte)32).b((byte)50).a((byte)255));
        drawLine(mx, my + 45, mx + mw, my + 45, GOLD);

        drawLegibleText("💾 PANEL DE RESPALDOS Y RESTAURACIÓN (BACKUP & RESTORE)", mx + 16, my + 14, 13, GOLD);
        drawLegibleText("JettraStore Zero-Set Archiving Engine (Compresión SIMD + Verificación SHA-256)", mx + 490, my + 16, 10, SKYBLUE);

        // Botón Cerrar (X)
        Rectangle closeBtnRec = new Rectangle().x(mx + mw - 36).y(my + 10).width(26).height(26);
        boolean closeHover = checkCollisionPointRec(getMousePosition(), closeBtnRec);
        drawRectangleRounded(closeBtnRec, 0.2f, 4, closeHover ? RED : DARKGRAY);
        drawLegibleText("X", (int)closeBtnRec.x() + 8, (int)closeBtnRec.y() + 5, 14, RAYWHITE);
        if (closeHover && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            showBackupModal = false;
            return;
        }

        // Pestañas: [ 💾 CREAR RESPALDO ] / [ ♻️ RESTAURAR RESPALDO ]
        int tabY = my + 54;
        Rectangle tabCrear = new Rectangle().x(mx + 20).y(tabY).width(220).height(26);
        boolean tCrearHov = checkCollisionPointRec(getMousePosition(), tabCrear);
        drawRectangleRounded(tabCrear, 0.2f, 4, (backupActiveTab == 0) ? fade(GOLD, 0.85f) : (tCrearHov ? fade(SKYBLUE, 0.5f) : fade(DARKGRAY, 0.4f)));
        drawLegibleText("💾 CREAR RESPALDO", (int)tabCrear.x() + 24, (int)tabCrear.y() + 6, 11, (backupActiveTab == 0) ? BLACK : RAYWHITE);
        if (tCrearHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            backupActiveTab = 0;
        }

        Rectangle tabRestaurar = new Rectangle().x(mx + 250).y(tabY).width(240).height(26);
        boolean tRestHov = checkCollisionPointRec(getMousePosition(), tabRestaurar);
        drawRectangleRounded(tabRestaurar, 0.2f, 4, (backupActiveTab == 1) ? fade(GOLD, 0.85f) : (tRestHov ? fade(SKYBLUE, 0.5f) : fade(DARKGRAY, 0.4f)));
        drawLegibleText("♻️ RESTAURAR RESPALDO", (int)tabRestaurar.x() + 24, (int)tabRestaurar.y() + 6, 11, (backupActiveTab == 1) ? BLACK : RAYWHITE);
        if (tRestHov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
            backupActiveTab = 1;
        }

        // Feedback / Notificación
        BackupManager bm = BackupManager.getInstance();
        if (bm.getBackupFeedbackTimer() > 0) {
            bm.decrementFeedbackTimer(0.016f);
            drawLegibleText(bm.getLastBackupFeedback(), mx + 505, tabY + 6, 10, LIME);
        }

        drawLine(mx + 20, my + 88, mx + mw - 20, my + 88, fade(GRAY, 0.35f));

        if (backupActiveTab == 0) {
            drawCreateBackupView(mx + 20, my + 98, mw - 40, mh - 110, bm);
        } else {
            drawRestoreBackupView(mx + 20, my + 98, mw - 40, mh - 110, bm);
        }
    }

    private void drawCreateBackupView(int bx, int by, int bw, int bh, BackupManager bm) {
        drawLegibleText("SELECCIÓN DE ORIGEN Y CONFIGURACIÓN DEL RESPALDO:", bx, by, 11, GOLD);

        // 1. Selector de Base de Datos
        drawLegibleText("Base de Datos a Respaldar:", bx, by + 24, 10, SKYBLUE);
        String[] dbs = {"example_factura_db", "samples_hostipal_db", "samples_ambiental_db", "system_metadata_db"};
        int dbX = bx;
        for (String db : dbs) {
            boolean isSel = backupSelectedDb.equalsIgnoreCase(db);
            Rectangle r = new Rectangle().x(dbX).y(by + 42).width(210).height(24);
            boolean hov = checkCollisionPointRec(getMousePosition(), r);
            drawRectangleRounded(r, 0.2f, 4, isSel ? fade(BLUE, 0.8f) : (hov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.3f)));
            drawRectangleRoundedLines(r, 0.2f, 4, isSel ? GOLD : DARKGRAY);
            drawLegibleText("🗄️ " + db, dbX + 8, by + 47, 10, isSel ? GOLD : RAYWHITE);
            if (hov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                backupSelectedDb = db;
            }
            dbX += 220;
        }

        // 2. Selector de Motor o Snapshot Multimodelo Completo
        drawLegibleText("Alcance del Respaldo (Engine):", bx, by + 80, 10, SKYBLUE);
        String[] engines = {"MULTIMODEL_SNAPSHOT", "DOCUMENT", "JAVA_RECORD", "GRAPH", "VECTOR"};
        int engX = bx;
        for (String eng : engines) {
            boolean isSel = backupSelectedEngine.equalsIgnoreCase(eng);
            Rectangle r = new Rectangle().x(engX).y(by + 98).width(168).height(24);
            boolean hov = checkCollisionPointRec(getMousePosition(), r);
            drawRectangleRounded(r, 0.2f, 4, isSel ? fade(PURPLE, 0.8f) : (hov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.3f)));
            drawRectangleRoundedLines(r, 0.2f, 4, isSel ? GOLD : DARKGRAY);
            drawLegibleText(eng, engX + 8, by + 103, 9, isSel ? GOLD : RAYWHITE);
            if (hov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                backupSelectedEngine = eng;
            }
            engX += 176;
        }

        // 3. Algoritmo de Compresión
        drawLegibleText("Algoritmo de Compresión y Empaquetado:", bx, by + 136, 10, SKYBLUE);
        String[] algos = {"ZSTD_SIMD", "LZ4_PANAMA", "GZIP", "RAW_ZEROSET"};
        int algX = bx;
        for (String al : algos) {
            boolean isSel = backupSelectedAlgo.equalsIgnoreCase(al);
            Rectangle r = new Rectangle().x(algX).y(by + 154).width(210).height(24);
            boolean hov = checkCollisionPointRec(getMousePosition(), r);
            drawRectangleRounded(r, 0.2f, 4, isSel ? fade(GREEN, 0.8f) : (hov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.3f)));
            drawRectangleRoundedLines(r, 0.2f, 4, isSel ? GOLD : DARKGRAY);
            String desc = al.equals("ZSTD_SIMD") ? " (Alto Ratio 3.4x)" : (al.equals("LZ4_PANAMA") ? " (Ultra Rápido)" : "");
            drawLegibleText("📦 " + al + desc, algX + 8, by + 159, 10, isSel ? BLACK : RAYWHITE);
            if (hov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                backupSelectedAlgo = al;
            }
            algX += 220;
        }

        // 4. Panel de Estimación y Parámetros
        int cardY = by + 196;
        Rectangle estBox = new Rectangle().x(bx).y(cardY).width(bw).height(170);
        drawRectangleRounded(estBox, 0.1f, 4, new Color().r((byte)12).g((byte)16).b((byte)28).a((byte)255));
        drawRectangleRoundedLines(estBox, 0.1f, 4, GOLD);

        drawLegibleText("📊 ESTIMACIÓN TÉCNICA DEL SNAPSHOT:", bx + 14, cardY + 12, 11, GOLD);
        drawLegibleText("• Base de Datos Destino: " + backupSelectedDb.toUpperCase(), bx + 14, cardY + 34, 10, RAYWHITE);
        drawLegibleText("• Alcance: " + backupSelectedEngine + " | Modo: Streaming Zero-Set Off-Heap", bx + 14, cardY + 54, 10, SKYBLUE);
        drawLegibleText("• Destino Físico: ~/jettra/backups/" + backupSelectedDb + "_snapshot.jbk", bx + 14, cardY + 74, 10, LIGHTGRAY);
        drawLegibleText("• Integridad: Criptográfico SHA-256 generado al vuelo con punteros Panama FFM.", bx + 14, cardY + 94, 10, LIME);
        drawLegibleText("• Tolerancia a Fallos: Compatible con restauración en caliente sin reiniciar el clúster.", bx + 14, cardY + 114, 10, YELLOW);
        drawLegibleText("• Cero Presión GC: Serialización binaria directa en memoria nativa sin copias al heap.", bx + 14, cardY + 134, 10, SKYBLUE);

        // Botón de Ejecución
        int execY = by + bh - 50;
        if (guiButton(bx, execY, 280, 36, "💾 EJECUTAR RESPALDO AHORA", GREEN)) {
            bm.createBackup(backupSelectedDb, backupSelectedEngine, backupSelectedAlgo, null);
            if (policeMonitor != null) {
                policeMonitor.triggerNodeTransfer("node-01", "node-02", ClusterDataTraffic.TrafficType.SSTABLE_COMPACTION,
                    "Creando Respaldo: " + backupSelectedDb, 350_000_000L, 9.5f);
            }
        }
    }

    private void drawRestoreBackupView(int bx, int by, int bw, int bh, BackupManager bm) {
        drawLegibleText("LISTA DE RESPALDOS DISPONIBLES EN EL ALMACENAMIENTO:", bx, by, 11, GOLD);

        List<BackupSnapshot> snaps = bm.getSnapshots();
        int tableY = by + 22;

        // Encabezados
        drawRectangle(bx, tableY, bw, 24, new Color().r((byte)24).g((byte)32).b((byte)52).a((byte)255));
        drawRectangleLines(bx, tableY, bw, 24, DARKGRAY);
        drawLegibleText("ID SNAPSHOT", bx + 6, tableY + 5, 9, GOLD);
        drawLegibleText("BASE DE DATOS", bx + 170, tableY + 5, 9, GOLD);
        drawLegibleText("ALCANCE", bx + 320, tableY + 5, 9, GOLD);
        drawLegibleText("FECHA", bx + 450, tableY + 5, 9, GOLD);
        drawLegibleText("OBJETOS", bx + 570, tableY + 5, 9, GOLD);
        drawLegibleText("TAMAÑO", bx + 650, tableY + 5, 9, GOLD);
        drawLegibleText("COMPRESIÓN", bx + 730, tableY + 5, 9, GOLD);
        drawLegibleText("ESTADO", bx + 830, tableY + 5, 9, GOLD);

        int rowY = tableY + 28;
        int rowH = 26;
        for (BackupSnapshot s : snaps) {
            boolean isSel = backupSelectedSnapshotId.equalsIgnoreCase(s.id());
            Rectangle r = new Rectangle().x(bx).y(rowY).width(bw).height(rowH);
            boolean hov = checkCollisionPointRec(getMousePosition(), r);

            drawRectangleRounded(r, 0.1f, 4, isSel ? fade(BLUE, 0.7f) : (hov ? fade(DARKGRAY, 0.5f) : fade(BLACK, 0.25f)));
            drawRectangleRoundedLines(r, 0.1f, 4, isSel ? GOLD : fade(GRAY, 0.3f));

            drawLegibleText("🏷️ " + s.id(), bx + 6, rowY + 6, 9, isSel ? GOLD : RAYWHITE);
            drawLegibleText(s.databaseName(), bx + 170, rowY + 6, 9, SKYBLUE);
            drawLegibleText(s.engineType(), bx + 320, rowY + 6, 9, LIME);
            drawLegibleText(s.timestamp(), bx + 450, rowY + 6, 9, LIGHTGRAY);
            drawLegibleText(String.format("%,d", s.totalObjects()), bx + 570, rowY + 6, 9, RAYWHITE);
            drawLegibleText(s.getFormattedSize(), bx + 650, rowY + 6, 9, YELLOW);
            drawLegibleText(s.compressionAlgo() + " (" + String.format("%.1f", s.compressionRatio()) + "x)", bx + 730, rowY + 6, 9, MAGENTA);
            drawLegibleText(s.status(), bx + 830, rowY + 6, 9, LIME);

            if (hov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                backupSelectedSnapshotId = s.id();
                backupTargetRestoreDb = s.databaseName();
            }
            rowY += rowH + 4;
        }

        // Panel de Opciones de Restauración
        int optY = by + bh - 130;
        drawRectangle(bx, optY, bw, 120, new Color().r((byte)12).g((byte)16).b((byte)28).a((byte)255));
        drawRectangleLines(bx, optY, bw, 120, GOLD);

        drawLegibleText("PARÁMETROS DE RESTAURACIÓN:", bx + 14, optY + 10, 10, GOLD);
        drawLegibleText("Snapshot Seleccionado: " + backupSelectedSnapshotId + " | Restaurar hacia Base de Datos:", bx + 14, optY + 30, 10, SKYBLUE);

        // Selector rápido de BD destino
        int rdbX = bx + 14;
        String[] rdbs = {"example_factura_db", "samples_hostipal_db", "samples_ambiental_db", "system_metadata_db"};
        for (String rdb : rdbs) {
            boolean isSel = backupTargetRestoreDb.equalsIgnoreCase(rdb);
            Rectangle r = new Rectangle().x(rdbX).y(optY + 48).width(190).height(22);
            boolean hov = checkCollisionPointRec(getMousePosition(), r);
            drawRectangleRounded(r, 0.2f, 3, isSel ? fade(BLUE, 0.8f) : fade(BLACK, 0.3f));
            drawRectangleRoundedLines(r, 0.2f, 3, isSel ? GOLD : DARKGRAY);
            drawLegibleText("🗄️ " + rdb, rdbX + 6, optY + 52, 9, isSel ? GOLD : RAYWHITE);
            if (hov && isMouseButtonPressed(MOUSE_BUTTON_LEFT)) {
                backupTargetRestoreDb = rdb;
            }
            rdbX += 200;
        }

        // Botones de Acción
        if (guiButton(bx + 14, optY + 80, 260, 30, "♻️ RESTAURAR SNAPSHOT AHORA", LIME)) {
            bm.restoreBackup(backupSelectedSnapshotId, backupTargetRestoreDb, backupOverwrite);
            if (policeMonitor != null) {
                policeMonitor.triggerNodeTransfer("node-02", "node-01", ClusterDataTraffic.TrafficType.SSTABLE_COMPACTION,
                    "Restaurando Respaldo: " + backupSelectedSnapshotId, 380_000_000L, 9.5f);
            }
        }

        if (guiButton(bx + 290, optY + 80, 200, 30, "🗑️ ELIMINAR SNAPSHOT", RED)) {
            bm.deleteSnapshot(backupSelectedSnapshotId);
        }
    }

}

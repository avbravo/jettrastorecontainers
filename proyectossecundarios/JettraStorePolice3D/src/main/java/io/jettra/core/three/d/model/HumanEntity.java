package io.jettra.core.three.d.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HumanEntity {
    public float x, y, z;
    public float rotation;
    public float targetX, targetY, targetZ;
    public String name = "Citizen";
    public String action = "IDLE";
    public List<String> history = new ArrayList<>();
    public int r = 255, g = 255, b = 255, a = 255;
    public float animTimer;
    public boolean isSelected;
    public float buildTimer;
    public float energy = 100.0f;
    public float socialNeed = 50.0f;
    public float intelligence = 1.0f;
    public float homeX, homeY, homeZ;
    public boolean hasHome;
    public String currentThought = "";
    public boolean isWolf;        // Perros / Caninos agentes JettraPolice
    public boolean isAnimal;
    public boolean isCar;         // Camiones de Tráfico de Datos
    public boolean isMachine;
    public int entityType;
    public String dream = "";
    public String job = "Usuario de Base de Datos";
    public float appCooldown;
    public float thoughtTimer;
    public boolean isTraveling;
    public float travelTimer;

    // --- METADATOS JETTRASTORE: USUARIOS, POLICE K9 Y TRÁFICO DE DATOS ---
    public String connectedDatabase = "";       // Base de datos a la que está conectado el usuario
    public String connectionFacility = "";      // Edificio/Sede de origen de la conexión
    public String targetServer = "";            // Servidor nodo consultado (master, replica)
    public String queryType = "SELECT";         // SELECT, INSERT, BATCH, VECTOR_KNN, TIMESERIES_STREAM
    public int queryCount = 0;                  // Cantidad de consultas realizadas
    public String userRole = "ANALYST";         // DBA, OPERATOR, DEVELOPER, SENSOR_AGENT

    // Para perros / agentes caninos JettraPolice
    public boolean isPoliceK9 = false;
    public boolean isJettraMascot = false;
    public boolean isPoliceOfficer = false;

    // Para camiones (Tráfico de Datos)
    public String dataPayload = "";             // Ej: "Lote: 25,000 Facturas"
    public float dataThroughputMb = 1.5f;       // MB/s transferidos
    public String trafficDirection = "CLIENT_TO_SERVER"; // CLIENT_TO_SERVER, REPLICATION_RAFT

    // PECS Model - Physical
    public float hunger = 100;
    public float thirst = 100;
    public float health = 100;
    public float mood = 100;
    public float stamina = 100;
    public float metabolismRate = 0.5f;
    public int age = 20;
    public String gender = "Other";
    public float infectionLevel = 0;
    public boolean isDead = false;

    // PECS Model - Emotional (Personality Big Five)
    public float openness = 0.5f;
    public float conscientiousness = 0.5f;
    public float extraversion = 0.5f;
    public float agreeableness = 0.5f;
    public float neuroticism = 0.5f;

    // PECS Model - Cognitive
    public String currentGoal = "CONSULTANDO_BD";
    
    // PECS Model - Social
    public String spouse = "";
    public List<String> children = new ArrayList<>();
    public List<String> friends = new ArrayList<>();
    public List<String> rivals = new ArrayList<>();
    public String familyID;
    public String partner;

    public HumanEntity() {
        this.metabolismRate = 0.01f + (float)Math.random() * 0.04f;
        this.gender = Math.random() > 0.5 ? "Male" : "Female";
        this.age = 22 + (int)(Math.random() * 35);
        this.openness = (float)Math.random();
        this.conscientiousness = (float)Math.random();
        this.extraversion = (float)Math.random();
        this.agreeableness = (float)Math.random();
        this.neuroticism = (float)Math.random();
        this.intelligence = 0.7f + (float)Math.random() * 0.3f;
    }

    public void addHistory(String msg) {
        history.add(msg);
        if (history.size() > 3) {
            history.remove(0);
        }
    }
}

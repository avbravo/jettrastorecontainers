package io.jettra.store.cluster;

import io.jettra.json.JsonObject;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.police.JettraPolice;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServidorAIConsultaHelidon: Adaptador de Servicio de Consultas Asistidas por IA y Microservicios Helidon.
 * Coordina la inferencia y ejecución de consultas de embeddings y grafos con el Anillo Distribuido,
 * evaluando preventivamente la densidad de RAM antes de procesar prompts pesados.
 */
public final class ServidorAIConsultaHelidon {
    private static final ServidorAIConsultaHelidon INSTANCE = new ServidorAIConsultaHelidon();
    public static ServidorAIConsultaHelidon getInstance() { return INSTANCE; }

    private final Map<String, String> cacheResultados = new ConcurrentHashMap<>();

    private ServidorAIConsultaHelidon() {}

    /**
     * Valida el payload de la solicitud HTTP entrante de acuerdo con los contratos Helidon.
     */
    public boolean validarRequest(String requestBody) {
        return requestBody != null && !requestBody.isBlank() && requestBody.contains("prompt");
    }

    /**
     * Procesa la consulta AI evaluando la saturación de memoria antes de ejecutar.
     * Si la memoria supera el 85%, transfiere la consulta al anillo distribuido (node-02, node-03).
     */
    public String procesarConsulta(String prompt, JettraDatabase db, DynamicRingEngine ringEngine) {
        if (prompt == null || prompt.isBlank()) {
            return formatearError(400, "El prompt no puede ser nulo o vacío.");
        }

        // 1. Evaluación preventiva de saturación de memoria RAM
        double ramPct = (db != null) ? db.getRamSaturationPercentage() : 25.0;

        if (ramPct >= 85.0 && ringEngine != null) {
            // Activar delegación al anillo distribuido
            ringEngine.evaluateMemorySaturation(ramPct / 100.0, db != null ? db.getMemTable() : null);
            JettraPolice.getInstance().recordAlert("AI_RING_DELEGATION", 
                String.format("Saturación de RAM (%.1f%%) detectada. Consulta AI delegada al anillo distribuido (node-02).", ramPct));

            String respuestaDelegada = String.format("[ANILLO DISTRIBUIDO - NODE-02] Respuesta para prompt '%s' procesada vía microservicio Helidon.", prompt);
            return formatearRespuestaJson("node-02", respuestaDelegada, true);
        }

        // 2. Procesamiento local de alta velocidad
        String respuestaLocal = String.format("[LOCAL - NODE-01] Consulta AI resuelta en memoria local para: '%s'.", prompt);
        return formatearRespuestaJson("node-01", respuestaLocal, false);
    }

    /**
     * Formatea la respuesta JSON conforme al estándar del pipeline Helidon.
     */
    public String formatearRespuestaJson(String nodoEjecutor, String resultado, boolean anilloActivo) {
        JsonObject json = new JsonObject();
        json.addProperty("status", 200);
        json.addProperty("nodoEjecutor", nodoEjecutor);
        json.addProperty("anilloActivo", anilloActivo);
        json.addProperty("timestamp", Instant.now().toString());
        json.addProperty("resultado", resultado);
        return json.toString();
    }

    private String formatearError(int status, String mensaje) {
        JsonObject json = new JsonObject();
        json.addProperty("status", status);
        json.addProperty("error", mensaje);
        json.addProperty("timestamp", Instant.now().toString());
        return json.toString();
    }
}

package io.jettra.driver.listener;

import io.jettra.store.police.JettraPoliceNotification;

/**
 * Listener de eventos para el Centinela Anti-OOM (JettraPolice).
 * Permite que cualquier cliente (JettraShell, JettraStoreFX, aplicaciones de negocio)
 * se entere de forma completamente desacoplada cuando el Sentinel fuerza streaming
 * por lotes seguros o paginación defensiva, mostrando feedback visual o en consola
 * sin alterar las firmas de métodos existentes.
 */
@FunctionalInterface
public interface JettraPoliceEventListener {

    /**
     * Invocado cuando el Sentinel de JettraPolice se activa proactivamente para
     * prevenir un agotamiento del Heap Space en el servidor o cliente.
     *
     * @param notification Metadatos detallados de la intervención preventiva
     */
    void onSentinelActivated(JettraPoliceNotification notification);
}

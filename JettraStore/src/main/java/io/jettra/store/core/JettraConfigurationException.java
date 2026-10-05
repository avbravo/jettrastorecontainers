package io.jettra.store.core;

/**
 * Excepción lanzada cuando la configuración de JettraStore en database.properties
 * o jettra.config no es válida o presenta incompatibilidades críticas con la topología del clúster.
 */
public class JettraConfigurationException extends RuntimeException {
    public JettraConfigurationException(String message) {
        super(message);
    }

    public JettraConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

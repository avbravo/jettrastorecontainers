package io.jettra.example.se.model;

import io.jettra.store.core.JettraId;

import java.util.Map;

/**
 * Modelo flexible (Java record) para el Bucket sin estructura fija ({@code NONE})
 * {@code NotasLibres} en {@code examplesedb}, identificado por {@link JettraId}.
 */
public record NotaLibre(
        JettraId jettraId,
        Map<String, Object> atributos
) {
    /** Constructor de conveniencia para nuevos registros cuyo {@link JettraId} será autogenerado al guardar. */
    public NotaLibre(Map<String, Object> atributos) {
        this(null, atributos);
    }

    public NotaLibre withJettraId(JettraId id) {
        return new NotaLibre(id, atributos);
    }
}

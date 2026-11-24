package io.jettra.example.se.model;

import io.jettra.rules.validations.NotBlank;
import io.jettra.rules.validations.Size;
import io.jettra.store.core.JettraId;

/**
 * Modelo de dominio inmutable (Java record) para el Bucket estructurado ({@code DEFINED}) {@code Pais}
 * en el engine {@code JAVA_RECORD} de {@code examplesedb}, identificado por {@link JettraId}.
 */
public record Pais(
        JettraId jettraId,
        Long codigo,
        @NotBlank(message = "El nombre del país es obligatorio")
        String name,
        @NotBlank(message = "Las siglas del país son obligatorias")
        @Size(min = 2, max = 3, message = "Las siglas deben tener entre 2 y 3 caracteres")
        String siglas
) {
    /** Constructor de conveniencia para nuevos registros cuyo {@link JettraId} será autogenerado al guardar. */
    public Pais(Long codigo, String name, String siglas) {
        this(null, codigo, name, siglas);
    }

    public Pais withJettraId(JettraId id) {
        return new Pais(id, codigo, name, siglas);
    }
}

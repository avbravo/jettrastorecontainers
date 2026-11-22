package io.jettra.example.se.model;

import io.jettra.rules.validations.NotBlank;
import io.jettra.rules.validations.Size;

/**
 * Modelo de dominio inmutable (Java record) para el bucket estructurado {@code Pais}
 * en el engine {@code JAVA_RECORD} de {@code examplesedb}.
 */
public record Pais(
        Long codigo,
        @NotBlank(message = "El nombre del país es obligatorio")
        String name,
        @NotBlank(message = "Las siglas del país son obligatorias")
        @Size(min = 2, max = 3, message = "Las siglas deben tener entre 2 y 3 caracteres")
        String siglas
) {}

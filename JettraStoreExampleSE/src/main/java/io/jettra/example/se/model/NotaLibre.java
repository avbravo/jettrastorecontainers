package io.jettra.example.se.model;

import java.util.Map;

/**
 * Modelo flexible (Java record) para el bucket no estructurado ({@code NONE})
 * {@code NotasLibres} en {@code examplesedb}.
 */
public record NotaLibre(
        String id,
        Map<String, Object> atributos
) {}

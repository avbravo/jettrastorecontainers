package io.jettra.example.se.model;

import io.jettra.rules.validations.NotBlank;
import io.jettra.rules.validations.Positive;

/**
 * Modelo de dominio inmutable (Java record) para el bucket estructurado {@code Producto}
 * en el engine {@code DOCUMENT} de {@code examplesedb}, con campo autoincrementable {@code sku}
 * y campo numérico indexado {@code precio}.
 */
public record Producto(
        Long sku,
        @NotBlank(message = "El nombre del producto es obligatorio")
        String nombre,
        @NotBlank(message = "La categoría es obligatoria")
        String categoria,
        @Positive(message = "El precio debe ser mayor que cero")
        double precio
) {}

package io.jettra.example.se.model;

import io.jettra.rules.validations.NotBlank;
import io.jettra.rules.validations.Positive;
import io.jettra.store.core.JettraId;

/**
 * Modelo de dominio inmutable (Java record) para el Bucket estructurado ({@code DEFINED}) {@code Producto}
 * en el engine {@code DOCUMENT} de {@code examplesedb}, identificado por {@link JettraId},
 * con campo autoincrementable {@code sku} y campo numérico indexado {@code precio}.
 */
public record Producto(
        JettraId jettraId,
        Long sku,
        @NotBlank(message = "El nombre del producto es obligatorio")
        String nombre,
        @NotBlank(message = "La categoría es obligatoria")
        String categoria,
        @Positive(message = "El precio debe ser mayor que cero")
        double precio
) {
    /** Constructor de conveniencia para nuevos registros cuyo {@link JettraId} será autogenerado al guardar. */
    public Producto(Long sku, String nombre, String categoria, double precio) {
        this(null, sku, nombre, categoria, precio);
    }

    public Producto withJettraId(JettraId id) {
        return new Producto(id, sku, nombre, categoria, precio);
    }
}

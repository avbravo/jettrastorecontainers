package io.jettra.example.se.repository;

import io.jettra.store.core.JettraId;

import java.util.List;
import java.util.Optional;

/**
 * Contrato genérico del Patrón Repository para entidades Java {@code record}
 * almacenadas en Buckets de JettraStore e identificadas por {@link JettraId}.
 */
public interface JettraRepository<T> {
    T save(T entity);
    T update(JettraId jettraId, T entity);
    Optional<T> findById(JettraId jettraId);
    List<T> findAll();
    boolean deleteById(JettraId jettraId);
}

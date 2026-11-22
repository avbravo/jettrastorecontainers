package io.jettra.example.se.repository;

import java.util.List;
import java.util.Optional;

/**
 * Contrato genérico del Patrón Repository para entidades Java {@code record} en JettraStore.
 */
public interface JettraRepository<T, ID> {
    String save(String documentId, T entity);
    Optional<T> findById(ID id);
    List<T> findAll();
    boolean deleteById(ID id);
}

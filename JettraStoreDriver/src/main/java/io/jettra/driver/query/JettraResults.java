package io.jettra.driver.query;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Contenedor inmutable y fuertemente tipado de resultados retornados por JettraFluentQuery.
 * Compatible con la API Stream, iteración foreach y deconstructores de Java 25.
 *
 * @param <T> Tipo del registro o entidad retornada
 */
public final class JettraResults<T> implements Iterable<T> {
    private final List<T> items;
    private final String summary;
    private final long durationMs;

    public JettraResults(List<T> items, String summary, long durationMs) {
        this.items = (items != null) ? List.copyOf(items) : List.of();
        this.summary = (summary != null) ? summary : "";
        this.durationMs = durationMs;
    }

    public List<T> toList() {
        return items;
    }

    public Stream<T> stream() {
        return items.stream();
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Optional<T> first() {
        return items.isEmpty() ? Optional.empty() : Optional.of(items.getFirst());
    }

    public T firstOrNull() {
        return items.isEmpty() ? null : items.getFirst();
    }

    public String summary() {
        return summary;
    }

    public long durationMs() {
        return durationMs;
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        items.forEach(action);
    }

    @Override
    public String toString() {
        return "JettraResults[size=" + items.size() + ", durationMs=" + durationMs + ", summary='" + summary + "']";
    }
}

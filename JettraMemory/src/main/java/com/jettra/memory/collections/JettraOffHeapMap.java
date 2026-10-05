package com.jettra.memory.collections;

import com.jettra.memory.engine.DiskStorageEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;

/**
 * Adaptador de colección Map Off-Heap para el ecosistema JettraCollections.
 * Permite manipular los datos almacenados en disco exactamente como si fuera un {@link java.util.Map},
 * pero manteniendo los datos fuera del Heap de la JVM para evitar la presión del Garbage Collector.
 *
 * @param <K> Tipo de la clave
 * @param <V> Tipo del valor
 */
public final class JettraOffHeapMap<K, V> implements Map<K, V> {

    private final String mapNamespace;
    private final DiskStorageEngine storageEngine;
    private final Function<K, String> keyEncoder;
    private final Function<String, K> keyDecoder;
    private final Function<V, byte[]> valueSerializer;
    private final Function<byte[], V> valueDeserializer;

    public JettraOffHeapMap(
            String mapNamespace,
            DiskStorageEngine storageEngine,
            Function<K, String> keyEncoder,
            Function<String, K> keyDecoder,
            Function<V, byte[]> valueSerializer,
            Function<byte[], V> valueDeserializer) {
        this.mapNamespace = mapNamespace != null ? mapNamespace : "default";
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine requerido");
        this.keyEncoder = keyEncoder != null ? keyEncoder : Object::toString;
        this.keyDecoder = keyDecoder != null ? keyDecoder : k -> (K) k;
        this.valueSerializer = valueSerializer != null ? valueSerializer : JettraOffHeapMap::defaultSerialize;
        this.valueDeserializer = valueDeserializer != null ? valueDeserializer : JettraOffHeapMap::defaultDeserialize;
    }

    private String qualifyKey(Object key) {
        return mapNamespace + ":" + keyEncoder.apply((K) key);
    }

    private String extractRawKey(String qualifiedKey) {
        if (qualifiedKey.startsWith(mapNamespace + ":")) {
            return qualifiedKey.substring((mapNamespace + ":").length());
        }
        return qualifiedKey;
    }

    @Override
    public int size() {
        int count = 0;
        for (String key : storageEngine.getIndexManager().getAllLiveEntries().keySet()) {
            if (key.startsWith(mapNamespace + ":")) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return storageEngine.containsKey(qualifyKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        byte[] target = valueSerializer.apply((V) value);
        for (Entry<K, V> entry : entrySet()) {
            if (Arrays.equals(valueSerializer.apply(entry.getValue()), target)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public V get(Object key) {
        try {
            byte[] bytes = storageEngine.get(qualifyKey(key));
            if (bytes == null) {
                return null;
            }
            return valueDeserializer.apply(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Error al leer clave off-heap: " + key, e);
        }
    }

    @Override
    public V put(K key, V value) {
        try {
            V previous = get(key);
            byte[] payload = valueSerializer.apply(value);
            storageEngine.put(qualifyKey(key), payload);
            return previous;
        } catch (IOException e) {
            throw new RuntimeException("Error al escribir clave off-heap: " + key, e);
        }
    }

    @Override
    public V remove(Object key) {
        try {
            V previous = get(key);
            if (previous != null) {
                storageEngine.delete(qualifyKey(key));
            }
            return previous;
        } catch (IOException e) {
            throw new RuntimeException("Error al borrar clave off-heap: " + key, e);
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        for (K key : keySet()) {
            remove(key);
        }
    }

    @Override
    public Set<K> keySet() {
        Set<K> keys = new HashSet<>();
        for (String k : storageEngine.getIndexManager().getAllLiveEntries().keySet()) {
            if (k.startsWith(mapNamespace + ":")) {
                keys.add(keyDecoder.apply(extractRawKey(k)));
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public Collection<V> values() {
        List<V> vals = new ArrayList<>();
        for (K key : keySet()) {
            vals.add(get(key));
        }
        return Collections.unmodifiableList(vals);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> entries = new HashSet<>();
        for (K key : keySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(key, get(key)));
        }
        return Collections.unmodifiableSet(entries);
    }

    @SuppressWarnings("unchecked")
    private static <T> byte[] defaultSerialize(T obj) {
        if (obj instanceof byte[] b) return b;
        if (obj instanceof String s) return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (obj instanceof Serializable) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(obj);
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Fallo en serialización por defecto", e);
            }
        }
        throw new IllegalArgumentException("El objeto debe ser byte[], String o Serializable: " + obj.getClass());
    }

    @SuppressWarnings("unchecked")
    private static <T> T defaultDeserialize(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (T) ois.readObject();
        } catch (Exception e) {
            // Si no es Java Serializable, retornar como String o byte[]
            return (T) new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

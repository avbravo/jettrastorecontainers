package com.jettra.memory.collections;

import com.jettra.memory.engine.DiskStorageEngine;

import java.util.Map;
import java.util.function.Function;

/**
 * Fábrica de estructuras de datos Off-Heap para la integración con JettraCollections.
 */
public final class JettraOffHeapCollectionFactory {

    private JettraOffHeapCollectionFactory() {}

    /**
     * Crea un Map de cadenas optimizado para almacenar datos directamente en disco.
     */
    public static Map<String, String> createStringMap(String namespace, DiskStorageEngine engine) {
        return new JettraOffHeapMap<>(
                namespace,
                engine,
                Function.identity(),
                Function.identity(),
                str -> str.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /**
     * Crea un Map de bytes directo para almacenar payloads binarios sin serialización.
     */
    public static Map<String, byte[]> createBinaryMap(String namespace, DiskStorageEngine engine) {
        return new JettraOffHeapMap<>(
                namespace,
                engine,
                Function.identity(),
                Function.identity(),
                Function.identity(),
                Function.identity()
        );
    }

    /**
     * Crea un Map genérico parametrizado con codificadores personalizados.
     */
    public static <K, V> JettraOffHeapMap<K, V> createCustomMap(
            String namespace,
            DiskStorageEngine engine,
            Function<K, String> keyEncoder,
            Function<String, K> keyDecoder,
            Function<V, byte[]> valueSerializer,
            Function<byte[], V> valueDeserializer) {
        return new JettraOffHeapMap<>(namespace, engine, keyEncoder, keyDecoder, valueSerializer, valueDeserializer);
    }
}

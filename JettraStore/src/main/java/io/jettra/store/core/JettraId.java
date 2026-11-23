package io.jettra.store.core;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identificador único interno y clave primaria por defecto de JettraStore ({@code JettraId}).
 *
 * <p>Estructura binaria fija de 16 bytes (representación canónica hexadecimal en minúsculas de 32 caracteres)
 * en orden big-endian:</p>
 * <ul>
 *   <li><b>Bytes 0..3 (4 bytes):</b> Timestamp Unix en segundos desde {@code 1970-01-01T00:00:00Z}.</li>
 *   <li><b>Bytes 4..8 (5 bytes):</b> Identificador aleatorio de 40 bits de proceso/máquina generado al arrancar la JVM.</li>
 *   <li><b>Bytes 9..12 (4 bytes):</b> Hash determinista FNV-1a de 32 bits de {@code databaseName + ":" + bucketName}.</li>
 *   <li><b>Bytes 13..15 (3 bytes):</b> Contador atómico incremental de 24 bits por proceso ({@code 0xFFFFFF}).</li>
 * </ul>
 */
public final class JettraId implements Serializable, Comparable<JettraId> {

    public static final int BYTE_LENGTH = 16;
    public static final int HEX_LENGTH = 32;

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final byte[] PROCESS_MACHINE_ID = new byte[5];
    private static final AtomicInteger PROCESS_COUNTER;

    static {
        SecureRandom rng = new SecureRandom();
        rng.nextBytes(PROCESS_MACHINE_ID);
        PROCESS_COUNTER = new AtomicInteger(rng.nextInt() & 0x00FFFFFF);
    }

    private final byte[] bytes;
    private final String hex;

    private JettraId(byte[] bytes) {
        if (bytes == null || bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("JettraId requiere exactamente " + BYTE_LENGTH + " bytes.");
        }
        this.bytes = Arrays.copyOf(bytes, BYTE_LENGTH);
        this.hex = encodeHex(this.bytes);
    }

    /**
     * Genera un nuevo {@code JettraId} para la base de datos y bucket especificados.
     */
    public static JettraId generate(String databaseName, String bucketName) {
        return generate(databaseName, bucketName, Instant.now().getEpochSecond());
    }

    /**
     * Genera un nuevo {@code JettraId} con un timestamp Unix explícito en segundos.
     */
    public static JettraId generate(String databaseName, String bucketName, long epochSeconds) {
        byte[] raw = new byte[BYTE_LENGTH];

        // 1. Timestamp Unix (4 bytes, big-endian)
        int ts = (int) epochSeconds;
        raw[0] = (byte) (ts >>> 24);
        raw[1] = (byte) (ts >>> 16);
        raw[2] = (byte) (ts >>> 8);
        raw[3] = (byte) ts;

        // 2. Identificador aleatorio de proceso / máquina (5 bytes)
        System.arraycopy(PROCESS_MACHINE_ID, 0, raw, 4, 5);

        // 3. Código hash determinista de Base de Datos y Bucket (4 bytes, big-endian)
        int scopeHash = computeScopeHash(databaseName, bucketName);
        raw[9] = (byte) (scopeHash >>> 24);
        raw[10] = (byte) (scopeHash >>> 16);
        raw[11] = (byte) (scopeHash >>> 8);
        raw[12] = (byte) scopeHash;

        // 4. Contador atómico incremental de 24 bits por proceso (3 bytes, big-endian)
        int seq = PROCESS_COUNTER.getAndIncrement() & 0x00FFFFFF;
        raw[13] = (byte) (seq >>> 16);
        raw[14] = (byte) (seq >>> 8);
        raw[15] = (byte) seq;

        return new JettraId(raw);
    }

    /**
     * Parsea y valida una representación hexadecimal de 32 caracteres de un {@code JettraId}.
     */
    public static JettraId fromHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("El valor hexadecimal de JettraId no puede ser nulo.");
        }
        String clean = hex.trim();
        if (clean.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("JettraId hexadecimal inválido (se esperan 32 caracteres): '" + hex + "'");
        }
        byte[] decoded = new byte[BYTE_LENGTH];
        for (int i = 0; i < BYTE_LENGTH; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("JettraId contiene caracteres hexadecimales inválidos: '" + hex + "'");
            }
            decoded[i] = (byte) ((hi << 4) | lo);
        }
        return new JettraId(decoded);
    }

    /**
     * Crea un {@code JettraId} a partir de un arreglo exacto de 16 bytes.
     */
    public static JettraId fromBytes(byte[] raw) {
        return new JettraId(raw);
    }

    /**
     * Indica si la cadena proporcionada corresponde a una representación hexadecimal válida de 32 caracteres.
     */
    public static boolean isValidHex(String hex) {
        if (hex == null) return false;
        String clean = hex.trim();
        if (clean.length() != HEX_LENGTH) return false;
        for (int i = 0; i < HEX_LENGTH; i++) {
            if (Character.digit(clean.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Calcula el hash determinista FNV-1a de 32 bits para {@code databaseName + ":" + bucketName}.
     */
    public static int computeScopeHash(String databaseName, String bucketName) {
        String db = databaseName != null ? databaseName.trim() : "";
        String bucket = bucketName != null ? bucketName.trim() : "";
        byte[] data = (db + ":" + bucket).getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }

    /**
     * Retorna una copia defensiva de los 16 bytes en orden big-endian.
     */
    public byte[] toBytes() {
        return Arrays.copyOf(bytes, BYTE_LENGTH);
    }

    /**
     * Retorna la representación canónica hexadecimal en minúsculas de 32 caracteres.
     */
    public String toHexString() {
        return hex;
    }

    /**
     * Extrae el timestamp Unix en segundos (bytes 0..3, sin signo).
     */
    public long timestampSeconds() {
        return ((long) (bytes[0] & 0xFF) << 24)
                | ((long) (bytes[1] & 0xFF) << 16)
                | ((long) (bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    /**
     * Retorna el {@link Instant} de creación extraído de los primeros 4 bytes.
     */
    public Instant createdAt() {
        return Instant.ofEpochSecond(timestampSeconds());
    }

    /**
     * Extrae el identificador de proceso/máquina de 40 bits (bytes 4..8).
     */
    public long processMachineId() {
        return ((long) (bytes[4] & 0xFF) << 32)
                | ((long) (bytes[5] & 0xFF) << 24)
                | ((long) (bytes[6] & 0xFF) << 16)
                | ((long) (bytes[7] & 0xFF) << 8)
                | (bytes[8] & 0xFF);
    }

    /**
     * Retorna una copia de los 5 bytes del identificador de proceso/máquina.
     */
    public byte[] processMachineBytes() {
        return Arrays.copyOfRange(bytes, 4, 9);
    }

    /**
     * Extrae el hash de 32 bits de base de datos y bucket (bytes 9..12).
     */
    public int databaseBucketHash() {
        return ((bytes[9] & 0xFF) << 24)
                | ((bytes[10] & 0xFF) << 16)
                | ((bytes[11] & 0xFF) << 8)
                | (bytes[12] & 0xFF);
    }

    /**
     * Extrae el contador incremental de 24 bits por proceso (bytes 13..15).
     */
    public int counter() {
        return ((bytes[13] & 0xFF) << 16)
                | ((bytes[14] & 0xFF) << 8)
                | (bytes[15] & 0xFF);
    }

    private static String encodeHex(byte[] raw) {
        char[] out = new char[HEX_LENGTH];
        for (int i = 0; i < BYTE_LENGTH; i++) {
            int v = raw[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }

    @Override
    public int compareTo(JettraId other) {
        Objects.requireNonNull(other, "other");
        return Arrays.compareUnsigned(this.bytes, other.bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JettraId other)) return false;
        return Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return hex;
    }
}

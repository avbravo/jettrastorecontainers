package io.jettra.driver.version;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instantánea inmutable de una versión histórica de un registro, documento u objeto en JettraStore (RF-18).
 *
 * @param versionNumber       Número secuencial incremental de la versión (1, 2, 3, ...).
 * @param versionId           Identificador único legible de la versión (ej. {@code <recordId>@v1}).
 * @param databaseName        Base de datos a la que pertenece el registro.
 * @param bucketName          Bucket o motor donde reside el registro.
 * @param recordId            Identificador único del registro ({@code _id} / {@code JettraId}).
 * @param operation           Operación realizada ({@code CREATE}, {@code UPDATE}, {@code DELETE}, {@code RESTORE}).
 * @param timestamp           Marca de tiempo ISO-8601 UTC de la mutación.
 * @param author              Usuario o agente que ejecutó la operación.
 * @param note                Nota descriptiva de la operación o restauración.
 * @param restoredFromVersion Versión origen cuando {@code operation == RESTORE}; {@code null} en caso contrario.
 * @param snapshot            Copia inmutable del contenido completo del registro en esa versión.
 */
public record RecordVersion(
        long versionNumber,
        String versionId,
        String databaseName,
        String bucketName,
        String recordId,
        VersionOperation operation,
        String timestamp,
        String author,
        String note,
        Long restoredFromVersion,
        Map<String, Object> snapshot
) implements Serializable {

    public RecordVersion {
        snapshot = snapshot == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    /**
     * Retorna una copia mutable del snapshot para operaciones de restauración o transformación segura.
     */
    public Map<String, Object> copySnapshot() {
        return new LinkedHashMap<>(snapshot);
    }
}

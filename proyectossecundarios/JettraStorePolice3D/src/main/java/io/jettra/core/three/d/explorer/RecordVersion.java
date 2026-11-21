package io.jettra.core.three.d.explorer;

import java.util.Collections;
import java.util.List;

/**
 * Representa una version historica inmutable de un registro dentro de un engine de JettraStore
 * para soporte de auditoria, control de cambios y restauracion de versiones previas.
 */
public record RecordVersion(
    int version,
    String summary,
    String details,
    String timestamp,
    String operationNote,
    List<RecordFieldInfo> fields
) {
    public RecordVersion(int version, String summary, String details, String timestamp, String operationNote) {
        this(version, summary, details, timestamp, operationNote, Collections.emptyList());
    }

    public List<RecordFieldInfo> safeFields() {
        return (fields != null) ? fields : Collections.emptyList();
    }
}

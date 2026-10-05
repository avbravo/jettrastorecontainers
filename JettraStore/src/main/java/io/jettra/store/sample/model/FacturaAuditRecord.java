package io.jettra.store.sample.model;

import java.io.Serializable;

/**
 * Registro inmutable tipado para el motor Java 25 RecordsEngine en JettraStore.
 * Representa una traza de auditoría y timbrado fiscal digital.
 */
public record FacturaAuditRecord(
    String auditId,
    String folioFiscal,
    String rfcEmisor,
    String rfcReceptor,
    double total,
    String hashSha256,
    long timestamp
) implements Serializable {}

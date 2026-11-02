package com.portfolio.wallet.audit.persistence;

import java.util.UUID;

/**
 * Column values of one new {@code audit.audit_logs} row; {@code oldValue} and {@code newValue} are
 * JSON text.
 */
public record AuditLogRow(
    UUID actorId,
    String actorType,
    String action,
    String resource,
    String resourceId,
    String result,
    String clientIp,
    String correlationId,
    String oldValue,
    String newValue) {}

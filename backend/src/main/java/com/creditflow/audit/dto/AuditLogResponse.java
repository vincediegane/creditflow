package com.creditflow.audit.dto;

import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        String entityType,
        Long entityId,
        String entityLabel,
        String action,
        String details,
        String actor,
        LocalDateTime createdAt
) {
}

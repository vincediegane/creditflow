package com.creditflow.audit.service;

import com.creditflow.audit.domain.AuditLog;
import com.creditflow.audit.dto.AuditLogResponse;
import com.creditflow.audit.repository.AuditLogRepository;
import com.creditflow.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void record(String entityType, Long entityId, String entityLabel, String action, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .entityLabel(entityLabel)
                .action(action)
                .details(details)
                .actor(CurrentUser.username())
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> list(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId).stream()
                .map(log -> new AuditLogResponse(
                        log.getId(),
                        log.getEntityType(),
                        log.getEntityId(),
                        log.getEntityLabel(),
                        log.getAction(),
                        log.getDetails(),
                        log.getActor(),
                        log.getCreatedAt()))
                .toList();
    }
}

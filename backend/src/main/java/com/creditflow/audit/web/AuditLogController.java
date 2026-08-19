package com.creditflow.audit.web;

import com.creditflow.audit.dto.AuditLogResponse;
import com.creditflow.audit.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-log")
@RequiredArgsConstructor
@Tag(name = "Journal d'audit", description = "Historique des actions sensibles (qui a fait quoi, quand)")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Historique d'une entite (client, contrat, produit)")
    public List<AuditLogResponse> list(@RequestParam String entityType, @RequestParam Long entityId) {
        return auditLogService.list(entityType, entityId);
    }
}

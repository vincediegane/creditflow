package com.creditflow.penalty.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.util.Money;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.dto.PenaltySettingsRequest;
import com.creditflow.penalty.dto.PenaltySettingsResponse;
import com.creditflow.penalty.repository.PenaltySettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PenaltySettingsService {

    private static final Long SETTINGS_ID = 1L;

    private final PenaltySettingsRepository penaltySettingsRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PenaltySettings current() {
        return penaltySettingsRepository.findById(SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("penalty_settings non initialise (migration V4 manquante)"));
    }

    @Transactional(readOnly = true)
    public PenaltySettingsResponse get() {
        return toResponse(current());
    }

    @Transactional
    public PenaltySettingsResponse update(PenaltySettingsRequest request) {
        if (request.enabled() && !Money.isPositive(request.rate())) {
            throw new BusinessRuleException(
                    "Le taux de penalite doit etre positif si la penalite est activee");
        }

        PenaltySettings settings = current();
        String details = "enabled: %s -> %s, rate: %s -> %s (%s/%s)".formatted(
                settings.isEnabled(), request.enabled(),
                settings.getRate(), request.rate(),
                request.rateType(), request.period());

        settings.setEnabled(request.enabled());
        settings.setRateType(request.rateType());
        settings.setRate(request.rate());
        settings.setPeriod(request.period());
        settings.setCapPercent(request.capPercent());

        PenaltySettings saved = penaltySettingsRepository.save(settings);
        auditLogService.record("PENALTY_SETTINGS", SETTINGS_ID, "Parametres de penalite", "UPDATE", details);

        return toResponse(saved);
    }

    private PenaltySettingsResponse toResponse(PenaltySettings settings) {
        return new PenaltySettingsResponse(
                settings.isEnabled(),
                settings.getRateType(),
                settings.getRate(),
                settings.getPeriod(),
                settings.getCapPercent(),
                settings.getUpdatedAt(),
                settings.getUpdatedBy());
    }
}

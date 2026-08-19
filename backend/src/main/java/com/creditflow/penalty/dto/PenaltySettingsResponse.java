package com.creditflow.penalty.dto;

import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PenaltySettingsResponse(
        boolean enabled,
        PenaltyRateType rateType,
        BigDecimal rate,
        PenaltyPeriod period,
        BigDecimal capPercent,
        LocalDateTime updatedAt,
        String updatedBy
) {
}

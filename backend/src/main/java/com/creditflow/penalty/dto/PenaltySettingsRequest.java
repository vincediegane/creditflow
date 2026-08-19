package com.creditflow.penalty.dto;

import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PenaltySettingsRequest(
        boolean enabled,
        @NotNull(message = "Le type de taux est obligatoire") PenaltyRateType rateType,
        @NotNull(message = "Le taux est obligatoire")
        @DecimalMin(value = "0.0", message = "Le taux ne peut pas etre negatif") BigDecimal rate,
        @NotNull(message = "La periode est obligatoire") PenaltyPeriod period,
        @DecimalMin(value = "0.0", inclusive = false, message = "Le plafond doit etre positif")
        @DecimalMax(value = "100.0", message = "Le plafond ne peut pas depasser 100%") BigDecimal capPercent
) {
}

package com.creditflow.sale.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Simulation d'echeancier avant enregistrement du contrat.
 */
public record SalePreviewRequest(
        @NotNull @DecimalMin(value = "1.0") BigDecimal totalPrice,
        @DecimalMin(value = "0.0") BigDecimal downPayment,
        @NotNull @Min(1) Integer installmentCount,
        @NotNull LocalDate startDate
) {
}

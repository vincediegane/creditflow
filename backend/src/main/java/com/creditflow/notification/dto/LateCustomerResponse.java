package com.creditflow.notification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne du tableau "clients en retard".
 */
public record LateCustomerResponse(
        Long customerId,
        String customerName,
        String phone,
        String productNames,
        long lateInstallments,
        long daysLate,
        LocalDate oldestDueDate,
        BigDecimal lateAmount,
        BigDecimal remainingAmount,
        Long primarySaleId,
        String primarySaleReference,
        BigDecimal monthlyAmount,
        BigDecimal penaltyAmount
) {
}

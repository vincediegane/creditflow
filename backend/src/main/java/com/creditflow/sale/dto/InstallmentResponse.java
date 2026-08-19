package com.creditflow.sale.dto;

import com.creditflow.sale.domain.InstallmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstallmentResponse(
        Long id,
        Long saleId,
        String saleReference,
        Long customerId,
        String customerName,
        String customerPhone,
        String productName,
        Integer number,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal amountPaid,
        BigDecimal remaining,
        InstallmentStatus status,
        /** Statut affiche : PAID, PARTIAL, LATE ou PENDING. */
        String displayStatus,
        boolean late,
        long daysLate,
        LocalDate paidAt,
        BigDecimal penaltyAmount
) {
}

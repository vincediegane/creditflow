package com.creditflow.sale.dto;

import com.creditflow.sale.domain.SaleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SaleResponse(
        Long id,
        String reference,
        Long customerId,
        String customerName,
        String customerPhone,
        Long productId,
        String productName,
        BigDecimal totalPrice,
        BigDecimal downPayment,
        BigDecimal financedAmount,
        Integer installmentCount,
        BigDecimal monthlyAmount,
        BigDecimal amountPaid,
        BigDecimal remainingAmount,
        LocalDate startDate,
        LocalDate endDate,
        SaleStatus status,
        boolean late,
        long lateInstallments,
        long daysLate,
        LocalDate nextDueDate,
        BigDecimal nextDueAmount,
        int paidInstallments,
        String notes,
        LocalDateTime createdAt,
        String createdBy,
        String updatedBy
) {
}

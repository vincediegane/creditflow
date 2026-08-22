package com.creditflow.payment.dto;

import com.creditflow.payment.domain.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long saleId,
        String saleReference,
        Long customerId,
        String customerName,
        String customerPhone,
        String productName,
        BigDecimal amount,
        LocalDate paymentDate,
        PaymentMethod method,
        String reference,
        String notes,
        BigDecimal saleRemainingAmount,
        LocalDateTime createdAt,
        String createdBy,
        String clientRequestId
) {
}

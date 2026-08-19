package com.creditflow.customer.dto;

import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.sale.dto.SaleResponse;

import java.math.BigDecimal;
import java.util.List;

public record CustomerDetailResponse(
        CustomerResponse customer,
        List<SaleResponse> sales,
        List<PaymentResponse> payments,
        BigDecimal totalPurchased,
        BigDecimal totalPaid,
        BigDecimal totalRemaining,
        long lateInstallments
) {
}

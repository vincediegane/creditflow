package com.creditflow.dashboard.dto;

import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.sale.dto.InstallmentResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
        LocalDate referenceDate,
        Metrics metrics,
        List<PaymentResponse> todayPayments,
        List<InstallmentResponse> upcomingInstallments,
        List<LateCustomerResponse> lateCustomers
) {

    public record Metrics(
            long totalCustomers,
            long totalSales,
            long activeSales,
            long completedSales,
            BigDecimal totalRemaining,
            BigDecimal collectedThisMonth,
            BigDecimal collectedToday,
            long todayPaymentsCount,
            long lateCustomersCount,
            long lateInstallmentsCount,
            BigDecimal lateAmount,
            long upcomingInstallmentsCount
    ) {
    }
}

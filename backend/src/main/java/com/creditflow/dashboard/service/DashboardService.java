package com.creditflow.dashboard.service;

import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.dashboard.dto.DashboardResponse;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.service.InstallmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Agregats du tableau de bord. Toutes les valeurs sont calculees a la volee
 * a partir des donnees des contrats et des paiements : aucun cache a invalider.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int UPCOMING_WINDOW_DAYS = 30;
    private static final int UPCOMING_LIMIT = 10;
    private static final int LATE_LIMIT = 10;

    private final CustomerRepository customerRepository;
    private final CreditSaleRepository saleRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final InstallmentService installmentService;
    private final LateCustomerService lateCustomerService;
    private final CurrentShopContext currentShopContext;

    @Transactional(readOnly = true)
    public DashboardResponse overview() {
        List<Long> shopIds = currentShopContext.accessibleShopIds();
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.from(today);
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        List<PaymentResponse> todayPayments = paymentRepository.findBetweenForShops(today, today, shopIds).stream()
                .map(paymentMapper::toResponse)
                .toList();

        List<InstallmentResponse> upcoming = installmentService.upcomingForShops(UPCOMING_WINDOW_DAYS, shopIds);
        List<LateCustomerResponse> lateCustomers = lateCustomerService.lateCustomers(shopIds);

        DashboardResponse.Metrics metrics = new DashboardResponse.Metrics(
                customerRepository.countByShop_IdIn(shopIds),
                saleRepository.countByShop_IdIn(shopIds),
                saleRepository.countByStatusAndShop_IdIn(SaleStatus.ACTIVE, shopIds),
                saleRepository.countByStatusAndShop_IdIn(SaleStatus.COMPLETED, shopIds),
                saleRepository.sumRemainingByStatusForShops(SaleStatus.ACTIVE, shopIds),
                paymentRepository.sumBetweenForShops(monthStart, monthEnd, shopIds),
                paymentRepository.sumBetweenForShops(today, today, shopIds),
                paymentRepository.countBetweenForShops(today, today, shopIds),
                lateCustomers.size(),
                installmentRepository.countLateForShops(today, shopIds),
                nullToZero(installmentRepository.sumLateAmountForShops(today, shopIds)),
                upcoming.size());

        return new DashboardResponse(
                today,
                metrics,
                todayPayments,
                upcoming.stream().limit(UPCOMING_LIMIT).toList(),
                lateCustomers.stream().limit(LATE_LIMIT).toList());
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

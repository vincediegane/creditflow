package com.creditflow.dashboard.service;

import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.dashboard.dto.DashboardResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.service.InstallmentService;
import com.creditflow.shop.dto.ShopSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private InstallmentService installmentService;

    @Mock
    private LateCustomerService lateCustomerService;

    @Mock
    private CurrentShopContext currentShopContext;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(customerRepository, saleRepository, installmentRepository,
                paymentRepository, paymentMapper, installmentService, lateCustomerService, currentShopContext);

        when(paymentRepository.findBetweenForShops(any(), any(), any())).thenReturn(List.of());
        when(paymentRepository.sumBetweenForShops(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.countBetweenForShops(any(), any(), any())).thenReturn(0L);
        when(installmentService.upcomingForShops(anyInt(), any())).thenReturn(List.of());
        when(lateCustomerService.lateCustomers(any())).thenReturn(List.of());
        when(installmentRepository.countLateForShops(any(), any(), any())).thenReturn(0L);
        when(installmentRepository.sumLateAmountForShops(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(saleRepository.countByStatusAndShop_IdIn(any(SaleStatus.class), any())).thenReturn(0L);
        when(saleRepository.sumRemainingByStatusForShops(any(SaleStatus.class), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
    }

    @Test
    @DisplayName("mono-boutique : consolidated=false, agregats restreints a l'unique boutique accessible")
    void monoShop_isNotConsolidated() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        when(currentShopContext.accessibleShops()).thenReturn(List.of(new ShopSummary(1L, "Boutique principale")));

        DashboardResponse response = dashboardService.overview();

        assertThat(response.consolidated()).isFalse();
        assertThat(response.accessibleShops()).containsExactly(new ShopSummary(1L, "Boutique principale"));
        verify(customerRepository).countByShop_IdIn(List.of(1L));
        verify(saleRepository).countByShop_IdIn(List.of(1L));
        verify(saleRepository).countByStatusAndShop_IdIn(SaleStatus.ACTIVE, List.of(1L));
        verify(saleRepository).countByStatusAndShop_IdIn(SaleStatus.COMPLETED, List.of(1L));
        verify(saleRepository).sumRemainingByStatusForShops(SaleStatus.ACTIVE, List.of(1L), 100L);
        verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L)));
        verify(installmentService).upcomingForShops(anyInt(), eq(List.of(1L)));
        verify(lateCustomerService).lateCustomers(List.of(1L));
        verify(installmentRepository).countLateForShops(any(), eq(List.of(1L)), eq(100L));
        verify(installmentRepository).sumLateAmountForShops(any(), eq(List.of(1L)), eq(100L));
    }

    @Test
    @DisplayName("multi-boutique sans en-tete : vue consolidee sur toutes les boutiques accessibles")
    void multiShopWithoutHeader_isConsolidated() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L, 2L));
        when(currentShopContext.accessibleShops()).thenReturn(List.of(
                new ShopSummary(1L, "Boutique principale"), new ShopSummary(2L, "Boutique annexe")));

        DashboardResponse response = dashboardService.overview();

        assertThat(response.consolidated()).isTrue();
        verify(customerRepository).countByShop_IdIn(List.of(1L, 2L));
        verify(lateCustomerService).lateCustomers(List.of(1L, 2L));
    }

    @Test
    @DisplayName("multi-boutique avec en-tete X-Shop-Id valide : vue restreinte a la boutique demandee")
    void multiShopWithHeader_isRestrictedToRequestedShop() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(2L));
        when(currentShopContext.accessibleShops()).thenReturn(List.of(
                new ShopSummary(1L, "Boutique principale"), new ShopSummary(2L, "Boutique annexe")));

        DashboardResponse response = dashboardService.overview();

        assertThat(response.consolidated()).isFalse();
        verify(customerRepository).countByShop_IdIn(List.of(2L));
        verify(lateCustomerService).lateCustomers(List.of(2L));
        verify(installmentService).upcomingForShops(anyInt(), eq(List.of(2L)));
    }
}

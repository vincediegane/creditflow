package com.creditflow.notification.service;

import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.domain.Customer;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltyCalculator;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.product.domain.Product;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.InstallmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LateCustomerServiceTest {

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PenaltySettingsService penaltySettingsService;

    @Mock
    private PenaltyCalculator penaltyCalculator;

    @Mock
    private CurrentShopContext currentShopContext;

    @InjectMocks
    private LateCustomerService lateCustomerService;

    @BeforeEach
    void setUp() {
        when(penaltySettingsService.current()).thenReturn(PenaltySettings.builder()
                .id(1L).enabled(false).rateType(PenaltyRateType.FIXED)
                .rate(BigDecimal.ZERO).period(PenaltyPeriod.DAY).build());
        when(penaltyCalculator.totalOutstanding(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
    }

    @Test
    @DisplayName("lateCustomers ne delegue qu'aux boutiques fournies")
    void lateCustomersDelegatesToShopIds() {
        List<Long> shopIds = List.of(1L, 2L);
        when(installmentRepository.findLateForShops(any(), eq(shopIds), eq(100L)))
                .thenReturn(List.of(lateInstallment()));

        lateCustomerService.lateCustomers(shopIds);

        verify(installmentRepository).findLateForShops(any(), eq(shopIds), eq(100L));
    }

    @Test
    @DisplayName("agrege les echeances en retard par client")
    void groupsLateInstallmentsByCustomer() {
        List<Long> shopIds = List.of(1L);
        when(installmentRepository.findLateForShops(any(), eq(shopIds), eq(100L)))
                .thenReturn(List.of(lateInstallment()));

        var result = lateCustomerService.lateCustomers(shopIds);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).customerId()).isEqualTo(1L);
    }

    private Installment lateInstallment() {
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").phone("770000001")
                .build();
        Product product = Product.builder().id(1L).name("iPhone 13").build();
        CreditSale sale = CreditSale.builder()
                .id(10L).reference("VC-2026-00001").customer(customer).product(product)
                .monthlyAmount(new BigDecimal("15000")).remainingAmount(new BigDecimal("15000"))
                .status(SaleStatus.ACTIVE).startDate(LocalDate.now().minusMonths(1))
                .endDate(LocalDate.now().plusMonths(5)).build();
        return Installment.builder()
                .id(100L).sale(sale).number(1).dueDate(LocalDate.now().minusDays(5))
                .amount(new BigDecimal("15000")).amountPaid(BigDecimal.ZERO).penaltyPaid(BigDecimal.ZERO)
                .status(InstallmentStatus.PENDING).build();
    }
}

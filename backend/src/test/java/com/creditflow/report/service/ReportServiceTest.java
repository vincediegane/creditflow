package com.creditflow.report.service;

import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.domain.Customer;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.product.domain.Product;
import com.creditflow.report.dto.ReportData;
import com.creditflow.report.dto.ReportType;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.shop.domain.Shop;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private LateCustomerService lateCustomerService;

    @Mock
    private CurrentShopContext currentShopContext;

    private ReportService reportService;

    private Shop shop1;
    private Shop shop2;
    private CreditSale saleShop1;
    private CreditSale saleShop2;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(paymentRepository, saleRepository, lateCustomerService, currentShopContext);

        shop1 = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        shop2 = Shop.builder().id(2L).name("Boutique annexe").active(true).build();

        Customer customer1 = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo")
                .phone("770000001").shop(shop1).build();
        Customer customer2 = Customer.builder().id(2L).firstName("Fatou").lastName("Sow")
                .phone("770000002").shop(shop2).build();
        Product product1 = Product.builder().id(1L).name("iPhone 13").shop(shop1).build();
        Product product2 = Product.builder().id(2L).name("Samsung A54").shop(shop2).build();

        saleShop1 = CreditSale.builder().id(1L).reference("VC-2026-00001").customer(customer1).product(product1)
                .shop(shop1).status(SaleStatus.ACTIVE).totalPrice(new BigDecimal("150000"))
                .downPayment(BigDecimal.ZERO).financedAmount(new BigDecimal("150000"))
                .amountPaid(new BigDecimal("50000")).remainingAmount(new BigDecimal("100000"))
                .monthlyAmount(new BigDecimal("50000")).startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(3)).build();
        saleShop2 = CreditSale.builder().id(2L).reference("VC-2026-00002").customer(customer2).product(product2)
                .shop(shop2).status(SaleStatus.ACTIVE).totalPrice(new BigDecimal("200000"))
                .downPayment(BigDecimal.ZERO).financedAmount(new BigDecimal("200000"))
                .amountPaid(new BigDecimal("0")).remainingAmount(new BigDecimal("200000"))
                .monthlyAmount(new BigDecimal("40000")).startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(5)).build();
    }

    @Test
    @DisplayName("DAILY_PAYMENTS interroge les paiements avec le resultat de resolveReadFilter()")
    void dailyPayments_usesResolvedShopIds() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        Payment payment = Payment.builder().id(1L).sale(saleShop1).amount(new BigDecimal("50000"))
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH).build();
        when(paymentRepository.findBetweenForShops(any(), any(), eq(List.of(1L)))).thenReturn(List.of(payment));

        ReportData data = reportService.build(ReportType.DAILY_PAYMENTS, null, null);

        verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L)));
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("MONTHLY_PAYMENTS interroge les paiements avec le resultat de resolveReadFilter()")
    void monthlyPayments_usesResolvedShopIds() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L, 2L));
        when(paymentRepository.findBetweenForShops(any(), any(), eq(List.of(1L, 2L)))).thenReturn(List.of());

        reportService.build(ReportType.MONTHLY_PAYMENTS, null, null);

        verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("LATE_CUSTOMERS delegue a LateCustomerService avec le resultat de resolveReadFilter()")
    void lateCustomers_usesResolvedShopIds() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        when(lateCustomerService.lateCustomers(List.of(1L))).thenReturn(List.of(
                new LateCustomerResponse(1L, "Amadou Diallo", "770000001", "iPhone 13", 1, 5,
                        LocalDate.now().minusDays(5), new BigDecimal("50000"), new BigDecimal("100000"),
                        1L, "VC-2026-00001", new BigDecimal("50000"), BigDecimal.ZERO)));

        ReportData data = reportService.build(ReportType.LATE_CUSTOMERS, null, null);

        verify(lateCustomerService).lateCustomers(List.of(1L));
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("OUTSTANDING interroge les contrats avec le resultat de resolveReadFilter()")
    void outstanding_usesResolvedShopIds() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        when(saleRepository.findAllDetailedForShops(List.of(1L))).thenReturn(List.of(saleShop1));

        ReportData data = reportService.build(ReportType.OUTSTANDING, null, null);

        verify(saleRepository).findAllDetailedForShops(List.of(1L));
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("non-regression mono-boutique : aucune ligne de la boutique 2 n'apparait")
    void outstanding_monoShopExcludesOtherShopRows() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        when(saleRepository.findAllDetailedForShops(List.of(1L))).thenReturn(List.of(saleShop1));

        ReportData filtered = reportService.build(ReportType.OUTSTANDING, null, null);

        assertThat(filtered.rows()).hasSize(1);
        assertThat(filtered.rows().get(0)).contains("VC-2026-00001");
        assertThat(filtered.rows().stream().flatMap(List::stream))
                .noneMatch(value -> "VC-2026-00002".equals(value));
    }
}

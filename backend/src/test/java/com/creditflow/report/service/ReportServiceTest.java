package com.creditflow.report.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
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
import com.creditflow.report.export.ExcelReportExporter;
import com.creditflow.report.export.PdfReportExporter;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.shop.domain.Shop;
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
class ReportServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private LateCustomerService lateCustomerService;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentShopContext currentShopContext;

    @InjectMocks
    private ReportService reportService;

    private Shop shop1;
    private Shop shop2;
    private CreditSale saleShop1;
    private CreditSale saleShop2;

    @BeforeEach
    void setUp() {
        when(userRepository.findAll()).thenReturn(List.of());
        when(installmentRepository.findLateForShops(any(), any(), any())).thenReturn(List.of());
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L));
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);

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

    // ------------------------------------------------------------------
    // Cloisonnement par boutique (#10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DAILY_PAYMENTS interroge les paiements avec le resultat de resolveReadFilter()")
    void dailyPayments_usesResolvedShopIds() {
        Payment payment = Payment.builder().id(1L).sale(saleShop1).amount(new BigDecimal("50000"))
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH).build();
        when(paymentRepository.findBetweenForShops(any(), any(), eq(List.of(1L)))).thenReturn(List.of(payment));

        ReportData data = reportService.build(ReportType.DAILY_PAYMENTS, null, null, null, null, null);

        verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L)));
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("MONTHLY_PAYMENTS interroge les paiements avec le resultat de resolveReadFilter()")
    void monthlyPayments_usesResolvedShopIds() {
        when(currentShopContext.resolveReadFilter()).thenReturn(List.of(1L, 2L));
        when(paymentRepository.findBetweenForShops(any(), any(), eq(List.of(1L, 2L)))).thenReturn(List.of());

        reportService.build(ReportType.MONTHLY_PAYMENTS, null, null, null, null, null);

        verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("LATE_CUSTOMERS delegue a LateCustomerService avec le resultat de resolveReadFilter()")
    void lateCustomers_usesResolvedShopIds() {
        when(lateCustomerService.lateCustomers(List.of(1L))).thenReturn(List.of(
                new LateCustomerResponse(1L, "Amadou Diallo", "770000001", "iPhone 13", 1, 5,
                        LocalDate.now().minusDays(5), new BigDecimal("50000"), new BigDecimal("100000"),
                        1L, "VC-2026-00001", new BigDecimal("50000"), BigDecimal.ZERO)));

        ReportData data = reportService.build(ReportType.LATE_CUSTOMERS, null, null, null, null, null);

        verify(lateCustomerService).lateCustomers(List.of(1L));
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("OUTSTANDING interroge les contrats avec le resultat de resolveReadFilter()")
    void outstanding_usesResolvedShopIds() {
        when(saleRepository.findAllDetailedForShops(List.of(1L), 100L)).thenReturn(List.of(saleShop1));

        ReportData data = reportService.build(ReportType.OUTSTANDING, null, null, null, null, null);

        verify(saleRepository).findAllDetailedForShops(List.of(1L), 100L);
        assertThat(data.rows()).hasSize(1);
    }

    @Test
    @DisplayName("non-regression mono-boutique : aucune ligne de la boutique 2 n'apparait")
    void outstanding_monoShopExcludesOtherShopRows() {
        when(saleRepository.findAllDetailedForShops(List.of(1L), 100L)).thenReturn(List.of(saleShop1));

        ReportData filtered = reportService.build(ReportType.OUTSTANDING, null, null, null, null, null);

        assertThat(filtered.rows()).hasSize(1);
        assertThat(filtered.rows().get(0)).contains("VC-2026-00001");
        assertThat(filtered.rows().stream().flatMap(List::stream))
                .noneMatch(value -> "VC-2026-00002".equals(value));
    }

    @Test
    @DisplayName("DEFAULT_RATE interroge les contrats avec le resultat de resolveReadFilter()")
    void defaultRate_usesResolvedShopIds() {
        when(saleRepository.findAllDetailedForShops(List.of(1L), 100L)).thenReturn(List.of(saleShop1));

        reportService.build(ReportType.DEFAULT_RATE, null, null, null, null, null);

        verify(saleRepository).findAllDetailedForShops(List.of(1L), 100L);
        verify(installmentRepository).findLateForShops(any(), eq(List.of(1L)), eq(100L));
    }

    @Test
    @DisplayName("SELLER_PERFORMANCE interroge les contrats avec le resultat de resolveReadFilter()")
    void sellerPerformance_usesResolvedShopIds() {
        when(saleRepository.findAllDetailedForShops(List.of(1L), 100L)).thenReturn(List.of(saleShop1));

        reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        verify(saleRepository).findAllDetailedForShops(List.of(1L), 100L);
        verify(installmentRepository).findLateForShops(any(), eq(List.of(1L)), eq(100L));
    }

    // ------------------------------------------------------------------
    // Taux de defaut et performance vendeur (#9)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("defaultRate filtre par profession (trim + insensible a la casse)")
    void defaultRate_filtreParProfession() {
        CreditSale enseignant = sale(1L, "Enseignant ", "prof1", new BigDecimal("100000"));
        CreditSale commercant = sale(2L, "Commercant", "prof2", new BigDecimal("100000"));
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(enseignant, commercant));

        ReportData data = reportService.build(ReportType.DEFAULT_RATE, null, null, "enseignant", null, null);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get(0)).isEqualTo("Enseignant");
    }

    @Test
    @DisplayName("defaultRate filtre par tranche de montant, independamment ou combine")
    void defaultRate_filtreParTrancheDeMontant() {
        CreditSale small = sale(1L, "Enseignant", "prof1", new BigDecimal("50000"));
        CreditSale mid = sale(2L, "Enseignant", "prof1", new BigDecimal("150000"));
        CreditSale big = sale(3L, "Enseignant", "prof1", new BigDecimal("300000"));
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(small, mid, big));

        ReportData minOnly = reportService.build(ReportType.DEFAULT_RATE, null, null, null,
                new BigDecimal("100000"), null);
        assertThat(minOnly.rows().get(0).get(1)).isEqualTo(2);

        ReportData maxOnly = reportService.build(ReportType.DEFAULT_RATE, null, null, null,
                null, new BigDecimal("150000"));
        assertThat(maxOnly.rows().get(0).get(1)).isEqualTo(2);

        ReportData both = reportService.build(ReportType.DEFAULT_RATE, null, null, null,
                new BigDecimal("100000"), new BigDecimal("150000"));
        assertThat(both.rows().get(0).get(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("defaultRate regroupe les professions nulles ou blanches sous 'Non renseignee'")
    void defaultRate_professionNonRenseigneeRegroupee() {
        CreditSale blank = sale(1L, "   ", "prof1", new BigDecimal("100000"));
        CreditSale nullProfession = sale(2L, null, "prof2", new BigDecimal("100000"));
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(blank, nullProfession));

        ReportData data = reportService.build(ReportType.DEFAULT_RATE, null, null, null, null, null);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get(0)).isEqualTo("Non renseignee");
        assertThat(data.rows().get(0).get(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("defaultRate ignore les contrats non actifs et calcule le taux de defaut")
    void defaultRate_calculeLeTauxDeDefaut() {
        CreditSale active = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        CreditSale cancelled = sale(2L, "Enseignant", "prof2", new BigDecimal("100000"));
        cancelled.setStatus(SaleStatus.CANCELLED);
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(active, cancelled));

        Installment lateInstallment = Installment.builder()
                .id(1L).sale(active).number(1).dueDate(LocalDate.now().minusDays(10))
                .amount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO)
                .status(InstallmentStatus.PENDING).build();
        when(installmentRepository.findLateForShops(any(), any(), any())).thenReturn(List.of(lateInstallment));

        ReportData data = reportService.build(ReportType.DEFAULT_RATE, null, null, null, null, null);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get(2)).isEqualTo(1L);
        assertThat(data.rows().get(0).get(3)).isEqualTo("100,0 %");
        assertThat((BigDecimal) data.rows().get(0).get(4)).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("sellerPerformance regroupe par vendeur resolu via l'utilisateur")
    void sellerPerformance_regroupeParVendeur() {
        CreditSale saleSeller1 = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        saleSeller1.setCreatedBy("seller1");
        saleSeller1.setAmountPaid(new BigDecimal("40000"));
        CreditSale saleSeller2 = sale(2L, "Commercant", "prof2", new BigDecimal("200000"));
        saleSeller2.setCreatedBy("seller2");
        saleSeller2.setAmountPaid(new BigDecimal("90000"));
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(saleSeller1, saleSeller2));
        when(userRepository.findAll()).thenReturn(List.of(
                User.builder().id(1L).username("seller1").fullName("Awa Ndiaye").role(Role.SELLER).enabled(true).build(),
                User.builder().id(2L).username("seller2").fullName("Moussa Fall").role(Role.SELLER).enabled(true).build()));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        assertThat(data.rows()).hasSize(2);
        assertThat(data.rows().get(0).get(0)).isEqualTo("Moussa Fall");
        assertThat(data.rows().get(1).get(0)).isEqualTo("Awa Ndiaye");
    }

    @Test
    @DisplayName("sellerPerformance regroupe les vendeurs inconnus ou non attribues ensemble")
    void sellerPerformance_nonAttribueRegroupe() {
        CreditSale noCreator = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        noCreator.setCreatedBy(null);
        CreditSale unknownCreator = sale(2L, "Commercant", "prof2", new BigDecimal("100000"));
        unknownCreator.setCreatedBy("ghost");
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(noCreator, unknownCreator));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get(0)).isEqualTo("Non attribue");
        assertThat(data.rows().get(0).get(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("sellerPerformance exclut les contrats annules")
    void sellerPerformance_excludesCancelled() {
        CreditSale cancelled = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        cancelled.setStatus(SaleStatus.CANCELLED);
        cancelled.setCreatedBy("seller1");
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(cancelled));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        assertThat(data.rows()).isEmpty();
    }

    @Test
    @DisplayName("le rapport DEFAULT_RATE est exportable en pdf et excel")
    void defaultRateEstExportable() {
        CreditSale active = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(active));

        ReportData data = reportService.build(ReportType.DEFAULT_RATE, null, null, null, null, null);

        byte[] pdf = new PdfReportExporter(new com.creditflow.config.AppProperties()).export(data);
        byte[] excel = new ExcelReportExporter().export(data);

        assertThat(pdf.length).isGreaterThan(0);
        assertThat(excel.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("le rapport SELLER_PERFORMANCE est exportable en pdf et excel")
    void sellerPerformanceEstExportable() {
        CreditSale active = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        active.setCreatedBy("seller1");
        when(saleRepository.findAllDetailedForShops(any(), any())).thenReturn(List.of(active));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        byte[] pdf = new PdfReportExporter(new com.creditflow.config.AppProperties()).export(data);
        byte[] excel = new ExcelReportExporter().export(data);

        assertThat(pdf.length).isGreaterThan(0);
        assertThat(excel.length).isGreaterThan(0);
    }

    private CreditSale sale(Long id, String profession, String customerCni, BigDecimal totalPrice) {
        Customer customer = Customer.builder()
                .id(id).firstName("Client").lastName(String.valueOf(id))
                .phone("77000000" + id).profession(profession).active(true).shop(shop1).build();

        return CreditSale.builder()
                .id(id)
                .reference("VC-" + id)
                .customer(customer)
                .shop(shop1)
                .totalPrice(totalPrice)
                .interestAmount(BigDecimal.ZERO)
                .downPayment(BigDecimal.ZERO)
                .financedAmount(totalPrice)
                .installmentCount(3)
                .monthlyAmount(totalPrice.divide(new BigDecimal("3"), 2, java.math.RoundingMode.HALF_UP))
                .amountPaid(BigDecimal.ZERO)
                .remainingAmount(totalPrice)
                .startDate(LocalDate.now().minusMonths(1))
                .endDate(LocalDate.now().plusMonths(2))
                .status(SaleStatus.ACTIVE)
                .build();
    }
}

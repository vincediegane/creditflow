package com.creditflow.report.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.customer.domain.Customer;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.repository.PaymentRepository;
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

    @InjectMocks
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        when(userRepository.findAll()).thenReturn(List.of());
        when(installmentRepository.findLate(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("defaultRate filtre par profession (trim + insensible a la casse)")
    void defaultRate_filtreParProfession() {
        CreditSale enseignant = sale(1L, "Enseignant ", "prof1", new BigDecimal("100000"));
        CreditSale commercant = sale(2L, "Commercant", "prof2", new BigDecimal("100000"));
        when(saleRepository.findAllDetailed()).thenReturn(List.of(enseignant, commercant));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(small, mid, big));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(blank, nullProfession));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(active, cancelled));

        Installment lateInstallment = Installment.builder()
                .id(1L).sale(active).number(1).dueDate(LocalDate.now().minusDays(10))
                .amount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO)
                .status(InstallmentStatus.PENDING).build();
        when(installmentRepository.findLate(any())).thenReturn(List.of(lateInstallment));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(saleSeller1, saleSeller2));
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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(noCreator, unknownCreator));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(cancelled));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        assertThat(data.rows()).isEmpty();
    }

    @Test
    @DisplayName("le rapport DEFAULT_RATE est exportable en pdf et excel")
    void defaultRateEstExportable() {
        CreditSale active = sale(1L, "Enseignant", "prof1", new BigDecimal("100000"));
        when(saleRepository.findAllDetailed()).thenReturn(List.of(active));

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
        when(saleRepository.findAllDetailed()).thenReturn(List.of(active));

        ReportData data = reportService.build(ReportType.SELLER_PERFORMANCE, null, null, null, null, null);

        byte[] pdf = new PdfReportExporter(new com.creditflow.config.AppProperties()).export(data);
        byte[] excel = new ExcelReportExporter().export(data);

        assertThat(pdf.length).isGreaterThan(0);
        assertThat(excel.length).isGreaterThan(0);
    }

    private CreditSale sale(Long id, String profession, String customerCni, BigDecimal totalPrice) {
        Customer customer = Customer.builder()
                .id(id).firstName("Client").lastName(String.valueOf(id))
                .phone("77000000" + id).profession(profession).active(true).build();

        CreditSale sale = CreditSale.builder()
                .id(id)
                .reference("VC-" + id)
                .customer(customer)
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
        return sale;
    }
}

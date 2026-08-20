package com.creditflow.sale.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreditSaleServiceTest {

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @Mock
    private InstallmentScheduleGenerator scheduleGenerator;

    @Mock
    private SaleMapper saleMapper;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PenaltySettingsService penaltySettingsService;

    @InjectMocks
    private CreditSaleService creditSaleService;

    private CreditSale sale;

    @BeforeEach
    void setUp() {
        when(penaltySettingsService.current()).thenReturn(PenaltySettings.builder()
                .id(1L).enabled(false).rateType(PenaltyRateType.FIXED)
                .rate(java.math.BigDecimal.ZERO).period(PenaltyPeriod.DAY).build());
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").build();
        Product product = Product.builder().id(1L).name("iPhone 13").build();
        sale = CreditSale.builder()
                .id(1L).reference("VC-2026-00001").customer(customer).product(product)
                .totalPrice(new BigDecimal("150000")).downPayment(BigDecimal.ZERO)
                .financedAmount(new BigDecimal("150000")).installmentCount(3)
                .monthlyAmount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("150000")).startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(3)).status(SaleStatus.ACTIVE)
                .installments(new ArrayList<>())
                .build();
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.of(sale));
    }

    @Test
    @DisplayName("annule un contrat et journalise l'action")
    void cancelsSaleAndRecordsAuditEntry() {
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(i -> i.getArgument(0));

        creditSaleService.cancel(1L);

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.CANCELLED);
        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "CANCEL", null);
    }

    @Test
    @DisplayName("refuse d'annuler un contrat solde")
    void rejectsCancellingCompletedSale() {
        sale.setStatus(SaleStatus.COMPLETED);

        assertThatThrownBy(() -> creditSaleService.cancel(1L)).isInstanceOf(BusinessRuleException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("supprime un contrat sans paiement et journalise l'action")
    void deletesSaleAndRecordsAuditEntry() {
        when(paymentRepository.findBySale(1L)).thenReturn(List.of());

        creditSaleService.delete(1L);

        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "DELETE", null);
        verify(saleRepository).delete(sale);
    }

    @Test
    @DisplayName("refuse de supprimer un contrat avec des paiements")
    void rejectsDeletingSaleWithPayments() {
        when(paymentRepository.findBySale(1L)).thenReturn(List.of(org.mockito.Mockito.mock(
                com.creditflow.payment.domain.Payment.class)));

        assertThatThrownBy(() -> creditSaleService.delete(1L)).isInstanceOf(BusinessRuleException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any());
        verify(saleRepository, never()).delete(any(CreditSale.class));
    }

    @Test
    @DisplayName("reprend les champs garant du CreateSaleRequest vers l'entite sauvegardee")
    void createCapturesGuarantorFields() {
        stubCreationDependencies();

        CreateSaleRequest request = new CreateSaleRequest(1L, 1L, new BigDecimal("150000"), BigDecimal.ZERO,
                null, null, 3, LocalDate.now(), null,
                "Moussa Kane", "770001122", "Dakar, Sicap Liberte", "1234567890123");

        creditSaleService.create(request);

        CreditSale saved = capturedSale();
        assertThat(saved.getGuarantorFullName()).isEqualTo("Moussa Kane");
        assertThat(saved.getGuarantorPhone()).isEqualTo("770001122");
        assertThat(saved.getGuarantorAddress()).isEqualTo("Dakar, Sicap Liberte");
        assertThat(saved.getGuarantorCniNumber()).isEqualTo("1234567890123");
    }

    @Test
    @DisplayName("cree un contrat sans garant (non-regression)")
    void createWithoutGuarantorLeavesFieldsNull() {
        stubCreationDependencies();

        CreateSaleRequest request = new CreateSaleRequest(1L, 1L, new BigDecimal("150000"), BigDecimal.ZERO,
                null, null, 3, LocalDate.now(), null,
                null, null, null, null);

        creditSaleService.create(request);

        CreditSale saved = capturedSale();
        assertThat(saved.getGuarantorFullName()).isNull();
        assertThat(saved.getGuarantorPhone()).isNull();
        assertThat(saved.getGuarantorAddress()).isNull();
        assertThat(saved.getGuarantorCniNumber()).isNull();
    }

    private void stubCreationDependencies() {
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").build();
        Product product = Product.builder().id(1L).name("iPhone 13").stock(0).build();
        when(customerService.getEntity(1L)).thenReturn(customer);
        when(productService.getEntity(1L)).thenReturn(product);
        when(scheduleGenerator.interestAmount(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(scheduleGenerator.generate(any(), anyInt(), any())).thenReturn(
                new InstallmentScheduleGenerator.Schedule(
                        new BigDecimal("50000"), LocalDate.now().plusMonths(3), List.of()));
        when(saleRepository.saveAndFlush(any(CreditSale.class))).thenAnswer(i -> {
            CreditSale s = i.getArgument(0);
            s.setId(2L);
            return s;
        });
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CreditSale capturedSale() {
        ArgumentCaptor<CreditSale> captor = ArgumentCaptor.forClass(CreditSale.class);
        verify(saleRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}

package com.creditflow.payment.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.util.Money;
import com.creditflow.customer.domain.Customer;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.export.PaymentReceiptGenerator;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
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
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.shop.domain.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentReceiptGenerator receiptGenerator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PenaltySettingsService penaltySettingsService;

    @Spy
    private PenaltyCalculator penaltyCalculator = new PenaltyCalculator();

    @Spy
    private PaymentAllocator paymentAllocator = new PaymentAllocator(new PenaltyCalculator());

    @Mock
    private CurrentShopContext currentShopContext;

    @InjectMocks
    private PaymentService paymentService;

    private CreditSale sale;

    @BeforeEach
    void setUp() {
        sale = buildSale();
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.of(sale));
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(null);
        when(penaltySettingsService.current()).thenReturn(disabledPenaltySettings());
    }

    private PenaltySettings disabledPenaltySettings() {
        return PenaltySettings.builder()
                .id(1L)
                .enabled(false)
                .rateType(PenaltyRateType.FIXED)
                .rate(BigDecimal.ZERO)
                .period(PenaltyPeriod.DAY)
                .build();
    }

    @Test
    @DisplayName("un versement met a jour l'echeancier et le contrat")
    void registerUpdatesScheduleAndSale() {
        paymentService.register(request(new BigDecimal("50000")));

        assertThat(sale.getInstallments().get(0).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(sale.getAmountPaid()).isEqualByComparingTo("50000");
        assertThat(sale.getRemainingAmount()).isEqualByComparingTo("100000");
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.ACTIVE);
    }

    @Test
    @DisplayName("le contrat passe a SOLDE quand le reste atteint zero")
    void saleIsCompletedWhenFullyPaid() {
        paymentService.register(request(new BigDecimal("150000")));

        assertThat(sale.getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getInstallments()).allMatch(Installment::isSettled);
    }

    @Test
    @DisplayName("refuse un versement superieur au reste a payer")
    void rejectsOverpayment() {
        assertThatThrownBy(() -> paymentService.register(request(new BigDecimal("200000"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("depasse le reste a payer");
    }

    @Test
    @DisplayName("la penalite est imputee avant le principal")
    void penaltyIsAppliedBeforePrincipal() {
        Installment first = sale.getInstallments().get(0);
        first.setDueDate(LocalDate.now().minusDays(4));
        sale.getInstallments().get(1).setDueDate(LocalDate.now().plusDays(10));
        sale.getInstallments().get(2).setDueDate(LocalDate.now().plusDays(40));
        when(penaltySettingsService.current()).thenReturn(enabledPenaltySettings());

        paymentService.register(request(new BigDecimal("3000")));

        assertThat(first.getPenaltyPaid()).isEqualByComparingTo("3000");
        assertThat(first.getAmountPaid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("accepte un versement superieur au reste principal mais couvrant reste + penalite en cours")
    void acceptsOverpaymentCoveredByPenalty() {
        sale.getInstallments().get(0).setDueDate(LocalDate.now().minusDays(60));
        sale.getInstallments().get(1).setDueDate(LocalDate.now().plusDays(10));
        sale.getInstallments().get(2).setDueDate(LocalDate.now().plusDays(40));
        when(penaltySettingsService.current()).thenReturn(enabledPenaltySettings());

        paymentService.register(request(new BigDecimal("200000")));

        assertThat(sale.getInstallments().get(0).getPenaltyPaid()).isEqualByComparingTo("60000");
    }

    private PenaltySettings enabledPenaltySettings() {
        return PenaltySettings.builder()
                .id(1L)
                .enabled(true)
                .rateType(PenaltyRateType.FIXED)
                .rate(new BigDecimal("1000"))
                .period(PenaltyPeriod.DAY)
                .build();
    }

    @Test
    @DisplayName("refuse un versement sur un contrat annule")
    void rejectsCancelledSale() {
        sale.setStatus(SaleStatus.CANCELLED);

        assertThatThrownBy(() -> paymentService.register(request(new BigDecimal("10000"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("annule");
    }

    @Test
    @DisplayName("refuse une date de paiement dans le futur")
    void rejectsFutureDate() {
        PaymentRequest request = new PaymentRequest(1L, new BigDecimal("10000"),
                LocalDate.now().plusDays(1), PaymentMethod.CASH, null, null, null);

        assertThatThrownBy(() -> paymentService.register(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("futur");
    }

    @Test
    @DisplayName("la suppression d'un versement journalise l'action sur le contrat")
    void deletingPaymentRecordsAuditEntryOnSale() {
        Payment payment = Payment.builder()
                .id(9L).sale(sale).amount(new BigDecimal("50000"))
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH).build();
        when(paymentRepository.findById(9L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findBySaleIdOrderByPaymentDateAscIdAsc(sale.getId())).thenReturn(List.of());

        paymentService.delete(9L);

        InOrder order = inOrder(auditLogService, paymentRepository);
        order.verify(auditLogService).record("CREDIT_SALE", sale.getId(), sale.getReference(), "PAYMENT_DELETE",
                "Versement de 50000 le %s (CASH)".formatted(LocalDate.now()));
        order.verify(paymentRepository).delete(payment);
    }

    @Test
    @DisplayName("delete() rejoue les versements avec penalite active et recalcule penaltyPaid")
    void deleteRecalculatesPenaltyPaid() {
        Installment first = sale.getInstallments().get(0);
        first.setDueDate(LocalDate.now().minusDays(4));
        first.setPenaltyPaid(new BigDecimal("9999.00"));
        Payment toDelete = Payment.builder()
                .id(9L).sale(sale).amount(new BigDecimal("1000"))
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH).build();
        Payment historical = Payment.builder()
                .id(5L).sale(sale).amount(new BigDecimal("2000"))
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH).build();
        when(paymentRepository.findById(9L)).thenReturn(Optional.of(toDelete));
        when(paymentRepository.findBySaleIdOrderByPaymentDateAscIdAsc(sale.getId())).thenReturn(List.of(historical));
        when(penaltySettingsService.current()).thenReturn(enabledPenaltySettings());

        paymentService.delete(9L);

        assertThat(first.getPenaltyPaid()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("search combine le filtre organisation avec les autres criteres")
    void search_combinesOrganizationFilter() {
        when(currentShopContext.accessibleShopIds()).thenReturn(List.of(1L));
        when(currentShopContext.currentOrganizationId()).thenReturn(10L);
        when(paymentRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Page.empty());

        paymentService.search(null, null, null, null, null, PageRequest.of(0, 10));

        verify(currentShopContext).currentOrganizationId();
    }

    @Test
    @DisplayName("findBySale refuse un contrat hors du perimetre boutique")
    void findBySaleRejectsWhenSaleNotAccessible() {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(1L);

        assertThatThrownBy(() -> paymentService.findBySale(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("un rejeu renvoie le versement existant sans creer de doublon")
    void replayReturnsExistingPaymentWithoutCreatingDuplicate() {
        Payment existing = existingPayment("req-1", new BigDecimal("50000"));
        when(paymentRepository.findByClientRequestId("req-1")).thenReturn(Optional.of(existing));

        PaymentService.RegistrationResult result = paymentService.register(request(new BigDecimal("50000"), "req-1"));

        assertThat(result.replayed()).isTrue();
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(saleRepository, never()).save(any(CreditSale.class));
        assertThat(sale.getAmountPaid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("le clientRequestId de la requete est persiste sur le versement")
    void registerStoresClientRequestId() {
        when(paymentRepository.findByClientRequestId("req-2")).thenReturn(Optional.empty());

        PaymentService.RegistrationResult result = paymentService.register(request(new BigDecimal("50000"), "req-2"));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isEqualTo("req-2");
        assertThat(result.replayed()).isFalse();
    }

    @Test
    @DisplayName("un clientRequestId vide est stocke a null et ne declenche aucune recherche")
    void blankClientRequestIdIsStoredAsNull() {
        paymentService.register(request(new BigDecimal("50000"), "   "));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isNull();
        verify(paymentRepository, never()).findByClientRequestId(any());
    }

    @Test
    @DisplayName("sans clientRequestId le comportement est strictement l'ancien")
    void registerWithoutClientRequestIdBehavesAsBefore() {
        PaymentService.RegistrationResult result = paymentService.register(request(new BigDecimal("50000"), null));

        verify(paymentRepository, never()).findByClientRequestId(any());
        verify(paymentRepository).save(any(Payment.class));
        assertThat(result.replayed()).isFalse();
        assertThat(sale.getInstallments().get(0).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(sale.getAmountPaid()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("le rejeu sur un contrat annule renvoie le versement d'origine et non un conflit")
    void replayOnCancelledSaleReturnsOriginalPaymentInsteadOfConflict() {
        sale.setStatus(SaleStatus.CANCELLED);
        Payment existing = existingPayment("req-3", new BigDecimal("50000"));
        when(paymentRepository.findByClientRequestId("req-3")).thenReturn(Optional.of(existing));

        PaymentService.RegistrationResult result = paymentService.register(request(new BigDecimal("50000"), "req-3"));

        assertThat(result.replayed()).isTrue();
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("le rejeu d'un versement d'une autre boutique est rejete")
    void replayOfPaymentFromAnotherShopIsRejected() {
        Payment existing = existingPayment("req-4", new BigDecimal("50000"));
        when(paymentRepository.findByClientRequestId("req-4")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(1L);

        assertThatThrownBy(() -> paymentService.register(request(new BigDecimal("50000"), "req-4")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Payment existingPayment(String clientRequestId, BigDecimal amount) {
        return Payment.builder()
                .id(42L).sale(sale).amount(amount)
                .paymentDate(LocalDate.now()).method(PaymentMethod.CASH)
                .clientRequestId(clientRequestId).build();
    }

    private PaymentRequest request(BigDecimal amount) {
        return request(amount, null);
    }

    private PaymentRequest request(BigDecimal amount, String clientRequestId) {
        return new PaymentRequest(1L, amount, LocalDate.now(), PaymentMethod.CASH, "REF-1", null, clientRequestId);
    }

    private CreditSale buildSale() {
        Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true).shop(shop).build();
        Product product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(3).shop(shop).build();

        CreditSale creditSale = CreditSale.builder()
                .id(1L)
                .reference("VC-2026-00001")
                .customer(customer)
                .product(product)
                .shop(shop)
                .totalPrice(new BigDecimal("150000"))
                .downPayment(Money.ZERO)
                .financedAmount(new BigDecimal("150000"))
                .installmentCount(3)
                .monthlyAmount(new BigDecimal("50000"))
                .amountPaid(Money.ZERO)
                .remainingAmount(new BigDecimal("150000"))
                .startDate(LocalDate.now().minusMonths(2))
                .endDate(LocalDate.now())
                .status(SaleStatus.ACTIVE)
                .installments(new ArrayList<>())
                .build();

        List<Installment> installments = new ArrayList<>();
        for (int number = 1; number <= 3; number++) {
            installments.add(Installment.builder()
                    .id((long) number)
                    .sale(creditSale)
                    .number(number)
                    .dueDate(creditSale.getStartDate().plusMonths(number - 1L))
                    .amount(new BigDecimal("50000"))
                    .amountPaid(Money.ZERO)
                    .status(InstallmentStatus.PENDING)
                    .build());
        }
        creditSale.getInstallments().addAll(installments);
        return creditSale;
    }
}

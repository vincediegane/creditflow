package com.creditflow.payment.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.util.Money;
import com.creditflow.customer.domain.Customer;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.export.PaymentReceiptGenerator;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.product.domain.Product;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import static org.mockito.Mockito.inOrder;
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

    @Spy
    private PaymentAllocator paymentAllocator = new PaymentAllocator();

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
                LocalDate.now().plusDays(1), PaymentMethod.CASH, null, null);

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

    private PaymentRequest request(BigDecimal amount) {
        return new PaymentRequest(1L, amount, LocalDate.now(), PaymentMethod.CASH, "REF-1", null);
    }

    private CreditSale buildSale() {
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true).build();
        Product product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(3).build();

        CreditSale creditSale = CreditSale.builder()
                .id(1L)
                .reference("VC-2026-00001")
                .customer(customer)
                .product(product)
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

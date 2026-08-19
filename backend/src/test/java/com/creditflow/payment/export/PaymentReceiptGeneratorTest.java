package com.creditflow.payment.export;

import com.creditflow.config.AppProperties;
import com.creditflow.customer.domain.Customer;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.product.domain.Product;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReceiptGeneratorTest {

    private PaymentReceiptGenerator generator;
    private Payment payment;
    private List<Installment> installments;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getShop().setName("Boutique Test");
        properties.getShop().setCurrency("FCFA");
        generator = new PaymentReceiptGenerator(properties);

        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true)
                .build();
        Product product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(2).build();

        CreditSale sale = CreditSale.builder()
                .id(1L).reference("VC-2026-00001")
                .customer(customer).product(product)
                .totalPrice(new BigDecimal("150000"))
                .downPayment(new BigDecimal("30000"))
                .financedAmount(new BigDecimal("120000"))
                .installmentCount(3)
                .monthlyAmount(new BigDecimal("40000"))
                .amountPaid(new BigDecimal("40000"))
                .remainingAmount(new BigDecimal("80000"))
                .startDate(LocalDate.of(2026, 6, 5))
                .endDate(LocalDate.of(2026, 8, 5))
                .status(SaleStatus.ACTIVE)
                .installments(new ArrayList<>())
                .build();

        installments = List.of(
                Installment.builder().id(1L).sale(sale).number(1)
                        .dueDate(LocalDate.of(2026, 6, 5)).amount(new BigDecimal("40000"))
                        .amountPaid(new BigDecimal("40000")).status(InstallmentStatus.PAID).build(),
                Installment.builder().id(2L).sale(sale).number(2)
                        .dueDate(LocalDate.of(2026, 7, 5)).amount(new BigDecimal("40000"))
                        .amountPaid(BigDecimal.ZERO).status(InstallmentStatus.PENDING).build());

        payment = Payment.builder()
                .id(7L).sale(sale).amount(new BigDecimal("40000"))
                .paymentDate(LocalDate.of(2026, 6, 6))
                .method(PaymentMethod.MOBILE_MONEY)
                .reference("MM-4477")
                .build();
    }

    @Test
    @DisplayName("produit un PDF valide et non vide")
    void producesAValidPdf() {
        byte[] content = generator.generate(payment, installments);

        assertThat(content).isNotEmpty();
        assertThat(new String(content, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(content.length).isGreaterThan(700);
    }

    @Test
    @DisplayName("numerote le recu de facon lisible et stable")
    void numbersTheReceipt() {
        assertThat(generator.receiptNumber(payment)).isEqualTo("REC-2026-00007");
        assertThat(generator.fileName(payment)).startsWith("recu-rec-2026-00007-");
        assertThat(generator.fileName(payment)).endsWith(".pdf");
    }

    @Test
    @DisplayName("reste generable lorsque le contrat vient d'etre solde")
    void handlesSettledSale() {
        List<Installment> allPaid = installments.stream()
                .peek(installment -> {
                    installment.setAmountPaid(installment.getAmount());
                    installment.setStatus(InstallmentStatus.PAID);
                })
                .toList();

        assertThat(generator.generate(payment, allPaid)).isNotEmpty();
    }
}

package com.creditflow.sale.export;

import com.creditflow.config.AppProperties;
import com.creditflow.customer.domain.Customer;
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

class InvoiceGeneratorTest {

    private InvoiceGenerator generator;
    private CreditSale sale;
    private List<Installment> installments;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getShop().setName("Boutique Test");
        properties.getShop().setCurrency("FCFA");
        generator = new InvoiceGenerator(properties);

        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true)
                .build();
        Product product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(2).build();

        sale = CreditSale.builder()
                .id(42L).reference("VC-2026-00042")
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
                        .amountPaid(new BigDecimal("10000")).status(InstallmentStatus.PARTIAL).build(),
                Installment.builder().id(3L).sale(sale).number(3)
                        .dueDate(LocalDate.of(2026, 8, 5)).amount(new BigDecimal("40000"))
                        .amountPaid(BigDecimal.ZERO).status(InstallmentStatus.PENDING).build());
    }

    @Test
    @DisplayName("produit un PDF valide et non vide")
    void producesAValidPdf() {
        byte[] content = generator.generate(sale, installments);

        assertThat(content).isNotEmpty();
        assertThat(new String(content, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(content.length).isGreaterThan(700);
    }

    @Test
    @DisplayName("numerote la facture de facon lisible et stable")
    void numbersTheInvoice() {
        assertThat(generator.invoiceNumber(sale)).isEqualTo("FAC-2026-00042");
        assertThat(generator.fileName(sale)).startsWith("facture-fac-2026-00042-");
        assertThat(generator.fileName(sale)).endsWith(".pdf");
    }

    @Test
    @DisplayName("liste l'integralite des echeances, pas seulement la prochaine")
    void listsAllInstallments() {
        byte[] withOneInstallment = generator.generate(sale, List.of(installments.get(0)));
        byte[] withAllInstallments = generator.generate(sale, installments);

        assertThat(withAllInstallments.length).isGreaterThan(withOneInstallment.length);
    }

    @Test
    @DisplayName("reste generable quel que soit le statut du contrat")
    void handlesEverySaleStatus() {
        for (SaleStatus status : List.of(SaleStatus.ACTIVE, SaleStatus.COMPLETED, SaleStatus.CANCELLED)) {
            sale.setStatus(status);

            assertThat(generator.generate(sale, installments)).isNotEmpty();
        }
    }
}

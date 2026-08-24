package com.creditflow.sale.export;

import com.creditflow.config.AppProperties;
import com.creditflow.customer.domain.Customer;
import com.creditflow.product.domain.Product;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.shop.domain.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryNoteGeneratorTest {

    private DeliveryNoteGenerator generator;
    private CreditSale sale;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getShop().setName("Boutique Test");
        properties.getShop().setCurrency("FCFA");
        generator = new DeliveryNoteGenerator(properties);

        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true)
                .build();
        Product product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(2).build();
        Shop shop = Shop.builder().id(1L).name("Boutique Dakar").address("Rue 10, Dakar").active(true).build();

        sale = CreditSale.builder()
                .id(42L).reference("VC-2026-00042")
                .customer(customer).product(product).shop(shop)
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
    }

    @Test
    @DisplayName("produit un PDF valide et non vide")
    void producesAValidPdf() {
        byte[] content = generator.generate(sale);

        assertThat(content).isNotEmpty();
        assertThat(new String(content, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(content.length).isGreaterThan(700);
    }

    @Test
    @DisplayName("numerote le bon de livraison de facon lisible et stable")
    void numbersTheDeliveryNote() {
        assertThat(generator.deliveryNoteNumber(sale)).isEqualTo("BL-2026-00042");
        assertThat(generator.fileName(sale)).startsWith("bon-livraison-bl-2026-00042-");
        assertThat(generator.fileName(sale)).endsWith(".pdf");
    }

    @Test
    @DisplayName("reste generable quel que soit le statut du contrat")
    void handlesEverySaleStatus() {
        for (SaleStatus status : List.of(SaleStatus.ACTIVE, SaleStatus.COMPLETED, SaleStatus.CANCELLED)) {
            sale.setStatus(status);

            assertThat(generator.generate(sale)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("le contenu varie selon le produit et le client, preuve qu'ils sont bien affiches")
    void contentVariesWithProductAndCustomer() {
        byte[] withLongNames = generator.generate(sale);

        Customer shortCustomer = Customer.builder()
                .id(2L).firstName("A").lastName("B").phone("770000002").active(true)
                .build();
        Product shortProduct = Product.builder()
                .id(2L).name("TV").category("Electromenager")
                .cashPrice(new BigDecimal("50000")).creditPrice(new BigDecimal("60000"))
                .stock(1).build();
        CreditSale withShortNames = CreditSale.builder()
                .id(42L).reference("VC-2026-00042")
                .customer(shortCustomer).product(shortProduct).shop(sale.getShop())
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

        assertThat(withLongNames.length).isGreaterThan(generator.generate(withShortNames).length);
    }

    @Test
    @DisplayName("n'affiche aucune donnee financiere ni echeancier, contrairement a la facture")
    void noFinancialData() {
        InvoiceGenerator invoiceGenerator = new InvoiceGenerator(new AppProperties());
        byte[] deliveryNote = generator.generate(sale);
        byte[] invoice = invoiceGenerator.generate(sale, List.of());

        assertThat(deliveryNote.length).isLessThan(invoice.length);
    }

    @Test
    @DisplayName("gere proprement une adresse boutique absente")
    void omitsAddressWhenBlank() {
        sale.getShop().setAddress(null);

        assertThat(generator.generate(sale)).isNotEmpty();
    }
}

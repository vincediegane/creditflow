package com.creditflow.sale.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CreditSaleTest {

    @Test
    @DisplayName("accepte les 4 champs garant lorsqu'ils sont renseignes")
    void acceptsGuarantorFields() {
        CreditSale sale = baseSale()
                .guarantorFullName("Moussa Kane")
                .guarantorPhone("770001122")
                .guarantorAddress("Dakar, Sicap Liberte")
                .guarantorCniNumber("1234567890123")
                .build();

        assertThat(sale.getGuarantorFullName()).isEqualTo("Moussa Kane");
        assertThat(sale.getGuarantorPhone()).isEqualTo("770001122");
        assertThat(sale.getGuarantorAddress()).isEqualTo("Dakar, Sicap Liberte");
        assertThat(sale.getGuarantorCniNumber()).isEqualTo("1234567890123");
    }

    @Test
    @DisplayName("les champs garant sont null par defaut si non renseignes")
    void guarantorFieldsAreNullByDefault() {
        CreditSale sale = baseSale().build();

        assertThat(sale.getGuarantorFullName()).isNull();
        assertThat(sale.getGuarantorPhone()).isNull();
        assertThat(sale.getGuarantorAddress()).isNull();
        assertThat(sale.getGuarantorCniNumber()).isNull();
    }

    private CreditSale.CreditSaleBuilder baseSale() {
        return CreditSale.builder()
                .reference("VC-2026-00001")
                .totalPrice(new BigDecimal("150000"))
                .interestAmount(BigDecimal.ZERO)
                .downPayment(BigDecimal.ZERO)
                .financedAmount(new BigDecimal("150000"))
                .installmentCount(3)
                .monthlyAmount(new BigDecimal("50000"))
                .amountPaid(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("150000"))
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(3));
    }
}

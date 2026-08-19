package com.creditflow.common.repository;

import com.creditflow.customer.repository.CustomerSpecifications;
import com.creditflow.payment.repository.PaymentSpecifications;
import com.creditflow.product.repository.ProductSpecifications;
import com.creditflow.sale.repository.InstallmentSpecifications;
import com.creditflow.sale.repository.SaleSpecifications;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un filtre absent doit produire un critere nul, donc aucun predicat dans le SQL.
 *
 * <p>C'est l'invariant qui remplace les anciennes conditions {@code :param IS NULL} :
 * PostgreSQL ne peut pas deduire le type d'un parametre utilise uniquement dans un
 * test de nullite, ce qui faisait echouer les recherches filtrees.</p>
 */
class SpecsTest {

    @Test
    @DisplayName("aucun critere n'est genere pour un filtre absent")
    void absentFiltersProduceNoPredicate() {
        assertThat(CustomerSpecifications.matches(null)).isNull();
        assertThat(CustomerSpecifications.matches("   ")).isNull();

        assertThat(ProductSpecifications.matches(null)).isNull();
        assertThat(ProductSpecifications.hasCategory("")).isNull();
        assertThat(ProductSpecifications.hasStatus(null)).isNull();

        assertThat(SaleSpecifications.matches(null)).isNull();
        assertThat(SaleSpecifications.hasStatus(null)).isNull();
        assertThat(SaleSpecifications.forCustomer(null)).isNull();

        assertThat(InstallmentSpecifications.matches(null)).isNull();
        assertThat(InstallmentSpecifications.hasStatus(null)).isNull();
        assertThat(InstallmentSpecifications.dueFrom(null)).isNull();
        assertThat(InstallmentSpecifications.dueTo(null)).isNull();
        assertThat(InstallmentSpecifications.lateOn(false, LocalDate.now())).isNull();

        assertThat(PaymentSpecifications.matches(null)).isNull();
        assertThat(PaymentSpecifications.hasMethod(null)).isNull();
        assertThat(PaymentSpecifications.paidFrom(null)).isNull();
        assertThat(PaymentSpecifications.paidTo(null)).isNull();
        assertThat(PaymentSpecifications.forCustomer(null)).isNull();
    }

    @Test
    @DisplayName("un critere est genere pour un filtre renseigne")
    void presentFiltersProduceAPredicate() {
        assertThat(CustomerSpecifications.matches("Diallo")).isNotNull();
        assertThat(ProductSpecifications.hasCategory("Telephone")).isNotNull();
        assertThat(SaleSpecifications.forCustomer(1L)).isNotNull();
        assertThat(InstallmentSpecifications.dueFrom(LocalDate.now())).isNotNull();
        assertThat(InstallmentSpecifications.lateOn(true, LocalDate.now())).isNotNull();
        assertThat(PaymentSpecifications.paidTo(LocalDate.now())).isNotNull();
    }

    @Test
    @DisplayName("la combinaison ignore les criteres nuls et reste utilisable a vide")
    void combineIgnoresNullSpecifications() {
        assertThat(Specs.<Object>combine()).isNotNull();
        assertThat(Specs.combine(null, null)).isNotNull();
        assertThat(Specs.combine(null, CustomerSpecifications.matches("Diallo"))).isNotNull();
    }
}

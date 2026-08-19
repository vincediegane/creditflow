package com.creditflow.sale.service;

import com.creditflow.common.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstallmentScheduleGeneratorTest {

    private final InstallmentScheduleGenerator generator = new InstallmentScheduleGenerator();

    @Test
    @DisplayName("repartit un montant divisible en mensualites egales")
    void generatesEqualInstallments() {
        var schedule = generator.generate(new BigDecimal("300000"), 6, LocalDate.of(2026, 1, 5));

        assertThat(schedule.lines()).hasSize(6);
        assertThat(schedule.monthlyAmount()).isEqualByComparingTo("50000");
        assertThat(schedule.lines()).allSatisfy(line ->
                assertThat(line.amount()).isEqualByComparingTo("50000"));
    }

    @Test
    @DisplayName("la derniere echeance absorbe le reliquat d'arrondi")
    void lastInstallmentAbsorbsRounding() {
        var schedule = generator.generate(new BigDecimal("100000"), 3, LocalDate.of(2026, 1, 5));

        assertThat(schedule.monthlyAmount()).isEqualByComparingTo("33333");
        assertThat(schedule.lines().get(0).amount()).isEqualByComparingTo("33333");
        assertThat(schedule.lines().get(1).amount()).isEqualByComparingTo("33333");
        assertThat(schedule.lines().get(2).amount()).isEqualByComparingTo("33334");
    }

    @Test
    @DisplayName("la somme des echeances est exactement egale au montant finance")
    void sumEqualsFinancedAmount() {
        var schedule = generator.generate(new BigDecimal("457000"), 7, LocalDate.of(2026, 3, 10));

        BigDecimal sum = schedule.lines().stream()
                .map(InstallmentScheduleGenerator.ScheduleLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum).isEqualByComparingTo("457000");
    }

    @Test
    @DisplayName("les echeances tombent de mois en mois a partir de la date de debut")
    void dueDatesAreMonthly() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        var schedule = generator.generate(new BigDecimal("120000"), 3, start);

        assertThat(schedule.lines().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(schedule.lines().get(1).dueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(schedule.lines().get(2).dueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(schedule.endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("refuse un montant nul ou negatif")
    void rejectsInvalidAmount() {
        assertThatThrownBy(() -> generator.generate(BigDecimal.ZERO, 3, LocalDate.now()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("refuse un nombre de mensualites invalide")
    void rejectsInvalidCount() {
        assertThatThrownBy(() -> generator.generate(new BigDecimal("100000"), 0, LocalDate.now()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("calcule l'interet a partir d'un taux seul")
    void interestFromRateOnly() {
        BigDecimal interest = generator.interestAmount(new BigDecimal("200000"), new BigDecimal("10"), null);

        assertThat(interest).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("calcule l'interet a partir de frais fixes seuls")
    void interestFromFeeOnly() {
        BigDecimal interest = generator.interestAmount(new BigDecimal("200000"), null, new BigDecimal("3000"));

        assertThat(interest).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("combine taux et frais fixes")
    void interestFromRateAndFee() {
        BigDecimal interest = generator.interestAmount(
                new BigDecimal("200000"), new BigDecimal("10"), new BigDecimal("3000"));

        assertThat(interest).isEqualByComparingTo("23000");
    }

    @Test
    @DisplayName("traite un taux et des frais nuls comme zero, pour la retrocompatibilite")
    void interestNullTreatedAsZero() {
        BigDecimal interest = generator.interestAmount(new BigDecimal("200000"), null, null);

        assertThat(interest).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("arrondit chaque composante de l'interet a l'unite FCFA")
    void interestRoundedToUnit() {
        BigDecimal interest = generator.interestAmount(new BigDecimal("100000"), new BigDecimal("3.33"), null);

        assertThat(interest).isEqualByComparingTo("3330");
    }

    @Test
    @DisplayName("la somme des echeances egale exactement le montant finance avec interet")
    void sumEqualsFinancedAmountWithInterest() {
        BigDecimal totalPrice = new BigDecimal("200000");
        BigDecimal downPayment = new BigDecimal("50000");
        BigDecimal interestAmount = generator.interestAmount(totalPrice, new BigDecimal("10"), new BigDecimal("3000"));
        assertThat(interestAmount).isEqualByComparingTo("23000");

        BigDecimal financed = totalPrice.add(interestAmount).subtract(downPayment);
        assertThat(financed).isEqualByComparingTo("173000");

        var schedule = generator.generate(financed, 6, LocalDate.of(2026, 1, 5));

        BigDecimal sum = schedule.lines().stream()
                .map(InstallmentScheduleGenerator.ScheduleLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum).isEqualByComparingTo("173000");
    }
}

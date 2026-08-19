package com.creditflow.penalty.service;

import com.creditflow.common.util.Money;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PenaltyCalculatorTest {

    private final PenaltyCalculator calculator = new PenaltyCalculator();
    private final LocalDate reference = LocalDate.of(2026, 8, 19);

    private Installment installment(String amount, LocalDate dueDate, String penaltyPaid) {
        return Installment.builder()
                .number(1)
                .dueDate(dueDate)
                .amount(new BigDecimal(amount).setScale(2))
                .amountPaid(Money.ZERO)
                .penaltyPaid(penaltyPaid == null ? null : new BigDecimal(penaltyPaid).setScale(2))
                .status(InstallmentStatus.PENDING)
                .build();
    }

    private PenaltySettings settings(boolean enabled, PenaltyRateType rateType, String rate,
                                      PenaltyPeriod period, String capPercent) {
        return PenaltySettings.builder()
                .id(1L)
                .enabled(enabled)
                .rateType(rateType)
                .rate(new BigDecimal(rate))
                .period(period)
                .capPercent(capPercent == null ? null : new BigDecimal(capPercent))
                .build();
    }

    @Test
    @DisplayName("penalite desactivee -> zero, quel que soit le retard")
    void disabledReturnsZero() {
        Installment installment = installment("50000", reference.minusDays(30), null);
        PenaltySettings settings = settings(false, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("echeance non en retard -> zero")
    void notLateReturnsZero() {
        Installment installment = installment("50000", reference.plusDays(5), null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("FIXED/DAY : 1 jour de retard")
    void fixedDayOneDayLate() {
        Installment installment = installment("50000", reference.minusDays(1), null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("FIXED/DAY : 6 jours de retard")
    void fixedDaySixDaysLate() {
        Installment installment = installment("50000", reference.minusDays(6), null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("PERCENT/DAY : 3 jours de retard sur 50000, taux 2%")
    void percentDayThreeDaysLate() {
        Installment installment = installment("50000", reference.minusDays(3), null);
        PenaltySettings settings = settings(true, PenaltyRateType.PERCENT, "2", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("WEEK : periode entamee comptee entiere (8 jours -> 2 semaines)")
    void weekPeriodPartiallyElapsedCountsFull() {
        Installment installment = installment("50000", reference.minusDays(8), null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "1000", PenaltyPeriod.WEEK, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("plafond en pourcentage de l'echeance")
    void capLimitsGrossAmount() {
        Installment installment = installment("50000", reference.minusDays(16), null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, "10");

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("penalite deja partiellement reglee")
    void partiallyPaidPenaltyIsDeducted() {
        Installment installment = installment("50000", reference.minusDays(6), "1000");
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("penalite payee superieure au brut -> jamais negatif")
    void overpaidPenaltyNeverNegative() {
        Installment installment = installment("50000", reference.minusDays(1), "10000");
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("penaltyPaid null traite comme zero")
    void nullPenaltyPaidTreatedAsZero() {
        Installment installment = installment("50000", reference.minusDays(1), null);
        installment.setPenaltyPaid(null);
        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.outstanding(installment, settings, reference)).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("totalOutstanding somme les echeances en retard, a jour et deja reglees")
    void totalOutstandingSumsAllInstallments() {
        Installment late = installment("50000", reference.minusDays(6), null);
        Installment onTime = installment("50000", reference.plusDays(5), null);
        Installment settled = installment("50000", reference.minusDays(10), null);
        settled.setStatus(InstallmentStatus.PAID);
        settled.setAmountPaid(new BigDecimal("50000.00"));

        PenaltySettings settings = settings(true, PenaltyRateType.FIXED, "500", PenaltyPeriod.DAY, null);

        assertThat(calculator.totalOutstanding(List.of(late, onTime, settled), settings, reference))
                .isEqualByComparingTo("3000");
    }
}

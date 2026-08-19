package com.creditflow.payment.service;

import com.creditflow.common.util.Money;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltyCalculator;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAllocatorTest {

    private final PaymentAllocator allocator = new PaymentAllocator(new PenaltyCalculator());
    private final LocalDate paymentDate = LocalDate.of(2026, 8, 4);

    private List<Installment> schedule(String... amounts) {
        List<Installment> installments = new ArrayList<>();
        int number = 1;
        for (String amount : amounts) {
            installments.add(Installment.builder()
                    .number(number++)
                    .dueDate(LocalDate.of(2026, 1, 5).plusMonths(number - 2L))
                    .amount(new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP))
                    .amountPaid(Money.ZERO)
                    .status(InstallmentStatus.PENDING)
                    .build());
        }
        return installments;
    }

    @Test
    @DisplayName("solde l'echeance la plus ancienne en premier")
    void allocatesOldestFirst() {
        List<Installment> installments = schedule("50000", "50000", "50000");

        BigDecimal leftover = allocator.allocate(installments, new BigDecimal("50000"), paymentDate);

        assertThat(leftover).isEqualByComparingTo("0");
        assertThat(installments.get(0).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(installments.get(0).getPaidAt()).isEqualTo(paymentDate);
        assertThat(installments.get(1).getStatus()).isEqualTo(InstallmentStatus.PENDING);
    }

    @Test
    @DisplayName("marque l'echeance en partiel quand le versement est insuffisant")
    void marksPartial() {
        List<Installment> installments = schedule("50000", "50000");

        allocator.allocate(installments, new BigDecimal("20000"), paymentDate);

        assertThat(installments.get(0).getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        assertThat(installments.get(0).getAmountPaid()).isEqualByComparingTo("20000");
        assertThat(installments.get(0).getRemaining()).isEqualByComparingTo("30000");
        assertThat(installments.get(0).getPaidAt()).isNull();
    }

    @Test
    @DisplayName("deborde sur les echeances suivantes")
    void cascadesOnNextInstallments() {
        List<Installment> installments = schedule("50000", "50000", "50000");

        BigDecimal leftover = allocator.allocate(installments, new BigDecimal("120000"), paymentDate);

        assertThat(leftover).isEqualByComparingTo("0");
        assertThat(installments.get(0).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(installments.get(1).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(installments.get(2).getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        assertThat(installments.get(2).getAmountPaid()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("retourne l'excedent lorsque tout est solde")
    void returnsLeftover() {
        List<Installment> installments = schedule("50000");

        BigDecimal leftover = allocator.allocate(installments, new BigDecimal("70000"), paymentDate);

        assertThat(leftover).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("la remise a zero efface les imputations, y compris la penalite")
    void resetClearsAllocations() {
        List<Installment> installments = schedule("50000", "50000");
        installments.get(0).setDueDate(paymentDate.minusDays(6));
        PenaltySettings settings = penaltySettings(true, "500");

        allocator.applyPenalty(installments, settings, new BigDecimal("3000"), paymentDate);
        allocator.allocate(installments, new BigDecimal("60000"), paymentDate);

        allocator.reset(installments);

        assertThat(installments).allSatisfy(installment -> {
            assertThat(installment.getAmountPaid()).isEqualByComparingTo("0");
            assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
            assertThat(installment.getPaidAt()).isNull();
            assertThat(installment.getPenaltyPaid()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("une echeance non soldee et depassee est en retard")
    void detectsLateInstallment() {
        List<Installment> installments = schedule("50000");
        Installment installment = installments.get(0);

        assertThat(installment.isLate(installment.getDueDate().plusDays(12))).isTrue();
        assertThat(installment.daysLate(installment.getDueDate().plusDays(12))).isEqualTo(12);
        assertThat(installment.isLate(installment.getDueDate())).isFalse();
    }

    @Test
    @DisplayName("applyPenalty impute la penalite sur l'echeance la plus ancienne d'abord")
    void appliesPenaltyOldestFirst() {
        List<Installment> installments = schedule("50000", "50000");
        installments.get(0).setDueDate(paymentDate.minusDays(6));
        installments.get(1).setDueDate(paymentDate.minusDays(2));
        PenaltySettings settings = penaltySettings(true, "500");

        BigDecimal leftover = allocator.applyPenalty(installments, settings, new BigDecimal("3000"), paymentDate);

        assertThat(installments.get(0).getPenaltyPaid()).isEqualByComparingTo("3000");
        assertThat(installments.get(1).getPenaltyPaid()).isEqualByComparingTo("0");
        assertThat(leftover).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("applyPenalty deborde sur l'echeance suivante quand le versement couvre les deux penalites")
    void appliesPenaltyAcrossTwoInstallments() {
        List<Installment> installments = schedule("50000", "50000");
        installments.get(0).setDueDate(paymentDate.minusDays(6));
        installments.get(1).setDueDate(paymentDate.minusDays(2));
        PenaltySettings settings = penaltySettings(true, "500");

        BigDecimal leftover = allocator.applyPenalty(installments, settings, new BigDecimal("4000"), paymentDate);

        assertThat(installments.get(0).getPenaltyPaid()).isEqualByComparingTo("3000");
        assertThat(installments.get(1).getPenaltyPaid()).isEqualByComparingTo("1000");
        assertThat(leftover).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("applyPenalty ne fait rien quand la penalite est desactivee")
    void appliesPenaltyDisabledReturnsAmountUnchanged() {
        List<Installment> installments = schedule("50000");
        installments.get(0).setDueDate(paymentDate.minusDays(6));
        PenaltySettings settings = penaltySettings(false, "500");

        BigDecimal leftover = allocator.applyPenalty(installments, settings, new BigDecimal("3000"), paymentDate);

        assertThat(leftover).isEqualByComparingTo("3000");
        assertThat(installments.get(0).getPenaltyPaid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("le reliquat d'applyPenalty alimente allocate pour le principal")
    void applyPenaltyThenAllocateConsumesPrincipalWithLeftover() {
        List<Installment> installments = schedule("50000");
        installments.get(0).setDueDate(paymentDate.minusDays(6));
        PenaltySettings settings = penaltySettings(true, "500");

        BigDecimal afterPenalty = allocator.applyPenalty(installments, settings, new BigDecimal("20000"), paymentDate);
        BigDecimal afterAllocate = allocator.allocate(installments, afterPenalty, paymentDate);

        assertThat(installments.get(0).getPenaltyPaid()).isEqualByComparingTo("3000");
        assertThat(installments.get(0).getAmountPaid()).isEqualByComparingTo("17000");
        assertThat(installments.get(0).getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        assertThat(afterAllocate).isEqualByComparingTo("0");
    }

    private PenaltySettings penaltySettings(boolean enabled, String rate) {
        return PenaltySettings.builder()
                .id(1L)
                .enabled(enabled)
                .rateType(PenaltyRateType.FIXED)
                .rate(new BigDecimal(rate))
                .period(PenaltyPeriod.DAY)
                .build();
    }
}

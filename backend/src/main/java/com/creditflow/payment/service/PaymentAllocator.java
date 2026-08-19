package com.creditflow.payment.service;

import com.creditflow.common.util.Money;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltyCalculator;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Repartit un versement sur les echeances, de la plus ancienne a la plus
 * recente (regle FIFO : on solde d'abord les retards).
 *
 * <p>Composant pur : il ne connait ni la base de donnees ni le web.</p>
 */
@Component
@RequiredArgsConstructor
public class PaymentAllocator {

    private final PenaltyCalculator penaltyCalculator;

    /**
     * Impute le versement sur la penalite en cours de chaque echeance,
     * de la plus ancienne a la plus recente. Ne touche jamais
     * amountPaid/status/paidAt : ceux-ci restent du ressort d'{@code allocate}.
     *
     * @return la part du versement non consommee par la penalite, destinee a {@code allocate}.
     */
    public BigDecimal applyPenalty(List<Installment> installments, PenaltySettings settings,
                                    BigDecimal amount, LocalDate paymentDate) {
        BigDecimal remaining = Money.round(amount);

        List<Installment> ordered = installments.stream()
                .sorted(Comparator.comparing(Installment::getNumber))
                .toList();

        for (Installment installment : ordered) {
            if (Money.isZeroOrLess(remaining)) {
                break;
            }
            BigDecimal outstanding = penaltyCalculator.outstanding(installment, settings, paymentDate);
            if (Money.isZeroOrLess(outstanding)) {
                continue;
            }

            BigDecimal applied = Money.min(outstanding, remaining);
            installment.setPenaltyPaid(installment.getPenaltyPaid().add(applied));
            remaining = remaining.subtract(applied);
        }

        return remaining;
    }

    /**
     * @return la part du versement qui n'a pas pu etre imputee (excedent).
     */
    public BigDecimal allocate(List<Installment> installments, BigDecimal amount, LocalDate paymentDate) {
        BigDecimal remaining = Money.round(amount);

        List<Installment> ordered = installments.stream()
                .sorted(Comparator.comparing(Installment::getNumber))
                .toList();

        for (Installment installment : ordered) {
            if (Money.isZeroOrLess(remaining)) {
                break;
            }
            BigDecimal due = installment.getRemaining();
            if (Money.isZeroOrLess(due)) {
                continue;
            }

            BigDecimal applied = Money.min(due, remaining);
            installment.setAmountPaid(installment.getAmountPaid().add(applied));
            remaining = remaining.subtract(applied);

            if (installment.getAmountPaid().compareTo(installment.getAmount()) >= 0) {
                installment.setStatus(InstallmentStatus.PAID);
                installment.setPaidAt(paymentDate);
            } else {
                installment.setStatus(InstallmentStatus.PARTIAL);
                installment.setPaidAt(null);
            }
        }

        return remaining;
    }

    /** Remet l'echeancier a zero avant un recalcul complet. */
    public void reset(List<Installment> installments) {
        installments.forEach(installment -> {
            installment.setAmountPaid(Money.ZERO);
            installment.setStatus(InstallmentStatus.PENDING);
            installment.setPaidAt(null);
            installment.setPenaltyPaid(Money.ZERO);
        });
    }
}

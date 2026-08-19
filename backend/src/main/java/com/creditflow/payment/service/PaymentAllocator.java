package com.creditflow.payment.service;

import com.creditflow.common.util.Money;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
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
public class PaymentAllocator {

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
        });
    }
}

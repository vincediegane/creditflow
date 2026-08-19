package com.creditflow.penalty.service;

import com.creditflow.common.util.Money;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.sale.domain.Installment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Calcule la penalite de retard due sur une echeance, a la lecture, sans
 * jamais persister de valeur derivee. Composant pur.
 */
@Component
public class PenaltyCalculator {

    public BigDecimal outstanding(Installment installment, PenaltySettings settings, LocalDate reference) {
        if (!settings.isEnabled()) {
            return BigDecimal.ZERO;
        }

        long daysLate = installment.daysLate(reference);
        if (daysLate == 0) {
            return BigDecimal.ZERO;
        }

        int periodDays = settings.getPeriod() == PenaltyPeriod.WEEK ? 7 : 1;
        long periodsElapsed = (daysLate + periodDays - 1) / periodDays;

        BigDecimal gross = switch (settings.getRateType()) {
            case FIXED -> settings.getRate().multiply(BigDecimal.valueOf(periodsElapsed));
            case PERCENT -> installment.getAmount()
                    .multiply(settings.getRate())
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(periodsElapsed));
        };

        if (settings.getCapPercent() != null) {
            BigDecimal cap = installment.getAmount()
                    .multiply(settings.getCapPercent())
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            gross = Money.min(gross, cap);
        }

        return Money.round(Money.max(gross.subtract(Money.nullToZero(installment.getPenaltyPaid())), BigDecimal.ZERO));
    }

    public BigDecimal totalOutstanding(List<Installment> installments, PenaltySettings settings, LocalDate reference) {
        if (installments == null || installments.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return installments.stream()
                .map(installment -> outstanding(installment, settings, reference))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

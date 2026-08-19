package com.creditflow.sale.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.util.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul pur de l'echeancier : aucune dependance a la base ou au web,
 * ce qui le rend directement testable unitairement.
 *
 * <p>Regle : toutes les echeances portent le meme montant mensuel arrondi a
 * l'unite, la derniere absorbe le reliquat d'arrondi afin que la somme des
 * echeances soit exactement egale au montant finance.</p>
 */
@Component
public class InstallmentScheduleGenerator {

    public record ScheduleLine(int number, LocalDate dueDate, BigDecimal amount) {
    }

    public record Schedule(BigDecimal monthlyAmount, LocalDate endDate, List<ScheduleLine> lines) {
    }

    public Schedule generate(BigDecimal financedAmount, int installmentCount, LocalDate startDate) {
        if (financedAmount == null || financedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Le montant a financer doit etre superieur a zero");
        }
        if (installmentCount < 1) {
            throw new BusinessRuleException("Le nombre de mensualites doit etre d'au moins 1");
        }
        if (startDate == null) {
            throw new BusinessRuleException("La date de debut est obligatoire");
        }

        BigDecimal financed = Money.round(financedAmount);
        BigDecimal monthly = financed
                .divide(BigDecimal.valueOf(installmentCount), 0, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);

        List<ScheduleLine> lines = new ArrayList<>(installmentCount);
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (int number = 1; number <= installmentCount; number++) {
            LocalDate dueDate = startDate.plusMonths(number - 1L);
            BigDecimal amount = number < installmentCount
                    ? monthly
                    : financed.subtract(allocated);
            lines.add(new ScheduleLine(number, dueDate, amount));
            allocated = allocated.add(amount);
        }

        LocalDate endDate = lines.get(lines.size() - 1).dueDate();
        return new Schedule(monthly, endDate, List.copyOf(lines));
    }

    public BigDecimal interestAmount(BigDecimal totalPrice, BigDecimal interestRate, BigDecimal interestFee) {
        BigDecimal total = Money.round(totalPrice);
        BigDecimal rate = Money.nullToZero(interestRate);
        BigDecimal fee = Money.round(Money.nullToZero(interestFee));
        BigDecimal fromRate = Money.round(
                total.multiply(rate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return fromRate.add(fee);
    }
}

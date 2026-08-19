package com.creditflow.sale.mapper;

import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.dto.SaleResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Assemble les DTO de vente. Les indicateurs de retard sont calcules a la
 * lecture (aucune tache planifiee n'est necessaire pour rester coherent).
 */
@Component
public class SaleMapper {

    public SaleResponse toResponse(CreditSale sale, List<Installment> installments, LocalDate today) {
        List<Installment> lines = installments == null ? List.of() : installments;

        long lateCount = lines.stream().filter(i -> i.isLate(today)).count();
        long maxDaysLate = lines.stream().mapToLong(i -> i.daysLate(today)).max().orElse(0L);
        int paidCount = (int) lines.stream().filter(Installment::isSettled).count();

        Optional<Installment> next = lines.stream()
                .filter(i -> !i.isSettled())
                .min(Comparator.comparing(Installment::getDueDate));

        return new SaleResponse(
                sale.getId(),
                sale.getReference(),
                sale.getCustomer().getId(),
                sale.getCustomer().getFullName(),
                sale.getCustomer().getPhone(),
                sale.getProduct().getId(),
                sale.getProduct().getName(),
                sale.getTotalPrice(),
                sale.getDownPayment(),
                sale.getFinancedAmount(),
                sale.getInstallmentCount(),
                sale.getMonthlyAmount(),
                sale.getAmountPaid(),
                sale.getRemainingAmount(),
                sale.getStartDate(),
                sale.getEndDate(),
                sale.getStatus(),
                lateCount > 0 && sale.getStatus() == SaleStatus.ACTIVE,
                lateCount,
                maxDaysLate,
                next.map(Installment::getDueDate).orElse(null),
                next.map(Installment::getRemaining).orElse(null),
                paidCount,
                sale.getNotes(),
                sale.getCreatedAt(),
                sale.getCreatedBy(),
                sale.getUpdatedBy());
    }

    public InstallmentResponse toResponse(Installment installment, LocalDate today) {
        CreditSale sale = installment.getSale();
        boolean late = installment.isLate(today);
        BigDecimal remaining = installment.getRemaining();

        return new InstallmentResponse(
                installment.getId(),
                sale.getId(),
                sale.getReference(),
                sale.getCustomer().getId(),
                sale.getCustomer().getFullName(),
                sale.getCustomer().getPhone(),
                sale.getProduct().getName(),
                installment.getNumber(),
                installment.getDueDate(),
                installment.getAmount(),
                installment.getAmountPaid(),
                remaining,
                installment.getStatus(),
                displayStatus(installment, late),
                late,
                installment.daysLate(today),
                installment.getPaidAt());
    }

    private String displayStatus(Installment installment, boolean late) {
        if (installment.getStatus() == InstallmentStatus.PAID) {
            return "PAID";
        }
        if (late) {
            return "LATE";
        }
        return installment.getStatus() == InstallmentStatus.PARTIAL ? "PARTIAL" : "PENDING";
    }
}

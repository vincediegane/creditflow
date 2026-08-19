package com.creditflow.sale.domain;

import com.creditflow.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "installments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Installment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private CreditSale sale;

    @Column(name = "number", nullable = false)
    private Integer number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_paid", nullable = false, precision = 15, scale = 2)
    private BigDecimal amountPaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InstallmentStatus status = InstallmentStatus.PENDING;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "penalty_paid", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal penaltyPaid = Money.ZERO;

    public BigDecimal getRemaining() {
        return amount.subtract(amountPaid == null ? BigDecimal.ZERO : amountPaid).max(BigDecimal.ZERO);
    }

    public boolean isSettled() {
        return status == InstallmentStatus.PAID;
    }

    /** Une echeance est en retard si elle n'est pas soldee et que la date prevue est depassee. */
    public boolean isLate(LocalDate reference) {
        return !isSettled() && dueDate.isBefore(reference);
    }

    public long daysLate(LocalDate reference) {
        if (!isLate(reference)) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, reference);
    }
}

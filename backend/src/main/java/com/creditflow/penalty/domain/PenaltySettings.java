package com.creditflow.penalty.domain;

import com.creditflow.common.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Reglage global de penalite de retard : ligne unique (id = 1), jamais
 * exposee telle quelle cote frontend.
 */
@Entity
@Table(name = "penalty_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PenaltySettings extends Auditable {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 20)
    @Builder.Default
    private PenaltyRateType rateType = PenaltyRateType.FIXED;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PenaltyPeriod period = PenaltyPeriod.DAY;

    @Column(name = "cap_percent", precision = 5, scale = 2)
    private BigDecimal capPercent;
}

package com.creditflow.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Le FCFA n'a pas de sous-unite : tous les montants sont arrondis a l'unite.
 */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private Money() {
    }

    /** Arrondit a l'unite monetaire puis normalise l'echelle de stockage. */
    public static BigDecimal round(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    public static boolean isZeroOrLess(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }
}

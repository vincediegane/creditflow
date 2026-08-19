package com.creditflow.dataimport.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compte rendu d'une reprise de donnees.
 *
 * <p>Volontairement detaille : le commercant doit pouvoir corriger son fichier
 * ligne par ligne avant de valider definitivement.</p>
 */
public record ImportReport(
        boolean dryRun,
        boolean applied,
        int totalRows,
        int validRows,
        int newCustomers,
        int existingCustomers,
        int newProducts,
        int createdSales,
        int recordedPayments,
        BigDecimal totalFinanced,
        BigDecimal totalAlreadyPaid,
        BigDecimal totalRemaining,
        List<RowError> errors,
        List<RowPreview> preview,
        String message
) {

    /** Erreur bloquante sur une ligne du fichier. */
    public record RowError(int line, String column, String value, String reason) {
    }

    /** Ce qui sera cree si la reprise est confirmee. */
    public record RowPreview(
            int line,
            String customer,
            String phone,
            boolean customerIsNew,
            String product,
            boolean productIsNew,
            BigDecimal totalPrice,
            BigDecimal downPayment,
            int installmentCount,
            String startDate,
            BigDecimal alreadyPaid,
            BigDecimal remaining
    ) {
    }
}

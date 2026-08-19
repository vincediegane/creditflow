package com.creditflow.report.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Representation neutre d'un rapport : le meme objet alimente l'ecran,
 * l'export Excel et l'export PDF.
 */
public record ReportData(
        ReportType type,
        String title,
        String period,
        List<Column> columns,
        List<List<Object>> rows,
        List<Total> totals,
        LocalDateTime generatedAt
) {

    public enum ColumnType {
        TEXT,
        /** Nombre simple (jours de retard, quantite). */
        NUMBER,
        /** Montant : affiche avec le separateur de milliers et la devise. */
        MONEY,
        DATE
    }

    public record Column(String label, ColumnType type) {

        public static Column text(String label) {
            return new Column(label, ColumnType.TEXT);
        }

        public static Column number(String label) {
            return new Column(label, ColumnType.NUMBER);
        }

        public static Column money(String label) {
            return new Column(label, ColumnType.MONEY);
        }

        public static Column date(String label) {
            return new Column(label, ColumnType.DATE);
        }
    }

    public record Total(String label, Object value, ColumnType type) {
    }
}

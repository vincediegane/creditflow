package com.creditflow.report.dto;

public enum ReportType {
    /** Paiements du jour. */
    DAILY_PAYMENTS,
    /** Paiements du mois. */
    MONTHLY_PAYMENTS,
    /** Clients en retard. */
    LATE_CUSTOMERS,
    /** Creances restantes. */
    OUTSTANDING
}

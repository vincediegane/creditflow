package com.creditflow.sale.domain;

public enum SaleStatus {
    /** Contrat en cours de remboursement. */
    ACTIVE,
    /** Contrat entierement solde. */
    COMPLETED,
    /** Contrat annule. */
    CANCELLED
}

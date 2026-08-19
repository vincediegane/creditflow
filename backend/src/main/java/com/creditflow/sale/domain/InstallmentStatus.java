package com.creditflow.sale.domain;

public enum InstallmentStatus {
    /** Aucun versement recu. */
    PENDING,
    /** Versement partiel recu. */
    PARTIAL,
    /** Echeance soldee. */
    PAID
}

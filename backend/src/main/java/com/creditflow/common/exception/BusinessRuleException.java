package com.creditflow.common.exception;

/**
 * Violation d'une regle metier (montant invalide, contrat deja solde, ...).
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}

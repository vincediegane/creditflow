package com.creditflow.notification.dto;

/**
 * Demande de generation d'un message de relance.
 * Fournir au choix un contrat ou un client.
 */
public record ReminderRequest(
        Long saleId,
        Long customerId,
        /** Gabarit personnalise, sinon celui configure dans l'application. */
        String template
) {
}

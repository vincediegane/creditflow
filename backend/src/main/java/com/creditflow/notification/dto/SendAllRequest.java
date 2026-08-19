package com.creditflow.notification.dto;

/**
 * Demande d'envoi en masse. Gabarit optionnel, sinon celui configure dans l'application.
 */
public record SendAllRequest(
        String template
) {
}

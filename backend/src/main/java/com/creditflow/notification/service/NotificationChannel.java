package com.creditflow.notification.service;

/**
 * Point d'extension pour l'envoi des relances.
 *
 * <p>Le MVP fournit uniquement {@link ManualCopyChannel} : le commercant copie
 * le message genere. Pour brancher WhatsApp Cloud API plus tard, il suffit
 * d'ajouter une implementation annotee
 * {@code @ConditionalOnProperty(name = "app.notification.channel", havingValue = "whatsapp")}
 * et de basculer la propriete, sans toucher au reste du code.</p>
 */
public interface NotificationChannel {

    /** Identifiant du canal, expose dans l'API. */
    String name();

    /**
     * @return true si le message a reellement ete transmis au destinataire.
     */
    boolean send(String phone, String message);
}

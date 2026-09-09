package com.creditflow.notification.service;

/**
 * Point d'extension pour l'envoi d'emails. Separee de {@link NotificationChannel}
 * (signatures et cycles de vie incompatibles : "to, subject, body" vs "phone, message",
 * pilotage par un booleen dedie app.mail.enabled plutot que par un choix de canal).
 */
public interface EmailChannel {

    /**
     * @return true si l'email a reellement ete transmis au serveur SMTP.
     */
    boolean send(String to, String subject, String body);
}

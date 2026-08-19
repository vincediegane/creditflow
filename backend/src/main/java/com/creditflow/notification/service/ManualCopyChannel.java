package com.creditflow.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Canal par defaut du MVP : aucun envoi automatique, le message est simplement
 * retourne a l'interface pour etre copie dans WhatsApp ou SMS.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.channel", havingValue = "manual", matchIfMissing = true)
public class ManualCopyChannel implements NotificationChannel {

    @Override
    public String name() {
        return "MANUAL_COPY";
    }

    @Override
    public boolean send(String phone, String message) {
        log.debug("Relance generee pour {} (copie manuelle)", phone);
        return false;
    }
}

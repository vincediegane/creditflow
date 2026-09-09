package com.creditflow.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Canal par defaut (app.mail.enabled=false ou absent) : aucun envoi reel,
 * meme patron de repli silencieux que ManualCopyChannel.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class NoopEmailChannel implements EmailChannel {

    @Override
    public boolean send(String to, String subject, String body) {
        log.debug("Email non envoye (canal mail desactive) : destinataire={}, sujet={}", to, subject);
        return false;
    }
}

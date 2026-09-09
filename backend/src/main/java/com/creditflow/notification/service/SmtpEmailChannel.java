package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Canal d'envoi reel via un serveur SMTP configure (app.mail.enabled=true).
 * Ne propage jamais d'exception : un echec SMTP est journalise en warn et
 * retourne false, jamais bloquant pour l'appelant (meme patron defensif que
 * WhatsAppCloudApiChannel).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpEmailChannel implements EmailChannel {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    @Override
    public boolean send(String to, String subject, String body) {
        try {
            AppProperties.Mail mail = properties.getMail();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("%s <%s>".formatted(mail.getFromName(), mail.getFrom()));
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            log.warn("Echec de l'envoi d'email vers {} : {}", to, e.getMessage());
            return false;
        }
    }
}

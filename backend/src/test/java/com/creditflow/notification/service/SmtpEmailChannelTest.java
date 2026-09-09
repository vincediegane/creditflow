package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class SmtpEmailChannelTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailChannel channel;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getMail().setFrom("no-reply@creditflow.test");
        properties.getMail().setFromName("CreditFlow");
        channel = new SmtpEmailChannel(mailSender, properties);
    }

    @Test
    @DisplayName("retourne true quand le serveur SMTP accepte l'envoi")
    void sendReturnsTrueOnSuccess() {
        assertThat(channel.send("client@test.com", "Sujet", "Corps")).isTrue();
    }

    @Test
    @DisplayName("retourne false sans exception quand le SMTP est indisponible")
    void sendReturnsFalseOnMailException() {
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(channel.send("client@test.com", "Sujet", "Corps")).isFalse();
    }
}

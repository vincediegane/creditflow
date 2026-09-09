package com.creditflow.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Bean JavaMailSender conditionne par app.mail.enabled=true, en plus de la
 * garde @ConditionalOnProperty sur SmtpEmailChannel lui-meme : sans cette
 * double garde, Spring tenterait de construire un JavaMailSenderImpl avec
 * des parametres vides au demarrage meme en mode desactive.
 *
 * Utilise des proprietes app.mail.* (pas spring.mail.*) : la MailSenderAutoConfiguration
 * de Spring Boot (activee sur spring.mail.host) ne se declenche donc jamais et
 * n'entre pas en conflit avec ce bean.
 */
@Configuration
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MailConfig {

    private final AppProperties properties;

    @Bean
    public JavaMailSender javaMailSender() {
        AppProperties.Mail mail = properties.getMail();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.getHost());
        sender.setPort(mail.getPort());
        sender.setUsername(mail.getUsername());
        sender.setPassword(mail.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.transport.protocol", "smtp");
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.starttls.enable", "true");

        return sender;
    }
}

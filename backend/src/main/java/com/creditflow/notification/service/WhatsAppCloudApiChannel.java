package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canal d'envoi automatique via l'API Meta WhatsApp Cloud.
 *
 * <p>Utilise un message de type {@code template} (et non {@code text}) afin de
 * fonctionner meme hors de la fenetre de 24h suivant le dernier message entrant
 * du client, cas normal d'une relance proactive de recouvrement. Ce template doit
 * etre cree et approuve au prealable dans Meta Business Manager, avec exactement
 * une variable de corps ({@code {{1}}}), nom = {@code app.notification.whatsapp.template-name},
 * langue = {@code app.notification.whatsapp.template-language-code}. Tant que ce
 * template n'existe pas ou n'est pas approuve, chaque envoi echouera (HTTP 4xx),
 * ce qui est le comportement attendu, pas un bug.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.channel", havingValue = "whatsapp")
@RequiredArgsConstructor
public class WhatsAppCloudApiChannel implements NotificationChannel {

    private final RestTemplate restTemplate;
    private final AppProperties properties;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    @Override
    public String name() {
        return "WHATSAPP_CLOUD_API";
    }

    @Override
    public boolean send(String phone, String message) {
        Optional<String> normalized = phoneNumberNormalizer.normalize(phone);
        if (normalized.isEmpty()) {
            log.warn("Numero de telephone non normalisable, envoi WhatsApp annule : {}", phone);
            return false;
        }

        AppProperties.Whatsapp whatsapp = properties.getNotification().getWhatsapp();
        String url = "%s/%s/%s/messages".formatted(
                whatsapp.getApiBaseUrl(), whatsapp.getApiVersion(), whatsapp.getPhoneNumberId());

        String toWithoutPlus = normalized.get().substring(1);
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toWithoutPlus,
                "type", "template",
                "template", Map.of(
                        "name", whatsapp.getTemplateName(),
                        "language", Map.of("code", whatsapp.getTemplateLanguageCode()),
                        "components", List.of(Map.of(
                                "type", "body",
                                "parameters", List.of(Map.of("type", "text", "text", message))))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(whatsapp.getAccessToken());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.error("Echec de l'envoi WhatsApp vers {} : {}", phone, e.getMessage());
            return false;
        }
    }
}

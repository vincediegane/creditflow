package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppCloudApiChannelTest {

    @Mock
    private RestTemplate restTemplate;

    private WhatsAppCloudApiChannel channel;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getNotification().setDefaultCountryCode("+221");
        properties.getNotification().getWhatsapp().setPhoneNumberId("123456");
        properties.getNotification().getWhatsapp().setAccessToken("test-token");

        PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer(properties);
        channel = new WhatsAppCloudApiChannel(restTemplate, properties, normalizer);
    }

    @Test
    @DisplayName("retourne true quand Meta repond 2xx")
    void returnsTrueOnSuccess() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertThat(channel.send("770000001", "Bonjour")).isTrue();
    }

    @Test
    @DisplayName("retourne false sans exception quand Meta repond en erreur HTTP")
    void returnsFalseOnHttpError() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        null, null, null));

        assertThat(channel.send("770000001", "Bonjour")).isFalse();
    }

    @Test
    @DisplayName("retourne false sans exception en cas d'erreur reseau")
    void returnsFalseOnNetworkError() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThat(channel.send("770000001", "Bonjour")).isFalse();
    }

    @Test
    @DisplayName("ne fait aucun appel reseau pour un numero non normalisable")
    void doesNotCallRestTemplateForInvalidPhone() {
        assertThat(channel.send("abc", "Bonjour")).isFalse();
        verifyNoInteractions(restTemplate);
    }
}

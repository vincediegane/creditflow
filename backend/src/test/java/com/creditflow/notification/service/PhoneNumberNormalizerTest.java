package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumberNormalizerTest {

    private PhoneNumberNormalizer normalizer;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getNotification().setDefaultCountryCode("+221");
        normalizer = new PhoneNumberNormalizer(properties);
    }

    @Test
    @DisplayName("prefixe un numero local avec l'indicatif par defaut")
    void prefixesLocalNumber() {
        assertThat(normalizer.normalize("770000001")).contains("+221770000001");
    }

    @Test
    @DisplayName("retire les espaces d'un numero deja au format international")
    void stripsWhitespaceFromInternationalNumber() {
        assertThat(normalizer.normalize("+221 77 000 00 01")).contains("+221770000001");
    }

    @Test
    @DisplayName("remplace le prefixe 00 par +")
    void replacesDoubleZeroPrefix() {
        assertThat(normalizer.normalize("00221770000001")).contains("+221770000001");
    }

    @Test
    @DisplayName("retourne vide pour un numero null, vide ou non numerique")
    void returnsEmptyForInvalidInput() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize("abc")).isEmpty();
    }

    @Test
    @DisplayName("retourne vide pour un numero trop court apres nettoyage")
    void returnsEmptyForTooShortNumber() {
        assertThat(normalizer.normalize("+221")).isEmpty();
    }
}

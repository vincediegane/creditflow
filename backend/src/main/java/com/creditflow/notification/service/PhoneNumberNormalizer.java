package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalise un numero de telephone stocke au format local (ex. {@code "770000001"})
 * vers un format E.164 simplifie (ex. {@code "+221770000001"}) exploitable par les
 * canaux d'envoi automatique.
 *
 * <p><b>Limite connue, assumee pour ce ticket</b> : un numero deja prefixe par un
 * indicatif pays mais sans {@code +} (ex. {@code "221770000001"}) est double par
 * erreur (donnerait {@code "+221221770000001"}). Aucune donnee du depot n'est dans
 * ce cas ; a corriger dans un ticket dedie si le besoin se presente.</p>
 */
@Component
@RequiredArgsConstructor
public class PhoneNumberNormalizer {

    private static final Pattern E164_SIMPLIFIED = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern NON_DIGIT_OR_PLUS = Pattern.compile("[^0-9+]");

    private final AppProperties properties;

    public Optional<String> normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return Optional.empty();
        }

        String cleaned = NON_DIGIT_OR_PLUS.matcher(rawPhone).replaceAll("");

        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2);
        } else if (!cleaned.startsWith("+")) {
            cleaned = properties.getNotification().getDefaultCountryCode() + cleaned;
        }

        if (!E164_SIMPLIFIED.matcher(cleaned).matches()) {
            return Optional.empty();
        }

        return Optional.of(cleaned);
    }
}

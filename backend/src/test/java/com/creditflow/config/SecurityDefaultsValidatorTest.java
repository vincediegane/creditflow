package com.creditflow.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le garde-fou qui empeche de livrer une boutique avec les secrets publics
 * de la demonstration.
 */
class SecurityDefaultsValidatorTest {

    private AppProperties properties(boolean strict) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setStrict(strict);
        properties.getSecurity().getJwt()
                .setSecret(SecurityDefaultsValidator.DEFAULT_JWT_SECRET);
        properties.getAdmin().setPassword(SecurityDefaultsValidator.DEFAULT_ADMIN_PASSWORD);
        properties.getDemo().setSeed(true);
        return properties;
    }

    private SecurityDefaultsValidator validator(AppProperties properties, String dbPassword) {
        SecurityDefaultsValidator validator = new SecurityDefaultsValidator(properties);
        ReflectionTestUtils.setField(validator, "databasePassword", dbPassword);
        return validator;
    }

    @Test
    @DisplayName("en production, refuse de demarrer avec les secrets de livraison")
    void refusesShippedSecretsInStrictMode() {
        SecurityDefaultsValidator validator =
                validator(properties(true), SecurityDefaultsValidator.DEFAULT_DB_PASSWORD);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("DEMO_SEED");
    }

    @Test
    @DisplayName("en demonstration, tolere les secrets par defaut")
    void allowsDefaultsInDemoMode() {
        SecurityDefaultsValidator validator =
                validator(properties(false), SecurityDefaultsValidator.DEFAULT_DB_PASSWORD);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("en production, accepte une configuration correctement durcie")
    void acceptsHardenedConfiguration() {
        AppProperties properties = properties(true);
        properties.getSecurity().getJwt().setSecret("une-cle-de-production-vraiment-longue-42-chars");
        properties.getAdmin().setPassword("MotDePasseSolide2026");
        properties.getDemo().setSeed(false);

        SecurityDefaultsValidator validator = validator(properties, "un-mot-de-passe-base-solide");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("en production, refuse un secret JWT trop court")
    void refusesShortJwtSecret() {
        AppProperties properties = properties(true);
        properties.getSecurity().getJwt().setSecret("trop-court");
        properties.getAdmin().setPassword("MotDePasseSolide2026");
        properties.getDemo().setSeed(false);

        SecurityDefaultsValidator validator = validator(properties, "un-mot-de-passe-base-solide");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 caracteres");
    }

    @Test
    @DisplayName("en production, refuse un mot de passe administrateur trop court")
    void refusesShortAdminPassword() {
        AppProperties properties = properties(true);
        properties.getSecurity().getJwt().setSecret("une-cle-de-production-vraiment-longue-42-chars");
        properties.getAdmin().setPassword("court");
        properties.getDemo().setSeed(false);

        SecurityDefaultsValidator validator = validator(properties, "un-mot-de-passe-base-solide");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }
}

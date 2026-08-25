package com.creditflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie que le demarrage echoue quand NOTIFICATION_CHANNEL=whatsapp est configure sur une
 * instance dont la formule n'inclut pas le canal WhatsApp automatique, et qu'il n'echoue dans
 * aucun autre cas (y compris la configuration par defaut, sans regression pour les instances
 * existantes).
 */
class PlanConfigValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, PlanConfigValidator.class);

    @Test
    void refusesStartupWhenWhatsappChannelSelectedWithoutPlanEntitlement() {
        contextRunner
                .withPropertyValues(
                        "app.notification.channel=whatsapp",
                        "app.plan.whatsapp-auto=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("WHATSAPP")
                            .hasStackTraceContaining("formule");
                });
    }

    @Test
    void allowsStartupWhenChannelIsManualEvenWithoutPlanEntitlement() {
        contextRunner
                .withPropertyValues(
                        "app.notification.channel=manual",
                        "app.plan.whatsapp-auto=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void allowsStartupWhenWhatsappChannelSelectedWithPlanEntitlement() {
        contextRunner
                .withPropertyValues(
                        "app.notification.channel=whatsapp",
                        "app.plan.whatsapp-auto=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void allowsStartupWithDefaultConfiguration() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties
    static class TestConfig {

        @Bean
        @ConfigurationProperties(prefix = "app")
        AppProperties appProperties() {
            return new AppProperties();
        }
    }
}

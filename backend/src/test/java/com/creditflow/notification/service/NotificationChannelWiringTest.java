package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import com.creditflow.config.HttpClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie que le canal automatique WhatsApp n'est instancie que si
 * {@code app.notification.channel=whatsapp} est explicitement configure, et qu'aucun
 * secret WhatsApp n'est requis (ni ne fait planter le contexte) en configuration par
 * defaut (canal manual).
 */
class NotificationChannelWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class, HttpClientConfig.class,
                    ManualCopyChannel.class, WhatsAppCloudApiChannel.class, PhoneNumberNormalizer.class);

    @Test
    void defaultConfigurationOnlyActivatesManualChannel() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ManualCopyChannel.class);
            assertThat(context).doesNotHaveBean(WhatsAppCloudApiChannel.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void whatsappChannelIsActivatedOnlyWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "app.notification.channel=whatsapp",
                        "app.notification.whatsapp.phone-number-id=123456",
                        "app.notification.whatsapp.access-token=test-token")
                .run(context -> {
                    assertThat(context).hasSingleBean(WhatsAppCloudApiChannel.class);
                    assertThat(context).doesNotHaveBean(ManualCopyChannel.class);
                    assertThat(context).hasNotFailed();
                });
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

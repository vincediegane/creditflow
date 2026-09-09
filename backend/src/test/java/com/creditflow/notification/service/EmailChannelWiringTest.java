package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import com.creditflow.config.MailConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

class EmailChannelWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, MailConfig.class,
                    NoopEmailChannel.class, SmtpEmailChannel.class);

    @Test
    void defaultConfigurationOnlyActivatesNoopChannel() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NoopEmailChannel.class);
            assertThat(context).doesNotHaveBean(SmtpEmailChannel.class);
            assertThat(context).doesNotHaveBean(JavaMailSender.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void smtpChannelIsActivatedOnlyWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.host=smtp.test",
                        "app.mail.from=no-reply@creditflow.test")
                .run(context -> {
                    assertThat(context).hasSingleBean(SmtpEmailChannel.class);
                    assertThat(context).hasSingleBean(JavaMailSender.class);
                    assertThat(context).doesNotHaveBean(NoopEmailChannel.class);
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

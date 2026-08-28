package com.creditflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie que le demarrage echoue quand STORAGE_PROVIDER=s3 est configure sans les
 * variables requises (absentes ou vides), et qu'il n'echoue dans aucun autre cas.
 */
class StorageConfigValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, StorageConfigValidator.class);

    @Test
    void refusesStartupWhenS3ProviderMissesAllVariables() {
        contextRunner
                .withPropertyValues("app.storage.provider=s3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("STORAGE_S3_BUCKET")
                            .hasStackTraceContaining("STORAGE_S3_REGION")
                            .hasStackTraceContaining("STORAGE_S3_ACCESS_KEY")
                            .hasStackTraceContaining("STORAGE_S3_SECRET_KEY");
                });
    }

    @Test
    void refusesStartupWhenS3ProviderHasBlankVariable() {
        contextRunner
                .withPropertyValues(
                        "app.storage.provider=s3",
                        "app.storage.s3.bucket=",
                        "app.storage.s3.region=eu-west-3",
                        "app.storage.s3.access-key=AKIA",
                        "app.storage.s3.secret-key=secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("STORAGE_S3_BUCKET");
                });
    }

    @Test
    void allowsStartupWhenS3ProviderHasAllVariables() {
        contextRunner
                .withPropertyValues(
                        "app.storage.provider=s3",
                        "app.storage.s3.bucket=creditflow-bucket",
                        "app.storage.s3.region=eu-west-3",
                        "app.storage.s3.access-key=AKIA",
                        "app.storage.s3.secret-key=secret")
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

package com.creditflow.common.storage;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie qu'un seul bean {@link DocumentStorage} est actif a la fois, selectionne par
 * {@code app.storage.provider}, sans branchement disperse dans la logique metier.
 */
class DocumentStorageWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class,
                    LocalDiskStorage.class, S3DocumentStorage.class, DocumentValidation.class);

    @Test
    void defaultConfigurationOnlyActivatesLocalDiskStorage(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues("app.storage.upload-dir=" + tempDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalDiskStorage.class);
                    assertThat(context).doesNotHaveBean(S3DocumentStorage.class);
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void s3ProviderOnlyActivatesS3DocumentStorage(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues(
                        "app.storage.upload-dir=" + tempDir,
                        "app.storage.provider=s3",
                        "app.storage.s3.bucket=creditflow-bucket",
                        "app.storage.s3.region=eu-west-3",
                        "app.storage.s3.access-key=AKIA",
                        "app.storage.s3.secret-key=secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(S3DocumentStorage.class);
                    assertThat(context).doesNotHaveBean(LocalDiskStorage.class);
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

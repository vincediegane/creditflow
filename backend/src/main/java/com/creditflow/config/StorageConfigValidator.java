package com.creditflow.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Garde-fou de mise en production du stockage S3.
 *
 * <p>Si {@code app.storage.provider=s3}, l'application refuse de demarrer tant que le
 * bucket, la region et les identifiants ne sont pas renseignes, plutot que d'echouer au
 * premier upload une fois en production.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageConfigValidator {

    private final AppProperties properties;

    @PostConstruct
    void validate() {
        if (!"s3".equals(properties.getStorage().getProvider())) {
            return;
        }

        List<String> missing = new ArrayList<>();
        AppProperties.S3 s3 = properties.getStorage().getS3();
        if (!StringUtils.hasText(s3.getBucket())) {
            missing.add("STORAGE_S3_BUCKET");
        }
        if (!StringUtils.hasText(s3.getRegion())) {
            missing.add("STORAGE_S3_REGION");
        }
        if (!StringUtils.hasText(s3.getAccessKey())) {
            missing.add("STORAGE_S3_ACCESS_KEY");
        }
        if (!StringUtils.hasText(s3.getSecretKey())) {
            missing.add("STORAGE_S3_SECRET_KEY");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("""
                    Demarrage refuse : STORAGE_PROVIDER=s3 est configure mais les variables \
                    suivantes sont manquantes ou vides : %s.

                    Renseignez-les, ou repassez STORAGE_PROVIDER=local, puis redemarrez."""
                    .formatted(String.join(", ", missing)));
        }
        log.info("Controle de stockage au demarrage : configuration S3 complete.");
    }
}

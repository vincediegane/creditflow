package com.creditflow.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Garde-fou de mise en production.
 *
 * <p>Les valeurs livrees par defaut permettent de lancer une demonstration
 * immediatement, mais elles sont publiques : les laisser en production
 * reviendrait a livrer une application ouverte. En mode strict
 * ({@code app.security.strict=true}, actif avec le profil {@code prod}),
 * l'application refuse donc de demarrer tant qu'un secret de livraison n'a
 * pas ete remplace.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityDefaultsValidator {

    static final String DEFAULT_JWT_SECRET =
            "creditflow-super-secret-key-change-me-in-production-0123456789";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    static final String DEFAULT_DB_PASSWORD = "creditflow";
    static final int MIN_JWT_SECRET_LENGTH = 32;
    static final int MIN_ADMIN_PASSWORD_LENGTH = 8;

    private final AppProperties properties;

    @Value("${spring.datasource.password:}")
    private String databasePassword;

    @PostConstruct
    void validate() {
        List<String> problems = collectProblems();

        if (!properties.getSecurity().isStrict()) {
            if (!problems.isEmpty()) {
                log.warn("=== MODE DEMONSTRATION : secrets par defaut en usage ===");
                problems.forEach(problem -> log.warn("  - {}", problem));
                log.warn("Ne jamais exposer cette configuration a des donnees reelles.");
                log.warn("Pour une vraie boutique : SPRING_PROFILES_ACTIVE=prod + fichier .env dedie.");
            }
            return;
        }

        if (!problems.isEmpty()) {
            String detail = String.join("\n  - ", problems);
            throw new IllegalStateException("""
                    Demarrage refuse : la configuration de production contient encore \
                    des secrets de livraison.
                      - %s

                    Corrigez ces valeurs dans votre fichier .env, puis redemarrez.
                    (Pour une simple demonstration, utilisez SPRING_PROFILES_ACTIVE=demo.)"""
                    .formatted(detail));
        }
        log.info("Controle de securite au demarrage : aucun secret par defaut detecte.");
    }

    private List<String> collectProblems() {
        List<String> problems = new ArrayList<>();
        String jwtSecret = properties.getSecurity().getJwt().getSecret();
        String adminPassword = properties.getAdmin().getPassword();

        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            problems.add("JWT_SECRET utilise encore la valeur de livraison");
        }
        if (jwtSecret == null
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_LENGTH) {
            problems.add("JWT_SECRET doit contenir au moins " + MIN_JWT_SECRET_LENGTH + " caracteres");
        }
        if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            problems.add("ADMIN_PASSWORD utilise encore la valeur de livraison");
        }
        if (adminPassword == null || adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            problems.add("ADMIN_PASSWORD doit contenir au moins "
                    + MIN_ADMIN_PASSWORD_LENGTH + " caracteres");
        }
        if (DEFAULT_DB_PASSWORD.equals(databasePassword)) {
            problems.add("DB_PASSWORD utilise encore la valeur de livraison");
        }
        if (properties.getDemo().isSeed()) {
            problems.add("DEMO_SEED doit valoir false : les donnees de demonstration "
                    + "n'ont pas leur place chez un client");
        }
        return problems;
    }
}

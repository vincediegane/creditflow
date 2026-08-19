package com.creditflow.auth.security;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    private AppProperties properties(String secret) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getJwt().setSecret(secret);
        properties.getSecurity().getJwt().setExpirationMinutes(60);
        properties.getSecurity().getJwt().setIssuer("creditflow");
        return properties;
    }

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(properties("secret-de-test-suffisamment-long-pour-hmac-sha256"));
    }

    @Test
    @DisplayName("emet un jeton exploitable contenant l'utilisateur")
    void generatesReadableToken() {
        String token = jwtService.generateToken("admin", "ADMIN");

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractUsername(token)).contains("admin");
        assertThat(jwtService.expiryOf(token)).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("rejette un jeton falsifie")
    void rejectsTamperedToken() {
        String token = jwtService.generateToken("admin", "ADMIN");
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThat(jwtService.isValid(tampered)).isFalse();
        assertThat(jwtService.extractUsername(tampered)).isEmpty();
    }

    @Test
    @DisplayName("rejette un jeton emis avec une autre cle")
    void rejectsTokenFromAnotherKey() {
        JwtService other = new JwtService(properties("une-autre-cle-secrete-de-test-tres-longue-1234"));
        String foreignToken = other.generateToken("admin", "ADMIN");

        assertThat(jwtService.isValid(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("refuse de demarrer avec un secret trop court")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService(properties("trop-court")))
                .isInstanceOf(IllegalStateException.class);
    }
}

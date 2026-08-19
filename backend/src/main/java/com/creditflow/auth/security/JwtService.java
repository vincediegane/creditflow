package com.creditflow.auth.security;

import com.creditflow.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Emission et validation des jetons JWT.
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;
    private final String issuer;

    public JwtService(AppProperties properties) {
        AppProperties.Jwt jwt = properties.getSecurity().getJwt();
        byte[] secret = jwt.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.security.jwt.secret doit contenir au moins 32 caracteres");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.expirationMinutes = jwt.getExpirationMinutes();
        this.issuer = jwt.getIssuer();
    }

    public String generateToken(String username, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationMinutes * 60);
        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .claims(Map.of("role", role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public LocalDateTime expiryOf(String token) {
        return parse(token)
                .map(Claims::getExpiration)
                .map(date -> LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()))
                .orElse(LocalDateTime.now().plusMinutes(expirationMinutes));
    }

    public Optional<String> extractUsername(String token) {
        return parse(token).map(Claims::getSubject);
    }

    public boolean isValid(String token) {
        return parse(token).isPresent();
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Jeton JWT invalide: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}

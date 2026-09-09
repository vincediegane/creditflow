package com.creditflow.auth.service;

import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.AuthResponse;
import com.creditflow.auth.dto.ChangePasswordRequest;
import com.creditflow.auth.dto.LoginRequest;
import com.creditflow.auth.dto.PlanSummary;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.auth.security.JwtService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.security.TenantContext;
import com.creditflow.config.AppProperties;
import com.creditflow.organization.service.OrganizationPlanResolver;
import com.creditflow.shop.dto.ShopSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CurrentShopContext currentShopContext;
    private final AppProperties properties;
    private final OrganizationPlanResolver organizationPlanResolver;

    public AuthResponse login(LoginRequest request) {
        // Etape 1 : lookup avant authenticate() (et donc avant tout calcul BCrypt), pour pouvoir
        // rejeter immediatement un compte verrouille sans relancer l'authentification (#65).
        // userRepository est un bean distinct d'AuthService -- cet appel ouvre sa propre
        // transaction/session (comportement par defaut de SimpleJpaRepository), meme si login()
        // n'est plus @Transactional. Ne touche que `users` (hors RLS).
        //
        // Compte inexistant : BadCredentialsException (pas ResourceNotFoundException) pour ne
        // jamais reveler cette information -- GlobalExceptionHandler.handleAuthentication mappe
        // toute AuthenticationException en 401 "Identifiants invalides", message strictement
        // identique aux deux autres cas d'echec ci-dessous (#65, critere d'acceptation n3).
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new BadCredentialsException("Identifiants invalides"));

        // Compte verrouille par registerFailedAttempt (#65) : rejet avant authenticate(), donc
        // sans cout BCrypt, avec le meme message que les deux autres cas d'echec.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new LockedException("Identifiants invalides");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            registerFailedAttempt(user);
            throw ex;
        }

        if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        // user.getOrganization() est un proxy lazy : .getId() est lisible sans requete
        // supplementaire (l'id de la FK est deja connu), meme sur une entite detachee.
        TenantContext.set(user.getOrganization().getId());
        try {
            // Etape 2 : currentShopContext est aussi un bean distinct -- nouvel appel a travers
            // le proxy Spring, donc nouvelle transaction/session, cette fois avec le tenant
            // resolu. Recharge l'utilisateur (necessaire : l'instance de l'etape 1 est detachee,
            // sa collection `shops` lazy ne peut pas etre initialisee dans une autre session).
            User reloaded = currentShopContext.reloadWithShopsInitialized(request.username());

            String token = jwtService.generateToken(reloaded.getUsername(), reloaded.getRole().name());
            log.info("Connexion reussie pour {}", reloaded.getUsername());
            PlanSummary plan = new PlanSummary(
                    organizationPlanResolver.multiShopEnabled(reloaded.getOrganization().getId()),
                    properties.getPlan().isWhatsappAuto());

            return new AuthResponse(token, "Bearer", jwtService.expiryOf(token), toResponse(reloaded),
                    currentShopContext.accessibleShops(reloaded), plan);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Incremente le compteur d'echecs consecutifs de {@code user} et pose un verrou temporaire
     * (app.security.login.lockout-minutes) des que le seuil (app.security.login.max-attempts)
     * est atteint. Le compteur est remis a zero au moment ou le verrou est pose (#65) : pas de
     * cumul indefini, et une nouvelle serie complete de tentatives est disponible a l'expiration
     * du verrou.
     */
    private void registerFailedAttempt(User user) {
        AppProperties.Login loginConfig = properties.getSecurity().getLogin();
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= loginConfig.getMaxAttempts()) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(loginConfig.getLockoutMinutes()));
            log.warn("Compte {} verrouille apres {} echecs consecutifs", user.getUsername(), attempts);
        } else {
            user.setFailedLoginAttempts(attempts);
        }
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .map(AuthService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
    }

    /** Boutiques accessibles a l'utilisateur connecte, pour rafraichir le selecteur sans se reconnecter. */
    @Transactional(readOnly = true)
    public List<ShopSummary> accessibleShops() {
        return currentShopContext.accessibleShops();
    }

    /**
     * Change le mot de passe de l'utilisateur connecte et leve l'obligation de
     * changement imposee a la premiere connexion.
     */
    @Transactional
    public UserResponse changePassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessRuleException("Le mot de passe actuel est incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BusinessRuleException("Le nouveau mot de passe doit etre different de l'ancien");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Mot de passe modifie pour {}", user.getUsername());
        return toResponse(user);
    }

    private static UserResponse toResponse(User user) {
        List<ShopSummary> shops = user.getShops().stream()
                .map(s -> new ShopSummary(s.getId(), s.getName()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops, user.getEmail());
    }
}

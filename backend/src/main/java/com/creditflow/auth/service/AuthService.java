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
import com.creditflow.shop.dto.ShopSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        // Etape 1 : userRepository est un bean distinct d'AuthService -- cet appel ouvre sa
        // propre transaction/session (comportement par defaut de SimpleJpaRepository), meme
        // si login() n'est plus @Transactional. Ne touche que `users` (hors RLS).
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

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
                    properties.getPlan().isMultiShop(), properties.getPlan().isWhatsappAuto());

            return new AuthResponse(token, "Bearer", jwtService.expiryOf(token), toResponse(reloaded),
                    currentShopContext.accessibleShops(reloaded), plan);
        } finally {
            TenantContext.clear();
        }
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
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops);
    }
}

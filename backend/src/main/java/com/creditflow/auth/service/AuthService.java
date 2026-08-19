package com.creditflow.auth.service;

import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.AuthResponse;
import com.creditflow.auth.dto.ChangePasswordRequest;
import com.creditflow.auth.dto.LoginRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.auth.security.JwtService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        log.info("Connexion reussie pour {}", user.getUsername());

        return new AuthResponse(token, "Bearer", jwtService.expiryOf(token), toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .map(AuthService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
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
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled());
    }
}

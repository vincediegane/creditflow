package com.creditflow.auth.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.ChangePasswordRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.auth.security.JwtService;
import com.creditflow.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        authService = new AuthService(authenticationManager, userRepository, jwtService,
                passwordEncoder);

        user = User.builder()
                .id(1L)
                .username("admin")
                .password(passwordEncoder.encode("MotDePasseInitial1"))
                .fullName("Proprietaire")
                .role(Role.ADMIN)
                .enabled(true)
                .mustChangePassword(true)
                .build();

        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("le changement de mot de passe leve l'obligation imposee a la premiere connexion")
    void changingPasswordClearsTheObligation() {
        UserResponse response = authService.changePassword("admin",
                new ChangePasswordRequest("MotDePasseInitial1", "NouveauMotDePasse2026"));

        assertThat(response.mustChangePassword()).isFalse();
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getPasswordChangedAt()).isNotNull();
        assertThat(passwordEncoder.matches("NouveauMotDePasse2026", user.getPassword())).isTrue();
    }

    @Test
    @DisplayName("refuse un mot de passe actuel incorrect")
    void rejectsWrongCurrentPassword() {
        assertThatThrownBy(() -> authService.changePassword("admin",
                new ChangePasswordRequest("mauvais", "NouveauMotDePasse2026")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("actuel est incorrect");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuse de reutiliser le mot de passe actuel")
    void rejectsSamePassword() {
        assertThatThrownBy(() -> authService.changePassword("admin",
                new ChangePasswordRequest("MotDePasseInitial1", "MotDePasseInitial1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("le profil courant expose l'obligation de changement")
    void currentUserExposesTheObligation() {
        assertThat(authService.currentUser("admin").mustChangePassword()).isTrue();
    }
}

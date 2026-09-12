package com.creditflow.auth.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.AuthResponse;
import com.creditflow.auth.dto.ChangePasswordRequest;
import com.creditflow.auth.dto.LoginRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.auth.security.JwtService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.service.OrganizationPlanResolver;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopSummary;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    @Mock
    private CurrentShopContext currentShopContext;

    @Mock
    private AppProperties properties;

    @Mock
    private OrganizationPlanResolver organizationPlanResolver;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        when(properties.getPlan()).thenReturn(new AppProperties.Plan());
        authService = new AuthService(authenticationManager, userRepository, jwtService,
                passwordEncoder, currentShopContext, properties, organizationPlanResolver);

        user = User.builder()
                .id(1L)
                .username("admin")
                .password(passwordEncoder.encode("MotDePasseInitial1"))
                .fullName("Proprietaire")
                .role(Role.ADMIN)
                .organization(Organization.builder().id(1L).name("Organisation principale").build())
                .enabled(true)
                .mustChangePassword(true)
                .build();

        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(currentShopContext.reloadWithShopsInitialized("admin")).thenReturn(user);
        when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    @Test
    @DisplayName("la connexion inclut les boutiques accessibles resolues apres authentification")
    void loginIncludesAccessibleShops() {
        List<ShopSummary> shops = List.of(new ShopSummary(1L, "Boutique principale"));
        when(currentShopContext.accessibleShops(user)).thenReturn(shops);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        AuthResponse response = authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(response.accessibleShops()).isEqualTo(shops);
    }

    @Test
    @DisplayName("la connexion expose la formule (plan) de cette instance")
    void loginIncludesPlan() {
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        AuthResponse response = authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(response.plan()).isNotNull();
        assertThat(response.plan().multiShop()).isTrue();
        assertThat(response.plan().whatsappAuto()).isTrue();
    }

    @Test
    @DisplayName("la connexion reflete la formule multiShop specifique a l'organisation, pas le fallback d'instance")
    void loginReflectsOrganizationOverrideOfMultiShop() {
        when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(false);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        AuthResponse response = authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(properties.getPlan().isMultiShop()).isTrue();
        assertThat(response.plan().multiShop()).isFalse();
    }

    @Test
    @DisplayName("la connexion resout les boutiques sans dependre du SecurityContext (encore anonyme a ce stade)")
    void loginResolvesAccessibleShopsWhileStillAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        ShopRepository shopRepository = mock(ShopRepository.class);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(user.getOrganization().getId()))
                .thenReturn(List.of(Shop.builder().id(1L).name("Boutique principale").active(true).build()));
        AuthService service = new AuthService(authenticationManager, userRepository, jwtService, passwordEncoder,
                new CurrentShopContext(userRepository, shopRepository), properties, organizationPlanResolver);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        AuthResponse response = service.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(response.accessibleShops()).containsExactly(new ShopSummary(1L, "Boutique principale"));
    }

    @Test
    @DisplayName("verrouille le compte apres le nombre maximal d'echecs consecutifs")
    void locksAccountAfterMaxConsecutiveFailures() {
        when(properties.getSecurity()).thenReturn(new AppProperties.Security());
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Identifiants invalides"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "mauvais")))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("rejette immediatement une connexion sur un compte verrouille, sans reappeler authenticate()")
    void rejectsLoginImmediatelyWhenAccountIsLocked() {
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "MotDePasseInitial1")))
                .isInstanceOf(LockedException.class);

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("remet a zero le compteur d'echecs apres une connexion reussie")
    void resetsFailedAttemptsOnSuccessfulLogin() {
        user.setFailedLoginAttempts(2);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("autorise a nouveau une tentative une fois le verrou expire")
    void unlocksAutomaticallyAfterLockoutExpires() {
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        verify(authenticationManager).authenticate(any());
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("rejette un identifiant inexistant sans reveler l'absence du compte")
    void throwsBadCredentialsForUnknownUsername() {
        when(userRepository.findByUsernameIgnoreCase("inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("inconnu", "peu importe")))
                .isInstanceOf(BadCredentialsException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);

        verify(authenticationManager, never()).authenticate(any());
    }
}

package com.creditflow.auth.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.UserRequest;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ShopRepository shopRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserService userService;

    private UserRequest request() {
        return new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L));
    }

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, shopRepository, passwordEncoder);
        Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        when(shopRepository.findById(1L)).thenReturn(Optional.of(shop));
    }

    @Test
    @DisplayName("cree un vendeur avec changement de mot de passe force")
    void createsSellerWithMustChangePasswordForced() {
        when(userRepository.existsByUsernameIgnoreCase("fatou.diop")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User saved = i.getArgument(0);
            saved.setId(4L);
            return saved;
        });

        var response = userService.create(request());

        assertThat(response.role()).isEqualTo("SELLER");
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(response.enabled()).isTrue();

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isNotEqualTo("TempPass2026!");
        assertThat(passwordEncoder.matches("TempPass2026!", captor.getValue().getPassword())).isTrue();
    }

    @Test
    @DisplayName("refuse un identifiant deja utilise")
    void rejectsDuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("fatou.diop")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deja utilise");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuse de desactiver son propre compte")
    void rejectsSelfDisable() {
        User admin = User.builder().id(1L).username("admin").role(Role.ADMIN).enabled(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.setEnabled(1L, false, "admin"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("propre compte");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("desactive le compte d'un autre utilisateur")
    void disablesAnotherUsersAccount() {
        User seller = User.builder().id(2L).username("fatou.diop").role(Role.SELLER).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        var response = userService.setEnabled(2L, false, "admin");

        assertThat(response.enabled()).isFalse();
        verify(userRepository).save(seller);
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("signale un utilisateur introuvable")
    void failsWhenTargetMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setEnabled(99L, false, "admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("refuse un vendeur sans boutique assignee")
    void rejectsSellerWithoutShop() {
        UserRequest request = new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("boutique");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("met a jour les boutiques assignees a un compte")
    void updatesShops() {
        User seller = User.builder().id(2L).username("fatou.diop").role(Role.SELLER).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        var response = userService.updateShops(2L, List.of(1L), "admin");

        assertThat(response.shops()).hasSize(1);
        assertThat(seller.getShops()).hasSize(1);
    }
}

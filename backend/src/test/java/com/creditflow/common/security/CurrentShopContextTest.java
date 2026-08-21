package com.creditflow.common.security;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrentShopContextTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ShopRepository shopRepository;

    @InjectMocks
    private CurrentShopContext currentShopContext;

    private final Shop shop1 = Shop.builder().id(1L).name("Boutique 1").active(true).build();
    private final Shop shop2 = Shop.builder().id(2L).name("Boutique 2").active(true).build();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(null);
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    @Test
    @DisplayName("un SELLER avec une boutique n'accede qu'a celle-ci")
    void accessibleShopIdsForSellerWithOneShop() {
        User seller = User.builder().username("vendeur").role(Role.SELLER)
                .shops(new HashSet<>(Set.of(shop1))).build();
        when(userRepository.findByUsernameIgnoreCase("vendeur")).thenReturn(Optional.of(seller));
        authenticateAs("vendeur");

        assertThat(currentShopContext.accessibleShopIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("un ADMIN sans assignation accede a toutes les boutiques actives (super-admin)")
    void accessibleShopIdsForAdminWithoutAssignment() {
        User admin = User.builder().username("admin").role(Role.ADMIN).shops(new HashSet<>()).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        when(shopRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(shop1, shop2));
        authenticateAs("admin");

        assertThat(currentShopContext.accessibleShopIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("un ADMIN avec assignation explicite n'accede qu'a ses boutiques (admin de boutique)")
    void accessibleShopIdsForAdminWithExplicitAssignment() {
        User admin = User.builder().username("admin-boutique").role(Role.ADMIN)
                .shops(new HashSet<>(Set.of(shop1))).build();
        when(userRepository.findByUsernameIgnoreCase("admin-boutique")).thenReturn(Optional.of(admin));
        authenticateAs("admin-boutique");

        assertThat(currentShopContext.accessibleShopIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("shopIdForCreation retourne l'unique boutique accessible pour un utilisateur mono-boutique")
    void shopIdForCreationReturnsSingleAccessibleShop() {
        User seller = User.builder().username("vendeur").role(Role.SELLER)
                .shops(new HashSet<>(Set.of(shop1))).build();
        when(userRepository.findByUsernameIgnoreCase("vendeur")).thenReturn(Optional.of(seller));
        authenticateAs("vendeur");

        assertThat(currentShopContext.shopIdForCreation()).isEqualTo(1L);
    }

    @Test
    @DisplayName("shopIdForCreation leve une exception pour un utilisateur multi-boutiques sans en-tete")
    void shopIdForCreationFailsForMultiShopWithoutHeader() {
        User admin = User.builder().username("admin").role(Role.ADMIN)
                .shops(new HashSet<>(Set.of(shop1, shop2))).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        authenticateAs("admin");
        RequestContextHolder.setRequestAttributes(null);

        assertThatThrownBy(() -> currentShopContext.shopIdForCreation())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("X-Shop-Id");
    }

    @Test
    @DisplayName("resolveReadFilter retourne la vue consolidee en l'absence d'en-tete pour un multi-boutiques")
    void resolveReadFilterReturnsConsolidatedViewWithoutHeader() {
        User admin = User.builder().username("admin").role(Role.ADMIN)
                .shops(new HashSet<>(Set.of(shop1, shop2))).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        authenticateAs("admin");

        assertThat(currentShopContext.resolveReadFilter()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("resolveReadFilter restreint a la boutique demandee via l'en-tete X-Shop-Id")
    void resolveReadFilterHonorsShopHeader() {
        User admin = User.builder().username("admin").role(Role.ADMIN)
                .shops(new HashSet<>(Set.of(shop1, shop2))).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        authenticateAs("admin");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentShopContext.SHOP_HEADER, "2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(currentShopContext.resolveReadFilter()).containsExactly(2L);
    }

    @Test
    @DisplayName("accessibleShops(user) resout les boutiques sans SecurityContext")
    void accessibleShopsForExplicitUserWithoutSecurityContext() {
        SecurityContextHolder.clearContext();
        User seller = User.builder().username("vendeur").role(Role.SELLER)
                .shops(new HashSet<>(Set.of(shop1))).build();

        assertThat(currentShopContext.accessibleShops(seller))
                .containsExactly(new com.creditflow.shop.dto.ShopSummary(1L, "Boutique 1"));
        assertThat(currentShopContext.accessibleShopIds(seller)).containsExactly(1L);
    }

    @Test
    @DisplayName("assertAccessible leve ResourceNotFoundException pour une boutique hors perimetre")
    void assertAccessibleFailsForOutOfScopeShop() {
        User seller = User.builder().username("vendeur").role(Role.SELLER)
                .shops(new HashSet<>(Set.of(shop1))).build();
        when(userRepository.findByUsernameIgnoreCase("vendeur")).thenReturn(Optional.of(seller));
        authenticateAs("vendeur");

        assertThatThrownBy(() -> currentShopContext.assertAccessible(2L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

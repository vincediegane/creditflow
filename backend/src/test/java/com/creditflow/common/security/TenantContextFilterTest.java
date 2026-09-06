package com.creditflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantContextFilterTest {

    @Mock
    private CurrentShopContext currentShopContext;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter(currentShopContext);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("positionne TenantContext avec l'organisation courante pendant l'execution de la chaine pour un utilisateur authentifie")
    void setsTenantContextForAuthenticatedUser() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("vendeur", null, List.of()));
        when(currentShopContext.currentOrganizationId()).thenReturn(10L);
        Long[] organizationIdDuringChain = new Long[1];
        doAnswer(invocation -> {
            organizationIdDuringChain[0] = TenantContext.get();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(organizationIdDuringChain[0]).isEqualTo(10L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("laisse TenantContext vide pour un utilisateur anonyme")
    void leavesTenantContextEmptyForAnonymousUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        Long[] organizationIdDuringChain = new Long[] {-1L};
        doAnswer(invocation -> {
            organizationIdDuringChain[0] = TenantContext.get();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(organizationIdDuringChain[0]).isNull();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("nettoie TenantContext meme si la chaine de filtres leve une exception")
    void clearsTenantContextWhenFilterChainThrows() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("vendeur", null, List.of()));
        when(currentShopContext.currentOrganizationId()).thenReturn(10L);
        doThrow(new RuntimeException("boom")).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(TenantContext.get()).isNull();
    }
}

package com.creditflow.payment.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTest {

    private MockedStatic<SecurityContextHolder> securityContextHolder;

    @AfterEach
    void tearDown() {
        if (securityContextHolder != null) {
            securityContextHolder.close();
        }
    }

    @Test
    @DisplayName("renseigne createdBy avec l'utilisateur authentifie")
    void setsCreatedByWhenAuthenticated() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("vendeur1", null, List.of());
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(authentication);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);

        Payment payment = new Payment();
        invokeOnCreate(payment);

        assertThat(payment.getCreatedBy()).isEqualTo("vendeur1");
    }

    @Test
    @DisplayName("ne leve pas d'exception et laisse createdBy a null sans authentification")
    void setsNullWhenUnauthenticated() throws Exception {
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(null);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);

        Payment payment = new Payment();
        invokeOnCreate(payment);

        assertThat(payment.getCreatedBy()).isNull();
    }

    private void invokeOnCreate(Payment payment) throws Exception {
        Method method = Payment.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(payment);
    }
}

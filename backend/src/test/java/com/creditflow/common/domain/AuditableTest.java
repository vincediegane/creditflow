package com.creditflow.common.domain;

import com.creditflow.customer.domain.Customer;
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

class AuditableTest {

    private MockedStatic<SecurityContextHolder> securityContextHolder;

    @AfterEach
    void tearDown() {
        if (securityContextHolder != null) {
            securityContextHolder.close();
        }
    }

    @Test
    @DisplayName("renseigne createdBy/updatedBy avec l'utilisateur authentifie")
    void setsAuditorWhenAuthenticated() throws Exception {
        mockAuthenticatedUser("vendeur1");
        Customer customer = Customer.builder().firstName("Amadou").lastName("Diallo").build();

        invoke(customer, "onCreate");

        assertThat(customer.getCreatedBy()).isEqualTo("vendeur1");
        assertThat(customer.getUpdatedBy()).isEqualTo("vendeur1");

        mockAuthenticatedUser("admin1");
        invoke(customer, "onUpdate");

        assertThat(customer.getUpdatedBy()).isEqualTo("admin1");
    }

    @Test
    @DisplayName("ne leve pas d'exception et laisse createdBy/updatedBy a null sans authentification")
    void setsNullWhenUnauthenticated() throws Exception {
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(null);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);

        Customer customer = Customer.builder().firstName("Amadou").lastName("Diallo").build();

        invoke(customer, "onCreate");

        assertThat(customer.getCreatedBy()).isNull();
        assertThat(customer.getUpdatedBy()).isNull();
    }

    private void mockAuthenticatedUser(String username) {
        if (securityContextHolder != null) {
            securityContextHolder.close();
        }
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, List.of());
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(authentication);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);
    }

    private void invoke(Object target, String methodName) throws Exception {
        Method method = Auditable.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }
}

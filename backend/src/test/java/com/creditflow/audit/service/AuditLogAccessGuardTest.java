package com.creditflow.audit.service;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.service.CreditSaleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogAccessGuardTest {

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @Mock
    private CreditSaleService creditSaleService;

    @InjectMocks
    private AuditLogAccessGuard auditLogAccessGuard;

    @Test
    @DisplayName("delegue la garde de boutique au module proprietaire de l'entite")
    void delegatesToOwningModule() {
        auditLogAccessGuard.assertReadable("CUSTOMER", 1L);
        auditLogAccessGuard.assertReadable("PRODUCT", 2L);
        auditLogAccessGuard.assertReadable("CREDIT_SALE", 3L);

        verify(customerService).getEntity(1L);
        verify(productService).getEntity(2L);
        verify(creditSaleService).getEntity(3L);
    }

    @Test
    @DisplayName("propage le refus quand l'entite appartient a une autre boutique")
    void propagatesRefusalForAnotherShop() {
        when(customerService.getEntity(9L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

        assertThatThrownBy(() -> auditLogAccessGuard.assertReadable("CUSTOMER", 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("refuse un type d'entite global ou inconnu")
    void rejectsGlobalOrUnknownEntityType() {
        assertThatThrownBy(() -> auditLogAccessGuard.assertReadable("PENALTY_SETTINGS", 1L))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> auditLogAccessGuard.assertReadable("USER", 1L))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(customerService, productService, creditSaleService);
    }
}

package com.creditflow.audit.web;

import com.creditflow.audit.service.AuditLogAccessGuard;
import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.service.CreditSaleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
@Import(AuditLogAccessGuard.class)
class AuditLogControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private CustomerService customerService;

    @MockBean
    private ProductService productService;

    @MockBean
    private CreditSaleService creditSaleService;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanListAuditLog() throws Exception {
        when(auditLogService.list("CREDIT_SALE", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/audit-log").param("entityType", "CREDIT_SALE").param("entityId", "1"))
                .andExpect(status().isOk());

        verify(creditSaleService).getEntity(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListAuditLog() throws Exception {
        when(auditLogService.list("CUSTOMER", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/audit-log").param("entityType", "CUSTOMER").param("entityId", "1"))
                .andExpect(status().isOk());

        verify(customerService).getEntity(1L);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("refuse le journal d'un client d'une autre boutique")
    void rejectsAuditLogOfCustomerFromAnotherShop() throws Exception {
        when(customerService.getEntity(2L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

        mockMvc.perform(get("/api/audit-log").param("entityType", "CUSTOMER").param("entityId", "2"))
                .andExpect(status().isNotFound());

        verify(auditLogService, never()).list(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("refuse le journal d'un produit d'une autre boutique")
    void rejectsAuditLogOfProductFromAnotherShop() throws Exception {
        when(productService.getEntity(7L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

        mockMvc.perform(get("/api/audit-log").param("entityType", "PRODUCT").param("entityId", "7"))
                .andExpect(status().isNotFound());

        verify(auditLogService, never()).list(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("refuse un type d'entite non rattachable a une boutique")
    void rejectsEntityTypeWithoutShop() throws Exception {
        mockMvc.perform(get("/api/audit-log").param("entityType", "PENALTY_SETTINGS").param("entityId", "1"))
                .andExpect(status().isNotFound());

        verify(auditLogService, never()).list(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void missingParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/audit-log").param("entityType", "CUSTOMER"))
                .andExpect(status().isBadRequest());
    }
}

package com.creditflow.audit.web;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.config.AbstractWebMvcSecurityTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
class AuditLogControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanListAuditLog() throws Exception {
        when(auditLogService.list("CREDIT_SALE", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/audit-log").param("entityType", "CREDIT_SALE").param("entityId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListAuditLog() throws Exception {
        when(auditLogService.list("CUSTOMER", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/audit-log").param("entityType", "CUSTOMER").param("entityId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void missingParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/audit-log").param("entityType", "CUSTOMER"))
                .andExpect(status().isBadRequest());
    }
}

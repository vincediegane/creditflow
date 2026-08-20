package com.creditflow.supplier.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.supplier.dto.SupplierRequest;
import com.creditflow.supplier.dto.SupplierResponse;
import com.creditflow.supplier.service.SupplierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupplierController.class)
class SupplierControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupplierService supplierService;

    private SupplierRequest request() {
        return new SupplierRequest("Grossiste Sahel", "Moussa Ba", "770000002",
                "contact@sahel.sn", "Dakar", null, true);
    }

    private SupplierResponse response() {
        return new SupplierResponse(1L, "Grossiste Sahel", "Moussa Ba", "770000002",
                "contact@sahel.sn", "Dakar", null, true, null, null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCreateSupplier() throws Exception {
        mockMvc.perform(post("/api/suppliers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateSupplier() throws Exception {
        when(supplierService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/suppliers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteSupplier() throws Exception {
        mockMvc.perform(delete("/api/suppliers/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteSupplier() throws Exception {
        mockMvc.perform(delete("/api/suppliers/1")).andExpect(status().isNoContent());
    }
}

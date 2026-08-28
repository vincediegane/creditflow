package com.creditflow.customer.web;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.storage.DocumentAccess;
import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.customer.service.CustomerProfileService;
import com.creditflow.customer.service.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
class CustomerControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerService customerService;

    @MockBean
    private CustomerProfileService customerProfileService;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteCustomer() throws Exception {
        mockMvc.perform(delete("/api/customers/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteCustomer() throws Exception {
        mockMvc.perform(delete("/api/customers/1")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("refuse la photo d'un client sans authentification")
    void unauthenticatedCannotGetPhoto() throws Exception {
        mockMvc.perform(get("/api/customers/1/photo")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("sert la photo en octets quand la resolution est locale")
    void sellerGetsInlinePhoto() throws Exception {
        when(customerService.resolvePhoto(1L)).thenReturn(new DocumentAccess.Inline(new byte[]{1, 2, 3}, "image/png"));

        mockMvc.perform(get("/api/customers/1/photo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("redirige vers l'URL signee quand la resolution est distante")
    void sellerGetsRedirectPhoto() throws Exception {
        when(customerService.resolvePhoto(1L))
                .thenReturn(new DocumentAccess.Redirect("https://s3.example.com/bucket/customers/1.png?sig=xyz"));

        mockMvc.perform(get("/api/customers/1/photo"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://s3.example.com/bucket/customers/1.png?sig=xyz"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("refuse la photo d'un client inaccessible")
    void sellerCannotGetPhotoOfInaccessibleCustomer() throws Exception {
        when(customerService.resolvePhoto(2L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

        mockMvc.perform(get("/api/customers/2/photo")).andExpect(status().isNotFound());
    }
}

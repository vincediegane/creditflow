package com.creditflow.customer.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.customer.service.CustomerProfileService;
import com.creditflow.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
}

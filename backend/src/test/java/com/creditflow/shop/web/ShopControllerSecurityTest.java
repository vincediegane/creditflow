package com.creditflow.shop.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.shop.dto.ShopRequest;
import com.creditflow.shop.dto.ShopResponse;
import com.creditflow.shop.service.ShopService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShopController.class)
class ShopControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ShopService shopService;

    private ShopRequest request() {
        return new ShopRequest("Boutique Centre-ville", "Dakar", "770000002", true);
    }

    private ShopResponse response() {
        return new ShopResponse(1L, "Boutique Centre-ville", "Dakar", "770000002", true,
                LocalDateTime.now(), null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotListShops() throws Exception {
        mockMvc.perform(get("/api/shops")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCreateShop() throws Exception {
        mockMvc.perform(post("/api/shops")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotUpdateShop() throws Exception {
        mockMvc.perform(put("/api/shops/1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteShop() throws Exception {
        mockMvc.perform(delete("/api/shops/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListShops() throws Exception {
        when(shopService.list()).thenReturn(java.util.List.of(response()));

        mockMvc.perform(get("/api/shops")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateShop() throws Exception {
        when(shopService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/shops")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanUpdateShop() throws Exception {
        when(shopService.update(anyLong(), any())).thenReturn(response());

        mockMvc.perform(put("/api/shops/1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteShop() throws Exception {
        mockMvc.perform(delete("/api/shops/1")).andExpect(status().isNoContent());
    }
}

package com.creditflow.product.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private ProductRequest request() {
        return new ProductRequest("Telephone", "Electronique", BigDecimal.valueOf(100000),
                BigDecimal.valueOf(120000), 10, null, ProductStatus.ACTIVE);
    }

    private ProductResponse response() {
        return new ProductResponse(1L, "Telephone", "Electronique", BigDecimal.valueOf(100000),
                BigDecimal.valueOf(120000), 10, null, ProductStatus.ACTIVE, true, LocalDateTime.now(), null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCreateProduct() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotUpdateProduct() throws Exception {
        mockMvc.perform(put("/api/products/1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteProduct() throws Exception {
        mockMvc.perform(delete("/api/products/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateProduct() throws Exception {
        when(productService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanUpdateProduct() throws Exception {
        when(productService.update(anyLong(), any())).thenReturn(response());

        mockMvc.perform(put("/api/products/1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteProduct() throws Exception {
        mockMvc.perform(delete("/api/products/1")).andExpect(status().isNoContent());
    }
}

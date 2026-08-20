package com.creditflow.supplier.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.supplier.dto.StockReceptionRequest;
import com.creditflow.supplier.dto.StockReceptionRequest.StockReceptionLineRequest;
import com.creditflow.supplier.dto.StockReceptionResponse;
import com.creditflow.supplier.service.StockReceptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockReceptionController.class)
class StockReceptionControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StockReceptionService stockReceptionService;

    private StockReceptionRequest request() {
        return new StockReceptionRequest(1L, LocalDate.now(), null, List.of(new StockReceptionLineRequest(1L, 3)));
    }

    private StockReceptionResponse response() {
        return new StockReceptionResponse(1L, 1L, "Grossiste Sahel", LocalDate.now(), null, List.of(), null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCreateStockReception() throws Exception {
        mockMvc.perform(post("/api/stock-receptions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateStockReception() throws Exception {
        when(stockReceptionService.receive(any())).thenReturn(response());

        mockMvc.perform(post("/api/stock-receptions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }
}

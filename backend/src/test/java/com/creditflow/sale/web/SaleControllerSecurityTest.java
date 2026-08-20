package com.creditflow.sale.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.sale.service.InstallmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SaleController.class)
class SaleControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CreditSaleService creditSaleService;

    @MockBean
    private InstallmentService installmentService;

    @MockBean
    private PaymentService paymentService;

    private CreateSaleRequest createRequest() {
        return new CreateSaleRequest(1L, 1L, BigDecimal.valueOf(100000), BigDecimal.valueOf(20000),
                null, null, 6, LocalDate.now(), null, null, null, null, null);
    }

    private SaleResponse response() {
        return new SaleResponse(1L, "V-0001", 1L, "Amadou Diallo", "770000001", 1L, "Telephone",
                BigDecimal.valueOf(100000), null, BigDecimal.ZERO, BigDecimal.valueOf(20000), BigDecimal.valueOf(80000), 6,
                BigDecimal.valueOf(13334), BigDecimal.ZERO, BigDecimal.valueOf(80000), LocalDate.now(),
                LocalDate.now().plusMonths(6), SaleStatus.ACTIVE, false, 0, 0, null, null, 0, null, null,
                null, null, BigDecimal.ZERO, null, null, null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCancelSale() throws Exception {
        mockMvc.perform(post("/api/sales/1/cancel")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteSale() throws Exception {
        mockMvc.perform(delete("/api/sales/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanCreateSale() throws Exception {
        when(creditSaleService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/sales")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCancelSale() throws Exception {
        when(creditSaleService.cancel(anyLong())).thenReturn(response());

        mockMvc.perform(post("/api/sales/1/cancel")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteSale() throws Exception {
        mockMvc.perform(delete("/api/sales/1")).andExpect(status().isNoContent());
    }
}

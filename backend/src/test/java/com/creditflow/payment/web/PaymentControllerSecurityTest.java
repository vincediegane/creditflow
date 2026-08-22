package com.creditflow.payment.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    private PaymentRequest request() {
        return new PaymentRequest(1L, BigDecimal.valueOf(15000), LocalDate.now(), PaymentMethod.CASH, null, null,
                "3f1c9a0e-0000-4000-8000-000000000001");
    }

    private PaymentResponse response() {
        return new PaymentResponse(1L, 1L, "V-0001", 1L, "Amadou Diallo", "770000001", "Telephone",
                BigDecimal.valueOf(15000), LocalDate.now(), PaymentMethod.CASH, null, null,
                BigDecimal.valueOf(65000), LocalDateTime.now(), null,
                "3f1c9a0e-0000-4000-8000-000000000001");
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeletePayment() throws Exception {
        mockMvc.perform(delete("/api/payments/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanRegisterPayment() throws Exception {
        when(paymentService.register(any()))
                .thenReturn(new PaymentService.RegistrationResult(response(), false));

        mockMvc.perform(post("/api/payments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void newPaymentReturns201WithLocation() throws Exception {
        when(paymentService.register(any()))
                .thenReturn(new PaymentService.RegistrationResult(response(), false));

        mockMvc.perform(post("/api/payments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/payments/1"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void replayedPaymentReturns200WithoutLocation() throws Exception {
        when(paymentService.register(any()))
                .thenReturn(new PaymentService.RegistrationResult(response(), true));

        mockMvc.perform(post("/api/payments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeletePayment() throws Exception {
        mockMvc.perform(delete("/api/payments/1")).andExpect(status().isNoContent());
    }
}

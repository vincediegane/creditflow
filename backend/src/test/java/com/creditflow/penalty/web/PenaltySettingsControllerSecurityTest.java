package com.creditflow.penalty.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.dto.PenaltySettingsResponse;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PenaltySettingsController.class)
class PenaltySettingsControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PenaltySettingsService penaltySettingsService;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotReadSettings() throws Exception {
        mockMvc.perform(get("/api/penalty-settings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotUpdateSettings() throws Exception {
        mockMvc.perform(put("/api/penalty-settings")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadSettings() throws Exception {
        when(penaltySettingsService.get()).thenReturn(new PenaltySettingsResponse(
                false, PenaltyRateType.FIXED, BigDecimal.ZERO, PenaltyPeriod.DAY, null, null, null));

        mockMvc.perform(get("/api/penalty-settings"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanUpdateSettings() throws Exception {
        when(penaltySettingsService.update(any())).thenReturn(new PenaltySettingsResponse(
                true, PenaltyRateType.FIXED, new BigDecimal("500"), PenaltyPeriod.DAY, null, null, null));

        mockMvc.perform(put("/api/penalty-settings")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isOk());
    }

    private String requestJson() throws Exception {
        return objectMapper.writeValueAsString(new com.creditflow.penalty.dto.PenaltySettingsRequest(
                true, PenaltyRateType.FIXED, new BigDecimal("500"), PenaltyPeriod.DAY, null));
    }
}

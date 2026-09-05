package com.creditflow.notification.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.notification.dto.BulkReminderResponse;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.notification.service.NotificationChannel;
import com.creditflow.notification.service.ReminderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReminderController.class)
class ReminderControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReminderService reminderService;

    @MockBean
    private LateCustomerService lateCustomerService;

    @MockBean
    private NotificationChannel notificationChannel;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotSend() throws Exception {
        mockMvc.perform(post("/api/reminders/send")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotSendAll() throws Exception {
        mockMvc.perform(post("/api/reminders/send-all")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanSend() throws Exception {
        when(reminderService.send(any())).thenReturn(reminderResponse());

        mockMvc.perform(post("/api/reminders/send")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanSendAll() throws Exception {
        when(reminderService.sendAll(any())).thenReturn(
                new BulkReminderResponse(1, 1, 0, List.of(reminderResponse())));

        mockMvc.perform(post("/api/reminders/send-all")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanGenerate() throws Exception {
        when(reminderService.generate(any())).thenReturn(reminderResponse());

        mockMvc.perform(post("/api/reminders/generate")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanGenerate() throws Exception {
        when(reminderService.generate(any())).thenReturn(reminderResponse());

        mockMvc.perform(post("/api/reminders/generate")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedCannotSend() throws Exception {
        mockMvc.perform(post("/api/reminders/send")
                        .contentType("application/json")
                        .content(requestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedCannotSendAll() throws Exception {
        mockMvc.perform(post("/api/reminders/send-all")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private String requestJson() throws Exception {
        return objectMapper.writeValueAsString(new com.creditflow.notification.dto.ReminderRequest(null, 1L, null));
    }

    private ReminderResponse reminderResponse() {
        return new ReminderResponse(1L, "Amadou Diallo", "770000001", new BigDecimal("15000"),
                "Bonjour", "WHATSAPP_CLOUD_API", true);
    }
}

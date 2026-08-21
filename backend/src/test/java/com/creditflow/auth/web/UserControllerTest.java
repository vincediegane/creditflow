package com.creditflow.auth.web;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.dto.UserRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.service.UserService;
import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    private UserResponse response() {
        return new UserResponse(4L, "fatou.diop", "Fatou Diop", "SELLER", true, true, List.of());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCreateUser() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L)))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotSetStatus() throws Exception {
        mockMvc.perform(patch("/api/users/1/status")
                        .contentType("application/json")
                        .content("{\"enabled\": false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListUsers() throws Exception {
        when(userService.list()).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/users")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateUser() throws Exception {
        when(userService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L)))))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanSetStatus() throws Exception {
        when(userService.setEnabled(anyLong(), anyBoolean(), anyString())).thenReturn(response());

        mockMvc.perform(patch("/api/users/1/status")
                        .contentType("application/json")
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotUpdateShops() throws Exception {
        mockMvc.perform(patch("/api/users/1/shops")
                        .contentType("application/json")
                        .content("{\"shopIds\": [1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanUpdateShops() throws Exception {
        when(userService.updateShops(anyLong(), any(), anyString())).thenReturn(response());

        mockMvc.perform(patch("/api/users/1/shops")
                        .contentType("application/json")
                        .content("{\"shopIds\": [1]}"))
                .andExpect(status().isOk());
    }
}

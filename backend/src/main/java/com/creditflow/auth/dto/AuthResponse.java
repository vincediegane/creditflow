package com.creditflow.auth.dto;

import java.time.LocalDateTime;

public record AuthResponse(
        String token,
        String tokenType,
        LocalDateTime expiresAt,
        UserResponse user
) {
}

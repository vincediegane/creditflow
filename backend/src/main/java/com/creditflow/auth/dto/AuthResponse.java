package com.creditflow.auth.dto;

import com.creditflow.shop.dto.ShopSummary;

import java.time.LocalDateTime;
import java.util.List;

public record AuthResponse(
        String token,
        String tokenType,
        LocalDateTime expiresAt,
        UserResponse user,
        /** Boutiques accessibles resolues par CurrentShopContext, pour peupler le selecteur frontend. */
        List<ShopSummary> accessibleShops
) {
}

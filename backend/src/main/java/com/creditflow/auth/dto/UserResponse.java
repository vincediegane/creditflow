package com.creditflow.auth.dto;

import com.creditflow.shop.dto.ShopSummary;

import java.util.List;

public record UserResponse(
        Long id,
        String username,
        String fullName,
        String role,
        /** Le client doit imposer le changement de mot de passe avant toute autre action. */
        boolean mustChangePassword,
        boolean enabled,
        List<ShopSummary> shops
) {
}

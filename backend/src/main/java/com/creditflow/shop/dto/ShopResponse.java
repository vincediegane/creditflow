package com.creditflow.shop.dto;

import java.time.LocalDateTime;

public record ShopResponse(
        Long id,
        String name,
        String address,
        String phone,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        String updatedBy
) {
}

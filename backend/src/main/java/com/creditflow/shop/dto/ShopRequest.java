package com.creditflow.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShopRequest(
        @NotBlank(message = "Le nom de la boutique est obligatoire")
        @Size(max = 120)
        String name,

        @Size(max = 255)
        String address,

        @Size(max = 30)
        String phone,

        Boolean active
) {
}

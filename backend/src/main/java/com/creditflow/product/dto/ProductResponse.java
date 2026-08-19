package com.creditflow.product.dto;

import com.creditflow.product.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResponse(
        Long id,
        String name,
        String category,
        BigDecimal cashPrice,
        BigDecimal creditPrice,
        Integer stock,
        String description,
        ProductStatus status,
        boolean sellable,
        LocalDateTime createdAt
) {
}

package com.creditflow.product.dto;

import com.creditflow.product.domain.StockMovementType;
import com.creditflow.product.domain.StockSourceType;

import java.time.LocalDateTime;

public record StockMovementResponse(
        Long id,
        Long productId,
        StockMovementType type,
        Integer quantity,
        StockSourceType sourceType,
        Long sourceId,
        LocalDateTime occurredAt,
        String createdBy
) {
}

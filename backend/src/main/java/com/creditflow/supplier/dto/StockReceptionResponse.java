package com.creditflow.supplier.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record StockReceptionResponse(
        Long id,
        Long supplierId,
        String supplierName,
        LocalDate receivedAt,
        String notes,
        List<StockReceptionLineResponse> lines,
        LocalDateTime createdAt,
        String createdBy
) {
    public record StockReceptionLineResponse(Long id, Long productId, String productName, Integer quantity) {
    }
}

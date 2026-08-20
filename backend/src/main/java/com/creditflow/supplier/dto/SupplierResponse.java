package com.creditflow.supplier.dto;

import java.time.LocalDateTime;

public record SupplierResponse(
        Long id,
        String name,
        String contactName,
        String phone,
        String email,
        String address,
        String notes,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        String updatedBy
) {
}

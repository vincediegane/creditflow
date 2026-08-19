package com.creditflow.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CustomerResponse(
        Long id,
        String firstName,
        String lastName,
        String fullName,
        String phone,
        String address,
        String cniNumber,
        String profession,
        String photoUrl,
        String notes,
        boolean active,
        LocalDateTime createdAt,
        Long salesCount,
        BigDecimal totalRemaining,
        boolean late
) {
}

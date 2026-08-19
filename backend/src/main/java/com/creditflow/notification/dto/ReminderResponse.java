package com.creditflow.notification.dto;

import java.math.BigDecimal;

public record ReminderResponse(
        Long customerId,
        String customerName,
        String phone,
        BigDecimal amount,
        String message,
        /** Canal utilise : MANUAL_COPY pour le MVP, WHATSAPP_CLOUD_API plus tard. */
        String channel,
        boolean sent
) {
}

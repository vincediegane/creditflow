package com.creditflow.notification.dto;

import java.util.List;

public record BulkReminderResponse(
        int total,
        int sent,
        int failed,
        List<ReminderResponse> results
) {
}

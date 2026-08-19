package com.creditflow.notification.web;

import com.creditflow.config.AppProperties;
import com.creditflow.notification.dto.BulkReminderResponse;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.dto.ReminderRequest;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.notification.dto.SendAllRequest;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.notification.service.NotificationChannel;
import com.creditflow.notification.service.ReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
@Tag(name = "Relances", description = "Generation des messages de relance et clients en retard")
public class ReminderController {

    private final ReminderService reminderService;
    private final LateCustomerService lateCustomerService;
    private final NotificationChannel notificationChannel;
    private final AppProperties properties;

    @PostMapping("/generate")
    @Operation(summary = "Generer le message de relance a copier")
    public ReminderResponse generate(@Valid @RequestBody ReminderRequest request) {
        return reminderService.generate(request);
    }

    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Envoyer une relance automatique a un client")
    public ReminderResponse send(@Valid @RequestBody ReminderRequest request) {
        return reminderService.send(request);
    }

    @PostMapping("/send-all")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Envoyer une relance automatique a tous les clients en retard")
    public BulkReminderResponse sendAll(@RequestBody(required = false) SendAllRequest request) {
        return reminderService.sendAll(request == null ? null : request.template());
    }

    @GetMapping("/late-customers")
    @Operation(summary = "Tableau des clients en retard")
    public List<LateCustomerResponse> lateCustomers() {
        return lateCustomerService.lateCustomers();
    }

    @GetMapping("/settings")
    @Operation(summary = "Gabarit et canal de relance configures")
    public Map<String, Object> settings() {
        return Map.of(
                "channel", notificationChannel.name(),
                "template", properties.getReminder().getDefaultTemplate(),
                "windowStartDay", properties.getReminder().getWindowStartDay(),
                "windowEndDay", properties.getReminder().getWindowEndDay(),
                "shopName", properties.getShop().getName(),
                "currency", properties.getShop().getCurrency());
    }
}

package com.creditflow.notification.service;

import com.creditflow.audit.repository.AuditLogRepository;
import com.creditflow.common.security.TenantContext;
import com.creditflow.config.AppProperties;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Declenche quotidiennement les relances de retard pour toutes les organisations
 * actives, avec un garde-fou anti-doublon base sur le journal d'audit. Le
 * declenchement manuel ({@code /api/reminders/send}, {@code /send-all}) n'est pas
 * affecte : cette tache passe exclusivement par {@link ReminderService#sendAutomatic}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderSchedulerJob {

    private final AppProperties properties;
    private final NotificationChannel notificationChannel;
    private final OrganizationRepository organizationRepository;
    private final ShopRepository shopRepository;
    private final LateCustomerService lateCustomerService;
    private final ReminderService reminderService;
    private final AuditLogRepository auditLogRepository;

    @Scheduled(cron = "${app.reminder.auto-cron}", zone = "${TZ:Africa/Dakar}")
    public void run() {
        if (!properties.getReminder().isAutoEnabled()) {
            log.info("Relances automatiques desactivees (app.reminder.auto-enabled=false).");
            return;
        }
        if (ManualCopyChannel.NAME.equals(notificationChannel.name())) {
            log.info("Aucun canal automatique configure : relances planifiees ignorees.");
            return;
        }
        for (Organization organization : organizationRepository.findAll()) {
            runForOrganization(organization);
        }
    }

    private void runForOrganization(Organization organization) {
        List<Shop> shops = shopRepository
                .findAllByActiveTrueAndOrganizationIdOrderByNameAsc(organization.getId());
        if (shops.isEmpty()) {
            log.debug("Organisation {} sans boutique active : ignoree.", organization.getId());
            return;
        }
        TenantContext.set(organization.getId());
        try {
            processOrganization(organization, shops.stream().map(Shop::getId).toList());
        } catch (Exception e) {
            log.error("Echec de la tache de relance pour l'organisation {} : {}",
                    organization.getId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    private void processOrganization(Organization organization, List<Long> shopIds) {
        int attempted = 0, sent = 0, skipped = 0, failed = 0;
        int cooldownDays = properties.getReminder().getAutoCooldownDays();
        LocalDateTime since = LocalDateTime.now().minusDays(cooldownDays);

        for (LateCustomerResponse lateCustomer :
                lateCustomerService.lateCustomers(shopIds, organization.getId())) {
            attempted++;
            if (auditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(
                    "CUSTOMER", lateCustomer.customerId(), "REMINDER_SENT", since)) {
                skipped++;
                continue;
            }
            try {
                ReminderResponse response = reminderService.sendAutomatic(lateCustomer.customerId());
                if (response.sent()) sent++; else failed++;
            } catch (Exception e) {
                log.warn("Echec de la relance automatique pour le client {} (organisation {}) : {}",
                        lateCustomer.customerId(), organization.getId(), e.getMessage());
                failed++;
            }
        }
        log.info("Organisation {} : {} tentes, {} envoyes, {} ignores (cooldown), {} echoues.",
                organization.getId(), attempted, sent, skipped, failed);
    }
}

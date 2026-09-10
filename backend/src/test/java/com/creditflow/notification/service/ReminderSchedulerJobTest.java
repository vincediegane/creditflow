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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReminderSchedulerJobTest {

    @Mock
    private NotificationChannel notificationChannel;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private LateCustomerService lateCustomerService;

    @Mock
    private ReminderService reminderService;

    @Mock
    private AuditLogRepository auditLogRepository;

    private AppProperties properties;
    private ReminderSchedulerJob job;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getReminder().setAutoEnabled(true);
        properties.getReminder().setAutoCooldownDays(3);

        job = new ReminderSchedulerJob(properties, notificationChannel, organizationRepository,
                shopRepository, lateCustomerService, reminderService, auditLogRepository);

        when(notificationChannel.name()).thenReturn("WHATSAPP_CLOUD_API");
        when(auditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(
                any(), anyLong(), any(), any())).thenReturn(false);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("autoEnabled=false : run() ne touche ni les organisations ni les clients en retard")
    void doesNothingWhenAutoDisabled() {
        properties.getReminder().setAutoEnabled(false);

        job.run();

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(lateCustomerService);
    }

    @Test
    @DisplayName("canal MANUAL_COPY : run() ne touche ni les organisations ni les clients en retard")
    void doesNothingWhenChannelIsManual() {
        when(notificationChannel.name()).thenReturn(ManualCopyChannel.NAME);

        job.run();

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(lateCustomerService);
    }

    @Test
    @DisplayName("organisation sans boutique active : jamais interrogee pour les clients en retard")
    void skipsOrganizationsWithoutActiveShop() {
        Organization organization = organization(1L);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of());

        job.run();

        verify(lateCustomerService, never()).lateCustomers(any(), any());
    }

    @Test
    @DisplayName("deux organisations : chacune est traitee avec son propre organizationId et TenantContext")
    void processesEachOrganizationWithItsOwnTenantContext() {
        Organization orgA = organization(1L);
        Organization orgB = organization(2L);
        when(organizationRepository.findAll()).thenReturn(List.of(orgA, orgB));

        Shop shopA = shop(10L);
        Shop shopB = shop(20L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shopA));
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(2L)).thenReturn(List.of(shopB));

        when(lateCustomerService.lateCustomers(List.of(10L), 1L)).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(1L);
            return List.of(lateCustomer(100L));
        });
        when(lateCustomerService.lateCustomers(List.of(20L), 2L)).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(2L);
            return List.of(lateCustomer(200L));
        });

        when(reminderService.sendAutomatic(anyLong())).thenReturn(sentResponse());

        job.run();

        verify(lateCustomerService).lateCustomers(List.of(10L), 1L);
        verify(lateCustomerService).lateCustomers(List.of(20L), 2L);
        verify(reminderService).sendAutomatic(100L);
        verify(reminderService).sendAutomatic(200L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("cooldown actif : sendAutomatic n'est pas appele pour ce client")
    void skipsCustomerUnderCooldown() {
        Organization organization = organization(1L);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        Shop shop = shop(10L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shop));
        when(lateCustomerService.lateCustomers(List.of(10L), 1L)).thenReturn(List.of(lateCustomer(100L)));
        when(auditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(
                eq("CUSTOMER"), eq(100L), eq("REMINDER_SENT"), any())).thenReturn(true);

        job.run();

        verify(reminderService, never()).sendAutomatic(anyLong());
    }

    @Test
    @DisplayName("cooldown inactif : sendAutomatic est appele pour ce client")
    void sendsCustomerNotUnderCooldown() {
        Organization organization = organization(1L);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        Shop shop = shop(10L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shop));
        when(lateCustomerService.lateCustomers(List.of(10L), 1L)).thenReturn(List.of(lateCustomer(100L)));
        when(reminderService.sendAutomatic(100L)).thenReturn(sentResponse());

        job.run();

        verify(reminderService).sendAutomatic(100L);
    }

    @Test
    @DisplayName("un client en echec n'empeche pas le traitement des suivants du meme lot")
    void continuesBatchWhenOneCustomerFails() {
        Organization organization = organization(1L);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        Shop shop = shop(10L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shop));
        when(lateCustomerService.lateCustomers(List.of(10L), 1L))
                .thenReturn(List.of(lateCustomer(100L), lateCustomer(200L)));
        when(reminderService.sendAutomatic(100L)).thenThrow(new RuntimeException("boom"));
        when(reminderService.sendAutomatic(200L)).thenReturn(sentResponse());

        job.run();

        verify(reminderService).sendAutomatic(100L);
        verify(reminderService).sendAutomatic(200L);
    }

    @Test
    @DisplayName("une organisation en echec n'empeche pas le traitement de la suivante")
    void continuesToNextOrganizationWhenOneFails() {
        Organization orgA = organization(1L);
        Organization orgB = organization(2L);
        when(organizationRepository.findAll()).thenReturn(List.of(orgA, orgB));

        Shop shopA = shop(10L);
        Shop shopB = shop(20L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shopA));
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(2L)).thenReturn(List.of(shopB));

        when(lateCustomerService.lateCustomers(List.of(10L), 1L)).thenThrow(new RuntimeException("boom"));
        when(lateCustomerService.lateCustomers(List.of(20L), 2L)).thenReturn(List.of(lateCustomer(200L)));
        when(reminderService.sendAutomatic(200L)).thenReturn(sentResponse());

        job.run();

        verify(lateCustomerService).lateCustomers(List.of(20L), 2L);
        verify(reminderService).sendAutomatic(200L);
    }

    @Test
    @DisplayName("TenantContext.clear() est appele meme si le traitement d'une organisation echoue")
    void clearsTenantContextEvenOnFailure() {
        Organization organization = organization(1L);
        when(organizationRepository.findAll()).thenReturn(List.of(organization));
        Shop shop = shop(10L);
        when(shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(1L)).thenReturn(List.of(shop));
        when(lateCustomerService.lateCustomers(List.of(10L), 1L)).thenThrow(new RuntimeException("boom"));

        job.run();

        assertThat(TenantContext.get()).isNull();
    }

    private Organization organization(Long id) {
        return Organization.builder().id(id).name("Organisation " + id).build();
    }

    private Shop shop(Long id) {
        return Shop.builder().id(id).name("Boutique " + id).active(true).build();
    }

    private LateCustomerResponse lateCustomer(Long customerId) {
        return new LateCustomerResponse(customerId, "Amadou Diallo", "770000001", "iPhone 13", 1, 5,
                LocalDate.now().minusDays(5), new BigDecimal("15000"), new BigDecimal("15000"),
                10L, "VC-2026-00001", new BigDecimal("15000"), BigDecimal.ZERO);
    }

    private ReminderResponse sentResponse() {
        return new ReminderResponse(1L, "Amadou Diallo", "770000001", new BigDecimal("15000"),
                "message", "WHATSAPP_CLOUD_API", true);
    }
}

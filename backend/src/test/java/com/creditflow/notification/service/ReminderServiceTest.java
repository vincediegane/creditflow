package com.creditflow.notification.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.notification.dto.BulkReminderResponse;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.dto.ReminderRequest;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.product.domain.Product;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReminderServiceTest {

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private ReminderMessageBuilder messageBuilder;

    @Mock
    private NotificationChannel notificationChannel;

    @Mock
    private LateCustomerService lateCustomerService;

    @Mock
    private AuditLogService auditLogService;

    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        reminderService = new ReminderService(saleRepository, installmentRepository, customerService,
                messageBuilder, notificationChannel, lateCustomerService, auditLogService);

        when(messageBuilder.build(any(), any())).thenReturn("Bonjour, votre echeance est en retard.");
        when(installmentRepository.findBySaleIdOrderByNumberAsc(anyLong())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("generate() ne declenche jamais l'envoi reel")
    void generateNeverCallsChannel() {
        Customer customer = customer(1L, "770000001");
        when(customerService.getEntity(1L)).thenReturn(customer);
        when(saleRepository.findByCustomer(1L)).thenReturn(List.of(sale(customer)));
        when(notificationChannel.name()).thenReturn("WHATSAPP_CLOUD_API");

        ReminderResponse response = reminderService.generate(new ReminderRequest(null, 1L, null));

        assertThat(response.sent()).isFalse();
        verify(notificationChannel, never()).send(any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("send() refuse le canal MANUAL_COPY")
    void sendRejectsManualChannel() {
        when(notificationChannel.name()).thenReturn(ManualCopyChannel.NAME);

        assertThatThrownBy(() -> reminderService.send(new ReminderRequest(null, 1L, null)))
                .isInstanceOf(BusinessRuleException.class);

        verify(notificationChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("send() avec un canal automatique historise un succes")
    void sendRecordsSuccess() {
        Customer customer = customer(1L, "770000001");
        when(customerService.getEntity(1L)).thenReturn(customer);
        when(saleRepository.findByCustomer(1L)).thenReturn(List.of(sale(customer)));
        when(notificationChannel.name()).thenReturn("WHATSAPP_CLOUD_API");
        when(notificationChannel.send(eq("770000001"), any())).thenReturn(true);

        ReminderResponse response = reminderService.send(new ReminderRequest(null, 1L, null));

        assertThat(response.sent()).isTrue();
        verify(auditLogService).record(eq("CUSTOMER"), eq(1L), eq(customer.getFullName()),
                eq("REMINDER_SENT"), any());
    }

    @Test
    @DisplayName("send() avec un canal automatique historise un echec")
    void sendRecordsFailure() {
        Customer customer = customer(1L, "770000001");
        when(customerService.getEntity(1L)).thenReturn(customer);
        when(saleRepository.findByCustomer(1L)).thenReturn(List.of(sale(customer)));
        when(notificationChannel.name()).thenReturn("WHATSAPP_CLOUD_API");
        when(notificationChannel.send(eq("770000001"), any())).thenReturn(false);

        ReminderResponse response = reminderService.send(new ReminderRequest(null, 1L, null));

        assertThat(response.sent()).isFalse();
        verify(auditLogService).record(eq("CUSTOMER"), eq(1L), eq(customer.getFullName()),
                eq("REMINDER_FAILED"), any());
    }

    @Test
    @DisplayName("sendAll() refuse le canal MANUAL_COPY avant d'interroger les clients en retard")
    void sendAllRejectsManualChannel() {
        when(notificationChannel.name()).thenReturn(ManualCopyChannel.NAME);

        assertThatThrownBy(() -> reminderService.sendAll(null))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(lateCustomerService);
    }

    @Test
    @DisplayName("sendAll() continue le lot meme si un client echoue")
    void sendAllContinuesOnFailure() {
        Customer okCustomer = customer(1L, "770000001");
        when(notificationChannel.name()).thenReturn("WHATSAPP_CLOUD_API");
        when(notificationChannel.send(eq("770000001"), any())).thenReturn(true);

        when(lateCustomerService.lateCustomers()).thenReturn(List.of(
                lateCustomer(1L, "Amadou Diallo", "770000001"),
                lateCustomer(2L, "Fatou Ndiaye", "770000002")));

        when(customerService.getEntity(1L)).thenReturn(okCustomer);
        when(saleRepository.findByCustomer(1L)).thenReturn(List.of(sale(okCustomer)));

        Customer failingCustomer = customer(2L, "770000002");
        when(customerService.getEntity(2L)).thenReturn(failingCustomer);
        when(saleRepository.findByCustomer(2L)).thenReturn(Collections.emptyList());

        BulkReminderResponse response = reminderService.sendAll(null);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.sent()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
    }

    private Customer customer(Long id, String phone) {
        return Customer.builder()
                .id(id)
                .firstName("Amadou")
                .lastName("Diallo")
                .phone(phone)
                .build();
    }

    private CreditSale sale(Customer customer) {
        Product product = Product.builder().id(1L).name("iPhone 13").build();
        return CreditSale.builder()
                .id(10L)
                .reference("VC-2026-00001")
                .customer(customer)
                .product(product)
                .monthlyAmount(new BigDecimal("15000"))
                .remainingAmount(new BigDecimal("15000"))
                .status(SaleStatus.ACTIVE)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(6))
                .build();
    }

    private LateCustomerResponse lateCustomer(Long customerId, String name, String phone) {
        return new LateCustomerResponse(customerId, name, phone, "iPhone 13", 1, 5,
                LocalDate.now().minusDays(5), new BigDecimal("15000"), new BigDecimal("15000"),
                10L, "VC-2026-00001", new BigDecimal("15000"), BigDecimal.ZERO);
    }
}

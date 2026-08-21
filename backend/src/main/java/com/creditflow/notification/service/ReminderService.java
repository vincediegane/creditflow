package com.creditflow.notification.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.audit.service.AuditLogService;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.notification.dto.BulkReminderResponse;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.dto.ReminderRequest;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final CreditSaleRepository saleRepository;
    private final InstallmentRepository installmentRepository;
    private final CustomerService customerService;
    private final ReminderMessageBuilder messageBuilder;
    private final NotificationChannel notificationChannel;
    private final LateCustomerService lateCustomerService;
    private final AuditLogService auditLogService;
    private final CurrentShopContext currentShopContext;

    private record ReminderPreview(Customer customer, BigDecimal amount, String message) {
    }

    @Transactional(readOnly = true)
    public ReminderResponse generate(ReminderRequest request) {
        ReminderPreview preview = prepare(request.saleId(), request.customerId(), request.template());
        return new ReminderResponse(
                preview.customer().getId(),
                preview.customer().getFullName(),
                preview.customer().getPhone(),
                preview.amount(),
                preview.message(),
                notificationChannel.name(),
                false);
    }

    @Transactional
    public ReminderResponse send(ReminderRequest request) {
        requireAutomaticChannel();
        ReminderPreview preview = prepare(request.saleId(), request.customerId(), request.template());
        return doSend(preview.customer(), preview.amount(), preview.message());
    }

    @Transactional
    public BulkReminderResponse sendAll(String template) {
        requireAutomaticChannel();

        List<ReminderResponse> results = new ArrayList<>();
        for (LateCustomerResponse lateCustomer : lateCustomerService.lateCustomers(
                currentShopContext.accessibleShopIds())) {
            try {
                ReminderPreview preview = prepareForCustomer(lateCustomer.customerId(), template);
                results.add(doSend(preview.customer(), preview.amount(), preview.message()));
            } catch (Exception e) {
                log.warn("Echec de preparation/envoi de la relance pour le client {} : {}",
                        lateCustomer.customerId(), e.getMessage());
                results.add(new ReminderResponse(
                        lateCustomer.customerId(),
                        lateCustomer.customerName(),
                        lateCustomer.phone(),
                        BigDecimal.ZERO,
                        "",
                        notificationChannel.name(),
                        false));
            }
        }

        int sent = (int) results.stream().filter(ReminderResponse::sent).count();
        return new BulkReminderResponse(results.size(), sent, results.size() - sent, results);
    }

    private void requireAutomaticChannel() {
        if (ManualCopyChannel.NAME.equals(notificationChannel.name())) {
            throw new BusinessRuleException("Aucun canal automatique n'est configure : utilisez la copie manuelle");
        }
    }

    private ReminderPreview prepare(Long saleId, Long customerId, String template) {
        if (saleId != null) {
            return prepareForSale(saleId, template);
        }
        if (customerId != null) {
            return prepareForCustomer(customerId, template);
        }
        throw new BusinessRuleException("Indiquez un contrat ou un client pour generer la relance");
    }

    private ReminderResponse doSend(Customer customer, BigDecimal amount, String message) {
        boolean sent = notificationChannel.send(customer.getPhone(), message);
        auditLogService.record("CUSTOMER", customer.getId(), customer.getFullName(),
                sent ? "REMINDER_SENT" : "REMINDER_FAILED", "Canal " + notificationChannel.name());
        return new ReminderResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getPhone(),
                amount,
                message,
                notificationChannel.name(),
                sent);
    }

    private ReminderPreview prepareForSale(Long saleId, String template) {
        CreditSale sale = saleRepository.findDetailById(saleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", saleId));
        currentShopContext.assertAccessible(sale.getShop().getId());

        LocalDate today = LocalDate.now();
        List<Installment> installments = installmentRepository.findBySaleIdOrderByNumberAsc(saleId);

        Optional<Installment> target = installments.stream()
                .filter(i -> !i.isSettled())
                .min(Comparator.comparing(Installment::getDueDate));

        BigDecimal amount = target.map(Installment::getRemaining).orElse(sale.getMonthlyAmount());
        long daysLate = target.map(i -> i.daysLate(today)).orElse(0L);

        ReminderMessageBuilder.ReminderContext context = new ReminderMessageBuilder.ReminderContext(
                sale.getCustomer().getFullName(),
                sale.getProduct().getName(),
                sale.getReference(),
                amount,
                sale.getRemainingAmount(),
                target.map(Installment::getDueDate).orElse(null),
                daysLate);

        return new ReminderPreview(sale.getCustomer(), amount, messageBuilder.build(context, template));
    }

    private ReminderPreview prepareForCustomer(Long customerId, String template) {
        Customer customer = customerService.getEntity(customerId);
        LocalDate today = LocalDate.now();

        List<CreditSale> sales = saleRepository.findByCustomer(customerId);
        if (sales.isEmpty()) {
            throw new BusinessRuleException("Ce client n'a aucun contrat en cours");
        }

        List<Installment> pending = sales.stream()
                .flatMap(sale -> installmentRepository.findBySaleIdOrderByNumberAsc(sale.getId()).stream())
                .filter(i -> !i.isSettled())
                .sorted(Comparator.comparing(Installment::getDueDate))
                .toList();

        BigDecimal amount = pending.isEmpty()
                ? BigDecimal.ZERO
                : pending.stream()
                        .filter(i -> i.getDueDate().equals(pending.get(0).getDueDate()))
                        .map(Installment::getRemaining)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remaining = sales.stream()
                .map(CreditSale::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Installment first = pending.isEmpty() ? null : pending.get(0);

        ReminderMessageBuilder.ReminderContext context = new ReminderMessageBuilder.ReminderContext(
                customer.getFullName(),
                sales.get(0).getProduct().getName(),
                sales.get(0).getReference(),
                amount,
                remaining,
                first == null ? null : first.getDueDate(),
                first == null ? 0L : first.daysLate(today));

        return new ReminderPreview(customer, amount, messageBuilder.build(context, template));
    }
}

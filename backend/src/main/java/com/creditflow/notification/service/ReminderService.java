package com.creditflow.notification.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.notification.dto.ReminderRequest;
import com.creditflow.notification.dto.ReminderResponse;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final CreditSaleRepository saleRepository;
    private final InstallmentRepository installmentRepository;
    private final CustomerService customerService;
    private final ReminderMessageBuilder messageBuilder;
    private final NotificationChannel notificationChannel;

    @Transactional(readOnly = true)
    public ReminderResponse generate(ReminderRequest request) {
        if (request.saleId() != null) {
            return generateForSale(request.saleId(), request.template());
        }
        if (request.customerId() != null) {
            return generateForCustomer(request.customerId(), request.template());
        }
        throw new BusinessRuleException("Indiquez un contrat ou un client pour generer la relance");
    }

    private ReminderResponse generateForSale(Long saleId, String template) {
        CreditSale sale = saleRepository.findDetailById(saleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", saleId));

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

        return respond(sale.getCustomer(), amount, messageBuilder.build(context, template));
    }

    private ReminderResponse generateForCustomer(Long customerId, String template) {
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

        return respond(customer, amount, messageBuilder.build(context, template));
    }

    private ReminderResponse respond(Customer customer, BigDecimal amount, String message) {
        boolean sent = notificationChannel.send(customer.getPhone(), message);
        return new ReminderResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getPhone(),
                amount,
                message,
                notificationChannel.name(),
                sent);
    }
}

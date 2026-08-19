package com.creditflow.notification.service;

import com.creditflow.customer.domain.Customer;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.repository.InstallmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agrege les echeances en retard par client pour alimenter le tableau des
 * relances et le tableau de bord.
 */
@Service
@RequiredArgsConstructor
public class LateCustomerService {

    private final InstallmentRepository installmentRepository;

    @Transactional(readOnly = true)
    public List<LateCustomerResponse> lateCustomers() {
        LocalDate today = LocalDate.now();

        Map<Long, List<Installment>> byCustomer = installmentRepository.findLate(today).stream()
                .collect(Collectors.groupingBy(i -> i.getSale().getCustomer().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        return byCustomer.values().stream()
                .map(installments -> toResponse(installments, today))
                .sorted(Comparator.comparingLong(LateCustomerResponse::daysLate).reversed())
                .toList();
    }

    private LateCustomerResponse toResponse(List<Installment> installments, LocalDate today) {
        Installment oldest = installments.stream()
                .min(Comparator.comparing(Installment::getDueDate))
                .orElseThrow();

        CreditSale primarySale = oldest.getSale();
        Customer customer = primarySale.getCustomer();

        BigDecimal lateAmount = installments.stream()
                .map(Installment::getRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remaining = installments.stream()
                .map(Installment::getSale)
                .distinct()
                .map(CreditSale::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String products = installments.stream()
                .map(i -> i.getSale().getProduct().getName())
                .distinct()
                .collect(Collectors.joining(", "));

        return new LateCustomerResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getPhone(),
                products,
                installments.size(),
                ChronoUnit.DAYS.between(oldest.getDueDate(), today),
                oldest.getDueDate(),
                lateAmount,
                remaining,
                primarySale.getId(),
                primarySale.getReference(),
                primarySale.getMonthlyAmount());
    }
}

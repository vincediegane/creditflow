package com.creditflow.report.service;

import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.report.dto.ReportData;
import com.creditflow.report.dto.ReportType;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String UNASSIGNED_PROFESSION = "Non renseignee";
    private static final String UNASSIGNED_SELLER = "Non attribue";

    private final PaymentRepository paymentRepository;
    private final CreditSaleRepository saleRepository;
    private final LateCustomerService lateCustomerService;
    private final InstallmentRepository installmentRepository;
    private final UserRepository userRepository;
    private final CurrentShopContext currentShopContext;

    @Transactional(readOnly = true)
    public ReportData build(ReportType type, LocalDate from, LocalDate to,
                             String profession, BigDecimal minAmount, BigDecimal maxAmount) {
        List<Long> shopIds = currentShopContext.resolveReadFilter();
        Long organizationId = currentShopContext.currentOrganizationId();
        return switch (type) {
            case DAILY_PAYMENTS -> payments(ReportType.DAILY_PAYMENTS, "Paiements du jour",
                    defaultDate(from), defaultDate(from), shopIds, organizationId);
            case MONTHLY_PAYMENTS -> {
                LocalDate reference = defaultDate(from);
                YearMonth month = YearMonth.from(reference);
                yield payments(ReportType.MONTHLY_PAYMENTS, "Paiements du mois",
                        from == null ? month.atDay(1) : from,
                        to == null ? month.atEndOfMonth() : to, shopIds, organizationId);
            }
            case LATE_CUSTOMERS -> lateCustomers(shopIds);
            case OUTSTANDING -> outstanding(shopIds);
            case DEFAULT_RATE -> defaultRate(profession, minAmount, maxAmount, shopIds);
            case SELLER_PERFORMANCE -> sellerPerformance(shopIds);
        };
    }

    private ReportData payments(ReportType type, String title, LocalDate from, LocalDate to,
                                 List<Long> shopIds, Long organizationId) {
        List<Payment> payments = paymentRepository.findBetweenForShops(from, to, shopIds, organizationId);

        List<List<Object>> rows = payments.stream()
                .map(p -> List.<Object>of(
                        p.getPaymentDate().format(DATE),
                        p.getSale().getReference(),
                        p.getSale().getCustomer().getFullName(),
                        p.getSale().getCustomer().getPhone(),
                        p.getSale().getProduct().getName(),
                        p.getAmount(),
                        p.getMethod().name(),
                        p.getReference() == null ? "" : p.getReference()))
                .toList();

        BigDecimal total = payments.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReportData(
                type,
                title,
                from.equals(to) ? from.format(DATE) : "%s - %s".formatted(from.format(DATE), to.format(DATE)),
                List.of(
                        ReportData.Column.date("Date"),
                        ReportData.Column.text("Contrat"),
                        ReportData.Column.text("Client"),
                        ReportData.Column.text("Telephone"),
                        ReportData.Column.text("Produit"),
                        ReportData.Column.money("Montant"),
                        ReportData.Column.text("Mode"),
                        ReportData.Column.text("Reference")),
                rows,
                List.of(
                        new ReportData.Total("Nombre de paiements", (long) payments.size(),
                                ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Total encaisse", total, ReportData.ColumnType.MONEY)),
                LocalDateTime.now());
    }

    private ReportData lateCustomers(List<Long> shopIds) {
        List<LateCustomerResponse> late = lateCustomerService.lateCustomers(shopIds);

        List<List<Object>> rows = late.stream()
                .map(l -> List.<Object>of(
                        l.customerName(),
                        l.phone(),
                        l.productNames(),
                        l.oldestDueDate().format(DATE),
                        l.daysLate(),
                        l.lateInstallments(),
                        l.lateAmount(),
                        l.remainingAmount()))
                .toList();

        BigDecimal totalLate = late.stream()
                .map(LateCustomerResponse::lateAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = late.stream()
                .map(LateCustomerResponse::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReportData(
                ReportType.LATE_CUSTOMERS,
                "Clients en retard",
                "Au " + LocalDate.now().format(DATE),
                List.of(
                        ReportData.Column.text("Client"),
                        ReportData.Column.text("Telephone"),
                        ReportData.Column.text("Produit(s)"),
                        ReportData.Column.date("Echeance la plus ancienne"),
                        ReportData.Column.number("Jours de retard"),
                        ReportData.Column.number("Echeances en retard"),
                        ReportData.Column.money("Montant en retard"),
                        ReportData.Column.money("Reste a payer")),
                rows,
                List.of(
                        new ReportData.Total("Clients en retard", (long) late.size(),
                                ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Montant en retard", totalLate, ReportData.ColumnType.MONEY),
                        new ReportData.Total("Reste a payer", totalRemaining, ReportData.ColumnType.MONEY)),
                LocalDateTime.now());
    }

    private ReportData outstanding(List<Long> shopIds) {
        List<CreditSale> sales = saleRepository.findAllDetailedForShops(shopIds).stream()
                .filter(s -> s.getStatus() == SaleStatus.ACTIVE)
                .toList();

        List<List<Object>> rows = new ArrayList<>();
        for (CreditSale sale : sales) {
            rows.add(List.of(
                    sale.getReference(),
                    sale.getCustomer().getFullName(),
                    sale.getCustomer().getPhone(),
                    sale.getProduct().getName(),
                    sale.getTotalPrice(),
                    sale.getDownPayment(),
                    sale.getAmountPaid(),
                    sale.getRemainingAmount(),
                    sale.getMonthlyAmount(),
                    sale.getEndDate().format(DATE)));
        }

        BigDecimal totalRemaining = sales.stream()
                .map(CreditSale::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFinanced = sales.stream()
                .map(CreditSale::getFinancedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReportData(
                ReportType.OUTSTANDING,
                "Creances restantes",
                "Au " + LocalDate.now().format(DATE),
                List.of(
                        ReportData.Column.text("Contrat"),
                        ReportData.Column.text("Client"),
                        ReportData.Column.text("Telephone"),
                        ReportData.Column.text("Produit"),
                        ReportData.Column.money("Prix total"),
                        ReportData.Column.money("Acompte"),
                        ReportData.Column.money("Deja paye"),
                        ReportData.Column.money("Reste a payer"),
                        ReportData.Column.money("Mensualite"),
                        ReportData.Column.date("Fin prevue")),
                rows,
                List.of(
                        new ReportData.Total("Contrats actifs", (long) sales.size(),
                                ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Total finance", totalFinanced, ReportData.ColumnType.MONEY),
                        new ReportData.Total("Reste a recuperer", totalRemaining, ReportData.ColumnType.MONEY)),
                LocalDateTime.now());
    }

    private ReportData defaultRate(String profession, BigDecimal minAmount, BigDecimal maxAmount,
                                   List<Long> shopIds) {
        String normalizedFilter = profession == null || profession.isBlank() ? null : profession.trim();

        List<CreditSale> sales = saleRepository.findAllDetailedForShops(shopIds).stream()
                .filter(s -> s.getStatus() == SaleStatus.ACTIVE)
                .filter(s -> minAmount == null || s.getTotalPrice().compareTo(minAmount) >= 0)
                .filter(s -> maxAmount == null || s.getTotalPrice().compareTo(maxAmount) <= 0)
                .filter(s -> normalizedFilter == null
                        || normalizeProfession(s.getCustomer().getProfession()).equalsIgnoreCase(normalizedFilter))
                .toList();

        LateInstallments late = lateInstallments(shopIds);
        Set<Long> lateSaleIds = late.saleIds();
        Map<Long, BigDecimal> lateAmountBySale = late.amountBySale();

        Map<String, List<CreditSale>> byProfession = sales.stream()
                .collect(Collectors.groupingBy(s -> normalizeProfession(s.getCustomer().getProfession())));

        List<Map.Entry<String, List<CreditSale>>> sortedEntries = byProfession.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<List<Object>> rows = new ArrayList<>();
        long totalContracts = 0;
        long totalLate = 0;
        BigDecimal totalLateAmount = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;

        for (Map.Entry<String, List<CreditSale>> entry : sortedEntries) {
            List<CreditSale> group = entry.getValue();
            long lateCount = group.stream().filter(s -> lateSaleIds.contains(s.getId())).count();
            BigDecimal lateAmount = group.stream()
                    .filter(s -> lateSaleIds.contains(s.getId()))
                    .map(s -> lateAmountBySale.getOrDefault(s.getId(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = group.stream()
                    .map(CreditSale::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            rows.add(List.of(
                    entry.getKey(),
                    group.size(),
                    lateCount,
                    defaultRateText(lateCount, group.size()),
                    lateAmount,
                    remaining));

            totalContracts += group.size();
            totalLate += lateCount;
            totalLateAmount = totalLateAmount.add(lateAmount);
            totalRemaining = totalRemaining.add(remaining);
        }

        return new ReportData(
                ReportType.DEFAULT_RATE,
                "Taux de defaut par profession",
                "Au " + LocalDate.now().format(DATE),
                List.of(
                        ReportData.Column.text("Profession"),
                        ReportData.Column.number("Contrats actifs"),
                        ReportData.Column.number("Contrats en retard"),
                        ReportData.Column.text("Taux de defaut"),
                        ReportData.Column.money("Montant en retard"),
                        ReportData.Column.money("Reste a payer")),
                rows,
                List.of(
                        new ReportData.Total("Professions", (long) sortedEntries.size(), ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Contrats actifs", totalContracts, ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Contrats en retard", totalLate, ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Taux de defaut global", defaultRateText(totalLate, totalContracts),
                                ReportData.ColumnType.TEXT),
                        new ReportData.Total("Montant en retard", totalLateAmount, ReportData.ColumnType.MONEY),
                        new ReportData.Total("Reste a payer", totalRemaining, ReportData.ColumnType.MONEY)),
                LocalDateTime.now());
    }

    private ReportData sellerPerformance(List<Long> shopIds) {
        Map<String, String> fullNameByUsername = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getUsername, User::getFullName, (a, b) -> a));

        List<CreditSale> sales = saleRepository.findAllDetailedForShops(shopIds).stream()
                .filter(s -> s.getStatus() != SaleStatus.CANCELLED)
                .toList();

        LateInstallments late = lateInstallments(shopIds);
        Set<Long> lateSaleIds = late.saleIds();

        Map<String, List<CreditSale>> bySeller = sales.stream()
                .collect(Collectors.groupingBy(s -> sellerLabel(s.getCreatedBy(), fullNameByUsername)));

        record SellerLine(String seller, List<CreditSale> group, BigDecimal financed, BigDecimal paid,
                           BigDecimal remaining, long lateCount) {
        }

        List<SellerLine> lines = bySeller.entrySet().stream()
                .map(entry -> {
                    List<CreditSale> group = entry.getValue();
                    BigDecimal financed = group.stream()
                            .map(CreditSale::getFinancedAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal paid = group.stream()
                            .map(CreditSale::getAmountPaid)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal remaining = group.stream()
                            .map(CreditSale::getRemainingAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    long lateCount = group.stream().filter(s -> lateSaleIds.contains(s.getId())).count();
                    return new SellerLine(entry.getKey(), group, financed, paid, remaining, lateCount);
                })
                .sorted((a, b) -> {
                    int cmp = b.paid().compareTo(a.paid());
                    return cmp != 0 ? cmp : a.seller().compareToIgnoreCase(b.seller());
                })
                .toList();

        List<List<Object>> rows = new ArrayList<>();
        long totalContracts = 0;
        BigDecimal totalFinanced = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;

        for (SellerLine line : lines) {
            rows.add(List.of(
                    line.seller(),
                    line.group().size(),
                    line.financed(),
                    line.paid(),
                    line.remaining(),
                    line.lateCount(),
                    defaultRateText(line.lateCount(), line.group().size())));

            totalContracts += line.group().size();
            totalFinanced = totalFinanced.add(line.financed());
            totalPaid = totalPaid.add(line.paid());
            totalRemaining = totalRemaining.add(line.remaining());
        }

        return new ReportData(
                ReportType.SELLER_PERFORMANCE,
                "Performance vendeur",
                "Au " + LocalDate.now().format(DATE),
                List.of(
                        ReportData.Column.text("Vendeur"),
                        ReportData.Column.number("Contrats"),
                        ReportData.Column.money("Montant finance"),
                        ReportData.Column.money("Montant encaisse"),
                        ReportData.Column.money("Reste a recouvrer"),
                        ReportData.Column.number("Contrats en retard"),
                        ReportData.Column.text("Taux de defaut")),
                rows,
                List.of(
                        new ReportData.Total("Vendeurs", (long) lines.size(), ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Contrats", totalContracts, ReportData.ColumnType.NUMBER),
                        new ReportData.Total("Montant finance total", totalFinanced, ReportData.ColumnType.MONEY),
                        new ReportData.Total("Montant encaisse total", totalPaid, ReportData.ColumnType.MONEY),
                        new ReportData.Total("Reste a recouvrer total", totalRemaining, ReportData.ColumnType.MONEY)),
                LocalDateTime.now());
    }

    private record LateInstallments(Set<Long> saleIds, Map<Long, BigDecimal> amountBySale) {
    }

    private LateInstallments lateInstallments(List<Long> shopIds) {
        Set<Long> saleIds = new HashSet<>();
        Map<Long, BigDecimal> amountBySale = new HashMap<>();
        for (Installment installment : installmentRepository.findLateForShops(LocalDate.now(), shopIds)) {
            Long saleId = installment.getSale().getId();
            saleIds.add(saleId);
            amountBySale.merge(saleId, installment.getRemaining(), BigDecimal::add);
        }
        return new LateInstallments(saleIds, amountBySale);
    }

    private String normalizeProfession(String raw) {
        return raw == null || raw.isBlank() ? UNASSIGNED_PROFESSION : raw.trim();
    }

    private String sellerLabel(String createdBy, Map<String, String> fullNameByUsername) {
        if (createdBy == null) {
            return UNASSIGNED_SELLER;
        }
        return fullNameByUsername.getOrDefault(createdBy, UNASSIGNED_SELLER);
    }

    private String defaultRateText(long lateCount, long totalCount) {
        if (totalCount == 0) {
            return "0,0 %";
        }
        return String.format(Locale.FRANCE, "%.1f %%", lateCount * 100.0 / totalCount);
    }

    private LocalDate defaultDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }
}

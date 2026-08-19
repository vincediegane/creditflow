package com.creditflow.report.service;

import com.creditflow.notification.dto.LateCustomerResponse;
import com.creditflow.notification.service.LateCustomerService;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.report.dto.ReportData;
import com.creditflow.report.dto.ReportType;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PaymentRepository paymentRepository;
    private final CreditSaleRepository saleRepository;
    private final LateCustomerService lateCustomerService;

    @Transactional(readOnly = true)
    public ReportData build(ReportType type, LocalDate from, LocalDate to) {
        return switch (type) {
            case DAILY_PAYMENTS -> payments(ReportType.DAILY_PAYMENTS, "Paiements du jour",
                    defaultDate(from), defaultDate(from));
            case MONTHLY_PAYMENTS -> {
                LocalDate reference = defaultDate(from);
                YearMonth month = YearMonth.from(reference);
                yield payments(ReportType.MONTHLY_PAYMENTS, "Paiements du mois",
                        from == null ? month.atDay(1) : from,
                        to == null ? month.atEndOfMonth() : to);
            }
            case LATE_CUSTOMERS -> lateCustomers();
            case OUTSTANDING -> outstanding();
        };
    }

    private ReportData payments(ReportType type, String title, LocalDate from, LocalDate to) {
        List<Payment> payments = paymentRepository.findBetween(from, to);

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

    private ReportData lateCustomers() {
        List<LateCustomerResponse> late = lateCustomerService.lateCustomers();

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

    private ReportData outstanding() {
        List<CreditSale> sales = saleRepository.findAllDetailed().stream()
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

    private LocalDate defaultDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }
}

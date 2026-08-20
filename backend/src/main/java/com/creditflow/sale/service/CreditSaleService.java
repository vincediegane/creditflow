package com.creditflow.sale.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.util.Money;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleDetailResponse;
import com.creditflow.sale.dto.SalePreviewRequest;
import com.creditflow.sale.dto.SalePreviewResponse;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.repository.SaleSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditSaleService {

    private final CreditSaleRepository saleRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final InstallmentScheduleGenerator scheduleGenerator;
    private final SaleMapper saleMapper;
    private final PaymentMapper paymentMapper;
    private final AuditLogService auditLogService;
    private final PenaltySettingsService penaltySettingsService;

    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> search(String search, SaleStatus status, Long customerId, Pageable pageable) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        Page<CreditSale> page = saleRepository.findAll(
                Specs.combine(
                        SaleSpecifications.matches(search),
                        SaleSpecifications.hasStatus(status),
                        SaleSpecifications.forCustomer(customerId)),
                pageable);
        return PageResponse.of(page, sale -> saleMapper.toResponse(sale, sale.getInstallments(), today, settings));
    }

    @Transactional(readOnly = true)
    public SaleResponse findById(Long id) {
        CreditSale sale = getEntity(id);
        PenaltySettings settings = penaltySettingsService.current();
        return saleMapper.toResponse(sale, sale.getInstallments(), LocalDate.now(), settings);
    }

    @Transactional(readOnly = true)
    public SaleDetailResponse findDetail(Long id) {
        CreditSale sale = getEntity(id);
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();

        return new SaleDetailResponse(
                saleMapper.toResponse(sale, sale.getInstallments(), today, settings),
                sale.getInstallments().stream().map(i -> saleMapper.toResponse(i, today, settings)).toList(),
                paymentRepository.findBySale(id).stream().map(paymentMapper::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public List<SaleResponse> findByCustomer(Long customerId) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        return saleRepository.findByCustomer(customerId).stream()
                .map(sale -> saleMapper.toResponse(sale, sale.getInstallments(), today, settings))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreditSale getEntity(Long id) {
        return saleRepository.findDetailById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", id));
    }

    /** Simulation d'echeancier, sans persistance. */
    @Transactional(readOnly = true)
    public SalePreviewResponse preview(SalePreviewRequest request) {
        BigDecimal totalPrice = Money.round(request.totalPrice());
        BigDecimal downPayment = Money.round(Money.nullToZero(request.downPayment()));
        validateDownPayment(totalPrice, downPayment);

        BigDecimal interestAmount =
                scheduleGenerator.interestAmount(totalPrice, request.interestRate(), request.interestFee());
        BigDecimal financed = totalPrice.add(interestAmount).subtract(downPayment);

        InstallmentScheduleGenerator.Schedule schedule =
                scheduleGenerator.generate(financed, request.installmentCount(), request.startDate());

        return new SalePreviewResponse(
                totalPrice,
                interestAmount,
                financed,
                schedule.monthlyAmount(),
                schedule.endDate(),
                schedule.lines().stream()
                        .map(l -> new SalePreviewResponse.PreviewLine(l.number(), l.dueDate(), l.amount()))
                        .toList());
    }

    @Transactional
    public SaleResponse create(CreateSaleRequest request) {
        Customer customer = customerService.getEntity(request.customerId());
        Product product = productService.getEntity(request.productId());

        BigDecimal totalPrice = Money.round(request.totalPrice());
        BigDecimal downPayment = Money.round(Money.nullToZero(request.downPayment()));
        validateDownPayment(totalPrice, downPayment);

        BigDecimal interestAmount =
                scheduleGenerator.interestAmount(totalPrice, request.interestRate(), request.interestFee());
        BigDecimal financed = totalPrice.add(interestAmount).subtract(downPayment);

        InstallmentScheduleGenerator.Schedule schedule =
                scheduleGenerator.generate(financed, request.installmentCount(), request.startDate());

        CreditSale sale = CreditSale.builder()
                .reference("TMP-" + UUID.randomUUID().toString().substring(0, 8))
                .customer(customer)
                .product(product)
                .totalPrice(totalPrice)
                .interestRate(request.interestRate())
                .interestAmount(interestAmount)
                .downPayment(downPayment)
                .financedAmount(financed)
                .installmentCount(request.installmentCount())
                .monthlyAmount(schedule.monthlyAmount())
                .amountPaid(Money.ZERO)
                .remainingAmount(financed)
                .startDate(request.startDate())
                .endDate(schedule.endDate())
                .status(SaleStatus.ACTIVE)
                .notes(request.notes())
                .guarantorFullName(blankToNull(request.guarantorFullName()))
                .guarantorPhone(blankToNull(request.guarantorPhone()))
                .guarantorAddress(blankToNull(request.guarantorAddress()))
                .guarantorCniNumber(blankToNull(request.guarantorCniNumber()))
                .build();

        schedule.lines().forEach(line -> sale.addInstallment(Installment.builder()
                .number(line.number())
                .dueDate(line.dueDate())
                .amount(line.amount())
                .amountPaid(Money.ZERO)
                .penaltyPaid(Money.ZERO)
                .status(InstallmentStatus.PENDING)
                .build()));

        CreditSale saved = saleRepository.saveAndFlush(sale);
        saved.setReference(buildReference(saved));
        saved = saleRepository.save(saved);

        if (product.getStock() != null && product.getStock() > 0) {
            productService.decreaseStock(product, 1);
        }

        log.info("Contrat {} cree pour {} ({} mensualites de {})",
                saved.getReference(), customer.getFullName(), saved.getInstallmentCount(), saved.getMonthlyAmount());

        return saleMapper.toResponse(saved, saved.getInstallments(), LocalDate.now(), penaltySettingsService.current());
    }

    @Transactional
    public SaleResponse cancel(Long id) {
        CreditSale sale = getEntity(id);
        if (sale.getStatus() == SaleStatus.COMPLETED) {
            throw new BusinessRuleException("Un contrat solde ne peut pas etre annule");
        }
        sale.setStatus(SaleStatus.CANCELLED);
        auditLogService.record("CREDIT_SALE", id, sale.getReference(), "CANCEL", null);
        return saleMapper.toResponse(saleRepository.save(sale), sale.getInstallments(), LocalDate.now(),
                penaltySettingsService.current());
    }

    @Transactional
    public void delete(Long id) {
        CreditSale sale = getEntity(id);
        if (!paymentRepository.findBySale(id).isEmpty()) {
            throw new BusinessRuleException(
                    "Ce contrat comporte des paiements : annulez-le au lieu de le supprimer");
        }
        auditLogService.record("CREDIT_SALE", id, sale.getReference(), "DELETE", null);
        saleRepository.delete(sale);
        log.info("Contrat supprime: {}", id);
    }

    @Transactional(readOnly = true)
    public List<Installment> installmentsOf(Long saleId) {
        return installmentRepository.findBySaleIdOrderByNumberAsc(saleId);
    }

    private void validateDownPayment(BigDecimal totalPrice, BigDecimal downPayment) {
        if (downPayment.compareTo(totalPrice) >= 0) {
            throw new BusinessRuleException("L'acompte doit etre inferieur au prix total");
        }
    }

    private String buildReference(CreditSale sale) {
        return "VC-%d-%05d".formatted(sale.getStartDate().getYear(), sale.getId());
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

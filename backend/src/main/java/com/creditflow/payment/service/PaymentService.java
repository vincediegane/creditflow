package com.creditflow.payment.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.util.Money;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.export.PaymentReceiptGenerator;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.payment.repository.PaymentSpecifications;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
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

/**
 * Enregistrement des versements. Chaque paiement met a jour, dans la meme
 * transaction : les echeances (FIFO), le contrat et donc le tableau de bord.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final CreditSaleRepository saleRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentAllocator paymentAllocator;
    private final PaymentMapper paymentMapper;
    private final PaymentReceiptGenerator receiptGenerator;

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> search(String search, PaymentMethod method, LocalDate from, LocalDate to,
                                                Long customerId, Pageable pageable) {
        Page<Payment> page = paymentRepository.findAll(
                Specs.combine(
                        PaymentSpecifications.matches(search),
                        PaymentSpecifications.hasMethod(method),
                        PaymentSpecifications.paidFrom(from),
                        PaymentSpecifications.paidTo(to),
                        PaymentSpecifications.forCustomer(customerId)),
                pageable);
        return PageResponse.of(page, paymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(Long id) {
        return paymentMapper.toResponse(paymentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Paiement", id)));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> findByCustomer(Long customerId) {
        return paymentRepository.findByCustomer(customerId).stream().map(paymentMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> findBySale(Long saleId) {
        return paymentRepository.findBySale(saleId).stream().map(paymentMapper::toResponse).toList();
    }

    /** Recu PDF remis au client : contenu et nom de fichier. */
    @Transactional(readOnly = true)
    public Receipt receipt(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Paiement", paymentId));
        List<Installment> installments =
                installmentRepository.findBySaleIdOrderByNumberAsc(payment.getSale().getId());

        return new Receipt(receiptGenerator.fileName(payment),
                receiptGenerator.generate(payment, installments));
    }

    public record Receipt(String fileName, byte[] content) {
    }

    @Transactional
    public PaymentResponse register(PaymentRequest request) {
        CreditSale sale = saleRepository.findDetailById(request.saleId())
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", request.saleId()));

        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw new BusinessRuleException("Ce contrat est annule, aucun versement ne peut etre enregistre");
        }
        if (sale.getStatus() == SaleStatus.COMPLETED) {
            throw new BusinessRuleException("Ce contrat est deja solde");
        }

        BigDecimal amount = Money.round(request.amount());
        if (!Money.isPositive(amount)) {
            throw new BusinessRuleException("Le montant du versement doit etre superieur a zero");
        }
        if (amount.compareTo(sale.getRemainingAmount()) > 0) {
            throw new BusinessRuleException(
                    "Le montant depasse le reste a payer (%s)".formatted(sale.getRemainingAmount().toPlainString()));
        }

        LocalDate paymentDate = request.paymentDate() == null ? LocalDate.now() : request.paymentDate();
        if (paymentDate.isAfter(LocalDate.now())) {
            throw new BusinessRuleException("La date de paiement ne peut pas etre dans le futur");
        }

        paymentAllocator.allocate(sale.getInstallments(), amount, paymentDate);

        Payment payment = Payment.builder()
                .sale(sale)
                .amount(amount)
                .paymentDate(paymentDate)
                .method(request.method())
                .reference(blankToNull(request.reference()))
                .notes(request.notes())
                .build();
        Payment saved = paymentRepository.save(payment);

        recalculate(sale);
        saleRepository.save(sale);

        log.info("Versement de {} enregistre sur le contrat {} (reste {})",
                amount, sale.getReference(), sale.getRemainingAmount());

        return paymentMapper.toResponse(saved);
    }

    /**
     * Annule un versement et recalcule integralement l'echeancier du contrat
     * a partir des versements restants.
     */
    @Transactional
    public void delete(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Paiement", id));
        CreditSale sale = saleRepository.findDetailById(payment.getSale().getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", payment.getSale().getId()));

        paymentRepository.delete(payment);
        paymentRepository.flush();

        List<Installment> installments = sale.getInstallments();
        paymentAllocator.reset(installments);
        paymentRepository.findBySaleIdOrderByPaymentDateAscIdAsc(sale.getId())
                .forEach(p -> paymentAllocator.allocate(installments, p.getAmount(), p.getPaymentDate()));

        recalculate(sale);
        saleRepository.save(sale);
        log.info("Versement {} annule, contrat {} recalcule", id, sale.getReference());
    }

    private void recalculate(CreditSale sale) {
        BigDecimal paid = sale.getInstallments().stream()
                .map(Installment::getAmountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sale.setAmountPaid(Money.round(paid));
        sale.setRemainingAmount(Money.max(sale.getFinancedAmount().subtract(paid), BigDecimal.ZERO));

        if (sale.getStatus() != SaleStatus.CANCELLED) {
            sale.setStatus(Money.isZeroOrLess(sale.getRemainingAmount()) ? SaleStatus.COMPLETED : SaleStatus.ACTIVE);
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

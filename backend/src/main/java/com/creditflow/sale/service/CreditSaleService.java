package com.creditflow.sale.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.storage.FileStorageService;
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
import com.creditflow.sale.domain.SaleAttachment;
import com.creditflow.sale.domain.SaleAttachmentType;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleAttachmentResponse;
import com.creditflow.sale.dto.SaleDetailResponse;
import com.creditflow.sale.dto.SalePreviewRequest;
import com.creditflow.sale.dto.SalePreviewResponse;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.export.DeliveryNoteGenerator;
import com.creditflow.sale.export.InvoiceGenerator;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.repository.SaleAttachmentRepository;
import com.creditflow.sale.repository.SaleSpecifications;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

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
    private final SaleAttachmentRepository saleAttachmentRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final InstallmentScheduleGenerator scheduleGenerator;
    private final SaleMapper saleMapper;
    private final PaymentMapper paymentMapper;
    private final AuditLogService auditLogService;
    private final PenaltySettingsService penaltySettingsService;
    private final FileStorageService fileStorageService;
    private final CurrentShopContext currentShopContext;
    private final ShopRepository shopRepository;
    private final InvoiceGenerator invoiceGenerator;
    private final DeliveryNoteGenerator deliveryNoteGenerator;

    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> search(String search, SaleStatus status, Long customerId, Pageable pageable) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        Page<CreditSale> page = saleRepository.findAll(
                Specs.combine(
                        SaleSpecifications.matches(search),
                        SaleSpecifications.hasStatus(status),
                        SaleSpecifications.forCustomer(customerId),
                        SaleSpecifications.inShops(currentShopContext.accessibleShopIds())),
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
                paymentRepository.findBySale(id).stream().map(paymentMapper::toResponse).toList(),
                saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id).stream()
                        .map(saleMapper::toResponse).toList());
    }

    public record Invoice(String fileName, byte[] content) {
    }

    /** Facture PDF remise au client : contenu et nom de fichier. */
    @Transactional(readOnly = true)
    public Invoice invoice(Long id) {
        CreditSale sale = getEntity(id);
        List<Installment> installments = sale.getInstallments();
        return new Invoice(invoiceGenerator.fileName(sale), invoiceGenerator.generate(sale, installments));
    }

    public record DeliveryNote(String fileName, byte[] content) {
    }

    /** Bon de livraison PDF a remettre au client : contenu et nom de fichier. */
    @Transactional(readOnly = true)
    public DeliveryNote deliveryNote(Long id) {
        CreditSale sale = getEntity(id);
        return new DeliveryNote(deliveryNoteGenerator.fileName(sale), deliveryNoteGenerator.generate(sale));
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
        CreditSale sale = saleRepository.findDetailById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", id));
        currentShopContext.assertAccessible(sale.getShop().getId());
        return sale;
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
        Long targetShopId = currentShopContext.shopIdForCreation();
        Customer customer = customerService.getEntity(request.customerId());
        Product product = productService.getEntity(request.productId());

        if (!customer.getShop().getId().equals(targetShopId)) {
            throw new BusinessRuleException(
                    "Le client selectionne n'appartient pas a la boutique cible de cette vente");
        }
        if (!product.getShop().getId().equals(targetShopId)) {
            throw new BusinessRuleException(
                    "Le produit selectionne n'appartient pas a la boutique cible de cette vente");
        }

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
                .shop(shopRepository.getReferenceById(targetShopId))
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
        saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id)
                .forEach(attachment -> fileStorageService.deleteByPublicUrl(attachment.getFileUrl()));
        auditLogService.record("CREDIT_SALE", id, sale.getReference(), "DELETE", null);
        saleRepository.delete(sale);
        log.info("Contrat supprime: {}", id);
    }

    @Transactional
    public SaleAttachmentResponse uploadAttachment(Long saleId, SaleAttachmentType type, MultipartFile file) {
        CreditSale sale = getEntity(saleId);
        if (type == SaleAttachmentType.SIGNATURE) {
            saleAttachmentRepository.findBySaleIdAndType(saleId, SaleAttachmentType.SIGNATURE)
                    .forEach(existing -> {
                        fileStorageService.deleteByPublicUrl(existing.getFileUrl());
                        saleAttachmentRepository.delete(existing);
                    });
        }

        String fileUrl = fileStorageService.store(file, "sales/" + saleId);
        SaleAttachment attachment = saleAttachmentRepository.save(SaleAttachment.builder()
                .sale(sale)
                .type(type)
                .fileUrl(fileUrl)
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .build());

        auditLogService.record("CREDIT_SALE", saleId, sale.getReference(), "ATTACHMENT_ADD", type.name());
        return saleMapper.toResponse(attachment);
    }

    @Transactional
    public void deleteAttachment(Long saleId, Long attachmentId) {
        CreditSale sale = getEntity(saleId);
        SaleAttachment attachment = saleAttachmentRepository.findByIdAndSaleId(attachmentId, saleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Piece jointe", attachmentId));

        fileStorageService.deleteByPublicUrl(attachment.getFileUrl());
        saleAttachmentRepository.delete(attachment);
        auditLogService.record("CREDIT_SALE", saleId, sale.getReference(), "ATTACHMENT_REMOVE",
                attachment.getType().name());
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

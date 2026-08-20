package com.creditflow.sale.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.storage.FileStorageService;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.payment.mapper.PaymentMapper;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.domain.StockMovement;
import com.creditflow.product.domain.StockMovementType;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.product.repository.StockMovementRepository;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleAttachment;
import com.creditflow.sale.domain.SaleAttachmentType;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.repository.SaleAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreditSaleServiceTest {

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private SaleAttachmentRepository saleAttachmentRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @Mock
    private InstallmentScheduleGenerator scheduleGenerator;

    @Mock
    private SaleMapper saleMapper;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PenaltySettingsService penaltySettingsService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private CreditSaleService creditSaleService;

    private CreditSale sale;

    @BeforeEach
    void setUp() {
        when(penaltySettingsService.current()).thenReturn(PenaltySettings.builder()
                .id(1L).enabled(false).rateType(PenaltyRateType.FIXED)
                .rate(java.math.BigDecimal.ZERO).period(PenaltyPeriod.DAY).build());
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").build();
        Product product = Product.builder().id(1L).name("iPhone 13").build();
        sale = CreditSale.builder()
                .id(1L).reference("VC-2026-00001").customer(customer).product(product)
                .totalPrice(new BigDecimal("150000")).downPayment(BigDecimal.ZERO)
                .financedAmount(new BigDecimal("150000")).installmentCount(3)
                .monthlyAmount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("150000")).startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(3)).status(SaleStatus.ACTIVE)
                .installments(new ArrayList<>())
                .build();
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.of(sale));
    }

    @Test
    @DisplayName("annule un contrat et journalise l'action")
    void cancelsSaleAndRecordsAuditEntry() {
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(i -> i.getArgument(0));

        creditSaleService.cancel(1L);

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.CANCELLED);
        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "CANCEL", null);
    }

    @Test
    @DisplayName("refuse d'annuler un contrat solde")
    void rejectsCancellingCompletedSale() {
        sale.setStatus(SaleStatus.COMPLETED);

        assertThatThrownBy(() -> creditSaleService.cancel(1L)).isInstanceOf(BusinessRuleException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("supprime un contrat sans paiement, purge les fichiers et journalise l'action")
    void deletesSaleAndRecordsAuditEntry() {
        when(paymentRepository.findBySale(1L)).thenReturn(List.of());
        SaleAttachment attachment = SaleAttachment.builder()
                .id(10L).sale(sale).type(SaleAttachmentType.ID_DOCUMENT).fileUrl("/uploads/sales/1/a.png").build();
        when(saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(attachment));

        creditSaleService.delete(1L);

        verify(fileStorageService).deleteByPublicUrl("/uploads/sales/1/a.png");
        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "DELETE", null);
        verify(saleRepository).delete(sale);
    }

    @Test
    @DisplayName("refuse de supprimer un contrat avec des paiements")
    void rejectsDeletingSaleWithPayments() {
        when(paymentRepository.findBySale(1L)).thenReturn(List.of(org.mockito.Mockito.mock(
                com.creditflow.payment.domain.Payment.class)));

        assertThatThrownBy(() -> creditSaleService.delete(1L)).isInstanceOf(BusinessRuleException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any());
        verify(saleRepository, never()).delete(any(CreditSale.class));
    }

    @Test
    @DisplayName("reprend les champs garant du CreateSaleRequest vers l'entite sauvegardee")
    void createCapturesGuarantorFields() {
        stubCreationDependencies();

        CreateSaleRequest request = new CreateSaleRequest(1L, 1L, new BigDecimal("150000"), BigDecimal.ZERO,
                null, null, 3, LocalDate.now(), null,
                "Moussa Kane", "770001122", "Dakar, Sicap Liberte", "1234567890123");

        creditSaleService.create(request);

        CreditSale saved = capturedSale();
        assertThat(saved.getGuarantorFullName()).isEqualTo("Moussa Kane");
        assertThat(saved.getGuarantorPhone()).isEqualTo("770001122");
        assertThat(saved.getGuarantorAddress()).isEqualTo("Dakar, Sicap Liberte");
        assertThat(saved.getGuarantorCniNumber()).isEqualTo("1234567890123");
    }

    @Test
    @DisplayName("cree un contrat sans garant (non-regression)")
    void createWithoutGuarantorLeavesFieldsNull() {
        stubCreationDependencies();

        CreateSaleRequest request = new CreateSaleRequest(1L, 1L, new BigDecimal("150000"), BigDecimal.ZERO,
                null, null, 3, LocalDate.now(), null,
                null, null, null, null);

        creditSaleService.create(request);

        CreditSale saved = capturedSale();
        assertThat(saved.getGuarantorFullName()).isNull();
        assertThat(saved.getGuarantorPhone()).isNull();
        assertThat(saved.getGuarantorAddress()).isNull();
        assertThat(saved.getGuarantorCniNumber()).isNull();
    }

    private void stubCreationDependencies() {
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").build();
        Product product = Product.builder().id(1L).name("iPhone 13").stock(0).build();
        when(customerService.getEntity(1L)).thenReturn(customer);
        when(productService.getEntity(1L)).thenReturn(product);
        when(scheduleGenerator.interestAmount(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(scheduleGenerator.generate(any(), anyInt(), any())).thenReturn(
                new InstallmentScheduleGenerator.Schedule(
                        new BigDecimal("50000"), LocalDate.now().plusMonths(3), List.of()));
        when(saleRepository.saveAndFlush(any(CreditSale.class))).thenAnswer(i -> {
            CreditSale s = i.getArgument(0);
            s.setId(2L);
            return s;
        });
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CreditSale capturedSale() {
        ArgumentCaptor<CreditSale> captor = ArgumentCaptor.forClass(CreditSale.class);
        verify(saleRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("trace un mouvement de stock OUT lors de la creation d'une vente")
    void create_recordsOutStockMovementForSoldProduct() {
        ProductRepository productRepository = org.mockito.Mockito.mock(ProductRepository.class);
        ProductMapper productMapper = org.mockito.Mockito.mock(ProductMapper.class);
        StockMovementRepository stockMovementRepository = org.mockito.Mockito.mock(StockMovementRepository.class);
        ProductService realProductService =
                new ProductService(productRepository, productMapper, auditLogService, stockMovementRepository);

        Product product = Product.builder().id(1L).name("iPhone 13").stock(3).status(ProductStatus.ACTIVE).build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").build();
        when(customerService.getEntity(1L)).thenReturn(customer);

        when(scheduleGenerator.interestAmount(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        InstallmentScheduleGenerator.Schedule schedule = new InstallmentScheduleGenerator.Schedule(
                new BigDecimal("50000"), LocalDate.now().plusMonths(3),
                List.of(new InstallmentScheduleGenerator.ScheduleLine(1, LocalDate.now(), new BigDecimal("150000"))));
        when(scheduleGenerator.generate(any(), anyInt(), any())).thenReturn(schedule);

        when(saleRepository.saveAndFlush(any(CreditSale.class))).thenAnswer(i -> {
            CreditSale s = i.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(saleRepository.save(any(CreditSale.class))).thenAnswer(i -> i.getArgument(0));

        CreditSaleService service = new CreditSaleService(saleRepository, installmentRepository, paymentRepository,
                saleAttachmentRepository, customerService, realProductService, scheduleGenerator, saleMapper,
                paymentMapper, auditLogService, penaltySettingsService, fileStorageService);

        CreateSaleRequest request = new CreateSaleRequest(1L, 1L, new BigDecimal("150000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 3, LocalDate.now(), null, null, null, null, null);

        service.create(request);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(StockMovementType.OUT);
        assertThat(captor.getValue().getProduct().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("accumule les pieces d'identite attachees")
    void accumulatesIdDocumentAttachments() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cni.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(fileStorageService.store(file, "sales/1")).thenReturn("/uploads/sales/1/cni.png");
        when(saleAttachmentRepository.save(any(SaleAttachment.class))).thenAnswer(i -> {
            SaleAttachment saved = i.getArgument(0);
            saved.setId(5L);
            return saved;
        });

        creditSaleService.uploadAttachment(1L, SaleAttachmentType.ID_DOCUMENT, file);

        verify(saleAttachmentRepository, never()).delete(any(SaleAttachment.class));
        verify(saleAttachmentRepository).save(any(SaleAttachment.class));
        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "ATTACHMENT_ADD", "ID_DOCUMENT");
    }

    @Test
    @DisplayName("remplace la signature existante")
    void replacesExistingSignature() {
        SaleAttachment existing = SaleAttachment.builder()
                .id(7L).sale(sale).type(SaleAttachmentType.SIGNATURE).fileUrl("/uploads/sales/1/old.png").build();
        when(saleAttachmentRepository.findBySaleIdAndType(1L, SaleAttachmentType.SIGNATURE))
                .thenReturn(List.of(existing));
        MockMultipartFile file = new MockMultipartFile(
                "file", "signature.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(fileStorageService.store(file, "sales/1")).thenReturn("/uploads/sales/1/new.png");
        when(saleAttachmentRepository.save(any(SaleAttachment.class))).thenAnswer(i -> i.getArgument(0));

        creditSaleService.uploadAttachment(1L, SaleAttachmentType.SIGNATURE, file);

        verify(fileStorageService).deleteByPublicUrl("/uploads/sales/1/old.png");
        verify(saleAttachmentRepository).delete(existing);
        verify(saleAttachmentRepository).save(any(SaleAttachment.class));
    }

    @Test
    @DisplayName("supprime une piece jointe existante")
    void deletesAttachment() {
        SaleAttachment attachment = SaleAttachment.builder()
                .id(3L).sale(sale).type(SaleAttachmentType.OTHER).fileUrl("/uploads/sales/1/doc.png").build();
        when(saleAttachmentRepository.findByIdAndSaleId(3L, 1L)).thenReturn(Optional.of(attachment));

        creditSaleService.deleteAttachment(1L, 3L);

        verify(fileStorageService).deleteByPublicUrl("/uploads/sales/1/doc.png");
        verify(saleAttachmentRepository).delete(attachment);
        verify(auditLogService).record("CREDIT_SALE", 1L, "VC-2026-00001", "ATTACHMENT_REMOVE", "OTHER");
    }

    @Test
    @DisplayName("refuse de supprimer une piece jointe n'appartenant pas au contrat")
    void rejectsDeletingAttachmentFromAnotherSale() {
        when(saleAttachmentRepository.findByIdAndSaleId(3L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> creditSaleService.deleteAttachment(1L, 3L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(saleAttachmentRepository, never()).delete(any(SaleAttachment.class));
    }
}

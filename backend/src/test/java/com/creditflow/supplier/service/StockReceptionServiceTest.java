package com.creditflow.supplier.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.product.repository.StockMovementRepository;
import com.creditflow.product.service.ProductService;
import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.StockReceptionRequest;
import com.creditflow.supplier.dto.StockReceptionRequest.StockReceptionLineRequest;
import com.creditflow.supplier.mapper.StockReceptionMapper;
import com.creditflow.supplier.repository.StockReceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockReceptionServiceTest {

    @Mock
    private StockReceptionRepository stockReceptionRepository;

    @Mock
    private StockReceptionMapper stockReceptionMapper;

    @Mock
    private SupplierService supplierService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StockMovementRepository stockMovementRepository;

    private ProductService productService;

    private StockReceptionService stockReceptionService;

    private Supplier supplier;
    private Product phone;
    private Product laptop;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, productMapper, auditLogService, stockMovementRepository);
        stockReceptionService = new StockReceptionService(
                stockReceptionRepository, stockReceptionMapper, supplierService, productService);

        supplier = Supplier.builder().id(1L).name("Grossiste Sahel").active(true).build();
        phone = Product.builder().id(1L).name("iPhone 13").stock(5).status(ProductStatus.ACTIVE).build();
        laptop = Product.builder().id(2L).name("MacBook Air").stock(0).status(ProductStatus.OUT_OF_STOCK).build();

        when(supplierService.getEntity(1L)).thenReturn(supplier);
        when(productRepository.findById(1L)).thenReturn(Optional.of(phone));
        when(productRepository.findById(2L)).thenReturn(Optional.of(laptop));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        when(stockReceptionRepository.save(any())).thenAnswer(i -> {
            var reception = i.getArgument(0, com.creditflow.supplier.domain.StockReception.class);
            reception.setId(10L);
            return reception;
        });
    }

    @Test
    @DisplayName("cree une reception avec plusieurs lignes")
    void receive_withMultipleLines_createsReceptionAndLines() {
        StockReceptionRequest request = new StockReceptionRequest(1L, LocalDate.now(), null, List.of(
                new StockReceptionLineRequest(1L, 3),
                new StockReceptionLineRequest(2L, 2)));

        stockReceptionService.receive(request);

        var captor = org.mockito.ArgumentCaptor.forClass(com.creditflow.supplier.domain.StockReception.class);
        verify(stockReceptionRepository).save(captor.capture());
        assertThat(captor.getValue().getLines()).hasSize(2);
        assertThat(captor.getValue().getSupplier()).isEqualTo(supplier);
    }

    @Test
    @DisplayName("incremente immediatement le stock des produits receptionnes")
    void receive_increasesProductStockImmediately() {
        StockReceptionRequest request = new StockReceptionRequest(1L, LocalDate.now(), null, List.of(
                new StockReceptionLineRequest(1L, 3)));

        stockReceptionService.receive(request);

        assertThat(phone.getStock()).isEqualTo(8);
    }

    @Test
    @DisplayName("annule tout si un produit de la reception est introuvable")
    void receive_withUnknownProduct_rollsBackEntirely() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
        StockReceptionRequest request = new StockReceptionRequest(1L, LocalDate.now(), null, List.of(
                new StockReceptionLineRequest(1L, 3),
                new StockReceptionLineRequest(99L, 5)));

        assertThatThrownBy(() -> stockReceptionService.receive(request))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(phone.getStock()).isEqualTo(5);
        verify(stockReceptionRepository, never()).save(any());
        verify(productRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
    }
}

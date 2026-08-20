package com.creditflow.product.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.domain.StockMovement;
import com.creditflow.product.domain.StockMovementType;
import com.creditflow.product.domain.StockSourceType;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.product.repository.StockMovementRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private ProductService productService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .id(1L).name("iPhone 13").category("Telephone")
                .cashPrice(new BigDecimal("450000")).creditPrice(new BigDecimal("560000"))
                .stock(3).status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("journalise la modification quand un prix change")
    void recordsAuditEntryWhenPriceChanges() {
        ProductRequest request = new ProductRequest("iPhone 13", "Telephone",
                new BigDecimal("450000"), new BigDecimal("580000"), 3, null, ProductStatus.ACTIVE);

        productService.update(1L, request);

        verify(auditLogService).record(eq("PRODUCT"), eq(1L), eq("iPhone 13"), eq("PRICE_UPDATE"), anyString());
    }

    @Test
    @DisplayName("ne journalise rien quand aucun prix ne change")
    void doesNotRecordAuditEntryWhenPricesUnchanged() {
        ProductRequest request = new ProductRequest("iPhone 13", "Telephone",
                new BigDecimal("450000"), new BigDecimal("560000"), 3, null, ProductStatus.ACTIVE);

        productService.update(1L, request);

        verify(auditLogService, never()).record(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("reactive un produit en rupture lors d'une reception")
    void increaseStock_reactivatesOutOfStockProduct() {
        Product outOfStock = Product.builder()
                .id(2L).name("MacBook Air").category("Ordinateur")
                .cashPrice(BigDecimal.TEN).creditPrice(BigDecimal.TEN)
                .stock(0).status(ProductStatus.OUT_OF_STOCK)
                .build();

        productService.increaseStock(outOfStock, 5, StockSourceType.PURCHASE_RECEPTION, 10L);

        assertThat(outOfStock.getStock()).isEqualTo(5);
        assertThat(outOfStock.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("trace un mouvement IN avec la source de reception")
    void increaseStock_recordsInMovement() {
        productService.increaseStock(product, 5, StockSourceType.PURCHASE_RECEPTION, 10L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        StockMovement movement = captor.getValue();
        assertThat(movement.getType()).isEqualTo(StockMovementType.IN);
        assertThat(movement.getQuantity()).isEqualTo(5);
        assertThat(movement.getSourceType()).isEqualTo(StockSourceType.PURCHASE_RECEPTION);
        assertThat(movement.getSourceId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("trace un mouvement OUT avec la quantite reellement decrementee")
    void decreaseStock_recordsOutMovementWithActualDecrease() {
        productService.decreaseStock(product, 10);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        StockMovement movement = captor.getValue();
        assertThat(movement.getType()).isEqualTo(StockMovementType.OUT);
        assertThat(movement.getQuantity()).isEqualTo(3);
        assertThat(movement.getSourceType()).isEqualTo(StockSourceType.SALE);
        assertThat(movement.getSourceId()).isNull();
        assertThat(product.getStock()).isEqualTo(0);
    }

    @Test
    @DisplayName("renvoie les mouvements du plus recent au plus ancien")
    void stockMovements_returnsMovementsOrderedMostRecentFirst() {
        StockMovement in = StockMovement.builder()
                .id(1L).product(product).type(StockMovementType.IN).quantity(5)
                .sourceType(StockSourceType.PURCHASE_RECEPTION).sourceId(10L)
                .occurredAt(LocalDateTime.now().minusHours(1)).createdBy("admin").build();
        StockMovement out = StockMovement.builder()
                .id(2L).product(product).type(StockMovementType.OUT).quantity(1)
                .sourceType(StockSourceType.SALE).sourceId(null)
                .occurredAt(LocalDateTime.now()).createdBy("admin").build();
        when(stockMovementRepository.findByProductIdOrderByOccurredAtDescIdDesc(1L))
                .thenReturn(List.of(out, in));

        var result = productService.stockMovements(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo(StockMovementType.OUT);
        assertThat(result.get(1).type()).isEqualTo(StockMovementType.IN);
    }
}

package com.creditflow.product.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

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
}

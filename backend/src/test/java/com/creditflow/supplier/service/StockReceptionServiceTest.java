package com.creditflow.supplier.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.product.repository.StockMovementRepository;
import com.creditflow.product.service.ProductService;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import com.creditflow.supplier.domain.StockReception;
import com.creditflow.supplier.domain.StockReceptionLine;
import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.StockReceptionRequest;
import com.creditflow.supplier.dto.StockReceptionRequest.StockReceptionLineRequest;
import com.creditflow.supplier.mapper.StockReceptionMapper;
import com.creditflow.supplier.repository.StockReceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private CurrentShopContext currentShopContext;

    @Mock
    private ShopRepository shopRepository;

    private ProductService productService;

    private StockReceptionService stockReceptionService;

    private Supplier supplier;
    private Product phone;
    private Product laptop;
    private Shop shop;
    private Shop otherShop;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, productMapper, auditLogService,
                stockMovementRepository, currentShopContext, shopRepository);
        stockReceptionService = new StockReceptionService(stockReceptionRepository, stockReceptionMapper,
                supplierService, productService, currentShopContext, shopRepository);

        shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        otherShop = Shop.builder().id(2L).name("Autre boutique").active(true).build();
        supplier = Supplier.builder().id(1L).name("Grossiste Sahel").active(true).build();
        phone = Product.builder().id(1L).name("iPhone 13").stock(5).status(ProductStatus.ACTIVE).shop(shop).build();
        laptop = Product.builder().id(2L).name("MacBook Air").stock(0).status(ProductStatus.OUT_OF_STOCK)
                .shop(shop).build();

        when(currentShopContext.accessibleShopIds()).thenReturn(List.of(1L));
        when(currentShopContext.shopIdForCreation()).thenReturn(1L);
        when(supplierService.getEntity(1L)).thenReturn(supplier);
        when(shopRepository.getReferenceById(1L)).thenReturn(shop);
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
        assertThat(captor.getValue().getShop()).isEqualTo(shop);
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

    @Test
    @DisplayName("la liste des receptions est restreinte aux boutiques accessibles")
    void search_filtersOnAccessibleShops() {
        when(stockReceptionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        stockReceptionService.search(null, PageRequest.of(0, 20));

        verify(currentShopContext).accessibleShopIds();
        ArgumentCaptor<Specification<StockReception>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(stockReceptionRepository).findAll(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("une reception d'une autre boutique reste invisible en acces direct")
    void getEntity_rejectsReceptionFromAnotherShop() {
        Product foreignProduct = Product.builder().id(9L).name("Tecno Spark").stock(2)
                .status(ProductStatus.ACTIVE).shop(otherShop).build();
        StockReception reception = StockReception.builder().id(10L).supplier(supplier)
                .receivedAt(LocalDate.now()).build();
        reception.addLine(StockReceptionLine.builder().product(foreignProduct).quantity(3).build());
        when(stockReceptionRepository.findById(10L)).thenReturn(Optional.of(reception));
        doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(2L);

        assertThatThrownBy(() -> stockReceptionService.findById(10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("refuse de receptionner un produit d'une autre boutique")
    void receive_rejectsProductFromAnotherShop() {
        Product foreignProduct = Product.builder().id(9L).name("Tecno Spark").stock(2)
                .status(ProductStatus.ACTIVE).shop(otherShop).build();
        when(productRepository.findById(9L)).thenReturn(Optional.of(foreignProduct));
        StockReceptionRequest request = new StockReceptionRequest(1L, LocalDate.now(), null, List.of(
                new StockReceptionLineRequest(9L, 3)));

        assertThatThrownBy(() -> stockReceptionService.receive(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("boutique cible");

        verify(stockReceptionRepository, never()).save(any());
    }
}

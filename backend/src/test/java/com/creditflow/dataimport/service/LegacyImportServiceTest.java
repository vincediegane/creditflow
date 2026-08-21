package com.creditflow.dataimport.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyImportServiceTest {

    @Mock
    private LegacyImportParser parser;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CreditSaleService creditSaleService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private CurrentShopContext currentShopContext;

    @Mock
    private ShopRepository shopRepository;

    private LegacyImportService legacyImportService;

    private Shop shop;
    private MockMultipartFile file;

    @BeforeEach
    void setUp() {
        legacyImportService = new LegacyImportService(parser, customerRepository, productRepository,
                creditSaleService, paymentService, currentShopContext, shopRepository);
        shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        file = new MockMultipartFile("file", "reprise.csv", "text/csv", new byte[0]);
    }

    private LegacyRow row(String phone, String product) {
        return new LegacyRow(2, "Khady", "Sy", phone, "Ouakam", "5551", "Restauratrice",
                product, "Telephone", new BigDecimal("98000"), new BigDecimal("18000"), 4,
                LocalDate.now(), BigDecimal.ZERO);
    }

    @Test
    @DisplayName("un import reussi assigne la boutique resolue aux nouveaux clients/produits/ventes crees")
    void importAssignsResolvedShopToNewCustomerAndProduct() {
        LegacyRow row = row("771110001", "Tecno Spark");
        when(currentShopContext.shopIdForCreation()).thenReturn(1L);
        when(shopRepository.getReferenceById(1L)).thenReturn(shop);
        when(parser.parse(file)).thenReturn(new LegacyImportParser.ParseResult(List.of(row), List.of(), 1));
        when(customerRepository.existsByPhone("771110001")).thenReturn(false);
        when(customerRepository.findByPhone("771110001")).thenReturn(Optional.empty());
        when(productRepository.findFirstByNameIgnoreCase("Tecno Spark")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(i -> {
            Customer c = i.getArgument(0);
            c.setId(10L);
            return c;
        });
        when(productRepository.save(any(Product.class))).thenAnswer(i -> {
            Product p = i.getArgument(0);
            p.setId(20L);
            return p;
        });
        when(creditSaleService.create(any(CreateSaleRequest.class))).thenReturn(
                mock(SaleResponse.class));

        legacyImportService.importLegacySales(file, false);

        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(customerCaptor.capture());
        assertThat(customerCaptor.getValue().getShop()).isEqualTo(shop);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertThat(productCaptor.getValue().getShop()).isEqualTo(shop);
    }

    @Test
    @DisplayName("rejette clairement un telephone deja connu dans une autre boutique")
    void rejectsPhoneAlreadyUsedInAnotherShop() {
        LegacyRow row = row("771110002", "HP 250");
        Shop otherShop = Shop.builder().id(2L).name("Autre boutique").active(true).build();
        Customer existing = Customer.builder().id(5L).firstName("Modou").lastName("Gaye")
                .phone("771110002").shop(otherShop).build();

        when(currentShopContext.shopIdForCreation()).thenReturn(1L);
        when(parser.parse(file)).thenReturn(new LegacyImportParser.ParseResult(List.of(row), List.of(), 1));
        when(customerRepository.existsByPhone("771110002")).thenReturn(true);
        when(customerRepository.findByPhone("771110002")).thenReturn(Optional.of(existing));
        when(productRepository.findFirstByNameIgnoreCase("HP 250")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> legacyImportService.importLegacySales(file, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("771110002")
                .hasMessageContaining("autre boutique");

        verify(creditSaleService, never()).create(any(CreateSaleRequest.class));
    }

    @Test
    @DisplayName("rejette l'import (422) si l'utilisateur est multi-boutiques sans en-tete X-Shop-Id")
    void rejectsWhenMultiShopUserHasNoHeader() {
        when(currentShopContext.shopIdForCreation()).thenThrow(new BusinessRuleException(
                "Vous etes rattache a plusieurs boutiques : precisez la boutique cible via l'en-tete X-Shop-Id "
                        + "avant de creer cette ressource."));

        assertThatThrownBy(() -> legacyImportService.importLegacySales(file, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("X-Shop-Id");

        verify(parser, never()).parse(any());
    }
}

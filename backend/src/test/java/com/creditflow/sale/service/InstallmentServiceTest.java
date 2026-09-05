package com.creditflow.sale.service;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.customer.domain.Customer;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.product.domain.Product;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.shop.domain.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstallmentServiceTest {

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private CreditSaleRepository saleRepository;

    @Mock
    private SaleMapper saleMapper;

    @Mock
    private PenaltySettingsService penaltySettingsService;

    @Mock
    private CurrentShopContext currentShopContext;

    @InjectMocks
    private InstallmentService installmentService;

    private Shop shop;
    private CreditSale sale;
    private Installment installment;

    @BeforeEach
    void setUp() {
        when(penaltySettingsService.current()).thenReturn(PenaltySettings.builder()
                .id(1L).enabled(false).rateType(PenaltyRateType.FIXED)
                .rate(BigDecimal.ZERO).period(PenaltyPeriod.DAY).build());
        shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        Customer customer = Customer.builder().id(1L).firstName("Amadou").lastName("Diallo").shop(shop).build();
        Product product = Product.builder().id(1L).name("iPhone 13").shop(shop).build();
        sale = CreditSale.builder()
                .id(1L).reference("VC-2026-00001").customer(customer).product(product).shop(shop)
                .totalPrice(new BigDecimal("150000")).downPayment(BigDecimal.ZERO)
                .financedAmount(new BigDecimal("150000")).installmentCount(3)
                .monthlyAmount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("150000")).startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(3)).status(SaleStatus.ACTIVE)
                .build();
        installment = Installment.builder()
                .id(1L).sale(sale).number(1).dueDate(LocalDate.now())
                .amount(new BigDecimal("50000")).amountPaid(BigDecimal.ZERO).penaltyPaid(BigDecimal.ZERO)
                .status(InstallmentStatus.PENDING).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("search combine le filtre de l'organisation courante")
    void searchCombinesCurrentOrganizationFilter() {
        when(currentShopContext.accessibleShopIds()).thenReturn(List.of(1L));
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
        when(installmentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        installmentService.search(null, null, null, null, false, Pageable.unpaged());

        verify(currentShopContext).currentOrganizationId();
    }

    @Test
    @DisplayName("upcoming transmet les boutiques accessibles et l'organisation courante")
    void upcomingTransmitsShopIdsAndOrganizationId() {
        List<Long> shopIds = List.of(1L, 2L);
        when(currentShopContext.accessibleShopIds()).thenReturn(shopIds);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
        when(installmentRepository.findUpcomingForShops(any(), any(), eq(shopIds), eq(100L)))
                .thenReturn(List.of(installment));

        installmentService.upcoming(30);

        verify(installmentRepository).findUpcomingForShops(any(), any(), eq(shopIds), eq(100L));
    }

    @Test
    @DisplayName("upcomingForShops transmet le shopIds fourni et resout l'organisation en interne")
    void upcomingForShopsTransmitsProvidedShopIdsAndResolvedOrganizationId() {
        List<Long> shopIds = List.of(2L);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
        when(installmentRepository.findUpcomingForShops(any(), any(), eq(shopIds), eq(100L)))
                .thenReturn(List.of(installment));

        installmentService.upcomingForShops(15, shopIds);

        verify(installmentRepository).findUpcomingForShops(any(), any(), eq(shopIds), eq(100L));
    }

    @Test
    @DisplayName("late transmet les boutiques accessibles et l'organisation courante")
    void lateTransmitsShopIdsAndOrganizationId() {
        List<Long> shopIds = List.of(1L);
        when(currentShopContext.accessibleShopIds()).thenReturn(shopIds);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
        when(installmentRepository.findLateForShops(any(), eq(shopIds), eq(100L)))
                .thenReturn(List.of(installment));

        installmentService.late();

        verify(installmentRepository).findLateForShops(any(), eq(shopIds), eq(100L));
    }

    @Test
    @DisplayName("bySale charge les echeances du contrat accessible")
    void bySaleReturnsInstallmentsForAccessibleSale() {
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.of(sale));
        when(installmentRepository.findBySaleIdOrderByNumberAsc(1L)).thenReturn(List.of(installment));

        installmentService.bySale(1L);

        verify(currentShopContext).assertAccessible(1L);
        verify(installmentRepository).findBySaleIdOrderByNumberAsc(1L);
    }

    @Test
    @DisplayName("bySale leve une exception si le contrat n'existe pas")
    void bySaleThrowsWhenSaleMissing() {
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> installmentService.bySale(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("bySale refuse l'acces a un contrat d'une autre boutique")
    void bySaleRejectsSaleFromAnotherShop() {
        when(saleRepository.findDetailById(1L)).thenReturn(Optional.of(sale));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(1L);

        assertThatThrownBy(() -> installmentService.bySale(1L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(installmentRepository, org.mockito.Mockito.never()).findBySaleIdOrderByNumberAsc(any());
    }
}

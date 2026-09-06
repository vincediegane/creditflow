package com.creditflow.bootstrap;

import com.creditflow.common.security.CurrentUser;
import com.creditflow.config.AppProperties;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoDataSeederTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @Mock
    private CreditSaleService creditSaleService;

    @Mock
    private PaymentService paymentService;

    private final AppProperties properties = new AppProperties();

    private ApplicationRunner runner;

    @BeforeEach
    void setUp() {
        properties.getAdmin().setUsername("admin");
        runner = new DemoDataSeeder(properties, customerRepository, shopRepository, organizationRepository,
                customerService, productService, creditSaleService, paymentService).seedDemoData();
        when(customerRepository.count()).thenReturn(0L);
        when(shopRepository.findAllByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(Shop.builder().id(1L).name("Boutique principale").active(true).build()));
        when(organizationRepository.findFirstByOrderByIdAsc())
                .thenReturn(java.util.Optional.of(Organization.builder().id(1L).name("Organisation par defaut").build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("le seeding s'execute sous l'identite technique de l'administrateur")
    void seedingRunsUnderTechnicalAdminIdentity() {
        AtomicReference<String> identity = new AtomicReference<>();
        when(customerService.create(any(CustomerRequest.class))).thenAnswer(invocation -> {
            identity.set(CurrentUser.username());
            throw new IllegalStateException("arret volontaire du seeding");
        });

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        assertThat(identity.get()).isEqualTo("admin");
        assertThat(CurrentUser.username()).isNull();
    }

    @Test
    @DisplayName("le seeding est ignore sur une installation multi-boutiques")
    void seedingSkippedWhenSeveralShopsExist() throws Exception {
        when(shopRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(
                Shop.builder().id(1L).name("Boutique 1").active(true).build(),
                Shop.builder().id(2L).name("Boutique 2").active(true).build()));

        runner.run(null);

        verify(customerService, never()).create(any());
    }

    @Test
    @DisplayName("le seeding est ignore lorsque des donnees existent deja")
    void seedingSkippedWhenDataAlreadyPresent() throws Exception {
        when(customerRepository.count()).thenReturn(12L);

        runner.run(null);

        verify(customerService, never()).create(any());
    }
}

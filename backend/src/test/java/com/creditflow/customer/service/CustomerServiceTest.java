package com.creditflow.customer.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.storage.DocumentAccess;
import com.creditflow.common.storage.DocumentStorage;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.mapper.CustomerMapper;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentShopContext currentShopContext;

    @Mock
    private ShopRepository shopRepository;

    @InjectMocks
    private CustomerService customerService;

    private CustomerRequest request() {
        return new CustomerRequest("Amadou", "Diallo", "770000001", "Medina",
                "1234567890123", "Commercant", null, null, true);
    }

    @BeforeEach
    void setUp() {
        when(currentShopContext.accessibleShopIds()).thenReturn(java.util.List.of(1L));
        when(currentShopContext.shopIdForCreation()).thenReturn(1L);
        when(currentShopContext.currentOrganizationId()).thenReturn(100L);
        when(shopRepository.getReferenceById(1L))
                .thenReturn(Shop.builder().id(1L).name("Boutique principale").active(true).build());
    }

    @Test
    @DisplayName("refuse un telephone deja utilise")
    void rejectsDuplicatePhone() {
        when(customerRepository.existsByPhone("770000001")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("770000001");

        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuse un numero CNI deja utilise")
    void rejectsDuplicateCni() {
        when(customerRepository.existsByPhone("770000001")).thenReturn(false);
        when(customerRepository.existsByCniNumber("1234567890123")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CNI");
    }

    @Test
    @DisplayName("enregistre un client valide")
    void createsCustomer() {
        Customer entity = Customer.builder().firstName("Amadou").lastName("Diallo")
                .phone("770000001").active(true).build();
        when(customerRepository.existsByPhone("770000001")).thenReturn(false);
        when(customerRepository.existsByCniNumber("1234567890123")).thenReturn(false);
        when(customerMapper.toEntity(any(CustomerRequest.class))).thenReturn(entity);
        when(customerRepository.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

        customerService.create(request());

        verify(customerRepository).save(entity);
        assertThat(entity.getCniNumber()).isEqualTo("1234567890123");
    }

    @Test
    @DisplayName("signale un client introuvable")
    void failsWhenMissing() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getEntity(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Client");
    }

    @Test
    @DisplayName("la recherche rapide ignore une requete vide")
    void quickSearchIgnoresBlankQuery() {
        assertThat(customerService.quickSearch("  ", 10)).isEmpty();
        verify(customerRepository, never()).quickSearch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("la recherche rapide transmet l'id d'organisation au repository")
    void quickSearchPassesOrganizationIdToRepository() {
        customerService.quickSearch("Amadou", 10);

        verify(customerRepository).quickSearch(eq("Amadou"), eq(java.util.List.of(1L)), eq(100L), any());
    }

    @Test
    @DisplayName("supprime un client et journalise l'action")
    void deletesCustomerAndRecordsAuditEntry() {
        Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true).shop(shop).build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        customerService.delete(1L);

        verify(auditLogService).record("CUSTOMER", 1L, "Amadou Diallo", "DELETE", null);
        verify(customerRepository).delete(customer);
    }

    @Test
    @DisplayName("getEntity refuse l'acces a un client d'une autre boutique")
    void getEntityRejectsCustomerFromAnotherShop() {
        Shop otherShop = Shop.builder().id(2L).name("Autre boutique").active(true).build();
        Customer customer = Customer.builder()
                .id(5L).firstName("Fatou").lastName("Ndiaye").phone("770000009").active(true).shop(otherShop).build();
        when(customerRepository.findById(5L)).thenReturn(Optional.of(customer));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(2L);

        assertThatThrownBy(() -> customerService.getEntity(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("resolvePhoto delegue au DocumentStorage quand une photo existe")
    void resolvePhotoDelegatesToDocumentStorage() {
        Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true)
                .shop(shop).photoUrl("/uploads/customers/a.png").build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        DocumentAccess access = new DocumentAccess.Inline(new byte[]{1, 2, 3}, "image/png");
        when(documentStorage.resolve("/uploads/customers/a.png")).thenReturn(access);

        assertThat(customerService.resolvePhoto(1L)).isSameAs(access);
    }

    @Test
    @DisplayName("resolvePhoto leve une exception quand le client n'a pas de photo")
    void resolvePhotoThrowsWhenNoPhoto() {
        Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true).shop(shop).build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> customerService.resolvePhoto(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("resolvePhoto refuse l'acces a un client d'une autre boutique")
    void resolvePhotoRejectsCustomerFromAnotherShop() {
        Shop otherShop = Shop.builder().id(2L).name("Autre boutique").active(true).build();
        Customer customer = Customer.builder()
                .id(5L).firstName("Fatou").lastName("Ndiaye").phone("770000009").active(true)
                .shop(otherShop).photoUrl("/uploads/customers/a.png").build();
        when(customerRepository.findById(5L)).thenReturn(Optional.of(customer));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Ressource introuvable"))
                .when(currentShopContext).assertAccessible(2L);

        assertThatThrownBy(() -> customerService.resolvePhoto(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create assigne la boutique resolue par shopIdForCreation")
    void createAssignsShopFromCreationContext() {
        Customer entity = Customer.builder().firstName("Amadou").lastName("Diallo")
                .phone("770000001").active(true).build();
        when(customerMapper.toEntity(any(CustomerRequest.class))).thenReturn(entity);
        when(customerRepository.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

        customerService.create(request());

        assertThat(entity.getShop()).isNotNull();
        assertThat(entity.getShop().getId()).isEqualTo(1L);
    }
}

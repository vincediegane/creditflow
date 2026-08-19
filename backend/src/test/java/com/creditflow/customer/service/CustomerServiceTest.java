package com.creditflow.customer.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.storage.FileStorageService;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.mapper.CustomerMapper;
import com.creditflow.customer.repository.CustomerRepository;
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
    private FileStorageService fileStorageService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CustomerService customerService;

    private CustomerRequest request() {
        return new CustomerRequest("Amadou", "Diallo", "770000001", "Medina",
                "1234567890123", "Commercant", null, null, true);
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
        verify(customerRepository, never()).quickSearch(any(), any());
    }

    @Test
    @DisplayName("supprime un client et journalise l'action")
    void deletesCustomerAndRecordsAuditEntry() {
        Customer customer = Customer.builder()
                .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true).build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        customerService.delete(1L);

        verify(auditLogService).record("CUSTOMER", 1L, "Amadou Diallo", "DELETE", null);
        verify(customerRepository).delete(customer);
    }
}

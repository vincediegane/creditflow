package com.creditflow.supplier.service;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.SupplierRequest;
import com.creditflow.supplier.mapper.SupplierMapper;
import com.creditflow.supplier.repository.SupplierRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private SupplierMapper supplierMapper;

    @InjectMocks
    private SupplierService supplierService;

    private SupplierRequest request() {
        return new SupplierRequest("Grossiste Sahel", "Moussa Ba", "770000002",
                "contact@sahel.sn", "Dakar", null, true);
    }

    @Test
    @DisplayName("enregistre un fournisseur valide")
    void create_createsSupplier() {
        Supplier entity = Supplier.builder().name("Grossiste Sahel").active(true).build();
        when(supplierMapper.toEntity(any(SupplierRequest.class))).thenReturn(entity);
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(i -> i.getArgument(0));

        supplierService.create(request());

        verify(supplierRepository).save(entity);
    }

    @Test
    @DisplayName("signale un fournisseur introuvable")
    void failsWhenMissing() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> supplierService.getEntity(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Fournisseur");
    }

    @Test
    @DisplayName("supprime un fournisseur")
    void deletesSupplier() {
        Supplier supplier = Supplier.builder().id(1L).name("Grossiste Sahel").active(true).build();
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));

        supplierService.delete(1L);

        verify(supplierRepository).delete(supplier);
    }

    @Test
    @DisplayName("la liste de selection ne retient que les fournisseurs actifs")
    void findAllForSelect_filtersInactive() {
        Supplier active = Supplier.builder().id(1L).name("Actif").active(true).build();
        Supplier inactive = Supplier.builder().id(2L).name("Inactif").active(false).build();
        when(supplierRepository.findAll(org.springframework.data.domain.Sort.by("name")))
                .thenReturn(java.util.List.of(active, inactive));
        when(supplierMapper.toResponse(active)).thenReturn(
                new com.creditflow.supplier.dto.SupplierResponse(1L, "Actif", null, null, null, null, null,
                        true, null, null, null));

        var result = supplierService.findAllForSelect();

        assertThat(result).hasSize(1);
    }
}

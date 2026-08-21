package com.creditflow.supplier.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.SupplierRequest;
import com.creditflow.supplier.dto.SupplierResponse;
import com.creditflow.supplier.mapper.SupplierMapper;
import com.creditflow.supplier.repository.SupplierRepository;
import com.creditflow.supplier.repository.SupplierSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Les fournisseurs restent volontairement communs a toutes les boutiques : un
 * grossiste est un tiers externe (table sans {@code shop_id}, ticket #8) et sa
 * fiche n'expose aucune donnee commerciale. Le cloisonnement par boutique porte
 * sur les receptions de stock, qui referencent les produits d'une boutique.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> search(String search, Pageable pageable) {
        Page<Supplier> page = supplierRepository.findAll(
                Specs.combine(SupplierSpecifications.matches(search)), pageable);
        return PageResponse.of(page, supplierMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> findAllForSelect() {
        return supplierRepository.findAll(Sort.by("name"))
                .stream()
                .filter(Supplier::isActive)
                .map(supplierMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierResponse findById(Long id) {
        return supplierMapper.toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Supplier getEntity(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Fournisseur", id));
    }

    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        Supplier supplier = supplierMapper.toEntity(request);
        Supplier saved = supplierRepository.save(supplier);
        log.info("Fournisseur cree: {} ({})", saved.getName(), saved.getId());
        return supplierMapper.toResponse(saved);
    }

    @Transactional
    public SupplierResponse update(Long id, SupplierRequest request) {
        Supplier supplier = getEntity(id);
        supplierMapper.updateEntity(request, supplier);
        supplier.setContactName(request.contactName());
        supplier.setPhone(request.phone());
        supplier.setEmail(request.email());
        supplier.setAddress(request.address());
        supplier.setNotes(request.notes());
        if (request.active() != null) {
            supplier.setActive(request.active());
        }
        return supplierMapper.toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public void delete(Long id) {
        Supplier supplier = getEntity(id);
        supplierRepository.delete(supplier);
        log.info("Fournisseur supprime: {}", id);
    }
}

package com.creditflow.customer.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.storage.FileStorageService;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.customer.mapper.CustomerMapper;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.customer.repository.CustomerSpecifications;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    private final CurrentShopContext currentShopContext;
    private final ShopRepository shopRepository;

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String search, Pageable pageable) {
        Page<Customer> page = customerRepository.findAll(
                Specs.combine(CustomerSpecifications.matches(search),
                        CustomerSpecifications.inShops(currentShopContext.accessibleShopIds())),
                pageable);
        return PageResponse.of(page, customerMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> quickSearch(String search, int limit) {
        if (!StringUtils.hasText(search)) {
            return List.of();
        }
        return customerRepository.quickSearch(search.trim(), currentShopContext.accessibleShopIds(),
                        PageRequest.of(0, limit))
                .stream()
                .map(customerMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> findAllForSelect() {
        return customerRepository.findAll(
                        Specs.combine(CustomerSpecifications.inShops(currentShopContext.accessibleShopIds())),
                        Sort.by("lastName", "firstName"))
                .stream()
                .filter(Customer::isActive)
                .map(customerMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(Long id) {
        return customerMapper.toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Customer getEntity(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Client", id));
        currentShopContext.assertAccessible(customer.getShop().getId());
        return customer;
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        assertPhoneAvailable(request.phone(), null);
        assertCniAvailable(request.cniNumber(), null);

        Shop shop = shopRepository.getReferenceById(currentShopContext.shopIdForCreation());

        Customer customer = customerMapper.toEntity(request);
        customer.setCniNumber(blankToNull(request.cniNumber()));
        customer.setShop(shop);
        Customer saved = customerRepository.save(customer);
        log.info("Client cree: {} ({})", saved.getFullName(), saved.getId());
        return customerMapper.toResponse(saved);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = getEntity(id);
        assertPhoneAvailable(request.phone(), id);
        assertCniAvailable(request.cniNumber(), id);

        customerMapper.updateEntity(request, customer);
        customer.setCniNumber(blankToNull(request.cniNumber()));
        customer.setAddress(request.address());
        customer.setProfession(request.profession());
        customer.setNotes(request.notes());
        if (request.active() != null) {
            customer.setActive(request.active());
        }
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    @Transactional
    public void delete(Long id) {
        Customer customer = getEntity(id);
        auditLogService.record("CUSTOMER", id, customer.getFullName(), "DELETE", null);
        fileStorageService.deleteByPublicUrl(customer.getPhotoUrl());
        customerRepository.delete(customer);
        log.info("Client supprime: {}", id);
    }

    @Transactional
    public CustomerResponse uploadPhoto(Long id, MultipartFile file) {
        Customer customer = getEntity(id);
        String previous = customer.getPhotoUrl();
        customer.setPhotoUrl(fileStorageService.store(file, "customers"));
        Customer saved = customerRepository.save(customer);
        fileStorageService.deleteByPublicUrl(previous);
        return customerMapper.toResponse(saved);
    }

    private void assertPhoneAvailable(String phone, Long id) {
        boolean exists = id == null
                ? customerRepository.existsByPhone(phone)
                : customerRepository.existsByPhoneAndIdNot(phone, id);
        if (exists) {
            throw new BusinessRuleException("Un client utilise deja le telephone " + phone);
        }
    }

    private void assertCniAvailable(String cni, Long id) {
        if (!StringUtils.hasText(cni)) {
            return;
        }
        boolean exists = id == null
                ? customerRepository.existsByCniNumber(cni)
                : customerRepository.existsByCniNumberAndIdNot(cni, id);
        if (exists) {
            throw new BusinessRuleException("Un client utilise deja le numero CNI " + cni);
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

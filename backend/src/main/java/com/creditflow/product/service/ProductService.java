package com.creditflow.product.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.util.Money;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.mapper.ProductMapper;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.product.repository.ProductSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String search, String category, ProductStatus status,
                                                Pageable pageable) {
        Page<Product> page = productRepository.findAll(
                Specs.combine(
                        ProductSpecifications.matches(search),
                        ProductSpecifications.hasCategory(category),
                        ProductSpecifications.hasStatus(status)),
                pageable);
        return PageResponse.of(page, productMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findAllForSelect() {
        return productRepository.findAll(Sort.by("name"))
                .stream()
                .filter(p -> p.getStatus() != ProductStatus.INACTIVE)
                .map(productMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> quickSearch(String search, int limit) {
        if (!StringUtils.hasText(search)) {
            return List.of();
        }
        return productRepository.quickSearch(search.trim(), PageRequest.of(0, limit))
                .stream()
                .map(productMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return productRepository.findAllCategories();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return productMapper.toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Product getEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Produit", id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = productMapper.toEntity(request);
        product.setCashPrice(Money.round(request.cashPrice()));
        product.setCreditPrice(Money.round(request.creditPrice()));
        product.setStatus(resolveStatus(request.status(), request.stock()));
        Product saved = productRepository.save(product);
        log.info("Produit cree: {} ({})", saved.getName(), saved.getId());
        return productMapper.toResponse(saved);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getEntity(id);
        BigDecimal oldCashPrice = product.getCashPrice();
        BigDecimal oldCreditPrice = product.getCreditPrice();

        productMapper.updateEntity(request, product);
        product.setDescription(request.description());
        product.setCashPrice(Money.round(request.cashPrice()));
        product.setCreditPrice(Money.round(request.creditPrice()));
        product.setStatus(resolveStatus(request.status(), request.stock()));

        recordPriceChange(product, oldCashPrice, oldCreditPrice);

        return productMapper.toResponse(productRepository.save(product));
    }

    private void recordPriceChange(Product product, BigDecimal oldCashPrice, BigDecimal oldCreditPrice) {
        boolean cashChanged = oldCashPrice.compareTo(product.getCashPrice()) != 0;
        boolean creditChanged = oldCreditPrice.compareTo(product.getCreditPrice()) != 0;
        if (!cashChanged && !creditChanged) {
            return;
        }

        StringBuilder details = new StringBuilder();
        if (creditChanged) {
            details.append("Prix credit: %s -> %s".formatted(
                    oldCreditPrice.toPlainString(), product.getCreditPrice().toPlainString()));
        }
        if (cashChanged) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append("Prix cash: %s -> %s".formatted(
                    oldCashPrice.toPlainString(), product.getCashPrice().toPlainString()));
        }

        auditLogService.record("PRODUCT", product.getId(), product.getName(), "PRICE_UPDATE", details.toString());
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(getEntity(id));
        log.info("Produit supprime: {}", id);
    }

    /**
     * Decremente le stock lors d'une vente. Le produit passe automatiquement
     * en rupture lorsque le stock atteint zero.
     */
    @Transactional
    public void decreaseStock(Product product, int quantity) {
        int newStock = Math.max(0, product.getStock() - quantity);
        product.setStock(newStock);
        if (newStock == 0 && product.getStatus() == ProductStatus.ACTIVE) {
            product.setStatus(ProductStatus.OUT_OF_STOCK);
        }
        productRepository.save(product);
    }

    private ProductStatus resolveStatus(ProductStatus requested, Integer stock) {
        ProductStatus status = requested == null ? ProductStatus.ACTIVE : requested;
        if (status == ProductStatus.ACTIVE && (stock == null || stock == 0)) {
            return ProductStatus.OUT_OF_STOCK;
        }
        if (status == ProductStatus.OUT_OF_STOCK && stock != null && stock > 0) {
            return ProductStatus.ACTIVE;
        }
        return status;
    }
}

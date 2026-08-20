package com.creditflow.supplier.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.StockSourceType;
import com.creditflow.product.service.ProductService;
import com.creditflow.supplier.domain.StockReception;
import com.creditflow.supplier.domain.StockReceptionLine;
import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.StockReceptionRequest;
import com.creditflow.supplier.dto.StockReceptionResponse;
import com.creditflow.supplier.mapper.StockReceptionMapper;
import com.creditflow.supplier.repository.StockReceptionRepository;
import com.creditflow.supplier.repository.StockReceptionSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockReceptionService {

    private final StockReceptionRepository stockReceptionRepository;
    private final StockReceptionMapper stockReceptionMapper;
    private final SupplierService supplierService;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public PageResponse<StockReceptionResponse> search(Long supplierId, Pageable pageable) {
        Page<StockReception> page = stockReceptionRepository.findAll(
                Specs.combine(StockReceptionSpecifications.forSupplier(supplierId)), pageable);
        return PageResponse.of(page, stockReceptionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public StockReceptionResponse findById(Long id) {
        return stockReceptionMapper.toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public StockReception getEntity(Long id) {
        return stockReceptionRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Reception", id));
    }

    @Transactional
    public StockReceptionResponse receive(StockReceptionRequest request) {
        Supplier supplier = supplierService.getEntity(request.supplierId());

        StockReception reception = StockReception.builder()
                .supplier(supplier)
                .receivedAt(request.receivedAt())
                .notes(request.notes())
                .build();

        List<Product> resolvedProducts = new ArrayList<>();
        for (var line : request.lines()) {
            Product product = productService.getEntity(line.productId());
            reception.addLine(StockReceptionLine.builder().product(product).quantity(line.quantity()).build());
            resolvedProducts.add(product);
        }

        StockReception saved = stockReceptionRepository.save(reception);

        for (int i = 0; i < request.lines().size(); i++) {
            productService.increaseStock(resolvedProducts.get(i), request.lines().get(i).quantity(),
                    StockSourceType.PURCHASE_RECEPTION, saved.getId());
        }

        log.info("Reception {} enregistree pour le fournisseur {} ({} lignes)",
                saved.getId(), supplier.getName(), request.lines().size());

        return stockReceptionMapper.toResponse(saved);
    }
}

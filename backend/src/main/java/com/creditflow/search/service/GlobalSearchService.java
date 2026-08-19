package com.creditflow.search.service;

import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.search.dto.GlobalSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Recherche globale : clients, produits et contrats en une seule requete.
 */
@Service
@RequiredArgsConstructor
public class GlobalSearchService {

    private final CustomerService customerService;
    private final ProductService productService;
    private final CreditSaleService creditSaleService;

    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return new GlobalSearchResponse("", List.of(), List.of(), List.of(), 0);
        }

        String q = query.trim();
        List<CustomerResponse> customers = customerService.quickSearch(q, limit);
        List<ProductResponse> products = productService.quickSearch(q, limit);
        List<SaleResponse> sales = creditSaleService
                .search(q, null, null, PageRequest.of(0, limit))
                .content();

        return new GlobalSearchResponse(q, customers, products, sales,
                customers.size() + products.size() + sales.size());
    }
}

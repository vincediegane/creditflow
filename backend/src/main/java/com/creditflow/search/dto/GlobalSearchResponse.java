package com.creditflow.search.dto;

import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.sale.dto.SaleResponse;

import java.util.List;

public record GlobalSearchResponse(
        String query,
        List<CustomerResponse> customers,
        List<ProductResponse> products,
        List<SaleResponse> sales,
        int totalResults
) {
}

package com.creditflow.supplier.repository;

import com.creditflow.supplier.domain.StockReception;
import org.springframework.data.jpa.domain.Specification;

public final class StockReceptionSpecifications {

    private StockReceptionSpecifications() {
    }

    public static Specification<StockReception> forSupplier(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId);
    }
}

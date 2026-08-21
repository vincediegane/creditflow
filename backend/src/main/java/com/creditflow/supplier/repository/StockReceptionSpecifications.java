package com.creditflow.supplier.repository;

import com.creditflow.supplier.domain.StockReception;
import com.creditflow.supplier.domain.StockReceptionLine;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public final class StockReceptionSpecifications {

    private StockReceptionSpecifications() {
    }

    public static Specification<StockReception> forSupplier(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId);
    }

    /**
     * Une reception appartient a la boutique de ses produits. La sous-requete evite
     * de dupliquer une reception a plusieurs lignes dans la page de resultats.
     */
    public static Specification<StockReception> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> lines = query.subquery(Long.class);
            Root<StockReceptionLine> line = lines.from(StockReceptionLine.class);
            lines.select(line.get("id"));
            lines.where(cb.equal(line.get("reception"), root),
                    line.get("product").get("shop").get("id").in(shopIds));
            return cb.exists(lines);
        };
    }
}

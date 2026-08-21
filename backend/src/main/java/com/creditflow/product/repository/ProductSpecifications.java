package com.creditflow.product.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.List;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                Specs.likeIgnoreCase(cb, root.get("name"), search),
                Specs.likeIgnoreCase(cb, root.get("category"), search),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("description"), ""), search));
    }

    public static Specification<Product> hasCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category"), category.trim());
    }

    public static Specification<Product> hasStatus(ProductStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Product> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("shop").get("id").in(shopIds);
    }
}

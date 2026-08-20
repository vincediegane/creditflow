package com.creditflow.supplier.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.supplier.domain.Supplier;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class SupplierSpecifications {

    private SupplierSpecifications() {
    }

    /** Recherche sur le nom, le contact, le telephone et l'email. */
    public static Specification<Supplier> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                Specs.likeIgnoreCase(cb, root.get("name"), search),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("contactName"), ""), search),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("phone"), ""), search),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("email"), ""), search));
    }
}

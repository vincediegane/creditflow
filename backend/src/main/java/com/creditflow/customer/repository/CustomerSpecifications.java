package com.creditflow.customer.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.customer.domain.Customer;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.List;

public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    /** Recherche sur le nom complet, le telephone, la CNI et la profession. */
    public static Specification<Customer> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                Specs.likeIgnoreCase(cb, root.get("firstName"), search),
                Specs.likeIgnoreCase(cb, root.get("lastName"), search),
                Specs.likeIgnoreCase(cb,
                        cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName")), search),
                cb.like(root.get("phone"), "%" + search.trim() + "%"),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("cniNumber"), ""), search),
                Specs.likeIgnoreCase(cb, cb.coalesce(root.get("profession"), ""), search));
    }

    public static Specification<Customer> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("shop").get("id").in(shopIds);
    }

    public static Specification<Customer> inOrganization(Long organizationId) {
        if (organizationId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("shop").get("organization").get("id"), organizationId);
    }
}

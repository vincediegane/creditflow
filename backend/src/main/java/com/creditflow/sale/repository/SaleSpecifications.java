package com.creditflow.sale.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.List;

public final class SaleSpecifications {

    private SaleSpecifications() {
    }

    /** Recherche sur la reference du contrat, le client, son telephone, le produit et le garant. */
    public static Specification<CreditSale> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        return (root, query, cb) -> {
            var customer = root.get("customer");
            var product = root.get("product");
            return cb.or(
                    Specs.likeIgnoreCase(cb, root.get("reference"), search),
                    Specs.likeIgnoreCase(cb,
                            cb.concat(cb.concat(customer.get("firstName"), " "), customer.get("lastName")),
                            search),
                    cb.like(customer.get("phone"), "%" + search.trim() + "%"),
                    Specs.likeIgnoreCase(cb, product.get("name"), search),
                    Specs.likeIgnoreCase(cb, cb.coalesce(root.get("guarantorFullName"), ""), search),
                    cb.like(cb.coalesce(root.get("guarantorPhone"), ""), "%" + search.trim() + "%"));
        };
    }

    public static Specification<CreditSale> hasStatus(SaleStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<CreditSale> forCustomer(Long customerId) {
        if (customerId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
    }

    public static Specification<CreditSale> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("shop").get("id").in(shopIds);
    }

    public static Specification<CreditSale> inOrganization(Long organizationId) {
        if (organizationId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("shop").get("organization").get("id"), organizationId);
    }
}

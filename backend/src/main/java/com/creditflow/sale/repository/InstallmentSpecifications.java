package com.creditflow.sale.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

public final class InstallmentSpecifications {

    private InstallmentSpecifications() {
    }

    public static Specification<Installment> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        return (root, query, cb) -> {
            var sale = root.get("sale");
            var customer = sale.get("customer");
            return cb.or(
                    Specs.likeIgnoreCase(cb,
                            cb.concat(cb.concat(customer.get("firstName"), " "), customer.get("lastName")),
                            search),
                    cb.like(customer.get("phone"), "%" + search.trim() + "%"),
                    Specs.likeIgnoreCase(cb, sale.get("product").get("name"), search),
                    Specs.likeIgnoreCase(cb, sale.get("reference"), search));
        };
    }

    public static Specification<Installment> hasStatus(InstallmentStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Installment> dueFrom(LocalDate from) {
        if (from == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dueDate"), from);
    }

    public static Specification<Installment> dueTo(LocalDate to) {
        if (to == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("dueDate"), to);
    }

    /** Echeance non soldee dont la date prevue est depassee. */
    public static Specification<Installment> lateOn(boolean onlyLate, LocalDate reference) {
        if (!onlyLate) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.notEqual(root.get("status"), InstallmentStatus.PAID),
                cb.lessThan(root.get("dueDate"), reference));
    }

    public static Specification<Installment> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("sale").get("shop").get("id").in(shopIds);
    }
}

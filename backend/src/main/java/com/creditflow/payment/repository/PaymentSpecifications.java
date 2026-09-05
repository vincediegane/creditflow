package com.creditflow.payment.repository;

import com.creditflow.common.repository.Specs;
import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.domain.PaymentMethod;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    public static Specification<Payment> matches(String search) {
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
                    Specs.likeIgnoreCase(cb, sale.get("reference"), search),
                    Specs.likeIgnoreCase(cb, cb.coalesce(root.get("reference"), ""), search),
                    Specs.likeIgnoreCase(cb, sale.get("product").get("name"), search));
        };
    }

    public static Specification<Payment> hasMethod(PaymentMethod method) {
        if (method == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("method"), method);
    }

    public static Specification<Payment> paidFrom(LocalDate from) {
        if (from == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("paymentDate"), from);
    }

    public static Specification<Payment> paidTo(LocalDate to) {
        if (to == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("paymentDate"), to);
    }

    public static Specification<Payment> forCustomer(Long customerId) {
        if (customerId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("sale").get("customer").get("id"), customerId);
    }

    public static Specification<Payment> inShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("sale").get("shop").get("id").in(shopIds);
    }

    public static Specification<Payment> inOrganization(Long organizationId) {
        if (organizationId == null) {
            return null;
        }
        return (root, query, cb) ->
                cb.equal(root.get("sale").get("shop").get("organization").get("id"), organizationId);
    }
}

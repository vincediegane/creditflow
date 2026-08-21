package com.creditflow.customer.repository;

import com.creditflow.customer.domain.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerSpecificationsTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inShops ne genere aucun predicat pour une liste nulle ou vide")
    void inShopsReturnsNullWhenEmpty() {
        assertThat(CustomerSpecifications.inShops(null)).isNull();
        assertThat(CustomerSpecifications.inShops(List.of())).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inShops filtre sur shop.id via IN")
    void inShopsFiltersOnShopId() {
        Root<Customer> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path shopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get("shop")).thenReturn(shopPath);
        when(shopPath.get("id")).thenReturn(idPath);
        when(idPath.in(List.of(1L, 2L))).thenReturn(predicate);

        Specification<Customer> specification = CustomerSpecifications.inShops(List.of(1L, 2L));
        assertThat(specification).isNotNull();

        Predicate result = specification.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(predicate);
        verify(root).get("shop");
        verify(shopPath).get("id");
        verify(idPath).in(List.of(1L, 2L));
    }
}

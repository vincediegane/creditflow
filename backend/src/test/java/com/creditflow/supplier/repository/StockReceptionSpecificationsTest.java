package com.creditflow.supplier.repository;

import com.creditflow.supplier.domain.StockReception;
import com.creditflow.supplier.domain.StockReceptionLine;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockReceptionSpecificationsTest {

    @Test
    @DisplayName("inShops ne genere aucun predicat pour une liste nulle ou vide")
    void inShopsReturnsNullWhenEmpty() {
        assertThat(StockReceptionSpecifications.inShops(null)).isNull();
        assertThat(StockReceptionSpecifications.inShops(List.of())).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inShops ne retient que les receptions dont les lignes portent un produit des boutiques accessibles")
    void inShopsFiltersOnLineProductShop() {
        Root<StockReception> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Subquery<Long> subquery = mock(Subquery.class);
        Root<StockReceptionLine> lineRoot = mock(Root.class);
        Path idPath = mock(Path.class);
        Path receptionPath = mock(Path.class);
        Path productPath = mock(Path.class);
        Path shopPath = mock(Path.class);
        Path shopIdPath = mock(Path.class);
        Predicate sameReception = mock(Predicate.class);
        Predicate shopPredicate = mock(Predicate.class);
        Predicate exists = mock(Predicate.class);

        when(query.subquery(Long.class)).thenReturn((Subquery) subquery);
        when(subquery.from(StockReceptionLine.class)).thenReturn(lineRoot);
        when(lineRoot.get("id")).thenReturn(idPath);
        when(lineRoot.get("reception")).thenReturn(receptionPath);
        when(lineRoot.get("product")).thenReturn(productPath);
        when(productPath.get("shop")).thenReturn(shopPath);
        when(shopPath.get("id")).thenReturn(shopIdPath);
        when(cb.equal(receptionPath, root)).thenReturn(sameReception);
        when(shopIdPath.in(List.of(1L))).thenReturn(shopPredicate);
        when(cb.exists(subquery)).thenReturn(exists);

        Specification<StockReception> specification = StockReceptionSpecifications.inShops(List.of(1L));
        assertThat(specification).isNotNull();

        Predicate result = specification.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(exists);
        verify(subquery).select(idPath);
        verify(subquery).where(sameReception, shopPredicate);
    }
}

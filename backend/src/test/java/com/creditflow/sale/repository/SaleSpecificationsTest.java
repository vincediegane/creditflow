package com.creditflow.sale.repository;

import com.creditflow.sale.domain.CreditSale;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie que la recherche de contrats atteint bien les champs garant (pas
 * d'environnement de test avec base embarquee dans ce projet : le predicat
 * genere est controle via des mocks JPA Criteria plutot qu'une execution SQL
 * reelle, sur le modele des tests existants de {@code SaleSpecifications}).
 */
class SaleSpecificationsTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("aucun predicat n'est genere pour une recherche absente")
    void matchesReturnsNullWhenSearchIsBlank() {
        assertThat(SaleSpecifications.matches(null)).isNull();
        assertThat(SaleSpecifications.matches("   ")).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("la recherche par nom du garant atteint le champ guarantorFullName")
    void matchesReachesGuarantorFullName() {
        Root<CreditSale> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Expression expression = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get(anyString())).thenReturn(path);
        when(path.get(anyString())).thenReturn(path);
        when(cb.concat(any(Expression.class), any(Expression.class))).thenReturn(expression);
        when(cb.concat(any(Expression.class), anyString())).thenReturn(expression);
        when(cb.lower(any(Expression.class))).thenReturn(expression);
        when(cb.coalesce(any(Expression.class), any())).thenReturn(expression);
        when(cb.like(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);

        Specification<CreditSale> specification = SaleSpecifications.matches("Moussa Kane");
        assertThat(specification).isNotNull();

        specification.toPredicate(root, query, cb);

        verify(root).get("guarantorFullName");
        verify(root).get("guarantorPhone");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("la recherche par telephone du garant compare le champ guarantorPhone via coalesce")
    void matchesReachesGuarantorPhoneWithCoalesce() {
        Root<CreditSale> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Expression expression = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get(anyString())).thenReturn(path);
        when(path.get(anyString())).thenReturn(path);
        when(cb.concat(any(Expression.class), any(Expression.class))).thenReturn(expression);
        when(cb.concat(any(Expression.class), anyString())).thenReturn(expression);
        when(cb.lower(any(Expression.class))).thenReturn(expression);
        when(cb.coalesce(any(Expression.class), any())).thenReturn(expression);
        when(cb.like(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);

        Specification<CreditSale> specification = SaleSpecifications.matches("770001122");
        assertThat(specification).isNotNull();

        specification.toPredicate(root, query, cb);

        verify(root).get("guarantorPhone");
        verify(cb, atLeastOnce()).coalesce(path, "");
        verify(cb, atLeastOnce()).like(expression, "%770001122%");
    }
}

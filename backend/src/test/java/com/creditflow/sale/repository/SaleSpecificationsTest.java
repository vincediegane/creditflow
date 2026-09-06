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

import java.util.List;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inShops ne genere aucun predicat pour une liste nulle ou vide")
    void inShopsReturnsNullWhenEmpty() {
        assertThat(SaleSpecifications.inShops(null)).isNull();
        assertThat(SaleSpecifications.inShops(List.of())).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inShops filtre sur shop.id via IN")
    void inShopsFiltersOnShopId() {
        Root<CreditSale> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path shopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get("shop")).thenReturn(shopPath);
        when(shopPath.get("id")).thenReturn(idPath);
        when(idPath.in(List.of(1L, 2L))).thenReturn(predicate);

        Specification<CreditSale> specification = SaleSpecifications.inShops(List.of(1L, 2L));
        assertThat(specification).isNotNull();

        Predicate result = specification.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(predicate);
        verify(root).get("shop");
        verify(shopPath).get("id");
        verify(idPath).in(List.of(1L, 2L));
    }

    @Test
    @DisplayName("inOrganization ne genere aucun predicat pour un id nul")
    void inOrganizationReturnsNullWhenNull() {
        assertThat(SaleSpecifications.inOrganization(null)).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("inOrganization filtre sur shop.organization.id")
    void inOrganizationFiltersOnShopOrganizationId() {
        Root<CreditSale> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path shopPath = mock(Path.class);
        Path organizationPath = mock(Path.class);
        Path idPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get("shop")).thenReturn(shopPath);
        when(shopPath.get("organization")).thenReturn(organizationPath);
        when(organizationPath.get("id")).thenReturn(idPath);
        when(cb.equal(idPath, 1L)).thenReturn(predicate);

        Specification<CreditSale> specification = SaleSpecifications.inOrganization(1L);
        assertThat(specification).isNotNull();

        Predicate result = specification.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(predicate);
        verify(root).get("shop");
        verify(shopPath).get("organization");
        verify(organizationPath).get("id");
        verify(cb).equal(idPath, 1L);
    }
}

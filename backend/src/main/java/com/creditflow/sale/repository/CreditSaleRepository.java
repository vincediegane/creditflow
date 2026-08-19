package com.creditflow.sale.repository;

import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.SaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CreditSaleRepository extends JpaRepository<CreditSale, Long>,
        JpaSpecificationExecutor<CreditSale> {

    /** Le graphe evite une requete supplementaire par ligne pour le client et le produit. */
    @Override
    @EntityGraph(attributePaths = {"customer", "product"})
    Page<CreditSale> findAll(Specification<CreditSale> specification, Pageable pageable);

    @Query("""
            SELECT s FROM CreditSale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.id = :id
            """)
    Optional<CreditSale> findDetailById(@Param("id") Long id);

    @Query("""
            SELECT s FROM CreditSale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.customer.id = :customerId
            ORDER BY s.createdAt DESC
            """)
    List<CreditSale> findByCustomer(@Param("customerId") Long customerId);

    long countByStatus(SaleStatus status);

    @Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s WHERE s.status = :status")
    BigDecimal sumRemainingByStatus(@Param("status") SaleStatus status);

    @Query("SELECT COALESCE(SUM(s.totalPrice), 0) FROM CreditSale s WHERE s.customer.id = :customerId")
    BigDecimal sumTotalPriceByCustomer(@Param("customerId") Long customerId);

    @Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s WHERE s.customer.id = :customerId")
    BigDecimal sumRemainingByCustomer(@Param("customerId") Long customerId);

    @Query("SELECT s FROM CreditSale s JOIN FETCH s.customer JOIN FETCH s.product ORDER BY s.createdAt DESC")
    List<CreditSale> findAllDetailed();
}

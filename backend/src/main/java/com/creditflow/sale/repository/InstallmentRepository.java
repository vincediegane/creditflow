package com.creditflow.sale.repository;

import com.creditflow.sale.domain.Installment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long>,
        JpaSpecificationExecutor<Installment> {

    /** Le graphe charge le contrat, le client et le produit en une seule requete. */
    @Override
    @EntityGraph(attributePaths = {"sale", "sale.customer", "sale.product"})
    Page<Installment> findAll(Specification<Installment> specification, Pageable pageable);

    List<Installment> findBySaleIdOrderByNumberAsc(Long saleId);

    @Query("""
            SELECT i FROM Installment i
            JOIN FETCH i.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE i.status <> com.creditflow.sale.domain.InstallmentStatus.PAID
              AND i.dueDate BETWEEN :from AND :to
              AND s.shop.id IN :shopIds
            ORDER BY i.dueDate ASC
            """)
    List<Installment> findUpcomingForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                            @Param("shopIds") List<Long> shopIds);

    @Query("""
            SELECT i FROM Installment i
            JOIN FETCH i.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE i.status <> com.creditflow.sale.domain.InstallmentStatus.PAID
              AND i.dueDate < :reference
              AND s.shop.id IN :shopIds
            ORDER BY i.dueDate ASC
            """)
    List<Installment> findLateForShops(@Param("reference") LocalDate reference, @Param("shopIds") List<Long> shopIds);

    @Query("""
            SELECT COUNT(i) FROM Installment i
            WHERE i.status <> com.creditflow.sale.domain.InstallmentStatus.PAID
              AND i.dueDate < :reference
              AND i.sale.shop.id IN :shopIds
            """)
    long countLateForShops(@Param("reference") LocalDate reference, @Param("shopIds") List<Long> shopIds);

    @Query("""
            SELECT COUNT(i) FROM Installment i
            WHERE i.sale.customer.id = :customerId
              AND i.status <> com.creditflow.sale.domain.InstallmentStatus.PAID
              AND i.dueDate < :reference
            """)
    long countLateByCustomer(@Param("customerId") Long customerId, @Param("reference") LocalDate reference);

    @Query("""
            SELECT COALESCE(SUM(i.amount - i.amountPaid), 0) FROM Installment i
            WHERE i.status <> com.creditflow.sale.domain.InstallmentStatus.PAID
              AND i.dueDate < :reference
              AND i.sale.shop.id IN :shopIds
            """)
    BigDecimal sumLateAmountForShops(@Param("reference") LocalDate reference, @Param("shopIds") List<Long> shopIds);
}

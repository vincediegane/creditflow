package com.creditflow.payment.repository;

import com.creditflow.payment.domain.Payment;
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

public interface PaymentRepository extends JpaRepository<Payment, Long>,
        JpaSpecificationExecutor<Payment> {

    /** Le graphe charge le contrat, le client et le produit en une seule requete. */
    @Override
    @EntityGraph(attributePaths = {"sale", "sale.customer", "sale.product"})
    Page<Payment> findAll(Specification<Payment> specification, Pageable pageable);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.id = :saleId
            ORDER BY p.paymentDate DESC, p.id DESC
            """)
    List<Payment> findBySale(@Param("saleId") Long saleId);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.customer.id = :customerId
            ORDER BY p.paymentDate DESC, p.id DESC
            """)
    List<Payment> findByCustomer(@Param("customerId") Long customerId);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE p.paymentDate BETWEEN :from AND :to
              AND s.shop.id IN :shopIds
            ORDER BY p.paymentDate DESC, p.id DESC
            """)
    List<Payment> findBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                       @Param("shopIds") List<Long> shopIds);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
            + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds")
    BigDecimal sumBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("shopIds") List<Long> shopIds);

    @Query("SELECT COUNT(p) FROM Payment p "
            + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds")
    long countBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("shopIds") List<Long> shopIds);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.sale.customer.id = :customerId")
    BigDecimal sumByCustomer(@Param("customerId") Long customerId);

    List<Payment> findBySaleIdOrderByPaymentDateAscIdAsc(Long saleId);
}

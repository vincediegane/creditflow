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
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>,
        JpaSpecificationExecutor<Payment> {

    /** Le graphe charge le contrat, le client et le produit en une seule requete. */
    @Override
    @EntityGraph(attributePaths = {"sale", "sale.customer", "sale.product"})
    Page<Payment> findAll(Specification<Payment> specification, Pageable pageable);

    /**
     * Aucun filtre organisation direct : l'appelant doit garantir l'accès au contrat
     * en amont (voir PaymentService.findBySale / CreditSaleService.findDetail / delete).
     */
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.id = :saleId
            ORDER BY p.paymentDate DESC, p.id DESC
            """)
    List<Payment> findBySale(@Param("saleId") Long saleId);

    /**
     * Aucun filtre organisation direct : l'appelant doit garantir l'accès au client
     * en amont (voir PaymentService.findByCustomer / CustomerProfileService.profile).
     */
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
              AND s.shop.organization.id = :organizationId
            ORDER BY p.paymentDate DESC, p.id DESC
            """)
    List<Payment> findBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                       @Param("shopIds") List<Long> shopIds,
                                       @Param("organizationId") Long organizationId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
            + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds "
            + "AND p.sale.shop.organization.id = :organizationId")
    BigDecimal sumBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("shopIds") List<Long> shopIds,
                                   @Param("organizationId") Long organizationId);

    @Query("SELECT COUNT(p) FROM Payment p "
            + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds "
            + "AND p.sale.shop.organization.id = :organizationId")
    long countBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("shopIds") List<Long> shopIds,
                               @Param("organizationId") Long organizationId);

    /**
     * Aucun filtre organisation direct : l'appelant doit garantir l'accès au client
     * en amont (voir PaymentService.findByCustomer / CustomerProfileService.profile).
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.sale.customer.id = :customerId")
    BigDecimal sumByCustomer(@Param("customerId") Long customerId);

    /**
     * Aucun filtre organisation direct : appelée uniquement après un assertAccessible
     * déjà exécuté dans PaymentService.delete.
     */
    List<Payment> findBySaleIdOrderByPaymentDateAscIdAsc(Long saleId);

    /**
     * Court-circuit d'idempotence : les JOIN FETCH evitent 4 requetes lazy par rejeu.
     * Aucun filtre organisation direct : protégée par un assertAccessible juste
     * après lecture dans PaymentService.register.
     */
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.sale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            JOIN FETCH s.shop
            WHERE p.clientRequestId = :clientRequestId
            """)
    Optional<Payment> findByClientRequestId(@Param("clientRequestId") String clientRequestId);
}

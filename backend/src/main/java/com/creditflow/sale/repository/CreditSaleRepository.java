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

    /**
     * Filtre uniquement par customerId, sans filtre organisation direct : le contrat implicite
     * est que l'appelant a deja valide l'acces au client (CustomerService.findById/getEntity,
     * deja scope organisation par #35/#36) avant d'invoquer cette methode. Ne jamais exposer
     * directement a un controleur avec un customerId de requete sans validation prealable
     * (cf. docs/bolts/37-.../design.md, section Risques).
     */
    @Query("""
            SELECT s FROM CreditSale s
            JOIN FETCH s.customer
            JOIN FETCH s.product
            WHERE s.customer.id = :customerId
            ORDER BY s.createdAt DESC
            """)
    List<CreditSale> findByCustomer(@Param("customerId") Long customerId);

    long countByStatusAndShop_IdIn(SaleStatus status, List<Long> shopIds);

    long countByShop_IdIn(List<Long> shopIds);

    @Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s "
            + "WHERE s.status = :status AND s.shop.id IN :shopIds AND s.shop.organization.id = :organizationId")
    BigDecimal sumRemainingByStatusForShops(@Param("status") SaleStatus status, @Param("shopIds") List<Long> shopIds,
                                             @Param("organizationId") Long organizationId);

    /**
     * Filtre uniquement par customerId, sans filtre organisation direct : le contrat implicite
     * est que l'appelant a deja valide l'acces au client (CustomerService.findById/getEntity,
     * deja scope organisation par #35/#36) avant d'invoquer cette methode. Ne jamais exposer
     * directement a un controleur avec un customerId de requete sans validation prealable
     * (cf. docs/bolts/37-.../design.md, section Risques).
     */
    @Query("SELECT COALESCE(SUM(s.totalPrice), 0) FROM CreditSale s WHERE s.customer.id = :customerId")
    BigDecimal sumTotalPriceByCustomer(@Param("customerId") Long customerId);

    /**
     * Filtre uniquement par customerId, sans filtre organisation direct : le contrat implicite
     * est que l'appelant a deja valide l'acces au client (CustomerService.findById/getEntity,
     * deja scope organisation par #35/#36) avant d'invoquer cette methode. Ne jamais exposer
     * directement a un controleur avec un customerId de requete sans validation prealable
     * (cf. docs/bolts/37-.../design.md, section Risques).
     */
    @Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s WHERE s.customer.id = :customerId")
    BigDecimal sumRemainingByCustomer(@Param("customerId") Long customerId);

    @Query("SELECT s FROM CreditSale s JOIN FETCH s.customer JOIN FETCH s.product "
            + "WHERE s.shop.id IN :shopIds AND s.shop.organization.id = :organizationId ORDER BY s.createdAt DESC")
    List<CreditSale> findAllDetailedForShops(@Param("shopIds") List<Long> shopIds,
                                              @Param("organizationId") Long organizationId);
}

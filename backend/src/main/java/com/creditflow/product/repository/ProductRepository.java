package com.creditflow.product.repository;

import com.creditflow.product.domain.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findFirstByNameIgnoreCaseAndShop_Id(String name, Long shopId);

    @Query("SELECT DISTINCT p.category FROM Product p WHERE p.shop.id IN :shopIds "
            + "AND p.shop.organization.id = :organizationId ORDER BY p.category")
    List<String> findAllCategories(@Param("shopIds") List<Long> shopIds, @Param("organizationId") Long organizationId);

    @Query("""
            SELECT p FROM Product p
            WHERE (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.category) LIKE LOWER(CONCAT('%', :search, '%')))
              AND p.shop.id IN :shopIds
              AND p.shop.organization.id = :organizationId
            ORDER BY p.name
            """)
    List<Product> quickSearch(@Param("search") String search, @Param("shopIds") List<Long> shopIds,
                               @Param("organizationId") Long organizationId, Pageable pageable);
}

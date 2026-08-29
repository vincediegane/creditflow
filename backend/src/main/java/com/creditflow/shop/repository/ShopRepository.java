package com.creditflow.shop.repository;

import com.creditflow.shop.domain.Shop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    List<Shop> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<Shop> findAllByActiveTrueOrderByNameAsc();

    List<Shop> findAllByActiveTrueAndOrganizationIdOrderByNameAsc(Long organizationId);

    long countByActiveTrue();

    boolean existsByActiveTrueAndIdNot(Long id);
}

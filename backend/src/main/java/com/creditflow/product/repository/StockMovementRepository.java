package com.creditflow.product.repository;

import com.creditflow.product.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByProductIdOrderByOccurredAtDescIdDesc(Long productId);
}

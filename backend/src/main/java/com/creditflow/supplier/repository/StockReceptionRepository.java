package com.creditflow.supplier.repository;

import com.creditflow.supplier.domain.StockReception;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StockReceptionRepository
        extends JpaRepository<StockReception, Long>, JpaSpecificationExecutor<StockReception> {
}

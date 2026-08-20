package com.creditflow.sale.repository;

import com.creditflow.sale.domain.SaleAttachment;
import com.creditflow.sale.domain.SaleAttachmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SaleAttachmentRepository extends JpaRepository<SaleAttachment, Long> {

    List<SaleAttachment> findBySaleIdOrderByCreatedAtAsc(Long saleId);

    List<SaleAttachment> findBySaleIdAndType(Long saleId, SaleAttachmentType type);

    Optional<SaleAttachment> findByIdAndSaleId(Long id, Long saleId);
}

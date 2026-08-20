package com.creditflow.sale.dto;

import com.creditflow.sale.domain.SaleAttachmentType;

import java.time.LocalDateTime;

public record SaleAttachmentResponse(
        Long id,
        Long saleId,
        SaleAttachmentType type,
        String fileUrl,
        String originalFilename,
        String contentType,
        LocalDateTime createdAt,
        String createdBy
) {
}

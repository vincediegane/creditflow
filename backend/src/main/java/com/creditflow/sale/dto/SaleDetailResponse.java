package com.creditflow.sale.dto;

import com.creditflow.payment.dto.PaymentResponse;

import java.util.List;

public record SaleDetailResponse(
        SaleResponse sale,
        List<InstallmentResponse> installments,
        List<PaymentResponse> payments,
        List<SaleAttachmentResponse> attachments
) {
}

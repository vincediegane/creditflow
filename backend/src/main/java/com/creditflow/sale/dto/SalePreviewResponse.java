package com.creditflow.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SalePreviewResponse(
        BigDecimal totalPrice,
        BigDecimal interestAmount,
        BigDecimal financedAmount,
        BigDecimal monthlyAmount,
        LocalDate endDate,
        List<PreviewLine> lines
) {

    public record PreviewLine(int number, LocalDate dueDate, BigDecimal amount) {
    }
}

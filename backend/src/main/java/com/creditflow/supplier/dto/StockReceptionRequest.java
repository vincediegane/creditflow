package com.creditflow.supplier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public record StockReceptionRequest(
        @NotNull(message = "Le fournisseur est obligatoire")
        Long supplierId,

        @NotNull(message = "La date de reception est obligatoire")
        LocalDate receivedAt,

        String notes,

        @NotEmpty(message = "Au moins une ligne est requise")
        @Valid
        List<StockReceptionLineRequest> lines
) {
    public record StockReceptionLineRequest(
            @NotNull(message = "Le produit est obligatoire")
            Long productId,

            @NotNull(message = "La quantite est obligatoire")
            @Positive(message = "La quantite doit etre positive")
            Integer quantity
    ) {
    }
}

package com.creditflow.product.dto;

import com.creditflow.product.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank(message = "Le nom du produit est obligatoire")
        @Size(max = 150)
        String name,

        @NotBlank(message = "La categorie est obligatoire")
        @Size(max = 60)
        String category,

        @NotNull(message = "Le prix comptant est obligatoire")
        @DecimalMin(value = "0.0", message = "Le prix comptant doit etre positif")
        BigDecimal cashPrice,

        @NotNull(message = "Le prix a credit est obligatoire")
        @DecimalMin(value = "0.0", message = "Le prix a credit doit etre positif")
        BigDecimal creditPrice,

        @NotNull(message = "Le stock est obligatoire")
        @Min(value = 0, message = "Le stock ne peut pas etre negatif")
        Integer stock,

        String description,

        ProductStatus status
) {
}

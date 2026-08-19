package com.creditflow.sale.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateSaleRequest(
        @NotNull(message = "Le client est obligatoire")
        Long customerId,

        @NotNull(message = "Le produit est obligatoire")
        Long productId,

        @NotNull(message = "Le prix est obligatoire")
        @DecimalMin(value = "1.0", message = "Le prix doit etre superieur a zero")
        BigDecimal totalPrice,

        @DecimalMin(value = "0.0", message = "L'acompte ne peut pas etre negatif")
        BigDecimal downPayment,

        @DecimalMin(value = "0.0", message = "Le taux ne peut pas etre negatif")
        @DecimalMax(value = "100.0", message = "Le taux ne peut pas depasser 100")
        BigDecimal interestRate,

        @DecimalMin(value = "0.0", message = "Les frais ne peuvent pas etre negatifs")
        BigDecimal interestFee,

        @NotNull(message = "Le nombre de mensualites est obligatoire")
        @Min(value = 1, message = "Au moins une mensualite")
        @Max(value = 60, message = "Maximum 60 mensualites")
        Integer installmentCount,

        @NotNull(message = "La date de debut est obligatoire")
        LocalDate startDate,

        String notes
) {
}

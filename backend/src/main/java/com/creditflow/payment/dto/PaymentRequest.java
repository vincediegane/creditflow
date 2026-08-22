package com.creditflow.payment.dto;

import com.creditflow.payment.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentRequest(
        @NotNull(message = "Le contrat est obligatoire")
        Long saleId,

        @NotNull(message = "Le montant est obligatoire")
        @DecimalMin(value = "1.0", message = "Le montant doit etre superieur a zero")
        BigDecimal amount,

        /** Par defaut : aujourd'hui. */
        LocalDate paymentDate,

        @NotNull(message = "Le mode de paiement est obligatoire")
        PaymentMethod method,

        @Size(max = 60)
        String reference,

        String notes,

        /** Identifiant d'idempotence genere par le client. Facultatif. */
        @Size(max = 64)
        String clientRequestId
) {
}

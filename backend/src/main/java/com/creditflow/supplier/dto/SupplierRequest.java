package com.creditflow.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierRequest(
        @NotBlank(message = "Le nom du fournisseur est obligatoire")
        @Size(max = 150)
        String name,

        @Size(max = 120)
        String contactName,

        @Size(max = 30)
        String phone,

        @Size(max = 120)
        @Email(message = "Email invalide")
        String email,

        @Size(max = 255)
        String address,

        String notes,

        Boolean active
) {
}

package com.creditflow.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank(message = "Le prenom est obligatoire")
        @Size(max = 80)
        String firstName,

        @NotBlank(message = "Le nom est obligatoire")
        @Size(max = 80)
        String lastName,

        @NotBlank(message = "Le telephone est obligatoire")
        @Size(max = 30)
        @Pattern(regexp = "^[0-9+\\-\\s()]{6,30}$", message = "Numero de telephone invalide")
        String phone,

        @Size(max = 255)
        String address,

        @Size(max = 50)
        String cniNumber,

        @Size(max = 120)
        String profession,

        @Size(max = 255)
        String photoUrl,

        String notes,

        Boolean active
) {
}

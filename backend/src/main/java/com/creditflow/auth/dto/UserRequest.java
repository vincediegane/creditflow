package com.creditflow.auth.dto;

import com.creditflow.auth.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserRequest(
        @NotBlank(message = "L'identifiant est obligatoire")
        @Size(max = 80)
        String username,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir au moins 8 caracteres")
        String password,

        @NotBlank(message = "Le nom complet est obligatoire")
        @Size(max = 150)
        String fullName,

        @NotNull(message = "Le role est obligatoire")
        Role role
) {
}

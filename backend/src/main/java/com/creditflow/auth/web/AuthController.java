package com.creditflow.auth.web;

import com.creditflow.auth.dto.AuthResponse;
import com.creditflow.auth.dto.ChangePasswordRequest;
import com.creditflow.auth.dto.LoginRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentification", description = "Connexion, deconnexion et profil courant")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Se connecter et obtenir un jeton JWT",
            security = @SecurityRequirement(name = ""))
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Profil de l'utilisateur connecte")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(authService.currentUser(principal.getUsername()));
    }

    @PutMapping("/password")
    @Operation(summary = "Changer son mot de passe")
    public ResponseEntity<UserResponse> changePassword(@AuthenticationPrincipal UserDetails principal,
                                                       @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changePassword(principal.getUsername(), request));
    }

    @PostMapping("/logout")
    @Operation(summary = "Se deconnecter (le client supprime le jeton stocke)")
    public ResponseEntity<Void> logout() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}

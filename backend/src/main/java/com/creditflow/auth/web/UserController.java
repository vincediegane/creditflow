package com.creditflow.auth.web;

import com.creditflow.auth.dto.UserRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.dto.UserStatusRequest;
import com.creditflow.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Utilisateurs", description = "Comptes vendeur/caissier, reserve a l'administrateur")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lister les comptes utilisateurs")
    public List<UserResponse> list() {
        return userService.list();
    }

    @PostMapping
    @Operation(summary = "Creer un compte vendeur/caissier")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request,
                                               UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/users/{id}").build(created.id()))
                .body(created);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activer ou desactiver un compte")
    public UserResponse setEnabled(@PathVariable Long id, @Valid @RequestBody UserStatusRequest request,
                                   @AuthenticationPrincipal UserDetails principal) {
        return userService.setEnabled(id, request.enabled(), principal.getUsername());
    }
}

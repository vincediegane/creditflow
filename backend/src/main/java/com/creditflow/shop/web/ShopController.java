package com.creditflow.shop.web;

import com.creditflow.shop.dto.ShopRequest;
import com.creditflow.shop.dto.ShopResponse;
import com.creditflow.shop.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/shops")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Boutiques", description = "Gestion des boutiques, reserve a l'administrateur")
public class ShopController {

    private final ShopService shopService;

    @GetMapping
    @Operation(summary = "Lister les boutiques")
    public List<ShopResponse> list() {
        return shopService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une boutique")
    public ShopResponse get(@PathVariable Long id) {
        return shopService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Creer une boutique")
    public ResponseEntity<ShopResponse> create(@Valid @RequestBody ShopRequest request,
                                               UriComponentsBuilder uriBuilder) {
        ShopResponse created = shopService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/shops/{id}").build(created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier une boutique")
    public ShopResponse update(@PathVariable Long id, @Valid @RequestBody ShopRequest request) {
        return shopService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer une boutique")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        shopService.delete(id);
    }
}

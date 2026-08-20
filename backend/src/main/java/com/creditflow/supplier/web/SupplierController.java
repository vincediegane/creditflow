package com.creditflow.supplier.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.supplier.dto.SupplierRequest;
import com.creditflow.supplier.dto.SupplierResponse;
import com.creditflow.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
@Tag(name = "Fournisseurs", description = "Gestion des fournisseurs")
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    @Operation(summary = "Lister et rechercher les fournisseurs")
    public PageResponse<SupplierResponse> list(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return supplierService.search(search, pageable);
    }

    @GetMapping("/select")
    @Operation(summary = "Liste simplifiee pour les listes deroulantes")
    public List<SupplierResponse> select() {
        return supplierService.findAllForSelect();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un fournisseur")
    public SupplierResponse get(@PathVariable Long id) {
        return supplierService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Creer un fournisseur")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        SupplierResponse created = supplierService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/suppliers/{id}").build(created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un fournisseur")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un fournisseur")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        supplierService.delete(id);
    }
}

package com.creditflow.product.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.dto.StockMovementResponse;
import com.creditflow.product.service.ProductService;
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
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Produits", description = "Catalogue des produits vendus a credit")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "Lister et rechercher les produits")
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) ProductStatus status,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return productService.search(search, category, status, pageable);
    }

    @GetMapping("/select")
    @Operation(summary = "Liste simplifiee pour les listes deroulantes")
    public List<ProductResponse> select() {
        return productService.findAllForSelect();
    }

    @GetMapping("/categories")
    @Operation(summary = "Categories existantes")
    public List<String> categories() {
        return productService.categories();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un produit")
    public ProductResponse get(@PathVariable Long id) {
        return productService.findById(id);
    }

    @GetMapping("/{id}/stock-movements")
    @Operation(summary = "Historique des mouvements de stock d'un produit")
    public List<StockMovementResponse> stockMovements(@PathVariable Long id) {
        return productService.stockMovements(id);
    }

    @PostMapping
    @Operation(summary = "Creer un produit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        ProductResponse created = productService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/products/{id}").build(created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un produit")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un produit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }
}

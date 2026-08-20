package com.creditflow.supplier.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.supplier.dto.StockReceptionRequest;
import com.creditflow.supplier.dto.StockReceptionResponse;
import com.creditflow.supplier.service.StockReceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/stock-receptions")
@RequiredArgsConstructor
@Tag(name = "Receptions de stock", description = "Achats fournisseurs et reception de stock")
public class StockReceptionController {

    private final StockReceptionService stockReceptionService;

    @GetMapping
    @Operation(summary = "Lister les receptions de stock")
    public PageResponse<StockReceptionResponse> list(
            @RequestParam(required = false) Long supplierId,
            @PageableDefault(size = 20, sort = "receivedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockReceptionService.search(supplierId, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une reception de stock")
    public StockReceptionResponse get(@PathVariable Long id) {
        return stockReceptionService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Enregistrer une reception de stock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StockReceptionResponse> create(@Valid @RequestBody StockReceptionRequest request,
                                                          UriComponentsBuilder uriBuilder) {
        StockReceptionResponse created = stockReceptionService.receive(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/stock-receptions/{id}").build(created.id()))
                .body(created);
    }
}

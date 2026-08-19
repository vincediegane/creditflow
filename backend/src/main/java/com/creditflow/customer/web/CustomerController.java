package com.creditflow.customer.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.customer.dto.CustomerDetailResponse;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.customer.service.CustomerProfileService;
import com.creditflow.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "Gestion des clients de la boutique")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerProfileService customerProfileService;

    @GetMapping
    @Operation(summary = "Lister et rechercher les clients")
    public PageResponse<CustomerResponse> list(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return customerService.search(search, pageable);
    }

    @GetMapping("/select")
    @Operation(summary = "Liste simplifiee pour les listes deroulantes")
    public List<CustomerResponse> select() {
        return customerService.findAllForSelect();
    }

    @GetMapping("/quick-search")
    @Operation(summary = "Recherche rapide par nom ou telephone")
    public List<CustomerResponse> quickSearch(@RequestParam String q,
                                              @RequestParam(defaultValue = "10") int limit) {
        return customerService.quickSearch(q, limit);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un client")
    public CustomerResponse get(@PathVariable Long id) {
        return customerService.findById(id);
    }

    @GetMapping("/{id}/profile")
    @Operation(summary = "Fiche complete : achats, paiements et soldes")
    public CustomerDetailResponse profile(@PathVariable Long id) {
        return customerProfileService.profile(id);
    }

    @PostMapping
    @Operation(summary = "Creer un client")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        CustomerResponse created = customerService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/customers/{id}").build(created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un client")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Televerser la photo du client")
    public CustomerResponse uploadPhoto(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return customerService.uploadPhoto(id, file);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un client")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        customerService.delete(id);
    }
}

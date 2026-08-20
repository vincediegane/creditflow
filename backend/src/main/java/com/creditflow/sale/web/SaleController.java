package com.creditflow.sale.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.sale.domain.SaleAttachmentType;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.dto.SaleAttachmentResponse;
import com.creditflow.sale.dto.SaleDetailResponse;
import com.creditflow.sale.dto.SalePreviewRequest;
import com.creditflow.sale.dto.SalePreviewResponse;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.sale.service.InstallmentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
@Tag(name = "Ventes a credit", description = "Contrats de vente a credit et echeanciers")
public class SaleController {

    private final CreditSaleService creditSaleService;
    private final InstallmentService installmentService;
    private final PaymentService paymentService;

    @GetMapping
    @Operation(summary = "Lister et rechercher les contrats")
    public PageResponse<SaleResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) Long customerId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return creditSaleService.search(search, status, customerId, pageable);
    }

    @PostMapping("/preview")
    @Operation(summary = "Simuler un echeancier avant enregistrement")
    public SalePreviewResponse preview(@Valid @RequestBody SalePreviewRequest request) {
        return creditSaleService.preview(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un contrat")
    public SaleResponse get(@PathVariable Long id) {
        return creditSaleService.findById(id);
    }

    @GetMapping("/{id}/detail")
    @Operation(summary = "Contrat + echeances + paiements")
    public SaleDetailResponse detail(@PathVariable Long id) {
        return creditSaleService.findDetail(id);
    }

    @GetMapping("/{id}/installments")
    @Operation(summary = "Echeances d'un contrat")
    public List<InstallmentResponse> installments(@PathVariable Long id) {
        return installmentService.bySale(id);
    }

    @GetMapping("/{id}/payments")
    @Operation(summary = "Paiements d'un contrat")
    public List<PaymentResponse> payments(@PathVariable Long id) {
        return paymentService.findBySale(id);
    }

    @PostMapping
    @Operation(summary = "Creer une vente a credit (echeancier genere automatiquement)")
    public ResponseEntity<SaleResponse> create(@Valid @RequestBody CreateSaleRequest request,
                                               UriComponentsBuilder uriBuilder) {
        SaleResponse created = creditSaleService.create(request);
        return ResponseEntity
                .created(uriBuilder.path("/api/sales/{id}").build(created.id()))
                .body(created);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Annuler un contrat")
    @PreAuthorize("hasRole('ADMIN')")
    public SaleResponse cancel(@PathVariable Long id) {
        return creditSaleService.cancel(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un contrat sans paiement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        creditSaleService.delete(id);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attacher une piece d'identite ou une signature au contrat")
    public SaleAttachmentResponse uploadAttachment(@PathVariable Long id,
                                                    @RequestParam SaleAttachmentType type,
                                                    @RequestPart("file") MultipartFile file) {
        return creditSaleService.uploadAttachment(id, type, file);
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @Operation(summary = "Supprimer une piece jointe du contrat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        creditSaleService.deleteAttachment(id, attachmentId);
    }
}

package com.creditflow.payment.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Paiements", description = "Enregistrement et suivi des versements")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    @Operation(summary = "Lister, filtrer et rechercher les paiements")
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long customerId,
            @PageableDefault(size = 20, sort = "paymentDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return paymentService.search(search, method, from, to, customerId, pageable);
    }

    @GetMapping("/methods")
    @Operation(summary = "Modes de paiement disponibles")
    public List<PaymentMethod> methods() {
        return List.of(PaymentMethod.values());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un paiement")
    public PaymentResponse get(@PathVariable Long id) {
        return paymentService.findById(id);
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Recu de paiement au format PDF, a remettre au client")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        PaymentService.Receipt receipt = paymentService.receipt(id);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + receipt.fileName() + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(receipt.content());
    }

    @PostMapping
    @Operation(summary = "Enregistrer un versement (idempotent via clientRequestId)")
    public ResponseEntity<PaymentResponse> register(@Valid @RequestBody PaymentRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        PaymentService.RegistrationResult result = paymentService.register(request);
        if (result.replayed()) {
            // 200 sans Location : rien n'a ete cree, le versement etait deja enregistre.
            return ResponseEntity.ok(result.payment());
        }
        return ResponseEntity
                .created(uriBuilder.path("/api/payments/{id}").build(result.payment().id()))
                .body(result.payment());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Annuler un versement et recalculer le contrat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        paymentService.delete(id);
    }
}

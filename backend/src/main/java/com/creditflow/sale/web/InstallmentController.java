package com.creditflow.sale.web;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.service.InstallmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/installments")
@RequiredArgsConstructor
@Tag(name = "Echeances", description = "Suivi des echeances et des retards")
public class InstallmentController {

    private final InstallmentService installmentService;

    @GetMapping
    @Operation(summary = "Lister, filtrer et rechercher les echeances")
    public PageResponse<InstallmentResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) InstallmentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean onlyLate,
            @PageableDefault(size = 20, sort = "dueDate", direction = Sort.Direction.ASC) Pageable pageable) {
        return installmentService.search(search, status, from, to, onlyLate, pageable);
    }

    @GetMapping("/upcoming")
    @Operation(summary = "Prochaines echeances (par defaut sur 30 jours)")
    public List<InstallmentResponse> upcoming(@RequestParam(defaultValue = "30") int days) {
        return installmentService.upcoming(days);
    }

    @GetMapping("/late")
    @Operation(summary = "Echeances en retard")
    public List<InstallmentResponse> late() {
        return installmentService.late();
    }
}

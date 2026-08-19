package com.creditflow.penalty.web;

import com.creditflow.penalty.dto.PenaltySettingsRequest;
import com.creditflow.penalty.dto.PenaltySettingsResponse;
import com.creditflow.penalty.service.PenaltySettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/penalty-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Penalites de retard", description = "Reglage global de penalite, reserve a l'administrateur")
public class PenaltySettingsController {

    private final PenaltySettingsService penaltySettingsService;

    @GetMapping
    @Operation(summary = "Consulter les reglages de penalite")
    public PenaltySettingsResponse get() {
        return penaltySettingsService.get();
    }

    @PutMapping
    @Operation(summary = "Mettre a jour les reglages de penalite")
    public PenaltySettingsResponse update(@Valid @RequestBody PenaltySettingsRequest request) {
        return penaltySettingsService.update(request);
    }
}

package com.creditflow.dashboard.web;

import com.creditflow.dashboard.dto.DashboardResponse;
import com.creditflow.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Tableau de bord", description = "Indicateurs cles de la boutique")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    @Operation(summary = "Indicateurs, paiements du jour, prochaines echeances et clients en retard")
    public DashboardResponse overview() {
        return dashboardService.overview();
    }
}

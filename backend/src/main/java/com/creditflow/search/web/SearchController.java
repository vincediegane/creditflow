package com.creditflow.search.web;

import com.creditflow.search.dto.GlobalSearchResponse;
import com.creditflow.search.service.GlobalSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "Recherche", description = "Recherche globale nom / telephone / produit / contrat")
public class SearchController {

    private final GlobalSearchService globalSearchService;

    @GetMapping
    @Operation(summary = "Rechercher partout")
    public GlobalSearchResponse search(@RequestParam String q,
                                       @RequestParam(defaultValue = "8") int limit) {
        return globalSearchService.search(q, limit);
    }
}

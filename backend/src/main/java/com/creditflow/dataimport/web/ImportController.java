package com.creditflow.dataimport.web;

import com.creditflow.dataimport.dto.ImportReport;
import com.creditflow.dataimport.service.LegacyImportParser;
import com.creditflow.dataimport.service.LegacyImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Reprise de donnees",
        description = "Import des creances en cours depuis un fichier CSV ou Excel")
public class ImportController {

    private final LegacyImportService legacyImportService;

    @PostMapping(value = "/legacy-sales", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer des contrats en cours",
            description = "Appeler d'abord avec dryRun=true pour simuler. "
                    + "L'import reel est en tout-ou-rien : une seule ligne invalide "
                    + "annule l'ensemble.")
    public ImportReport importLegacySales(@RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "true") boolean dryRun) {
        return legacyImportService.importLegacySales(file, dryRun);
    }

    @GetMapping("/template")
    @Operation(summary = "Telecharger le modele de fichier de reprise (CSV)")
    public ResponseEntity<byte[]> template() {
        String header = String.join(";", LegacyImportParser.ALL_COLUMNS);
        String example = String.join("\n",
                header,
                "Amadou;Diallo;770000101;Medina Dakar;1234567890001;Commercant;"
                        + "Samsung Galaxy A15;Telephone;125000;25000;6;15/03/2026;40000",
                "Fatou;Ndiaye;770000102;Pikine;;Coiffeuse;"
                        + "HP 250 G9;Ordinateur;400000;100000;10;01/02/2026;90000");

        // BOM UTF-8 : sans lui, Excel affiche les accents de travers a l'ouverture.
        byte[] content = ("﻿" + example + "\n").getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"modele-reprise-creditflow.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    @GetMapping("/columns")
    @Operation(summary = "Colonnes attendues et leur caractere obligatoire")
    public Map<String, List<String>> columns() {
        return Map.of(
                "required", LegacyImportParser.REQUIRED_COLUMNS,
                "all", LegacyImportParser.ALL_COLUMNS);
    }
}

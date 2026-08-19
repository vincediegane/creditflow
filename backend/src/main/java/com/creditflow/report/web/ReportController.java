package com.creditflow.report.web;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.report.dto.ReportData;
import com.creditflow.report.dto.ReportType;
import com.creditflow.report.export.ReportExporter;
import com.creditflow.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Rapports", description = "Rapports operationnels et exports PDF / Excel")
public class ReportController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReportService reportService;
    private final List<ReportExporter> exporters;

    @GetMapping("/types")
    @Operation(summary = "Rapports disponibles")
    public List<ReportType> types() {
        return List.of(ReportType.values());
    }

    @GetMapping("/{type}")
    @Operation(summary = "Consulter un rapport")
    public ReportData report(@PathVariable ReportType type,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.build(type, from, to);
    }

    @GetMapping("/{type}/export")
    @Operation(summary = "Exporter un rapport au format pdf ou excel")
    public ResponseEntity<byte[]> export(
            @PathVariable ReportType type,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Map<String, ReportExporter> byFormat = exporters.stream()
                .collect(Collectors.toMap(ReportExporter::format, Function.identity()));

        ReportExporter exporter = byFormat.get(format.toLowerCase(Locale.ROOT));
        if (exporter == null) {
            throw new BusinessRuleException("Format d'export inconnu : " + format);
        }

        ReportData data = reportService.build(type, from, to);
        byte[] content = exporter.export(data);

        String filename = "%s-%s.%s".formatted(
                type.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                LocalDate.now().format(FILE_DATE),
                exporter.fileExtension());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(exporter.contentType()))
                .body(content);
    }
}

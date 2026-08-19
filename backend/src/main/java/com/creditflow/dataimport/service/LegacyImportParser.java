package com.creditflow.dataimport.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.dataimport.dto.ImportReport;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lit le fichier de reprise (CSV ou Excel) et convertit chaque ligne en
 * {@link LegacyRow}. Les erreurs sont collectees ligne par ligne plutot que
 * levees : le commercant doit voir tous ses problemes d'un coup.
 */
@Component
public class LegacyImportParser {

    public static final List<String> REQUIRED_COLUMNS =
            List.of("prenom", "nom", "telephone", "produit", "prix_total",
                    "nb_mensualites", "date_debut");

    public static final List<String> ALL_COLUMNS =
            List.of("prenom", "nom", "telephone", "adresse", "cni", "profession",
                    "produit", "categorie", "prix_total", "acompte", "nb_mensualites",
                    "date_debut", "deja_paye");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    public record ParseResult(List<LegacyRow> rows, List<ImportReport.RowError> errors,
                              int totalRows) {
    }

    public ParseResult parse(MultipartFile file) {
        List<Map<String, String>> raw = readRaw(file);

        List<LegacyRow> rows = new ArrayList<>();
        List<ImportReport.RowError> errors = new ArrayList<>();

        for (int i = 0; i < raw.size(); i++) {
            int line = i + 2; // ligne 1 = en-tete, pour coller au fichier vu par l'utilisateur
            Map<String, String> values = raw.get(i);
            if (values.values().stream().noneMatch(StringUtils::hasText)) {
                continue; // ligne vide
            }
            int before = errors.size();
            LegacyRow row = toRow(line, values, errors);
            if (row != null && errors.size() == before) {
                rows.add(row);
            }
        }
        return new ParseResult(rows, errors, raw.size());
    }

    // ------------------------------------------------------------------
    // Lecture brute
    // ------------------------------------------------------------------
    private List<Map<String, String>> readRaw(MultipartFile file) {
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx")) {
                return readExcel(file.getInputStream());
            }
            if (name.endsWith(".csv") || name.endsWith(".txt") || name.isEmpty()) {
                return readCsv(file.getInputStream());
            }
            throw new BusinessRuleException(
                    "Format non supporte : utilisez un fichier .csv ou .xlsx");
        } catch (IOException ex) {
            throw new BusinessRuleException("Le fichier n'a pas pu etre lu");
        }
    }

    private List<Map<String, String>> readCsv(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new BusinessRuleException("Le fichier est vide");
            }
            headerLine = stripBom(headerLine);
            char separator = headerLine.chars().filter(c -> c == ';').count()
                    >= headerLine.chars().filter(c -> c == ',').count() ? ';' : ',';

            List<String> headers = splitLine(headerLine, separator).stream()
                    .map(this::normalizeHeader).toList();
            assertHeaders(headers);

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cells = splitLine(line, separator);
                Map<String, String> row = new HashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    row.put(headers.get(c), c < cells.size() ? cells.get(c).trim() : "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, String>> readExcel(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.FRANCE);

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BusinessRuleException("Le fichier est vide");
            }
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                headers.add(normalizeHeader(formatter.formatCellValue(headerRow.getCell(c))));
            }
            assertHeaders(headers);

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row sheetRow = sheet.getRow(r);
                Map<String, String> row = new HashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = sheetRow == null ? null : sheetRow.getCell(c);
                    String value = formatter.formatCellValue(cell).trim();
                    // Excel rend souvent les dates au format local : on garde le texte tel quel
                    row.put(headers.get(c), value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void assertHeaders(List<String> headers) {
        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(required -> !headers.contains(required))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException(
                    "Colonnes obligatoires absentes du fichier : " + String.join(", ", missing)
                            + ". Telechargez le modele pour repartir sur la bonne structure.");
        }
    }

    // ------------------------------------------------------------------
    // Conversion + controles
    // ------------------------------------------------------------------
    private LegacyRow toRow(int line, Map<String, String> values,
                            List<ImportReport.RowError> errors) {
        String firstName = required(line, values, "prenom", errors);
        String lastName = required(line, values, "nom", errors);
        String phone = required(line, values, "telephone", errors);
        String product = required(line, values, "produit", errors);

        BigDecimal totalPrice = amount(line, values, "prix_total", errors, true);
        BigDecimal downPayment = amount(line, values, "acompte", errors, false);
        BigDecimal alreadyPaid = amount(line, values, "deja_paye", errors, false);
        Integer months = count(line, values, "nb_mensualites", errors);
        LocalDate startDate = date(line, values, "date_debut", errors);

        if (firstName == null || lastName == null || phone == null || product == null
                || totalPrice == null || months == null || startDate == null) {
            return null;
        }
        downPayment = downPayment == null ? BigDecimal.ZERO : downPayment;
        alreadyPaid = alreadyPaid == null ? BigDecimal.ZERO : alreadyPaid;

        // Coherence metier : ce sont les erreurs les plus frequentes dans un cahier.
        if (downPayment.compareTo(totalPrice) >= 0) {
            errors.add(new ImportReport.RowError(line, "acompte", downPayment.toPlainString(),
                    "L'acompte doit etre inferieur au prix total"));
            return null;
        }
        BigDecimal financed = totalPrice.subtract(downPayment);
        if (alreadyPaid.compareTo(financed) > 0) {
            errors.add(new ImportReport.RowError(line, "deja_paye", alreadyPaid.toPlainString(),
                    "Le montant deja paye (%s) depasse le montant a financer (%s)"
                            .formatted(alreadyPaid.toPlainString(), financed.toPlainString())));
            return null;
        }

        return new LegacyRow(line, firstName, lastName, phone,
                optional(values, "adresse"), optional(values, "cni"),
                optional(values, "profession"), product,
                StringUtils.hasText(optional(values, "categorie"))
                        ? optional(values, "categorie") : "Reprise",
                totalPrice, downPayment, months, startDate, alreadyPaid);
    }

    private String required(int line, Map<String, String> values, String column,
                            List<ImportReport.RowError> errors) {
        String value = values.get(column);
        if (!StringUtils.hasText(value)) {
            errors.add(new ImportReport.RowError(line, column, "", "Valeur obligatoire manquante"));
            return null;
        }
        return value.trim();
    }

    private String optional(Map<String, String> values, String column) {
        String value = values.get(column);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BigDecimal amount(int line, Map<String, String> values, String column,
                              List<ImportReport.RowError> errors, boolean mandatory) {
        String raw = values.get(column);
        if (!StringUtils.hasText(raw)) {
            if (mandatory) {
                errors.add(new ImportReport.RowError(line, column, "",
                        "Montant obligatoire manquant"));
            }
            return null;
        }
        // Tolere "1 250 000", "1250000,50", "1.250.000"
        String cleaned = raw.replace(" ", "").replace(" ", "")
                .replace("FCFA", "").replace("fcfa", "").trim();
        if (cleaned.matches(".*\\d[.]\\d{3}.*")) {
            cleaned = cleaned.replace(".", "");
        }
        cleaned = cleaned.replace(",", ".");
        try {
            BigDecimal parsed = new BigDecimal(cleaned);
            if (parsed.signum() < 0) {
                errors.add(new ImportReport.RowError(line, column, raw,
                        "Le montant ne peut pas etre negatif"));
                return null;
            }
            if (mandatory && parsed.signum() == 0) {
                errors.add(new ImportReport.RowError(line, column, raw,
                        "Le montant doit etre superieur a zero"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            errors.add(new ImportReport.RowError(line, column, raw, "Montant illisible"));
            return null;
        }
    }

    private Integer count(int line, Map<String, String> values, String column,
                          List<ImportReport.RowError> errors) {
        String raw = values.get(column);
        if (!StringUtils.hasText(raw)) {
            errors.add(new ImportReport.RowError(line, column, "",
                    "Nombre de mensualites obligatoire"));
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim().split("[.,]")[0]);
            if (parsed < 1 || parsed > 60) {
                errors.add(new ImportReport.RowError(line, column, raw,
                        "Le nombre de mensualites doit etre compris entre 1 et 60"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            errors.add(new ImportReport.RowError(line, column, raw,
                    "Nombre de mensualites illisible"));
            return null;
        }
    }

    private LocalDate date(int line, Map<String, String> values, String column,
                           List<ImportReport.RowError> errors) {
        String raw = values.get(column);
        if (!StringUtils.hasText(raw)) {
            errors.add(new ImportReport.RowError(line, column, "", "Date de debut obligatoire"));
            return null;
        }
        String cleaned = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format);
            } catch (Exception ignored) {
                // format suivant
            }
        }
        errors.add(new ImportReport.RowError(line, column, raw,
                "Date illisible (formats acceptes : 31/12/2025 ou 2025-12-31)"));
        return null;
    }

    // ------------------------------------------------------------------
    private List<String> splitLine(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    /** Accents et casse sont ignores : "Téléphone" et "telephone" sont equivalents. */
    private String normalizeHeader(String header) {
        return Normalizer.normalize(header == null ? "" : header.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('\'', '_');
    }

    private String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}

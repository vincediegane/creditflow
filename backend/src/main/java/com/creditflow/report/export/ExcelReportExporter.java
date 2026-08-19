package com.creditflow.report.export;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.report.dto.ReportData;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ExcelReportExporter implements ReportExporter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    public String format() {
        return "excel";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String fileExtension() {
        return "xlsx";
    }

    @Override
    public byte[] export(ReportData data) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(data.title()));

            CellStyle titleStyle = boldStyle(workbook, 14);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle moneyStyle = moneyStyle(workbook);
            CellStyle totalStyle = boldStyle(workbook, 11);

            int rowIndex = 0;

            Row titleRow = sheet.createRow(rowIndex++);
            titleRow.createCell(0).setCellValue(data.title());
            titleRow.getCell(0).setCellStyle(titleStyle);

            Row periodRow = sheet.createRow(rowIndex++);
            periodRow.createCell(0).setCellValue("Periode : " + data.period());

            Row generatedRow = sheet.createRow(rowIndex++);
            generatedRow.createCell(0).setCellValue("Genere le " + data.generatedAt().format(TIMESTAMP));

            rowIndex++;

            Row header = sheet.createRow(rowIndex++);
            List<ReportData.Column> columns = data.columns();
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(headerStyle);
            }

            for (List<Object> line : data.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < line.size(); i++) {
                    Cell cell = row.createCell(i);
                    writeValue(cell, line.get(i), moneyStyle);
                }
            }

            rowIndex++;
            for (ReportData.Total total : data.totals()) {
                Row row = sheet.createRow(rowIndex++);
                Cell label = row.createCell(0);
                label.setCellValue(total.label());
                label.setCellStyle(totalStyle);
                writeValue(row.createCell(1), total.value(), moneyStyle);
            }

            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessRuleException("Impossible de generer le fichier Excel");
        }
    }

    private void writeValue(Cell cell, Object value, CellStyle moneyStyle) {
        switch (value) {
            case null -> cell.setBlank();
            case BigDecimal number -> {
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(moneyStyle);
            }
            case Number number -> cell.setCellValue(number.doubleValue());
            default -> cell.setCellValue(String.valueOf(value));
        }
    }

    private CellStyle boldStyle(Workbook workbook, int size) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) size);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = boldStyle(workbook, 11);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle moneyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        return style;
    }

    private String sheetName(String title) {
        String name = title.replaceAll("[\\\\/*?\\[\\]:]", " ");
        return name.length() > 31 ? name.substring(0, 31) : name;
    }
}

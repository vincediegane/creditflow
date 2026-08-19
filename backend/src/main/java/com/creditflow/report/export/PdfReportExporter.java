package com.creditflow.report.export;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import com.creditflow.report.dto.ReportData;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PdfReportExporter implements ReportExporter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppProperties properties;

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public String fileExtension() {
        return "pdf";
    }

    @Override
    public byte[] export(ReportData data) {
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 32, 32);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.BLACK);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

            Paragraph shop = new Paragraph(properties.getShop().getName(), subtitleFont);
            shop.setSpacingAfter(2f);
            document.add(shop);

            Paragraph title = new Paragraph(data.title(), titleFont);
            title.setSpacingAfter(4f);
            document.add(title);

            Paragraph period = new Paragraph(
                    "Periode : %s   |   Genere le %s".formatted(data.period(), data.generatedAt().format(TIMESTAMP)),
                    subtitleFont);
            period.setSpacingAfter(12f);
            document.add(period);

            PdfPTable table = new PdfPTable(data.columns().size());
            table.setWidthPercentage(100);
            table.setHeaderRows(1);

            for (ReportData.Column column : data.columns()) {
                PdfPCell cell = new PdfPCell(new Phrase(column.label(), headerFont));
                cell.setBackgroundColor(new Color(33, 82, 156));
                cell.setPadding(6f);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }

            boolean alternate = false;
            for (java.util.List<Object> row : data.rows()) {
                for (int i = 0; i < row.size(); i++) {
                    Object value = row.get(i);
                    boolean numeric = value instanceof Number;
                    PdfPCell cell = new PdfPCell(new Phrase(formatValue(value), cellFont));
                    cell.setPadding(5f);
                    cell.setHorizontalAlignment(numeric ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                    if (alternate) {
                        cell.setBackgroundColor(new Color(244, 246, 250));
                    }
                    table.addCell(cell);
                }
                alternate = !alternate;
            }

            if (data.rows().isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("Aucune donnee pour cette periode", cellFont));
                empty.setColspan(data.columns().size());
                empty.setPadding(10f);
                empty.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(empty);
            }

            document.add(table);

            Paragraph spacer = new Paragraph(" ");
            document.add(spacer);

            for (ReportData.Total total : data.totals()) {
                Paragraph line = new Paragraph(
                        "%s : %s".formatted(total.label(), formatValue(total.value())), totalFont);
                line.setAlignment(Element.ALIGN_RIGHT);
                document.add(line);
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException ex) {
            throw new BusinessRuleException("Impossible de generer le fichier PDF");
        }
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal amount) {
            return amountFormat().format(amount) + " " + properties.getShop().getCurrency();
        }
        if (value instanceof Number number) {
            return amountFormat().format(number);
        }
        return String.valueOf(value);
    }

    private DecimalFormat amountFormat() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols);
    }
}

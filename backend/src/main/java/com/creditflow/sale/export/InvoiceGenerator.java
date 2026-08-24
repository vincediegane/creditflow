package com.creditflow.sale.export;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Facture remise au client apres la vente : recapitule le contrat et l'echeancier complet.
 */
@Component
@RequiredArgsConstructor
public class InvoiceGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Color HEADER = new Color(0x21, 0x52, 0x9C);
    private static final Color SOFT = new Color(0xF4, 0xF6, 0xFA);

    private final AppProperties properties;

    public byte[] generate(CreditSale sale, List<Installment> installments) {
        Document document = new Document(PageSize.A5, 30, 30, 32, 30);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter.getInstance(document, out);
            document.open();

            Font shopFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, HEADER);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);

            Paragraph shop = new Paragraph(properties.getShop().getName(), shopFont);
            shop.setSpacingAfter(2f);
            document.add(shop);

            Paragraph title = new Paragraph("FACTURE", titleFont);
            title.setSpacingAfter(2f);
            document.add(title);

            Paragraph reference = new Paragraph(
                    "N° %s   -   %s".formatted(invoiceNumber(sale), LocalDate.now().format(DATE)), smallFont);
            reference.setSpacingAfter(12f);
            document.add(reference);

            document.add(details(sale));

            Paragraph scheduleTitle = new Paragraph("Echeancier", titleFont);
            scheduleTitle.setSpacingBefore(14f);
            scheduleTitle.setSpacingAfter(6f);
            document.add(scheduleTitle);
            document.add(schedule(installments));

            Paragraph footer = new Paragraph(
                    "Merci pour votre confiance. Document a conserver.",
                    smallFont);
            footer.setSpacingBefore(14f);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new BusinessRuleException("Impossible de generer la facture");
        }
    }

    public String invoiceNumber(CreditSale sale) {
        return "FAC-%d-%05d".formatted(sale.getStartDate().getYear(), sale.getId());
    }

    private PdfPTable details(CreditSale sale) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{40f, 60f});

        row(table, "Client", sale.getCustomer().getFullName());
        row(table, "Telephone", sale.getCustomer().getPhone());
        row(table, "Contrat", sale.getReference());
        row(table, "Produit", sale.getProduct().getName());
        row(table, "Prix total", money(sale.getTotalPrice()));
        row(table, "Acompte", money(sale.getDownPayment()));
        row(table, "Montant finance", money(sale.getFinancedAmount()));
        row(table, "Mensualite", money(sale.getMonthlyAmount()));
        row(table, "Deja regle", money(sale.getDownPayment().add(sale.getAmountPaid())));
        row(table, "Reste a payer", money(sale.getRemainingAmount()), true);

        return table;
    }

    private PdfPTable schedule(List<Installment> installments) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{10f, 30f, 30f, 30f});

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        for (String header : new String[]{"N°", "Echeance", "Montant", "Statut"}) {
            PdfPCell headerCell = new PdfPCell(new Phrase(header, headerFont));
            headerCell.setBackgroundColor(HEADER);
            headerCell.setPadding(5f);
            table.addCell(headerCell);
        }

        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        for (Installment installment : installments) {
            addScheduleCell(table, String.valueOf(installment.getNumber()), cellFont);
            addScheduleCell(table, installment.getDueDate().format(DATE), cellFont);
            addScheduleCell(table, money(installment.getAmount()), cellFont);
            addScheduleCell(table, statusLabel(installment), cellFont);
        }

        return table;
    }

    private void addScheduleCell(PdfPTable table, String value, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        cell.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        cell.setPadding(5f);
        table.addCell(cell);
    }

    private String statusLabel(Installment installment) {
        return switch (installment.getStatus()) {
            case PENDING -> "En attente";
            case PARTIAL -> "Partiel";
            case PAID -> "Payée";
        };
    }

    private void row(PdfPTable table, String label, String value) {
        row(table, label, value, false);
    }

    private void row(PdfPTable table, String label, String value, boolean highlight) {
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
        Font valueFont = FontFactory.getFont(
                highlight ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 9, Color.BLACK);

        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        labelCell.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        labelCell.setPadding(5f);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        valueCell.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        valueCell.setPadding(5f);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols).format(amount == null ? BigDecimal.ZERO : amount)
                + " " + properties.getShop().getCurrency();
    }

    /** Nom de fichier propose au telechargement. */
    public String fileName(CreditSale sale) {
        return "facture-%s-%s.pdf".formatted(
                invoiceNumber(sale).toLowerCase(Locale.ROOT),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }
}

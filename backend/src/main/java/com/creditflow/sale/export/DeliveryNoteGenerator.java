package com.creditflow.sale.export;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import com.creditflow.sale.domain.CreditSale;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Bon de livraison remis au client lors de la remise du produit : preuve de livraison,
 * distincte de la facture (pas de donnee financiere ni d'echeancier).
 */
@Component
@RequiredArgsConstructor
public class DeliveryNoteGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Color HEADER = new Color(0x21, 0x52, 0x9C);
    private static final Color SOFT = new Color(0xF4, 0xF6, 0xFA);

    private final AppProperties properties;

    public byte[] generate(CreditSale sale) {
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

            Paragraph title = new Paragraph("BON DE LIVRAISON", titleFont);
            title.setSpacingAfter(2f);
            document.add(title);

            Paragraph reference = new Paragraph(
                    "N° %s   -   %s".formatted(deliveryNoteNumber(sale), LocalDate.now().format(DATE)), smallFont);
            reference.setSpacingAfter(12f);
            document.add(reference);

            document.add(details(sale));

            Paragraph signaturesTitle = new Paragraph("Signatures", titleFont);
            signaturesTitle.setSpacingBefore(14f);
            signaturesTitle.setSpacingAfter(6f);
            document.add(signaturesTitle);
            document.add(signatures());

            Paragraph footer = new Paragraph("Document a conserver.", smallFont);
            footer.setSpacingBefore(14f);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new BusinessRuleException("Impossible de generer le bon de livraison");
        }
    }

    public String deliveryNoteNumber(CreditSale sale) {
        return "BL-%d-%05d".formatted(sale.getStartDate().getYear(), sale.getId());
    }

    private PdfPTable details(CreditSale sale) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{40f, 60f});

        row(table, "Boutique", properties.getShop().getName());
        if (StringUtils.hasText(sale.getShop().getAddress())) {
            row(table, "Adresse", sale.getShop().getAddress());
        }
        row(table, "Contrat", sale.getReference());
        row(table, "Client", sale.getCustomer().getFullName());
        row(table, "Telephone", sale.getCustomer().getPhone());
        row(table, "Produit", sale.getProduct().getName());
        row(table, "Quantite livree", "1");
        row(table, "Date de livraison", sale.getStartDate().format(DATE));

        return table;
    }

    private PdfPTable signatures() {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50f, 50f});

        addSignatureCell(table, "Signature du client");
        addSignatureCell(table, "Signature du livreur / vendeur");

        return table;
    }

    private void addSignatureCell(PdfPTable table, String label) {
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);

        PdfPCell line = new PdfPCell(new Phrase(" ", labelFont));
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        line.setFixedHeight(50f);
        line.setPadding(5f);

        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPadding(5f);
        labelCell.setPaddingTop(4f);

        PdfPTable cell = new PdfPTable(1);
        cell.addCell(line);
        cell.addCell(labelCell);

        PdfPCell wrapper = new PdfPCell(cell);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(5f);
        table.addCell(wrapper);
    }

    private void row(PdfPTable table, String label, String value) {
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);

        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(Rectangle.BOTTOM);
        labelCell.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        labelCell.setPadding(5f);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBorder(Rectangle.BOTTOM);
        valueCell.setBorderColor(new Color(0xE0, 0xE5, 0xEE));
        valueCell.setPadding(5f);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    /** Nom de fichier propose au telechargement. */
    public String fileName(CreditSale sale) {
        return "bon-livraison-%s-%s.pdf".formatted(
                deliveryNoteNumber(sale).toLowerCase(Locale.ROOT),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }
}

package com.creditflow.payment.export;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import com.creditflow.payment.domain.Payment;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Recu remis au client apres un versement.
 *
 * <p>Le client qui paie en especes attend une preuve : sans elle, la parole du
 * commercant s'oppose a celle du client en cas de litige.</p>
 */
@Component
@RequiredArgsConstructor
public class PaymentReceiptGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Color HEADER = new Color(0x21, 0x52, 0x9C);
    private static final Color SOFT = new Color(0xF4, 0xF6, 0xFA);

    private final AppProperties properties;

    public byte[] generate(Payment payment, List<Installment> installments) {
        CreditSale sale = payment.getSale();
        Document document = new Document(PageSize.A5, 30, 30, 32, 30);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter.getInstance(document, out);
            document.open();

            Font shopFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, HEADER);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);
            Font amountFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, HEADER);

            Paragraph shop = new Paragraph(properties.getShop().getName(), shopFont);
            shop.setSpacingAfter(2f);
            document.add(shop);

            Paragraph title = new Paragraph("RECU DE PAIEMENT", titleFont);
            title.setSpacingAfter(2f);
            document.add(title);

            Paragraph reference = new Paragraph(
                    "N° %s   -   %s".formatted(receiptNumber(payment),
                            payment.getPaymentDate().format(DATE)), smallFont);
            reference.setSpacingAfter(12f);
            document.add(reference);

            // --- Montant encaisse, mis en evidence ---
            PdfPTable amountBox = new PdfPTable(1);
            amountBox.setWidthPercentage(100);
            PdfPCell amountCell = new PdfPCell();
            amountCell.setBackgroundColor(SOFT);
            amountCell.setBorderColor(HEADER);
            amountCell.setPadding(10f);
            amountCell.addElement(new Paragraph("Montant recu", smallFont));
            Paragraph amountValue = new Paragraph(money(payment.getAmount()), amountFont);
            amountCell.addElement(amountValue);
            amountBox.addCell(amountCell);
            amountBox.setSpacingAfter(14f);
            document.add(amountBox);

            // --- Details ---
            document.add(details(sale, payment, installments));

            Paragraph footer = new Paragraph(
                    "Merci pour votre confiance. Conservez ce recu comme preuve de paiement.",
                    smallFont);
            footer.setSpacingBefore(14f);
            document.add(footer);

            Paragraph signature = new Paragraph("\n\nSignature du vendeur : ____________________",
                    smallFont);
            signature.setAlignment(Element.ALIGN_RIGHT);
            document.add(signature);

            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new BusinessRuleException("Impossible de generer le recu");
        }
    }

    public String receiptNumber(Payment payment) {
        return "REC-%d-%05d".formatted(payment.getPaymentDate().getYear(), payment.getId());
    }

    private PdfPTable details(CreditSale sale, Payment payment, List<Installment> installments) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{40f, 60f});

        row(table, "Client", sale.getCustomer().getFullName());
        row(table, "Telephone", sale.getCustomer().getPhone());
        row(table, "Contrat", sale.getReference());
        row(table, "Produit", sale.getProduct().getName());
        row(table, "Mode de paiement", methodLabel(payment));
        if (payment.getReference() != null && !payment.getReference().isBlank()) {
            row(table, "Reference", payment.getReference());
        }
        row(table, "Prix total", money(sale.getTotalPrice()));
        row(table, "Deja regle", money(sale.getDownPayment().add(sale.getAmountPaid())));
        row(table, "Reste a payer", money(sale.getRemainingAmount()), true);

        Optional<Installment> next = installments.stream()
                .filter(installment -> !installment.isSettled())
                .min(Comparator.comparing(Installment::getDueDate));
        row(table, "Prochaine echeance", next
                .map(installment -> "%s  (%s)".formatted(installment.getDueDate().format(DATE),
                        money(installment.getRemaining())))
                .orElse("Contrat solde"));

        return table;
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

    private String methodLabel(Payment payment) {
        return switch (payment.getMethod()) {
            case CASH -> "Especes";
            case MOBILE_MONEY -> "Mobile Money";
            case BANK_TRANSFER -> "Virement";
            case CHECK -> "Cheque";
            case CARD -> "Carte";
        };
    }

    private String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols).format(amount == null ? BigDecimal.ZERO : amount)
                + " " + properties.getShop().getCurrency();
    }

    /** Nom de fichier propose au telechargement. */
    public String fileName(Payment payment) {
        return "recu-%s-%s.pdf".formatted(
                receiptNumber(payment).toLowerCase(Locale.ROOT),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }
}

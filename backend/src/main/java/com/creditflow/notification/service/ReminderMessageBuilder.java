package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Construit le texte de relance a partir d'un gabarit a variables.
 *
 * <p>Variables supportees : {{nom}}, {{montant}}, {{devise}}, {{jour_debut}},
 * {{jour_fin}}, {{boutique}}, {{produit}}, {{reference}}, {{echeance}},
 * {{retard}}, {{reste}}.</p>
 */
@Component
@RequiredArgsConstructor
public class ReminderMessageBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AppProperties properties;

    public record ReminderContext(
            String customerName,
            String productName,
            String saleReference,
            BigDecimal amount,
            BigDecimal remainingAmount,
            LocalDate dueDate,
            long daysLate
    ) {
    }

    public String build(ReminderContext context, String customTemplate) {
        String template = StringUtils.hasText(customTemplate)
                ? customTemplate
                : properties.getReminder().getDefaultTemplate();

        Map<String, String> values = new HashMap<>();
        values.put("nom", nullToEmpty(context.customerName()));
        values.put("produit", nullToEmpty(context.productName()));
        values.put("reference", nullToEmpty(context.saleReference()));
        values.put("montant", formatAmount(context.amount()));
        values.put("reste", formatAmount(context.remainingAmount()));
        values.put("devise", properties.getShop().getCurrency());
        values.put("boutique", properties.getShop().getName());
        values.put("jour_debut", String.valueOf(properties.getReminder().getWindowStartDay()));
        values.put("jour_fin", String.valueOf(properties.getReminder().getWindowEndDay()));
        values.put("echeance", context.dueDate() == null ? "" : context.dueDate().format(DATE_FORMAT));
        values.put("retard", String.valueOf(context.daysLate()));

        String message = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return message.trim();
    }

    /** Formate 125000 en "125 000" (espace insecable evite pour faciliter le copier-coller). */
    public String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols).format(amount);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

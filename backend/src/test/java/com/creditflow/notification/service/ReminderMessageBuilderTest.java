package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderMessageBuilderTest {

    private ReminderMessageBuilder builder;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getShop().setName("Boutique Test");
        properties.getShop().setCurrency("FCFA");
        properties.getReminder().setWindowStartDay(1);
        properties.getReminder().setWindowEndDay(10);
        properties.getReminder().setDefaultTemplate("""
                Bonjour {{nom}},
                Nous vous rappelons que votre versement mensuel de {{montant}} {{devise}} est attendu entre le {{jour_debut}} et le {{jour_fin}} du mois.
                Merci pour votre confiance.""");
        builder = new ReminderMessageBuilder(properties);
    }

    @Test
    @DisplayName("remplace les variables du gabarit par defaut")
    void buildsDefaultMessage() {
        String message = builder.build(context(new BigDecimal("125000")), null);

        assertThat(message).contains("Bonjour Amadou Diallo,");
        assertThat(message).contains("125 000 FCFA");
        assertThat(message).contains("entre le 1 et le 10 du mois");
        assertThat(message).doesNotContain("{{");
    }

    @Test
    @DisplayName("accepte un gabarit personnalise avec toutes les variables")
    void buildsCustomMessage() {
        String template = "{{nom}} | {{produit}} | {{reference}} | {{echeance}} | {{retard}} j | "
                + "reste {{reste}} {{devise}} | {{boutique}}";

        String message = builder.build(context(new BigDecimal("50000")), template);

        assertThat(message).isEqualTo(
                "Amadou Diallo | iPhone 13 | VC-2026-00001 | 05/07/2026 | 12 j | reste 300 000 FCFA | Boutique Test");
    }

    @Test
    @DisplayName("formate les montants avec un separateur de milliers")
    void formatsAmounts() {
        assertThat(builder.formatAmount(new BigDecimal("1250000"))).isEqualTo("1 250 000");
        assertThat(builder.formatAmount(null)).isEqualTo("0");
    }

    private ReminderMessageBuilder.ReminderContext context(BigDecimal amount) {
        return new ReminderMessageBuilder.ReminderContext(
                "Amadou Diallo",
                "iPhone 13",
                "VC-2026-00001",
                amount,
                new BigDecimal("300000"),
                LocalDate.of(2026, 7, 5),
                12L);
    }
}

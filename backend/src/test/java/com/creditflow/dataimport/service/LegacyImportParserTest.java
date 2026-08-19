package com.creditflow.dataimport.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.dataimport.dto.ImportReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le fichier de reprise vient d'un cahier ou d'un tableur : il est rarement
 * propre. L'analyseur doit donc tolerer les formats courants et signaler
 * precisement ce qui bloque.
 */
class LegacyImportParserTest {

    private final LegacyImportParser parser = new LegacyImportParser();

    private static final String HEADER =
            "prenom;nom;telephone;adresse;cni;profession;produit;categorie;"
                    + "prix_total;acompte;nb_mensualites;date_debut;deja_paye";

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "reprise.csv", "text/csv",
                (HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("lit une ligne correcte")
    void readsAValidRow() {
        var result = parser.parse(csv(
                "Khady;Sy;771110001;Ouakam;5551;Restauratrice;Tecno Spark;Telephone;"
                        + "98000;18000;4;15/04/2026;40000"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).hasSize(1);

        LegacyRow row = result.rows().get(0);
        assertThat(row.customerFullName()).isEqualTo("Khady Sy");
        assertThat(row.totalPrice()).isEqualByComparingTo("98000");
        assertThat(row.financedAmount()).isEqualByComparingTo("80000");
        assertThat(row.remaining()).isEqualByComparingTo("40000");
        assertThat(row.startDate()).isEqualTo(LocalDate.of(2026, 4, 15));
    }

    @Test
    @DisplayName("tolere les montants ecrits avec des espaces et les dates ISO")
    void toleratesCommonFormats() {
        var result = parser.parse(csv(
                "Modou;Gaye;771110002;Thies;;Chauffeur;HP 250;Ordinateur;"
                        + "400 000;100000;10;2026-03-01;90 000"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().get(0).totalPrice()).isEqualByComparingTo("400000");
        assertThat(result.rows().get(0).alreadyPaid()).isEqualByComparingTo("90000");
        assertThat(result.rows().get(0).startDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("refuse un acompte superieur au prix")
    void rejectsDownPaymentAboveTotal() {
        var result = parser.parse(csv(
                "Awa;Fall;771110003;Dakar;;Enseignante;iPhone 13;Telephone;"
                        + "560000;600000;6;01/05/2026;0"));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement()
                .extracting(ImportReport.RowError::column, ImportReport.RowError::reason)
                .containsExactly("acompte", "L'acompte doit etre inferieur au prix total");
    }

    @Test
    @DisplayName("refuse un deja-paye superieur au montant finance")
    void rejectsOverpaidLegacyAmount() {
        var result = parser.parse(csv(
                "Ndeye;Sarr;771110005;Dakar;;Coiffeuse;Tecno;Telephone;"
                        + "98000;8000;6;01/05/2026;500000"));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement()
                .extracting(ImportReport.RowError::column).isEqualTo("deja_paye");
    }

    @Test
    @DisplayName("signale une date illisible et une valeur obligatoire manquante")
    void reportsUnreadableValues() {
        var result = parser.parse(csv(
                ";Ba;771110004;Dakar;;Menuisier;iPhone 15;Telephone;"
                        + "880000;80000;6;pas-une-date;0"));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).extracting(ImportReport.RowError::column)
                .contains("prenom", "date_debut");
    }

    @Test
    @DisplayName("indique le numero de ligne du fichier, en-tete comprise")
    void reportsTheUserVisibleLineNumber() {
        var result = parser.parse(csv(
                "Khady;Sy;771110001;Ouakam;;Restauratrice;Tecno;Telephone;"
                        + "98000;18000;4;15/04/2026;0\n"
                        + ";Sy;771110009;Ouakam;;Restauratrice;Tecno;Telephone;"
                        + "98000;18000;4;15/04/2026;0"));

        // La 2e ligne de donnees est la ligne 3 du fichier.
        assertThat(result.errors()).singleElement()
                .extracting(ImportReport.RowError::line).isEqualTo(3);
    }

    @Test
    @DisplayName("refuse un fichier dont les colonnes obligatoires manquent")
    void rejectsAFileWithMissingColumns() {
        MockMultipartFile file = new MockMultipartFile("file", "mauvais.csv", "text/csv",
                "nom;telephone\nSy;771110001".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("prenom")
                .hasMessageContaining("produit");
    }

    @Test
    @DisplayName("accepte les en-tetes accentues et ignore les lignes vides")
    void acceptsAccentedHeadersAndSkipsBlankLines() {
        String header = "Prénom;Nom;Téléphone;Adresse;CNI;Profession;Produit;Catégorie;"
                + "prix_total;acompte;nb_mensualites;date_debut;deja_paye";
        MockMultipartFile file = new MockMultipartFile("file", "reprise.csv", "text/csv",
                (header + "\nKhady;Sy;771110001;Ouakam;;Restauratrice;Tecno;Telephone;"
                        + "98000;18000;4;15/04/2026;0\n;;;;;;;;;;;;\n")
                        .getBytes(StandardCharsets.UTF_8));

        var result = parser.parse(file);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).hasSize(1);
    }
}

package com.creditflow.dataimport.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.util.Money;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.dataimport.dto.ImportReport;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.product.domain.Product;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.repository.ProductRepository;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reprise des creances en cours d'une boutique qui tenait ses comptes sur cahier.
 *
 * <p>Deux garde-fous volontaires :</p>
 * <ul>
 *   <li><b>Simulation obligatoire</b> : le premier appel se fait toujours en
 *       {@code dryRun}, ce qui permet de corriger le fichier avant d'ecrire.</li>
 *   <li><b>Tout ou rien</b> : si une seule ligne est invalide, rien n'est importe.
 *       Un fichier a moitie repris serait pire que pas de reprise du tout.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyImportService {

    private static final String IMPORT_REFERENCE = "REPRISE";

    private final LegacyImportParser parser;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CreditSaleService creditSaleService;
    private final PaymentService paymentService;
    private final CurrentShopContext currentShopContext;
    private final ShopRepository shopRepository;

    @Transactional
    public ImportReport importLegacySales(MultipartFile file, boolean dryRun) {
        Long targetShopId = currentShopContext.shopIdForCreation();
        LegacyImportParser.ParseResult parsed = parser.parse(file);
        List<ImportReport.RowError> errors = new ArrayList<>(parsed.errors());
        List<LegacyRow> rows = parsed.rows();

        // Doublons a l'interieur meme du fichier : un telephone identifie un client,
        // mais le meme client peut avoir plusieurs contrats -> on ne bloque pas.
        detectConflictingDuplicates(rows, errors);

        List<ImportReport.RowPreview> preview = new ArrayList<>();
        Map<String, Boolean> customerIsNew = new LinkedHashMap<>();
        Map<String, Boolean> productIsNew = new LinkedHashMap<>();

        for (LegacyRow row : rows) {
            boolean newCustomer = !customerRepository.existsByPhone(row.phone())
                    && !customerIsNew.containsKey(row.phone());
            customerIsNew.putIfAbsent(row.phone(), newCustomer);

            String productKey = row.productName().toLowerCase(Locale.ROOT);
            boolean newProduct = findProductByName(row.productName()).isEmpty()
                    && !productIsNew.containsKey(productKey);
            productIsNew.putIfAbsent(productKey, newProduct);

            preview.add(new ImportReport.RowPreview(
                    row.line(), row.customerFullName(), row.phone(),
                    Boolean.TRUE.equals(customerIsNew.get(row.phone())),
                    row.productName(), Boolean.TRUE.equals(productIsNew.get(productKey)),
                    row.totalPrice(), row.downPayment(), row.installmentCount(),
                    row.startDate().toString(), row.alreadyPaid(), row.remaining()));
        }

        ImportReport report = summarize(dryRun, false, parsed.totalRows(), rows, errors, preview,
                customerIsNew, productIsNew, null);

        if (dryRun) {
            return report.errors().isEmpty()
                    ? withMessage(report, "Simulation reussie : le fichier peut etre importe.")
                    : withMessage(report, "Simulation : %d ligne(s) a corriger avant import."
                            .formatted(report.errors().size()));
        }

        if (!errors.isEmpty()) {
            throw new BusinessRuleException(
                    "Import refuse : %d ligne(s) invalide(s). Corrigez le fichier puis relancez "
                            .formatted(errors.size())
                            + "la simulation. Aucune donnee n'a ete modifiee.");
        }
        if (rows.isEmpty()) {
            throw new BusinessRuleException("Le fichier ne contient aucune ligne exploitable.");
        }

        return applyImport(rows, parsed.totalRows(), preview, customerIsNew, productIsNew, targetShopId);
    }

    // ------------------------------------------------------------------
    // Ecriture effective
    // ------------------------------------------------------------------
    private ImportReport applyImport(List<LegacyRow> rows, int totalRows,
                                     List<ImportReport.RowPreview> preview,
                                     Map<String, Boolean> customerIsNew,
                                     Map<String, Boolean> productIsNew,
                                     Long targetShopId) {
        Map<String, Customer> customersByPhone = new HashMap<>();
        Map<String, Product> productsByName = new HashMap<>();
        int payments = 0;

        for (LegacyRow row : rows) {
            Customer customer = customersByPhone.computeIfAbsent(row.phone(),
                    phone -> resolveCustomer(row, targetShopId));
            Product product = productsByName.computeIfAbsent(
                    row.productName().toLowerCase(Locale.ROOT), key -> resolveProduct(row, targetShopId));

            SaleResponse sale = creditSaleService.create(new CreateSaleRequest(
                    customer.getId(), product.getId(), row.totalPrice(), row.downPayment(),
                    null, null,
                    row.installmentCount(), row.startDate(),
                    "Contrat repris depuis l'ancien suivi papier", null, null, null, null));

            if (Money.isPositive(row.alreadyPaid())) {
                LocalDate paymentDate = row.startDate().isAfter(LocalDate.now())
                        ? LocalDate.now() : row.startDate();
                paymentService.register(new PaymentRequest(
                        sale.id(), row.alreadyPaid(), paymentDate, PaymentMethod.CASH,
                        IMPORT_REFERENCE, "Cumul des versements deja recus avant la reprise"));
                payments++;
            }
        }

        log.info("Reprise terminee : {} contrats crees, {} versements enregistres.",
                rows.size(), payments);

        ImportReport report = summarize(false, true, totalRows, rows, List.of(), preview,
                customerIsNew, productIsNew, payments);
        return withMessage(report,
                "Reprise terminee : %d contrat(s) importe(s).".formatted(rows.size()));
    }

    private Customer resolveCustomer(LegacyRow row, Long targetShopId) {
        Optional<Customer> existing = customerRepository.findByPhone(row.phone());
        if (existing.isPresent()) {
            Customer customer = existing.get();
            if (!customer.getShop().getId().equals(targetShopId)) {
                throw new BusinessRuleException(
                        "Le telephone %s appartient deja a un client d'une autre boutique".formatted(row.phone()));
            }
            return customer;
        }
        return customerRepository.save(Customer.builder()
                .firstName(row.firstName())
                .lastName(row.lastName())
                .phone(row.phone())
                .address(row.address())
                .cniNumber(row.cniNumber())
                .profession(row.profession())
                .notes("Client repris depuis l'ancien suivi papier")
                .active(true)
                .shop(shopRepository.getReferenceById(targetShopId))
                .build());
    }

    private Product resolveProduct(LegacyRow row, Long targetShopId) {
        return findProductByName(row.productName())
                .orElseGet(() -> productRepository.save(Product.builder()
                        .name(row.productName())
                        .category(row.category())
                        .cashPrice(Money.round(row.totalPrice()))
                        .creditPrice(Money.round(row.totalPrice()))
                        .stock(0)
                        .description("Produit cree automatiquement lors de la reprise")
                        .status(ProductStatus.OUT_OF_STOCK)
                        .shop(shopRepository.getReferenceById(targetShopId))
                        .build()));
    }

    private Optional<Product> findProductByName(String name) {
        return productRepository.findFirstByNameIgnoreCase(name.trim());
    }

    // ------------------------------------------------------------------
    /**
     * Un meme telephone associe a deux identites differentes est une erreur de
     * saisie classique dans un cahier : on la signale plutot que de creer un
     * client bancal.
     */
    private void detectConflictingDuplicates(List<LegacyRow> rows,
                                             List<ImportReport.RowError> errors) {
        Map<String, LegacyRow> byPhone = new HashMap<>();
        for (LegacyRow row : rows) {
            LegacyRow previous = byPhone.putIfAbsent(row.phone(), row);
            if (previous != null
                    && !previous.customerFullName().equalsIgnoreCase(row.customerFullName())) {
                errors.add(new ImportReport.RowError(row.line(), "telephone", row.phone(),
                        "Ce telephone est deja utilise ligne %d pour %s"
                                .formatted(previous.line(), previous.customerFullName())));
            }
        }
    }

    private ImportReport summarize(boolean dryRun, boolean applied, int totalRows,
                                   List<LegacyRow> rows, List<ImportReport.RowError> errors,
                                   List<ImportReport.RowPreview> preview,
                                   Map<String, Boolean> customerIsNew,
                                   Map<String, Boolean> productIsNew,
                                   Integer recordedPayments) {
        BigDecimal financed = rows.stream().map(LegacyRow::financedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = rows.stream().map(LegacyRow::alreadyPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int newCustomers = (int) customerIsNew.values().stream().filter(Boolean::booleanValue).count();
        int newProducts = (int) productIsNew.values().stream().filter(Boolean::booleanValue).count();
        int payments = recordedPayments != null ? recordedPayments
                : (int) rows.stream().filter(row -> Money.isPositive(row.alreadyPaid())).count();

        return new ImportReport(dryRun, applied, totalRows, rows.size(),
                newCustomers, customerIsNew.size() - newCustomers, newProducts,
                rows.size(), payments,
                financed, paid, financed.subtract(paid),
                errors, preview, null);
    }

    private ImportReport withMessage(ImportReport report, String message) {
        return new ImportReport(report.dryRun(), report.applied(), report.totalRows(),
                report.validRows(), report.newCustomers(), report.existingCustomers(),
                report.newProducts(), report.createdSales(), report.recordedPayments(),
                report.totalFinanced(), report.totalAlreadyPaid(), report.totalRemaining(),
                report.errors(), report.preview(), message);
    }
}

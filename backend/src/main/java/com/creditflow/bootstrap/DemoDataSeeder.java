package com.creditflow.bootstrap;

import com.creditflow.auth.domain.Role;
import com.creditflow.config.AppProperties;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.customer.repository.CustomerRepository;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.payment.domain.PaymentMethod;
import com.creditflow.payment.dto.PaymentRequest;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.product.domain.ProductStatus;
import com.creditflow.product.dto.ProductRequest;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Jeu de donnees de demonstration : 10 clients, 15 produits, 20 contrats et
 * 30 paiements, generes de maniere deterministe pour pouvoir presenter le
 * logiciel immediatement apres {@code docker compose up}.
 *
 * <p>Le seeding ne s'execute que si la base est vide et si
 * {@code app.demo.seed=true}.</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DemoDataSeeder {

    private static final int TARGET_PAYMENTS = 30;

    private final AppProperties properties;
    private final CustomerRepository customerRepository;
    private final ShopRepository shopRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final CreditSaleService creditSaleService;
    private final PaymentService paymentService;

    private record CustomerSeed(String firstName, String lastName, String phone, String address,
                                String cni, String profession) {
    }

    private record ProductSeed(String name, String category, long cashPrice, long creditPrice,
                               int stock, String description) {
    }

    @Bean
    @Order(2)
    public ApplicationRunner seedDemoData() {
        return args -> {
            if (!properties.getDemo().isSeed()) {
                log.info("Donnees de demonstration desactivees (app.demo.seed=false).");
                return;
            }
            if (customerRepository.count() > 0) {
                log.info("Donnees deja presentes : seeding ignore.");
                return;
            }
            List<Shop> shops = shopRepository.findAllByActiveTrueOrderByNameAsc();
            if (shops.size() != 1) {
                log.info("Installation multi-boutiques ({} boutiques actives) : seeding ignore.", shops.size());
                return;
            }

            // Les services de creation resolvent la boutique cible via CurrentShopContext, qui
            // exige un utilisateur authentifie : le seeding s'execute sous l'identite technique
            // de l'administrateur cree par AdminInitializer (@Order(1)).
            authenticateAsAdmin();
            try {
                log.info("Generation des donnees de demonstration pour la boutique {}...", shops.get(0).getName());
                List<CustomerResponse> customers = seedCustomers();
                List<ProductResponse> products = seedProducts();
                List<SaleResponse> sales = seedSales(customers, products);
                int payments = seedPayments(sales);

                log.info("Demonstration prete : {} clients, {} produits, {} contrats, {} paiements.",
                        customers.size(), products.size(), sales.size(), payments);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                properties.getAdmin().getUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.ADMIN.name()))));
    }

    private List<CustomerResponse> seedCustomers() {
        List<CustomerSeed> seeds = List.of(
                new CustomerSeed("Amadou", "Diallo", "770000001", "Medina, Dakar", "1234567890123", "Commerçant"),
                new CustomerSeed("Fatou", "Ndiaye", "770000002", "Parcelles Assainies, Dakar", "1234567890124", "Coiffeuse"),
                new CustomerSeed("Moussa", "Sow", "770000003", "Grand Yoff, Dakar", "1234567890125", "Chauffeur"),
                new CustomerSeed("Awa", "Fall", "770000004", "Guediawaye", "1234567890126", "Enseignante"),
                new CustomerSeed("Ibrahima", "Ba", "770000005", "Pikine", "1234567890127", "Menuisier"),
                new CustomerSeed("Mariama", "Sarr", "770000006", "Yoff, Dakar", "1234567890128", "Infirmiere"),
                new CustomerSeed("Cheikh", "Gueye", "770000007", "Thies", "1234567890129", "Mecanicien"),
                new CustomerSeed("Aissatou", "Diop", "770000008", "Rufisque", "1234567890130", "Commerçante"),
                new CustomerSeed("Ousmane", "Kane", "770000009", "Keur Massar", "1234567890131", "Tailleur"),
                new CustomerSeed("Ndeye", "Faye", "770000010", "Liberte 6, Dakar", "1234567890132", "Secretaire"));

        List<CustomerResponse> created = new ArrayList<>();
        for (CustomerSeed seed : seeds) {
            created.add(customerService.create(new CustomerRequest(
                    seed.firstName(), seed.lastName(), seed.phone(), seed.address(),
                    seed.cni(), seed.profession(), null, null, true)));
        }
        return created;
    }

    private List<ProductResponse> seedProducts() {
        List<ProductSeed> seeds = List.of(
                new ProductSeed("Samsung Galaxy A15", "Telephone", 95_000, 125_000, 12, "6.5 pouces, 128 Go"),
                new ProductSeed("Samsung Galaxy A35", "Telephone", 165_000, 210_000, 8, "6.6 pouces, 256 Go"),
                new ProductSeed("Samsung Galaxy S23", "Telephone", 420_000, 520_000, 4, "Haut de gamme, 256 Go"),
                new ProductSeed("iPhone 13", "Telephone", 450_000, 560_000, 5, "128 Go, reconditionne"),
                new ProductSeed("iPhone 15", "Telephone", 720_000, 880_000, 3, "128 Go, neuf"),
                new ProductSeed("Tecno Spark 20", "Telephone", 75_000, 98_000, 20, "128 Go, double SIM"),
                new ProductSeed("Infinix Hot 40", "Telephone", 85_000, 110_000, 15, "256 Go, batterie 5000 mAh"),
                new ProductSeed("Xiaomi Redmi Note 13", "Telephone", 130_000, 168_000, 10, "256 Go, AMOLED"),
                new ProductSeed("HP 250 G9", "Ordinateur", 320_000, 400_000, 7, "Core i3, 8 Go, 256 Go SSD"),
                new ProductSeed("HP ProBook 450", "Ordinateur", 520_000, 650_000, 4, "Core i5, 16 Go, 512 Go SSD"),
                new ProductSeed("Dell Latitude 5420", "Ordinateur", 480_000, 600_000, 5, "Core i5, 8 Go, 512 Go SSD"),
                new ProductSeed("Lenovo IdeaPad 3", "Ordinateur", 295_000, 375_000, 9, "Ryzen 5, 8 Go, 512 Go SSD"),
                new ProductSeed("MacBook Air M2", "Ordinateur", 950_000, 1_180_000, 2, "13 pouces, 8 Go, 256 Go"),
                new ProductSeed("Asus VivoBook 15", "Ordinateur", 340_000, 425_000, 6, "Core i5, 8 Go, 512 Go SSD"),
                new ProductSeed("Ecouteurs JBL Tune", "Accessoire", 25_000, 33_000, 30, "Bluetooth, sans fil"));

        List<ProductResponse> created = new ArrayList<>();
        for (ProductSeed seed : seeds) {
            created.add(productService.create(new ProductRequest(
                    seed.name(), seed.category(),
                    BigDecimal.valueOf(seed.cashPrice()), BigDecimal.valueOf(seed.creditPrice()),
                    seed.stock(), seed.description(), ProductStatus.ACTIVE)));
        }
        return created;
    }

    private List<SaleResponse> seedSales(List<CustomerResponse> customers, List<ProductResponse> products) {
        Random random = new Random(42);
        LocalDate today = LocalDate.now();
        List<SaleResponse> sales = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            CustomerResponse customer = customers.get(i % customers.size());
            ProductResponse product = products.get(random.nextInt(products.size()));

            BigDecimal price = product.creditPrice();
            BigDecimal downPayment = price
                    .multiply(BigDecimal.valueOf(10L + random.nextInt(3) * 5L))
                    .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP);

            int months = 3 + random.nextInt(10);
            LocalDate startDate = today.minusMonths(1L + random.nextInt(8)).withDayOfMonth(5);

            sales.add(creditSaleService.create(new CreateSaleRequest(
                    customer.id(), product.id(), price, downPayment, null, null, months, startDate,
                    "Contrat de demonstration", null, null, null, null)));
        }
        return sales;
    }

    private int seedPayments(List<SaleResponse> sales) {
        Random random = new Random(7);
        LocalDate today = LocalDate.now();
        int registered = 0;

        // Un premier versement sur la plupart des contrats, puis des versements
        // supplementaires pour obtenir des situations variees (a jour, en retard, solde).
        for (int round = 0; round < 4 && registered < TARGET_PAYMENTS; round++) {
            for (SaleResponse sale : sales) {
                if (registered >= TARGET_PAYMENTS) {
                    break;
                }
                // Quelques contrats restent volontairement impayes pour alimenter les relances.
                if (round > 0 && sale.id() % 4 == 0) {
                    continue;
                }

                SaleResponse current = creditSaleService.findById(sale.id());
                BigDecimal remaining = current.remainingAmount();
                if (remaining.compareTo(BigDecimal.ONE) < 0) {
                    continue;
                }

                BigDecimal amount = current.monthlyAmount().min(remaining);
                LocalDate paymentDate = current.startDate().plusMonths(round).plusDays(random.nextInt(6));
                if (paymentDate.isAfter(today)) {
                    paymentDate = today;
                }

                paymentService.register(new PaymentRequest(
                        sale.id(),
                        amount,
                        paymentDate,
                        randomMethod(random),
                        "REC-%05d".formatted(1000 + registered),
                        round == 0 ? "Premier versement" : null));
                registered++;
            }
        }
        return registered;
    }

    private PaymentMethod randomMethod(Random random) {
        PaymentMethod[] methods = {
                PaymentMethod.CASH, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY,
                PaymentMethod.MOBILE_MONEY, PaymentMethod.BANK_TRANSFER};
        return methods[random.nextInt(methods.length)];
    }
}

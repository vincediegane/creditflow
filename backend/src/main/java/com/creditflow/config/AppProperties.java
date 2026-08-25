package com.creditflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Cors cors = new Cors();
    private Storage storage = new Storage();
    private Admin admin = new Admin();
    private Shop shop = new Shop();
    private Reminder reminder = new Reminder();
    private Notification notification = new Notification();
    private Demo demo = new Demo();
    private Plan plan = new Plan();

    @Getter
    @Setter
    public static class Security {
        private Jwt jwt = new Jwt();
        /**
         * En mode strict (production), l'application refuse de demarrer si un secret
         * de livraison n'a pas ete remplace. Desactive uniquement pour la demonstration.
         */
        private boolean strict = true;
    }

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMinutes = 720;
        private String issuer = "creditflow";
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }

    @Getter
    @Setter
    public static class Storage {
        private String uploadDir = "./data/uploads";
        private String publicPath = "/uploads";
    }

    @Getter
    @Setter
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
        private String fullName = "Administrateur";
        /**
         * Impose au commercant de definir son propre mot de passe a la premiere
         * connexion : l'installateur ne doit pas conserver un acces.
         */
        private boolean forcePasswordChange = true;
    }

    @Getter
    @Setter
    public static class Shop {
        private String name = "CreditFlow Boutique";
        private String currency = "FCFA";
    }

    @Getter
    @Setter
    public static class Reminder {
        private String defaultTemplate = "";
        private int windowStartDay = 1;
        private int windowEndDay = 10;
    }

    @Getter
    @Setter
    public static class Notification {
        private String channel = "manual";
        private String defaultCountryCode = "+221";
        private Whatsapp whatsapp = new Whatsapp();
    }

    @Getter
    @Setter
    public static class Whatsapp {
        private String phoneNumberId;
        private String accessToken;
        private String apiBaseUrl = "https://graph.facebook.com";
        private String apiVersion = "v20.0";
        private String templateName = "relance_creditflow";
        private String templateLanguageCode = "fr";
    }

    @Getter
    @Setter
    public static class Demo {
        private boolean seed = true;
    }

    @Getter
    @Setter
    public static class Plan {
        /**
         * Formule Essentiel (une seule boutique) vs Pro/Multi-boutiques.
         * Defaut true : ne jamais bloquer retroactivement une instance existante.
         */
        private boolean multiShop = true;
        /**
         * Formule sans le canal WhatsApp automatique. Verifie au demarrage
         * par PlanConfigValidator, pas a l'execution (le canal est fige par bean Spring).
         */
        private boolean whatsappAuto = true;
    }
}

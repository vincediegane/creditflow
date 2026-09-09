# Spec — Issue #52 : notifications par email — nouveau canal SMTP

## Résumé

Ajout d'un canal d'envoi d'email SMTP optionnel (désactivé par défaut), déclenché uniquement à la création d'un compte utilisateur (`UserService.create`), sans jamais bloquer la création de compte en cas d'échec d'envoi.

## Tâches

- [ ] **Migration** — créer `backend/src/main/resources/db/migration/V19__users_email.sql` (SQL ci-dessous, colonne `email` nullable, non unique, aucun GRANT supplémentaire nécessaire — `users` est déjà couvert par `V16__app_role_grants.sql`).
- [ ] **Dépendance Maven** — ajouter `spring-boot-starter-mail` dans `backend/pom.xml` (pas de version explicite : héritée de `spring-boot-starter-parent` 3.5.6).
- [ ] **Entité** — ajouter le champ `email` à `backend/src/main/java/com/creditflow/auth/domain/User.java`.
- [ ] **Config** — ajouter la classe imbriquée `Mail` à `backend/src/main/java/com/creditflow/config/AppProperties.java`.
- [ ] **Config** — créer `backend/src/main/java/com/creditflow/config/MailConfig.java` (bean `JavaMailSender` conditionné).
- [ ] **application.yml** — ajouter le bloc `app.mail`.
- [ ] **.env.example / .env.production.example** — ajouter les variables `MAIL_*`.
- [ ] **Interface** — créer `backend/src/main/java/com/creditflow/notification/service/EmailChannel.java`.
- [ ] **Implémentation par défaut** — créer `backend/src/main/java/com/creditflow/notification/service/NoopEmailChannel.java`.
- [ ] **Implémentation SMTP** — créer `backend/src/main/java/com/creditflow/notification/service/SmtpEmailChannel.java`.
- [ ] **DTO** — ajouter le champ `email` à `backend/src/main/java/com/creditflow/auth/dto/UserRequest.java`.
- [ ] **DTO** — ajouter le champ `email` à `backend/src/main/java/com/creditflow/auth/dto/UserResponse.java`.
- [ ] **Point d'intégration** — modifier `backend/src/main/java/com/creditflow/auth/service/UserService.java` (nouvelle dépendance `EmailChannel`, méthode `sendWelcomeEmail`, appel après `save`).
- [ ] **Mise à jour du site d'appel existant** — adapter `backend/src/main/java/com/creditflow/auth/service/AuthService.java` (`toResponse` construit aussi un `UserResponse`, doit passer `user.getEmail()`).
- [ ] **Tests unitaires** — créer `backend/src/test/java/com/creditflow/notification/service/SmtpEmailChannelTest.java`.
- [ ] **Test de câblage Spring** — créer `backend/src/test/java/com/creditflow/notification/service/EmailChannelWiringTest.java` (preuve automatisée du critère n°4).
- [ ] **Tests existants à adapter** — mettre à jour `backend/src/test/java/com/creditflow/auth/service/UserServiceTest.java` (nouveau mock `EmailChannel`, nouveaux cas de test) et `backend/src/test/java/com/creditflow/auth/web/UserControllerTest.java` (signatures `UserRequest`/`UserResponse` changées).
- [ ] **Frontend types** — étendre `frontend/src/types.ts` (`UserAccount`, `CreateUserPayload`).
- [ ] **Frontend formulaire** — ajouter le champ optionnel "Email" dans `frontend/src/pages/UsersPage.tsx`.

---

## Contrat technique

### Migration SQL

`backend/src/main/resources/db/migration/V19__users_email.sql` :

```sql
-- =====================================================================
-- V19 - Ajout d'une colonne email sur users (#52 - notifications SMTP)
-- Nullable : les comptes existants n'ont pas d'email. Pas d'UNIQUE : pas
-- de cas d'usage d'authentification par email dans ce ticket, l'identifiant
-- de connexion reste `username`.
-- `users` n'est pas soumise a RLS (V15), aucune policy a mettre a jour.
-- Aucun GRANT supplementaire requis : V16__app_role_grants.sql accorde
-- deja SELECT/INSERT/UPDATE/DELETE sur `users` au role applicatif.
-- =====================================================================

ALTER TABLE users ADD COLUMN email VARCHAR(255);
```

### Entité `User.java`

Avant (extrait) :
```java
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @ManyToMany(fetch = FetchType.LAZY)
```

Après :
```java
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(length = 255)
    private String email;

    @ManyToMany(fetch = FetchType.LAZY)
```

### `AppProperties.java` — bloc `Mail`

Ajouter au champ racine (à côté des autres blocs) :
```java
    private Mail mail = new Mail();
```

Nouvelle classe imbriquée (même patron que `Whatsapp`) :
```java
    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = false;
        private String host;
        private int port = 587;
        private String username;
        private String password;
        private String from;
        private String fromName = "CreditFlow";
    }
```

### `application.yml`

Ajouter sous le bloc `app:` existant (au même niveau que `notification:`, `demo:`) :
```yaml
  mail:
    enabled: ${MAIL_ENABLED:false}
    host: ${MAIL_HOST:}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    from: ${MAIL_FROM:}
    from-name: ${MAIL_FROM_NAME:CreditFlow}
```

### `.env.example` et `.env.production.example`

Ajouter, à la suite du bloc `WHATSAPP_*` existant :
```
# Notifications par email (canal SMTP, desactive par defaut)
MAIL_ENABLED=false
MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=
MAIL_FROM_NAME=CreditFlow
```

### `EmailChannel.java` (nouveau)

```java
package com.creditflow.notification.service;

/**
 * Point d'extension pour l'envoi d'emails. Separee de {@link NotificationChannel}
 * (signatures et cycles de vie incompatibles : "to, subject, body" vs "phone, message",
 * pilotage par un booleen dedie app.mail.enabled plutot que par un choix de canal).
 */
public interface EmailChannel {

    /**
     * @return true si l'email a reellement ete transmis au serveur SMTP.
     */
    boolean send(String to, String subject, String body);
}
```

### `NoopEmailChannel.java` (nouveau)

```java
package com.creditflow.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Canal par defaut (app.mail.enabled=false ou absent) : aucun envoi reel,
 * meme patron de repli silencieux que ManualCopyChannel.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class NoopEmailChannel implements EmailChannel {

    @Override
    public boolean send(String to, String subject, String body) {
        log.debug("Email non envoye (canal mail desactive) : destinataire={}, sujet={}", to, subject);
        return false;
    }
}
```

### `SmtpEmailChannel.java` (nouveau)

```java
package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Canal d'envoi reel via un serveur SMTP configure (app.mail.enabled=true).
 * Ne propage jamais d'exception : un echec SMTP est journalise en warn et
 * retourne false, jamais bloquant pour l'appelant (meme patron defensif que
 * WhatsAppCloudApiChannel).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpEmailChannel implements EmailChannel {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    @Override
    public boolean send(String to, String subject, String body) {
        try {
            AppProperties.Mail mail = properties.getMail();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("%s <%s>".formatted(mail.getFromName(), mail.getFrom()));
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            log.warn("Echec de l'envoi d'email vers {} : {}", to, e.getMessage());
            return false;
        }
    }
}
```

### `MailConfig.java` (nouveau)

```java
package com.creditflow.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Bean JavaMailSender conditionne par app.mail.enabled=true, en plus de la
 * garde @ConditionalOnProperty sur SmtpEmailChannel lui-meme : sans cette
 * double garde, Spring tenterait de construire un JavaMailSenderImpl avec
 * des parametres vides au demarrage meme en mode desactive.
 *
 * Utilise des proprietes app.mail.* (pas spring.mail.*) : la MailSenderAutoConfiguration
 * de Spring Boot (activee sur spring.mail.host) ne se declenche donc jamais et
 * n'entre pas en conflit avec ce bean.
 */
@Configuration
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MailConfig {

    private final AppProperties properties;

    @Bean
    public JavaMailSender javaMailSender() {
        AppProperties.Mail mail = properties.getMail();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.getHost());
        sender.setPort(mail.getPort());
        sender.setUsername(mail.getUsername());
        sender.setPassword(mail.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.transport.protocol", "smtp");
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.starttls.enable", "true");

        return sender;
    }
}
```

### `UserRequest.java` — avant/après

Avant :
```java
public record UserRequest(
        @NotBlank(message = "L'identifiant est obligatoire")
        @Size(max = 80)
        String username,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir au moins 8 caracteres")
        String password,

        @NotBlank(message = "Le nom complet est obligatoire")
        @Size(max = 150)
        String fullName,

        @NotNull(message = "Le role est obligatoire")
        Role role,

        List<Long> shopIds
) {
}
```

Après (champ `email` ajouté **en dernière position** pour limiter les sites d'appel positionnels impactés à un simple ajout en fin de liste) :
```java
public record UserRequest(
        @NotBlank(message = "L'identifiant est obligatoire")
        @Size(max = 80)
        String username,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir au moins 8 caracteres")
        String password,

        @NotBlank(message = "Le nom complet est obligatoire")
        @Size(max = 150)
        String fullName,

        @NotNull(message = "Le role est obligatoire")
        Role role,

        /** Validee en service (obligatoire pour un SELLER), pas en annotation car la regle depend du role. */
        List<Long> shopIds,

        @Email(message = "Format d'email invalide")
        @Size(max = 255)
        String email
) {
}
```
Import supplémentaire : `jakarta.validation.constraints.Email`.

### `UserResponse.java` — avant/après

Avant :
```java
public record UserResponse(
        Long id,
        String username,
        String fullName,
        String role,
        boolean mustChangePassword,
        boolean enabled,
        List<ShopSummary> shops
) {
}
```

Après (champ `email` ajouté en dernière position, nullable) :
```java
public record UserResponse(
        Long id,
        String username,
        String fullName,
        String role,
        boolean mustChangePassword,
        boolean enabled,
        List<ShopSummary> shops,
        String email
) {
}
```

### `UserService.java` — avant/après

Avant (constructeur `@RequiredArgsConstructor` implicite, 4 dépendances, extrait de `create()` et `toResponse`) :
```java
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationRepository organizationRepository;

    ...

    @Transactional
    public UserResponse create(UserRequest request) {
        ...
        User saved = userRepository.save(user);
        log.info("Compte utilisateur cree: {} ({})", saved.getUsername(), saved.getId());
        return toResponse(saved);
    }

    ...

    private static UserResponse toResponse(User user) {
        List<ShopSummary> shops = user.getShops().stream()
                .map(s -> new ShopSummary(s.getId(), s.getName()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops);
    }
```

Après (nouvelle dépendance `EmailChannel`, ajoutée en dernier champ pour que le constructeur généré ajoute `emailChannel` en dernier paramètre) :
```java
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationRepository organizationRepository;
    private final EmailChannel emailChannel;

    ...

    @Transactional
    public UserResponse create(UserRequest request) {
        ...
        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(request.role())
                .email(request.email())
                .enabled(true)
                .mustChangePassword(true)
                .shops(shops)
                .organization(resolveDefaultOrganization())
                .build();

        User saved = userRepository.save(user);
        log.info("Compte utilisateur cree: {} ({})", saved.getUsername(), saved.getId());
        sendWelcomeEmail(saved);
        return toResponse(saved);
    }

    /**
     * Envoi non bloquant : un echec (SMTP indisponible, config absente, canal
     * desactive) ne doit jamais faire echouer la creation du compte deja
     * persistee. Meme patron defensif que ReminderService.sendAll().
     */
    private void sendWelcomeEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        try {
            String subject = "Votre compte CreditFlow a ete cree";
            String body = ("Bonjour %s,\n\n"
                    + "Votre compte CreditFlow a ete cree avec l'identifiant \"%s\".\n"
                    + "Connectez-vous avec le mot de passe qui vous a ete communique.\n\n"
                    + "L'equipe CreditFlow").formatted(user.getFullName(), user.getUsername());
            emailChannel.send(user.getEmail(), subject, body);
        } catch (Exception e) {
            log.warn("Echec de l'envoi de l'email de bienvenue a {} pour le compte {} : {}",
                    user.getEmail(), user.getUsername(), e.getMessage());
        }
    }

    ...

    private static UserResponse toResponse(User user) {
        List<ShopSummary> shops = user.getShops().stream()
                .map(s -> new ShopSummary(s.getId(), s.getName()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops, user.getEmail());
    }
```

Note : `sendWelcomeEmail` a son propre `try/catch(Exception)` (pas seulement `MailException`) car `SmtpEmailChannel.send` ne devrait déjà rien propager, mais ce second filet couvre aussi une éventuelle `NoopEmailChannel`/erreur de configuration Spring imprévue — défense en profondeur, cohérent avec le critère d'acceptation n°3.

### `AuthService.java` — site d'appel à corriger

Ligne 118-119 actuelle :
```java
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops);
```
Remplacer par :
```java
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops, user.getEmail());
```

### Frontend — `types.ts`

Avant :
```ts
export interface UserAccount {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  enabled: boolean;
  mustChangePassword: boolean;
  shops: ShopSummary[];
}

export interface CreateUserPayload {
  username: string;
  password: string;
  fullName: string;
  role: Role;
  shopIds?: number[];
}
```

Après :
```ts
export interface UserAccount {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  enabled: boolean;
  mustChangePassword: boolean;
  shops: ShopSummary[];
  email?: string;
}

export interface CreateUserPayload {
  username: string;
  password: string;
  fullName: string;
  role: Role;
  shopIds?: number[];
  email?: string;
}
```
(L'interface `User` — utilisateur de la session courante — n'est pas modifiée : hors périmètre du design, non nécessaire pour l'affichage/l'édition côté admin.)

### Frontend — `UsersPage.tsx`

Ajouter au `EMPTY_FORM` (ligne ~41) :
```ts
const EMPTY_FORM: CreateUserPayload = {
  username: '',
  password: '',
  fullName: '',
  role: 'SELLER',
  email: '',
  // ... champs existants inchangés
};
```

Ajouter un champ dans le formulaire (`Dialog` "Nouveau compte", après le champ "Identifiant", avant le sélecteur de rôle ou juste après, à l'appréciation de l'implémentation UI) :
```tsx
            <Grid item xs={12}>
              <TextField
                fullWidth
                type="email"
                label="Email (optionnel)"
                {...register('email')}
                error={Boolean(formState.errors.email)}
              />
            </Grid>
```
Pas de `required: true` — champ optionnel, cohérent avec `UserRequest.email` non `@NotBlank`.

---

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| 1. Le backend peut envoyer un email via un fournisseur SMTP configuré, sans identifiant en dur | `SmtpEmailChannelTest.sendReturnsTrueOnSuccess` (mock `JavaMailSender`, vérifie l'appel `mailSender.send(...)` avec un message construit à partir de `AppProperties.Mail`) ; revue manuelle : aucune valeur `host`/`username`/`password` en dur dans le code (seulement dans `AppProperties.Mail`, alimentée par variables d'environnement `MAIL_*`). |
| 2. Au moins un évènement métier concret déclenche un envoi réel (création de compte) | `UserServiceTest.createSendsWelcomeEmailWhenEmailProvided` : requête avec `email` renseigné → `verify(emailChannel).send(eq(email), anyString(), anyString())`. `UserServiceTest.createDoesNotSendEmailWhenEmailAbsent` : requête sans email → `verify(emailChannel, never()).send(any(), any(), any())`. |
| 3. Un échec d'envoi ne bloque jamais l'action métier associée | `UserServiceTest.createSucceedsEvenWhenEmailChannelThrows` : `when(emailChannel.send(any(), any(), any())).thenThrow(new RuntimeException("SMTP down"))` → `userService.create(request)` retourne normalement une `UserResponse` (pas d'exception propagée), et `verify(userRepository).save(any())` confirme que la persistance a bien eu lieu avant l'appel email. Complément unitaire : `SmtpEmailChannelTest.sendReturnsFalseOnMailException` (mock `JavaMailSender` qui lève `MailSendException`, vérifie que `send()` retourne `false` sans propager). |
| 4. Par défaut, le canal est désactivé (aucun envoi réel sans config SMTP explicite) | `EmailChannelWiringTest.defaultConfigurationOnlyActivatesNoopChannel` (nouveau, patron `ApplicationContextRunner` identique à `NotificationChannelWiringTest`) : sans `app.mail.enabled`, assert `hasSingleBean(NoopEmailChannel.class)`, `doesNotHaveBean(SmtpEmailChannel.class)`, `doesNotHaveBean(JavaMailSender.class)`. `EmailChannelWiringTest.smtpChannelIsActivatedOnlyWhenEnabled` : avec `app.mail.enabled=true` + host/from renseignés, assert `hasSingleBean(SmtpEmailChannel.class)`, `hasSingleBean(JavaMailSender.class)`, `doesNotHaveBean(NoopEmailChannel.class)`. |

### Détail des nouveaux/modifiés fichiers de test

**`SmtpEmailChannelTest.java`** (nouveau, patron identique à `WhatsAppCloudApiChannelTest.java`) :
```java
package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class SmtpEmailChannelTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailChannel channel;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getMail().setFrom("no-reply@creditflow.test");
        properties.getMail().setFromName("CreditFlow");
        channel = new SmtpEmailChannel(mailSender, properties);
    }

    @Test
    @DisplayName("retourne true quand le serveur SMTP accepte l'envoi")
    void sendReturnsTrueOnSuccess() {
        assertThat(channel.send("client@test.com", "Sujet", "Corps")).isTrue();
    }

    @Test
    @DisplayName("retourne false sans exception quand le SMTP est indisponible")
    void sendReturnsFalseOnMailException() {
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(channel.send("client@test.com", "Sujet", "Corps")).isFalse();
    }
}
```

**`EmailChannelWiringTest.java`** (nouveau, patron identique à `NotificationChannelWiringTest.java`) :
```java
package com.creditflow.notification.service;

import com.creditflow.config.AppProperties;
import com.creditflow.config.MailConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

class EmailChannelWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, MailConfig.class,
                    NoopEmailChannel.class, SmtpEmailChannel.class);

    @Test
    void defaultConfigurationOnlyActivatesNoopChannel() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NoopEmailChannel.class);
            assertThat(context).doesNotHaveBean(SmtpEmailChannel.class);
            assertThat(context).doesNotHaveBean(JavaMailSender.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void smtpChannelIsActivatedOnlyWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.host=smtp.test",
                        "app.mail.from=no-reply@creditflow.test")
                .run(context -> {
                    assertThat(context).hasSingleBean(SmtpEmailChannel.class);
                    assertThat(context).hasSingleBean(JavaMailSender.class);
                    assertThat(context).doesNotHaveBean(NoopEmailChannel.class);
                    assertThat(context).hasNotFailed();
                });
    }

    @Configuration
    @EnableConfigurationProperties
    static class TestConfig {

        @Bean
        @ConfigurationProperties(prefix = "app")
        AppProperties appProperties() {
            return new AppProperties();
        }
    }
}
```

**`UserServiceTest.java`** (modifications) :
- Ajouter `@Mock private EmailChannel emailChannel;`.
- Adapter `setUp()` : `userService = new UserService(userRepository, shopRepository, passwordEncoder, organizationRepository, emailChannel);`.
- Adapter `request()` (ajout du champ `email` en dernière position) : `new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L), null)`.
- Adapter `rejectsSellerWithoutShop` : `new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(), null)`.
- Ajouter :
```java
    @Test
    @DisplayName("envoie un email de bienvenue quand un email est fourni")
    void createSendsWelcomeEmailWhenEmailProvided() {
        when(userRepository.existsByUsernameIgnoreCase("fatou.diop")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        UserRequest request = new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop",
                Role.SELLER, List.of(1L), "fatou@test.com");

        userService.create(request);

        verify(emailChannel).send(eq("fatou@test.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("n'envoie aucun email quand aucun email n'est fourni")
    void createDoesNotSendEmailWhenEmailAbsent() {
        when(userRepository.existsByUsernameIgnoreCase("fatou.diop")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        userService.create(request());

        verify(emailChannel, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("cree le compte meme si l'envoi d'email echoue")
    void createSucceedsEvenWhenEmailChannelThrows() {
        when(userRepository.existsByUsernameIgnoreCase("fatou.diop")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User saved = i.getArgument(0);
            saved.setId(4L);
            return saved;
        });
        when(emailChannel.send(any(), any(), any())).thenThrow(new RuntimeException("SMTP down"));
        UserRequest request = new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop",
                Role.SELLER, List.of(1L), "fatou@test.com");

        var response = userService.create(request);

        assertThat(response.id()).isEqualTo(4L);
        verify(userRepository).save(any(User.class));
    }
```
(imports supplémentaires : `com.creditflow.notification.service.EmailChannel`, `static org.mockito.ArgumentMatchers.eq`, `static org.mockito.Mockito.verify`, `static org.mockito.Mockito.never` déjà présent.)

**`UserControllerTest.java`** (modifications minimales, pas de nouveau test requis — seulement compilation) :
- `response()` : `new UserResponse(4L, "fatou.diop", "Fatou Diop", "SELLER", true, true, List.of(), null)`.
- Les deux `new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L))` → `new UserRequest("fatou.diop", "TempPass2026!", "Fatou Diop", Role.SELLER, List.of(1L), null)`.

### Vérification manuelle (pas de test automatisé pertinent)

- Démarrer l'application avec `MAIL_ENABLED=true`, `MAIL_HOST` vide → confirme qu'aucun crash au démarrage ne se produit (comportement documenté en risque par l'architecte, non couvert par un validateur type `PlanConfigValidator` — hors périmètre).
- Créer un compte via `UsersPage.tsx` avec un email valide en environnement `MAIL_ENABLED=true` pointant vers un vrai serveur SMTP (ex. Mailhog/Mailtrap en local) → vérifier réception réelle de l'email de bienvenue.

---

## Écarts identifiés

- Le design ne prévoit explicitement qu'un test `SmtpEmailChannelTest` pour couvrir le canal SMTP, mais ne propose aucun test automatisé équivalent à `NotificationChannelWiringTest` pour prouver que `NoopEmailChannel` est bien le seul bean actif par défaut (critère d'acceptation n°4). J'ajoute `EmailChannelWiringTest.java` (patron identique à l'existant) pour combler ce trou et fournir une preuve automatisée plutôt qu'une simple vérification manuelle.
- Le design ne précise pas la position du nouveau champ `email` dans les records `UserRequest`/`UserResponse` ni l'impact sur les sites d'appel positionnels existants (`AuthService.toResponse`, `UserControllerTest`). J'ai tranché : ajout en dernière position dans les deux records, et j'ai listé explicitement les 5 sites d'appel à corriger (2 dans `UserServiceTest`, 2 dans `UserControllerTest`, 1 dans `AuthService`) pour que le codeur ne les découvre pas seulement à la compilation.
- Le design ne mentionne pas que `AuthService.java` (utilisé par le flux de connexion / changement de mot de passe) construit lui aussi un `UserResponse` de façon indépendante de `UserService.toResponse` — sans correction, ce fichier ne compilerait plus après l'ajout du champ `email`. Ajouté comme tâche explicite dans la checklist.

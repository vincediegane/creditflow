# Spec — #24 Gating des fonctionnalités par formule (plan) via configuration par instance

## Résumé

Ajout d'un bloc de configuration `app.plan` (`multiShop`, `whatsappAuto`, défaut `true`) qui bloque côté backend la création/réactivation d'une seconde boutique active et le démarrage de l'application en cas de combinaison `NOTIFICATION_CHANNEL=whatsapp` + plan sans WhatsApp auto, et qui masque côté frontend le bouton de création de boutique quand le plan ne l'autorise pas.

## Tâches

### Backend — configuration

- [ ] `backend/src/main/java/com/creditflow/config/AppProperties.java` : ajouter le champ `private Plan plan = new Plan();` (positionné après `private Demo demo = new Demo();`, ligne 21) et la classe imbriquée :
  ```java
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
  ```
  Style Javadoc identique à `Security.strict`.

- [ ] `backend/src/main/resources/application.yml` : ajouter après le bloc `notification:` (avant `demo:`, ligne 102) :
  ```yaml
  plan:
    multi-shop: ${PLAN_MULTI_SHOP:true}
    whatsapp-auto: ${PLAN_WHATSAPP_AUTO:true}
  ```

### Backend — garde de démarrage WhatsApp

- [ ] `backend/src/main/java/com/creditflow/config/PlanConfigValidator.java` (nouveau) — `@Slf4j @Component @RequiredArgsConstructor`, champ `private final AppProperties properties;`, méthode `@PostConstruct void validate()`. Logique :
  ```java
  @PostConstruct
  void validate() {
      boolean whatsappChannelSelected = "whatsapp".equals(properties.getNotification().getChannel());
      if (whatsappChannelSelected && !properties.getPlan().isWhatsappAuto()) {
          throw new IllegalStateException("""
                  Demarrage refuse : NOTIFICATION_CHANNEL=whatsapp est configure mais la \
                  formule de cette instance (PLAN_WHATSAPP_AUTO=false) n'inclut pas le canal \
                  WhatsApp automatique.

                  Corrigez l'une des deux valeurs : NOTIFICATION_CHANNEL=manual pour rester \
                  sur cette formule, ou PLAN_WHATSAPP_AUTO=true si la formule vendue inclut \
                  bien WhatsApp automatique, puis redemarrez.""");
      }
      log.info("Controle de plan au demarrage : configuration WhatsApp coherente avec la formule.");
  }
  ```
  Actif dans tous les profils (contrairement à `SecurityDefaultsValidator`, aucune branche `strict`/`demo`). Message d'erreur exact ci-dessus (référence les deux noms de variables d'environnement pour que l'exploitant sache quoi corriger, sur le modèle du message de `SecurityDefaultsValidator` qui cite `.env`).

- [ ] `backend/src/test/java/com/creditflow/config/PlanConfigValidatorTest.java` (nouveau) — reproduire le pattern `ApplicationContextRunner` de `NotificationChannelWiringTest` (pas un test unitaire isolé, conformément au design) :
  ```java
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
          .withUserConfiguration(TestConfig.class, PlanConfigValidator.class);
  ```
  avec une `@Configuration @EnableConfigurationProperties` interne exposant un bean `AppProperties` via `@ConfigurationProperties(prefix = "app")`, comme dans `NotificationChannelWiringTest`. Cas à couvrir : voir Plan de tests.

### Backend — garde multi-boutiques

- [ ] `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` : ajouter
  ```java
  long countByActiveTrue();

  boolean existsByActiveTrueAndIdNot(Long id);
  ```

- [ ] `backend/src/main/java/com/creditflow/shop/service/ShopService.java` :
  - Ajouter `private final AppProperties properties;` (constructeur généré par `@RequiredArgsConstructor`, import `com.creditflow.config.AppProperties`).
  - Dans `create(ShopRequest request)` : insérer `assertPlanAllowsActive(effectiveActive(request, null), null);` **avant** `assertNameAvailable(...)` (l'ordre entre les deux validations n'a pas d'impact fonctionnel — les deux lèvent `BusinessRuleException` 422 — mais placer la garde de plan en premier reflète la priorité métier : la formule est la contrainte la plus structurante).
  - Dans `update(Long id, ShopRequest request)` : après `Shop shop = getEntity(id);`, insérer `assertPlanAllowsActive(effectiveActive(request, shop), id);` avant `assertNameAvailable(...)`.
  - Nouvelles méthodes privées :
    ```java
    /** Etat actif resultant de la requete, en repliquant la semantique de ShopMapper
     *  (create: null -> true ; update: null -> etat courant inchange). */
    private boolean effectiveActive(ShopRequest request, Shop existing) {
        if (request.active() != null) {
            return request.active();
        }
        return existing == null || existing.isActive();
    }

    private void assertPlanAllowsActive(boolean requestedActive, Long excludingShopId) {
        if (!requestedActive || properties.getPlan().isMultiShop()) {
            return;
        }
        boolean anotherActiveShopExists = excludingShopId == null
                ? shopRepository.countByActiveTrue() > 0
                : shopRepository.existsByActiveTrueAndIdNot(excludingShopId);
        if (anotherActiveShopExists) {
            throw new BusinessRuleException(
                    "Votre formule actuelle ne permet qu'une seule boutique active. "
                    + "Contactez l'exploitant de la plateforme pour passer à la formule Multi-boutiques.");
        }
    }
    ```
  - `delete()` : aucun changement (hors périmètre, confirmé par le design).

- [ ] `backend/src/test/java/com/creditflow/shop/service/ShopServiceTest.java` : adapter `@InjectMocks` (Mockito injecte automatiquement `@Mock private AppProperties properties;` s'il est déclaré — l'ajouter comme `@Mock`) et fournir un stub par défaut `when(properties.getPlan()).thenReturn(new AppProperties.Plan());` dans les tests existants qui ne testent pas le gating (la classe est déjà `@MockitoSettings(strictness = Strictness.LENIENT)`, donc les stubs non utilisés dans certains tests ne cassent rien). Ajouter les nouveaux cas listés dans le Plan de tests.

### Backend — exposition au frontend

- [ ] `backend/src/main/java/com/creditflow/auth/dto/PlanSummary.java` (nouveau) :
  ```java
  package com.creditflow.auth.dto;

  public record PlanSummary(boolean multiShop, boolean whatsappAuto) {
  }
  ```

- [ ] `backend/src/main/java/com/creditflow/auth/dto/AuthResponse.java` : ajouter le champ `PlanSummary plan` en dernière position du record (après `accessibleShops`), avec Javadoc courte `/** Fonctionnalites incluses dans la formule de cette instance. */`.

- [ ] `backend/src/main/java/com/creditflow/auth/service/AuthService.java` :
  - Ajouter `private final AppProperties properties;` aux dépendances injectées.
  - Dans `login()`, construire `PlanSummary plan = new PlanSummary(properties.getPlan().isMultiShop(), properties.getPlan().isWhatsappAuto());` et l'ajouter comme dernier argument du `new AuthResponse(...)`.
  - Import `com.creditflow.config.AppProperties` et `com.creditflow.auth.dto.PlanSummary`.

### Backend — configuration de déploiement

- [ ] `.env.example` : ajouter après le bloc `# Relances automatiques ...` (ligne 32) :
  ```
  # Formule vendue a cette instance (Essentiel = false, Pro/Multi-boutiques = true)
  PLAN_MULTI_SHOP=true
  PLAN_WHATSAPP_AUTO=true
  ```

- [ ] `.env.production.example` : même ajout, après le bloc `# --- Relances automatiques ---` (ligne 62), avec commentaire adapté :
  ```
  # --- Formule vendue --------------------------------------------------
  # A ALIGNER sur la formule facturee a ce client
  PLAN_MULTI_SHOP=true
  PLAN_WHATSAPP_AUTO=true
  ```

- [ ] `docker-compose.yml`, service `backend`, bloc `environment:` (lignes 29-43) : ajouter
  ```yaml
      NOTIFICATION_CHANNEL: ${NOTIFICATION_CHANNEL:-manual}
      NOTIFICATION_DEFAULT_COUNTRY_CODE: ${NOTIFICATION_DEFAULT_COUNTRY_CODE:-+221}
      WHATSAPP_PHONE_NUMBER_ID: ${WHATSAPP_PHONE_NUMBER_ID:-}
      WHATSAPP_ACCESS_TOKEN: ${WHATSAPP_ACCESS_TOKEN:-}
      WHATSAPP_TEMPLATE_NAME: ${WHATSAPP_TEMPLATE_NAME:-relance_creditflow}
      WHATSAPP_TEMPLATE_LANGUAGE_CODE: ${WHATSAPP_TEMPLATE_LANGUAGE_CODE:-fr}
      PLAN_MULTI_SHOP: ${PLAN_MULTI_SHOP:-true}
      PLAN_WHATSAPP_AUTO: ${PLAN_WHATSAPP_AUTO:-true}
  ```
  (correction du gap préexistant `NOTIFICATION_CHANNEL`/`WHATSAPP_*` documenté dans le design, indispensable pour que le critère d'acceptation n°2 soit vérifiable en Docker réel — sans cela `PlanConfigValidator` ne voit jamais `channel=whatsapp` en conteneur).

### Frontend

- [ ] `frontend/src/types.ts` : ajouter
  ```ts
  export interface PlanSummary {
    multiShop: boolean;
    whatsappAuto: boolean;
  }
  ```
  (positionné près de `ShopSummary`, ligne ~36) et ajouter le champ `plan: PlanSummary;` à `AuthResponse` (ligne 111-117, après `accessibleShops`).

- [ ] `frontend/src/api/client.ts` : ajouter `export const PLAN_KEY = 'creditflow.plan';` après `ACCESSIBLE_SHOPS_KEY` (ligne 7). Ajouter le nettoyage `localStorage.removeItem(PLAN_KEY);` dans l'intercepteur 401 (après `localStorage.removeItem(ACCESSIBLE_SHOPS_KEY);`, ligne 56), pour cohérence avec le nettoyage déjà fait pour `accessibleShops`.

- [ ] `frontend/src/auth/AuthContext.tsx` :
  - Import `PLAN_KEY` en plus des autres clés (ligne 3) et le type `PlanSummary` (ligne 6).
  - Constante `const DEFAULT_PLAN: PlanSummary = { multiShop: true, whatsappAuto: true };` (module-level, avant `readStoredUser`).
  - Fonction `readStoredPlan()` sur le modèle exact de `readStoredAccessibleShops()` : `JSON.parse`, retour de `DEFAULT_PLAN` (pas `[]`/`null`) si absent ou parse invalide.
  - État `const [plan, setPlan] = useState<PlanSummary>(() => readStoredPlan());`.
  - Dans `login()` : `localStorage.setItem(PLAN_KEY, JSON.stringify(response.plan));` et `setPlan(response.plan);`, en s'alignant sur le pattern `accessibleShops` — mais si `response.plan` est absent (session backend antérieure à ce ticket, cas peu probable en pratique mais listé par le design comme risque), retomber sur `DEFAULT_PLAN` : `const plan = response.plan ?? DEFAULT_PLAN;` avant les deux lignes ci-dessus.
  - Dans `logout()` : `localStorage.removeItem(PLAN_KEY);` et `setPlan(DEFAULT_PLAN);` (pas `null` — jamais `undefined`/`null` d'après le design).
  - `AuthContextValue` : ajouter `plan: PlanSummary;` à l'interface (ligne 8-18) et au `value` retourné par `useMemo` (avec `plan` dans le tableau de dépendances).
  - Pas de `refreshPlan` : hors périmètre (rafraîchissement en session hors scope selon le design).

- [ ] `frontend/src/pages/ShopsPage.tsx` :
  - Importer `useAuth` depuis `'../auth/AuthContext'`.
  - `const { plan } = useAuth();` en tête du composant.
  - Remplacer le bloc `action={...}` du `PageHeader` (lignes 120-124) par un rendu conditionnel :
    ```tsx
    action={
      plan.multiShop ? (
        <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
          Nouvelle boutique
        </Button>
      ) : (
        <Chip
          label="Formule actuelle : une seule boutique. Contactez l'exploitant pour passer au Multi-boutiques."
          variant="outlined"
        />
      )
    }
    ```
    (`Chip` déjà importé ligne 9 — réutilisé, pas de nouvel import MUI.)
  - Aucun changement sur la ligne de bouton "Nouvelle boutique" dans le tableau lui-même : il n'y en a pas d'autre — seul le `PageHeader` en expose un.

- [ ] `frontend/src/pages/LateCustomersPage.tsx`, `frontend/src/components/ReminderDialog.tsx` : aucun changement (confirmé par le design — dérivent déjà de l'API réelle).

## Contrat technique

### Backend

- **`AppProperties.Plan`** : `boolean multiShop = true`, `boolean whatsappAuto = true`.
- **Variables d'environnement** : `PLAN_MULTI_SHOP` (bool, défaut `true`), `PLAN_WHATSAPP_AUTO` (bool, défaut `true`).
- **`ShopRepository`** : `long countByActiveTrue()`, `boolean existsByActiveTrueAndIdNot(Long id)`.
- **Erreur métier** : `POST /api/shops` et `PUT /api/shops/{id}` répondent `422 Unprocessable Entity` (via `GlobalExceptionHandler` existant, inchangé) avec le message `"Votre formule actuelle ne permet qu'une seule boutique active. Contactez l'exploitant de la plateforme pour passer à la formule Multi-boutiques."` quand la formule ne le permet pas.
- **`PlanConfigValidator`** : échoue le démarrage Spring (`IllegalStateException` au `@PostConstruct`, donc `ApplicationContext` refuse de se charger) si `app.notification.channel=whatsapp` ET `app.plan.whatsapp-auto=false`. Aucune condition de profil.
- **`AuthResponse`** — nouveau champ, JSON attendu :
  ```json
  {
    "token": "...",
    "tokenType": "Bearer",
    "expiresAt": "...",
    "user": { ... },
    "accessibleShops": [ ... ],
    "plan": { "multiShop": true, "whatsappAuto": true }
  }
  ```
  `PlanSummary` record : `boolean multiShop`, `boolean whatsappAuto`.

### Frontend

- **`PlanSummary`** (types.ts) : `{ multiShop: boolean; whatsappAuto: boolean }`.
- **`AuthResponse.plan`** : `PlanSummary` (nouveau champ obligatoire côté type, avec repli runtime `?? DEFAULT_PLAN`).
- **`useAuth()`** : expose désormais `plan: PlanSummary` en plus des champs existants.
- **Clé de stockage local** : `creditflow.plan` (`PLAN_KEY`), JSON sérialisé de `PlanSummary`, purgée à la déconnexion et sur 401.

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Instance `PLAN_MULTI_SHOP=false` ne peut pas créer de seconde boutique active | `ShopServiceTest.rejectsSecondActiveShopWhenPlanIsSingleShopOnCreate` — `properties.getPlan().isMultiShop()` = `false`, `shopRepository.countByActiveTrue()` renvoie `1`, `request().active() = true` → `assertThatThrownBy(...).isInstanceOf(BusinessRuleException.class)`, `verify(shopRepository, never()).save(any())`. |
| Idem via réactivation (update) | `ShopServiceTest.rejectsReactivationOfSecondShopWhenPlanIsSingleShop` — boutique existante inactive, `existsByActiveTrueAndIdNot(id)` renvoie `true` (une autre boutique déjà active), `request.active() = true` → `BusinessRuleException`, `save` jamais appelé. |
| Non-blocage si la formule autorise (`PLAN_MULTI_SHOP=true`) | `ShopServiceTest.allowsSecondActiveShopWhenPlanIsMultiShop` — `properties.getPlan().isMultiShop() = true`, `countByActiveTrue()` renvoie `1` → `create()` réussit, `verify(shopRepository).save(any())`. |
| Non-régression instance déjà multi-boutiques en base | `ShopServiceTest.allowsUpdateOfSingleAlreadyActiveShopEvenWithSingleShopPlan` — plan single-shop, update de la *seule* boutique active existante sans changer son état (`request.active() = null` ou `true`, `existsByActiveTrueAndIdNot(id)` renvoie `false` car c'est la seule active) → pas d'exception. Couvre explicitement le cas "la garde ne s'applique qu'aux nouvelles créations/réactivations", pas rétroactivement. |
| `PLAN_WHATSAPP_AUTO=false` empêche l'activation de WhatsApp auto | `PlanConfigValidatorTest.refusesStartupWhenWhatsappChannelSelectedWithoutPlanEntitlement` — `ApplicationContextRunner` avec `app.notification.channel=whatsapp`, `app.plan.whatsapp-auto=false` → `context.getStartupFailure()` non nul, `isInstanceOf(IllegalStateException.class)` (via `assertThat(context).hasFailed()` puis inspection de la cause), message contenant `"WHATSAPP"` et `"formule"`. |
| Démarrage normal dans tous les autres cas | `PlanConfigValidatorTest` — trois cas additionnels : (1) `channel=manual` + `whatsapp-auto=false` → `hasNotFailed()` ; (2) `channel=whatsapp` + `whatsapp-auto=true` (défaut) → `hasNotFailed()` ; (3) configuration par défaut complète (aucune propriété `app.plan.*`/`app.notification.channel` positionnée) → `hasNotFailed()`. |
| `plan` présent dans `AuthResponse` | Test d'intégration existant sur `AuthService`/`AuthController` (identifier le fichier de test `login` actuel — probablement `AuthControllerTest` ou `AuthServiceTest` — à étendre) : assertion `response.plan()` non nul, `response.plan().multiShop() == true` et `response.plan().whatsappAuto() == true` avec la configuration par défaut de test. Si un test unitaire `AuthServiceTest` existe déjà avec `@Mock AppProperties`, y ajouter le stub `properties.getPlan()` et l'assertion sur le `plan` retourné. |
| Frontend masque le bouton de création si `!plan.multiShop` | Test manuel (aucun test automatisé frontend existant identifié dans le périmètre relu pour `ShopsPage` — si un framework de test de composants React est présent ailleurs dans le repo, envisager un test unitaire équivalent ; sinon, procédure manuelle) : se connecter avec un compte dont l'API renvoie `plan.multiShop=false`, vérifier que le bouton "Nouvelle boutique" est absent du `PageHeader` et remplacé par le `Chip` explicatif ; se reconnecter avec `plan.multiShop=true`, vérifier que le bouton réapparaît. |
| Valeur par défaut `{multiShop:true, whatsappAuto:true}` si absent du stockage local | Test manuel ou test unitaire de `AuthContext` si l'infrastructure de test frontend le permet : vider `localStorage.creditflow.plan`, appeler `readStoredPlan()` (ou monter `AuthProvider` sans clé stockée) → `plan` égal à `{ multiShop: true, whatsappAuto: true }`, jamais `undefined`. Couvre explicitement le cas "session ouverte avant déploiement du ticket". |
| Aucune régression sur les instances existantes (défaut = tout activé) | Couvert transversalement par : `AppProperties.Plan` défauts `true`/`true` (revue de code du fichier modifié) ; `PlanConfigValidatorTest` cas "configuration par défaut" ci-dessus ; `ShopServiceTest.allowsSecondActiveShopWhenPlanIsMultiShop` (comportement identique à avant le ticket quand `multiShop=true`, valeur par défaut). |
| Gap `docker-compose.yml` corrigé (prérequis d'observabilité du critère n°2 en Docker réel) | Test manuel : `docker compose config` (ou équivalent) sur le fichier modifié, vérifier que `NOTIFICATION_CHANNEL`, les quatre `WHATSAPP_*` et les deux `PLAN_*` apparaissent bien dans l'environnement résolu du service `backend`. |

## Écarts identifiés

Aucun écart entre le design et les critères d'acceptation du ticket : les quatre critères sont couverts explicitement par le design (garde `ShopService` pour le critère 1, `PlanConfigValidator` pour le critère 2, masquage `ShopsPage` pour le critère 3, défauts `true` partout pour le critère 4). Le seul point relevé par l'architecte lui-même — le gap préexistant `docker-compose.yml` sur `NOTIFICATION_CHANNEL`/`WHATSAPP_*` — est repris comme tâche à part entière ci-dessus plutôt que signalé comme écart non tranché, car le design fournit déjà la décision (corriger en même temps).

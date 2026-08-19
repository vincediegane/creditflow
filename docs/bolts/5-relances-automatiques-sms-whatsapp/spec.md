# Spec — Issue #5 : relances automatiques par SMS/WhatsApp

## Résumé

Ajouter un canal d'envoi automatique WhatsApp (`WhatsAppCloudApiChannel`) et deux actions explicites `send`/`send-all` dans `ReminderService`, découplées de la génération d'aperçu, avec historisation dans `audit_log` et repli garanti sur la copie manuelle quand aucun canal automatique n'est configuré.

## Décisions tranchées par cette spec

### 1. Normalisation des numéros de téléphone

Données réelles vérifiées dans `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` (ex. `"770000001"`) : les numéros sont stockés **sans indicatif pays, sans `+`, 9 chiffres**, format mobile sénégalais. Le déploiement production (`.env.production.example`) fixe `TZ=Africa/Dakar`, confirmant le contexte Sénégal (+221). `CustomerRequest.phone` est validé par `^[0-9+\-\s()]{6,30}$` (chiffres, `+`, `-`, espaces, parenthèses), donc jamais de lettres à gérer.

**Règle retenue**, implémentée dans un nouveau composant `PhoneNumberNormalizer` :
1. `null`/vide → invalide.
2. Retirer tout caractère autre que chiffres et `+` (supprime espaces, tirets, parenthèses).
3. Si le résultat commence par `00`, remplacer ce préfixe par `+`.
4. Sinon, si le résultat ne commence pas par `+`, préfixer avec `app.notification.default-country-code` (défaut `+221`, configurable via `NOTIFICATION_DEFAULT_COUNTRY_CODE`).
5. Valider le résultat contre `^\+[1-9]\d{7,14}$` (E.164 simplifié). Si invalide → retourner vide, ne jamais lever d'exception.

Exemple : `"770000001"` → `"+221770000001"` (12 chiffres après le `+`, conforme).

**Limite documentée (à assumer, pas à corriger dans ce ticket)** : si un numéro contient déjà un indicatif pays mais sans `+` (ex. `"221770000001"`), la règle le double par erreur (`+221221770000001`). Aucune donnée du dépôt n'est dans ce cas ; à documenter en Javadoc sur `PhoneNumberNormalizer`, pas à corriger ici (hors périmètre du ticket).

Le canal `WHATSAPP_CLOUD_API` doit **rejeter silencieusement** (retourner `false`, logguer un warning, ne pas appeler `RestTemplate`) tout numéro qui ne normalise pas — pas d'appel réseau inutile, pas d'exception remontée à l'appelant.

### 2. Fenêtre 24h WhatsApp Cloud API

**Décision : le payload utilise `"type": "template"`, pas `"type": "text"`.** Le texte libre produit par `ReminderMessageBuilder` est passé comme unique variable `{{1}}` du corps d'un template Meta pré-approuvé (nom et langue configurables). Ce choix rend l'envoi utilisable même hors de la fenêtre de 24h suivant le dernier message entrant du client — cas normal d'une relance proactive de recouvrement.

**Limite opérationnelle à documenter pour l'utilisateur final** (Javadoc + aide frontend) : ce template doit être créé et approuvé au préalable dans Meta Business Manager, avec **exactement une variable de corps** (`{{1}}`), nom = `app.notification.whatsapp.template-name` (défaut `relance_creditflow`), langue = `app.notification.whatsapp.template-language-code` (défaut `fr`). La création/soumission de ce template reste hors périmètre de ce ticket (cf. design). **Tant que le template n'existe pas ou n'est pas approuvé côté Meta, chaque envoi retournera un échec HTTP (4xx), sera historisé `REMINDER_FAILED`, et la copie manuelle restera la seule option fiable** — comportement attendu, pas un bug.

### 3. Restriction RBAC de l'envoi automatique (point laissé ouvert par le spec-writer, tranché par l'orchestrateur)

Le ticket parle explicitement du **« gérant »** qui déclenche la relance automatique, pas du vendeur. Dans le vocabulaire déjà établi par les tickets précédents de ce projet, « gérant » désigne systématiquement le rôle `ADMIN` (ticket #1 : « En tant que gérant de boutique, je veux créer des comptes distincts pour mes vendeurs/caissiers » — ADMIN crée, VENDEUR/SELLER est créé ; ticket #4 : `PenaltySettingsController` est `ADMIN`-only avec la même logique de réglage sensible réservé au gérant). Un envoi en masse de messages à des clients (potentiellement des dizaines à la fois, engageant l'image de la boutique) est un acte plus sensible qu'une simple copie manuelle au cas par cas, et s'aligne avec ce précédent.

**Décision : `POST /api/reminders/send` et `POST /api/reminders/send-all` sont restreints à `ADMIN`** via `@PreAuthorize("hasRole('ADMIN')")` sur les deux méthodes du contrôleur (pas sur la classe entière, pour ne pas affecter `/generate` ni `/settings`, qui restent ouverts à `SELLER` comme aujourd'hui — la copie manuelle reste un outil quotidien du vendeur). Côté frontend, les boutons « Envoyer les relances » et « Envoyer automatiquement » sur `LateCustomersPage.tsx` ne doivent donc s'afficher que pour un utilisateur `ADMIN` (même garde que les autres actions ADMIN-only déjà présentes ailleurs dans l'app, ex. `RequireRole`/vérification du rôle courant exposé par le contexte d'authentification) — en plus de la condition existante « canal ≠ MANUAL_COPY ». Le bouton « Générer la relance » (aperçu + copier-coller) reste visible et actif pour `SELLER` et `ADMIN`, sans changement.

## Tâches

### Backend — configuration

- [ ] `backend/src/main/java/com/creditflow/config/AppProperties.java` — ajouter un champ `private Notification notification = new Notification();` sur `AppProperties`, et deux classes imbriquées :
  ```java
  @Getter @Setter
  public static class Notification {
      private String channel = "manual";
      private String defaultCountryCode = "+221";
      private Whatsapp whatsapp = new Whatsapp();
  }

  @Getter @Setter
  public static class Whatsapp {
      private String phoneNumberId;
      private String accessToken;
      private String apiBaseUrl = "https://graph.facebook.com";
      private String apiVersion = "v20.0";
      private String templateName = "relance_creditflow";
      private String templateLanguageCode = "fr";
  }
  ```
- [ ] `backend/src/main/resources/application.yml` — ajouter sous `app:` :
  ```yaml
  notification:
    channel: ${NOTIFICATION_CHANNEL:manual}
    default-country-code: ${NOTIFICATION_DEFAULT_COUNTRY_CODE:+221}
    whatsapp:
      phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID:}
      access-token: ${WHATSAPP_ACCESS_TOKEN:}
      api-base-url: ${WHATSAPP_API_BASE_URL:https://graph.facebook.com}
      api-version: ${WHATSAPP_API_VERSION:v20.0}
      template-name: ${WHATSAPP_TEMPLATE_NAME:relance_creditflow}
      template-language-code: ${WHATSAPP_TEMPLATE_LANGUAGE_CODE:fr}
  ```
- [ ] `.env.example` — ajouter (valeurs vides, section commentée) : `NOTIFICATION_CHANNEL=manual`, `NOTIFICATION_DEFAULT_COUNTRY_CODE=+221`, `WHATSAPP_PHONE_NUMBER_ID=`, `WHATSAPP_ACCESS_TOKEN=`, `WHATSAPP_TEMPLATE_NAME=relance_creditflow`, `WHATSAPP_TEMPLATE_LANGUAGE_CODE=fr`.
- [ ] `.env.production.example` — même bloc, avec commentaire `# A CHANGER uniquement si vous activez WhatsApp — laisser NOTIFICATION_CHANNEL=manual sinon`.

### Backend — canal automatique et normalisation

- [ ] `backend/src/main/java/com/creditflow/notification/service/ManualCopyChannel.java` — exposer `public static final String NAME = "MANUAL_COPY";` et faire retourner `name()` = `NAME` (au lieu du littéral en dur).
- [ ] `backend/src/main/java/com/creditflow/notification/service/PhoneNumberNormalizer.java` (nouveau, `@Component`) — implémente la règle décrite ci-dessus, méthode `Optional<String> normalize(String rawPhone)`. Javadoc documentant explicitement la limite (numéro déjà internationalisé sans `+`).
- [ ] `backend/src/main/java/com/creditflow/config/WebConfig.java` — ajouter un `@Bean RestTemplate restTemplate(RestTemplateBuilder builder)` avec `connectTimeout = 5s`, `readTimeout = 10s` (via `builder.setConnectTimeout(...).setReadTimeout(...).build()`).
- [ ] `backend/src/main/java/com/creditflow/notification/service/WhatsAppCloudApiChannel.java` (nouveau) :
  - `@Component`, `@ConditionalOnProperty(name = "app.notification.channel", havingValue = "whatsapp")`, `@Slf4j`, `@RequiredArgsConstructor`.
  - Dépendances injectées : `RestTemplate`, `AppProperties`, `PhoneNumberNormalizer`.
  - `name()` retourne `"WHATSAPP_CLOUD_API"`.
  - `send(phone, message)` : normalise le numéro (retourne `false` + log warning si invalide, sans appel réseau) ; construit l'URL `{apiBaseUrl}/{apiVersion}/{phoneNumberId}/messages` ; POST JSON avec en-tête `Authorization: Bearer {accessToken}` et le payload template décrit en **Contrat technique** ; catch `RestClientException` en interne → log erreur, `return false` ; ne relance jamais d'exception. Retourne `true` uniquement si la réponse HTTP est 2xx.

### Backend — refonte du service de relance

- [ ] `backend/src/main/java/com/creditflow/notification/service/ReminderService.java` :
  - Injecter en plus `LateCustomerService` et `AuditLogService` (via `@RequiredArgsConstructor`, ajout des champs `final`).
  - Extraire un porteur interne privé `private record ReminderPreview(Customer customer, BigDecimal amount, String message) {}`.
  - Renommer/adapter `generateForSale`/`generateForCustomer` en `prepareForSale`/`prepareForCustomer` (privées) qui retournent un `ReminderPreview` **sans appeler `notificationChannel`**.
  - `generate(ReminderRequest request)` : dispatch vers `prepareForSale`/`prepareForCustomer` (ou lève `BusinessRuleException` si ni `saleId` ni `customerId`), puis construit directement un `ReminderResponse` avec `sent = false` et `channel = notificationChannel.name()` — **aucun appel à `notificationChannel.send(...)`**.
  - `@Transactional public ReminderResponse send(ReminderRequest request)` : appelle `requireAutomaticChannel()` (lève `BusinessRuleException` si `notificationChannel.name().equals(ManualCopyChannel.NAME)`, message `"Aucun canal automatique n'est configure : utilisez la copie manuelle"`), résout le `ReminderPreview` (même logique que `generate`), puis appelle un helper privé `doSend(customer, amount, message)`.
  - `doSend(Customer customer, BigDecimal amount, String message)` (privé) : appelle réellement `notificationChannel.send(customer.getPhone(), message)`, journalise via `auditLogService.record("CUSTOMER", customer.getId(), customer.getFullName(), sent ? "REMINDER_SENT" : "REMINDER_FAILED", "Canal " + notificationChannel.name())`, retourne le `ReminderResponse` correspondant.
  - `@Transactional public BulkReminderResponse sendAll(String template)` : appelle `requireAutomaticChannel()`, itère `lateCustomerService.lateCustomers()`, pour chaque client `try { prepareForCustomer(...) puis doSend(...) } catch (Exception e) { log.warn(...); ajouter un ReminderResponse(sent=false, message="") au résultat }` — **le lot ne doit jamais s'interrompre** sur l'échec d'un client. Agrège dans un `BulkReminderResponse(total, sent, failed, results)`.
- [ ] `backend/src/main/java/com/creditflow/notification/dto/BulkReminderResponse.java` (nouveau, record) : `int total, int sent, int failed, List<ReminderResponse> results`.
- [ ] `backend/src/main/java/com/creditflow/notification/dto/SendAllRequest.java` (nouveau, record) : `String template` (nullable — gabarit par défaut si absent).
- [ ] `backend/src/main/java/com/creditflow/notification/web/ReminderController.java` — ajouter :
  ```java
  @PostMapping("/send")
  @PreAuthorize("hasRole('ADMIN')")
  public ReminderResponse send(@Valid @RequestBody ReminderRequest request) {
      return reminderService.send(request);
  }

  @PostMapping("/send-all")
  @PreAuthorize("hasRole('ADMIN')")
  public BulkReminderResponse sendAll(@RequestBody(required = false) SendAllRequest request) {
      return reminderService.sendAll(request == null ? null : request.template());
  }
  ```
  **`@PreAuthorize("hasRole('ADMIN')")` sur ces deux méthodes uniquement** (voir Décision #3 ci-dessus) — `/generate` et l'éventuel `/settings` restent inchangés, ouverts à `SELLER` et `ADMIN`.

### Backend — tests

- [ ] `backend/src/test/java/com/creditflow/notification/service/PhoneNumberNormalizerTest.java` (nouveau) — cas : `"770000001"` → `"+221770000001"` ; `"+221 77 000 00 01"` → `"+221770000001"` (espaces retirés) ; `"00221770000001"` → `"+221770000001"` ; `null`/`""`/`"abc"` → vide ; numéro trop court après nettoyage → vide.
- [ ] `backend/src/test/java/com/creditflow/notification/service/WhatsAppCloudApiChannelTest.java` (nouveau) — `RestTemplate` mocké (`@Mock`), aucun appel réseau réel : succès (2xx → `true`), échec HTTP 4xx/429 (→ `false`, pas d'exception), `RestClientException` levée par le mock (→ `false`, pas d'exception), numéro non normalisable (→ `false`, **`restTemplate` jamais appelé** — vérifier avec `verifyNoInteractions`).
- [ ] `backend/src/test/java/com/creditflow/notification/service/ReminderServiceTest.java` (nouveau, mocks Mockito des repositories/services existants + `NotificationChannel`, `LateCustomerService`, `AuditLogService`) :
  - `generate()` ne doit **jamais** appeler `notificationChannel.send(...)` (`verify(notificationChannel, never()).send(any(), any())`), et retourne `sent = false`.
  - `send()` avec `notificationChannel.name()` = `MANUAL_COPY` lève `BusinessRuleException`.
  - `send()` avec un canal automatique appelle `notificationChannel.send(...)` puis `auditLogService.record("CUSTOMER", ..., "REMINDER_SENT", ...)` si `true`, ou `"REMINDER_FAILED"` si `false`.
  - `sendAll()` avec deux clients en retard dont un lève une exception dans `prepareForCustomer` (ex. client sans contrat) : le lot continue, le `BulkReminderResponse` contient bien les 2 résultats (`total = 2`), l'un marqué échoué.
  - `sendAll()` avec `MANUAL_COPY` lève `BusinessRuleException` avant tout appel à `lateCustomerService.lateCustomers()`.
- [ ] `backend/src/test/java/com/creditflow/notification/web/ReminderControllerSecurityTest.java` (nouveau, `@WebMvcTest(ReminderController.class)` + `AbstractWebMvcSecurityTest`, suivant le modèle de `PenaltySettingsControllerSecurityTest`) :
  - `POST /api/reminders/send` et `POST /api/reminders/send-all` retournent **403 pour `roles = "SELLER"`** et **200 pour `roles = "ADMIN"`** (conforme à la Décision #3 — mock des services pour retourner une réponse valide dans le cas ADMIN).
  - `POST /api/reminders/generate` reste accessible à `SELLER` et `ADMIN` (200 dans les deux cas — non-régression explicite sur ce point).
  - Requête non authentifiée (sans `@WithMockUser`) sur `/send` et `/send-all` retourne 401.

### Frontend

- [ ] `frontend/src/types.ts` — ajouter, après `ReminderSettings` :
  ```ts
  export interface BulkReminderResult {
    total: number;
    sent: number;
    failed: number;
    results: Reminder[];
  }
  ```
- [ ] `frontend/src/api/endpoints.ts` — dans `remindersApi`, ajouter :
  ```ts
  send: (payload: { saleId?: number; customerId?: number; template?: string }) =>
    api.post<Reminder>('/reminders/send', payload).then((r) => r.data),
  sendAll: (template?: string) =>
    api.post<BulkReminderResult>('/reminders/send-all', { template }).then((r) => r.data),
  ```
  (importer `BulkReminderResult` depuis `../types`).
- [ ] `frontend/src/pages/LateCustomersPage.tsx` :
  - Bouton d'en-tête « Envoyer les relances » (via `useMutation(remindersApi.sendAll)`), visible uniquement si l'utilisateur courant a le rôle `ADMIN` **et** `settings.data?.channel !== 'MANUAL_COPY'`, désactivé pendant l'envoi ou si `rows.length === 0`. Demander confirmation avant déclenchement (ex. `window.confirm` ou dialogue MUI) car action irréversible en masse. Après succès, afficher un `Snackbar`/`Alert` avec `sent`/`failed` du `BulkReminderResult`, et invalider les queries `['late-customers']` et l'historique d'audit affiché ailleurs.
  - Bouton par ligne « Envoyer automatiquement » (icône différente du bouton existant « Générer la relance », ex. `SendIcon`), appelant `remindersApi.send({ customerId: row.customerId })`. Visible uniquement si rôle `ADMIN` **et** `settings.data?.channel !== 'MANUAL_COPY'` (ne remplace pas « Générer la relance », qui reste toujours disponible pour `SELLER`/`ADMIN`).
  - Ajouter un texte d'aide (`Alert severity="info"` ou tooltip) visible quand le canal WhatsApp est actif et l'utilisateur est ADMIN : *« Les relances automatiques WhatsApp utilisent un modèle de message pré-approuvé par Meta. En dehors des 24h suivant le dernier message du client, seul ce modèle peut être envoyé — configurez-le dans Meta Business Manager avant d'activer ce canal. »*
  - Pour connaître le rôle courant, vérifier comment l'app expose déjà cette information ailleurs (ex. contexte d'authentification déjà utilisé par `RequireRole`/`UsersPage`/`PenaltySettingsPage` — réutiliser le même mécanisme, ne pas en créer un nouveau).
- [ ] `frontend/src/utils/format.ts` — dans `AUDIT_ACTION_LABELS`, ajouter `REMINDER_SENT: 'Relance envoyée'` et `REMINDER_FAILED: 'Relance en échec'`.
- [ ] `frontend/src/pages/CustomerDetailPage.tsx` — ligne ~230, étendre l'`actionLabels` local de `AuditHistoryCard` : `actionLabels={{ DELETE: 'Suppression du client', REMINDER_SENT: 'Relance envoyée', REMINDER_FAILED: 'Relance en échec' }}` (cet objet remplace intégralement le défaut, il faut donc bien reprendre `DELETE` existant).

## Contrat technique

### Endpoints

**`POST /api/reminders/send`** (ADMIN uniquement)
Payload (identique à `/generate`) :
```json
{ "saleId": 12, "customerId": null, "template": null }
```
Réponse `200 OK` (`ReminderResponse`, forme inchangée) :
```json
{
  "customerId": 3,
  "customerName": "Amadou Diallo",
  "phone": "770000001",
  "amount": 15000,
  "message": "Bonjour Amadou, ...",
  "channel": "WHATSAPP_CLOUD_API",
  "sent": true
}
```
`422 Unprocessable Entity` (`BusinessRuleException` → `ApiError`) si `channel = MANUAL_COPY` : `{ "status": 422, "message": "Aucun canal automatique n'est configure : utilisez la copie manuelle", ... }`.
`403 Forbidden` si l'appelant n'est pas `ADMIN`.

**`POST /api/reminders/send-all`** (ADMIN uniquement)
Payload optionnel :
```json
{ "template": null }
```
Réponse `200 OK` (`BulkReminderResponse`) :
```json
{
  "total": 5,
  "sent": 4,
  "failed": 1,
  "results": [ { "customerId": 3, "...": "...", "sent": true }, "..." ]
}
```
`422` si `channel = MANUAL_COPY`, avant tout appel à `lateCustomerService.lateCustomers()`. `403 Forbidden` si l'appelant n'est pas `ADMIN`.

`/api/reminders/generate` (et `/settings` s'il existe) restent **inchangés** : accessibles à `SELLER` et `ADMIN`, comme aujourd'hui — seule la partie envoi effectif (`send`/`send-all`) est restreinte.

### Payload JSON envoyé à Meta Graph API par `WhatsAppCloudApiChannel`

`POST {apiBaseUrl}/{apiVersion}/{phoneNumberId}/messages`, en-tête `Authorization: Bearer {accessToken}`, `Content-Type: application/json` :
```json
{
  "messaging_product": "whatsapp",
  "to": "221770000001",
  "type": "template",
  "template": {
    "name": "relance_creditflow",
    "language": { "code": "fr" },
    "components": [
      {
        "type": "body",
        "parameters": [
          { "type": "text", "text": "<message construit par ReminderMessageBuilder>" }
        ]
      }
    ]
  }
}
```
Notes :
- `to` = numéro normalisé (`+221770000001`) **sans le `+`** (exigence Meta Graph API).
- Le champ `text` du paramètre de template reçoit l'intégralité du message construit par `ReminderMessageBuilder` (variables déjà substituées) — le template Meta doit donc être conçu côté Business Manager avec **une seule variable de corps générique**, pas de structure multi-variables.
- Implémentation suggérée : construire le payload avec `Map.of(...)` imbriqués (évite le formalisme `@JsonProperty` pour `messaging_product`), sérialisé automatiquement par le convertisseur Jackson par défaut de `RestTemplate`.

### `PhoneNumberNormalizer`

```java
public Optional<String> normalize(String rawPhone)
```
Règle : voir section « Décisions tranchées » ci-dessus. Pattern final : `^\+[1-9]\d{7,14}$`.

### `AppProperties.Notification` / `AppProperties.Whatsapp`

Voir section Tâches — structure Java exacte donnée ci-dessus.

## Plan de tests

| Critère d'acceptation (ticket) | Test |
|---|---|
| Le gérant peut déclencher une relance automatique en masse sur tous les clients en retard | `ReminderServiceTest#sendAll...` (agrège correctement, continue le lot en cas d'échec partiel) ; `ReminderControllerSecurityTest` (200 pour `POST /send-all` avec ADMIN, 403 avec SELLER) ; manuel : bouton « Envoyer les relances » sur `LateCustomersPage`, canal WhatsApp configuré en local avec des identifiants de test, connecté en ADMIN |
| Le gérant peut déclencher une relance automatique individuellement | `ReminderServiceTest#send...` (appelle le canal, retourne `sent`) ; `ReminderControllerSecurityTest` (200 pour `POST /send` avec ADMIN, 403 avec SELLER) ; manuel : bouton « Envoyer automatiquement » par ligne sur `LateCustomersPage`, connecté en ADMIN |
| Chaque relance envoyée est historisée avec son statut (envoyée / échec) | `ReminderServiceTest` : vérifie `auditLogService.record("CUSTOMER", id, name, "REMINDER_SENT"/"REMINDER_FAILED", ...)` appelé dans `doSend` pour les deux cas (`sent=true`/`sent=false`) ; `WhatsAppCloudApiChannelTest` (statut HTTP → booléen correct, base du statut historisé) ; manuel : `AuditHistoryCard` sur `CustomerDetailPage` affiche « Relance envoyée »/« Relance en échec » après un envoi |
| En l'absence de configuration du canal automatique, le comportement actuel (copier-coller) reste disponible sans erreur | `ReminderServiceTest#generateNeverCallsChannel` (aperçu pur, jamais d'appel réseau) ; `ReminderServiceTest` : `send()`/`sendAll()` avec `MANUAL_COPY` lèvent `BusinessRuleException` (422, pas de crash) ; manuel : avec `NOTIFICATION_CHANNEL` absent/`manual` (défaut), `GET /api/reminders/settings` retourne `channel: MANUAL_COPY`, boutons d'envoi automatique masqués sur le frontend, bouton « Générer la relance » + copier-coller fonctionnent inchangés pour SELLER et ADMIN ; démarrage de l'application avec les variables `WHATSAPP_*` vides (défaut) ne plante pas (`WhatsAppCloudApiChannel` non instancié grâce à `@ConditionalOnProperty`) |
| (implicite) Normalisation des numéros | `PhoneNumberNormalizerTest` (cas listés ci-dessus, calibrés sur le format réel de `DemoDataSeeder`) ; `WhatsAppCloudApiChannelTest` : numéro non normalisable → `false`, aucun appel `RestTemplate` |
| (implicite) Robustesse du lot | `ReminderServiceTest#sendAll` avec un client en échec au milieu du lot : les autres clients sont bien traités (pas d'interruption) |
| (implicite) Restriction RBAC de l'envoi automatique | `ReminderControllerSecurityTest` : 403 pour SELLER sur `/send` et `/send-all`, 200 pour ADMIN ; `/generate` reste 200 pour SELLER (non-régression) |

## Écarts identifiés

- **Point RBAC tranché par l'orchestrateur, pas par le spec-writer** : le spec-writer avait laissé ouverte la question du rôle autorisé à déclencher l'envoi automatique (le ticket dit « gérant », l'exposition actuelle de `/generate` est ADMIN+SELLER). Décision retenue et documentée en section « Décisions tranchées » #3 : `/send` et `/send-all` sont `ADMIN`-only, `/generate` reste inchangé (ADMIN+SELLER). Le codeur doit implémenter cette restriction telle quelle, y compris côté frontend (masquage des boutons pour SELLER).
- **Limite de normalisation non couverte** : un numéro déjà préfixé par un indicatif pays mais sans `+` (ex. `"221770000001"`) sera doublé par erreur. Aucune donnée du dépôt n'est dans ce cas, donc pas bloquant pour ce ticket, mais à documenter en Javadoc plutôt qu'à corriger silencieusement.
- **Dépendance opérationnelle non technique** : le canal WhatsApp ne peut fonctionner en production qu'après création et approbation d'un template Meta Business Manager à une variable — hors périmètre de ce ticket (explicitement exclu par le design). Tant que ce template n'existe pas, `sendAll`/`send` avec `channel=whatsapp` produiront systématiquement des échecs historisés `REMINDER_FAILED` ; ce n'est pas un bug mais doit être communiqué clairement à l'utilisateur (texte d'aide frontend prévu dans les tâches).

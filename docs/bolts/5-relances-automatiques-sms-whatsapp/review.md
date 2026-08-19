# Review — Issue #5 : relances automatiques par SMS/WhatsApp

## Verdict

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| Le gérant peut déclencher une relance automatique en masse sur tous les clients en retard, ou individuellement | Couvert — `ReminderService.send()`/`sendAll()`, `POST /api/reminders/send` et `/send-all` (ADMIN-only), boutons frontend « Envoyer les relances » / « Envoyer automatiquement » sur `LateCustomersPage.tsx`, testé côté service (`ReminderServiceTest`) et sécurité (`ReminderControllerSecurityTest`, 200 ADMIN) |
| Chaque relance envoyée est historisée avec son statut (envoyée / échec) | Couvert — `doSend()` appelle `auditLogService.record("CUSTOMER", id, name, "REMINDER_SENT"/"REMINDER_FAILED", ...)` dans les deux branches, testé explicitement (`sendRecordsSuccess`, `sendRecordsFailure`), libellés ajoutés à `AUDIT_ACTION_LABELS` et à `CustomerDetailPage` |
| En l'absence de configuration du canal automatique, le comportement actuel (copier-coller) reste disponible sans erreur | Couvert — `generate()` ne référence jamais `notificationChannel.send(...)` (vérifié `verify(..., never())` dans `generateNeverCallsChannel`), `send()`/`sendAll()` lèvent `BusinessRuleException` (422) sur `MANUAL_COPY` avant tout traitement, `ManualCopyChannel` reste actif par défaut sans crash (`NotificationChannelWiringTest`) |

## Vérifications ciblées demandées

- **Déplacement du bean `RestTemplate` vers `HttpClientConfig.java`** : raisonnement du codeur validé. `WebConfig` implémente `WebMvcConfigurer` et est donc chargé par toute tranche `@WebMvcTest`, qui n'autoconfigure pas `RestTemplateBuilder` — y placer le bean aurait cassé `PenaltySettingsControllerSecurityTest` et les autres `@WebMvcTest`. `HttpClientConfig` est un `@Configuration` simple, dans le même package `com.creditflow.config` que `WebConfig`, scanné normalement par `@SpringBootApplication` sur `com.creditflow` — confirmé par la suite complète (153/153, y compris toutes les tranches `@WebMvcTest` de sécurité) qui passe sans accroc.
- **Migrations `V3` dupliquées (`V3__audit_columns.sql` / `V3__credit_sale_interest.sql`)** : confirmé présent sur `master` et hérité tel quel sur cette branche — le ticket #5 n'ajoute aucune migration et ne touche à aucun fichier du dossier `db/migration`. Rien de nouveau introduit par ce ticket ; à traiter séparément (hors périmètre), signalé ici pour information uniquement.
- `ReminderService.generate()` : confirmé pur, aucun appel à `notificationChannel.send(...)`.
- `send()`/`sendAll()` : refusent bien `MANUAL_COPY` via `requireAutomaticChannel()` avec `BusinessRuleException`, et `sendAll()` appelle ce garde-fou avant toute itération sur `lateCustomerService.lateCustomers()` (`verifyNoInteractions(lateCustomerService)` dans le test dédié).
- `sendAll()` continue le lot sur échec partiel : test `sendAllContinuesOnFailure` réellement probant — le deuxième client échoue parce que `saleRepository.findByCustomer(2L)` retourne une liste vide (`prepareForCustomer` lève `BusinessRuleException("Ce client n'a aucun contrat en cours")`), capturé par le `try/catch` du service ; le premier client est bien traité et compté comme envoyé (`total=2, sent=1, failed=1`).
- `WhatsAppCloudApiChannel` : catch interne systématique de `RestClientException` (jamais de relance vers l'appelant, testé avec `HttpClientErrorException` 429 et `ResourceAccessException`), payload conforme au contrat (`type: template`, `components[0].parameters[0].text`, `to` sans le `+`).
- `PhoneNumberNormalizer` : implémentation fidèle à la règle de la spec (nettoyage `[^0-9+]`, `00`→`+`, préfixe `+221` par défaut si absent de `+`, pattern final `^\+[1-9]\d{7,14}$`), Javadoc documentant la limite assumée (indicatif sans `+` doublé).
- RBAC : `ReminderControllerSecurityTest` couvre 403 SELLER / 200 ADMIN sur `/send` et `/send-all`, 401 non authentifié, et non-régression explicite `/generate` → 200 pour SELLER et ADMIN.
- Historisation : confirmé, `doSend` seul point d'appel à `auditLogService.record`, avec le bon type d'entité et les deux actions attendues.
- Frontend `LateCustomersPage.tsx` : boutons « Envoyer les relances » et « Envoyer automatiquement » gardés par `canSendAutomatically = isAdmin && automaticChannelActive` (même mécanisme de rôle que `RequireRole`/`useAuth`), confirmation `window.confirm` avant l'envoi en masse, retour `Snackbar` avec `sent`/`failed`, invalidation des queries `['late-customers']` et `['audit-log']`.
- `NotificationChannelWiringTest` : pertinent et correct — utilise un `ApplicationContextRunner` léger (pas de base de données), confirme qu'en configuration par défaut seul `ManualCopyChannel` est instancié (`ManualCopyChannel` porte déjà `@ConditionalOnProperty(havingValue = "manual", matchIfMissing = true)`, préexistant) et que `WhatsAppCloudApiChannel` ne l'est que si `app.notification.channel=whatsapp`, sans jamais faire échouer le contexte (`hasNotFailed()`), même sans secrets WhatsApp.

## Cohérence avec spec.md

Tous les fichiers listés dans les tâches de la spec sont présents et conformes au contrat technique donné (structure Java, YAML, endpoints, payload JSON, regex). Le seul écart assumé (emplacement du bean `RestTemplate`) est documenté en Javadoc dans `HttpClientConfig` et justifié techniquement — validé ci-dessus. Aucune tâche de la spec n'est manquante.

## Findings

Aucun finding bloquant. Deux remarques mineures, non bloquantes :

1. Le bouton d'en-tête « Envoyer les relances » (`LateCustomersPage.tsx:96-105`) est désactivé si `rows.length === 0`, mais reste rendu (pas seulement masqué) même quand il n'y a aucun client en retard — comportement mineur, conforme à la spec qui demande « désactivé ... si `rows.length === 0` », donc pas un défaut.
2. `sendAllMutation` (`LateCustomersPage.tsx:73-80`) ne transmet pas de `template` explicite (`() => remindersApi.sendAll()` sans argument) — conforme au contrat (`template` optionnel, gabarit par défaut côté serveur), mais aucun moyen pour l'utilisateur de personnaliser le gabarit lors d'un envoi en masse depuis l'UI. Non demandé explicitement par la spec, donc non bloquant.

## Build/tests

- Backend : `mvn -o test` (répertoire `backend/`) → `Tests run: 153, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`. Confirme en particulier que `PenaltySettingsControllerSecurityTest` et les autres tranches `@WebMvcTest` passent avec `HttpClientConfig` isolé de `WebConfig`.
- Frontend : `npm run build` (répertoire `frontend/`) → `tsc --noEmit && vite build` réussi, aucun avertissement TypeScript bloquant (seul un avertissement Rollup sur la taille de chunk, non lié à ce ticket).

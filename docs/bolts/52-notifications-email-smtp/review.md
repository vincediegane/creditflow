# Review — Issue #52 : notifications par email — nouveau canal SMTP

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Le backend peut envoyer un email via un fournisseur SMTP configuré, sans identifiant en dur | Couvert | `SmtpEmailChannel` construit le message via `JavaMailSender` + `AppProperties.Mail` (host/port/username/password/from alimentés uniquement par `MAIL_*` en env, cf. `application.yml`, `.env.example`) ; aucune valeur en dur trouvée dans le code (`SmtpEmailChannel.java`, `MailConfig.java`). Test : `SmtpEmailChannelTest.sendReturnsTrueOnSuccess`. |
| 2 | Au moins un évènement métier concret déclenche un envoi réel (périmètre fixé en spec) | Couvert | `UserService.create()` appelle `sendWelcomeEmail(saved)` après persistance, si `email` fourni. Tests : `UserServiceTest.createSendsWelcomeEmailWhenEmailProvided` / `createDoesNotSendEmailWhenEmailAbsent` — ces deux tests échoueraient si l'appel à `sendWelcomeEmail`/`emailChannel.send` était retiré ou mal câblé. |
| 3 | Un échec d'envoi ne bloque jamais l'action métier associée | Couvert | Double filet : `SmtpEmailChannel.send` catch `MailException` → `false` (jamais de throw) ; `UserService.sendWelcomeEmail` a son propre `try/catch(Exception)` en défense en profondeur. Test : `UserServiceTest.createSucceedsEvenWhenEmailChannelThrows` (mock qui lève une `RuntimeException`, la création réussit quand même et `userRepository.save` est bien appelé avant). Complément : `SmtpEmailChannelTest.sendReturnsFalseOnMailException`. |
| 4 | Canal désactivé par défaut (dev/démo), aucun envoi réel sans config SMTP explicite | Couvert | `NoopEmailChannel` actif par défaut (`@ConditionalOnProperty(..., matchIfMissing = true)`), `SmtpEmailChannel`/`MailConfig`/`JavaMailSender` conditionnés strictement sur `app.mail.enabled=true`. Test de câblage Spring : `EmailChannelWiringTest` (`defaultConfigurationOnlyActivatesNoopChannel` et `smtpChannelIsActivatedOnlyWhenEnabled`), patron identique à `NotificationChannelWiringTest` déjà en place pour WhatsApp. |

## Conformité au contrat technique (spec.md)

Diff relu fichier par fichier (`git diff master...HEAD`, hors `design.md`/`spec.md`) contre le contrat détaillé de la spec : chaque fichier (migration `V19__users_email.sql`, `pom.xml`, `User.java`, `AppProperties.java`, `MailConfig.java`, `EmailChannel.java`, `NoopEmailChannel.java`, `SmtpEmailChannel.java`, `application.yml`, `.env.example`/`.env.production.example`, `UserRequest.java`, `UserResponse.java`, `UserService.java`, `AuthService.java`, `UserServiceTest.java`, `UserControllerTest.java`, `EmailChannelWiringTest.java`, `SmtpEmailChannelTest.java`, `types.ts`, `UsersPage.tsx`) correspond mot pour mot (ou quasi) au contrat avant/après de la spec. Aucune tâche de la checklist n'a été sautée ou faite différemment sans justification. Tous les 5 sites d'appel positionnels identifiés par le spec-writer (`AuthService.toResponse`, 2× `UserServiceTest`, 2× `UserControllerTest`) sont bien corrigés — vérifié également par recherche exhaustive de tous les `new UserRequest(...)`/`new UserResponse(...)` dans le repo, aucun site manqué.

`V19__users_email.sql` est bien le prochain numéro de migration disponible (V1 à V18 existants, pas de collision).

## Findings (non bloquants)

1. **Nullabilité incohérente : `""` au lieu de `NULL` pour les comptes sans email** — `frontend/src/pages/UsersPage.tsx:46` initialise `EMPTY_FORM.email` à `''` et le payload est envoyé tel quel (`frontend/src/api/endpoints.ts:307-308`, pas de nettoyage). Résultat : un compte créé sans email stocke `email = ''` en base plutôt que `NULL`, alors que le commentaire de la migration (`V19__users_email.sql:40-41`) et l'intention de la spec présentent la colonne comme nullable pour représenter "pas d'email". Sans conséquence fonctionnelle actuelle (`sendWelcomeEmail` traite `""` comme `isBlank()` donc ne tente pas d'envoi, et rien n'affiche/n'interroge ce champ ailleurs pour l'instant), mais c'est une dette de propreté de données à corriger si un futur ticket interroge `email IS NULL` ou affiche la colonne. Suggestion : normaliser côté `UserService.create` (`request.email() != null && !request.email().isBlank() ? request.email() : null`) ou côté frontend avant soumission.

Ce point n'affecte aucun critère d'acceptation et ne justifie pas à lui seul un `CHANGES_REQUESTED` — signalé pour information/suivi.

## Build / Tests

- Backend : `mvn -o test` (working dir `backend/`) → **BUILD SUCCESS**, `Tests run: 418, Failures: 0, Errors: 0, Skipped: 0`. Inclut les nouveaux tests `EmailChannelWiringTest` (2/2), `SmtpEmailChannelTest` (2/2), `UserServiceTest` (11/11, dont les 3 nouveaux cas email), `UserControllerTest` (8/8).
- Frontend : `npx tsc --noEmit` (working dir `frontend/`) → aucune erreur de compilation.

## Fichiers relus

- `backend/pom.xml`
- `backend/src/main/resources/db/migration/V19__users_email.sql`
- `backend/src/main/java/com/creditflow/auth/domain/User.java`
- `backend/src/main/java/com/creditflow/auth/dto/UserRequest.java`
- `backend/src/main/java/com/creditflow/auth/dto/UserResponse.java`
- `backend/src/main/java/com/creditflow/auth/service/UserService.java`
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java`
- `backend/src/main/java/com/creditflow/config/AppProperties.java`
- `backend/src/main/java/com/creditflow/config/MailConfig.java`
- `backend/src/main/java/com/creditflow/notification/service/EmailChannel.java`
- `backend/src/main/java/com/creditflow/notification/service/NoopEmailChannel.java`
- `backend/src/main/java/com/creditflow/notification/service/SmtpEmailChannel.java`
- `backend/src/main/resources/application.yml`
- `.env.example`, `.env.production.example`
- `backend/src/test/java/com/creditflow/auth/service/UserServiceTest.java`
- `backend/src/test/java/com/creditflow/auth/web/UserControllerTest.java`
- `backend/src/test/java/com/creditflow/notification/service/EmailChannelWiringTest.java`
- `backend/src/test/java/com/creditflow/notification/service/SmtpEmailChannelTest.java`
- `frontend/src/types.ts`
- `frontend/src/pages/UsersPage.tsx`
- `docs/bolts/52-notifications-email-smtp/spec.md`, `design.md` (lus pour contexte, non revus comme code applicatif)

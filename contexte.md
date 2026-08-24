# Contexte — CreditFlow

Document de contexte pour reprendre le projet rapidement (humain ou assistant). Reflète l'état
réel du code à la date ci-dessous, pas le README (qui décrit encore le périmètre du MVP initial
et n'a pas été mis à jour après les tickets #1 à #11).

Dernière mise à jour : **2026-08-22**.

---

## Ce que c'est

CreditFlow est une application de gestion de ventes à crédit pour une boutique qui vend des
téléphones et ordinateurs à crédit (marché sénégalais, devise FCFA, fuseau `Africa/Dakar`) :
clients, produits, contrats, échéanciers, paiements, relances, rapports. Conçue mono-boutique à
l'origine, elle supporte désormais le **multi-boutiques** (voir plus bas).

Stack : **Java 21 / Spring Boot 3.5** (backend), **React 18 / Vite / TypeScript / MUI** (frontend),
**PostgreSQL** + **Flyway** (schéma, aucun `ddl-auto`), **Docker Compose** + **nginx** (déploiement).

Démarrage : `docker compose up --build` → app sur `http://localhost:3010` (ou `https://…:3443`
si un certificat existe dans `./certs`), API sur `http://localhost:8080/api`, Swagger sur
`/swagger-ui.html`. Compte admin par défaut : `admin` / `admin123` (démo uniquement).

---

## État du backlog

**Les 11 tickets du backlog produit initial sont livrés et mergés dans `master`.** Le board GitHub
Project (`vincediegane/creditflow`, projet n°2) est à 11/11 Done, aucune PR ouverte.

| # | Ticket | Ce qu'il a ajouté |
|---|---|---|
| 1 | RBAC — comptes vendeur/caissier | Rôles `ADMIN`/`SELLER`, `@PreAuthorize` sur les endpoints sensibles |
| 2 | Journal d'audit | `created_by`/`updated_by` sur les entités, écran Historique |
| 3 | Taux d'intérêt / frais de dossier | `interest_rate`/`interest_amount` sur les contrats |
| 4 | Pénalité de retard configurable | Module `penalty`, calcul à la lecture |
| 5 | Relances automatiques SMS/WhatsApp | `WhatsAppCloudApiChannel` (implémentation de `NotificationChannel`) |
| 6 | Signature électronique / pièce jointe | Upload de pièces sur un contrat, vérification par magic bytes |
| 7 | Garant/caution sur un contrat | 4 champs garant sur `credit_sales`, recherche associée |
| 8 | Achats fournisseurs et réception de stock | Module `supplier`, historique des mouvements de stock |
| 9 | Statistiques taux de défaut / performance vendeur | 2 nouveaux types de rapport, ADMIN-only pour le second |
| 10 | Consolidation multi-boutiques | Module `shop`, `CurrentShopContext`, cloisonnement transverse |
| 11 | Mode hors-ligne avec synchronisation | File IndexedDB, idempotence serveur, PWA |

Pas de nouveau ticket produit en attente à ce jour.

---

## Architecture backend (par module métier)

```
backend/src/main/java/com/creditflow/
├── auth/          authentification, JWT, rôles, boutiques accessibles à l'utilisateur
├── shop/          boutiques (multi-boutiques, ticket #10)
├── customer/      clients, fiche 360
├── product/       catalogue, mouvements de stock (StockMovement)
├── supplier/      fournisseurs, réceptions de stock (ticket #8)
├── sale/          contrats de crédit, échéancier, garant, pièces jointes
├── payment/       versements, imputation FIFO, idempotence (clientRequestId, ticket #11)
├── penalty/       pénalités de retard configurables
├── dashboard/      agrégats du tableau de bord (consolidé multi-boutiques)
├── notification/  relances (copie manuelle + WhatsApp Cloud API)
├── report/        rapports + exports PDF/Excel
├── search/        recherche globale
├── audit/         journal d'audit (cloisonné par boutique depuis le ticket #10)
├── dataimport/    reprise de données CSV/Excel (tout ou rien)
├── common/        exceptions, pagination, stockage de fichiers, CurrentShopContext, utilitaires
├── config/        sécurité, CORS, OpenAPI, propriétés
└── bootstrap/     données de démonstration (DemoDataSeeder)
```

**Rôles** : `ADMIN` (tous droits, super-admin si aucune boutique assignée) et `SELLER` (création
de vente/paiement, consultation ; pas de suppression, pas de modification produit/prix).

**Migrations** : `V1` à `V12`, **trou volontaire à `V8`** (collision Flyway résolue en son temps en
renumérotant — sans conséquence, Flyway n'exige pas de continuité stricte). Ne jamais réutiliser
V8 ; la prochaine migration est **V13**.

### Choix de conception qui traversent tout le code

- **Aucun `ddl-auto`** : le schéma vient exclusivement de Flyway.
- **`InstallmentScheduleGenerator`** est une classe pure sans dépendance base : mensualités
  arrondies à l'unité FCFA, la dernière échéance absorbe le reliquat (somme exacte garantie).
- **`PaymentAllocator`** impute un versement de la plus ancienne échéance à la plus récente ;
  l'annulation remet l'échéancier à zéro et rejoue les versements restants.
- **Les retards sont calculés à la lecture**, jamais stockés — aucune tâche planifiée à
  désynchroniser.
- **Recherches en JPA `Specification`** (`*Specifications`) : un filtre absent ne produit aucun
  prédicat.
- **Multi-boutiques (`CurrentShopContext`)** : filtrage résolu côté service, pas exposé en
  paramètre d'URL sur les listes standard. Un utilisateur mono-boutique n'a aucun changement
  d'API perceptible. Seuls le dashboard et les rapports acceptent un choix explicite via l'en-tête
  `X-Shop-Id`. `ADMIN` sans boutique assignée = accès à toutes les boutiques (super-admin).
  `payments`/`installments` n'ont pas de `shop_id` propre : filtrage par jointure vers
  `credit_sales.shop_id`.
- **Idempotence des paiements (`clientRequestId`)** : un UUID généré côté client, unique en base
  (index `ux_payments_client_request_id`). Un rejeu (même ID) renvoie le versement existant
  (200) au lieu d'en créer un second (201 à la création). C'est le fondement du mode hors-ligne.

---

## Architecture frontend

```
frontend/src/
├── pages/         un fichier par écran (Dashboard, Customers, Sales, Payments, Shops, Users, …)
├── components/    composants partagés (AppLayout, PaymentDialog, StatusChip, OfflineBanner, …)
├── context/       ShopContext, OfflineQueueContext
├── auth/          AuthContext (JWT, boutiques accessibles)
├── offline/       file d'attente hors-ligne (queue.ts, sync.ts, httpCache.ts) — voir ci-dessous
├── api/           client axios (intercepteurs JWT + X-Shop-Id), endpoints.ts
└── types.ts       types partagés frontend
```

**State management** : TanStack Query (React Query) + contextes maison, pas de Redux.

**PWA** (`vite-plugin-pwa`) : manifeste installable, service worker avec cache `NetworkFirst` sur
les GET `/api` déjà consultés. **Ne s'active qu'en HTTPS** — en HTTP pur (mode par défaut du
dépôt sans certificat), aucun service worker ne s'enregistre et la lecture hors-ligne ne
fonctionne pas. Générer un certificat auto-signé pour tester :
```bash
./scripts/generate-self-signed-cert.sh localhost
docker compose up -d --force-recreate frontend
```

**Mode hors-ligne (ticket #11)** : un paiement saisi sans connexion (ou si le serveur répond sans
`error.response` — cas du wifi captif) est mis en file dans IndexedDB (`creditflow-offline`,
store `pendingPayments`, clé = `clientRequestId`) plutôt que perdu. Rejeu séquentiel FIFO par
l'app React (pas par le service worker) au retour du réseau. Deux invariants de sûreté :
1. un élément n'est supprimé que sur réponse 2xx ou 409 (jamais sur erreur) ;
2. un conflit (422 — contrat annulé/soldé/montant excédentaire) est **terminal**, jamais rejoué
   automatiquement, le vendeur doit le retirer explicitement après l'avoir noté.

Un 401 (JWT expiré, durée de vie 12h) interrompt le lot sans purger la file ni rediriger
brutalement — la synchronisation reprend après reconnexion.

---

## Tests — ce qui existe et ce qui n'existe pas

**Backend** : 294 tests, tous **unitaires** (Mockito) ou `@WebMvcTest` de sécurité
(`AbstractWebMvcSecurityTest`). **Aucun `@SpringBootTest`, aucun H2/Testcontainers** — pas un
seul test n'exécute de vraie requête SQL contre un moteur de base. `mvn test` depuis `backend/`.

**Frontend** : 16 tests **Vitest**, `environment: 'node'`, strictement limités aux modules purs
`src/offline/queue.ts` et `src/offline/sync.ts` (là où un bug perd ou double de l'argent).
**Aucune infrastructure de test de composant** : pas de jsdom, pas de Testing Library, pas de
`@testing-library/react` dans `package.json`. `npm run test` depuis `frontend/`.

**Conséquence directe** : sur cette session, c'est la **relecture humaine du diff** (via les
reviewers du pipeline `/bolt`, voir plus bas) qui a rattrapé 5 défauts bloquants que la suite de
tests ne voyait pas — deux régressions de démarrage/connexion et deux fuites de données
inter-boutiques sur le ticket #10, un risque de double encaissement sur le ticket #11. Aucun
filet automatique n'aurait détecté ces cas. **C'est le premier chantier de dette technique
recommandé avant d'empiler de nouvelles fonctionnalités.**

---

## Comment le développement a été mené — pipeline `/bolt`

Les tickets #6 à #11 ont été livrés via un pipeline en 4 étapes (`.claude/agents/bolt-*.md`) :
**architecte** (note de conception dans `docs/bolts/<n>-<slug>/design.md`) → **spec-writer**
(spécification actionnable dans `spec.md`) → **codeur** (implémentation + tests, commits par
étape) → **reviewer** (verdict `APPROVE`/`CHANGES_REQUESTED` dans `review.md`, une seule passe de
correction autorisée si rejet). Chaque dossier `docs/bolts/<n>-<slug>/` garde la trace complète
du raisonnement — design, spec, review — et vaut la peine d'être consulté avant de retoucher la
fonctionnalité correspondante : plusieurs contiennent des sections « Écarts identifiés » qui
documentent des hypothèses du ticket original invalidées après lecture du code réel (ex. le
ticket #11 supposait un verrou optimiste `@Version` qui n'existait pas ; le vrai préalable était
l'idempotence).

Le pipeline ne mergeait jamais automatiquement : chaque PR restait en `draft`, revue humaine
obligatoire avant merge.

---

## Points de vigilance connus

- **HTTPS requis pour le mode hors-ligne complet** (lecture hors-ligne, installabilité). Le
  déploiement client doit suivre la procédure `certs/` du README avant mise en service si l'usage
  terrain hors-ligne est réel.
- **La checklist manuelle de #11** (`docs/bolts/11-mode-hors-ligne-synchronisation/spec.md`,
  section finale, points M1-M10) n'a pas encore été exécutée intégralement par un humain avec
  DevTools — en particulier M5 (conflit provoqué entre deux sessions) et M9 (expiration JWT
  pendant une tournée), qui couvrent directement le risque de double encaissement.
- **Absence de tests d'intégration** (voir section Tests ci-dessus) — dette technique n°1.
- **Migration V8** : trou permanent dans la numérotation, ne jamais tenter de le combler.
- **README.md à la racine est obsolète** : il décrit le périmètre du MVP avant les tickets #1-#11
  (pas de mention RBAC, audit, pénalités, WhatsApp, garant, fournisseurs, multi-boutiques,
  statistiques avancées, mode hors-ligne). Ce fichier `contexte.md` fait foi pour l'état actuel ;
  une mise à jour du README serait utile mais n'a pas été faite.

# CreditFlow — Gestion des ventes à crédit

Application web complète pour une boutique qui vend des téléphones et ordinateurs à crédit :
clients, produits, contrats, échéanciers, paiements, relances et rapports.

MVP mono-boutique. Le code est organisé par modules métier pour évoluer vers un SaaS
multi-boutiques sans refonte.

---

## Démarrage en une commande

```bash
docker compose up --build
```

| Service | URL | Détail |
|---|---|---|
| Application | http://localhost:3010 | Interface React |
| API | http://localhost:8080/api | REST |
| Swagger | http://localhost:8080/swagger-ui.html | Documentation interactive |
| PostgreSQL | localhost:5432 | base `creditflow` |

**Compte administrateur créé automatiquement : `admin` / `admin123`.**

Au premier démarrage, la base est remplie avec un jeu de démonstration :
1 administrateur, 10 clients, 15 produits, 20 contrats et 30 paiements.
Mettre `DEMO_SEED=false` pour démarrer avec une base vide.

Copiez `.env.example` vers `.env` pour changer les mots de passe, les ports ou le nom de la boutique.

---

## Installation chez un client réel

Le démarrage par défaut est un **mode démonstration** : secrets publics et jeu de données
fictif. Pour une vraie boutique :

```bash
cp .env.production.example .env
# remplacez toutes les valeurs marquées « A CHANGER »
docker compose up -d --build
```

Le profil `prod` **refuse de démarrer** tant qu'un secret de livraison subsiste
(`JWT_SECRET`, `ADMIN_PASSWORD`, `DB_APP_PASSWORD`, `DB_MIGRATION_PASSWORD`) ou que `DEMO_SEED`
vaut `true`. Le message d'erreur indique précisément quoi corriger.

À la première connexion, le commerçant doit choisir son propre mot de passe : l'installateur
ne conserve donc aucun accès.

### Sauvegardes

Un service `backup` tourne en permanence : une sauvegarde au démarrage, puis toutes les
24 h dans `./backups/`, avec 14 jours de rétention (`BACKUP_INTERVAL_HOURS`,
`BACKUP_RETENTION_DAYS`).

```bash
./scripts/backup.sh                                   # sauvegarde immédiate
./scripts/restore.sh backups/creditflow-<date>.sql.gz # restauration
```

> **Le dossier `./backups` doit être recopié hors de la machine** (disque externe, cloud).
> Une sauvegarde qui reste sur le disque qui tombe en panne ne protège de rien.
> Testez une restauration avant la mise en service, puis une fois par trimestre.

### HTTPS

Dès que `certs/fullchain.pem` et `certs/privkey.pem` existent, le frontend bascule
automatiquement en HTTPS et redirige le trafic en clair — sans reconstruction d'image.

```bash
./scripts/generate-self-signed-cert.sh creditflow.local   # réseau local
docker compose up -d --force-recreate frontend
```

Pour un accès depuis Internet, déposez un certificat Let's Encrypt sous les mêmes noms.

### Fuseau horaire

`TZ` (par défaut `Africa/Dakar`) s'applique à la base, au backend et aux sauvegardes.
C'est ce réglage qui détermine ce qu'est « aujourd'hui » pour les paiements du jour
et le calcul des retards.

---

## Reprise de l'existant

Une boutique qui démarre a déjà des crédits en cours sur son cahier. L'écran
**Reprise de données** les importe depuis un fichier CSV ou Excel :

1. Télécharger le modèle (colonnes attendues + exemples)
2. **Simuler** — rien n'est écrit ; les lignes à corriger sont listées une par une
3. **Confirmer** — import en **tout ou rien** : une seule ligne invalide annule l'ensemble

Pour chaque ligne, le système crée le client (s'il est nouveau), le produit, le contrat avec
son échéancier, et enregistre le cumul déjà versé (colonne `deja_paye`), qui s'impute
automatiquement sur les premières échéances.

---

## Développement local

### Backend

```bash
cd backend
mvn spring-boot:run          # nécessite PostgreSQL sur localhost:5432
mvn test                     # tests unitaires
```

Variables utiles : `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`,
`DEMO_SEED`, `SHOP_NAME`, `UPLOAD_DIR`.

### Frontend

```bash
cd frontend
npm install
npm run dev                  # http://localhost:5173, proxy /api vers le backend
npm run build
```

---

## Stack

**Backend** — Java 21, Spring Boot 3.5, Spring Security + JWT, Spring Data JPA, PostgreSQL,
Flyway, Lombok, MapStruct, Bean Validation, springdoc (Swagger), Apache POI (Excel),
OpenPDF (PDF).

**Frontend** — React 18, Vite, TypeScript, Material UI, React Router, TanStack Query,
React Hook Form, Axios.

**Infra** — Docker, Docker Compose, nginx.

---

## Architecture

Organisation par module métier ; chaque module contient son domaine, son accès aux données,
ses services, ses DTO et ses contrôleurs.

```
backend/src/main/java/com/creditflow/
├── auth/          authentification, JWT, création de l'administrateur
├── customer/      clients, fiche 360 (achats + paiements)
├── product/       catalogue
├── sale/          contrats de crédit, génération de l'échéancier
├── payment/       versements, imputation FIFO sur les échéances
├── dashboard/     agrégats du tableau de bord
├── notification/  relances (message généré, clients en retard)
├── report/        rapports + exports PDF / Excel
├── search/        recherche globale
├── common/        exceptions, pagination, stockage de fichiers, utilitaires monétaires
├── config/        sécurité, CORS, OpenAPI, propriétés
└── bootstrap/     données de démonstration
```

### Choix de conception

- **Aucun `ddl-auto`.** Le schéma vient exclusivement des migrations Flyway
  (`backend/src/main/resources/db/migration`).
- **Calcul de l'échéancier isolé** dans `InstallmentScheduleGenerator` : une classe pure,
  sans dépendance à la base, donc testable directement. Les mensualités sont arrondies à
  l'unité FCFA et la dernière échéance absorbe le reliquat, si bien que la somme des
  échéances est toujours exactement égale au montant financé.
- **Imputation des paiements isolée** dans `PaymentAllocator` : un versement solde les
  échéances de la plus ancienne à la plus récente. L'annulation d'un versement remet
  l'échéancier à zéro et rejoue les versements restants.
- **Les retards sont calculés à la lecture** (date d'échéance dépassée et échéance non
  soldée), pas stockés : aucune tâche planifiée ne peut désynchroniser l'affichage.
- **Recherches et filtres en JPA Specifications** (`*Specifications`) : un filtre absent ne
  produit aucun prédicat, donc la requête SQL ne contient que les critères réellement
  utilisés. Cela évite aussi les conditions `:param IS NULL`, que PostgreSQL refuse quand il
  ne peut pas déduire le type du paramètre.
- **Exports extensibles** : ajouter un format = ajouter une implémentation de
  `ReportExporter`, sans modifier le contrôleur.
- **Relances extensibles** : `NotificationChannel` isole l'envoi. Le MVP fournit
  `ManualCopyChannel` (copier-coller). Pour brancher WhatsApp Cloud API, ajoutez une
  implémentation conditionnée par `app.notification.channel=whatsapp`.
- **Vers le multi-boutiques** : les tables et les modules sont indépendants ; l'ajout d'une
  colonne `shop_id` et d'un filtre transverse suffira.

---

## Fonctionnalités

**Tableau de bord** — nombre de clients, ventes à crédit, montant restant à récupérer,
encaissé du mois, clients en retard, paiements du jour, prochaines échéances.

**Clients** — CRUD complet (nom, prénom, téléphone, adresse, CNI, profession, photo),
recherche rapide, historique d'achats et de paiements.

**Produits** — CRUD complet (nom, catégorie, prix comptant, prix à crédit, stock,
description, statut). Le stock passe automatiquement en rupture à zéro.

**Vente à crédit** — sélection client + produit, saisie prix / acompte / mensualités /
date de début ; le système calcule le montant restant, la mensualité, toutes les échéances,
la date de fin et le statut. Simulation de l'échéancier en direct avant enregistrement.

**Paiements** — enregistrement en 3 clics maximum depuis le tableau de bord, la liste des
contrats, les échéances ou le tableau des retards. Mise à jour automatique des échéances,
du contrat et du tableau de bord. **Reçu PDF** édité dans la foulée pour le client.
Annulation possible avec recalcul complet.

**Échéances** — liste complète avec client, produit, date prévue, montant, statut et retard,
recherche et filtres (statut, période, en retard uniquement).

**Relances** — tableau des clients en retard (jours de retard, montant, téléphone) et bouton
« Générer la relance » qui produit le message à copier. Aucun envoi automatique dans le MVP.

**Rapports** — paiements du jour, paiements du mois, clients en retard, créances restantes,
avec export PDF et Excel.

**Recherche globale** — nom, téléphone, produit, référence de contrat.

---

## API REST

Documentation complète et testable : http://localhost:8080/swagger-ui.html

| Méthode | Route | Description |
|---|---|---|
| POST | `/api/auth/login` | Connexion, retourne un JWT |
| GET | `/api/auth/me` | Profil courant |
| POST | `/api/auth/logout` | Déconnexion |
| GET | `/api/dashboard` | Indicateurs du tableau de bord |
| GET/POST | `/api/customers` | Lister / créer un client |
| GET/PUT/DELETE | `/api/customers/{id}` | Consulter / modifier / supprimer |
| GET | `/api/customers/{id}/profile` | Fiche 360 |
| POST | `/api/customers/{id}/photo` | Téléverser la photo |
| GET/POST | `/api/products` | Lister / créer un produit |
| GET/PUT/DELETE | `/api/products/{id}` | Consulter / modifier / supprimer |
| GET/POST | `/api/sales` | Lister / créer un contrat |
| POST | `/api/sales/preview` | Simuler un échéancier |
| GET | `/api/sales/{id}/detail` | Contrat + échéances + paiements |
| POST | `/api/sales/{id}/cancel` | Annuler un contrat |
| GET | `/api/installments` | Échéances (recherche + filtres) |
| GET | `/api/installments/late` | Échéances en retard |
| GET/POST | `/api/payments` | Lister / enregistrer un versement |
| DELETE | `/api/payments/{id}` | Annuler un versement |
| POST | `/api/reminders/generate` | Générer un message de relance |
| GET | `/api/reminders/late-customers` | Clients en retard |
| GET | `/api/reports/{type}` | Rapport (JSON) |
| GET | `/api/reports/{type}/export?format=pdf\|excel` | Export |
| GET | `/api/search?q=` | Recherche globale |

Toutes les routes sauf `/api/auth/login`, Swagger et `/actuator/health` exigent un en-tête
`Authorization: Bearer <token>`.

---

## Tests

```bash
cd backend && mvn test
```

32 tests unitaires couvrent le calcul des échéanciers, l'imputation des paiements et la
détection des retards, les règles métier des versements (surpaiement, contrat annulé, date
future, passage au statut soldé), la validation des clients (doublons téléphone / CNI),
la génération / validation des jetons JWT, les messages de relance et la construction des
critères de recherche.

---

## Présentations

Deux supports PowerPoint sont fournis dans [docs/](docs/) :

| Fichier | Usage | Contenu |
|---|---|---|
| `CreditFlow-Pitch.pptx` | Argumentaire commercial | 13 diapositives : problème, solution, bénéfices, feuille de route |
| `CreditFlow-Demo-Fonctionnalites.pptx` | Démonstration guidée | 19 diapositives : parcours en 14 étapes, avec notes de l'orateur |

Le second support est conçu pour être suivi écran par écran pendant une démonstration
live : chaque étape indique ce qu'il faut cliquer, ce qu'il faut souligner, et contient
des notes de présentation (visibles en mode Présentateur).

Pour les régénérer après modification :

```bash
pip install python-pptx
python docs/generate_presentations.py
```

---

## Sécurité

Avant une mise en production, modifiez impérativement dans `.env` :
`JWT_SECRET` (32 caractères minimum), `ADMIN_PASSWORD`, `DB_APP_PASSWORD` (rôle applicatif
restreint utilisé par le backend) et `DB_MIGRATION_PASSWORD` (rôle de migration Flyway,
propriétaire des tables).

### Isolation Postgres par organisation (Row-Level Security, #40)

En plus du filtrage applicatif, la base impose une isolation multi-tenant au niveau Postgres
(Row-Level Security) : le backend se connecte avec un rôle dédié restreint (`creditflow_app`,
non superuser, non propriétaire des tables), et chaque requête ne voit que les données de
l'organisation courante, même en cas d'oubli d'un filtre côté code.

#### Mise à niveau vers le rôle applicatif restreint (#40) — instances déjà déployées

Sur une instance Docker Compose déjà lancée, le script `docker-entrypoint-initdb.d` ne se
rejoue pas automatiquement (il ne s'exécute qu'à la toute première initialisation du volume
Postgres). Avant de mettre à jour vers cette version :

1. Se connecter en superuser : `docker compose exec db psql -U <DB_USERNAME actuel> -d <DB_NAME>`
2. Exécuter :
   ```sql
   CREATE ROLE creditflow_app LOGIN PASSWORD '<mot de passe choisi>';
   GRANT CONNECT ON DATABASE <DB_NAME> TO creditflow_app;
   GRANT USAGE ON SCHEMA public TO creditflow_app;
   ```
3. Dans `.env` : renommez `DB_USERNAME`/`DB_PASSWORD` actuels en `DB_MIGRATION_USERNAME`/
   `DB_MIGRATION_PASSWORD`, ajoutez `DB_APP_USERNAME=creditflow_app` et
   `DB_APP_PASSWORD=<le mot de passe choisi à l'étape 2>`.
4. `docker compose up -d --build` (Flyway applique les migrations avec les identifiants de
   migration ; le backend démarre ensuite avec les identifiants applicatifs restreints).

Sans l'étape 2, le démarrage échoue au moment de la migration `V16__app_role_grants.sql`
(`GRANT ... TO creditflow_app` sur un rôle inexistant).

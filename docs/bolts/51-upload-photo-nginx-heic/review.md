# Review #51 - Upload de photo client : limite nginx et formats HEIC

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Photo smartphone 8-10 Mo s'uploade avec succès | Couvert | `client_max_body_size` relevé à `12m` (`frontend/nginx/locations.conf:10`), aligné sur `max-request-size: 12MB` backend (inchangé). Vérifié fonctionnellement : conteneur nginx réel construit à partir du `locations.conf` de la branche + backend factice, requête PUT de 9 Mo → `200 OK` (passthrough, pas de coupure). Côté backend, non-régression confirmée par la suite existante (`DocumentValidationTest`, `UploadSizeGuardFilterTest`), non modifiée. |
| 2 | Photo HEIC/HEIF acceptée (conversion) ou refusée avec message explicite/actionnable | Couvert | Tranché en spec : refus ciblé. `DocumentValidation.java` teste `HEIC_EXTENSIONS` avant `ALLOWED_EXTENSIONS` et lève `BusinessRuleException` avec message actionnable (étapes iPhone incluses). Deux tests unitaires dédiés (`rejectsHeicExtension`, `rejectsHeifExtension`) qui échoueraient si le bloc `if (HEIC_EXTENSIONS...)` était retiré (message reviendrait au générique "Format d'image non supporte", qui ne contient ni "HEIC/HEIF" ni "Convertissez"). |
| 3 | Tout rejet (proxy ou backend) affiche un message visible, plus jamais silencieux | Couvert | Backend HEIC → `BusinessRuleException` → 422 JSON via `GlobalExceptionHandler.handleBusiness` (déjà mappé, testé). nginx > 12 Mo → vérifié fonctionnellement : `curl` sur fichier de 13 Mo renvoie `413` avec `Content-Type: application/json` et corps `{"status":413,"error":"Payload Too Large","message":"..."}` — pas de page HTML nginx par défaut. Backend 10-12 Mo → couvert par `UploadSizeGuardFilterTest` existant, non modifié. |
| 4 | Non-régression jpg/png/webp < 8 Mo | Couvert | `DocumentValidationTest.acceptsValidPng/Jpeg/Webp` toujours verts (le bloc HEIC est un `if` supplémentaire placé avant, ne modifie pas le chemin existant). Suite complète backend 381/381 verte. |

## Findings

Aucun finding bloquant. Une observation non bloquante ci-dessous, à titre informatif seulement — ne remet pas en cause l'approbation.

- **Note (non bloquante)** : le message JSON du 413 nginx (`frontend/nginx/locations.conf:82`) annonce "10 Mo" alors que le seuil qui a réellement déclenché ce 413 est celui de nginx, 12 Mo (`client_max_body_size`). C'est un choix assumé et documenté explicitement dans `design.md` ("Décisions clés" et "JSON d'erreur nginx statique") : cohérence du message perçu par l'utilisateur (la vraie limite métier reste 10 Mo) plutôt que précision technique sur la couche qui a rejeté. Cas limite peu probable pour une photo unique. Je considère la justification suffisante, dans la continuité de l'écart sur la duplication du message déjà tranché en spec — pas un motif de CHANGES_REQUESTED.

## Vérifications complémentaires effectuées

- Diff `master..HEAD` limité aux fichiers attendus : `frontend/nginx/locations.conf`, `backend/.../DocumentValidation.java`, `backend/.../DocumentValidationTest.java`, plus `design.md`/`spec.md` (étapes précédentes du pipeline). Aucun fichier hors périmètre touché (`application.yml`, `UploadSizeGuardFilter.java`, `GlobalExceptionHandler.java`, `fileValidation.ts`, `client.ts`, `CustomerDetailPage.tsx` : diff vide, confirmé).
- Cohérence code Java : `HEIC_EXTENSIONS` réutilise `extensionOf()` qui lowercase déjà via `Locale.ROOT` (pas de nouvelle logique de casse), aucun import ajouté, style cohérent avec le reste du fichier, pas d'ajout à `ALLOWED_EXTENSIONS` ni à `matchesExtension` (conforme à la spec — pas de magic-bytes HEIC).
- Callers de `DocumentValidation.validate()` : `S3DocumentStorage` et `LocalDiskStorage`, tous deux impactés par le nouveau message (cohérent avec la spec qui documente cet effet de bord sur les pièces jointes de vente).
- Syntaxe nginx validée avec `nginx -t` (image `nginx:1.27-alpine` via Docker, config assemblée avec `nginx.conf` + `conf.d/http.conf` + le `locations.conf` réel de la branche) : `syntax is ok` / `test is successful`.
- Test fonctionnel nginx de bout en bout (Docker : conteneur nginx avec le `locations.conf` réel + faux backend HTTP) :
  - PUT 13 Mo → `413`, `Content-Type: application/json`, corps JSON exploitable par `errorMessage()` frontend.
  - PUT 9 Mo (fourchette AC1) → `200`, passthrough correct vers le backend.
  - PUT 5 Mo → `200`, passthrough correct (non-régression sous l'ancien seuil).
- `client_max_body_size 12m;` reste dans le bloc commun (ligne 10), donc s'applique à toutes les locations comme avant (8m) — pas de changement de portée.
- `error_page 413 = @api_413;` scopé uniquement à `location /api/` (lignes 68-77), placé avant `location @api_413` (interne, `internal;`), lui-même placé avant `location /uploads/` — conforme à la spec, `/uploads/`, `/swagger-ui/`, `/v3/api-docs`, assets statiques non touchés (diff confirmé, 9 lignes ajoutées, aucune ligne modifiée hors bloc `/api/` et son voisinage immédiat).
- `application.yml` : `max-file-size: 10MB` / `max-request-size: 12MB` (lignes 43-44) confirmés inchangés. `max-swallow-size: 15MB` (ligne 6, Tomcat) confirmé inchangé — non-régression #44 respectée.

## Build/tests

- `mvn -Dtest=DocumentValidationTest test` (backend) → **8/8 tests passés**, `BUILD SUCCESS`.
- `mvn test` (backend, suite complète) → **381/381 tests passés**, `BUILD SUCCESS`, aucune régression détectée (inclut `UploadSizeGuardFilterTest`, `ReportControllerSecurityTest`, etc.).
- `nginx -t` (Docker, `nginx:1.27-alpine`, config réelle de la branche + upstream factice pour la résolution DNS) → **syntax is ok / test is successful**.
- Test fonctionnel nginx de bout en bout (Docker, nginx réel + backend HTTP factice sur le même réseau) → comportements 413/200 conformes aux AC1 et AC3 (détails ci-dessus).
- Frontend : aucun fichier frontend modifié par ce bolt (hors nginx), pas de build/tests frontend nécessaires pour ce périmètre.


# Review — Issue #65 : limitation de débit sur /api/auth/login

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Une série de tentatives automatisées et rapprochées sur `/api/auth/login` est ralentie/bloquée (nginx ou applicatif) | Couvert | `limit_req_zone` (5r/m, burst=10 nodelay) sur `location = /api/auth/login` ; reproduit en conditions réelles (voir Build/tests) : 11 requêtes passent, puis `429` avec le corps JSON attendu. Couche applicative : `AuthServiceTest.locksAccountAfterMaxConsecutiveFailures` et verrouillage réel observé en stack Docker après 5 échecs (`admin` verrouillé, log `Compte admin verrouille apres 5 echecs consecutifs`). |
| 2 | Un utilisateur légitime qui se trompe occasionnellement n'est pas bloqué de façon disproportionnée | Couvert | Seuil 5 essais / verrou 15 min (valeurs par défaut raisonnables, configurables via `LOGIN_MAX_ATTEMPTS`/`LOGIN_LOCKOUT_MINUTES`) ; `AuthServiceTest.resetsFailedAttemptsOnSuccessfulLogin` et `unlocksAutomaticallyAfterLockoutExpires` prouvent que le compteur ne s'accumule pas indéfiniment et qu'un succès ou l'expiration du verrou rétablit l'accès normal. |
| 3 | Le message d'erreur ne distingue pas "compte inexistant" de "mot de passe incorrect" (ni "compte verrouillé") | Couvert | `AuthService.login()` lève `BadCredentialsException` (pas `ResourceNotFoundException`) pour un compte inexistant, `LockedException` pour un compte verrouillé ; `GlobalExceptionHandler.handleAuthentication` mappe toute `AuthenticationException` en 401 "Identifiants invalides" codé en dur (message de l'exception ignoré). Testé unitairement (`throwsBadCredentialsForUnknownUsername`, `rejectsLoginImmediatelyWhenAccountIsLocked`, `GlobalExceptionHandlerTest.handlesLockedExceptionLikeAnyOtherAuthenticationFailure`) et vérifié en conditions réelles : réponses JSON strictement identiques (`{"status":401,"error":"Unauthorized","message":"Identifiants invalides",...}`) pour un compte inexistant et un mauvais mot de passe sur le même compte réel. |

## Analyse du diff

Diff réel (`git diff master...HEAD`, hors `design.md`/`spec.md`) : 12 fichiers, strictement le périmètre annoncé par la spec (migration `V20`, `User`, `AppProperties`, `application.yml`, `.env*.example`, `AuthService`, 2 fichiers de tests, `frontend/Dockerfile`, `nginx/locations.conf`, `nginx/ratelimit.conf`). Rien hors périmètre n'a été touché.

Le code produit correspond mot pour mot au contrat technique du `spec.md` (diffs Java/SQL/nginx). Point de sécurité critique du ticket vérifié explicitement :
- `AuthService.java` : `userRepository.findByUsernameIgnoreCase(...).orElseThrow(() -> new BadCredentialsException("Identifiants invalides"))` — confirmé, pas de `ResourceNotFoundException` sur ce chemin.
- `AuthServiceTest.throwsBadCredentialsForUnknownUsername` fait explicitement `.isInstanceOf(BadCredentialsException.class).isNotInstanceOf(ResourceNotFoundException.class)` : ce test échouerait immédiatement si on revenait à `ResourceNotFoundException`. Le critère d'acceptation n°3 est donc couvert par un test qui casserait avec la régression décrite.
- `GlobalExceptionHandler.handleAuthentication` capture `AuthenticationException` (classe mère de `BadCredentialsException` et `LockedException`) et renvoie toujours `HttpStatus.UNAUTHORIZED` + `"Identifiants invalides"` en dur, sans jamais lire `ex.getMessage()` — donc strictement identique pour les trois cas (compte inexistant / mauvais mot de passe / compte verrouillé). Confirmé par lecture du code et par test dédié.

`frontend/nginx/locations.conf` : `location = /api/auth/login` (correspondance exacte, donc prioritaire sur `location /api/` quel que soit l'ordre — comportement nginx standard) duplique exactement les 5 `proxy_set_header`/`proxy_http_version`/`proxy_read_timeout` du bloc `/api/` existant, sans divergence. Vérifié ligne à ligne par diff des deux blocs.

`frontend/nginx/ratelimit.conf` copié dans `/etc/nginx/conf.d/ratelimit.conf` au build (pas via le template runtime) : cohabite sans conflit avec `default.conf` généré par `docker-entrypoint.sh`, dans le contexte `http{}` par défaut de l'image `nginx:1.27-alpine`. Hypothèse vérifiée indépendamment (voir Build/tests).

Migration `V20__users_login_lockout.sql` : numérotation Flyway correcte (V19 = dernière existante), SQL valide (`ALTER TABLE ... ADD COLUMN`, pas de contrainte NOT NULL sans DEFAULT problématique), pas d'impact RLS/GRANT (table `users` hors RLS, grants déjà couverts par V16).

Point mineur relevé, non bloquant : `.env.example`/`.env.production.example` déclarent `LOGIN_MAX_ATTEMPTS`/`LOGIN_LOCKOUT_MINUTES`, mais `docker-compose.yml` ne les relaie pas explicitement dans `environment:` du service `backend` (contrairement à `JWT_EXPIRATION_MINUTES` par exemple). Un utilisateur qui modifierait ces valeurs dans son `.env` sans aussi éditer `docker-compose.yml` n'aurait aucun effet (le défaut Spring `5`/`15` s'appliquerait silencieusement). Ce n'est cependant pas un écart par rapport à la spec, qui ne demandait explicitement que la mise à jour des fichiers `.env*.example` — à considérer pour un futur ticket si la configurabilité en environnement Docker Compose est un besoin réel.

Note annexe du codeur : conteneur `creditflow-backup` en état `Restarting` — confirmé indépendamment lors du test en stack complète, non lié au diff de #65 (aucun fichier touchant `backup`/`scripts/backup-loop.sh` dans ce diff), effectivement hors périmètre.

## Build/tests

- `cd backend && mvn -o test` → **BUILD SUCCESS**, `Tests run: 424, Failures: 0, Errors: 0, Skipped: 0` (suite complète, y compris les 5 nouveaux tests `AuthServiceTest` et le nouveau test `GlobalExceptionHandlerTest`).
- `docker compose build backend frontend` → succès, deux images reconstruites sans erreur.
- `docker compose up -d` → `db`, `backend`, `frontend` démarrent `healthy` ; `backup` en `Restarting` (confirmé hors périmètre #65, cf. ci-dessus).
- `docker compose exec frontend nginx -t` → `syntax is ok` / `test is successful` : confirme indépendamment que `ratelimit.conf` se charge sans erreur dans le contexte `http{}` de l'image `nginx:1.27-alpine`.
- Test de charge réel contre `http://localhost:3010/api/auth/login` (15 requêtes POST rapprochées, identifiants invalides) : 11 réponses `401` puis 4 réponses `429` avec le corps `{"status":429,"error":"Too Many Requests","message":"Trop de tentatives de connexion, reessayez dans quelques instants."}` — cohérent avec `rate=5r/m, burst=10 nodelay`.
- Vérification croisée du message d'erreur : requête sur un compte inexistant et requête avec mauvais mot de passe sur `admin` renvoient un corps JSON strictement identique (`401`, `"Identifiants invalides"`).
- Vérification du verrouillage applicatif réel : les 10 requêtes à mauvais mot de passe contre `admin` (dans le test de charge ci-dessus) ont déclenché le verrou après le 5e échec (log backend : `Compte admin verrouille apres 5 echecs consecutifs`) ; une tentative ultérieure avec le **bon** mot de passe (`admin123`) a été rejetée avec la même réponse `401 Identifiants invalides`, confirmant le rejet immédiat sans distinction, tel que voulu.
- Vérification de non-régression sur le reste de l'API : `GET /api/customers` → `401` (comportement normal, non authentifié) ; `GET /swagger-ui/index.html` → `200` : la nouvelle `location = /api/auth/login` n'affecte pas les autres routes servies par `location /api/` ou les autres locations.

Aucune anomalie bloquante trouvée. Le pipeline est prêt pour revue humaine.

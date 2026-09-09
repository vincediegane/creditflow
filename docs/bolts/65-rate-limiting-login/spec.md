# Spec — Issue #65 : limitation de débit sur /api/auth/login

## Résumé

Deux défenses indépendantes sur `/api/auth/login` : un `limit_req` nginx par IP (couche edge) et un verrouillage temporaire par compte après N échecs consécutifs côté `AuthService` (couche applicative), sans jamais permettre de distinguer "compte inexistant" de "mot de passe incorrect" ou "compte verrouillé" dans la réponse HTTP.

## Tâches

- [ ] **Migration** — créer `backend/src/main/resources/db/migration/V20__users_login_lockout.sql` (SQL ci-dessous ; `V19` est la dernière migration existante, `V20` est donc le prochain numéro disponible).
- [ ] **Entité** — ajouter les champs `failedLoginAttempts` (int, `@Builder.Default` à `0`) et `lockedUntil` (`LocalDateTime`, nullable) à `backend/src/main/java/com/creditflow/auth/domain/User.java`.
- [ ] **Config** — ajouter la classe imbriquée `Login` (sous `Security`) à `backend/src/main/java/com/creditflow/config/AppProperties.java`.
- [ ] **application.yml** — ajouter le bloc `app.security.login`.
- [ ] **.env.example / .env.production.example** — ajouter les variables `LOGIN_MAX_ATTEMPTS=5`, `LOGIN_LOCKOUT_MINUTES=15`.
- [ ] **Service** — restructurer `login()` dans `backend/src/main/java/com/creditflow/auth/service/AuthService.java` : lookup avant `authenticate()`, rejet immédiat si verrouillé, incrémentation/verrouillage sur échec, reset sur succès (nouvelle méthode privée `registerFailedAttempt`).
- [ ] **nginx — zone** — créer `frontend/nginx/ratelimit.conf` (déclare `limit_req_zone` + `limit_req_status 429`, doit vivre dans le contexte `http{}`).
- [ ] **nginx — location** — ajouter dans `frontend/nginx/locations.conf` la `location = /api/auth/login` (avec `limit_req`) et la location interne `@api_429`.
- [ ] **Dockerfile frontend** — ajouter `COPY nginx/ratelimit.conf /etc/nginx/conf.d/ratelimit.conf` dans `frontend/Dockerfile`.
- [ ] **Tests unitaires** — étendre `backend/src/test/java/com/creditflow/auth/service/AuthServiceTest.java` (verrouillage après N échecs, déverrouillage à l'expiration, reset du compteur après succès, rejet immédiat d'un compte inexistant sans lever `ResourceNotFoundException`, rejet immédiat d'un compte verrouillé sans appeler `authenticate()`).
- [ ] **Test unitaire complémentaire** — étendre `backend/src/test/java/com/creditflow/common/exception/GlobalExceptionHandlerTest.java` pour prouver explicitement que `LockedException` produit la même réponse que `BadCredentialsException` (critère d'acceptation n°3, couche HTTP).
- [ ] **Vérification manuelle** — `docker compose build frontend` puis `nginx -t` dans le conteneur (confirme que `/etc/nginx/conf.d/*.conf` est bien inclus dans `http{}` par l'image `nginx:1.27-alpine` — hypothèse non vérifiée du design) ; puis test de charge simple (ex. boucle `curl` rapprochée) contre `/api/auth/login` pour confirmer le `429`.

## Contrat technique

### Migration SQL

`backend/src/main/resources/db/migration/V20__users_login_lockout.sql` :

```sql
-- =====================================================================
-- V20 - Verrouillage temporaire de compte apres echecs de connexion (#65)
-- failed_login_attempts : compteur d'echecs consecutifs, remis a zero a
-- chaque connexion reussie et au moment ou le verrou est pose (pas de
-- cumul indefini, cf. AuthService.registerFailedAttempt).
-- locked_until : nullable, NULL = pas de verrou actif. Compare a now()
-- cote application (AuthService.login), pas de contrainte SQL dediee.
-- `users` n'est pas soumise a RLS (V15), aucune policy a mettre a jour.
-- Aucun GRANT supplementaire requis : V16__app_role_grants.sql accorde
-- deja SELECT/INSERT/UPDATE/DELETE sur `users` au role applicatif.
-- =====================================================================

ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMP;
```

### Entité `User.java`

Avant (extrait) :
```java
    @Column(length = 255)
    private String email;

    @ManyToMany(fetch = FetchType.LAZY)
```

Après :
```java
    @Column(length = 255)
    private String email;

    /** Compteur d'echecs consecutifs, remis a zero au succes ou au moment du verrouillage (#65). */
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    /** NULL = pas de verrou actif. Compare a LocalDateTime.now() dans AuthService.login (#65). */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @ManyToMany(fetch = FetchType.LAZY)
```

Aucun autre site d'appel de `User.builder()` n'est impacté : ce sont des champs nommés (pas un record), les deux ont une valeur par défaut sûre (`0` / `null`).

### `AppProperties.java` — bloc `Login`

```java
    @Getter
    @Setter
    public static class Security {
        private Jwt jwt = new Jwt();
        private Login login = new Login();
        private boolean strict = true;
    }

    @Getter
    @Setter
    public static class Login {
        /** Nombre d'echecs consecutifs avant verrouillage temporaire du compte (#65). */
        private int maxAttempts = 5;
        /** Duree du verrouillage, en minutes, une fois le seuil atteint. */
        private int lockoutMinutes = 15;
    }
```

### `application.yml`

Sous le bloc `app.security` existant (au même niveau que `jwt:`) :
```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET:creditflow-super-secret-key-change-me-in-production-0123456789}
      expiration-minutes: ${JWT_EXPIRATION_MINUTES:720}
      issuer: creditflow
    login:
      max-attempts: ${LOGIN_MAX_ATTEMPTS:5}
      lockout-minutes: ${LOGIN_LOCKOUT_MINUTES:15}
```

### `.env.example` et `.env.production.example`

Ajouter à la suite du bloc `JWT_*`/`ADMIN_*` (section "Sécurité") :
```
LOGIN_MAX_ATTEMPTS=5
LOGIN_LOCKOUT_MINUTES=15
```

### `AuthService.java` — `login()` restructuré

Avant (extrait) :
```java
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        // Etape 1 : userRepository est un bean distinct d'AuthService -- cet appel ouvre sa
        // propre transaction/session (comportement par defaut de SimpleJpaRepository), meme
        // si login() n'est plus @Transactional. Ne touche que `users` (hors RLS).
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

        // user.getOrganization() est un proxy lazy : .getId() est lisible sans requete
        // supplementaire (l'id de la FK est deja connu), meme sur une entite detachee.
        TenantContext.set(user.getOrganization().getId());
        try {
```

Après :
```java
    public AuthResponse login(LoginRequest request) {
        // Etape 1 : lookup avant authenticate() (et donc avant tout calcul BCrypt), pour pouvoir
        // rejeter immediatement un compte verrouille sans relancer l'authentification (#65).
        // userRepository est un bean distinct d'AuthService -- cet appel ouvre sa propre
        // transaction/session (comportement par defaut de SimpleJpaRepository), meme si login()
        // n'est plus @Transactional. Ne touche que `users` (hors RLS).
        //
        // Compte inexistant : BadCredentialsException (pas ResourceNotFoundException) pour ne
        // jamais reveler cette information -- GlobalExceptionHandler.handleAuthentication mappe
        // toute AuthenticationException en 401 "Identifiants invalides", message strictement
        // identique aux deux autres cas d'echec ci-dessous (#65, critere d'acceptation n3).
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new BadCredentialsException("Identifiants invalides"));

        // Compte verrouille par registerFailedAttempt (#65) : rejet avant authenticate(), donc
        // sans cout BCrypt, avec le meme message que les deux autres cas d'echec.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new LockedException("Identifiants invalides");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            registerFailedAttempt(user);
            throw ex;
        }

        if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        // user.getOrganization() est un proxy lazy : .getId() est lisible sans requete
        // supplementaire (l'id de la FK est deja connu), meme sur une entite detachee.
        TenantContext.set(user.getOrganization().getId());
        try {
```

Le reste du corps (bloc `try`/`finally` avec `currentShopContext.reloadWithShopsInitialized(...)`, `jwtService.generateToken(...)`, `return new AuthResponse(...)`) est inchangé.

Nouvelle méthode privée, ajoutée après `login()` :
```java
    /**
     * Incremente le compteur d'echecs consecutifs de {@code user} et pose un verrou temporaire
     * (app.security.login.lockout-minutes) des que le seuil (app.security.login.max-attempts)
     * est atteint. Le compteur est remis a zero au moment ou le verrou est pose (#65) : pas de
     * cumul indefini, et une nouvelle serie complete de tentatives est disponible a l'expiration
     * du verrou.
     */
    private void registerFailedAttempt(User user) {
        AppProperties.Login loginConfig = properties.getSecurity().getLogin();
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= loginConfig.getMaxAttempts()) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(loginConfig.getLockoutMinutes()));
            log.warn("Compte {} verrouille apres {} echecs consecutifs", user.getUsername(), attempts);
        } else {
            user.setFailedLoginAttempts(attempts);
        }
        userRepository.save(user);
    }
```

Imports à ajouter : `org.springframework.security.authentication.BadCredentialsException`, `org.springframework.security.authentication.LockedException`, `org.springframework.security.core.AuthenticationException`. L'import `com.creditflow.common.exception.ResourceNotFoundException` reste nécessaire (`currentUser`, `changePassword`) — ne pas le supprimer.

**Décision tranchée (question laissée ouverte par le design) : `LockedException` pour le cas "compte verrouillé", `BadCredentialsException` pour "compte inexistant" et pour l'échec relayé tel quel depuis `authenticationManager.authenticate(...)`.** Justification : les deux exceptions héritent de `AuthenticationException` et sont interceptées de façon strictement identique par `GlobalExceptionHandler.handleAuthentication`, qui ignore `ex.getMessage()` et renvoie toujours `HttpStatus.UNAUTHORIZED` + `"Identifiants invalides"` codé en dur — donc aucune fuite d'information côté client quel que soit le type choisi. `LockedException` est en revanche le type sémantiquement correct de Spring Security pour "compte verrouillé" (distinct de `BadCredentialsException`), ce qui rend les logs serveur et les tests unitaires plus lisibles sans rien changer à la réponse HTTP.

### nginx — `frontend/nginx/ratelimit.conf` (nouveau)

```nginx
# Zone de limitation de debit pour /api/auth/login (#65).
# Cle $binary_remote_addr : forme binaire de l'IP cliente, compacte en memoire.
# 10m ~= 160 000 IP suivies simultanement, tres largement suffisant ici.
# rate=5r/m : 5 tentatives/minute en regime soutenu par IP. Complete (ne remplace
# pas) le verrou applicatif par compte (AuthService#65), qui protege un compte
# cible depuis plusieurs IP (ex. boutique partagee, botnet distribue).
# Premiere estimation non mesuree sur trafic reel -- a assouplir si des faux
# positifs legitimes sont rapportes (cf. Risques du design).
limit_req_zone $binary_remote_addr zone=login_zone:10m rate=5r/m;
limit_req_status 429;
```

Ce fichier doit être copié directement dans `/etc/nginx/conf.d/ratelimit.conf` **au build**, pas via le template runtime : `docker-entrypoint.sh` ne fait que copier `http.conf` ou `https.conf` vers `/etc/nginx/conf.d/default.conf` selon la présence de certificats — il ne vide jamais `/etc/nginx/conf.d/`, donc `ratelimit.conf` cohabite sans conflit avec `default.conf` généré au démarrage, dans le même contexte `http{}` inclus par la config de base de l'image `nginx:1.27-alpine`.

### nginx — `frontend/nginx/locations.conf`

Insérer, **avant** le bloc `location /api/ { ... }` existant (l'ordre n'a pas d'incidence fonctionnelle — une location `=` est toujours prioritaire sur un préfixe — mais ce placement documente l'intention) :

```nginx
# Limitation de debit sur la connexion (#65) : location exacte, donc prioritaire
# sur le prefixe /api/ ci-dessous quel que soit l'ordre d'ecriture (regle nginx :
# les locations "=" sont evaluees avant les prefixes). N'herite pas des directives
# proxy_* de /api/ (nginx n'herite pas les directives proxy_* entre locations
# distinctes) : dupliquees explicitement ci-dessous.
location = /api/auth/login {
    limit_req zone=login_zone burst=10 nodelay;
    error_page 429 = @api_429;

    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 120s;
}

location @api_429 {
    internal;
    default_type application/json;
    return 429 '{"status":429,"error":"Too Many Requests","message":"Trop de tentatives de connexion, reessayez dans quelques instants."}';
}
```

Le bloc `location /api/ { ... }` et `location @api_413 { ... }` existants restent inchangés en dessous.

### `frontend/Dockerfile`

Avant :
```dockerfile
COPY nginx/locations.conf /etc/nginx/snippets/locations.conf
COPY nginx/http.conf /etc/nginx/templates/http.conf
COPY nginx/https.conf /etc/nginx/templates/https.conf
COPY docker-entrypoint.sh /docker-entrypoint-creditflow.sh
```

Après :
```dockerfile
COPY nginx/locations.conf /etc/nginx/snippets/locations.conf
COPY nginx/http.conf /etc/nginx/templates/http.conf
COPY nginx/https.conf /etc/nginx/templates/https.conf
COPY nginx/ratelimit.conf /etc/nginx/conf.d/ratelimit.conf
COPY docker-entrypoint.sh /docker-entrypoint-creditflow.sh
```

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| 1. Une série de tentatives automatisées et rapprochées sur `/api/auth/login` est ralentie/bloquée (nginx ou applicatif) | **nginx** : vérification manuelle — `docker compose build frontend`, lancer une boucle de requêtes rapprochées (`for i in 1 2 3 4 5 6 7 8 9 10 11; do curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:3010/api/auth/login -d '{}' -H 'Content-Type: application/json'; done`) et confirmer l'apparition de `429` après le burst de 10. **Applicatif** : `AuthServiceTest.locksAccountAfterMaxConsecutiveFailures` (5 échecs simulés via `authenticationManager.authenticate` qui lève `BadCredentialsException` → `user.getLockedUntil()` non nul, `failedLoginAttempts` remis à `0`) + `AuthServiceTest.rejectsLoginImmediatelyWhenAccountIsLocked` (`lockedUntil` dans le futur → `assertThatThrownBy(...).isInstanceOf(LockedException.class)`, `verify(authenticationManager, never()).authenticate(any())`). |
| 2. Un utilisateur légitime qui se trompe occasionnellement de mot de passe n'est pas bloqué de façon disproportionnée | `AuthServiceTest.resetsFailedAttemptsOnSuccessfulLogin` (2 échecs puis 1 succès → `failedLoginAttempts == 0`, `lockedUntil == null`, connexion aboutit normalement) ; `AuthServiceTest.unlocksAutomaticallyAfterLockoutExpires` (`lockedUntil` dans le passé → `authenticationManager.authenticate` est bien appelé, la connexion aboutit si les identifiants sont corrects) ; revue manuelle des valeurs par défaut (`maxAttempts=5`, `lockoutMinutes=15`) comme compromis raisonnable pour une erreur de frappe occasionnelle. |
| 3. Le message d'erreur ne distingue pas "compte inexistant" de "mot de passe incorrect" (ni du 3e cas "compte verrouillé", introduit par ce ticket) | `AuthServiceTest.throwsBadCredentialsForUnknownUsername` (`userRepository.findByUsernameIgnoreCase` retourne vide → `BadCredentialsException`, pas `ResourceNotFoundException`) ; `AuthServiceTest.propagatesBadCredentialsForWrongPassword` (comportement existant, à conserver) ; `AuthServiceTest.rejectsLoginImmediatelyWhenAccountIsLocked` (ci-dessus, `LockedException`) ; `GlobalExceptionHandlerTest.handlesLockedExceptionLikeAnyOtherAuthenticationFailure` (nouveau : `handler.handleAuthentication(new LockedException("..."), request)` → `HttpStatus.UNAUTHORIZED` + message `"Identifiants invalides"`) — preuve que les 3 cas produisent la même réponse HTTP au niveau où le client la reçoit. |

### Détail des nouveaux/modifiés tests

**`AuthServiceTest.java`** — ajouter (après `loginResolvesAccessibleShopsWhileStillAnonymous`) :

```java
    @Test
    @DisplayName("verrouille le compte apres le nombre maximal d'echecs consecutifs")
    void locksAccountAfterMaxConsecutiveFailures() {
        when(properties.getSecurity()).thenReturn(new AppProperties.Security());
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Identifiants invalides"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "mauvais")))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("rejette immediatement une connexion sur un compte verrouille, sans reappeler authenticate()")
    void rejectsLoginImmediatelyWhenAccountIsLocked() {
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "MotDePasseInitial1")))
                .isInstanceOf(LockedException.class);

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("remet a zero le compteur d'echecs apres une connexion reussie")
    void resetsFailedAttemptsOnSuccessfulLogin() {
        user.setFailedLoginAttempts(2);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("autorise a nouveau une tentative une fois le verrou expire")
    void unlocksAutomaticallyAfterLockoutExpires() {
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");

        authService.login(new LoginRequest("admin", "MotDePasseInitial1"));

        verify(authenticationManager).authenticate(any());
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("rejette un identifiant inexistant sans reveler l'absence du compte")
    void throwsBadCredentialsForUnknownUsername() {
        when(userRepository.findByUsernameIgnoreCase("inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("inconnu", "peu importe")))
                .isInstanceOf(BadCredentialsException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);

        verify(authenticationManager, never()).authenticate(any());
    }
```

Imports supplémentaires requis : `org.springframework.security.authentication.BadCredentialsException`, `org.springframework.security.authentication.LockedException`, `com.creditflow.common.exception.ResourceNotFoundException`, `java.time.LocalDateTime`.

Ajustement nécessaire du `setUp()` existant : `properties` est un `@Mock` ; `locksAccountAfterMaxConsecutiveFailures` doit stubber `properties.getSecurity()` (les autres tests s'appuient sur la lenience déjà en place).

**`GlobalExceptionHandlerTest.java`** — ajouter :
```java
    @Test
    @DisplayName("renvoie la meme reponse pour un compte verrouille que pour tout autre echec d'authentification")
    void handlesLockedExceptionLikeAnyOtherAuthenticationFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/login");

        ResponseEntity<ApiError> response = handler.handleAuthentication(
                new org.springframework.security.authentication.LockedException("Compte verrouille"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo("Identifiants invalides");
    }
```

### Vérification manuelle (pas de test automatisé pertinent)

- `docker compose build frontend` puis `docker compose run --rm frontend nginx -t` (ou équivalent) pour confirmer que `ratelimit.conf` est bien chargé sans erreur de syntaxe dans le contexte `http{}` — hypothèse non vérifiée signalée par le design.
- Boucle `curl` rapprochée contre `/api/auth/login` (voir critère n°1 ci-dessus) pour confirmer visuellement l'apparition du `429` et le corps JSON renvoyé par `@api_429`.
- Connexion avec un mauvais mot de passe 5 fois de suite sur un compte réel (ex. `admin` en environnement de démo), confirmer le `401` identique aux tentatives précédentes (pas de code ou message différent au 5e essai côté client), puis réessayer avec le bon mot de passe après expiration du verrou (ou en avançant l'horloge/en modifiant temporairement `LOGIN_LOCKOUT_MINUTES` pour le test manuel).

## Écarts identifiés

- **Trou critique comblé** : le design ne traite pas explicitement ce qui doit se produire quand le lookup de l'utilisateur (déplacé avant `authenticate()`) échoue. Le code actuel jette `ResourceNotFoundException("Utilisateur introuvable")` dans ce cas — mappée par `GlobalExceptionHandler.handleNotFound` en **404** avec un message distinct de "Identifiants invalides". Avant la restructuration, cette branche était inatteignable en pratique (un utilisateur inexistant faisait déjà échouer `authenticate()` en amont via `AppUserDetailsService`/`DaoAuthenticationProvider`, avant d'atteindre le lookup). Après la restructuration proposée par le design (lookup en premier), cette branche devient systématiquement atteignable pour tout identifiant inexistant, et sans correction, l'application violerait directement le critère d'acceptation n°3 (un compte inexistant renverrait 404 + message explicite, distinguable d'un mauvais mot de passe qui renvoie 401). Tranché : remplacer `ResourceNotFoundException` par `BadCredentialsException("Identifiants invalides")` dans cette branche précise de `login()` (uniquement — `currentUser` et `changePassword` continuent d'utiliser `ResourceNotFoundException` sans changement, ces endpoints sont authentifiés et n'ont pas cette contrainte).
- Le design laisse explicitement ouvert le choix entre `LockedException` et `BadCredentialsException` pour le cas "compte verrouillé" — tranché ci-dessus (`LockedException`).
- Le design ne mentionne pas de test explicite prouvant, au niveau `GlobalExceptionHandler`, que les trois types d'exception (compte inexistant, mauvais mot de passe, compte verrouillé) produisent une réponse HTTP strictement identique. Ajouté `GlobalExceptionHandlerTest.handlesLockedExceptionLikeAnyOtherAuthenticationFailure` pour fournir une preuve automatisée directe du critère d'acceptation n°3, plutôt que de s'appuyer uniquement sur la lecture du code.

## Fichiers concernés (référence rapide)

- `backend/src/main/resources/db/migration/V20__users_login_lockout.sql` (nouveau)
- `backend/src/main/java/com/creditflow/auth/domain/User.java`
- `backend/src/main/java/com/creditflow/config/AppProperties.java`
- `backend/src/main/resources/application.yml`
- `.env.example`, `.env.production.example`
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java`
- `backend/src/test/java/com/creditflow/auth/service/AuthServiceTest.java`
- `backend/src/test/java/com/creditflow/common/exception/GlobalExceptionHandlerTest.java`
- `frontend/nginx/ratelimit.conf` (nouveau)
- `frontend/nginx/locations.conf`
- `frontend/Dockerfile`

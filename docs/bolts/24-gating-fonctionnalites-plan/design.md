# Design — #24 Gating des fonctionnalités par formule (plan) via configuration par instance

## Approche

Ajout d'un bloc `AppProperties.Plan` (même pattern que `Notification`/`Reminder`), lu depuis
`application.yml` via `PLAN_MULTI_SHOP` / `PLAN_WHATSAPP_AUTO`, défaut = `true` pour ne rien casser
sur les instances déjà en production. Deux flags seulement dans ce ticket, `multiShop` et
`whatsappAuto`, car ce sont les deux seuls couverts par les critères d'acceptation ; voir
Décisions clés pour `stockSuppliers`/`excelExport`. La garde `multiShop` s'insère dans
`ShopService` (création et mise à jour), sur le modèle des gardes de `CurrentShopContext` qui
lèvent `BusinessRuleException` (HTTP 422). La garde `whatsappAuto` ne peut pas être une garde
d'API runtime : `NOTIFICATION_CHANNEL=whatsapp` sélectionne un bean Spring
(`@ConditionalOnProperty` sur `WhatsAppCloudApiChannel`) au démarrage, il n'existe aucun endpoint
qui bascule ce canal à chaud. La garde correspondante est donc un validateur de démarrage qui
refuse de lancer l'application si la combinaison `NOTIFICATION_CHANNEL=whatsapp` +
`PLAN_WHATSAPP_AUTO=false` est détectée, sur le modèle exact de `SecurityDefaultsValidator`
(`@PostConstruct` plus `IllegalStateException`). Les entitlements sont exposés au frontend via
`AuthResponse`, pas un endpoint `/api/config` dédié, car ce pattern existe déjà pour
`accessibleShops` et le ticket cite explicitement ce fichier ; le prix payé est que les
entitlements ne se rafraîchissent qu'à la prochaine connexion, ce qui est acceptable car un
changement de formule est un événement commercial rare (édition manuelle du `.env` puis
redémarrage du conteneur), pas une action utilisateur en session.

## Fichiers/modules impactés

Backend :
- `backend/src/main/java/com/creditflow/config/AppProperties.java` — nouvelle classe imbriquée
  `Plan` (`multiShop` boolean, défaut `true` ; `whatsappAuto` boolean, défaut `true`) et champ
  `private Plan plan = new Plan();`.
- `backend/src/main/resources/application.yml` — nouveau bloc `app.plan` :
  `multi-shop: ${PLAN_MULTI_SHOP:true}` et `whatsapp-auto: ${PLAN_WHATSAPP_AUTO:true}`.
- `backend/src/main/java/com/creditflow/config/PlanConfigValidator.java` (nouveau) — `@Component`,
  `@PostConstruct`, refuse le démarrage (`IllegalStateException`, message dans le même style que
  `SecurityDefaultsValidator`) si `app.notification.channel=whatsapp` et
  `!app.plan.whatsappAuto`. Actif dans tous les profils, pas seulement en mode strict/prod : le
  gating de formule est une contrainte commerciale, indépendante du mode démo.
- `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` — deux méthodes
  nouvelles : `long countByActiveTrue();` et `boolean existsByActiveTrueAndIdNot(Long id);`.
- `backend/src/main/java/com/creditflow/shop/service/ShopService.java` — injection
  d'`AppProperties`, nouvelle méthode privée `assertPlanAllowsActive(Boolean requestedActive, Long
  excludingShopId)` appelée en tête de `create()` (avec `excludingShopId = null`) et de `update()`
  (avec `excludingShopId = id`) ; lève `BusinessRuleException` si la boutique visée serait active,
  que `!plan.isMultiShop()`, et qu'une autre boutique active existe déjà.
- `backend/src/main/java/com/creditflow/auth/dto/AuthResponse.java` — nouveau champ
  `PlanSummary plan`.
- `backend/src/main/java/com/creditflow/auth/dto/PlanSummary.java` (nouveau record) :
  `record PlanSummary(boolean multiShop, boolean whatsappAuto)`.
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java` — `login()` construit
  `PlanSummary` depuis `AppProperties.getPlan()` (nécessite l'injection d'`AppProperties`, absente
  aujourd'hui de ce service) et le passe au constructeur d'`AuthResponse`.
- `.env.example` et `.env.production.example` — nouvelle section avec `PLAN_MULTI_SHOP=true` et
  `PLAN_WHATSAPP_AUTO=true`, commentaire renvoyant à la formule vendue.
- `docker-compose.yml` — ajout de `PLAN_MULTI_SHOP: ${PLAN_MULTI_SHOP:-true}` et
  `PLAN_WHATSAPP_AUTO: ${PLAN_WHATSAPP_AUTO:-true}` au bloc `environment:` du service `backend`
  (voir Risques pour le gap préexistant sur `NOTIFICATION_CHANNEL`/`WHATSAPP_*`, absents de ce même
  bloc aujourd'hui).

Frontend :
- `frontend/src/types.ts` — nouveau type `PlanSummary { multiShop: boolean; whatsappAuto: boolean }`,
  ajouté au champ `plan` de `AuthResponse`.
- `frontend/src/api/client.ts` — nouvelle clé de stockage `PLAN_KEY = 'creditflow.plan'`.
- `frontend/src/auth/AuthContext.tsx` — nouvel état `plan` (lu/écrit comme `accessibleShops` :
  fonction `readStoredPlan()`, persistance dans `login()`, suppression dans `logout()`), exposé par
  `useAuth()`. Valeur par défaut si absente du stockage local (session ouverte avant ce
  déploiement) : `{ multiShop: true, whatsappAuto: true }`, cohérente avec le défaut backend.
- `frontend/src/pages/ShopsPage.tsx` — le bouton « Nouvelle boutique » du `PageHeader` est masqué
  quand `!plan.multiShop` (remplacé par un texte bref expliquant que la formule ne permet qu'une
  boutique).
- `frontend/src/pages/LateCustomersPage.tsx` et `frontend/src/components/ReminderDialog.tsx` :
  aucun changement de code. Ces écrans dérivent déjà leur affichage de `settings.data.channel`
  (issu de `GET /api/reminders/settings`), qui ne peut jamais valoir `WHATSAPP_CLOUD_API` sur une
  instance où `whatsappAuto=false` puisque `PlanConfigValidator` empêche ce démarrage. Voir
  Décisions clés.

## Décisions clés

- Flags couverts par ce ticket : `multiShop` et `whatsappAuto` uniquement. `stockSuppliers` et
  `excelExport`, mentionnés dans le périmètre proposé du ticket, sont laissés hors périmètre :
  aucun critère d'acceptation ne les teste, et les gater correctement demanderait d'identifier et
  de garder plusieurs contrôleurs supplémentaires (achats/fournisseurs, exports Excel/PDF) sans
  définition précise de ce qui doit être bloqué (contrôleur entier ou bouton d'export seul). Les
  ajouter maintenant gonflerait un ticket déjà qualifié de transversal, sans filet de test. Ils
  suivront le même pattern `AppProperties.Plan` dans un ticket dédié.
- Garde `multiShop` appliquée à la création ET à la mise à jour d'une boutique, pas seulement à la
  création comme le texte du ticket le suggère littéralement. Sans garder aussi `update()`, une
  instance en formule Essentiel pourrait contourner la restriction en créant une boutique inactive
  puis en la réactivant via `PUT /api/shops/{id}`. Le paramètre `excludingShopId` évite de bloquer
  à tort une mise à jour qui laisse inchangée la seule boutique déjà active.
- `PlanConfigValidator` est une classe séparée de `SecurityDefaultsValidator`, plutôt qu'ajoutée à
  celle-ci : `SecurityDefaultsValidator` ne bloque le démarrage que si `app.security.strict=true`
  (donc jamais en profil démo), pour des secrets de livraison. Le gating de formule est une
  contrainte commerciale indépendante du mode démo ou strict — une instance de démonstration
  commerciale doit, elle aussi, respecter la formule qu'on veut présenter au client. D'où un
  validateur distinct, actif dans tous les profils, sans dépendre de `app.security.strict`.
- Pas de garde runtime redondante dans `ReminderService` ou `WhatsAppCloudApiChannel` :
  `@ConditionalOnProperty(name = "app.notification.channel", havingValue = "whatsapp")` garantit
  déjà que `WhatsAppCloudApiChannel` n'existe comme bean que si le canal vaut explicitement
  `whatsapp`, et `PlanConfigValidator` empêche ce démarrage sous `whatsappAuto=false`. Une
  deuxième garde au niveau du service dupliquerait la même condition sans bénéfice. Source de
  vérité unique : le démarrage.
- Exposition via `AuthResponse`, pas un endpoint `/api/config` : réutilise le mécanisme déjà en
  place pour `accessibleShops` (résolu à la connexion, mis en cache localStorage,
  `refreshAccessibleShops` disponible si besoin de rafraîchir sans se reconnecter). Un endpoint
  séparé ajouterait une route, un hook de fetch et un cache supplémentaires pour une donnée qui ne
  varie qu'au redémarrage du conteneur.
- Aucune action de masquage frontend spécifique à `whatsappAuto` : la seule UI qui référence le
  canal automatique affiche déjà un état dérivé de l'API réelle (`channel`), et il n'existe aucun
  contrôle qui permette à un utilisateur de choisir ou d'activer le canal WhatsApp depuis
  l'interface (c'est une variable d'environnement, pas un réglage applicatif). `plan.whatsappAuto`
  est exposé côté frontend pour cohérence et usage futur, mais aucun appel API ne peut aujourd'hui
  échouer sur ce point précis : il n'y a pas de bouton à masquer.
- Message de `BusinessRuleException` pour le blocage `multiShop` orienté exploitant : par exemple
  « Votre formule actuelle ne permet qu'une seule boutique active. Contactez l'exploitant de la
  plateforme pour passer à la formule Multi-boutiques. », cohérent avec le ton des messages
  existants de `CurrentShopContext`.

## Risques / points d'attention

- Rétrocompatibilité stricte : toute instance existante sans `PLAN_MULTI_SHOP`/
  `PLAN_WHATSAPP_AUTO` dans son `.env` doit se comporter exactement comme avant (défaut `true` des
  deux côtés, backend et frontend). À vérifier explicitement : une instance ayant déjà plusieurs
  boutiques actives en base ne doit jamais être bloquée rétroactivement — la garde ne s'applique
  qu'aux nouvelles créations ou réactivations, jamais aux boutiques déjà actives.
- Cohérence backend/frontend : le frontend masque le bouton de création de boutique sur la base de
  l'entitlement chargé à la connexion, potentiellement périmé si l'admin a changé de formule sans
  que l'utilisateur se reconnecte. Le backend reste la seule source de vérité qui bloque
  réellement — le masquage frontend est un confort d'UX, pas un mécanisme de sécurité. Si l'API
  est appelée directement (contournement de l'UI), le message `BusinessRuleException` (HTTP 422)
  reste clair et actionnable.
- `docker-compose.yml` ne propage aujourd'hui ni `NOTIFICATION_CHANNEL` ni les variables
  `WHATSAPP_*` vers le conteneur `backend` (elles sont documentées dans `.env.example` et
  `.env.production.example` mais absentes du bloc `environment:` du service `backend`) : même si
  un exploitant règle `NOTIFICATION_CHANNEL=whatsapp` dans son `.env`, cela n'a aujourd'hui aucun
  effet en déploiement Docker, le backend retombe silencieusement sur le défaut `manual`. C'est un
  gap préexistant, indépendant de ce ticket, mais qui rend le critère d'acceptation n°2 impossible
  à valider de bout en bout en environnement Docker si on ne le corrige pas en même temps :
  `NOTIFICATION_CHANNEL` et les quatre variables `WHATSAPP_*` doivent être ajoutées au bloc
  `environment:` du service `backend` dans `docker-compose.yml`, en plus des deux nouvelles
  variables `PLAN_*`, sans quoi la garde de démarrage ne peut jamais être exercée en conditions
  réelles.
- `PlanConfigValidator` doit être couvert par un test d'intégration Spring Boot (contexte qui
  échoue à démarrer avec `app.notification.channel=whatsapp` et `app.plan.whatsapp-auto=false`),
  pas seulement un test unitaire de la classe isolée, pour vérifier que le `@PostConstruct`
  s'exécute bien après le chargement complet d'`AppProperties`.
- `ShopService.delete()` n'est pas concerné par la garde `multiShop` : réduire le nombre de
  boutiques actives n'est jamais un problème vis-à-vis du plan, seule la création ou réactivation
  d'une deuxième boutique active l'est.
- Frontend sans `plan` dans le stockage local après déploiement (utilisateurs déjà connectés avant
  la mise en production de ce ticket) : `AuthContext` doit retomber sur
  `{ multiShop: true, whatsappAuto: true }` par défaut plutôt que sur `undefined`, pour ne pas
  masquer à tort le bouton « Nouvelle boutique » chez un client qui a réellement le droit d'en
  créer une seconde, tant qu'il ne s'est pas reconnecté.

## Hors périmètre

- Flags `stockSuppliers` et `excelExport` (voir Décisions clés) : aucune garde backend, aucun champ
  `AppProperties.Plan`, aucun masquage frontend pour ces deux-là dans ce ticket.
- Tout système d'abonnement en base de données (table dédiée, cycle de facturation, expiration
  automatique) : le ticket exclut explicitement cette voie au profit d'une configuration par
  instance.
- Interface d'administration permettant de modifier la formule à chaud depuis l'application (par
  exemple un écran « Changer de formule ») : la formule reste pilotée exclusivement par `.env` et
  un redémarrage du conteneur, décision déjà actée par le ticket.
- Toute UI permettant de changer `NOTIFICATION_CHANNEL` depuis le frontend : ce réglage reste une
  variable d'environnement, aucun écran de configuration n'est demandé ni introduit.
- Rafraîchissement en session des entitlements de plan sans reconnexion (polling, WebSocket) : non
  demandé, un changement de formule est un événement rare géré par redémarrage.

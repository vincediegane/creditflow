# Review — #24 Gating des fonctionnalités par formule (plan) via configuration par instance

## APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | PLAN_MULTI_SHOP=false empeche la creation d'une seconde boutique active | Couvert | ShopService.assertPlanAllowsActive (backend/src/main/java/com/creditflow/shop/service/ShopService.java lignes 49,60,90-107), teste par ShopServiceTest.rejectsSecondActiveShopWhenPlanIsSingleShopOnCreate et .rejectsReactivationOfSecondShopWhenPlanIsSingleShop -- retrait du code entrainerait l'echec de ces tests. |
| 2 | PLAN_WHATSAPP_AUTO=false empeche l'activation du canal WhatsApp auto | Couvert | PlanConfigValidator.validate() (nouveau, @PostConstruct), teste par PlanConfigValidatorTest.refusesStartupWhenWhatsappChannelSelectedWithoutPlanEntitlement via ApplicationContextRunner -- le contexte echoue au demarrage. Confirme executable en Docker reel via docker compose config (gap preexistant corrige). |
| 3 | Le frontend masque les sections non incluses au lieu de laisser echouer un appel API | Couvert | ShopsPage.tsx : rendu conditionnel plan.multiShop ? Button : Chip (lignes 123-134). Pas de test automatise frontend (aucune infra de test de composants dans le repo, coherent avec le reste du projet). |
| 4 | Aucune regression sur les instances existantes (plan par defaut = tout active) | Couvert | AppProperties.Plan defauts true/true ; docker-compose.yml/.env.example/.env.production.example defauts true ; frontend DEFAULT_PLAN = multiShop:true, whatsappAuto:true jamais undefined/null. Teste par PlanConfigValidatorTest.allowsStartupWithDefaultConfiguration, ShopServiceTest.allowsSecondActiveShopWhenPlanIsMultiShop, et surtout allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan (voir verification prioritaire ci-dessous). |

## Verification prioritaire -- ecart wasActive par rapport a la spec litterale

Le raisonnement du codeur est correct et le correctif est bien implemente.

La spec (spec.md lignes 90-102) donne un algorithme assertPlanAllowsActive(requestedActive, excludingShopId) sans etat anterieur. Applique tel quel a update() avec assertPlanAllowsActive(effectiveActive(request, shop), id), sur une instance ayant deja 2+ boutiques actives (plan degrade apres coup) : une simple modification d'une boutique deja active, sans toucher active (donc effectiveActive renvoie l'etat courant true), declencherait existsByActiveTrueAndIdNot(id) -> true (d'autres boutiques actives existent) -> BusinessRuleException levee a tort. Ce comportement contredit explicitement l'invariant du design (design.md lignes 119-123 et 145-147) : une instance ayant deja plusieurs boutiques actives en base ne doit jamais etre bloquee retroactivement, la garde ne s'applique qu'aux nouvelles creations ou reactivations. La spec ne couvre ce cas que pour l'instance a une seule boutique active (excludingShopId exclut alors la boutique elle-meme, donc exists renvoie false naturellement) -- mais pas pour une instance a plusieurs boutiques actives, cas que le design traite explicitement comme un invariant a preserver.

Le correctif ajoute un parametre wasActive (ShopService.java lignes 90-105) :

    private void assertPlanAllowsActive(boolean requestedActive, boolean wasActive, Long excludingShopId) {
        if (!requestedActive || wasActive || properties.getPlan().isMultiShop()) {
            return;
        }
        ...
    }

- create() appelle avec wasActive = false (litteral) : une nouvelle boutique n'a jamais ete active avant, la garde s'applique normalement a toute creation active=true.
- update() appelle avec wasActive = shop.isActive() (etat avant application du patch) : si la boutique etait deja active, la garde ne s'execute jamais, quel que soit le nombre d'autres boutiques actives en base.

Verifie par lecture manuelle des 4 combinaisons (creation active/inactive, update avec/sans changement d'etat) -- coherent avec la semantique attendue. Le test ajoute allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan (ShopServiceTest.java) reproduit exactement le scenario contradictoire : boutique deja active, existsByActiveTrueAndIdNot stubbe a true (simulant une instance avec plusieurs boutiques actives et un plan degrade), update() ne leve pas d'exception. Execute (voir section Build/tests) : passe. En supprimant le parametre wasActive (retour a l'algorithme litteral de la spec), ce test echouerait -- bonne couverture de regression pour cet ecart.

Ecart bien justifie par une lecture attentive du design, pas une improvisation du codeur ; documente par une Javadoc claire sur assertPlanAllowsActive (lignes 90-95 de ShopService.java).

## Autres verifications ciblees

1. PlanConfigValidator actif dans tous les profils : confirme, aucune condition @Profile/strict/demo, contrairement a SecurityDefaultsValidator. Teste par les 4 cas d'ApplicationContextRunner (PlanConfigValidatorTest), y compris configuration par defaut (pas de regression).
2. delete() non garde, update d'une boutique multi-shop sans changement d'etat non bloque : confirme par lecture du diff (ShopService.java -- aucune modification de delete()) et par le test allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan.
3. AuthResponse.plan : bien renvoye par login() (AuthService.java lignes 50-55), PlanSummary construit depuis properties.getPlan(), teste par AuthServiceTest.loginIncludesPlan (valeurs par defaut true/true).
4. docker-compose.yml : verifie avec docker compose config (Docker disponible dans l'environnement) -- NOTIFICATION_CHANNEL, les 4 WHATSAPP_* et les 2 PLAN_* apparaissent bien resolus dans l'environnement du service backend (sortie complete inspectee, voir Build/tests).
5. Frontend : DEFAULT_PLAN toujours retourne par readStoredPlan() en cas d'absence ou de JSON invalide (jamais undefined/null) ; login() applique response.plan ou DEFAULT_PLAN (fallback nullish coalescing) avant persistance ; PLAN_KEY nettoye a la fois dans logout() (AuthContext.tsx) et dans l'intercepteur 401 (client.ts ligne 58). ShopsPage.tsx : Chip deja importe, pas de nouvel import MUI necessaire.
6. Pas de regression : SecurityDefaultsValidator, ReminderService, WhatsAppCloudApiChannel non modifies (git diff vide sur ces fichiers) -- coherent avec la decision du design de ne pas dupliquer la garde whatsappAuto en runtime.

## Coherence spec/design/code

Aucun ecart non justifie constate entre spec.md, design.md et le code livre, hormis le point wasActive analyse ci-dessus (justifie). Toutes les taches de la spec sont couvertes : AppProperties.Plan, application.yml, PlanConfigValidator + test, ShopRepository (2 methodes), ShopService (garde + 5 tests), PlanSummary, AuthResponse/AuthService + test, .env.example/.env.production.example/docker-compose.yml, frontend (types.ts, client.ts, AuthContext.tsx, ShopsPage.tsx). Flags stockSuppliers/excelExport correctement laisses hors perimetre, conformement aux Decisions cles du design.

## Build/tests

- mvn -o -Dtest=PlanConfigValidatorTest,ShopServiceTest,AuthServiceTest test (dans backend/) : BUILD SUCCESS, Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 (AuthServiceTest 7/7, PlanConfigValidatorTest 4/4, ShopServiceTest 9/9).
- mvn -o test (suite complete backend) : BUILD SUCCESS, Tests run: 322, Failures: 0, Errors: 0, Skipped: 0.
- npm run lint (frontend, tsc --noEmit) : OK, aucune erreur.
- npm run build (frontend, tsc --noEmit puis vite build) : OK, build produit (avertissement preexistant sur la taille du chunk principal, sans lien avec ce ticket).
- docker compose config (Docker Desktop disponible dans l'environnement) : OK, environnement resolu du service backend contient NOTIFICATION_CHANNEL=manual, WHATSAPP_PHONE_NUMBER_ID, WHATSAPP_ACCESS_TOKEN, WHATSAPP_TEMPLATE_NAME, WHATSAPP_TEMPLATE_LANGUAGE_CODE, PLAN_MULTI_SHOP=true, PLAN_WHATSAPP_AUTO=true -- gap preexistant bien corrige, critere d'acceptation numero 2 verifiable en Docker reel.

## Conclusion

Code fidele au design et a la spec, ecart wasActive verifie et justifie par l'invariant explicite du design (non-regression retroactive), build et tests backend/frontend au vert, gap docker-compose.yml corrige et verifie en conditions reelles. APPROVE.

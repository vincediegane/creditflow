# Review #3 (finale) — #10 Consolidation multi-boutiques

Branche : `bolt/issue-10-consolidation-multi-boutiques` — base `master`
Perimetre de cette revue : **ciblee** sur le commit `b362687` (fermeture du finding N4 de la
revue #2) et sa non-regression. Le reste du chantier (`master...7150b1d`, 90 fichiers) a ete
audite integralement en revue #1 et #2 et declare conforme ; il n'est pas re-audite ici.

## Verdict

**APPROVE**

Le dernier finding bloquant (fuite inter-boutiques via `GET /api/audit-log`) est **ferme
proprement**. Le correctif est minimal, correctement place, sans chemin de contournement,
sans regression fonctionnelle cote frontend, et couvert par des tests qui echouent
reellement sans le code (verifie par mutation, voir section Build/tests). L'AC1 passe de
« Partiel » a **Couvert**. Les deux remarques mineures restantes sont hors perimetre de ce
ticket et ne bloquent pas.

## Parcours du ticket — synthese

| # | Finding | Revue d'origine | Commit correctif | Statut final |
|---|---|---|---|---|
| N1 | Backend ne demarre plus sur base vierge (`DemoDataSeeder` hors contexte de securite) | #1 | `3e1cdde` | **Corrige** (valide en #2) |
| N2 | `POST /api/auth/login` en 404 systematique (`accessibleShops()` sur `SecurityContext` non peuple) | #1 | `73b40bf` | **Corrige** (valide en #2) |
| N3 | Fuite inter-boutiques via `GET /api/stock-receptions` | #1 | `2479492` | **Corrige** (valide en #2) |
| N4 | Fuite inter-boutiques via `GET /api/audit-log` | #2 | `b362687` | **Corrige** (valide ici) |

Remarques mineures non bloquantes, inchangees et reportees hors ticket : absence totale de
test d'integration contre un vrai moteur SQL (les `*Specifications` ne sont exercees que via
des mocks de `CriteriaBuilder`), et `StockReceptionService.getEntity:57-58` qui appelle
`assertAccessible` une fois par ligne de reception (N resolutions identiques de
`accessibleShopIds()`).

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Un utilisateur rattache a une seule boutique ne voit que les donnees de celle-ci | **Couvert** |
| AC2 | Un gerant multi-boutiques accede a un tableau de bord consolide et peut filtrer par boutique | **Couvert** |
| AC3 | Les rapports et exports acceptent un filtre boutique sans regression mono-boutique | **Couvert** |

**AC1** — desormais couvert sur l'integralite de la surface de lecture. Les modules metier
etaient deja instrumentes (listes et recherches filtrees par `accessibleShopIds()`, gardes
`assertAccessible` sur tous les acces directs par identifiant, recherche globale filtree
transitivement, receptions de stock corrigees en passe 1). Le dernier trou — le journal
d'audit — est ferme par `b362687`.

**AC2** et **AC3** — inchanges depuis la revue #2 (`DashboardService.overview:48` et
`ReportService.build:36-52` resolvent `resolveReadFilter()` une seule fois et le propagent ;
`frontend/src/api/client.ts:30-33` n'emet `X-Shop-Id` qu'en multi-boutiques, donc
non-regression mono-boutique acquise). Le commit `b362687` ne touche aucun de ces chemins.

## Verification du finding N4

### 1. La fuite est fermee, sans chemin de contournement

`backend/src/main/java/com/creditflow/audit/web/AuditLogController.java:28-29` appelle
`auditLogAccessGuard.assertReadable(entityType, entityId)` **avant**
`auditLogService.list(...)`.

Recherche exhaustive des points d'entree du journal :

- `grep -rn "auditLogService\.|AuditLogService" backend/src/main/java` : `list(...)` n'a
  **qu'un seul appelant** en code de production, `AuditLogController:29`. Les autres
  references sont des `record(...)` (ecriture) ou des declarations de champ.
- `grep -rn "AuditLogRepository" backend/src/main/java` : le repository n'est injecte que
  dans `AuditLogService:17`. Aucun controleur, aucun service de rapport ou d'export ne lit
  la table directement.
- Un seul endpoint expose le journal : `@RequestMapping("/api/audit-log")` avec un unique
  `@GetMapping`. Pas de variante par identifiant, pas d'export.

Le contournement par requete est ferme egalement : `assertReadable` recoit les parametres
bruts de la requete, et `AuditLogService.list` interroge ensuite la table sur ces memes
`entityType`/`entityId`. Il n'y a aucun decalage entre l'entite gardee et l'entite lue.

### 2. Inventaire des `entityType` — exhaustif

`grep -rn "auditLogService.record(" backend/src/main/java` renvoie 9 sites d'appel dans
6 fichiers, pour **4 valeurs distinctes** — le rapport du codeur est confirme :

| `entityType` | Sites | Traitement par la garde |
|---|---|---|
| `CUSTOMER` | `customer/service/CustomerService.java:123`, `notification/service/ReminderService.java:112` | `customerService.getEntity(id)` |
| `PRODUCT` | `product/service/ProductService.java:153` | `productService.getEntity(id)` |
| `CREDIT_SALE` | `payment/service/PaymentService.java:177`, `sale/service/CreditSaleService.java:228,242,267,279` | `creditSaleService.getEntity(id)` |
| `PENALTY_SETTINGS` | `penalty/service/PenaltySettingsService.java:54` | refuse (branche `default`) |

Aucun type n'est omis. Les trois types delegues pointent vers des `getEntity(...)` qui
appellent effectivement `currentShopContext.assertAccessible(...)` :
`CustomerService.java:81-86`, `ProductService.java:95-100`, `CreditSaleService.java:116-121`
— la garde n'est donc pas declarative, elle traverse la meme verification que les acces
directs par identifiant.

### 3. Types non rattachables — decision saine, sans regression frontend

`audit/service/AuditLogAccessGuard.java:29-38` : la branche `default` leve
`new ResourceNotFoundException("Ressource introuvable")`, **exactement le meme message** que
`CurrentShopContext.assertAccessible` (`common/security/CurrentShopContext.java:113-117`).
Verifie ligne a ligne : pas d'oracle d'existence introduit, un refus « type global » est
indistinguable d'un refus « entite d'une autre boutique ». La justification est portee par un
commentaire dans le code (`AuditLogAccessGuard.java:33-36`), pas seulement par le message de
commit.

Pas de regression fonctionnelle : `frontend/src/types.ts:473` declare
`AuditEntityType = 'CUSTOMER' | 'CREDIT_SALE' | 'PRODUCT'` — les trois types autorises, et
**aucun** type refuse. L'endpoint est appele en un seul endroit
(`frontend/src/api/endpoints.ts:313-317`), type par `AuditEntityType`, et le seul consommateur
est `components/AuditHistoryCard.tsx`, monte uniquement depuis `pages/CustomerDetailPage.tsx:227`
(`CUSTOMER`) et `pages/SaleDetailPage.tsx:430` (`CREDIT_SALE`). L'historique de
`PENALTY_SETTINGS` n'a jamais ete lisible depuis l'interface.

Ecart assume par rapport a la correction suggeree en revue #2 : celle-ci proposait de reserver
`PENALTY_SETTINGS` a `ADMIN` plutot que de le refuser. Le codeur a choisi le refus total.
C'est defendable et plus sur — un singleton global n'a pas de boutique de reference, donc
aucune garde de cloisonnement n'a de sens sur lui — et sans impact puisque le frontend ne
l'interroge pas. Si le besoin apparait, ce sera un ajout, pas une correction.

### 4. Placement de la garde — le raisonnement tient

Le cycle invoque est reel : `CustomerService:39`, `ProductService:43`, `CreditSaleService:65`
et `PaymentService:53` injectent tous `AuditLogService` pour ecrire. Mettre la garde dans
`AuditLogService` aurait cree `AuditLogService -> CustomerService -> AuditLogService`, cycle
refuse par defaut par Spring Boot. Le composant dedie
(`AuditLogAccessGuard -> {Customer,Product,CreditSale}Service -> AuditLogService`) est un
graphe acyclique. Justification correctement documentee en javadoc de classe
(`AuditLogAccessGuard.java:15-18`).

Ce placement n'affaiblit pas la garde **en l'etat** : le controleur est l'unique porte
d'entree (verifie au point 1). La reserve est prospective — un futur endpoint qui injecterait
`AuditLogService` directement contournerait la garde sans qu'aucun test ne le detecte. Risque
acceptable pour ce ticket (un seul endpoint, un seul appelant, garde immediatement adjacente
a l'appel) ; a reprendre si le module d'audit s'etoffe.

### 5. Qualite des tests — verifiee par mutation, pas sur parole

`AuditLogControllerSecurityTest` (6 tests) porte la valeur : il exerce le cablage reel via
`@Import(AuditLogAccessGuard.class)` sur un `@WebMvcTest`, avec les trois services metier en
`@MockBean`. Les trois nouveaux cas de refus assertent **404** et surtout
`verify(auditLogService, never()).list(anyString(), any())` : cela prouve que la garde
s'execute **avant** toute lecture, et pas seulement que la reponse finale est une erreur — un
filtrage a posteriori du resultat passerait le `status().isNotFound()` mais pas le `never()`.
Les deux tests nominaux preexistants ont ete renforces par
`verify(creditSaleService).getEntity(1L)` et `verify(customerService).getEntity(1L)`, ce qui
interdit de « corriger » un futur echec en retirant simplement l'appel a la garde.

`AuditLogAccessGuardTest` (3 tests, unitaire) couvre la table de routage type par type, la
propagation du refus, et le `verifyNoInteractions` sur la branche `default` (aucun module
n'est sollicite pour un type inconnu).

**Verification par mutation, refaite ici** : dans un `git worktree` jetable positionne sur
`b362687`, j'ai neutralise la seule ligne `auditLogAccessGuard.assertReadable(...)` de
`AuditLogController`, puis relance les tests du module audit. Resultat :

    [ERROR] Tests run: 6, Failures: 5, Errors: 0 -- in AuditLogControllerSecurityTest
      rejectsAuditLogOfCustomerFromAnotherShop:77  Status expected:<404> but was:<200>
      rejectsAuditLogOfProductFromAnotherShop:89   Status expected:<404> but was:<200>
      rejectsEntityTypeWithoutShop:99              Status expected:<404> but was:<200>
      sellerCanListAuditLog:56                     (verify getEntity)
      adminCanListAuditLog:67                      (verify getEntity)
    [ERROR] Tests run: 12, Failures: 5 -- BUILD FAILURE

Conforme au rapport du codeur, y compris les trois `Status expected:<404> but was:<200>`.
Le worktree a ete supprime, le depot est intact (`git status` propre, aucun fichier source
modifie par cette revue). Seul `AuditLogAccessGuardTest` reste vert sous mutation, ce qui est
attendu : c'est un test unitaire de la garde elle-meme, il ne traverse pas le controleur. La
couverture du cablage est bien assuree par `AuditLogControllerSecurityTest`.

### 6. Non-regression — perimetre du commit conforme

`git diff 7150b1d..HEAD --stat` : **4 fichiers, 168 insertions, 1 suppression**, tous dans le
module `audit`.

| Fichier | Nature |
|---|---|
| `audit/service/AuditLogAccessGuard.java` | nouveau, 40 lignes |
| `audit/web/AuditLogController.java` | +5 / -1 (import, champ, appel a la garde, libelle OpenAPI) |
| `audit/service/AuditLogAccessGuardTest.java` | nouveau test, 65 lignes |
| `audit/web/AuditLogControllerSecurityTest.java` | +59 (renforcement + 3 cas) |

L'unique ligne supprimee est le `@Operation(summary = ...)` remplace par un libelle plus
precis. Aucun test existant supprime, aucune assertion relachee, aucun `@Disabled`, aucune
migration Flyway, aucun fichier frontend, aucun autre module touche. La non-regression est
acquise par construction, et confirmee par la suite complete.

Un effet de bord identifie et juge acceptable : apres suppression definitive d'un client
(`CustomerService.delete:120-126`, suppression physique) ou d'un contrat
(`CreditSaleService.delete:233-246`), l'historique d'audit de cette entite devient
irrecuperable via l'API (`getEntity` leve 404 alors que les lignes d'audit subsistent en
base). C'est la contrepartie assumee du fait de deriver le droit de lecture de l'entite
ciblee, et il n'existe aucun chemin d'interface pour l'atteindre : `AuditHistoryCard` n'est
monte que dans la page de detail d'une entite existante. Les donnees restent consultables en
base pour un audit de conformite. Non bloquant.

## Build et tests

| Commande | Ou | Resultat |
|---|---|---|
| `mvn -B test` | `backend/` | **BUILD SUCCESS** — `Tests run: 270, Failures: 0, Errors: 0, Skipped: 0` (264 avant `b362687`, soit +6) |
| `mvn -B -o test -Dtest=AuditLog*Test` sur code mute (worktree jetable) | `backend/` | **BUILD FAILURE** attendue — 5 echecs / 6, dont 3 `expected:<404> but was:<200>` : les tests sont bien couples au correctif |
| `npm run build` | `frontend/` | non relance — aucun fichier frontend dans `7150b1d..HEAD` ; dernier resultat connu (revue #2) : succes |

La suite backend a ete relancee integralement de mon cote, pas reprise du rapport du codeur.

## Conclusion

Les 4 findings bloquants du parcours sont fermes. La correction du dernier est ciblee,
justifiee, testee de maniere robuste et strictement contenue dans son perimetre. Le chantier
est sain. **APPROVE**.

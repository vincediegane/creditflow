# Review — #25 Architecture multi-tenant SaaS

APPROVE

## Contexte de cette passe

Deuxieme passe de review sur ce bolt. La premiere passe (commit `7a12a97`) avait rendu
`CHANGES_REQUESTED` sur deux findings portant uniquement sur le decoupage des tickets de suivi
proposes par `design.md`, la partie technique de la note (comparaison des strategies d'isolation,
decision row-level `tenant_id` + Postgres RLS, section « zero ligne de code ») ayant deja ete
verifiee fait par fait et jugee exacte. Le correctif du bolt-architect (commit `0ce0934`, "fix -
corrige le decoupage des tickets de suivi") est evalue ci-dessous point par point.

## Verification du correctif (commit `0ce0934`)

**Finding 1 (bloquant, premiere passe) — le ticket de suivi n°1 ne fournissait pas
`User -> Organization` dont dependait le ticket n°2.**

Resolu. `design.md:47-60` scope desormais explicitement le ticket n°1 sur `organizations`,
`shops.organization_id` **et** `users.organization_id` (entite JPA `Organization`, relations
`Shop.organization` et `User.organization`), avec une justification explicite du choix (porter
l'organisation directement sur `User` plutot qu'une regle de derivation implicite du type
« organisation unique en mode mono-tenant »). La justification cite precisement le scenario
casse identifie en premiere passe (`design.md:51-53`, citant `CurrentShopContext.
accessibleShopsOf`, ligne 59-60) — verifie de nouveau contre le code reel
(`backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java:53-63`) : la
citation de ligne est exacte, un `ADMIN` sans boutique assignee (`user.getShops().isEmpty()`)
tombe bien sur `shopRepository.findAllByActiveTrueOrderByNameAsc()` sans filtre.

Verification concrete que le ticket n°2 peut desormais reellement filtrer pour ce cas : avec
`users.organization_id` porte directement par `User` (et non derive de `Shop`), le ticket n°2
(`design.md:61-65`, "`ShopRepository.findAllByActiveTrueOrderByNameAsc()` remplace par une
variante filtree par `organization_id`") dispose de la donnee necessaire independamment de la
presence ou non de boutiques assignees a l'ADMIN — le probleme originel (aucune boutique dont
deriver une organisation) ne se pose plus puisque l'organisation ne depend plus de `Shop` pour
cet utilisateur. Le scenario qui cassait en premiere passe est bien couvert.

**Finding 2 (non bloquant, premiere passe) — le ticket de suivi n°3 s'auto-declarait hors
gabarit d'un seul cycle bolt sans etre scinde.**

Resolu. L'ancien ticket n°3 unique est scinde en 4 tickets par module
(`design.md:66-82`) : n°3 Customer/Product, n°4 CreditSale/Installment, n°5 Payment,
n°6 StockReception/AuditLog. Couverture verifiee sans trou par rapport a la liste de
l'ancien ticket n°3 unique (`git show 7a12a97:docs/bolts/25-architecture-multi-tenant-saas/design.md`,
lignes 61-68, qui listait `Customer`, `Product`, `CreditSale`, `Payment`, `Installment`,
`StockReception`, `AuditLog`) :
- n°3 : `CustomerSpecifications`, `CustomerRepository`, `ProductRepository`,
  `ProductSpecifications`, `CustomerService`, `ProductService`.
- n°4 : `CreditSaleRepository`, `SaleSpecifications`, `InstallmentRepository`,
  `InstallmentSpecifications`, `CreditSaleService`, `InstallmentService`.
- n°5 : `PaymentRepository`, `PaymentSpecifications`, `PaymentService`.
- n°6 : `StockReceptionSpecifications`, `StockReceptionService`,
  `AuditLogAccessGuard.assertReadable`, `ReminderService`.

Les 7 domaines metier de l'ancien ticket unique (Customer, Product, CreditSale, Payment,
Installment, StockReception, AuditLog) et les services associes listes ailleurs dans le document
(section « Fichiers/modules impactes », inchangee par le fix) sont tous repris dans l'un des 4
nouveaux tickets — aucun repository ni service oublie. Chaque ticket reste borne a 3-6 fichiers
sources, un perimetre raisonnable pour un seul cycle bolt (coherent avec la taille des bolts deja
observes sur ce depot).

## Coherence de bout en bout du document apres renumerotation

Lecture integrale de `design.md` (296 lignes), pas seulement le diff du commit `0ce0934`.
Toutes les occurrences de "ticket de suivi n°X" ont ete recensees et confrontees a la liste
numerotee 1-10 (lignes 46-100) :

| Reference dans le texte | Ligne | Numero attendu apres renumerotation | Coherent |
|---|---|---|---|
| n°2 (derivation organisation ADMIN) | 51 | Scoping ADMIN | oui |
| n°3 a 6 (audit exhaustif des points d'entree) | 145 | Propagation par module | oui |
| n°7 (cout RLS) | 150 | RLS | oui |
| n°2 (JWT/claims, doc a faire) | 163 | Scoping ADMIN | oui |
| n°1 (coexistence, une ligne organizations) | 169 | Fondation de donnees | oui |
| n°9 (table plan par tenant) | 179, 186 | Modele de plan par tenant | oui |
| n°8 (isolation stockage fichiers) | 263 | Isolation stockage fichiers | oui |
| n°10 (bascule/backup-loop.sh) | 268 | Bascule/coexistence | oui |
| n°7 (variable de session RLS, pool de connexions) | 273 | RLS | oui |
| n°1 (revalidation pattern shop_id) | 276 | Fondation de donnees | oui |
| n°1 (backfill NOT NULL vs nullable, users+shops) | 289 | Fondation de donnees | oui |

Aucun renvoi orphelin trouve vers un ancien numero (ancien n°3 unique, ancien n°4 RLS, ancien
n°5 stockage, ancien n°6 plan, ancien n°7 bascule) : la recherche exhaustive de toutes les
occurrences `n°\d` et `ticket de suivi n` dans le fichier ne remonte que des numeros conformes
au nouveau decoupage 1-10. Le renumero est complet et coherent.

`git diff 7a12a97 HEAD -- docs/bolts/25-architecture-multi-tenant-saas/design.md` confirme que
les seules modifications portent sur : (a) le scope et la justification du ticket n°1
(ajout `users.organization_id`), (b) la scission de l'ancien ticket n°3 en 4 tickets n°3-6,
(c) le renumero mecanique de tous les renvois internes affectes (n°4->7, n°5->8, n°6->9,
n°7->10), et (d) une precision correspondante dans « Hors perimetre » (backfill
`users.organization_id` en plus de `shops.organization_id`). Aucune alteration de la section
comparant les trois strategies d'isolation (DB-per-tenant / schema-per-tenant / row-level), de
la decision retenue (row-level `tenant_id` + RLS en defense en profondeur), ni de la section
« Recommandation de perimetre » (zero ligne de code) — toutes deja validees et jugees exactes en
premiere passe, non retouchees ici, donc toujours valides.

## Verification factuelle (reprise de la premiere passe, section technique inchangee)

Toutes les affirmations factuelles verifiees en premiere passe restent exactes (section non
modifiee par le fix, re-confirmee sur un point cle ci-dessus : citation de ligne
`CurrentShopContext.accessibleShopsOf` 59-60) :
- `CurrentShopContext.accessibleShopsOf` (lignes 53-63) : un ADMIN sans boutique assignee
  (`user.getShops().isEmpty()`) tombe bien sur `shopRepository.findAllByActiveTrueOrderByNameAsc()`,
  non scope — confirme.
- `PaymentRepository.findByCustomer/findBySale`, `CreditSaleRepository.findByCustomer/
  sumTotalPriceByCustomer/sumRemainingByCustomer` : confirme, ces requetes filtrent uniquement
  par `customerId`/`saleId`, sans jointure sur `shop.id`, en s'appuyant sur un `assertAccessible`
  en amont dans le service appelant.
- `SecurityConfig.PUBLIC_ENDPOINTS` contient bien `/uploads/**`, et `WebConfig.
  addResourceHandlers` sert le dossier d'upload de facon statique — confirme, aucun controle
  d'acces applicatif.
- `JwtService.generateToken(username, role)` ne porte bien que `subject` (username) et le claim
  `role` — confirme, pas de claim de tenant actuellement.
- `AuditLogAccessGuard.assertReadable` delegue bien a `getEntity()` des services metier
  (`CustomerService`, `ProductService`, `CreditSaleService`) — confirme.
- Le compte de 25 fichiers backend referencant `shopId` est exact.
- `scripts/backup-loop.sh` fait bien un `pg_dump` complet, sans filtrage par tenant — confirme.
- `app.notification.channel` est bien lu via `@ConditionalOnProperty`, une seule valeur par
  processus JVM — confirme, la limite decrite sur `whatsappAuto` est reelle.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Une note d'architecture documente et tranche la strategie d'isolation retenue | Couvert -- la decision (row-level tenant_id + RLS en defense en profondeur) est tranchee, justifiee, fondee sur du code reel verifie, et le decoupage des tickets de suivi qui l'operationnalise est desormais coherent de bout en bout (les deux findings bloquant/non bloquant de la premiere passe sont resolus). |
| 2 | Aucun utilisateur d'une organisation ne peut acceder aux donnees d'une autre organisation | N/A pour ce bolt (aucun code livre, delegue aux tickets de suivi n°2-6) -- correctement delegue, et le decoupage delegue desormais une donnee complete (users + shops) permettant reellement l'implementation. |
| 3 | Le role ADMIN est scope a son organisation | N/A pour ce bolt -- correctement delegue au ticket de suivi n°2, qui dispose desormais (via le ticket n°1 corrige) de la donnee `User.organization` necessaire pour couvrir le cas ADMIN sans boutique assignee. |
| 4 | Les instances single-tenant existantes ne sont pas cassees | N/A pour ce bolt -- strategie de coexistence documentee (une organisation par base, filtrage `organization_id` non restrictif sur une base mono-org) et testee par le ticket de suivi n°2. |

## Build/tests

Non executes -- aucun code livre dans ce bolt (recommandation "zero ligne de code" du bolt,
non contestee en premiere passe et non remise en cause par ce fix). Revue documentaire complete :
lecture integrale de `design.md`, `git diff 7a12a97 HEAD` sur le fichier, `git show
7a12a97:...design.md` pour comparaison de l'ancien decoupage, et re-verification ponctuelle
contre `CurrentShopContext.java` (citation de ligne).

## Verdict

APPROVE. Les deux findings de la premiere passe sont resolus concretement, pas seulement en
apparence : le ticket n°1 fournit desormais la donnee `users.organization_id` avec une
justification explicite (porter l'organisation sur `User` plutot qu'une derivation implicite),
ce qui permet reellement au ticket n°2 de scoper un ADMIN sans boutique assignee -- le scenario
qui cassait en premiere passe est verifie couvert. Le decoupage module par module des tickets
n°3-6 couvre integralement la liste de repositories/services de l'ancien ticket n°3 unique, sans
trou, avec un perimetre raisonnable par ticket. La renumerotation complete des dix tickets de
suivi est coherente de bout en bout du document : aucun renvoi orphelin vers un ancien numero
retrouve apres recensement exhaustif. Le reste du document (comparaison des strategies, decision
row-level + RLS, section "zero ligne de code") est inchange et reste valide au regard de la
verification factuelle deja effectuee. Aucun code livre dans ce bolt, conformement a sa
recommandation de perimetre -- rien a construire ni tester.

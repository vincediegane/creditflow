# Design — #66 Empecher la survente : bloquer la vente a credit quand le stock produit est a 0

## Approche

`CreditSaleService.create()` (lignes 175-246) charge le produit via `productService.getEntity(id)`
(simple `findById`, sans verrou) puis, en fin de methode, decremente le stock seulement si
`stock > 0` sans jamais empecher la creation du contrat en amont : c'est exactement le trou decrit
par le ticket. La correction ajoute une garde explicite au tout debut de `create()`, sur le meme
modele que les deux verifications de boutique deja presentes juste apres (lignes 181-188) :
`if (product.getStock() == null || product.getStock() <= 0) throw new BusinessRuleException(...)`.
Comportement retenu : blocage strict, sans option de confirmation/override pour le vendeur. Le
ticket laissait ce point ouvert ("a trancher selon l'usage reel") ; je tranche pour le blocage
simple parce que (a) c'est la seule option qui satisfait sans ambiguite le critere d'acceptation
"jamais silencieusement acceptee", (b) elle reutilise le pattern d'exception deja en place dans
cette meme methode (pas de nouveau flux UI de confirmation, pas de nouveau champ "override" a
tracer/auditer), (c) le prix a payer est que si le stock papier n'est pas a jour (rupture
fictive), le vendeur est bloque jusqu'a correction du stock via le module `product/` existant
(`increaseStock`/edition manuelle) — un cas deja gere aujourd'hui pour toute autre rupture de
stock, pas une regression nouvelle.

Second volet, requis par le ticket lui-meme : la garde Java seule (`if stock <= 0`) ne ferme pas
la fenetre de concurrence explicitement citee (deux vendeurs sur le dernier exemplaire). Verifie
dans le code : aucun verrou pessimiste n'existe nulle part dans le depot (`grep PESSIMISTIC|Lock`
sans resultat), `ProductRepository` n'expose que des lectures non verrouillees, et le backend
tourne sur PostgreSQL (`pom.xml`, drivers `postgresql`/`flyway-database-postgresql`), qui supporte
nativement `SELECT ... FOR UPDATE`. Vu que `create()` est deja `@Transactional` (ligne 175) et que
Postgres est confirme, un verrou pessimiste scope au produit est faisable a cout limite : on charge
le produit avec un verrou en ecriture au debut de `create()`, ce qui force toute transaction
concurrente sur le meme produit a attendre la fin de la premiere avant de lire son stock a jour.
Prix : le verrou est tenu pendant toute la duree de la transaction de creation de vente (generation
d'echeancier + deux ecritures sur `sale`), donc un peu plus de contention si plusieurs ventes
concurrentes portent sur le meme produit tres demande — acceptable a l'echelle boutique visee par
l'app (pas de volume documente qui contre-indique ce choix).

## Fichiers/modules impactes

Backend :
- `backend/src/main/java/com/creditflow/product/repository/ProductRepository.java` : nouvelle
  methode `findByIdForUpdate(Long id)` avec `@Lock(LockModeType.PESSIMISTIC_WRITE)` sur une
  `@Query("select p from Product p where p.id = :id")` (Spring Data ne supporte pas `@Lock` sur les
  methodes derivees par nom sans requete explicite).
- `backend/src/main/java/com/creditflow/product/service/ProductService.java` : nouvelle methode
  `getEntityForUpdate(Long id)` qui reprend exactement le corps de `getEntity(id)` (lignes 97-103)
  mais appelle `findByIdForUpdate` au lieu de `findById` — `getEntity(id)` existant n'est pas
  touche, pour ne pas verrouiller par effet de bord les lectures simples (fiche produit,
  recherche, etc.) qui l'utilisent ailleurs.
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` : dans `create()`,
  remplacer `productService.getEntity(request.productId())` (ligne 179) par
  `productService.getEntityForUpdate(request.productId())`, puis ajouter la garde de stock juste
  apres (avant ou juste apres les controles de boutique existants, lignes 181-188). Le bloc existant
  lignes 238-240 (`decreaseStock` conditionnel) est simplifie : le produit est garanti en stock
  positif a ce stade, donc l'appel a `productService.decreaseStock(product, 1)` devient
  inconditionnel (suppression du `if`).
- `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` : le helper
  partage `stubCreationDependencies()` (lignes 278-293) construit aujourd'hui un `Product` avec
  `stock(0)`, utilise par 3 tests dont `createSucceedsWhenCustomerAndProductMatchTargetShop` (ligne
  266) — ce test va desormais echouer avec la nouvelle garde puisque le produit qu'il utilise a un
  stock nul. Le fixture doit passer a un stock strictement positif (ex. `stock(5)`), et de nouveaux
  cas de test dedies doivent couvrir : stock 0 -> `BusinessRuleException`, stock `null` ->
  `BusinessRuleException`, stock positif -> creation OK + `decreaseStock` appele avec quantite 1
  (non-regression du critere d'acceptation 3). Le mock `productService.getEntityForUpdate(...)`
  doit etre stubbe a la place de `getEntity(...)` dans ces tests.

Frontend :
- `frontend/src/pages/NewSalePage.tsx` : le formulaire affiche deja l'erreur backend via
  `errorMessage(err, ...)` dans une `Alert severity="error"` (lignes 113, 201) — le message clair
  de `BusinessRuleException` remonte donc automatiquement sans changement de plomberie. Ameliorer
  neanmoins la selection produit (lignes 232-249) pour eviter a l'utilisateur d'atteindre l'erreur
  serveur inutilement : le type `Product` du frontend expose deja `sellable: boolean`
  (`frontend/src/types.ts:166`, calcule par `Product.isSellable()` cote backend) mais n'est
  actuellement pas utilise dans `NewSalePage.tsx`. Ajouter un indicateur visuel (option grisee ou
  suffixe "— rupture de stock") pour les produits `!sellable` dans l'`Autocomplete`, sans les
  retirer de la liste (un vendeur peut vouloir consulter le produit avant de choisir un
  remplacant).

Aucune migration Flyway necessaire pour ce ticket : `products.stock` reste `integer not null` sans
contrainte CHECK ajoutee (voir Decisions cles).

## Decisions cles

- **Blocage strict, sans confirmation/override** : voir justification dans Approche. Alternative
  ecartee (autoriser avec confirmation explicite) parce qu'elle demande un nouveau flux UI
  (modale/checkbox), un champ ou un log d'audit pour tracer le "j'autorise quand meme", et le
  ticket ne fournit aucune preuve d'un besoin operationnel reel (stock papier desynchronise) qui
  justifierait cette complexite pour un correctif P1.
- **Verrou pessimiste (`SELECT ... FOR UPDATE`) scope a `ProductService.getEntityForUpdate`,
  utilise uniquement par `CreditSaleService.create()`**, plutot que de modifier `getEntity(id)`
  global. Alternative ecartee : rendre `decreaseStock` atomique via une requete SQL
  `UPDATE products SET stock = stock - 1 WHERE id = :id AND stock > 0` (retour du nombre de lignes
  affectees) sans verrou explicite. Cette option est correcte aussi mais son perimetre est plus
  large : `decreaseStock`/`increaseStock` sont des methodes generiques reutilisees par le module
  `product/` (receptions fournisseur, ajustements manuels potentiels), et les faire reposer sur un
  retour "0 ligne affectee -> echec" changerait leur contrat pour tous leurs appelants, pas
  seulement celui de ce ticket. Le verrou pessimiste isole au chemin de creation de vente ferme la
  meme fenetre de concurrence sans toucher au comportement des autres appelants de
  `ProductService`.
- **Pas de contrainte CHECK SQL sur `products.stock >= 0`** ajoutee dans ce ticket : `stock` est
  deja protege cote applicatif (`decreaseStock` utilise `Math.max(0, ...)`, ligne 172 de
  `ProductService.java`, donc ne peut pas descendre sous zero par ce chemin) ; une contrainte
  CHECK est une securite supplementaire raisonnable mais independante du bug rapporte (elle ne
  bloquerait pas la creation du contrat, seulement un decrement invalide qui n'existe pas dans le
  code actuel) — laissee hors perimetre pour ne pas elargir un correctif P1 cible.
- **`decreaseStock(product, 1)` devient un appel inconditionnel** dans `create()` (suppression du
  `if (product.getStock() != null && product.getStock() > 0)`) puisque la garde en tete de methode
  garantit deja un stock strictement positif au moment de l'appel — evite de dupliquer la meme
  condition deux fois dans la meme methode.

## Risques / points d'attention

- **Regression de test immediate et attendue** : `stubCreationDependencies()` dans
  `CreditSaleServiceTest.java` cree un produit a `stock(0)`, utilise par
  `createSucceedsWhenCustomerAndProductMatchTargetShop` et par le test de mismatch de boutique
  (autour des lignes 257-263) — sans mise a jour de ce fixture (stock positif) ces tests
  echoueront avec la nouvelle garde. A traiter explicitement dans l'implementation, pas comme un
  effet de bord surprenant.
- **Verrou tenu sur toute la duree de `create()`** (generation d'echeancier, deux ecritures sur
  `CreditSale`) : pas de mesure de volumetrie reelle disponible dans le depot pour confirmer
  l'absence totale de contention perceptible sur un produit tres vendu en meme temps par plusieurs
  vendeurs ; risque juge acceptable a l'echelle boutique mais a garder en tete si le produit
  evolue vers un usage a tres haut debit de ventes simultanees.
- **`getEntity(id)` (sans verrou) reste utilise par tous les autres appelants de
  `ProductService`** (recherche, fiche produit, mise a jour, suppression) : ce ticket ne les
  verrouille pas, ce qui est voulu (voir Decisions cles) mais signifie que la race condition n'est
  fermee que sur le chemin de creation de vente a credit, pas sur d'autres ecritures concurrentes
  potentielles sur `Product` (ex. deux mises a jour de fiche produit en parallele) — hors
  perimetre de ce ticket.
- **Message d'erreur affiche au vendeur** : verifier lors de l'implementation que le texte de
  `BusinessRuleException` est sans ambiguite sur la cause ("stock epuise", pas un message
  generique) puisque c'est un critere d'acceptation explicite ("message clair expliquant pourquoi
  la vente ne peut pas etre creee").
- **Aucune etape de reservation intermediaire** n'existe dans ce flux (pas de panier/etape entre
  selection produit et soumission du formulaire) : le verrou pessimiste protege uniquement la
  fenetre de la transaction `create()` elle-meme, pas le temps que le vendeur passe a remplir le
  formulaire avant de soumettre — suffisant pour fermer la race condition decrite par le ticket
  (deux soumissions concurrentes), mais ne garantit pas qu'un produit affiche comme disponible au
  chargement du formulaire le soit encore au moment du clic (limite normale de ce type de flux,
  non demandee autrement par le ticket).

## Hors perimetre

- Flux de confirmation/override permettant de forcer une vente malgre un stock a 0 (tranche pour
  un blocage strict, voir Decisions cles) — a revisiter seulement si un besoin operationnel reel
  de stock papier desynchronise est remonte.
- Contrainte CHECK SQL sur `products.stock >= 0` et toute migration Flyway associee.
- Refonte de `decreaseStock`/`increaseStock` en operations SQL atomiques (`UPDATE ... WHERE stock
  > 0`) pour tous leurs appelants (receptions fournisseur, module `product/` en general) : seul le
  chemin de creation de vente a credit est concerne par ce ticket.
- Verrouillage pessimiste sur d'autres chemins d'ecriture concurrents sur `Product` (mise a jour de
  fiche produit, suppression, receptions de stock) : uniquement `CreditSaleService.create()` est
  couvert.
- Reservation temporaire de stock pendant la saisie du formulaire avant soumission (pas de
  mecanisme de panier/verrou cote UI) : hors demande du ticket.
- Ajout d'un indicateur de stock disponible en temps reel (websocket/polling) sur la page de
  creation de vente : la garde serveur au moment de la soumission suffit au critere d'acceptation ;
  seule une amelioration statique de l'affichage (`sellable`) est prevue cote frontend.

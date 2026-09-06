# Design — #41 Multi-tenant 8/10 — Isolation du stockage fichiers

## Approche

Lecture du code reel (pas de la description du ticket) : FileStorageService n'existe plus, il a
ete scinde par #45 en DocumentStorage (interface) / LocalDiskStorage / S3DocumentStorage
(backend/src/main/java/com/creditflow/common/storage/), et #45 a deja retire l'exposition statique
/uploads/** (WebConfig.java n'existe plus, SecurityConfig.PUBLIC_ENDPOINTS ne contient plus
/uploads/**) au profit d'endpoints authentifies (GET /api/customers/{id}/photo,
GET /api/sales/{id}/attachments/{attachmentId}/file) qui reutilisent le scoping boutique deja en
place (getEntity(id) + CurrentShopContext.assertAccessible). Le design de #45 documentait
explicitement l'isolation multi-tenant comme hors perimetre, faute d'Organization a l'epoque
(ticket de suivi #41). Depuis, #34-#40 ont livre l'Organization, le scoping boutique par
organisation (CurrentShopContext.accessibleShopIds()/currentOrganizationId()) et surtout le
Postgres Row-Level Security (#40) : customers, sale_attachments (via jointure credit_sales vers
shops) sont deja filtres par app.current_org_id au niveau connexion. Concretement, getEntity(id)
dans CustomerService/CreditSaleService ne peut deja plus charger l'entite d'une autre
organisation (RLS filtre la ligne avant meme assertAccessible), donc AC1 (fichier non accessible
sans authentification) et AC2 (pas d'acces inter-organisation via l'API) sont deja couverts par
#45+#40, pas a refaire ici. Le travail reellement neuf de #41 est le perimetre explicite du
ticket -- dossiers scopes par organisation -- c'est-a-dire faire porter la meme isolation sur la
disposition physique des fichiers (chemin disque / cle S3), en defense en profondeur : acces
serveur/operationnel au disque ou au bucket, preparation a un futur decoupage par prefixe IAM S3
par organisation, et confinement du rayon d'impact d'un bug de traversee de chemin futur. Prix
assume : ce n'est pas un gain de securite applicative mesurable aujourd'hui (l'API bloque deja
l'acces inter-organisation), c'est une hygiene de stockage demandee explicitement par le ticket,
qui ne doit pas etre presentee comme comblant une faille qui n'existe plus dans le code actuel.

## Fichiers/modules impactes

- backend/src/main/java/com/creditflow/customer/service/CustomerService.java -- uploadPhoto()
  (ligne 136) : documentStorage.store(file, "customers") devient
  documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/customers")
  (dependance deja injectee, deja utilisee ligne 49/60/70 pour currentOrganizationId()).
- backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java -- uploadAttachment()
  (ligne 285) : documentStorage.store(file, "sales/" + saleId) devient
  documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/sales/" + saleId)
  (currentShopContext deja injecte ligne 71).
- Aucun changement dans DocumentStorage, LocalDiskStorage, S3DocumentStorage, DocumentAccess,
  DocumentValidation, DocumentAccessResponses : le parametre folder est deja une chaine libre
  acceptee telle quelle par store(), aucune signature ne change.
- Aucun changement dans CustomerController, SaleController (endpoints deja authentifies et deja
  scopes par #45), ni dans SecurityConfig/WebConfig (deja a jour depuis #45).
- Aucun changement frontend : frontend/src/hooks/useAuthenticatedFile.ts,
  frontend/src/components/CustomerAvatar.tsx, frontend/src/components/AttachmentThumbnail.tsx,
  frontend/src/utils/apiFileUrl.ts consomment deja les URL d'API authentifiees (photoUrl/fileUrl
  renvoyes par les DTO sont deja des URL /api/..., pas des cles de stockage brutes) -- le
  changement de disposition physique des dossiers est invisible pour eux.
- docs/bolts/41-multitenant-isolation-stockage-fichiers/ (ce document) et sa suite (spec.md) : a
  documenter, mais aucune migration Flyway n'est necessaire (voir Decisions cles).

## Decisions cles

- Aucune migration des fichiers deja stockes, ni des cles deja en base (customers.photo_url,
  sale_attachments.file_url). resolve(key)/delete(key) utilisent toujours litteralement la cle
  stockee en base, quelle que soit la convention de dossier utilisee au moment de l'upload
  d'origine ; seule la convention appliquee aux nouveaux appels de store() change. Un fichier
  existant reste donc accessible avec son ancien chemin (customers/<uuid>.jpg) sans aucun script de
  migration -- ce qui satisfait directement AC3 sur une instance mono-tenant deja en production.
  C'est un choix delibere plutot qu'un renommage retroactif des objets existants (option ecartee :
  couteuse, risquee sur un volume de prod, et sans benefice puisque l'acces a ces fichiers est deja
  scope par organisation au niveau applicatif/RLS, pas par leur chemin physique).
- Convention de dossier org-{organizationId}/... (prefixe simple), pas un sous-repertoire par
  boutique (shop-{shopId}) : l'isolement a garantir ici est inter-organisation (le risque decrit
  par le ticket), pas inter-boutique au sein d'une meme organisation -- coherent avec le choix deja
  fait par RLS (#40) de scoper par organisation, jamais par boutique, au niveau schema/policy.
- Resolution de l'organisation via CurrentShopContext.currentOrganizationId(), deja present et
  deja utilise par les deux services concernes pour d'autres besoins (specifications de recherche)
  -- pas de nouvelle dependance, pas de nouveau mecanisme de resolution de tenant a inventer (a
  distinguer de TenantContext/TenantContextFilter de #40, qui pilote la connexion Postgres, pas la
  construction de chemins applicatifs).
- Pas de changement de S3DocumentStorage : le prefixe org-{id}/... fait simplement partie de la
  cle d'objet S3 (key = folder + / + uuid + . + extension, ligne 85), strictement compatible avec
  un futur decoupage de politique IAM par prefixe org-*/ si le produit en a besoin un jour -- non
  demande, non implemente ici (voir Hors perimetre).
- Pas de verification applicative supplementaire ajoutee dans resolve() : la garde d'appartenance
  a l'organisation reste entierement portee par getEntity(id) (RLS + shop scoping), appele avant
  tout documentStorage.resolve(...) dans les deux services concernes -- le chemin de stockage n'est
  jamais utilise comme mecanisme de controle d'acces, seulement comme organisation physique des
  donnees, pour eviter de dupliquer une logique d'autorisation a deux endroits (base de donnees et
  systeme de fichiers) qui pourrait diverger.

## Risques / points d'attention

- AC1/AC2 sont deja satisfaits par du code merge sur master avant ce ticket (#45 pour
  l'authentification, #40 pour l'isolement RLS inter-organisation, #35 pour assertAccessible
  inter-boutique) : le spec-writer doit rediger des criteres de test qui verifient ce comportement
  existant (non-regression) plutot que de faire perdre du temps a concevoir un mecanisme
  d'autorisation qui existe deja. Le risque principal serait de dupliquer inutilement une garde
  deja presente ou, pire, d'introduire une seconde source de verite qui diverge de RLS/assertAccessible.
- documentStorage.delete(key) sur LocalDiskStorage/S3DocumentStorage continue de fonctionner sans
  changement car il opere uniquement sur la cle fournie, jamais sur une convention de nommage
  supposee -- mais toute evolution future qui tenterait de deriver un chemin a partir de
  l'organisation courante plutot que de la cle stockee casserait la suppression des fichiers deja
  existants (cles sans prefixe org-). A ne pas faire.
- Pas de test d'integration disque/S3 existant pour LocalDiskStorage/S3DocumentStorage au-dela des
  tests unitaires actuels (a verifier par le spec-writer) : le changement de valeur du parametre
  folder est mineur mais doit etre couvert par un test qui verifie que le chemin/la cle generee
  contient bien le segment org-{organizationId}, pas seulement que l'upload reussit.
- Cohabitation d'anciennes cles (customers/<uuid>.jpg) et de nouvelles cles
  (org-{id}/customers/<uuid>.jpg) sur le meme volume/bucket, indefiniment : c'est un choix assume
  (voir Decisions cles), pas un defaut transitoire a corriger -- aucune tache de purge/renommage
  n'est prevue ni necessaire.
- Instance mono-tenant avec une seule organisation : le prefixe org-{id}/ sera identique pour tous
  les nouveaux uploads (id constant), donc aucun changement de comportement observable pour
  l'utilisateur, uniquement une reorganisation interne du dossier ./data/uploads (local) ou du
  prefixe de cle S3 -- a verifier explicitement en test de non-regression avant tout scenario
  multi-organisation, meme raisonnement que #35-#40.
- Aucune autre entite ne stocke de fichier via DocumentStorage aujourd'hui (verifie par recherche
  exhaustive : seuls CustomerService et CreditSaleService appellent store()) -- pas de troisieme
  point d'appel a traiter.

## Hors perimetre

- Migration/renommage des fichiers deja stockes vers la nouvelle convention de dossier par
  organisation (voir Decisions cles : la cle en base reste la source de verite, aucun renommage
  n'est necessaire ni prevu).
- Toute modification de SecurityConfig, CustomerController, SaleController ou du mecanisme
  d'authentification des endpoints de fichiers : deja livre par #45, non retouche ici.
- Bascule vers un bucket S3 dedie par organisation ou politique IAM par prefixe org-*/ : la
  convention de cle le permet sans changement d'appelant futur, mais ce n'est pas demande par ce
  ticket et n'est pas implemente.
- Toute correction des lacunes multi-tenant deja identifiees et non liees au stockage de fichiers
  (suppliers partage entre organisations, penalty_settings global -- documentees comme hors
  perimetre par #40) : sans rapport avec le stockage de fichiers, non traitees ici.
- Changement frontend : aucun composant n'affiche plus d'URL brute (#45/#51 l'ont deja corrige),
  donc rien a adapter cote React pour ce ticket.

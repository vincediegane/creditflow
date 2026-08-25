# Review — #25 Architecture multi-tenant SaaS

CHANGES_REQUESTED

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Une note d'architecture documente et tranche la strategie d'isolation retenue | Partiel -- la decision (row-level tenant_id + RLS en defense en profondeur) est reellement tranchee, correctement justifiee face aux deux alternatives, et fondee sur une lecture veridique du code reel (tous les faits cites ont ete verifies, voir ci-dessous). Mais le decoupage des tickets de suivi censes operationnaliser la decision contient une incoherence factuelle qui empeche de le creer tel quel (voir Finding 1). Le critere n'est donc pas entierement couvert au sens ou le pipeline pourrait s'arreter ici et transmettre les tickets sans repasser dessus. |
| 2 | Aucun utilisateur d'une organisation ne peut acceder aux donnees d'une autre organisation | N/A pour ce bolt (aucun code livre, delegue aux tickets de suivi 2-5) -- correctement delegue. |
| 3 | Le role ADMIN est scope a son organisation | N/A pour ce bolt -- correctement delegue au ticket de suivi 2. |
| 4 | Les instances single-tenant existantes ne sont pas cassees | N/A pour ce bolt -- strategie de coexistence documentee (une organisation par base, filtrage organization_id non restrictif sur une base mono-org) et testee par le ticket de suivi 2. |

Le choix de "zero ligne de code dans ce bolt" est raisonnable et bien justifie (pas un moyen d'eviter le travail : la note explique pourquoi un chantier touchant une dizaine de modules et le modele de donnees de production ne doit pas etre tente en un seul cycle codeur->reviewer automatise, et pourquoi une implementation partielle serait pire qu'une absence -- une entite Organization qui existe mais qu'aucune requete ne filtre est pire que son absence). Ce n'est pas conteste.

## Verification factuelle (code reel vs affirmations de la note)

Toutes les affirmations factuelles verifiees se sont revelees exactes :
- CurrentShopContext.accessibleShopsOf (lignes 53-63) : un ADMIN sans boutique assignee (user.getShops().isEmpty()) tombe bien sur shopRepository.findAllByActiveTrueOrderByNameAsc(), non scope -- confirme.
- PaymentRepository.findByCustomer/findBySale, CreditSaleRepository.findByCustomer/sumTotalPriceByCustomer/sumRemainingByCustomer : confirme, ces requetes filtrent uniquement par customerId/saleId, sans jointure sur shop.id, en s'appuyant sur un assertAccessible en amont dans le service appelant.
- SecurityConfig.PUBLIC_ENDPOINTS contient bien /uploads/** (ligne 40), et WebConfig.addResourceHandlers sert le dossier d'upload de facon statique -- confirme, aucun controle d'acces applicatif.
- JwtService.generateToken(username, role) ne porte bien que subject (username) et le claim role -- confirme, pas de claim de tenant actuellement.
- AuditLogAccessGuard.assertReadable delegue bien a getEntity() des services metier (CustomerService, ProductService, CreditSaleService) -- confirme.
- Le compte de 25 fichiers backend referencant shopId est exact (verifie par recherche).
- scripts/backup-loop.sh fait bien un pg_dump complet, sans filtrage par tenant -- confirme.
- app.notification.channel est bien lu via @ConditionalOnProperty sur ManualCopyChannel/WhatsAppCloudApiChannel, une seule valeur par processus JVM -- confirme, la limite decrite sur whatsappAuto est reelle.

Aucune affirmation trouvee fausse ou approximative. La comparaison des trois strategies (DB-per-tenant/schema-per-tenant/row-level) est correcte et le tableau de compromis couts/complexite/securite est coherent avec le contexte de deploiement actuel reellement observe (docker-compose.yml, migrations V1-V12).

## Findings

### 1. (Bloquant) Le ticket de suivi n1 tel que redige ne fournit pas la donnee dont depend le ticket n2 -- incoherence interne a la note

- design.md:46-51 : le ticket de suivi n1 (Fondation de donnees) scope explicitement la migration a l'ajout de la table organizations et d'une colonne organization_id sur shops uniquement. Aucune mention d'un ajout sur users.
- Mais design.md:135-140 (section strategie d'isolation, JWT/claims) affirme explicitement que le scoping doit charger User.organization a chaque requete -- ce qui presuppose une relation User -> Organization en base.
- Et design.md:176-178 (inventaire des fichiers impactes) liste auth/domain/User.java comme devant recevoir une nouvelle relation vers Organization, en plus de Shop.java.
- Scenario concret qui casse : un ADMIN sans boutique assignee (cas reel et verifie dans CurrentShopContext.accessibleShopsOf, ligne 59-60) n'a, par definition, aucune boutique dont deriver une organisation. Le ticket de suivi n2 (Scoping ADMIN par organisation) ne peut filtrer ShopRepository.findAllByActiveTrueOrderByNameAsc() par organization_id pour un tel ADMIN que si l'utilisateur porte lui-meme un organization_id -- donnee que le ticket n1, tel qu'il est redige (ligne 46-51), ne cree pas.
- Consequence : si ces tickets sont crees tels quels sur GitHub, le ticket n1 sera livre incomplet au regard de ce qu'exige le ticket n2, et cette lacune ne sera detectee qu'au moment de specifier/coder le ticket n2 -- soit apres coup, ce que la note pretend justement eviter en recadrant en spec technique une fois le choix tranche.
- Correction attendue : le scope du ticket n1 doit explicitement inclure soit une colonne/relation organization_id sur users, soit une regle de derivation documentee (ex. l'organisation d'un ADMIN sans boutique assignee est celle de l'unique organisation existante en mode mono-tenant, non definie en mode multi-org tant que le ticket n6/7 n'introduit pas d'affectation explicite) -- actuellement aucune des deux n'est presente dans la description du ticket n1.

### 2. (Non bloquant, a corriger) Le ticket de suivi n3 s'auto-declare potentiellement hors gabarit d'un seul cycle bolt

- design.md:43-44 pose comme regle que les tickets de suivi sont chacun bornes pour un seul cycle bolt, condition explicitement demandee par le brief de revue.
- design.md:61-63 (ticket n3) : Probablement a re-decouper par module tant le perimetre (une dizaine de repositories) depasse un seul bolt.
- C'est une auto-contradiction transparente (la note le signale elle-meme, ce qui est honnete), mais cela signifie que le ticket n3 ne peut pas etre cree tel quel sur GitHub comme un ticket unique conforme a la regle que la note s'est elle-meme fixee. Il aurait du etre scinde des cette note (par exemple par module : Customer/Product, CreditSale/Installment, Payment, StockReception/AuditLog) plutot que de laisser cette decision a un futur cycle qui, par construction, n'aura pas plus d'information que celle deja disponible ici (la liste des repositories concernes est deja connue et citee).

## Decoupage des tickets -- appreciation generale

En dehors des deux points ci-dessus, l'ordre logique est correct (fondation -> scoping applicatif -> propagation -> defense en profondeur DB -> stockage fichiers -> plan par tenant -> bascule/outillage), sans chevauchement evident entre les tickets 1, 2, 4, 5, 6, 7. Le risque assertAccessible non scope (classe de bug principale identifiee) est correctement rattache au ticket n3 avec des exemples concrets et verifies. Le point de vigilance RLS (variable de session a repositionner a chaque emprunt de connexion pool) est un ajout de valeur reel pour le futur ticket n4.

## Build/tests

Non executes -- aucun code livre dans ce bolt, conformement a la mission de revue (points 1-3 uniquement, verification documentaire/factuelle contre le code existant).

## Verdict

CHANGES_REQUESTED. La note d'architecture est rigoureuse, ses affirmations factuelles sur le code sont exactes sans exception trouvee, et la decision d'isolation (row-level + RLS) est reellement tranchee avec une justification solide -- le refus de coder dans ce bolt est un appel raisonnable, pas une esquive. Mais le decoupage des tickets de suivi, qui est la seule sortie actionnable de ce bolt, contient une incoherence interne bloquante (Finding 1 : le ticket n1 ne fournit pas la donnee User -> Organization que le ticket n2 et la note elle-meme presupposent) et une auto-contradiction non resolue sur le gabarit du ticket n3 (Finding 2). A corriger avant de transformer ces sept points en tickets GitHub.

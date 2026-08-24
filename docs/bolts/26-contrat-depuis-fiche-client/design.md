# Design — #26 Créer un contrat directement depuis la fiche client

## Approche

Passage du client présélectionné via un query param `?customerId=` sur la route
`ventes/nouvelle`, exactement comme suggéré par le ticket — et cohérent avec le seul précédent
du code : `SearchPage.tsx` lit déjà `?q=` via `useSearchParams`. Alternative écartée : le `state`
de navigation react-router, qui ne survit pas à un rafraîchissement de page ni à un lien
partagé/ouvert dans un nouvel onglet, et qui aurait forcé un cast non typé côté `NewSalePage`
(`useLocation().state as any`) — un query param est lisible, ré-entrant et sans risque de perte
silencieuse.

`CustomerDetailPage.tsx` gagne un bouton « Nouveau contrat » qui construit cette URL.
`NewSalePage.tsx` lit `customerId` au montage, attend le chargement de `customersApi.select()`
(déjà utilisé pour peupler l'Autocomplete client), retrouve le client correspondant dans la
liste, et pré-remplit à la fois la valeur du formulaire et l'affichage visuel de l'Autocomplete.
Ce dernier point est un vrai changement de comportement de composant existant, pas un simple
ajout : aujourd'hui l'Autocomplete (client comme garant) n'a ni `value` ni `defaultValue`, il
n'est donc pas contrôlé — impossible de pré-remplir visuellement le champ sans lui passer un
`value` piloté par un état React. Prix assumé : `NewSalePage` doit être retouché au-delà du
strict ajout de pré-remplissage (introduction d'un état `selectedCustomer` contrôlant
l'Autocomplete client), avec le risque de régresser le parcours actuel si mal fait.

## Fichiers/modules impactés

- `frontend/src/pages/CustomerDetailPage.tsx` — ajout d'un bouton « Nouveau contrat » dans le
  `Stack` d'actions du `PageHeader` (à côté de « Retour » et « Générer la relance »), qui navigue
  vers `/ventes/nouvelle?customerId=` suivi de l'id du client.
- `frontend/src/pages/NewSalePage.tsx` :
  - lecture de `customerId` via `useSearchParams` (`react-router-dom`, déjà utilisé dans
    `SearchPage.tsx`) ;
  - ajout d'un état contrôlant l'affichage de l'Autocomplete client (`value` calculé depuis
    `customersQuery.data` et `customerId`), aujourd'hui absent du composant ;
  - `useEffect` de pré-sélection déclenché quand `customersQuery.data` est disponible et que
    `customerId` est présent dans l'URL : mise à jour de la valeur `customerId` du formulaire et
    de l'état de contrôle de l'Autocomplete ;
  - `createMutation.onSuccess` : navigation conditionnelle (voir Décisions clés) et invalidation
    du cache client (voir Risques).
- `frontend/src/App.tsx` — aucun changement de route nécessaire : `ventes/nouvelle` accepte déjà
  n'importe quelle query string, seul `NewSalePage` doit la lire. Vérifié : la route est déclarée
  simplement à la ligne 40 (`Route path="ventes/nouvelle" element={NewSalePage}`), sans paramètre
  dynamique à ajouter.
- Aucun fichier backend : `POST /api/sales` accepte déjà `customerId` (confirmé par
  `createMutation.mutationFn = salesApi.create` existant), aucune migration, aucun contrôleur à
  toucher.

## Décisions clés

- Bouton placé dans le `PageHeader` de `CustomerDetailPage`, pas dans la carte
  « Historique des achats » : c'est une action de premier niveau sur la fiche client (au même
  rang que « Générer la relance »), pas une action liée à une ligne du tableau des ventes.
- L'Autocomplete client de `NewSalePage` devient contrôlé (`value` dérivé de
  `customersQuery.data.find` par id), avec `isOptionEqualToValue` comparé par id plutôt que par
  égalité de référence, pour rester robuste même si l'objet vient d'un état local distinct du
  tableau `customersQuery.data`. L'Autocomplete garant, lui, reste non contrôlé : il n'a aucune
  raison d'être pré-rempli par ce ticket.
- Client introuvable dans `customersQuery.data` (id invalide, client inexistant, ou — vérifié
  côté backend, `CustomerService.findAllForSelect()` filtre les clients actifs uniquement —
  client désactivé) : pas de blocage de page ni d'erreur bloquante. Le formulaire s'affiche
  normalement, l'Autocomplete reste vide, et une alerte discrète de type avertissement informe
  (client demandé introuvable, à sélectionner manuellement) uniquement quand `customerId` est
  présent dans l'URL mais qu'aucune correspondance n'est trouvée après chargement de la liste.
- Pas de garde sur les clients inactifs côté `CustomerDetailPage` : le ticket ne demande pas de
  masquer le bouton pour un client désactivé ; ce cas se traduira simplement par l'avertissement
  ci-dessus dans `NewSalePage`, sans nouvelle règle métier à inventer.

- Retour après création : si la vente a été initiée avec un `customerId` déjà présent dans
  l'URL (donc depuis la fiche client), `createMutation.onSuccess` navigue vers la fiche client
  plutôt que vers le détail de la vente créée, conformément à la demande du ticket (retour vers
  la fiche client). Sans `customerId` dans l'URL (parcours existant depuis le menu Ventes), le
  comportement actuel est conservé à l'identique : navigation vers le détail de la vente créée.
  Décision motivée par la préservation stricte du parcours existant (critère d'acceptation n°3)
  plutôt que par une préférence de design.
- Invalidation explicite du cache client : `createMutation.onSuccess` invalide la query
  `customer-profile` pour l'id concerné dans tous les cas (retour fiche client ou retour détail
  vente), pas seulement quand on redirige vers la fiche. Nécessaire car le `staleTime` global est
  fixé à 15 secondes (`frontend/src/main.tsx`) : sans invalidation, un retour rapide sur la fiche
  client peut afficher la liste de contrats en cache, encore sans la vente qui vient d'être créée,
  ce qui violerait le critère d'acceptation n°2 (apparition immédiate).

## Risques / points d'attention

- Régression du parcours existant sans client présélectionné : `DashboardPage.tsx` et
  `SalesPage.tsx` naviguent tous deux vers `/ventes/nouvelle` sans aucun paramètre. Le nouvel
  effet de pré-sélection doit être un no-op strict quand `customerId` est absent de l'URL (pas de
  mise à jour de valeur déclenchée, pas d'alerte affichée), et la bascule de l'Autocomplete client
  vers un composant contrôlé ne doit rien changer à son comportement de saisie/recherche libre
  existant.
- Fenêtre de chargement de `customersQuery` : au premier rendu, `customersQuery.data` est
  indéfini (la liste n'est pas encore chargée) ; l'effet de pré-sélection ne doit s'exécuter
  qu'une fois `customersQuery.data` défini, sinon la recherche du client échoue systématiquement
  et déclenche à tort l'avertissement client introuvable. Prévoir aussi l'état intermédiaire où
  l'Autocomplete affiche une liste vide ou un état de chargement pendant ce court délai — ne pas
  afficher l'avertissement tant que `customersQuery.isLoading` est vrai.
- Effet déclenché une seule fois : l'effet de pré-sélection doit se garder de re-déclencher à
  chaque nouveau rendu (par exemple après un rechargement de `customersQuery` provoqué par une
  invalidation ailleurs), pour ne pas écraser une sélection manuelle ultérieure de l'utilisateur
  qui aurait changé de client dans l'Autocomplete après le pré-remplissage automatique.
- `customerId` non numérique ou négatif dans l'URL (saisie manuelle, lien cassé) : la conversion
  numérique d'une chaîne invalide donne NaN ; aucune entrée de `customersQuery.data` n'a l'id NaN,
  donc le cas se comporte comme client introuvable, sans crash — comportement déjà couvert par la
  décision ci-dessus, mais à vérifier explicitement en test.
- Dépendance implicite au filtre actif du backend
  (`backend/src/main/java/com/creditflow/customer/service/CustomerService.java`,
  méthode `findAllForSelect`) : un client désactivé consulté sur sa fiche (qui reste accessible)
  verra son id absent de la liste de sélection des clients, donc jamais présélectionnable.
  Comportement acceptable au vu du périmètre du ticket, mais à ne pas confondre avec un bug de
  pré-remplissage lors des tests.
- Le reste du formulaire (produit, taux d'intérêt, frais de dossier, garant) n'est pas concerné
  par ce ticket : seule la présélection du champ client est dans le périmètre.

## Hors périmètre

- Pré-remplissage du garant à partir de la fiche client : non demandé par le ticket, l'Autocomplete
  garant reste inchangé.
- Pré-sélection ou filtrage du produit : hors périmètre, le champ produit reste à saisir
  manuellement dans tous les cas.
- Création d'un raccourci symétrique (par exemple un bouton nouveau paiement depuis la fiche
  client) : seul le contrat est demandé par ce ticket.
- Tout changement d'API backend (ventes, sélection des clients) : l'API accepte déjà `customerId`
  à la création, aucune évolution n'est nécessaire.
- Masquage ou désactivation du bouton Nouveau contrat pour un client inactif : non demandé
  explicitement par le ticket ; le cas est simplement absorbé par l'avertissement client
  introuvable décrit plus haut, sans nouvelle règle métier de statut à implémenter.

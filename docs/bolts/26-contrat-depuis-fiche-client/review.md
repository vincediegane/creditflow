# Review — #26 Créer un contrat directement depuis la fiche client

CHANGES_REQUESTED

## Critères d'acceptation

| # | Critère | Statut | Constat |
|---|---|---|---|
| 1 | Depuis /clients/:id, un bouton ouvre le formulaire de nouvelle vente avec le client déjà sélectionné. | Couvert | CustomerDetailPage.tsx:75-77 ajoute le bouton, NewSalePage.tsx:80-95 fait la présélection via useSearchParams + useEffect déclenché une seule fois à la transition undefined -> data chargée, Autocomplete client rendu contrôlé (value={selectedCustomer}). Comportement vérifié par lecture de code et cohérent avec le contrat technique de la spec. |
| 2 | Le contrat créé apparaît immédiatement dans la liste des contrats de la fiche client. | Partiel | Couvert pour le parcours nominal (client présélectionné non modifié par l'utilisateur) : NewSalePage.tsx:106 invalide ['customer-profile', sale.customerId], clé identique à celle utilisée par CustomerDetailPage.tsx:45, donc la table Historique des achats se rafraîchit sans reload. Non couvert si l'utilisateur change manuellement de client dans l'Autocomplete après la présélection (cas explicitement prévu et testé par la spec) : la navigation post-succès utilise customerIdParam (id de l'URL) et non l'id réellement soumis — voir Finding 1. |
| 3 | Le parcours existant (créer une vente depuis Ventes sans client présélectionné) continue de fonctionner sans régression. | Couvert | DashboardPage.tsx:78 et SalesPage.tsx:71 appellent navigate('/ventes/nouvelle') sans query string -> customerIdParam est null -> l'effet de présélection sort en no-op strict (NewSalePage.tsx:81-83, premier return), pas d'alerte affichée, pas de setValue/setSelectedCustomer appelés, navigation post-création inchangée. Autocomplete garant non touché (diff ne modifie pas les lignes 325-342). Confirmé par npm run build / lint / test en local. |

## Findings

### 1. (Bloquant) Navigation post-création vers le mauvais client si l'utilisateur change la présélection — frontend/src/pages/NewSalePage.tsx:103-114

Code actuel :

    const createMutation = useMutation({
      mutationFn: salesApi.create,
      onSuccess: (sale) => {
        queryClient.invalidateQueries({ queryKey: ['customer-profile', sale.customerId] });
        if (customerIdParam != null) {
          navigate(`/clients/${customerIdParam}`);
        } else {
          navigate(`/ventes/${sale.id}`);
        }
      },
      ...

L'invalidation du cache utilise correctement l'id du client réellement soumis (sale.customerId), conformément à la résolution actée dans spec.md (section Écarts identifiés, dernier point). Mais la navigation, elle, utilise customerIdParam — l'id lu dans l'URL au montage — et non sale.customerId. Ces deux valeurs divergent dès que l'utilisateur modifie la sélection après la présélection automatique, un scénario explicitement voulu et testé par le plan de tests de la spec (choisir manuellement un autre client -> vérifier que ce changement n'est jamais écrasé).

Scénario concret : depuis la fiche du client A (/clients/12), clic sur "Nouveau contrat" -> /ventes/nouvelle?customerId=12, le client A est présélectionné. L'utilisateur change d'avis et sélectionne le client B (id=45) dans l'Autocomplete, remplit le formulaire, soumet. Le contrat est bien créé pour le client B et le cache ['customer-profile', 45] est bien invalidé — mais navigate renvoie l'utilisateur vers /clients/12 (fiche du client A), qui n'a pas été invalidée et ne contient pas le nouveau contrat. Le critère d'acceptation n°2 (apparition immédiate dans la liste des contrats de la fiche client) est donc violé dans ce cas : l'utilisateur se retrouve sur la fiche du mauvais client, sans le contrat qu'il vient de créer, sans erreur ni message explicatif.

Cas aggravant : si customerIdParam est un id invalide ou introuvable (?customerId=999999 ou ?customerId=abc), l'utilisateur doit forcément sélectionner un client manuellement (Autocomplete vide + avertissement). Après soumission, navigate('/clients/999999') ou navigate('/clients/abc') mène à l'écran "Client introuvable." (CustomerDetailPage.tsx:59-61) — pas de crash JS, mais l'utilisateur atterrit sur une page d'erreur alors que le contrat a bel et bien été créé avec succès pour le client qu'il a choisi.

Correctif suggéré : utiliser sale.customerId (déjà disponible et déjà utilisé pour l'invalidation) comme cible de navigation, en gardant customerIdParam != null uniquement comme condition de déclenchement du retour-fiche-client :

    if (customerIdParam != null) {
      navigate(`/clients/${sale.customerId}`);
    } else {
      navigate(`/ventes/${sale.id}`);
    }

Cela rend la navigation cohérente avec l'invalidation de cache et couvre correctement le critère d'acceptation n°2 y compris en cas de changement manuel du client.

### 2. (Mineur, non bloquant) Message d'avertissement ambigu en cas d'échec réseau de customersQuery — frontend/src/pages/NewSalePage.tsx:203-207

La condition d'affichage de l'alerte (customerIdParam != null && !customersQuery.isLoading && selectedCustomer === null) est vraie aussi bien quand customersQuery a fini de charger avec succès sans trouver le client, que quand customersQuery est en erreur (isError, isLoading devient false sans que data ne soit jamais défini). Dans ce second cas le message "Client demandé introuvable" est trompeur (le problème est un échec réseau/API, pas une absence de correspondance). Cas préexistant à la marge du composant, non couvert par la spec, à signaler mais ne bloque pas ce ticket.

## Cohérence avec spec.md

Les tâches de spec.md sont suivies fidèlement point par point : bouton, lecture useSearchParams, état selectedCustomer + Number.isNaN traité comme absence, useEffect à déclenchement unique via ref, Autocomplete client contrôlé avec isOptionEqualToValue par id, Autocomplete garant non touché, Alert d'avertissement avec le message suggéré, useQueryClient + invalidation ['customer-profile', sale.customerId], App.tsx non modifié (route déjà tolérante, confirmée en lecture de App.tsx:40). Le seul écart réel par rapport à l'intention du design est le Finding 1 ci-dessus : la spec elle-même dicte navigate(`/clients/${customerIdParam}`) (section Contrat technique, Navigation post-création) sans jamais réconcilier cette valeur avec celle utilisée pour l'invalidation (sale.customerId) — le codeur a suivi la lettre de la spec, mais la spec contient elle-même cette incohérence entre les deux mécanismes, non relevée dans les Écarts identifiés ni testée dans le plan de tests (qui teste que la sélection manuelle n'est pas écrasée, mais pas où la navigation post-submit atterrit ensuite).

## Build/tests

Exécutés localement sur bolt/issue-26-contrat-depuis-fiche-client (frontend uniquement, aucun changement backend dans ce ticket) :

- npm run lint (tsc --noEmit) — OK, aucune erreur.
- npm run build (tsc --noEmit && vite build) — OK, build réussi (seul avertissement : chunk index-*.js > 500 kB, préexistant, sans lien avec ce ticket).
- npm run test (vitest run) — OK, 16/16 tests passés (src/offline/__tests__/queue.test.ts, src/offline/__tests__/sync.test.ts), suite inchangée par ce ticket, confirmée non affectée.

Rapport du codeur cohérent avec ces résultats. Aucun test automatisé de composant React n'existe pour ce ticket (infra Vitest limitée à src/offline/** en environnement node, sans @testing-library/react) — confirmé hors périmètre par la spec, non contesté par cette revue.

## Verdict

CHANGES_REQUESTED — le Finding 1 est un bug fonctionnel réel et facilement déclenchable (tout changement manuel de client après présélection, ou tout accès avec un customerId d'URL invalide/introuvable suivi d'une sélection manuelle) qui viole le critère d'acceptation n°2 et contredit la propre logique d'invalidation de cache du code. Correctif d'une ligne (navigate(`/clients/${sale.customerId}`) au lieu de customerIdParam), à appliquer avant merge.

# Review — #26 Créer un contrat directement depuis la fiche client

APPROVE

## Contexte de cette passe

Deuxième passe de review. La première passe (commit `49c6f35`) avait rendu `CHANGES_REQUESTED` sur un unique finding bloquant : la navigation post-création dans `NewSalePage.tsx` utilisait `customerIdParam` (id lu dans l'URL au montage) au lieu de `sale.customerId` (id réellement soumis), ce qui redirigeait vers la mauvaise fiche client si l'utilisateur changeait de client dans l'Autocomplete après la présélection. Le commit `99bf87d` corrige ce point. Cette review vérifie que le correctif est complet, exact, et n'introduit aucune régression.

## Vérification du correctif (commit 99bf87d)

Diff réel (`git show 99bf87d`), un seul fichier touché, une seule ligne modifiée :

    onSuccess: (sale) => {
      queryClient.invalidateQueries({ queryKey: ['customer-profile', sale.customerId] });
      if (customerIdParam != null) {
-       navigate(`/clients/${customerIdParam}`);
+       navigate(`/clients/${sale.customerId}`);
      } else {
        navigate(`/ventes/${sale.id}`);
      }

Confirmé par lecture directe du fichier final (`NewSalePage.tsx:103-114`) — le correctif appliqué est identique, au caractère près, à celui suggéré dans le Finding 1 de la première passe :
- La condition de déclenchement reste `customerIdParam != null` (on ne revient vers une fiche client que si le parcours a démarré depuis une fiche client) — comportement du critère n°3 inchangé.
- La cible de navigation devient `sale.customerId`, désormais cohérente avec la clé d'invalidation de cache (`['customer-profile', sale.customerId]`, ligne juste au-dessus) — les deux mécanismes utilisent maintenant la même source de vérité, l'id réellement soumis au serveur.

Rejeu du scénario du Finding 1 : depuis `/clients/12`, clic sur "Nouveau contrat" → présélection du client A (id 12) → l'utilisateur change pour le client B (id 45) dans l'Autocomplete → soumission. Le contrat est créé pour le client 45, le cache `['customer-profile', 45]` est invalidé, et `navigate('/clients/45')` renvoie désormais vers la fiche du client B, qui contient bien le contrat fraîchement créé. Le cas aggravant (customerId d'URL invalide/introuvable suivi d'une sélection manuelle) est également couvert : la navigation cible toujours le client effectivement soumis, plus jamais l'id brut de l'URL.

Aucune autre ligne de `NewSalePage.tsx`, ni d'aucun autre fichier du diff, n'a été touchée par ce commit (`git diff 49c6f35..99bf87d` confirme un seul fichier, une seule ligne changée). Le bouton sur `CustomerDetailPage.tsx`, la logique de présélection (`useEffect` à déclenchement unique via `preselectionResolved` ref), l'Autocomplete client contrôlé (`value={selectedCustomer}`, `isOptionEqualToValue`), l'Autocomplete garant, et l'alerte d'avertissement restent identiques à la première passe et ne sont pas remis en cause.

Le Finding 2 (mineur, message d'alerte ambigu en cas d'échec réseau) n'a pas été traité — conforme aux instructions, explicitement hors périmètre de cette passe, ne compte pas contre le verdict.

## Critères d'acceptation

| # | Critère | Statut | Constat |
|---|---|---|---|
| 1 | Depuis /clients/:id, un bouton ouvre le formulaire de nouvelle vente avec le client déjà sélectionné. | Couvert | Inchangé depuis la première passe : `CustomerDetailPage.tsx:75-77` (bouton), `NewSalePage.tsx:80-95` (présélection via `useSearchParams` + `useEffect` à déclenchement unique), Autocomplete contrôlé. |
| 2 | Le contrat créé apparaît immédiatement dans la liste des contrats de la fiche client. | Couvert | Le Finding 1 est résolu : invalidation (`['customer-profile', sale.customerId]`, ligne 106) et navigation (`/clients/${sale.customerId}`, ligne 108) utilisent désormais la même valeur — l'id réellement soumis, y compris quand l'utilisateur change de client après la présélection ou quand le customerId d'URL était invalide. |
| 3 | Le parcours existant (créer une vente depuis Ventes sans client présélectionné) continue de fonctionner sans régression. | Couvert | `customerIdParam == null` → branche `else` inchangée (`navigate('/ventes/${sale.id}')`), effet de présélection sort en no-op strict. Confirmé par lecture de code et par build/lint/test. |

## Cohérence avec spec.md

Aucun changement de périmètre par rapport à la première passe : le correctif ne fait que réconcilier la navigation avec la logique d'invalidation de cache déjà conforme à la spec, sans introduire de nouvelle divergence ni de tâche non faite.

## Build/tests

Exécutés localement sur `bolt/issue-26-contrat-depuis-fiche-client`, après le commit `99bf87d` (frontend uniquement, aucun changement backend dans ce ticket) :

- `npm run lint` (tsc --noEmit) — OK, aucune erreur.
- `npm run build` (tsc --noEmit && vite build) — OK, build réussi (seul avertissement : chunk `index-*.js` > 500 kB, préexistant, sans lien avec ce ticket).
- `npm run test` (vitest run) — OK, 16/16 tests passés (`src/offline/__tests__/queue.test.ts`, `src/offline/__tests__/sync.test.ts`), suite non affectée par ce ticket. Aucun test automatisé de composant React n'existe pour `NewSalePage.tsx` (infra Vitest limitée à `src/offline/**` en environnement node, sans `@testing-library/react`) — confirmé hors périmètre par la spec, non contesté par cette revue ; la vérification du correctif repose donc sur la lecture de code (cohérence stricte entre invalidation et navigation) plutôt que sur un test automatisé.

## Verdict

APPROVE — le Finding 1 bloquant de la première passe est corrigé de façon exacte et complète (le diff est strictement le correctif suggéré, sans effet de bord), les trois critères d'acceptation sont couverts, aucune régression n'est introduite sur le reste du diff déjà validé, et build/lint/test passent tous.

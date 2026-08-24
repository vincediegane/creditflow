# Spec — #26 Créer un contrat directement depuis la fiche client

## Résumé

Un bouton « Nouveau contrat » sur la fiche client ouvre le formulaire de nouvelle vente avec le client déjà présélectionné (via `?customerId=`), et le retour après création se fait vers la fiche client au lieu du détail de la vente.

## Tâches

- [ ] **`frontend/src/pages/CustomerDetailPage.tsx`** — Ajouter un bouton « Nouveau contrat » dans le `Stack` d'actions du `PageHeader` (ligne ~71-78), entre ou après « Retour » et « Générer la relance ». `onClick={() => navigate(\`/ventes/nouvelle?customerId=${customerId}\`)}`. Pas de garde sur `customer.active` (hors périmètre, confirmé par le design).

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Importer `useSearchParams` depuis `react-router-dom` (déjà utilisé dans `SearchPage.tsx`) en plus de `useNavigate`. Lire `customerId` au montage : `const [searchParams] = useSearchParams();` puis `const customerIdParam = searchParams.get('customerId');`.

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Ajouter un état `selectedCustomer: Customer | null` (état React, pas seulement la valeur du form) qui pilote la prop `value` de l'Autocomplete client. Convertir `customerIdParam` en nombre via `Number(customerIdParam)` ; traiter toute valeur telle que `Number.isNaN(...)` comme absence de présélection (identique à "id introuvable").

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Ajouter un `useEffect` avec dépendances `[customersQuery.data]` (pas `customerIdParam` seul, pour ne se déclencher qu'une fois les données chargées) :
  - no-op strict si `customerIdParam` est `null`/absent (parcours existant depuis Ventes/Dashboard) ;
  - no-op tant que `customersQuery.data` est `undefined` (chargement en cours) ;
  - une fois `customersQuery.data` disponible : chercher `customersQuery.data.find(c => c.id === Number(customerIdParam))` ;
    - trouvé → `setValue('customerId', match.id)` + `setSelectedCustomer(match)` ;
    - non trouvé (id NaN, inexistant, ou client inactif filtré côté backend) → laisser `selectedCustomer` à `null`, ne pas toucher `customerId` du form, déclencher l'affichage de l'avertissement (voir tâche suivante).
  - Utiliser un flag/ref (ou vérifier que l'effet ne s'exécute qu'une seule fois utile) pour ne jamais ré-écraser une sélection manuelle faite ensuite par l'utilisateur dans l'Autocomplete — l'effet ne doit se déclencher qu'à la transition `undefined → data chargé`, pas à chaque re-render une fois `customersQuery.data` stable.

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Rendre l'Autocomplete client (lignes ~174-187) contrôlé : ajouter `value={selectedCustomer}` et `isOptionEqualToValue={(option, value) => option.id === value.id}` sur ce seul Autocomplete. Dans `onChange`, en plus de `setValue('customerId', value?.id ?? '')`, ajouter `setSelectedCustomer(value ?? null)` pour que toute sélection manuelle ultérieure (y compris effacement du champ) reste cohérente avec l'état contrôlé.

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Ne **pas** rendre contrôlé l'Autocomplete garant (lignes ~285-302) : aucune modification de sa prop `value`/`onChange` au-delà de l'existant. Non-régression explicite à vérifier (voir Plan de tests).

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Ajouter une `Alert severity="warning"` conditionnelle, affichée uniquement quand : `customerIdParam` est présent (non-null) ET `customersQuery.data` est chargé (`!customersQuery.isLoading`) ET aucune correspondance trouvée (`selectedCustomer === null` après résolution de l'effet). Placer cette alerte dans la `Stack` du formulaire, avant ou juste après l'`Alert` d'erreur existante (ligne ~172). Message suggéré : « Client demandé introuvable — sélectionnez-le manuellement ci-dessous. » Ne pas bloquer le rendu du reste du formulaire.

- [ ] **`frontend/src/pages/NewSalePage.tsx`** — Adapter `createMutation` : importer `useQueryClient` depuis `@tanstack/react-query` (`const queryClient = useQueryClient();`). Dans `onSuccess: (sale) => { ... }` :
  - invalider systématiquement `queryClient.invalidateQueries({ queryKey: ['customer-profile', Number(form.customerId ou sale.customerId)] })` (utiliser l'id du client réellement soumis, pas `customerIdParam`, pour couvrir aussi le cas où l'utilisateur a changé de client manuellement) — dans tous les cas, présélection ou non ;
  - navigation conditionnelle : si `customerIdParam` était présent dans l'URL au chargement de la page, `navigate(\`/clients/${customerIdParam}\`)` ; sinon conserver `navigate(\`/ventes/${sale.id}\`)` (comportement actuel inchangé).
  - Vérifier que `sale` (type `Sale`, retourné par `salesApi.create`) porte bien un `customerId` exploitable, sinon utiliser la valeur du formulaire soumis au moment du submit.

- [ ] **`frontend/src/App.tsx`** — Aucune modification requise (confirmé par le design : la route `ventes/nouvelle` accepte déjà toute query string). Vérifier simplement, en revue, qu'aucun `Route` intermédiaire ne strip les query params (peu probable avec `react-router-dom` v6, mais à confirmer visuellement en test manuel).

- [ ] **Vérification manuelle croisée** — Confirmer qu'aucun autre point d'entrée vers `/ventes/nouvelle` (`DashboardPage.tsx` ligne 78, `SalesPage.tsx` ligne 71) n'est impacté : les deux appellent `navigate('/ventes/nouvelle')` sans query string, donc `customerIdParam` sera `null` et le nouveau code doit rester un no-op strict pour ces deux parcours.

## Contrat technique

- **Route** : `ventes/nouvelle` (inchangée dans `App.tsx`) — accepte désormais un query param optionnel `customerId` (string numérique). Exemple : `/ventes/nouvelle?customerId=42`.
- **Navigation depuis `CustomerDetailPage`** : `navigate(\`/ventes/nouvelle?customerId=${customerId}\`)` où `customerId = Number(useParams().id)`.
- **Lecture dans `NewSalePage`** : `useSearchParams().get('customerId')` → `string | null`. Aucune validation stricte requise côté route (délai de résolution/aucune correspondance géré par l'UI, pas par un 404/erreur bloquante).
- **Nouvel état local `NewSalePage`** : `selectedCustomer: Customer | null` (type `Customer` importé de `../types`, déjà utilisé implicitement via `customersQuery.data: Customer[] | undefined`).
- **Autocomplete client (contrôlé)** :
  - `value={selectedCustomer}`
  - `isOptionEqualToValue={(option, value) => option.id === value.id}`
  - `onChange={(_, value) => { setValue('customerId', value?.id ?? ''); setSelectedCustomer(value ?? null); }}`
- **Invalidation cache** : `queryClient.invalidateQueries({ queryKey: ['customer-profile', <customerId numérique du form soumis>] })` — clé identique à celle utilisée dans `CustomerDetailPage.tsx` (`['customer-profile', customerId]`, ligne 45).
- **Navigation post-création** :
  - avec présélection (`customerIdParam` non-null au chargement) → `navigate(\`/clients/${customerIdParam}\`)`
  - sans présélection (comportement actuel) → `navigate(\`/ventes/${sale.id}\`)`
- **Aucun changement backend** : `POST /api/sales` (`salesApi.create`) et `GET /customers/select` (`customersApi.select`, filtré sur `Customer.active` côté `CustomerService.findAllForSelect`, confirmé dans `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` lignes 64-73) restent inchangés.

## Plan de tests

**Contrainte d'infrastructure à noter avant exécution** : la configuration Vitest actuelle (`frontend/vite.config.ts`, bloc `test`) utilise `environment: 'node'` et restreint `include` à `src/offline/**/*.test.ts`. Il n'existe aujourd'hui aucun test de composant React dans `frontend/src` (seul `src/offline` est testé), et `@testing-library/react` n'est pas une dépendance du projet. Sans extension de cette configuration (changement d'environnement vers `jsdom`/`happy-dom`, ajout de `@testing-library/react`, élargissement du glob `include`) — ce qui est un changement d'infrastructure hors périmètre de ce ticket — aucun test automatisé de composant n'est exécutable en l'état. Le plan ci-dessous distingue donc explicitement ce qui est vérifiable manuellement de ce qui nécessiterait une extension d'infra (à signaler au reviewer, ne pas improviser silencieusement).

| Critère d'acceptation du ticket | Test | Type |
|---|---|---|
| Depuis `/clients/:id`, un bouton ouvre le formulaire de nouvelle vente avec le client déjà sélectionné. | Ouvrir une fiche client active ayant au moins un contrat existant → cliquer « Nouveau contrat » → vérifier l'URL (`/ventes/nouvelle?customerId=<id>`) et que le champ Autocomplete « Client » affiche déjà `Nom — téléphone` du client sans action supplémentaire. | Manuel |
| Le contrat créé apparaît immédiatement dans la liste des contrats de la fiche client. | Depuis le parcours ci-dessus, compléter et soumettre le formulaire (prix, mensualités, date) → vérifier la redirection vers `/clients/:id` (pas vers `/ventes/:id`) → vérifier que le nouveau contrat apparaît dans le tableau « Historique des achats » sans rechargement manuel de la page (validation de l'invalidation de `['customer-profile', customerId]`). | Manuel (couvre aussi l'invalidation du cache décrite dans le design) |
| Le parcours existant (créer une vente depuis le menu « Ventes » sans client présélectionné) continue de fonctionner sans régression. | Depuis `SalesPage.tsx` (« Nouvelle vente ») et depuis `DashboardPage.tsx`, ouvrir `/ventes/nouvelle` sans query param → vérifier que l'Autocomplete client est vide (pas de présélection, pas d'avertissement affiché), que la saisie manuelle du client fonctionne comme avant, et qu'après soumission la redirection va bien vers `/ventes/:id` (comportement actuel inchangé). | Manuel — non-régression explicite |
| (Risque architecte) Client introuvable (id inexistant, ou client inactif filtré par `findAllForSelect`). | Naviguer directement vers `/ventes/nouvelle?customerId=999999` (id inexistant) → vérifier : pas de crash, pas de blocage de page, Autocomplete vide, alerte d'avertissement affichée une fois le chargement de `customersQuery` terminé (pas avant). Répéter avec l'id d'un client désactivé (`active: false`) si un tel client existe en données de test. | Manuel |
| (Risque architecte) `customerId` non numérique ou négatif dans l'URL. | Naviguer vers `/ventes/nouvelle?customerId=abc` puis `/ventes/nouvelle?customerId=-5` → vérifier absence de crash JS (pas de `NaN` affiché dans l'UI, pas d'exception console), comportement identique au cas « client introuvable » (alerte affichée, Autocomplete vide). | Manuel |
| (Risque architecte) Fenêtre de chargement de `customersQuery` / effet déclenché une seule fois. | Simuler un réseau lent (throttling navigateur) sur `/ventes/nouvelle?customerId=<id valide>` → vérifier que l'avertissement de « client introuvable » ne s'affiche pas pendant le chargement (`customersQuery.isLoading`), puis que le client se présélectionne correctement une fois les données arrivées. Ensuite, dans le champ Autocomplete client déjà présélectionné, choisir manuellement un autre client → vérifier que ce changement n'est jamais écrasé par un re-déclenchement de l'effet de présélection (pas de retour au client initial). | Manuel |
| (Risque architecte) Non-régression de l'Autocomplete garant. | Sur `/ventes/nouvelle?customerId=<id valide>`, utiliser l'Autocomplete « Choisir un client comme garant » pour sélectionner un client différent du client principal → vérifier que les champs `guarantorFullName`/`guarantorPhone`/`guarantorAddress` se remplissent comme avant, sans lien ni interférence avec l'état `selectedCustomer` du client principal. | Manuel |
| Cohérence code — no-op strict sans `customerId` | Relecture de code : confirmer que le `useEffect` de présélection sort immédiatement (return anticipé) quand `customerIdParam` est `null`, sans appel à `setValue`/`setSelectedCustomer`, pour les deux entrées existantes vers `/ventes/nouvelle` (`DashboardPage.tsx` ligne 78, `SalesPage.tsx` ligne 71). | Revue de code |

## Écarts identifiés

- **Infrastructure de test frontend absente pour les composants de page.** Le design ne mentionne pas que `frontend/vite.config.ts` restreint aujourd'hui l'exécution Vitest à `src/offline/**/*.test.ts` en environnement `node` (pas de DOM, pas de `@testing-library/react` installé). Aucun des critères d'acceptation de ce ticket ne peut donc être vérifié par un test automatisé sans une décision préalable d'extension d'infrastructure (ajout de `jsdom`/`happy-dom`, `@testing-library/react`, élargissement de `include`). À trancher avant codage : soit ce ticket reste couvert uniquement par des tests manuels (ce que propose ce plan), soit l'extension d'infra est ajoutée en amont dans une tâche dédiée — mais dans ce cas elle doit être explicitement scopée (impact sur le temps CI, dépendances supplémentaires) et validée séparément, pas glissée silencieusement dans ce ticket.
- **Identité exacte de la clé de requête `customer-profile` à invalider.** Le design dit « invalide la query `customer-profile` pour l'id concerné dans tous les cas » sans préciser explicitement quel id utiliser si l'utilisateur change de client manuellement après une présélection initiale (le `customerId` de l'URL diffère alors du `customerId` réellement soumis). La tâche ci-dessus tranche ce point : invalider l'id du formulaire soumis (`form.customerId` / `sale.customerId`), pas `customerIdParam`, pour rester correct dans tous les cas — à confirmer que le codeur suit bien cette résolution et pas l'id de l'URL.

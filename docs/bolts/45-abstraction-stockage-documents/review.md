# Review — #45 Module de stockage de documents abstrait par fournisseur

APPROVE

## Résumé

Diff `master..HEAD` relu en intégralité (backend cœur du stockage, contrôle d'accès/endpoints,
frontend, configuration). Build et tests relancés moi-même (pas seulement les chiffres rapportés
par le codeur) : backend `mvn -o clean compile` + `mvn -o test` → 362 tests, 0 échec ; frontend
`npm run lint` (tsc --noEmit) → OK, `npm test -- --run` → 22 tests, 0 échec, `npm run build` → OK.
L'écart signalé sur `s3-presigner` est vérifié et légitime (voir plus bas).

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | `STORAGE_PROVIDER` (+ variables associées) change le fournisseur sans modification de code | Couvert — `@ConditionalOnProperty` sur `LocalDiskStorage`/`S3DocumentStorage`, `DocumentStorageWiringTest` vérifie qu'un seul bean est actif selon la propriété, `StorageConfigValidatorTest` couvre absent/vide/complet. Vérification manuelle contre un vrai bucket S3 documentée comme hors-portée automatisée dans la spec (écart assumé et raisonnable, pas de MinIO dans le repo). |
| 2 | Instance sans configuration continue de fonctionner en local, à l'identique | Couvert — `LocalDiskStorage` reprend le comportement exact de l'ancien `FileStorageService` (même garde anti-traversal, même format de clé `/uploads/...`), `matchIfMissing = true`, `LocalDiskStorageTest` migre tous les cas de l'ancien test + ajoute `resolve`. |
| 3 | Fichier uploadé plus accessible sans authentification, quel que soit le fournisseur | Couvert — `WebConfig` (ResourceHandler `/uploads/**`) supprimé, `/uploads/**` retiré de `PUBLIC_ENDPOINTS`, donc `anyRequest().authenticated()` s'applique désormais à toute URL. Nouveaux endpoints `GET /api/customers/{id}/photo` et `GET /api/sales/{id}/attachments/{attachmentId}/file` scopés boutique via `getEntity`/`assertAccessible` (réutilisation du scoping existant), testés en 401/200/302/404 côté `*ControllerSecurityTest` et en isolation inter-boutique côté `*ServiceTest`. Grep du repo : aucune référence résiduelle à `FileStorageService` ni à un `ResourceHandler`/endpoint public sur `/uploads`. |
| 4 | Logique métier ignore le fournisseur concret, un seul point d'entrée | Couvert — `CustomerService`/`CreditSaleService` n'importent que `DocumentStorage` (interface), aucun `if (provider == ...)`. `DocumentAccessResponses` est le seul endroit qui distingue `Inline`/`Redirect` (au niveau HTTP, pas métier), conformément au design. `DocumentStorageWiringTest` vérifie qu'un seul bean `DocumentStorage` est actif à la fois. |

## Points vérifiés en détail

- Scoping RBAC des nouveaux endpoints : `CustomerService.resolvePhoto` et `CreditSaleService.resolveAttachment` réutilisent `getEntity(id)` (qui appelle `currentShopContext.assertAccessible(shopId)`) avant tout accès au document ; pour les pièces jointes, `saleAttachmentRepository.findByIdAndSaleId` empêche en plus l'accès à une pièce jointe d'un autre contrat même si l'ID est deviné. Aucune fuite d'accès identifiée entre boutiques/clients. Pas de `@PreAuthorize` supplémentaire nécessaire : cohérent avec le pattern existant du reste des contrôleurs (seuls les endpoints de suppression/annulation sont `ADMIN`-only, la lecture est ouverte à tout utilisateur authentifié scopé boutique, même logique que `GET /{id}` déjà en place).
- `/uploads/**` plus public nulle part : `WebConfig.java` supprimé entièrement (Javadoc mort dans `HttpClientConfig.java` corrigé), `/uploads/**` retiré de `PUBLIC_ENDPOINTS`. Grep sur l'ensemble du repo : les seules références résiduelles à `/uploads` sont (a) la clé de stockage interne encore utilisée par `LocalDiskStorage`/`AppProperties` (attendu, documenté comme tel dans le design), et (b) deux entrées de configuration mortes non mentionnées dans le design/spec : `frontend/vite.config.ts` (proxy dev `/uploads` + `navigateFallbackDenylist` PWA) et `frontend/nginx/locations.conf` lignes 78-81 (`location /uploads/ { proxy_pass ... }`). Ces emplacements ne recréent pas d'accès public : une requête sur `/uploads/...` retombe désormais sur `anyRequest().authenticated()` côté backend (401 sans token, 404 avec token car plus aucun handler Spring n'y répond), donc le critère d'acceptation est respecté malgré ce résidu de configuration. Signalé en finding mineur non bloquant ci-dessous.
- Écart `s3-presigner` : vérifié en relançant `mvn -o clean compile` et `mvn -o dependency:tree -Dincludes=software.amazon.awssdk`, seul `software.amazon.awssdk:s3` (+ transitives) est résolu, `s3-presigner` n'apparaît pas dans l'arbre de dépendances et `S3Presigner` compile et s'exécute quand même : à partir des versions récentes du SDK v2 (confirmé ici en 2.28.29), les classes `software.amazon.awssdk.services.s3.presigner.*` (dont `S3Presigner`) sont bundlées directement dans le jar `s3` (vérifié via `jar tf` sur le jar résolu en cache local). L'écart du codeur n'est pas un raccourci qui casse en pratique : compilation et tests (`S3DocumentStorageTest`, mocks `S3Presigner`) passent réellement, pas seulement en théorie.
- URLs signées S3 côté frontend : `useAuthenticatedFile` ne met rien en cache long terme, fetch authentifié à chaque changement d'`apiUrl` (dépendance d'effet), révocation de l'`ObjectURL` au démontage et à chaque changement, conforme au risque documenté dans `design.md`. Composants `CustomerAvatar`/`AttachmentThumbnail` correctement introduits pour respecter les règles des hooks React dans les `.map()` de listes (`CustomersPage`, `SaleDetailPage`).
- Tests vs plan de tests de la spec : tous les cas listés sont couverts nommément, 401/200 (Inline)/302 (Redirect)/404 sur les deux nouveaux endpoints (`CustomerControllerSecurityTest`, `SaleControllerSecurityTest`), wiring exclusif d'un seul bean `DocumentStorage` (`DocumentStorageWiringTest`), `StorageConfigValidator` avec valeur absente et valeur présente-mais-vide (`StorageConfigValidatorTest`), scoping boutique (`resolvePhotoRejectsCustomerFromAnotherShop`, `resolveAttachmentRejectsSaleFromAnotherShop`), suppression toujours fonctionnelle après renommage (`documentStorage.delete(...)` vérifié dans les tests existants adaptés). `S3DocumentStorageTest` vérifie bien que la validation rejette avant tout appel S3 (`verifyNoInteractions(s3Client)`).

## Findings (non bloquants)

1. Config dev/prod obsolète sur `/uploads` (mineure, cosmétique) — `frontend/vite.config.ts` (lignes 36 et 62) et `frontend/nginx/locations.conf` (lignes 78-81) référencent encore un proxy vers `/uploads/**`, qui n'a plus aucune route côté backend depuis ce ticket (`WebConfig` supprimé). Ça ne crée pas de faille (la requête proxyée échoue avec 401/404 côté backend désormais authentifié), mais c'est de la configuration morte que ce ticket aurait pu nettoyer puisqu'il touche directement ce périmètre. À traiter en petit suivi, pas bloquant pour ce bolt.
2. `useAuthenticatedFile` peut brièvement exposer une `ObjectURL` déjà révoquée (mineure, UX) — `frontend/src/hooks/useAuthenticatedFile.ts` (lignes 20-51) : quand `apiUrl` change sans démontage du composant (ex. navigation entre deux fiches client réutilisant le même `CustomerAvatar`), l'effet de nettoyage révoque l'`ObjectURL` précédente avant que le nouvel appel réseau ne résolve et ne remplace `url` par une nouvelle valeur ; entre les deux, l'`Avatar` pointe momentanément vers une URL déjà révoquée (image cassée le temps du fetch suivant). Pas de risque de sécurité ni de fuite mémoire, juste un flash visuel potentiel sur navigation rapide entre deux entités. Un `setUrl(undefined)` au début de l
'effet réglerait proprement ce point si souhaité dans un futur bolt.

Aucun de ces deux points ne remet en cause l'un des quatre critères d'acceptation ni ne constitue une régression fonctionnelle ou de sécurité ; ils sont documentés pour traçabilité mais n'empêchent pas l'approbation.

## Build/tests

- `cd backend && mvn -o clean compile` -> BUILD SUCCESS (confirme que `S3DocumentStorage` compile réellement avec `S3Presigner`, malgré l'absence de dépendance `s3-presigner` explicite dans le pom).
- `cd backend && mvn -o dependency:tree -Dincludes=software.amazon.awssdk` -> confirme l'absence de `s3-presigner` dans l'arbre résolu (seul `s3` + transitives).
- `cd backend && mvn -o test` -> Tests run: 362, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.
- `cd frontend && npm run lint` (tsc --noEmit) -> OK, aucune erreur.
- `cd frontend && npm test -- --run` (vitest) -> 4 fichiers de test, 22 tests, 0 échec.
- `cd frontend && npm run build` -> OK (avertissement pré-existant sur la taille du chunk principal, sans rapport avec ce ticket).

## Verdict

APPROVE. Les quatre critères d'acceptation sont couverts par du code et des tests qui échoueraient si le code était retiré. Le contrôle d'accès des nouveaux endpoints est correctement scopé boutique/contrat, sans fuite identifiée. `/uploads/**` n'est plus servi ni public. L'écart sur `s3-presigner` est vérifié réel et sans impact (le SDK 2.28.x embarque `S3Presigner` dans le module `s3`). Build et tests backend/frontend passent réellement en local. Les deux findings relevés sont mineurs, non bloquants, et n'affectent ni la sécurité ni les critères d'acceptation du ticket.

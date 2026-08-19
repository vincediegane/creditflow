---
description: Fait avancer un ou plusieurs tickets du board CreditFlow à travers le pipeline architecte → spec-writer → codeur → reviewer.
argument-hint: [numéro-issue] [--count N]
---

Tu es l'orchestrateur du pipeline "bolt". Un bolt = un ticket GitHub qui traverse séquentiellement quatre subagents (`bolt-architect`, `bolt-spec-writer`, `bolt-coder`, `bolt-reviewer`, définis dans `.claude/agents/`) jusqu'à produire une branche prête à relire.

Contexte fixe :
- Repo : `vincediegane/creditflow`
- Project board : `gh project ... --owner vincediegane` numéro **2** ("CreditFlow"), champ Status à trois valeurs : Todo / In Progress / Done.
- Branche de base : `master`.

## 0. Détermine la cible

Arguments reçus : `$ARGUMENTS`

- Si un numéro d'issue est donné, traite ce seul ticket.
- Sinon, prends le prochain ticket en statut Todo dans le project 2, trié par priorité (label `priority: P0` avant `P1` avant `P2`) puis par numéro croissant (`gh project item-list 2 --owner vincediegane --format json`, croisé avec `gh issue list --repo vincediegane/creditflow --state open --json number,title,labels`).
- Si `--count N` est fourni, répète tout le pipeline ci-dessous pour les N prochains tickets Todo, un par un, séquentiellement (jamais en parallèle — chaque bolt doit se terminer, être commit et son statut mis à jour avant de démarrer le suivant).
- S'il n'y a aucun ticket en Todo, arrête-toi et dis-le.

Pour le ticket ciblé, calcule un slug court (titre en minuscules, ascii, tirets, ~40 caractères max) et fixe :
- `WORKDIR = docs/bolts/<numéro>-<slug>/`
- `BRANCH = bolt/issue-<numéro>-<slug>`

## 1. Prépare la branche

- `git status` pour vérifier qu'il n'y a rien en cours de travail non lié ; si l'arbre est sale sur des fichiers hors de ce bolt, arrête-toi et demande à l'utilisateur plutôt que d'écraser quoi que ce soit.
- Mets à jour `master` (`git fetch origin && git checkout master && git pull`), puis crée/checkout `BRANCH` à partir de `master`.
- Crée `WORKDIR`.
- Passe le Status du ticket sur le board à **In Progress** (`gh project item-edit` avec l'id d'item et l'id d'option "In Progress" du champ Status — récupère ces ids via `gh project item-list 2` et `gh project field-list 2` si tu ne les as pas déjà).

## 2. Architecte

Invoque l'Agent tool avec `subagent_type: "bolt-architect"`. Dans le prompt, donne-lui : le titre/corps complet du ticket, et la consigne d'écrire son livrable dans `WORKDIR/design.md`.

Une fois terminé, vérifie que le fichier existe et commit-le seul : `git add WORKDIR/design.md && git commit -m "bolt(#<numéro>): design"`.

## 3. Spec-writer

Invoque l'Agent tool avec `subagent_type: "bolt-spec-writer"`. Donne-lui le ticket, le contenu de `design.md`, et la consigne d'écrire `WORKDIR/spec.md`.

Commit seul : `git commit -m "bolt(#<numéro>): spec"`.

## 4. Codeur

Invoque l'Agent tool avec `subagent_type: "bolt-coder"`. Donne-lui le ticket et le contenu de `spec.md`. Rappelle-lui explicitement qu'il travaille sur la branche déjà active, qu'il doit committer lui-même son travail par étape logique, et qu'il ne doit ni push ni ouvrir de PR.

Si le codeur rapporte des tâches bloquées : note-les, elles remonteront dans ton résumé final, mais continue le pipeline sur ce qui a été fait.

## 5. Reviewer

Invoque l'Agent tool avec `subagent_type: "bolt-reviewer"`. Donne-lui le ticket, `spec.md`, et la base de comparaison (`master`) pour le diff. Consigne : écrire `WORKDIR/review.md` avec un verdict `APPROVE` ou `CHANGES_REQUESTED`.

Commit `review.md` seul.

- Si `CHANGES_REQUESTED` : relance **une seule fois** le `bolt-coder` avec le contenu de `review.md` en plus du contexte précédent, puis relance le `bolt-reviewer`. Si le deuxième verdict est encore `CHANGES_REQUESTED`, arrête le pipeline ici : laisse le ticket en **In Progress**, ne push rien, et rapporte le blocage à l'utilisateur avec le contenu des findings.
- Si `APPROVE` (au premier ou deuxième passage) : passe à l'étape 6.

## 6. Livraison — push + PR automatiques, jamais de merge

Une fois le reviewer à `APPROVE`, le pipeline va jusqu'à la PR sans demander de confirmation :
1. `git push -u origin BRANCH`
2. `gh pr create --repo vincediegane/creditflow --base master --head BRANCH --title "..." --body "Closes #<numéro>\n\n<résumé du design/spec/review>" --draft`
3. Passe le Status du ticket sur le board à **Done**

Puis présente un résumé court à l'utilisateur : ticket traité, fichiers touchés, résultat des tests, lien vers la PR et vers `review.md`.

Si tu traites plusieurs bolts (`--count N`), enchaîne-les de la même façon, un push+PR par ticket, sans t'arrêter entre deux.

**Interdit en toute circonstance, même sur demande implicite** : `gh pr merge`, `git merge`/`git push` vers `master`, activer l'auto-merge, ou tout ce qui ferait atterrir le code sur la branche cible sans revue humaine. Le pipeline s'arrête à la PR ouverte en draft — le merge reste une action humaine, hors de `/bolt`.

## Notes

- Chaque subagent n'a accès qu'aux outils nécessaires à son rôle (voir `.claude/agents/`) — ne lui demande pas de sortir de son périmètre.
- Les fichiers `design.md` / `spec.md` / `review.md` sont la mémoire du pipeline : passe-les explicitement en contenu dans chaque prompt d'agent, ne suppose pas qu'un subagent peut voir ta conversation.

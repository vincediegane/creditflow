-- =====================================================================
-- V11 - Colonnes d'audit manquantes sur users
-- L'entite User etend Auditable depuis le ticket #2, mais V3 n'a ajoute
-- created_by/updated_by qu'aux tables metier : toute insertion dans users
-- (creation de l'administrateur au demarrage, creation d'un compte)
-- echouait sur une base a jour des migrations.
-- =====================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS created_by VARCHAR(80);
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_by VARCHAR(80);

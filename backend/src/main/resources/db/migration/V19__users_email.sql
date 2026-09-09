-- =====================================================================
-- V19 - Ajout d'une colonne email sur users (#52 - notifications SMTP)
-- Nullable : les comptes existants n'ont pas d'email. Pas d'UNIQUE : pas
-- de cas d'usage d'authentification par email dans ce ticket, l'identifiant
-- de connexion reste `username`.
-- `users` n'est pas soumise a RLS (V15), aucune policy a mettre a jour.
-- Aucun GRANT supplementaire requis : V16__app_role_grants.sql accorde
-- deja SELECT/INSERT/UPDATE/DELETE sur `users` au role applicatif.
-- =====================================================================

ALTER TABLE users ADD COLUMN email VARCHAR(255);

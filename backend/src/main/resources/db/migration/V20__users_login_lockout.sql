-- =====================================================================
-- V20 - Verrouillage temporaire de compte apres echecs de connexion (#65)
-- failed_login_attempts : compteur d'echecs consecutifs, remis a zero a
-- chaque connexion reussie et au moment ou le verrou est pose (pas de
-- cumul indefini, cf. AuthService.registerFailedAttempt).
-- locked_until : nullable, NULL = pas de verrou actif. Compare a now()
-- cote application (AuthService.login), pas de contrainte SQL dediee.
-- `users` n'est pas soumise a RLS (V15), aucune policy a mettre a jour.
-- Aucun GRANT supplementaire requis : V16__app_role_grants.sql accorde
-- deja SELECT/INSERT/UPDATE/DELETE sur `users` au role applicatif.
-- =====================================================================

ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMP;

-- =====================================================================
-- Politique de mot de passe
-- Permet d'imposer un changement au premier acces (compte livre avec un
-- mot de passe par defaut) et de tracer la derniere modification.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN password_changed_at TIMESTAMP;

-- Les comptes existants qui utilisent encore un mot de passe de livraison
-- devront le changer a la prochaine connexion.
UPDATE users SET must_change_password = TRUE WHERE password_changed_at IS NULL;

-- =====================================================================
-- V12 - Idempotence de l'enregistrement des versements (#11)
-- Un versement saisi hors-ligne peut etre rejoue plusieurs fois au retour
-- du reseau (reprise apres coupure, rechargement du service worker, double
-- tap). client_request_id est un UUID genere par le client a la validation
-- du formulaire : l'index unique garantit qu'un meme encaissement ne peut
-- pas etre insere deux fois.
--
-- Colonne nullable et sans retro-remplissage : les versements historiques
-- n'ont pas d'origine client. Postgres tolere plusieurs NULL dans un index
-- unique, les lignes existantes ne se genent donc pas entre elles.
-- Ne pas reutiliser "reference" : c'est un numero de bordereau metier,
-- souvent vide et legitimement duplicable.
-- =====================================================================

ALTER TABLE payments ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_client_request_id
    ON payments (client_request_id);

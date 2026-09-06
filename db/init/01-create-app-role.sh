#!/bin/sh
# Cree le role applicatif restreint, hors Flyway (aucun mot de passe versionne).
# Monte sur /docker-entrypoint-initdb.d/ : ne s'execute qu'a la toute premiere
# initialisation du volume Postgres (voir Risques pour les instances existantes).
set -e

: "${DB_APP_USERNAME:?DB_APP_USERNAME doit etre defini}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD doit etre defini}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_APP_USERNAME}') THEN
            CREATE ROLE "${DB_APP_USERNAME}" LOGIN PASSWORD '${DB_APP_PASSWORD}';
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${DB_APP_USERNAME}";
    GRANT USAGE ON SCHEMA public TO "${DB_APP_USERNAME}";
EOSQL

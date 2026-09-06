-- =====================================================================
-- V18 - Octroi des droits au role applicatif restreint sur organization_plan (#42)
-- Migration separee de V16 : V16 est deja appliquee en production, on ne
-- modifie jamais une migration Flyway existante (checksum).
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON organization_plan TO ${creditflowAppRole};

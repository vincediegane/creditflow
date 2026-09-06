-- =====================================================================
-- V16 - Octroi des droits au role applicatif restreint (#40)
-- Suppose que le role ${creditflowAppRole} existe deja (cree par
-- db/init/01-create-app-role.sh, hors Flyway -- voir Risques).
-- Enumeration explicite des tables : ALL TABLES IN SCHEMA public
-- inclurait flyway_schema_history, que le pool applicatif n'a aucune
-- raison de lire ou modifier.
-- audit_log ajoutee (absente de la liste du contrat technique de la spec) :
-- AuditLogService.record() y ecrit depuis la plupart des services metier
-- (PaymentService, CreditSaleService, ProductService, CustomerService,
-- ReminderService, PenaltySettingsService) -- sans ce GRANT, le role
-- applicatif casserait ces ecritures des le premier appel en production,
-- alors meme que la note de la spec rappelle explicitement que RLS et
-- GRANT sont deux mecanismes independants et que l'absence de policy RLS
-- ne dispense pas des privileges de table (voir rapport du codeur, #40).
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON
    organizations,
    shops,
    users,
    user_shops,
    customers,
    products,
    credit_sales,
    installments,
    payments,
    sale_attachments,
    suppliers,
    stock_receptions,
    stock_reception_lines,
    stock_movements,
    penalty_settings,
    audit_log
TO ${creditflowAppRole};

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${creditflowAppRole};

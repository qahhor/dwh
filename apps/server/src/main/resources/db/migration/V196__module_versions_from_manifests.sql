set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: a module's version, the least platform API version it needs and its dependencies come from its manifest
--         META-INF/smartupcms/modules/<code>.json (ADR-0033, 6.4; plan 10/10, item 6.4), so the registry keeps no
--         version of its own: the column held '1.0.0' written by V028 and V182 whatever the code was. No installation
--         holds data (AGENTS.md); the registry shows the manifest's version from now on.
-- approved_by: ADR-0033, 6.4 (plan 10/10, item 6.4)
alter table md_installed_modules drop column version;

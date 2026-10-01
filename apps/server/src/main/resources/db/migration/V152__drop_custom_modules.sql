set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: md_custom_modules held the switched-off "Plugin SDK" model (created in V017, every row set to DISABLED in
--         V019). No code reads or writes it since V019, nothing references it by a foreign key, and modules are
--         registered in md_installed_modules (V028); the rows describe modules that can no longer run.
-- approved_by: product owner (plan 10/10, item 4.7)
-- Plan 10/10, item 4.7: the dead table goes in its own migration. Its indexes (V017, V128) go with it.
drop table if exists md_custom_modules;

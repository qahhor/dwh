set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 4.6: validates the keys and checks V149 added as NOT VALID. VALIDATE CONSTRAINT takes a lock that
-- lets reads and writes go on while it scans the existing rows, which is why it runs apart from V149.
-- A failure names the constraint and a row that breaks it: correct that row and run the migration again
-- (docs/ops/migration-repair.md).
alter table upl_packages validate constraint upl_packages_fk_uploaded_by;
alter table upl_packages validate constraint upl_packages_fk_load;
alter table search_jobs validate constraint search_jobs_fk_actor;
alter table search_settings validate constraint search_settings_fk_updated_by;

alter table md_installed_modules validate constraint md_installed_modules_ck_attributes;
alter table md_users validate constraint md_users_ck_attributes;
alter table ms_notes validate constraint ms_notes_ck_attributes;
alter table ms_task_projects validate constraint ms_task_projects_ck_attributes;
alter table ms_tasks validate constraint ms_tasks_ck_attributes;

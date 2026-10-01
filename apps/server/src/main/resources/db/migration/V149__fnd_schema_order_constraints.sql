set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 4.6: order in the schema. The file carries the fnd prefix because it names the registry of loads,
-- which only fnd migrations may name.
--
-- 1. Reference columns that had no foreign key. Each key is added NOT VALID, which takes a short lock and checks only
--    new rows; V150 validates the existing rows in a transaction of its own, so writes go on during that scan.
alter table upl_packages
    add constraint upl_packages_fk_uploaded_by foreign key (uploaded_by_id) references md_users (id) not valid;
alter table upl_packages
    add constraint upl_packages_fk_load foreign key (load_id) references fnd_loads (id) not valid;
alter table search_jobs
    add constraint search_jobs_fk_actor foreign key (actor_id) references md_users (id) not valid;
alter table search_settings
    add constraint search_settings_fk_updated_by foreign key (updated_by) references md_users (id) not valid;

-- Every foreign key has an index on its columns (plan 10/10, item 3.7). These tables are small.
create index if not exists upl_packages_uploaded_by_id_idx on upl_packages (uploaded_by_id);
create index if not exists upl_packages_load_id_idx on upl_packages (load_id);
create index if not exists search_jobs_actor_id_idx on search_jobs (actor_id);
create index if not exists search_settings_updated_by_idx on search_settings (updated_by);

-- 2. The extra fields of a record (attributes) are always one JSON object: the code reads them as a map, and the
--    custom field migrations merge into them with ||, which turns an array or a scalar into something else.
alter table md_installed_modules
    add constraint md_installed_modules_ck_attributes check (jsonb_typeof(attributes) = 'object') not valid;
alter table md_users
    add constraint md_users_ck_attributes check (jsonb_typeof(attributes) = 'object') not valid;
alter table ms_notes
    add constraint ms_notes_ck_attributes check (jsonb_typeof(attributes) = 'object') not valid;
alter table ms_task_projects
    add constraint ms_task_projects_ck_attributes check (jsonb_typeof(attributes) = 'object') not valid;
alter table ms_tasks
    add constraint ms_tasks_ck_attributes check (jsonb_typeof(attributes) = 'object') not valid;

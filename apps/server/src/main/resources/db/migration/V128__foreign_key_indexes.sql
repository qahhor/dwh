set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.7: every foreign key gets an index that starts with its columns, so deleting or updating a
-- referenced row does not scan the referencing table under a lock (ForeignKeyIndexTest). These tables stay small;
-- the large ones are indexed concurrently in V129. Custom field equality reads attributes with @> (QueryPlan), which a
-- GIN index serves: users and tasks have one since V001, projects and notes get theirs here.
create index if not exists ms_task_projects_attributes_gin_idx on ms_task_projects using gin (attributes jsonb_path_ops);
create index if not exists ms_notes_attributes_gin_idx on ms_notes using gin (attributes jsonb_path_ops);
create index if not exists fnd_job_queue_schedule_code_idx on fnd_job_queue (schedule_code);
create index if not exists fnd_loads_superseded_by_idx on fnd_loads (superseded_by);
create index if not exists fnd_unit_coefficients_to_unit_idx on fnd_unit_coefficients (to_unit);
create index if not exists fnd_units_base_unit_code_idx on fnd_units (base_unit_code);
create index if not exists kauth_api_tokens_user_id_idx on kauth_api_tokens (user_id);
create index if not exists kwh_subscriptions_created_by_idx on kwh_subscriptions (created_by);
create index if not exists md_custom_modules_created_by_idx on md_custom_modules (created_by);
create index if not exists md_effective_permissions_source_role_id_idx on md_effective_permissions (source_role_id);
create index if not exists md_i18n_languages_created_by_idx on md_i18n_languages (created_by);
create index if not exists md_i18n_languages_modified_by_idx on md_i18n_languages (modified_by);
create index if not exists md_i18n_translation_overrides_modified_by_idx on md_i18n_translation_overrides (modified_by);
create index if not exists md_navigation_items_created_by_idx on md_navigation_items (created_by);
create index if not exists md_navigation_items_modified_by_idx on md_navigation_items (modified_by);
create index if not exists md_role_permissions_form_code_action_idx on md_role_permissions (form_code, action);
create index if not exists md_user_roles_role_id_idx on md_user_roles (role_id);
create index if not exists ms_announcement_reads_user_id_idx on ms_announcement_reads (user_id);
create index if not exists ms_announcements_created_by_idx on ms_announcements (created_by);
create index if not exists ms_notes_modified_by_idx on ms_notes (modified_by);
create index if not exists ms_task_project_members_user_id_idx on ms_task_project_members (user_id);
create index if not exists ms_task_projects_created_by_idx on ms_task_projects (created_by);
create index if not exists search_jobs_generation_id_idx on search_jobs (generation_id);
create index if not exists search_jobs_retry_of_job_id_idx on search_jobs (retry_of_job_id);
create index if not exists upl_packages_file_id_idx on upl_packages (file_id);
create index if not exists upl_packages_source_id_format_version_idx on upl_packages (source_id, format_version);

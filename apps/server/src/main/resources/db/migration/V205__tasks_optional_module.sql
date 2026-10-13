set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: tasks and projects are optional business modules; their registry switch must be available (FR-MOD-01).
-- approved_by: product owner, 2026-10-12
update md_installed_modules
set is_system = false, modified_at = clock_timestamp(), revision = revision + 1
where code = 'tasks' and is_system;

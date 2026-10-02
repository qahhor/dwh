set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 10.1 and 6.10 (plan 10/10, item 5.8): the right of an import, the action import of the form of every
-- entity that declares IMPORT - the reference orders (area example) and the task types (area tasks) - granted to the
-- roles that may already create its records; and the hourly cleanup of the imports whose week is over (the report file
-- first, then the journal row). Seed data only: the tables are V186's.
insert into md_form_actions (form_code, action, name) values
('example.orders', 'import', 'Импорт заказов'),
('tasks.types', 'import', 'Импорт типов задач')
on conflict (form_code, action) do nothing;

-- A role that creates the records of a form imports them too
insert into md_role_permissions (role_id, form_code, action)
select rp.role_id, rp.form_code, 'import'
from md_role_permissions rp
where rp.form_code in ('example.orders', 'tasks.types') and rp.action = 'create'
on conflict do nothing;

insert into fnd_job_schedule (code, handler, interval_sec, args)
select 'report.import_cleanup', 'report.import_cleanup', 3600, '{}'::jsonb
where not exists (select 1 from fnd_job_schedule where code = 'report.import_cleanup');

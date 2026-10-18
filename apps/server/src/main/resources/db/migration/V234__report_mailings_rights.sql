set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0045: the rights of report mailings. "own" keeps the mailings of the reports a person saved; "manage" keeps the
-- mailings of any report the person may read (administrators). A mailing still runs as each recipient.
insert into md_forms (code, module, name) values ('report.mailings', 'report', 'Рассылки отчётов') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values
('report.mailings', 'own', 'Свои рассылки'),
('report.mailings', 'manage', 'Все рассылки')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'report.mailings', a.action
from md_roles r cross join (values ('own'), ('manage')) as a (action)
where r.pcode in ('chief_admin', 'admin')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'report.mailings', 'own'
from md_roles r
where r.pcode in ('manager', 'user')
on conflict do nothing;
-- The scheduler looks for due mailings every minute; the job queue lets one node take each tick.
insert into fnd_job_schedule (code, handler, interval_sec, args)
select 'report.mailing_tick', 'report.mailing_tick', 60, '{}'::jsonb
where not exists (select 1 from fnd_job_schedule where code = 'report.mailing_tick');

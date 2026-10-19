set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0046: the rights of automations of live forms. "view" reads the automations and their run log, "manage" keeps
-- them; only administrators get them. An automation still runs with the rights of its author.
insert into md_forms (code, module, name) values ('studio.automations', 'studio', 'Автоматизации') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values
('studio.automations', 'view', 'Просмотр'),
('studio.automations', 'manage', 'Ведение')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'studio.automations', a.action
from md_roles r cross join (values ('view'), ('manage')) as a (action)
where r.pcode in ('chief_admin', 'admin')
on conflict do nothing;
-- The scheduler starts the due scheduled automations and the runs whose retry is due, every minute on one node.
insert into fnd_job_schedule (code, handler, interval_sec, args)
select 'studio.automation_tick', 'studio.automation_tick', 60, '{}'::jsonb
where not exists (select 1 from fnd_job_schedule where code = 'studio.automation_tick');

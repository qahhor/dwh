set lock_timeout = '2s';
set statement_timeout = '60s';
-- ============================================================================
-- V119: Расписание чистки истёкших выгрузок (ADR-0018). Сид отдельно от DDL V118.
-- ============================================================================

-- Истёкшие выгрузки удаляются раз в час: файл из хранилища, затем строка журнала.
insert into fnd_job_schedule (code, handler, interval_sec, args)
select 'report.export_cleanup', 'report.export_cleanup', 3600, '{}'::jsonb
where not exists (select 1 from fnd_job_schedule where code = 'report.export_cleanup');

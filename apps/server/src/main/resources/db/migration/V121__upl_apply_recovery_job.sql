set lock_timeout = '2s';
set statement_timeout = '60s';
-- ============================================================================
-- V121: Расписание восстановления прерванных применений пакетов (P0 DWH). Только сид.
-- ============================================================================

-- Раз в 15 минут: пакет «проверен» с загрузкой pending старше часа закрывается «отклонён системой».
insert into fnd_job_schedule (code, handler, interval_sec, args)
select 'upl.apply_recovery', 'upl.apply_recovery', 900, '{"staleMinutes": 60}'::jsonb
where not exists (select 1 from fnd_job_schedule where code = 'upl.apply_recovery');

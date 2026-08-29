-- ============================================================================
-- V011: запас месячных партиций audit_log
--
-- В V001 созданы только 2026-08, 2026-09 и default. С 1 октября 2026 весь
-- аудит уходил бы в default, а это тупик: отцепить default нельзя (FR-AUD-2
-- требует retention с отцеплением), и создать октябрьскую партицию задним
-- числом уже не выйдет — PostgreSQL при create table ... partition of
-- сканирует default и откажет, пока в нём есть подходящие строки.
--
-- Здесь закрывается горизонт до конца 2027 года. Дальше партиции досоздаёт
-- AuditPartitionWorker; миграция нужна, чтобы запас был сразу, не дожидаясь
-- первого запуска воркера.
-- ============================================================================

do $$
declare
    m date := date '2026-10-01';
    last_month date := date '2028-01-01';
    part_name text;
begin
    while m < last_month loop
        part_name := 'audit_log_' || to_char(m, 'YYYY_MM');
        if not exists (select 1 from pg_class where relname = part_name) then
            execute format(
                'create table %I partition of audit_log for values from (%L) to (%L)',
                part_name, m, m + interval '1 month');
        end if;
        m := (m + interval '1 month')::date;
    end loop;
end $$;

comment on table audit_log_default is
    'Аварийный приёмник. Строки здесь означают, что партиция за месяц не была '
    'создана вовремя: retention их не отцепит, а создание нужной партиции '
    'заблокировано, пока они не перенесены. Пустота — норма.';

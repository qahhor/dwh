-- ============================================================================
-- V014: audit_log действительно неизменяемый (FR-AUD-1, цель вехи M8)
--
-- Веха M8 заявляет «неизменяемый партиционированный журнал аудита», но ни
-- ограничения, ни триггера, ни отзыва прав в схеме не было: строку журнала
-- можно было отредактировать или удалить обычным UPDATE / DELETE тем же
-- соединением, которым работает приложение.
--
-- Для журнала, которым доказывают «кто кому выдал право», это отменяет смысл
-- самого журнала: правка не оставляет следа и неотличима от его отсутствия.
--
-- Приложение никогда не выполняет UPDATE или DELETE по audit_log — проверено
-- по коду, — поэтому запрет ничего не ломает.
--
-- Чего триггер намеренно НЕ трогает:
--   * INSERT — единственная операция, которая журналу нужна;
--   * DETACH PARTITION — это DDL, срок хранения (FR-AUD-2) продолжает работать;
--   * ручное вмешательство эксплуатации — триггер можно снять, но это
--     осознанное действие с правами владельца, а не побочный эффект запроса.
-- ============================================================================

create or replace function audit_log_immutable() returns trigger
    language plpgsql as $$
begin
    raise exception
        'audit_log неизменяем: операция % запрещена (FR-AUD-1). Срок хранения '
        'реализован отцеплением партиций, а не удалением строк', tg_op
        using errcode = 'restrict_violation';
end;
$$;

comment on function audit_log_immutable() is
    'Запрет UPDATE/DELETE по audit_log: журнал аудита только дополняется';

create trigger audit_log_no_update
    before update on audit_log
    for each row execute function audit_log_immutable();

create trigger audit_log_no_delete
    before delete on audit_log
    for each row execute function audit_log_immutable();

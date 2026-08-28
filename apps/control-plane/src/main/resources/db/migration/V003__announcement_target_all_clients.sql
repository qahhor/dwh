-- ============================================================================
-- V003: корректная адресация объявлений «всем клиентам» (FR-CP-5)
--
-- В V001 колонка cp_announcement_targets.client_id входит в первичный ключ,
-- поэтому NULL в ней невозможен — а комментарий обещал «NULL = все клиенты».
-- Семантика противоречила ограничению, вставка падала.
--
-- Решение: «для всех» — свойство объявления, а не строка в таблице целей.
-- Таблица целей хранит только явно перечисленных клиентов.
-- ============================================================================

alter table cp_announcements
    add column if not exists is_for_all_clients boolean not null default true;

comment on column cp_announcements.is_for_all_clients is
    'true — объявление для всех клиентов; false — только для перечисленных в cp_announcement_targets';

comment on table cp_announcement_targets is
    'Явные адресаты объявления. Используется только при is_for_all_clients = false';

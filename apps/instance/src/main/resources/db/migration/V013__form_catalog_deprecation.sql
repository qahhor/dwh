-- ============================================================================
-- V013: пометка устаревших форм и действий каталога прав (FR-PERM-1, Д-5)
--
-- Каталог форм наполнялся миграциями, а метод регистрации из кода
-- (MdPermissionService.initSystemFormsIfEmpty) не вызывался ниоткуда — то есть
-- требование «каталог регистрируется из кода по @RequiresPermission» не
-- выполнялось вовсе, а не наполовину, как считала ревизия AUDIT-05.
--
-- Следствие видно на живом стенде: администратор видит в матрице прав пары,
-- за которыми нет ни одного эндпоинта, и может их выдать. Право выдано,
-- ничего не открывает — и это неотличимо от ошибки настройки доступа.
-- На 30.08 таких пар четыре: notify.preferences.view, notify.preferences.update,
-- iam.profile.manage_channels, platform.files.manage_quotas.
--
-- Удалять их нельзя: удаление формы каскадом снимет уже выданные права, а
-- временное переименование эндпоинта молча лишило бы людей доступа. Поэтому
-- запись остаётся, но помечается устаревшей — её видно, и она не выдаётся.
-- ============================================================================

alter table md_forms add column is_deprecated boolean not null default false;
alter table md_form_actions add column is_deprecated boolean not null default false;

comment on column md_forms.is_deprecated is
    'Форма отсутствует среди @RequiresPermission в коде: право по ней ничего не открывает';
comment on column md_form_actions.is_deprecated is
    'Действие отсутствует среди @RequiresPermission в коде: право по нему ничего не открывает';

-- Частичный индекс: запросы матрицы прав интересуются живыми записями,
-- а устаревших всегда меньшинство.
create index md_forms_active_idx on md_forms (code) where not is_deprecated;
create index md_form_actions_active_idx on md_form_actions (form_code, action) where not is_deprecated;

set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: встроенными остаются ru, uz, en; каталоги kk, ky, tg, de, tr удалены из поставки. Строки не удаляются:
--         языки становятся пользовательскими и выключенными, их overrides сохраняются.
-- approved_by: владелец продукта (решение 27.09.2026)
-- Built-in languages are Russian, Uzbek and English (2026-09-27). Kazakh, Kyrgyz, Tajik, German and Turkish
-- no longer ship a catalog: they become the administrator's own languages, switched off, so any overrides an
-- installation made stay and the language can be switched back on and translated in the language editor.
update md_i18n_languages
set is_builtin = false,
    is_active = false,
    revision = revision + 1,
    modified_at = now()
where code in ('kk', 'ky', 'tg', 'de', 'tr');

-- A person whose language is switched off reads Russian, the canonical catalog.
update md_users
set language = 'ru'
where language in ('kk', 'ky', 'tg', 'de', 'tr');

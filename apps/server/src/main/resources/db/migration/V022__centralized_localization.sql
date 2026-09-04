-- Centralized, instance-wide localization registry and administrator overrides.

create table md_i18n_languages (
    code text primary key,
    name text not null,
    is_builtin boolean not null default false,
    is_active boolean not null default true,
    revision bigint not null default 1 check (revision > 0),
    created_by bigint references md_users(id) on delete set null,
    modified_by bigint references md_users(id) on delete set null,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now(),
    check (code = lower(code)),
    check (code ~ '^[a-z]{2,3}(-[a-z0-9]{2,8})*$'),
    check (length(btrim(name)) between 1 and 100)
);

create table md_i18n_translation_overrides (
    language_code text not null references md_i18n_languages(code) on delete cascade,
    translation_key text not null,
    value text not null,
    modified_by bigint references md_users(id) on delete set null,
    modified_at timestamptz not null default now(),
    primary key (language_code, translation_key),
    check (length(btrim(translation_key)) between 1 and 200),
    check (length(value) between 1 and 4000)
);

create index md_i18n_languages_active_idx
    on md_i18n_languages (is_active, created_at, code);

insert into md_i18n_languages (code, name, is_builtin, is_active)
values
    ('ru', 'Русский', true, true),
    ('uz', 'Oʻzbekcha', true, true),
    ('en', 'English', true, true),
    ('kk', 'Қазақша', true, true),
    ('ky', 'Кыргызча', true, true),
    ('tg', 'Тоҷикӣ', true, true),
    ('de', 'Deutsch', true, true),
    ('tr', 'Türkçe', true, true);

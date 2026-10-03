set lock_timeout = '2s';
set statement_timeout = '60s';
-- The books of the library module (ADR-0033, 8): a table of an entity on the general runtime (ADR-0032, 14.1). The
-- module numbers its migrations itself; the platform applies them after its own, with the history in
-- flyway_module_library (ADR-0033, 6.5).
create table lib_books (
    id          bigint generated always as identity constraint lib_books_pkey primary key,
    title       text not null constraint lib_books_ck_title check (char_length(title) <= 200),
    isbn        text constraint lib_books_ck_isbn check (isbn ~ '^[0-9-]{1,17}$'),
    pages       integer constraint lib_books_ck_pages check (pages between 1 and 100000),
    lent        boolean not null default false,
    attributes  jsonb not null default '{}' constraint lib_books_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    archived_at timestamptz,
    archived_by bigint constraint lib_books_fk_archived_by references md_users (id),
    created_by  bigint not null constraint lib_books_fk_created_by references md_users (id),
    modified_by bigint not null constraint lib_books_fk_modified_by references md_users (id),
    created_at  timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp(),
    revision    bigint not null default 1
);
create index lib_books_created_by_idx on lib_books (created_by);
create index lib_books_modified_by_idx on lib_books (modified_by);
create index lib_books_archived_by_idx on lib_books (archived_by);

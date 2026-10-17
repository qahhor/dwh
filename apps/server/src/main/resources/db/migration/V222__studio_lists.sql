set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: reference lists an administrator keeps without a release; live form fields store the code of an item.
create table studio_lists (
    id bigint generated always as identity constraint studio_lists_pkey primary key,
    code text not null constraint studio_lists_ck_code check (code ~ '^[a-z][a-z0-9_]{0,39}$'),
    name_ru text not null constraint studio_lists_ck_name_ru check (char_length(name_ru) between 1 and 80),
    name_uz text not null constraint studio_lists_ck_name_uz check (char_length(name_uz) between 1 and 80),
    name_en text not null constraint studio_lists_ck_name_en check (char_length(name_en) between 1 and 80),
    revision bigint not null default 1
        constraint studio_lists_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_by bigint not null constraint studio_lists_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_by bigint not null constraint studio_lists_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint studio_lists_uk_code unique (code)
);
create index studio_lists_created_by_idx on studio_lists (created_by);
create index studio_lists_modified_by_idx on studio_lists (modified_by);

create table studio_list_items (
    id bigint generated always as identity constraint studio_list_items_pkey primary key,
    list_id bigint not null constraint studio_list_items_fk_list references studio_lists (id),
    code text not null constraint studio_list_items_ck_code check (code ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    name_ru text not null constraint studio_list_items_ck_name_ru check (char_length(name_ru) between 1 and 80),
    name_uz text not null constraint studio_list_items_ck_name_uz check (char_length(name_uz) between 1 and 80),
    name_en text not null constraint studio_list_items_ck_name_en check (char_length(name_en) between 1 and 80),
    sort_order integer not null constraint studio_list_items_ck_sort_order check (sort_order between 1 and 9999),
    active boolean not null default true,
    revision bigint not null default 1
        constraint studio_list_items_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_by bigint not null constraint studio_list_items_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_by bigint not null constraint studio_list_items_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint studio_list_items_uk_list_code unique (list_id, code)
);
create index studio_list_items_created_by_idx on studio_list_items (created_by);
create index studio_list_items_modified_by_idx on studio_list_items (modified_by);

-- Records keep item codes: a list and an item are never deleted and an item never changes its code or list; an item
-- that is no longer offered is deactivated.
create function studio_lists_keep_code() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if new.code = old.code then
            return new;
        end if;
    end if;
    raise exception 'Reference list codes are permanent' using errcode = 'restrict_violation';
end;
$$;

create function studio_list_items_keep_code() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if new.code = old.code and new.list_id = old.list_id then
            return new;
        end if;
    end if;
    raise exception 'Reference list codes are permanent' using errcode = 'restrict_violation';
end;
$$;

create trigger studio_lists_permanent before update or delete on studio_lists
for each row execute function studio_lists_keep_code();

create trigger studio_lists_no_truncate before truncate on studio_lists
for each statement execute function studio_lists_keep_code();

create trigger studio_list_items_permanent before update or delete on studio_list_items
for each row execute function studio_list_items_keep_code();

create trigger studio_list_items_no_truncate before truncate on studio_list_items
for each statement execute function studio_list_items_keep_code();

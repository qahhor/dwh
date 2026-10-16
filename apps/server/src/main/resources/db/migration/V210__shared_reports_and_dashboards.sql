set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0036: internal user preferences, not generated runtime entities or published definition packages.
create table md_list_view_roles (
    view_id bigint not null constraint md_list_view_roles_fk_view references md_list_views (id) on delete cascade,
    role_id bigint not null constraint md_list_view_roles_fk_role references md_roles (id) on delete cascade,
    constraint md_list_view_roles_pkey primary key (view_id, role_id)
);
create index md_list_view_roles_role_idx on md_list_view_roles (role_id, view_id);
create index md_list_views_reports_page_idx on md_list_views (modified_at desc, id desc) where kind in ('report', 'widget');

create table md_dashboards (
    id bigint generated always as identity constraint md_dashboards_pkey primary key,
    user_id bigint not null constraint md_dashboards_fk_user references md_users (id) on delete cascade,
    name text not null constraint md_dashboards_ck_name check (length(btrim(name)) between 1 and 80),
    widgets jsonb not null constraint md_dashboards_ck_widgets check (jsonb_typeof(widgets) = 'array' and jsonb_array_length(widgets) <= 12),
    revision bigint not null default 1 constraint md_dashboards_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp()
);
create unique index md_dashboards_user_name_uq on md_dashboards (user_id, lower(btrim(name)));
create index md_dashboards_page_idx on md_dashboards (modified_at desc, id desc);
create table md_dashboard_roles (
    dashboard_id bigint not null constraint md_dashboard_roles_fk_dashboard references md_dashboards (id) on delete cascade,
    role_id bigint not null constraint md_dashboard_roles_fk_role references md_roles (id) on delete cascade,
    constraint md_dashboard_roles_pkey primary key (dashboard_id, role_id)
);
create index md_dashboard_roles_role_idx on md_dashboard_roles (role_id, dashboard_id);
set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 9.4 (plan 10/10, item 5.7): the reference "document with lines and statuses" of the low-code platform, the
-- module example. An order has a number from its sequence, a customer, a date, a currency, a status of its process
-- (draft, posted, cancelled) and the org unit of its scope (ADR-0013); its lines are a child table without a revision
-- or history of their own: the order's revision rises and its audit names the change when a line changes. Names,
-- types and indexes by ADR-0020 and ADR-0032, 14.1; every foreign key has its index.
create sequence ex_orders_number_seq;

create table ex_orders (
    id bigint generated always as identity constraint ex_orders_pkey primary key,
    number text not null constraint ex_orders_ck_number check (char_length(number) <= 32),
    customer text not null constraint ex_orders_ck_customer check (char_length(customer) between 1 and 255),
    order_date date not null default current_date,
    currency text not null default 'UZS' constraint ex_orders_ck_currency check (currency in ('UZS', 'USD', 'EUR')),
    status text not null default 'draft'
        constraint ex_orders_ck_status check (status in ('draft', 'posted', 'cancelled')),
    comment text constraint ex_orders_ck_comment check (char_length(comment) <= 2000),
    org_unit_id bigint constraint ex_orders_fk_org_unit references md_org_units (id),
    attributes jsonb not null default '{}'::jsonb
        constraint ex_orders_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    created_by bigint not null constraint ex_orders_fk_created_by references md_users (id),
    modified_by bigint not null constraint ex_orders_fk_modified_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp(),
    revision bigint not null default 1
);

create unique index ex_orders_number_uq on ex_orders (number);
create index ex_orders_org_unit_id_idx on ex_orders (org_unit_id);
create index ex_orders_created_by_idx on ex_orders (created_by);
create index ex_orders_modified_by_idx on ex_orders (modified_by);

create table ex_order_lines (
    id bigint generated always as identity constraint ex_order_lines_pkey primary key,
    order_id bigint not null constraint ex_order_lines_fk_order references ex_orders (id) on delete cascade,
    position integer not null constraint ex_order_lines_ck_position check (position >= 1),
    product text not null constraint ex_order_lines_ck_product check (char_length(product) between 1 and 255),
    qty numeric(15, 3) not null constraint ex_order_lines_ck_qty check (qty > 0),
    price numeric(19, 4) not null constraint ex_order_lines_ck_price check (price >= 0)
);

create index ex_order_lines_order_id_idx on ex_order_lines (order_id, position);

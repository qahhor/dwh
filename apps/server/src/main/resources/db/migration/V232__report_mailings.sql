set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0045: scheduled mailings of saved reports. A mailing names a saved report, the users it goes to, the channels
-- (email, Telegram, in-app), the format (an XLSX attachment and/or a link), a daily, weekly or monthly schedule at a
-- local time in a time zone, fixed values of the report's parameters and a condition. Each run executes the report as
-- each recipient, never as the mailing's owner; a run row per scheduled slot is unique, so a slot is sent once across
-- nodes. Deleting the saved report deletes its mailings and their history.
create table report_mailings (
    id bigint generated always as identity constraint report_mailings_pkey primary key,
    entity_code text not null
        constraint report_mailings_ck_entity check (entity_code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,3}$'),
    view_id bigint not null constraint report_mailings_fk_view references md_list_views (id) on delete cascade,
    name text not null constraint report_mailings_ck_name check (char_length(btrim(name)) between 1 and 120),
    channels text[] not null
        constraint report_mailings_ck_channels check (
            cardinality(channels) between 1 and 3 and channels <@ array['email', 'telegram', 'in_app']::text[]),
    attach_xlsx boolean not null,
    include_link boolean not null,
    frequency text not null constraint report_mailings_ck_frequency check (frequency in ('daily', 'weekly', 'monthly')),
    weekday smallint constraint report_mailings_ck_weekday check (weekday between 1 and 7),
    month_day smallint constraint report_mailings_ck_month_day check (month_day between 1 and 31),
    send_time time not null,
    time_zone text not null constraint report_mailings_ck_time_zone check (char_length(time_zone) between 1 and 64),
    parameters jsonb not null default '{}'::jsonb
        constraint report_mailings_ck_parameters check (jsonb_typeof(parameters) = 'object'),
    condition jsonb not null default '{"kind": "always"}'::jsonb
        constraint report_mailings_ck_condition check (jsonb_typeof(condition) = 'object'),
    active boolean not null default true,
    next_run_at timestamptz,
    owner_id bigint not null constraint report_mailings_fk_owner references md_users (id),
    revision bigint not null default 1
        constraint report_mailings_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_at timestamptz not null default clock_timestamp(),
    modified_by bigint not null constraint report_mailings_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint report_mailings_ck_format check (attach_xlsx or include_link),
    constraint report_mailings_ck_schedule check (
        (frequency = 'daily' and weekday is null and month_day is null)
        or (frequency = 'weekly' and weekday is not null and month_day is null)
        or (frequency = 'monthly' and weekday is null and month_day is not null)),
    constraint report_mailings_ck_due check (active or next_run_at is null)
);
create index report_mailings_view_idx on report_mailings (view_id, created_at desc, id desc);
create index report_mailings_due_idx on report_mailings (next_run_at) where active;
create index report_mailings_owner_idx on report_mailings (owner_id);
create index report_mailings_modified_by_idx on report_mailings (modified_by);

-- The users a mailing goes to; each run reads them anew, so a blocked or anonymized user is skipped then.
create table report_mailing_recipients (
    mailing_id bigint not null
        constraint report_mailing_recipients_fk_mailing references report_mailings (id) on delete cascade,
    user_id bigint not null constraint report_mailing_recipients_fk_user references md_users (id),
    constraint report_mailing_recipients_pkey primary key (mailing_id, user_id)
);
create index report_mailing_recipients_user_idx on report_mailing_recipients (user_id);

-- One run of a mailing: a scheduled slot (unique per mailing), a send now to every recipient, or a test send to the
-- person who asked. The counters count the deliveries below.
create table report_mailing_runs (
    id bigint generated always as identity constraint report_mailing_runs_pkey primary key,
    mailing_id bigint not null
        constraint report_mailing_runs_fk_mailing references report_mailings (id) on delete cascade,
    kind text not null constraint report_mailing_runs_ck_kind check (kind in ('scheduled', 'manual', 'test')),
    slot timestamptz not null,
    status text not null default 'queued'
        constraint report_mailing_runs_ck_status check (
            status in ('queued', 'running', 'done', 'partial', 'failed', 'skipped')),
    requested_by bigint constraint report_mailing_runs_fk_requested_by references md_users (id),
    recipients integer not null default 0 constraint report_mailing_runs_ck_recipients check (recipients >= 0),
    sent integer not null default 0 constraint report_mailing_runs_ck_sent check (sent >= 0),
    skipped integer not null default 0 constraint report_mailing_runs_ck_skipped check (skipped >= 0),
    failed integer not null default 0 constraint report_mailing_runs_ck_failed check (failed >= 0),
    error text constraint report_mailing_runs_ck_error check (char_length(error) <= 200),
    created_at timestamptz not null default clock_timestamp(),
    started_at timestamptz,
    finished_at timestamptz
);
create unique index report_mailing_runs_slot_uq on report_mailing_runs (mailing_id, slot) where kind = 'scheduled';
create index report_mailing_runs_mailing_idx on report_mailing_runs (mailing_id, created_at desc, id desc);
create index report_mailing_runs_created_idx on report_mailing_runs (created_at);
create index report_mailing_runs_requested_by_idx on report_mailing_runs (requested_by);

-- What each recipient got on each channel in a run: sent (in-app), queued (handed to the notification outbox with its
-- key), skipped with a reason, or failed with a reason. Unique per run, user and channel, so a retried run never sends
-- twice.
create table report_mailing_deliveries (
    id bigint generated always as identity constraint report_mailing_deliveries_pkey primary key,
    run_id bigint not null
        constraint report_mailing_deliveries_fk_run references report_mailing_runs (id) on delete cascade,
    user_id bigint not null constraint report_mailing_deliveries_fk_user references md_users (id),
    channel text not null
        constraint report_mailing_deliveries_ck_channel check (channel in ('email', 'telegram', 'in_app')),
    status text not null
        constraint report_mailing_deliveries_ck_status check (status in ('sent', 'queued', 'skipped', 'failed')),
    reason text constraint report_mailing_deliveries_ck_reason check (reason ~ '^[a-z][a-z_]{0,39}$'),
    outbox_key uuid,
    row_count integer constraint report_mailing_deliveries_ck_row_count check (row_count >= 0),
    created_at timestamptz not null default clock_timestamp(),
    constraint report_mailing_deliveries_uk_target unique (run_id, user_id, channel)
);
create index report_mailing_deliveries_user_idx on report_mailing_deliveries (user_id);

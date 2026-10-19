set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0046: automations of live forms. An automation belongs to one live form: a trigger (a record created, updated,
-- saved, moved by a transition, deleted, or a calendar schedule over the records), an optional condition formula and
-- 1 to 10 ordered actions (set fields, create a record of another live form, run a transition, notify). It runs with
-- the rights of its author, the person who last saved or switched it on, checked when it is saved and again at every
-- run; every change it makes names it in the audit.
create table studio_automations (
    id bigint generated always as identity constraint studio_automations_pkey primary key,
    form_code text not null constraint studio_automations_ck_form check (form_code ~ '^studio\.[a-z][a-z0-9_]{0,62}$'),
    name text not null constraint studio_automations_ck_name check (char_length(btrim(name)) between 1 and 120),
    trigger_kind text not null
        constraint studio_automations_ck_trigger_kind check (
            trigger_kind in ('created', 'updated', 'saved', 'transition', 'deleted', 'scheduled')),
    trigger jsonb not null default '{}'::jsonb
        constraint studio_automations_ck_trigger check (jsonb_typeof(trigger) = 'object'),
    condition text constraint studio_automations_ck_condition check (btrim(condition) <> ''),
    actions jsonb not null
        constraint studio_automations_ck_actions check (
            jsonb_typeof(actions) = 'array' and jsonb_array_length(actions) between 1 and 10),
    time_zone text not null
        constraint studio_automations_ck_time_zone check (char_length(time_zone) between 1 and 64),
    active boolean not null default true,
    next_run_at timestamptz,
    author_id bigint not null constraint studio_automations_fk_author references md_users (id),
    revision bigint not null default 1
        constraint studio_automations_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_at timestamptz not null default clock_timestamp(),
    modified_by bigint not null constraint studio_automations_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint studio_automations_ck_due check ((active and trigger_kind = 'scheduled') or next_run_at is null)
);
create index studio_automations_form_idx on studio_automations (form_code, created_at desc, id desc);
create index studio_automations_active_idx on studio_automations (form_code) where active;
create index studio_automations_due_idx on studio_automations (next_run_at) where active;
create index studio_automations_author_idx on studio_automations (author_id);
create index studio_automations_modified_by_idx on studio_automations (modified_by);

-- One run of an automation for one record: queued in the transaction of the change that triggered it (or by the
-- scheduler for a slot, or by a run now), unique per automation and event, so a retried event or slot runs once. The
-- runs of a record execute in the order they were queued; a run applies its actions and its outcome in one
-- transaction. A failure that a retry may fix waits until not_before; the results list what each action did.
create table studio_automation_runs (
    id bigint generated always as identity constraint studio_automation_runs_pkey primary key,
    automation_id bigint not null
        constraint studio_automation_runs_fk_automation references studio_automations (id) on delete cascade,
    event_key text not null
        constraint studio_automation_runs_ck_event_key check (char_length(event_key) between 1 and 100),
    kind text not null constraint studio_automation_runs_ck_kind check (kind in ('event', 'scheduled', 'manual')),
    trigger_kind text not null
        constraint studio_automation_runs_ck_trigger_kind check (
            trigger_kind in ('created', 'updated', 'saved', 'transition', 'deleted', 'scheduled')),
    form_code text not null
        constraint studio_automation_runs_ck_form check (form_code ~ '^studio\.[a-z][a-z0-9_]{0,62}$'),
    record_id bigint not null,
    depth smallint not null default 1 constraint studio_automation_runs_ck_depth check (depth between 1 and 3),
    payload jsonb not null default '{}'::jsonb
        constraint studio_automation_runs_ck_payload check (jsonb_typeof(payload) = 'object'),
    status text not null default 'queued'
        constraint studio_automation_runs_ck_status check (status in ('queued', 'done', 'skipped', 'failed')),
    reason text constraint studio_automation_runs_ck_reason check (reason ~ '^[a-z][a-z_]{0,39}$'),
    error jsonb constraint studio_automation_runs_ck_error check (jsonb_typeof(error) = 'object'),
    results jsonb not null default '[]'::jsonb
        constraint studio_automation_runs_ck_results check (jsonb_typeof(results) = 'array'),
    attempts smallint not null default 0 constraint studio_automation_runs_ck_attempts check (attempts >= 0),
    not_before timestamptz not null default clock_timestamp(),
    created_at timestamptz not null default clock_timestamp(),
    finished_at timestamptz,
    constraint studio_automation_runs_uk_event unique (automation_id, event_key)
);
create index studio_automation_runs_queue_idx on studio_automation_runs (form_code, record_id, id)
    where status = 'queued';
create index studio_automation_runs_due_idx on studio_automation_runs (not_before) where status = 'queued';
create index studio_automation_runs_automation_idx on studio_automation_runs (automation_id, created_at desc, id desc);
create index studio_automation_runs_record_idx on studio_automation_runs (automation_id, record_id, created_at);
create index studio_automation_runs_created_idx on studio_automation_runs (created_at);

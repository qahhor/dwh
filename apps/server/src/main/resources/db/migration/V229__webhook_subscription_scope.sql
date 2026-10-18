set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041, owner decisions of 2026-10-18: a webhook subscription sends either every record of the entity ('all') or
-- only the records its creator may read when the event is written ('creator': an active user holding the view right
-- whose data scope holds the record). The narrower scope is the default; existing subscriptions take it as well.
alter table kwh_subscriptions
    add column scope text not null default 'creator'
        constraint kwh_subscriptions_ck_scope check (scope in ('all', 'creator'));

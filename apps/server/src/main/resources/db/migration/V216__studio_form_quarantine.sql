set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0038: an operator withdraws a stored live form from serving; the immutable declaration and its records stay.
create table studio_form_quarantine (
    id bigint generated always as identity constraint studio_form_quarantine_pkey primary key,
    form_id bigint not null constraint studio_form_quarantine_fk_form references studio_forms (id),
    reason text not null constraint studio_form_quarantine_ck_reason check (char_length(reason) between 1 and 500),
    quarantined_by bigint not null constraint studio_form_quarantine_fk_quarantined_by references md_users (id),
    quarantined_at timestamptz not null default clock_timestamp(),
    constraint studio_form_quarantine_uk_form unique (form_id)
);
create index studio_form_quarantine_quarantined_by_idx on studio_form_quarantine (quarantined_by);

-- Persist worker ownership across the external delivery call. SKIP LOCKED by
-- itself is insufficient because a plain SELECT releases its lock immediately.
alter table ms_notification_outbox
    add column claim_token uuid,
    add column claimed_at timestamptz;

-- Older releases allowed PROCESSING but did not persist worker ownership.
-- Requeue such rows so the new invariant can be added without losing them.
update ms_notification_outbox
set status = 'PENDING',
    next_attempt_at = least(next_attempt_at, now())
where status = 'PROCESSING';

alter table ms_notification_outbox
    add constraint ms_notification_outbox_claim_check check (
        (status = 'PROCESSING' and claim_token is not null and claimed_at is not null)
        or
        (status <> 'PROCESSING' and claim_token is null and claimed_at is null)
    );

create index ms_outbox_recovery_idx
    on ms_notification_outbox (claimed_at)
    where status = 'PROCESSING';

alter table kwh_outbox
    drop constraint kwh_outbox_status_check,
    add constraint kwh_outbox_status_check
        check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    add column claim_token uuid,
    add column claimed_at timestamptz,
    add constraint kwh_outbox_claim_check check (
        (status = 'PROCESSING' and claim_token is not null and claimed_at is not null)
        or
        (status <> 'PROCESSING' and claim_token is null and claimed_at is null)
    );

create index kwh_outbox_recovery_idx
    on kwh_outbox (claimed_at)
    where status = 'PROCESSING';

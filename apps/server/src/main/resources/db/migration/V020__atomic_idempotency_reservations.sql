-- Reserve an idempotency key before executing a mutating request.  Existing
-- rows are completed cache entries; new PENDING rows carry an owner token so
-- that only the request which acquired the reservation can complete/release it.
alter table idempotency_keys
    add column state varchar(16) not null default 'COMPLETED',
    add column reservation_token uuid;

alter table idempotency_keys
    alter column response_status drop not null,
    alter column response_body drop not null;

alter table idempotency_keys
    add constraint idempotency_keys_state_check check (
        (state = 'PENDING'
            and reservation_token is not null
            and response_status is null
            and response_body is null)
        or
        (state = 'COMPLETED'
            and reservation_token is null
            and response_status is not null
            and response_body is not null)
    );

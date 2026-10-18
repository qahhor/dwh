set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0045: a letter of the notification outbox may carry one attachment (the XLSX of a report mailing). It is kept
-- only until the letter is sent or given up: the worker clears it then, so the outbox holds no report data longer than
-- the delivery needs. Nullable columns without a default change no existing row.
alter table ms_notification_outbox
    add column attachment_name text
        constraint ms_notification_outbox_ck_attachment_name check (char_length(attachment_name) between 1 and 200),
    add column attachment_type text
        constraint ms_notification_outbox_ck_attachment_type check (char_length(attachment_type) between 1 and 100),
    add column attachment bytea;

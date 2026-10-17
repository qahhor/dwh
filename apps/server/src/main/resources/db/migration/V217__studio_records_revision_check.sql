set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0024: record revisions stay within the JavaScript safe-integer range, as in V209 and V210.
alter table studio_records
    add constraint studio_records_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1));

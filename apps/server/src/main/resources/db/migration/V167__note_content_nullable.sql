set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 6.2 (plan 10/10, item 5.4): notes are served by the general entity runtime, where a null value of a field
-- that is not required clears it. The text of a note is such a field: it may be empty, so its column takes null; a new
-- note without a text still gets the empty default.
alter table ms_notes alter column content_md drop not null;

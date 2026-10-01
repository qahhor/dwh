set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 5.4 (plan 10/10, item 5.3): notes take the ARCHIVE capability. An archived note keeps its row and still
-- reads by id; the list leaves it out until the archive is asked for. The archive keeps who archived the note and
-- when; the column of the person has its index, as every foreign key does (ADR-0020).
alter table ms_notes
    add column archived_at timestamptz,
    add column archived_by bigint constraint ms_notes_fk_archived_by references md_users (id);
create index ms_notes_archived_by_idx on ms_notes (archived_by);
-- ADR-0026, ADR-0032 5.4: the global search reads notes through the published view, and an archived note leaves the
-- search. The columns stay as they are; the read-only trigger stays on the view.
create or replace view ms_note_pub_notes as
select id, title, content_md, is_pinned, created_by
from ms_notes
where archived_at is null;

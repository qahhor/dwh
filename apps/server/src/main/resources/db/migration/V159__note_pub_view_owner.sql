set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0013, ADR-0026: a note is its owner's alone. The global search reads notes through the published view, so
-- the view publishes the owner and the search shows a person only their own notes. The new column goes last, the
-- only place a replaced view may add one; the read-only trigger stays on the view.
create or replace view ms_note_pub_notes as
select id, title, content_md, is_pinned, created_by
from ms_notes;

set lock_timeout = '2s';
set statement_timeout = '0';
-- destructive: approved
-- reason: two indexes repeat the leading columns of another index of the same table with the same order, so the
--         planner reaches the same rows through the wider one; they only slow down every write. No data is touched.
-- approved_by: product owner (plan 10/10, item 4.6)
-- Plan 10/10, item 4.6. Dropped concurrently, so writes go on meanwhile; Flyway runs this file outside a transaction.
-- security_events (created_at desc) is covered by security_events_created_at_id_idx (created_at desc, id desc);
-- ms_task_files (task_id) is covered by the primary key (task_id, file_id), which also serves its foreign key.
drop index concurrently if exists security_events_time_idx;
drop index concurrently if exists ms_task_files_task_idx;

set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.5: the notification inbox is read page by page, newest first by (created_at, id); this index
-- serves that order, so a page reads its rows and not the whole inbox (the partial index of V001 holds unread rows
-- only). The comments of a task already have ms_task_comments_task_idx. Built concurrently, outside a transaction;
-- an interrupted build leaves an invalid index to remove by hand before the migration runs again
-- (docs/ops/migration-repair.md).
create index concurrently if not exists ms_notifications_user_created_idx
    on ms_notifications (user_id, created_at desc, id desc);

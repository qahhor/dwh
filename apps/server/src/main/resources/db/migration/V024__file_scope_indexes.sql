-- Data-scope file predicates resolve comment attachments from file -> comment.
-- The primary key starts with comment_id, so it cannot serve this direction.
create index if not exists ms_task_comment_files_file_idx
    on ms_task_comment_files (file_id);

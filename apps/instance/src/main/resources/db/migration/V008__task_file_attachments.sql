-- V008: Task file attachments support
create table if not exists ms_task_files (
    task_id bigint not null references ms_tasks(id) on delete cascade,
    file_id uuid not null references mf_files(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (task_id, file_id)
);

create index if not exists ms_task_files_task_idx on ms_task_files (task_id);
create index if not exists ms_task_files_file_idx on ms_task_files (file_id);

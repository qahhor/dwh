set lock_timeout = '2s';
set statement_timeout = '0';
-- ADR-0032, 8 (plan 10/10, item 5.6): the status, the type and the responsible person of a task are filtered and
-- grouped by the list, the kanban and the reports; the responsible person is a foreign key (ADR-0020). Built
-- concurrently, as every index of the large table ms_tasks; Flyway runs this file outside a transaction. An interrupted
-- build leaves an invalid index: remove it by hand and run the migration again (docs/ops/migration-repair.md).
create index concurrently if not exists ms_tasks_status_code_idx on ms_tasks (status_code);
create index concurrently if not exists ms_tasks_type_code_idx on ms_tasks (type_code);
create index concurrently if not exists ms_tasks_responsible_id_idx on ms_tasks (responsible_id);

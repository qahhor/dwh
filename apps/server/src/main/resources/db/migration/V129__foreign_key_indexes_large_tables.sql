set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.7: indexes of the foreign keys of large tables, built concurrently so writes go on meanwhile
-- (ADR-0020, rule 8). Flyway runs this file outside a transaction. If a build is interrupted, PostgreSQL leaves an
-- invalid index behind: remove that index by hand and run the migration again (docs/ops/migration-repair.md).
create index concurrently if not exists idempotency_keys_user_id_idx on idempotency_keys (user_id);
create index concurrently if not exists kwh_logs_subscription_id_idx on kwh_logs (subscription_id);
create index concurrently if not exists kwh_outbox_subscription_id_idx on kwh_outbox (subscription_id);
create index concurrently if not exists md_users_avatar_file_id_idx on md_users (avatar_file_id);
create index concurrently if not exists md_users_created_by_idx on md_users (created_by);
create index concurrently if not exists md_users_manager_id_idx on md_users (manager_id);
create index concurrently if not exists md_users_modified_by_idx on md_users (modified_by);
create index concurrently if not exists ms_task_comments_user_id_idx on ms_task_comments (user_id);
create index concurrently if not exists ms_tasks_created_by_idx on ms_tasks (created_by);
create index concurrently if not exists ms_tasks_modified_by_idx on ms_tasks (modified_by);
create index concurrently if not exists ms_tasks_parent_task_id_idx on ms_tasks (parent_task_id);
create index concurrently if not exists search_generation_delivery_entity_type_entity_id_idx
    on search_generation_delivery (entity_type, entity_id);
create index concurrently if not exists search_index_state_active_generation_id_idx
    on search_index_state (active_generation_id);

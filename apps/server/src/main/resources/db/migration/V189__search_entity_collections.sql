set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: the search finds the entities that declare the SEARCH capability (ADR-0032, 10.3; plan 10/10, item 5.8).
--         An indexed type is the code of an entity (ms.tasks) instead of a fixed name (TASK, PROJECT, USER), and the
--         collections of a generation are rows, one per entity, instead of three columns. No installation holds a
--         search index yet (AGENTS.md): the index state, its generations, jobs and settings start again, and the
--         first rebuild indexes every entity with the capability.
-- approved_by: product owner (plan 10/10, item 5.8)
delete from search_generation_delivery;
delete from search_projection_versions;
update search_index_state set active_generation_id = null, initialized = false, version = version + 1;
delete from search_jobs;
delete from search_generations;
update search_settings set version = 1, configuration = '{}'::jsonb, updated_by = null;

alter table search_projection_versions drop constraint search_projection_versions_entity_type_check;
alter table search_projection_versions
    add constraint search_projection_versions_ck_entity_type
        check (entity_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$');

-- The three framework columns stay (the framework schema is frozen below V100) and are no longer written:
-- an empty default and no uniqueness let a generation leave them out.
alter table search_generations drop constraint search_generations_task_collection_key;
alter table search_generations drop constraint search_generations_project_collection_key;
alter table search_generations drop constraint search_generations_user_collection_key;
alter table search_generations alter column task_collection set default '';
alter table search_generations alter column project_collection set default '';
alter table search_generations alter column user_collection set default '';
alter table search_generations drop constraint search_generations_discovery_entity_check;
alter table search_generations
    add constraint search_generations_ck_discovery_entity
        check (discovery_entity = 'DONE' or discovery_entity ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$');

-- One collection of a generation per entity it indexes; the primary key serves the foreign key to the generation.
create table search_generation_collections (
    generation_id uuid not null
        constraint search_generation_collections_fk_generation references search_generations (id) on delete cascade,
    entity_type   text not null
        constraint search_generation_collections_ck_entity_type
            check (entity_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    collection    text not null
        constraint search_generation_collections_ck_collection check (collection ~ '^[a-z0-9_]{1,128}$'),
    constraint search_generation_collections_pkey primary key (generation_id, entity_type),
    constraint search_generation_collections_uk_collection unique (collection)
);

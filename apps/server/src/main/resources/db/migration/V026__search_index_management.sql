create table search_projection_versions (
  entity_type text not null check (entity_type in ('TASK','PROJECT','USER')),
  entity_id bigint not null check (entity_id > 0),
  revision bigint not null check (revision > 0),
  changed_at timestamptz not null default clock_timestamp(),
  primary key (entity_type, entity_id)
);
create table search_generations (
  id uuid primary key,
  state text not null check (state in ('LEGACY','BUILDING','ACTIVE','RETAINED','FAILED')),
  task_collection text not null unique,
  project_collection text not null unique,
  user_collection text not null unique,
  schema_version integer not null check (schema_version >= 0),
  schema_profile text not null check (schema_profile in ('MIXED','RU')),
  settings_version bigint not null check (settings_version > 0),
  discovery_entity text check (discovery_entity in ('TASK','PROJECT','USER','DONE')),
  discovery_after_id bigint not null default 0 check (discovery_after_id >= 0),
  verified_at timestamptz,
  created_at timestamptz not null default clock_timestamp()
);
create table search_generation_delivery (
  generation_id uuid not null references search_generations(id),
  entity_type text not null,
  entity_id bigint not null,
  delivered_revision bigint not null default 0 check (delivered_revision >= 0),
  attempted_revision bigint not null default 0 check (attempted_revision >= 0),
  attempts integer not null default 0 check (attempts >= 0),
  next_attempt_at timestamptz not null default clock_timestamp(),
  error_code text,
  owner_token uuid,
  delivered_fingerprint text,
  primary key (generation_id, entity_type, entity_id),
  foreign key (entity_type,entity_id) references search_projection_versions(entity_type,entity_id)
);
create table search_index_state (
  id integer primary key check (id=1),
  active_generation_id uuid references search_generations(id),
  version bigint not null default 1 check (version > 0),
  initialized boolean not null default false,
  worker_owner uuid,
  worker_started_at timestamptz
);
insert into search_index_state(id) values(1);
create table search_jobs (
  id uuid primary key,
  request_id uuid not null unique,
  action text not null check (action in ('CHECK','REBUILD','ROLLBACK')),
  generation_id uuid references search_generations(id),
  state text not null check (state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING','SUCCEEDED','FAILED','CANCELLED')),
  actor_id bigint,
  owner_token uuid,
  processed_count bigint not null default 0 check (processed_count >= 0),
  failed_count bigint not null default 0 check (failed_count >= 0),
  verification jsonb,
  error_code text,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  finished_at timestamptz
);
create unique index search_one_mutating_job on search_jobs ((1))
where action in ('REBUILD','ROLLBACK') and state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING');
create index search_delivery_retry on search_generation_delivery(generation_id,next_attempt_at);
create index search_jobs_recent on search_jobs(created_at desc,id);
create table search_settings (
  id integer primary key check (id=1),
  version bigint not null default 1 check (version > 0),
  configuration jsonb not null default '{}'::jsonb check (jsonb_typeof(configuration)='object'),
  updated_by bigint,
  updated_at timestamptz not null default clock_timestamp()
);
insert into search_settings(id) values(1);

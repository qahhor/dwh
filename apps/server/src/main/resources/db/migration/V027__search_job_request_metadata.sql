-- Preserve original request metadata separately from the resolved generation.
-- Historical rows cannot be assumed to have used an implicit target.
alter table search_jobs
  add column requested_generation_id uuid,
  add column retry_of_job_id uuid references search_jobs(id),
  add column request_metadata_recorded boolean not null default false;
alter table search_jobs alter column request_metadata_recorded set default true;

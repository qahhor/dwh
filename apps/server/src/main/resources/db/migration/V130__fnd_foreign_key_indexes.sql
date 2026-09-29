set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.7: indexes of the foreign keys of the fnd module's small tables (ForeignKeyIndexTest); fnd tables
-- are named only in fnd migrations (FndLoadsAccessRuleTest).
create index if not exists fnd_job_queue_schedule_code_idx on fnd_job_queue (schedule_code);
create index if not exists fnd_loads_superseded_by_idx on fnd_loads (superseded_by);
create index if not exists fnd_unit_coefficients_to_unit_idx on fnd_unit_coefficients (to_unit);
create index if not exists fnd_units_base_unit_code_idx on fnd_units (base_unit_code);

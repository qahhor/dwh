set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041, owner decision 1: the org unit of a live form record, read by forms whose data scope is the org unit.
alter table studio_records
    add column org_unit_id bigint constraint studio_records_fk_org_unit references md_org_units (id);

create index studio_records_org_unit_id_idx on studio_records (org_unit_id);

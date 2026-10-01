set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: only the owning-module label of catalog rows changes, from the old module code kwh to webhook; codes of
--         forms, grants and effective rights stay as they are, and the catalog sync at start-up writes the same value.
-- approved_by: product owner (plan 10/10, item 4.3)
-- Plan 10/10, item 4.3: the webhook module is called webhook. The form catalog stores the owning module of a form
-- (ADR-0028) and the role matrix groups forms by it, so the old code is not kept anywhere. Tables keep their kwh_
-- names (ADR-0020).
update md_forms set module = 'webhook' where module = 'kwh';

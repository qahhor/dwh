set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: equality on a string or select custom field is JSON containment of a string (plan 10/10, item 3.7), and
--         the service now stores those values as strings. A number or boolean stored before (123, true) would never
--         be found by that filter; this rewrites such values as the same text ("123", "true"). Values of other
--         field types, objects and arrays stay as they are.
-- approved_by: product owner (plan 10/10 debt review)

update md_users r
set attributes = r.attributes || (
        select jsonb_object_agg(f.code, to_jsonb(r.attributes ->> f.code))
        from md_custom_fields f
        where f.entity_type = 'USER'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'))
where exists (
        select 1
        from md_custom_fields f
        where f.entity_type = 'USER'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'));

update ms_tasks r
set attributes = r.attributes || (
        select jsonb_object_agg(f.code, to_jsonb(r.attributes ->> f.code))
        from md_custom_fields f
        where f.entity_type = 'TASK'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'))
where exists (
        select 1
        from md_custom_fields f
        where f.entity_type = 'TASK'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'));

update ms_task_projects r
set attributes = r.attributes || (
        select jsonb_object_agg(f.code, to_jsonb(r.attributes ->> f.code))
        from md_custom_fields f
        where f.entity_type = 'PROJECT'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'))
where exists (
        select 1
        from md_custom_fields f
        where f.entity_type = 'PROJECT'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'));

update ms_notes r
set attributes = r.attributes || (
        select jsonb_object_agg(f.code, to_jsonb(r.attributes ->> f.code))
        from md_custom_fields f
        where f.entity_type = 'NOTE'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'))
where exists (
        select 1
        from md_custom_fields f
        where f.entity_type = 'NOTE'
          and f.field_type in ('string', 'select')
          and jsonb_typeof(r.attributes -> f.code) in ('number', 'boolean'));

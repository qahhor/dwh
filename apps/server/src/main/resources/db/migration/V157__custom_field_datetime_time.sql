set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: the old field type check is dropped only to be replaced at once by a wider one (datetime and time added);
--         no row is changed and every stored value stays valid.
-- approved_by: product owner (plan 10/10, item 5.0)
-- Plan 10/10, item 5.0: entity fields know a moment (date and time with its offset) and a time of day, so a custom
-- field can hold them too. The check has allowed the six types of V001 since then.
alter table md_custom_fields drop constraint md_custom_fields_field_type_check;
alter table md_custom_fields
    add constraint md_custom_fields_ck_field_type
    check (field_type in ('string', 'number', 'date', 'datetime', 'time', 'boolean', 'select', 'user_ref'));

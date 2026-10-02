set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 10.2 (plan 10/10, item 5.8): a report or a widget is a saved view of a list - data, not code. A view gets
-- its kind: a table view (columns, sort, filter), a report (grouping, measures, filter, chart) or a widget (a report
-- shown on the viewer's dashboard). Only a table view can be the one a list opens with. Existing views are table views.
alter table md_list_views add column kind text not null default 'table'
    constraint md_list_views_ck_kind check (kind in ('table', 'report', 'widget'));

alter table md_list_views add constraint md_list_views_ck_default_table check (kind = 'table' or not is_default);

-- The dashboard reads the viewer's widgets across every list.
create index md_list_views_widget_idx on md_list_views (user_id) where kind = 'widget';

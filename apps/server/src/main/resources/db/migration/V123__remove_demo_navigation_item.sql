set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: V032 засеял демонстрационный пункт меню superset-sales со встраиванием внешнего сайта
--         superset.apache.org; в поставке для разработчиков ему не место. Удаляется только нетронутый пункт.
-- approved_by: владелец продукта (решение 27.09.2026)

delete from md_navigation_items
where code = 'superset-sales'
  and url = 'https://superset.apache.org'
  and not exists (select 1 from md_navigation_items child where child.parent_id = md_navigation_items.id);

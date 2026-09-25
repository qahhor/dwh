set lock_timeout = '2s';
set statement_timeout = '60s';
-- ============================================================================
-- V120: Синонимы заголовка колонки анкеты (роадмап п. 23). Поставщики пишут
-- один заголовок по-разному («Сумма», «Сумма, руб»); колонка принимает любой
-- из перечисленных. Для колонок по позиции синонимы не нужны и не мешают.
-- ============================================================================

alter table upl_format_columns
  add column header_synonyms text[] not null default '{}',
  add constraint upl_format_columns_ck_synonyms check (cardinality(header_synonyms) <= 10);

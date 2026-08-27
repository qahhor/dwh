# Регламент безопасных миграций базы данных (Flyway & PostgreSQL 18)

**Версия:** 1.0
**Дата:** 2026-08-27
**Основание:** ADR-0007 (разд. 2.3: миграции отдельным batch-job и правило expand/contract), ТЗ-01 NFR-10, CONTRIBUTING.md
**Назначение:** обязательное руководство для инженеров по написанию версионируемых SQL-миграций Flyway для PostgreSQL 18.

---

## 1. Общие правила и организация файлов

1. **Именование файлов:** `V{NNN}__{short_description}.sql` (например, `V001__init_schema.sql`, `V012__add_task_priority.sql`). Версии сквозные, трёхзначные с ведущими нулями.
2. **Повторяемые миграции (Repeatable):** `R__{description}.sql` (только для представлений `VIEW`, триггерных функций и процедур).
3. **Один релиз — одна цель:** DDL-структура и DML-сиды разносятся по разным файлам миграций.
4. **Таймауты блокировок:** Каждая DDL-миграция обязана начинаться с настройки таймаута блокировки во избежание захвата эксклюзивных локов в production:
   ```sql
   set lock_timeout = '2s';
   set statement_timeout = '60s';
   ```
5. **Тестирование в CI:** Каждая миграция автоматически валидируется линтером и прогоняется на анонимизированном дампе крупнейшего клиента с замером времени выполнения.

---

## 2. Принцип Expand/Contract (Двухфазные миграции)

Любое нетривиальное изменение схемы, затрагивающее существующие данные и код, обязано выполняться минимум в **два последовательных релиза**:

```
[Релиз N: EXPAND]          ──► [Релиз N: ДЕПЛОЙ КОДА]   ──► [Релиз N+1: CONTRACT]
Добавляем новую колонку/        Код начинает писать/         Удаляем старую колонку/
индекс/таблицу (совместимо      читать по-новому, но          таблицу после полного
со старым кодом)                готов к откату                перехода флота
```

---

## 3. Шаблоны безопасных DDL-операций в PostgreSQL 18

### 3.1. Добавление новой колонки со значением по умолчанию
В PostgreSQL 18 добавление колонки с `DEFAULT` выполняется мгновенно на уровне метаданных без перезаписи всей таблицы.
```sql
-- Релиз N (Expand)
alter table tasks add column priority text not null default 'medium' check (priority in ('low', 'medium', 'high', 'critical'));
```

### 3.2. Удаление колонки (Contract)
Удаление колонки выполняется только после того, как приложение перестало к ней обращаться (минимум через 1 релиз).
```sql
-- Релиз N+1 (Contract)
-- destructive: approved (требует ревью второго инженера)
alter table users drop column if exists legacy_nickname;
```

### 3.3. Создание индексов на работающих таблицах
Создание индекса не должно блокировать запись в таблицу (`CREATE INDEX CONCURRENTLY`). В Flyway для этого миграция помечается как `non-transactional`.
```sql
-- V015__create_tasks_assignee_idx.sql
-- flyway:non-transactional

create index concurrently if not exists tasks_assignee_status_idx on tasks (reporter_id, status_id);
```

### 3.4. Добавление внешнего ключа (Foreign Key) без долгой блокировки
Добавление FK на большую таблицу разбивается на два шага: создание ограничения без проверки существующих строк (`NOT VALID`) и последующая фоновая валидация (`VALIDATE CONSTRAINT`).
```sql
-- Шаг 1: Быстрое добавление (требует лишь кратковременного замка)
alter table task_comments
  add constraint fk_task_comments_task
  foreign key (task_id) references tasks(id)
  not valid;

-- Шаг 2: Валидация данных без эксклюзивной блокировки всей таблицы
alter table task_comments
  validate constraint fk_task_comments_task;
```

### 3.5. Переименование колонки
Прямой `RENAME COLUMN` ломает обратную совместимость и возможность отката canary-деплоя.
**Безопасный алгоритм:**
1. **Релиз N (Expand):** Добавить новую колонку `new_name` (nullable).
2. **Релиз N (Триггер/Код):** Код пишет в обе колонки, читает из `new_name` (с fallback на `old_name`). Фоновый batch копирует старые данные: `UPDATE table SET new_name = old_name WHERE new_name IS NULL`.
3. **Релиз N+1 (Contract):** Удалить `old_name`.

### 3.6. Добавление значения в ENUM / CHECK-ограничение
```sql
-- Для CHECK-констрейнтов:
alter table tasks drop constraint tasks_priority_check;
alter table tasks add constraint tasks_priority_check check (priority in ('low', 'medium', 'high', 'critical', 'urgent'));
```

---

## 4. Шаблон создания новой таблицы с аудитом и служебными колонками

Каждая новая бизнес-таблица обязана следовать стандарту:

```sql
-- V020__create_inventory_items.sql
set lock_timeout = '2s';

create table inventory_items (
    id bigint generated always as identity primary key,
    code text not null unique,
    name text not null,
    quantity int not null default 0 check (quantity >= 0),
    
    -- Служебные метаданные
    created_at timestamptz not null default now(),
    created_by bigint references users(id),
    modified_at timestamptz not null default now(),
    modified_by bigint references users(id)
);

-- Подключение системного триггера аудита изменений (JSONB)
create trigger trg_audit_inventory_items
    after insert or update or delete on inventory_items
    for each row execute function audit_log_trigger();
```

---

## 5. Линтер миграций и деструктивные операции

Следующие операции в миграциях блокируют сборку CI, если в начале файла отсутствует явная пометка согласования:
- `DROP TABLE`, `DROP COLUMN`
- `ALTER TABLE ... ALTER COLUMN ... TYPE ...` (сужение типа)
- `ALTER TABLE ... ADD COLUMN ... NOT NULL` без `DEFAULT`
- `TRUNCATE`

**Формат пометки:**
```sql
-- destructive: approved
-- reason: Удаление устаревшей колонки legacy_status после релиза M2
-- approved_by: @techlead, @db-engineer
```

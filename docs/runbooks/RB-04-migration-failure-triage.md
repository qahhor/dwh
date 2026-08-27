# Runbook RB-04: Диагностика и устранение сбоев миграций Flyway

**Версия:** 1.0
**Основание:** ADR-0007 (разд. 2.3), guidelines/database-migrations.md
**Актор:** Инженер релизного конвейера / Дежурный инженер.

---

## 1. Симптомы и детекция

1. В процессе развёртывания в кольце (R0, R1 или R2) Nomad batch-job `flyway-migration` завершился с ошибкой `Exit Code 1`.
2. Canary-развёртывание группы `app` **автоматически заблокировано** (старые версии приложения продолжают работать благодаря правилу expand/contract).
3. Алерт в Telegram: `[ERROR] MigrationFailed: client-042 (script: V012__add_task_priority.sql)`.

---

## 2. Порядок диагностики

1. Получить логи упавшей миграции из Nomad:
   ```bash
   nomad alloc logs -job flyway-migration-client-042
   ```
2. Типовые причины сбоев:
   - **Lock Timeout (504):** Таблица была заблокирована долгой аналитической транзакцией > 2с (`lock_timeout = '2s'`).
   - **Data Conflict (Duplicate/Null violation):** В таблице есть исторические строки, нарушающие новый `CHECK` или `UNIQUE` констрейнт.
   - **Syntax Error / Schema mismatch:** Ошибка в SQL-скрипте миграции.

---

## 3. Процедура устранения

### Вариант А: Сбой из-за Lock Timeout (Транзиентная ошибка)
1. Убедиться, что в базе нет «зависших» транзакций:
   ```sql
   select pid, now() - query_start as duration, state, query
   from pg_stat_activity
   where state != 'idle' and now() - query_start > interval '10 seconds';
   ```
2. При необходимости завершить блокирующую транзакцию (`SELECT pg_terminate_backend(pid)`).
3. Повторно запустить batch-job миграции:
   ```bash
   nomad job dispatch -meta client_code="client-042" dwh-flyway-migration
   ```

### Вариант Б: Ошибка данных или дефект в SQL-скрипте
1. Поскольку в PostgreSQL 18 DDL транзакционен, упавшая миграция откатилась автоматически (`ROLLBACK`), база осталась в согласованном состоянии версии `V011`.
2. Если таблица `flyway_schema_history` зафиксировала статус `success = false`:
   - Запустить процедуру восстановления состояния истории:
   ```bash
   flyway repair -url=jdbc:postgresql://... -user=... -password=...
   ```
3. Разработчик исправляет миграцию в ветке `hotfix/migration-fix`.
4. Новый релиз проходит сборку в CI и повторно подаётся в кольцо R0.

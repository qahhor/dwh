# Рецепт: схема и миграции — `cms migration diff`

**Эталон:** таблицы `ex_products` и `ex_requests`
(`apps/server/src/main/resources/db/migration/V197__example_products_requests.sql`);
проверка — `EntitySchemaCheck` ([ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md), §7).

## Цель

Объявление и таблица всегда сходятся: недостающая колонка видна до запуска,
а не ошибкой SQL на первом запросе. Платформа сравнивает их при старте
(`EntitySchemaGate`, расхождение — отказ старта) и в сборке
(`EntitySchemaContractTest`); CLI показывает разницу заранее и пишет
недостающий DDL.

## Команда

```bash
cms migration diff                                   # что схема не знает из объявлений
cms migration diff --write example_requests_sync     # то же новой миграцией со следующим номером
cms migration diff --no-libs                         # libs/* уже установлены
```

<!-- docs-contract: hypothetical example_requests_sync -->

CLI запускает `EntitySchemaDiffTest` (Maven, встроенный PostgreSQL): тот
берёт объявления, которые реально исполняет приложение, и схему после всех
миграций. Недостающие таблицы и колонки — DDL по соглашению ADR-0020 и §14.1
ADR-0032; прочие расхождения (тип колонки, `not null` без значения) — список
для правки руками.

## Пример: что сравнивается

<!-- from: apps/server/src/main/resources/db/migration/V197__example_products_requests.sql -->
```sql
create table ex_requests (
    id bigint generated always as identity constraint ex_requests_pkey primary key,
    subject text not null constraint ex_requests_ck_subject check (char_length(subject) between 1 and 255),
    product_id bigint not null constraint ex_requests_fk_product references ex_products (id),
    qty numeric(15, 3) not null constraint ex_requests_ck_qty check (qty > 0),
    status text not null default 'draft'
```

| Поле объявления | Колонка |
|---|---|
| `text`, `textarea`, `select` | `text` |
| `number` | `numeric` или целое |
| `ref` | `bigint` с внешним ключом и индексом |
| `money(...)` | сумма `numeric` и валюта `text` (две колонки) |
| `instant` | `timestamptz` |
| обязательное без умолчания | `not null` допустим; необязательное над `not null` без умолчания — расхождение |

## Тест

`EntitySchemaContractTest` — все объявления против мигрированной базы, 0
расхождений; `MigrationManifestTest` — каждая миграция закреплена в
`apps/server/src/test/resources/migration-manifest.sha256`.

## Подводные камни

- Выпущенная миграция не меняется: исправление — новая миграция. Пока ветка не
  слита, свои новые миграции можно сливать и переписывать (эталон слил
  одиннадцать файлов CLI в `V197` и `V198`), затем перезакрепить в манифесте:
  `mvnw -B -pl apps/server test -Dtest=MigrationManifestTest -Dmigrations.manifest.append=true`.
- DDL и данные — разные файлы (`V197` — таблицы, `V198` — права).
- Колонку, которой нет в объявлении, `migration diff` не удаляет: удаление
  пишется руками.
- Без Maven команда не работает; цена — около двух минут сборки и старта
  контекста.

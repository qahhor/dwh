# Рецепт: связи между сущностями

**Эталоны:** заявка → товар (`example.requests.productId`), товар → его заявки
(вкладка `requests` у `example.products`); несколько ссылок — исполнители
задачи (`MsTaskEntity`).

## Цель

Поле, которое выбирает запись другой сущности; список связанных записей в
карточке; несколько ссылок одного вида.

## Команда

```bash
cms entity add-field example.requests productId --type ref --target example.products --required
```

CLI пишет поле с `.target(код, поле подписи)`, колонку `product_id bigint`
с внешним ключом и индексом. Подпись по умолчанию — `name`
(`--target-label` меняет).

## Объявление

Ссылка называет цель **кодом сущности**:

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java -->
```java
            .field(ref("productId", "example.requests.col.product_id")
                    .column("product_id")
                    .target(ExampleProductsEntity.CODE, "name")
                    .required()
                    .list(sortable()))
```

Обратная сторона — вкладка карточки со связанным списком, который читается с
правами и скоупом самой цели:

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleProductsEntity.java -->
```java
            .tab(EntityTab.sections("main", "ui.entity_page.tab_fields", "main"))
            .tab(EntityTab.related("requests", "nav.example_requests", ExampleRequestsEntity.CODE, "productId"))
            .tab(EntityTab.history("history", "ui.entity_page.tab_history"))
```

Несколько ссылок одного вида — `multiRef(...).link(таблица, владелец, цель)`;
несколько списков в одной таблице связи различает колонка вида:

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/ms/task/service/MsTaskEntity.java -->
```java
        task.field(multiRef("executorIds", "tasks.col.executors", USERS)
                .link("ms_task_members", "task_id", "user_id", "involve_kind", MsTaskPref.INVOLVE_EXECUTOR)
                .list(hidden()));
```

Ссылка не на сущность (оргединица, пользователь из своего API) — источник
выбора `QueryRef.whole(путь, поле)`, как `orgUnitId` у заявок.

## Тест

Кит не придумывает ссылку на обязательное поле — её даёт фикстура (запись
цели вставляется прямо в таблицу):

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleProducts.java -->
```java
    static long insert(JdbcClient jdbc, long author) {
        String code = "p-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbc.sql("insert into ex_products (code, name, unit, created_by, modified_by)"
                        + " values (:code, :name, 'pcs', :author, :author) returning id")
```

Пользователям кита он сам выдаёт `view` на каждую сущность, которую называет
ссылка: новое значение ссылки — запись, которую автор видит. Отказ на
архивную цель (422 `archived`) проверяет
`ExampleRequestsProcessIntegrationTest`.

## Подводные камни

- Значение ссылки вне скоупа автора или без права `view` на цель — 422
  `not_found`; архивная цель — 422 `archived` (старые записи её сохраняют).
- Цель не удаляется, пока на неё ссылаются (внешний ключ): архивируйте.
- Вкладка `related` читает записи цели с правами и скоупом цели (ADR-0032,
  §9.3), а не карточки: зритель видит только те заявки, которые видел бы в их
  собственном списке.
- Ссылка на ту же сущность (родитель) кит не расширяет правами — право у
  пользователя уже есть.

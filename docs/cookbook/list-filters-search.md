# Рецепт: список с фильтрами, видами и поиском

**Эталоны:** `example.products` (`ExampleProductsEntity`), `example.orders`
(`ExampleOrderEntity`).

## Цель

Колонки, сортировка, фильтр, свободный поиск, сохранённые виды, выгрузка и
массовые действия списка — без бина `QueryList` и без кода веба: список
сущности строит `EntityLists` из полей объявления (ADR-0016, ADR-0032 §6).

## Команда

Поле, созданное `cms entity add-field`, сразу попадает в список (тип
определяет флаги по умолчанию); `--no-list` оставляет его только в форме:

```bash
cms entity add-field example.requests resolution --type textarea --no-list
```

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleProductsEntity.java -->
```java
            .field(text("name", "example.products.col.name")
                    .column("name")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            // ...
            .field(instant("modifiedAt", "example.products.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable().hidden()))
            // ...
            .search(EntitySearchSpec.title("name").body("code"))
            .defaultSort("name", Entity.Sort.ASC)
            .capabilities(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK)
```

| Признак | Что даёт |
|---|---|
| `.list(...)` | колонка и фильтр по типу поля; без него поле только в форме |
| `sortable()` | сортировка (`sort=name`, `sort=-name`) |
| `searchable()` | поле участвует в свободном поиске `q` |
| `hidden()` | колонка скрыта по умолчанию, но доступна в настройке колонок и фильтре |
| `.defaultSort(...)` | порядок без `sort` |
| `SAVED_VIEWS`, `EXPORT`, `BULK` | сохранённые виды, выгрузка, массовые действия |
| `.search(EntitySearchSpec...)` | запись находит глобальный поиск (Typesense) в скоупе зрителя |

Запрос списка: `GET /api/v1/entities/example.products?filter=...&sort=-name&q=...&limit=50`;
фильтр — JSON DSL ADR-0016, например
`[{"field":"unit","op":"in","value":["kg","l"]}]`. Ссылка из другого экрана на
отфильтрованный список — `/e/example.products?filter=<условия DSL>`.

## Тест

Кит проверяет список, выгрузку (только колонки зрителя) и, у сущности с
`.search(...)`, находку глобальным поиском в скоупе зрителя и её отсутствие
вне скоупа. Своего теста списка не нужно.

## Подводные камни

- `limit` выше максимума — 422, а не обрезка; курсор (`cursor`) неотделим от
  фильтра и сортировки, с которыми выдан.
- Поле с правом (`requires`) без права исчезает из `query-meta`; фильтр по нему
  — 422, как по неизвестному полю.
- Деньги фильтруются суммой и отдельным скрытым полем валюты
  (`<ключ>Currency`), сумма разных валют в одной колонке не складывается.
- Глобальный поиск индексирует только текстовые поля, перечисленные в
  `EntitySearchSpec`; право на поле в поиске не проверяется, поэтому поле с
  правом туда не включают.

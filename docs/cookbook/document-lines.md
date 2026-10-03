# Рецепт: документ со строками

**Эталон:** `example.orders` — `ExampleOrderEntity`
(`apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleOrderEntity.java`),
таблицы `ex_orders` и `ex_order_lines` (`V181__example_orders.sql`).

## Цель

Шапка документа и его строки в одной форме и одной транзакции: строки читаются
вместе с документом, проверяются построчно (`lines[3].qty`), суммы строк и
итог считает база. Своего экрана и кода веба нет — общий экран рисует таблицу
строк (`smt-entity-lines`).

## Команда

Шапку даёт `cms entity new`; коллекцию строк CLI не генерирует (дочерняя
таблица — решение автора). Дочернюю таблицу пишут миграцией руками, затем
`cms migration diff` проверяет, что объявление и схема сходятся
([migration-diff.md](migration-diff.md)).

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleOrderEntity.java -->
```java
            .field(money("total", "example.orders.col.total", "UZS", "USD", "EUR")
                    .computed(TOTAL)
                    .currencyFrom("currency")
                    .list(sortable()))
            // ...
            .collection(EntityCollection.of(LINES, "example.orders.lines")
                    .table("ex_order_lines", "l")
                    .parentColumn("order_id")
                    .positionColumn("position")
                    // ...
                    .field(money("price", "example.orders.line.price", "UZS", "USD", "EUR")
                            .money("price", null)
                            .currencyFrom("currency")
                            .required()
                            .range(BigDecimal.ZERO, null))
                    .field(money("amount", "example.orders.line.amount", "UZS", "USD", "EUR")
                            .computed("round(l.qty * l.price, 2)")
                            .currencyFrom("currency"))
                    .maxRows(EntityCollection.DEFAULT_MAX_ROWS)
                    .build())
            // ...
            .tab(EntityTab.collection(LINES, "example.orders.lines", LINES))
```

- Строка — таблица `id generated always as identity`, `order_id … on delete cascade`
  с индексом, `position`; своей ревизии, авторов и аудита у строки нет: меняется
  ревизия документа, аудит документа называет изменение строк.
- `.money("price", null).currencyFrom("currency")` — деньги строки в валюте
  шапки: валюта не хранится в строке и не вводится.
- Итог — вычисляемые деньги шапки (`computed(TOTAL)`, подзапрос по строкам).
- `.tab(EntityTab.collection(...))` — вкладка строк в карточке.

## Тест

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleOrderContractTest.java -->
```java
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of(
                        ExampleOrderEntity.LINES,
                        List.of(
                                Map.of("product", "Flour " + context.tag(), "qty", "3", "price", "10.00"),
                                Map.of("product", "Sugar " + context.tag(), "qty", "1.5", "price", "4.20"))))
                .update(Map.of("comment", "changed " + context.tag()));
    }
```

Группа кита «коллекции и процесс» проверяет адресацию ошибок строк, лимит
строк и блокировку строк состоянием. Поведение сверх контракта (итог, валюта
строк, аудит изменений строк, массовый переход) —
`ExampleOrderDocumentIntegrationTest`.

## Подводные камни

- Строка, названная в одном сохранении дважды по `id`, — 422
  `lines[1].id`; строка без `id` — новая, отсутствующая в теле — удаляется.
- Лимит строк — `maxRows` (по умолчанию `EntityCollection.DEFAULT_MAX_ROWS`);
  выше — 422 на коллекции.
- Строки не входят в шаблон импорта: импорт меняет только шапку.
- Регистры накопления платформа не ведёт: остатки модуль пишет в хуке
  перехода (`afterSave` с действием `post`), см. [hooks.md](hooks.md).

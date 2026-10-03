# Рецепт: отчёты и виджеты

**Эталон:** любой список сущности; проверено на заказах
(`EntityReportIntegrationTest` строит отчёты по `example.orders`).

## Цель

Сводка «сколько и на какую сумму» по полям списка — без SQL и без кода
модуля: у каждого списка сущности есть вкладка «Отчёт» (`smt-entity-report`),
отчёт сохраняется видом списка, виджет — тем же видом на панели аналитики
(ADR-0032, §10.2).

## Команда

Не нужна. Чтобы поле можно было группировать или суммировать, ему достаточно
быть в списке (`.list(...)`) с подходящим типом.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleOrderEntity.java -->
```java
            .field(date("orderDate", "example.orders.col.order_date")
                    .column("order_date")
                    .required()
                    .defaultValue(FieldDefault.today())
                    .list(sortable()))
            // ...
            .field(money("total", "example.orders.col.total", "UZS", "USD", "EUR")
                    .computed(TOTAL)
                    .currencyFrom("currency")
                    .list(sortable()))
```

| Что | Из каких полей |
|---|---|
| Группировка (до 2) | выбор и `ENUM`, да/нет, ссылка, дата или момент по `day`/`week`/`month`/`quarter`/`year` |
| Мера (до 4) | `count`; `sum`, `avg`, `min`, `max` по числам и деньгам (деньги сами группируются по валюте) |

Построитель — `GET /api/v1/entities/example.orders/report?groupBy=...&measures=...&filter=...`,
сохранённый — `GET /api/v1/entities/<код>/reports/{viewId}`; вид хранится в
`md_list_views` (`kind` = `report` или `widget`), виджеты зрителя —
`GET /api/v1/report-widgets`.

## Тест

Отчёт соблюдает скоуп, архив и права на поля так же, как список; это
проверяет `EntityReportIntegrationTest` один раз для платформы. Модулю свой
тест отчёта не нужен.

## Подводные камни

- Поле без `.list(...)` в отчёт не попадает; поле с `requires` без права — 422,
  как в фильтре.
- До 1000 групп и 5 с на запрос; момент группируется в UTC.
- Отчёт с особой логикой (несколько сущностей, свои формулы) — не отчёт списка,
  а свой `QueryList` или свой экран (ADR-0016, ADR-0032 §7.2).

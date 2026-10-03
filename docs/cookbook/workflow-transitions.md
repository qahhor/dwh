# Рецепт: переходы процесса

**Эталоны:** `example.orders` (правило перехода, проведение) и
`example.requests` (решение, хук на переходе).

## Цель

У каждого перехода — своё право, при нужде правило («нельзя провести без
строк»), вопрос перед действием и то, что меняется вместе с ним (дата
решения, запись остатков).

## Команда

Отдельной команды нет: переходы объявляются в `.workflow(...)`, их права — в
`.rights(...)` и в миграции прав (`md_form_actions`), см.
[permissions.md](permissions.md).

## Объявление

Правило перехода — тот же `EntityRule`, что у полей:

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleOrderEntity.java -->
```java
                    .transition("post", "draft", "posted")
                    .rule(Rules.hasRows(LINES))
                    .transition("unpost", "posted", "draft")
                    .transition("cancel", "draft", "cancelled")
                    .confirm("example.orders.cancel_confirm")
```

То, что меняет переход сверх статуса, — хук: переход приходит в `beforeSave`
как сохранение с `EntityOperation.ACTION` и кодом перехода:

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsHooks.java -->
```java
    @Override
    public void beforeSave(EntitySave save) {
        if (save.operation() == EntityOperation.ACTION && ExampleRequestsEntity.DECISIONS.contains(save.action())) {
            save.values().set("decidedAt", OffsetDateTime.now(clock).toString());
        }
    }
```

| Что | Как |
|---|---|
| Право перехода | по умолчанию — код перехода (`approve`); другое — `.permission("…")` |
| Правило | `.rule(...)` после `.transition(...)`; нарушение — 422 до записи |
| Вопрос | `.confirm(ключ)`; текст с `{name}` записи |
| Подпись кнопки | ключ `entity.action.<код>` в ru/uz/en |
| Блокировка | `.locks(...)` у состояния, `terminal()` |
| Запись своих данных | `afterSave` с `save.operation() == ACTION` и `save.action()` |
| Массово | `POST /api/v1/entities/<код>/bulk` с `action` — переход по каждой записи из её состояния |

## Тест

Кит проходит каждый переход (см. [document-statuses.md](document-statuses.md)).
Правило и хук — своими тестами: «нельзя провести без строк» —
`ExampleOrderDocumentIntegrationTest.postingNeedsALineAndReachesTheHistory`,
штамп решения — `ExampleRequestsHooksTest` (часы фиксированы) и
`ExampleRequestsProcessIntegrationTest`.

## Подводные камни

- Кит берёт переход без тела запроса: правило перехода должно выполняться на
  записи из фикстуры (у заказов фикстура даёт строки, иначе `post` не пройдёт).
- Хук перехода получает уже новый статус в `save.values()`, а старый — в
  `save.before()`.
- `save.values().set(...)` меняет только записываемое поле формы (не
  вычисляемое и не доп. поле); значение проверяется по типу поля, дата-время —
  строкой ISO-8601.
- Переход поднимает ревизию даже без изменения полей: клиенту нужен новый
  `ETag` из ответа.

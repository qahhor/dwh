# Рецепт: оптимистическая блокировка (`If-Match`)

**Эталон:** каждая сущность общего runtime; свой контроллер — по правилу
[ADR-0024](../adr/ADR-0024-optimistic-locking.md) через `Revisions`.

## Цель

Два пользователя открыли одну запись: сохраняет первый, второй получает 409 и
видит, что запись изменилась, а не затирает чужую правку молча.

## Команда

Не нужна: таблица, созданная `cms entity new`, получает
`revision bigint not null default 1`, runtime сам требует `If-Match`.

## Как работает

| Шаг | Что |
|---|---|
| Чтение | ответ несёт `revision` и заголовок `ETag: "<ревизия>"` |
| Изменение, удаление, переход | `PATCH`/`DELETE`/`POST …/actions/{код}` с `If-Match: "<ревизия>"` |
| Без заголовка | 428 |
| Ревизия устарела | 409; ничего не записано |
| Успех | ревизия + 1, новый `ETag` в ответе |

В тестах эталона это видно прямо: каждый шаг передаёт ревизию, которую дал
предыдущий.

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleRequestsProcessIntegrationTest.java -->
```java
        assertThat(take(clerk, id, "submit", 1).getStatus()).isEqualTo(200);
        MockHttpServletResponse resolved =
                approver.send(patch(REQUESTS + "/" + id).header("If-Match", "\"2\""), Map.of("resolution", "Buy it"));
```

Свой контроллер (не runtime) делает то же руками: ответ — record, реализующий
`Revisioned`; `PUT`/`PATCH` принимает `@RequestHeader(Revisions.IF_MATCH)` и
передаёт `Revisions.required(ifMatch)`; репозиторий пишет
`where id = :id and revision = :expected` и на пустой результат бросает
`Revisions.conflict()`.

## Тест

Кит: изменение без `If-Match` — 428, с устаревшей ревизией — 409, повтор с тем
же `Idempotency-Key` — сохранённый ответ. `ChangesNameTheirRevisionTest`
проверяет, что каждый изменяющий endpoint своего контроллера принимает
ревизию.

## Подводные камни

- Веб передаёт ревизию загруженной записи (`ifMatch` в `ApiService`), а 409 и
  428 показывает `SaveErrorNotifier`; своих диалогов конфликта не нужно.
- Массовое действие ревизии не требует: оно идёт по каждой записи из её
  текущего состояния и сообщает, какие не прошли.
- Изменение строк документа поднимает ревизию документа: у строки своей
  ревизии нет.

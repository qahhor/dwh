# Рецепт: права на поле

**Эталон:** решение заявки `resolution` (`ExampleRequestsEntity`): пишет только
держатель `approve`; пример поля, которое без права не видно вовсе, —
назначения пользователя (`MdUserEntity`).

## Цель

Поле, которое видят все, а меняет только часть пользователей; или поле,
которого без права нет ни в форме, ни в списке, ни в записи, ни в выгрузке
(ADR-0032, §5.2).

## Команда

```bash
cms entity add-field example.requests resolution --type textarea --no-list
```

Право на поле CLI не пишет: строку `.readonlyUnless(...)` или `.requires(...)`
добавляют руками.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java -->
```java
            .field(textarea("resolution", "example.requests.col.resolution")
                    .column("resolution")
                    .length(null, 2000)
                    .readonlyUnless(CODE, "approve")
                    .list(searchable().hidden()))
```

| Построитель | Без права |
|---|---|
| `.readonlyUnless(форма, действие)` | поле видно, в `form-meta` — `readonly: true`; значение в теле — 422 `readonly` |
| `.requires(форма, действие)` | поля нет в `form-meta`, `query-meta`, записи, списке и выгрузке; значение в теле — 422 `unknown_field`; фильтр и колонка выгрузки — 422 |

Право поля может быть действием своей формы (здесь — переход `approve`) или
правом другой формы (`MdUserEntity`: назначения видит держатель
`md.assignments` `view`).

## Тест

Кит строит пользователя без прав полей и проверяет каждое поле с правом:
`readonly` в `form-meta`, 422 на значение без права и 201 у держателя; у поля с
`requires` — его отсутствие в метаданных, записи, списке и выгрузке. Сценарий
целиком (клерк получает 422, утверждающий пишет решение и утверждает) —
`ExampleRequestsProcessIntegrationTest.theApproverDecides`.

## Подводные камни

- Поле с `requires` не включают в глобальный поиск (`EntitySearchSpec`): поиск
  права на поле не проверяет.
- Право поля, совпадающее с действием формы, раньше обходило проверку кита
  (его «пользователь без прав полей» держал все действия формы); теперь кит
  снимает с него именно права полей.
- Состояние процесса блокирует поле независимо от права: в решённой заявке
  `resolution` не меняет и утверждающий.

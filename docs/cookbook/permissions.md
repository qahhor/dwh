# Рецепт: права сущности и действий

**Эталон:** `example.requests` (права CRUD и четырёх переходов),
`V198__example_products_requests_rights.sql`; правило кодов —
[ADR-0028](../adr/ADR-0028-permission-codes.md).

## Цель

Кто видит, создаёт, меняет, удаляет и проводит записи — правами формы
`<область>.<сущность>`, которые администратор выдаёт ролям в матрице прав.
Проверки на сервере делает runtime; `@RequiresPermission` на CRUD не пишется.

## Команда

```bash
cms module new inventory --title "Склад"
cms entity new inventory items --title "Товары"
```

<!-- docs-contract: hypothetical inventory, inventory.items -->

`module new` записывает область права модуля в `PermissionAreas`;
`entity new` — ключи названий прав, миграцию прав (`md_forms`,
`md_form_actions`, выдача системным ролям) и строку модуля в реестре.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java -->
```java
            .rights(
                    "example",
                    "example.requests.rights.form",
                    Map.of(
                            "view", "example.requests.rights.view",
                            "create", "example.requests.rights.create",
                            "update", "example.requests.rights.update",
                            "delete", "example.requests.rights.delete",
                            "submit", "example.requests.rights.submit",
                            "recall", "example.requests.rights.recall",
                            "approve", "example.requests.rights.approve",
                            "reject", "example.requests.rights.reject"))
```

Первый аргумент — модуль-владелец формы, затем ключ названия формы и ключ
названия каждого действия в матрице. Миграция прав:

<!-- from: apps/server/src/main/resources/db/migration/V198__example_products_requests_rights.sql -->
```sql
insert into md_form_actions (form_code, action, name) values
-- ...
('example.requests', 'submit', 'Отправка заявки'),
('example.requests', 'recall', 'Отзыв заявки'),
('example.requests', 'approve', 'Утверждение заявки'),
('example.requests', 'reject', 'Отклонение заявки')
on conflict (form_code, action) do nothing;
```

| Действие | Что разрешает |
|---|---|
| `view` | список, карточку, выгрузку; без него — 404 на всех путях, как у несуществующей сущности |
| `create`, `update`, `delete` | запись; с одним `view` — 403 |
| архив | право `delete` (так у справочника); у стороннего модуля — своё действие `archive` |
| код перехода или действия записи | этот переход или действие |
| `import` | импорт (ещё нужны `create` или `update`) |

## Тест

`EntityActionPermissionContractTest` — у каждой пары «форма, действие»
объявления есть название и строка каталога прав; `PermissionCodesTest` — код
формы по ADR-0028; кит — 404 без `view`, 403 без права действия, `actions` в
`form-meta` и в записи только из прав зрителя.

## Подводные камни

- Новая область — правка `PermissionAreas` и ADR-0028; форма чужой области —
  только опубликованная вашему модулю.
- Действие без строки в `md_form_actions` не попадёт в матрицу ролей: его
  некому выдать.
- Права проверяет только сервер: экран прячет кнопки по `actions`, но это
  удобство, а не защита.
- Право на отдельное поле — [field-rights.md](field-rights.md); чьи записи
  видны — [data-scope.md](data-scope.md).

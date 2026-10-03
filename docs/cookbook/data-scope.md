# Рецепт: скоуп данных

**Эталоны:** справочник — `all()` (`example.products`), документ подразделения
— `orgUnit(...)` (`example.requests`, `example.orders`), личная запись —
`owner(...)` (`library.books`), своё правило — `custom(...)` (проекты,
`MsProjectEntity`). Основание — [ADR-0013](../adr/ADR-0013-data-scope.md),
ADR-0032 §5.1.

## Цель

Право отвечает «что можно делать с сущностью», скоуп — «с какими записями».
Запись вне скоупа для зрителя не существует: 404 на чтение, её нет в списке,
выгрузке, массовом действии, поиске и отчёте.

## Команда

`cms entity new` пишет `.scope(EntityScope.all())` с комментарием о
вариантах; скоуп обязателен — без него объявление не собирается.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java -->
```java
            .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
            // ...
            .field(ref("orgUnitId", "example.requests.col.org_unit", QueryRef.whole("/iam/org-units", "name"))
                    .column("org_unit_id")
                    .defaultValue(FieldDefault.currentOrgUnit())
                    .list(hidden()))
```

| Скоуп | Кто видит запись | Таблица |
|---|---|---|
| `EntityScope.all()` | каждый держатель `view` | — |
| `EntityScope.orgUnit(колонка, автор)` | по правилу роли: `ALL` — все, `SUBTREE`/`UNITS` — записи единиц области зрителя, `SELF` — свои | колонка единицы с внешним ключом на `md_org_units` |
| `EntityScope.owner(колонка)` | только владелец, при любом правиле роли | колонка владельца |
| `EntityScope.custom(имя, провайдер)` | правило модуля (участники проекта), SQL-условие провайдера | по правилу |

Единица новой записи — текущая единица автора (`FieldDefault.currentOrgUnit()`);
выбрать можно только единицу своей области.

## Тест

Кит создаёт владельца в одной единице и постороннего в другой: чужая запись —
404 на каждом пути по id (тот же ответ, что у несуществующей), её нет в
списке, массовом действии и выгрузке. `EntityScopeDeclaredTest` держит
список скоупов всех сущностей на ревью: новый `custom` виден в диффе.

## Подводные камни

- `owner(...)` не расширяется ролью: администратор тоже не видит чужие личные
  записи. Для «своих и подчинённых» — `orgUnit(...)` с правилом роли.
- `custom(...)` — EXPERIMENTAL в API платформы: SQL модуля, его меняют вместе
  с версией API.
- У скоупа `custom` глобальный поиск требует `.scopeUsers(sql участников)` в
  `EntitySearchSpec`.
- Скоуп ссылки проверяется у цели: выбрать можно только запись, которую автор
  видит в её скоупе ([relations.md](relations.md)).

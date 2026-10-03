# Рецепт: справочник

**Эталон:** `example.products` — `ExampleProductsEntity`
(`apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleProductsEntity.java`).

## Цель

Список записей с кодом и названием, который видят все держатели права, который
архивируют вместо удаления, на который ссылаются документы. Сервер — один
файл объявления; экран — общий `/e/example.products`.

## Команда

```bash
cms entity new example products --title "Товары" --title-en Products --title-uz Tovarlar --icon inventory_2
cms entity add-field example.products unit --type select --options pcs,kg,l --required
cms entity add-field example.products price --type money --currencies UZS,USD
```

`entity new` пишет объявление с полями `name`, `code`, `modifiedAt`, скоупом
`all()`, архивом и удалением, тест контракта, миграцию таблицы и миграцию
прав, ключи ru/uz/en. Каждый `add-field` — поле, колонку отдельной миграцией и
ключи. Перед коммитом миграции одной сущности можно слить в два файла (DDL и
данные), пока они не выпущены: эталон так и сделан (`V197`, `V198`).

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleProductsEntity.java -->
```java
            .table("ex_products", "p")
            // Reference data: every row for whoever holds the right (ADR-0032, 5.1).
            .scope(EntityScope.all())
            // ...
            .field(text("code", "example.products.col.code")
                    .column("code")
                    .required()
                    .length(1, 64)
                    .matching(CODE_PATTERN)
                    .readonlyOnUpdate()
                    .list(sortable().searchable()))
            // ...
            .section("main", "entity.section.main", "code", "name", "unit", "price")
            // ...
            .actions("create", "update")
            .archivable()
            .actions("delete")
```

- `.readonlyOnUpdate()` — код задают при создании и больше не меняют: на него
  опираются ссылки, импорт и внешние системы.
- `.archivable()` — колонки `archived_at`/`archived_by`, действие архива,
  фильтр «архив» в списке; архивную запись нельзя выбрать в новой ссылке
  (422 `archived`).
- Уникальность кода — частичным индексом по живым строкам:
  `create unique index ex_products_code_uq on ex_products (code) where archived_at is null`.

## Тест

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleProductsContractTest.java -->
```java
class ExampleProductsContractTest extends EntityContractTestKit {
    // ...
    @Override
    protected String entity() {
        return ExampleProductsEntity.CODE;
    }
}
```

Кит выводит из объявления 47 случаев: CRUD, ревизию, архив, права, скоуп,
правила полей, строгое тело, аудит, выгрузку, события и импорт. Фикстура не
нужна: справочник ни на что не ссылается.

## Подводные камни

- Модуль `example` в поставке выключен: тест включает его
  (`ModuleRegistryService.toggleModuleStatus`), иначе runtime отвечает 404, как
  на неизвестную сущность.
- Индекс по `lower(code)` не годится ключу импорта: `EntityImportDeclaredTest`
  ищет уникальный индекс по самой колонке. Код и так в нижнем регистре по
  проверке, поэтому `cms entity new` пишет индекс по `code`.
- Справочник для поля `ENUM` (код вместо id) объявляется
  `.reference("code", "name")` — так сделаны типы задач (`MsTaskTypeEntity`); для
  ссылки по id (`ref`) он не нужен, см. [relations.md](relations.md).
- Удалить запись, на которую ссылаются, не даст внешний ключ; держите архив
  основным способом «убрать из выбора».

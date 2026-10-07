# Рецепт: модуль вне монорепо

**Эталон:** `examples/external-module` — модуль `library`, сущность
`library.books` (`LibraryModule`, `LibraryBookHooks`); решение —
[ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md).

## Цель

Модуль собирают отдельно от платформы, против публичного API
(`com.smartup24.cms:platform-api`), и подключают jar к готовой установке.
Объявление, хуки и тест-кит — те же, что у встроенного модуля.

## Команда

```bash
cms module new library --external --title "Библиотека" --title-en Library --title-uz Kutubxona
```

Команда создаёт структуру внешнего модуля (`modules/<код>` по умолчанию или `--dir <путь>`):
свой `pom.xml`, манифест с `configuration`, `migrations` и `messages`, стартовую сущность,
миграцию и тесты (`EntityContractTestKit` и проверка границы API).

## Объявление

Манифест — первое, что читает платформа, до создания бинов:

<!-- from: examples/external-module/src/main/resources/META-INF/smartupcms/modules/library.json -->
```json
  "code": "library",
```

Объявление импортирует только `com.smartup24.cms.platform.api..`:

<!-- from: examples/external-module/src/main/java/com/acme/library/LibraryModule.java -->
```java
@Configuration(proxyBeanMethods = false)
public class LibraryModule {
    // ...
    public static final EntityDefinition DEFINITION = Entity.define(BOOKS, BOOKS)
            .table("lib_books", "b")
            .scope(EntityScope.owner("created_by"))
```

| Часть | Где |
|---|---|
| Зависимости | `platform-api` и `spring-context` — `provided`; `platform-testkit` (`<type>pom</type>`) — `test` |
| Манифест | `META-INF/smartupcms/modules/<код>.json`: `code`, `name`, `version`, `minPlatform`, `dependencies`, `configuration`, `migrations`, `messages` |
| Миграции | `db/modules/<код>`, свои номера `V`, история `flyway_module_<код>` |
| Ключи | `META-INF/smartupcms/modules/<код>/i18n/{ru,uz,en}.json` |
| Отказ хука | `EntityRefusal` (сторонний модуль не видит `ApiException`) |

## Тест

<!-- from: examples/external-module/src/test/java/com/acme/library/LibraryBooksContractTest.java -->
```java
class LibraryBooksContractTest extends EntityContractTestKit {
```

Кит стартует платформу с jar модуля на classpath и проходит тот же контракт;
`LibraryModuleBoundaryTest` следит, что исходники импортируют из платформы
только API; `LibraryBookHooksTest` — хук без платформы.

## Подводные камни

- Неизвестное поле манифеста, модуль для более новой или другой MAJOR-версии
  API, отсутствующая зависимость — отказ старта с текстом, который называет
  модуль и версии.
- Ключ перевода, который уже есть у платформы или другого модуля, — отказ
  старта: держите префикс своего модуля.
- Таблицы — по соглашению ADR-0032 §14.1 (`attributes`, `revision`, авторы);
  расхождение с объявлением — отказ старта (`EntitySchemaGate`).
- Доставка jar в образ Docker: каталог `modules/` монтируется в контейнер как `/app/modules:ro`,
  платформа загружает его по пути классов `app.jar:lib/*:/app/modules/*` (точка входа образа — `InstanceApplication`; извлечённый `app.jar` без загрузчика Spring Boot).


# Точки расширения SmartupCMS

**Версия:** 1.1

**Обновлено:** 2026-10-01

**Основание:** [ADR-0016](../adr/ADR-0016-field-registry-query-dsl.md),
[ADR-0019](../adr/ADR-0019-low-code-entity-model.md),
[ADR-0011](../adr/ADR-0011-provider-spi.md) и текущий код.

SmartupCMS расширяется **модулями в коде**: модуль — пакет внутри монолита
(`com.smartup24.cms.instance.<префикс>.<модуль>`) и экран в
`apps/web/src/app/features`. Модуль объявляет Spring-бины из списка ниже, и
платформа строит из них API, экран и служебные функции. Подключаемых во время
работы плагинов (jar/bundle) нет — это сознательное решение модульного монолита
([ADR-0006](../adr/ADR-0006-modular-monolith.md)).

Сокращения путей: `S` — `apps/server/src/main/java/com/smartup24/cms/instance`,
`W` — `apps/web/src/app`.

## 1. Сущность: объявление и записи

| Точка | Где | Что даёт |
|---|---|---|
| `EntityDefinition` (`@Bean`) | `S/common/entity/EntityDefinition.java` | Одно объявление сущности: поля формы и правила (`FormField`: `TEXT`, `TEXTAREA`, `MARKDOWN`, `NUMBER`, `DATE`, `BOOLEAN`, `SELECT`, `REF`), секции, действия с правом каждое, названия права (`EntityRights`), пункт меню (`EntityMenu`), возможности (`EntityCapability`). Отдаётся `GET /api/v1/form-meta/{code}`. |
| `EntityRecords` (`@Bean`) | `S/common/entity/EntityRecords.java` | То, что знает только модуль: видимость записи в скоупе зрителя, страница списка, удаление одной записи. Из него и объявления платформа строит историю, экспорт и `POST /api/v1/entities/{code}/bulk`. |
| `EntityValidator` | `S/common/entity/EntityValidator.java` | Проверка сохранения по объявлению: 422 с ошибкой на каждом поле. Вызывается сервисом модуля. |
| `FormFieldExtender` | `S/common/entity/FormFieldExtender.java` | Поля, добавляемые в форму во время запроса. Реализация — дополнительные поля (`S/md/service/MdCustomFieldFormFields.java`). |

Возможности (`EntityCapability`) и что им нужно:

| Возможность | Требует | Что появляется |
|---|---|---|
| `CUSTOM_FIELDS` | `customEntity` (тип сущности дополнительных полей) | Дополнительные поля администратора в форме, списке, фильтре и экспорте |
| `SAVED_VIEWS` | `listCode` | Сохранённые виды списка |
| `EXPORT` | `listCode`, `EntityRecords.page` | Выгрузка списка в Excel (журнал «Мои выгрузки») |
| `HISTORY` | `auditTable`, `EntityRecords.requireVisible` | Вкладка истории изменений |
| `BULK` | действие `delete`, `EntityRecords.delete` | Удаление выбранных записей |

Объявление, которому не хватает нужного, не даёт приложению стартовать.

## 2. Списки: реестр полей

| Точка | Где | Что даёт |
|---|---|---|
| `QueryList` (`@Bean`) | `S/common/query/QueryList.java` | Список: SQL выборки, поля с типами, фильтрами, сортировкой и поиском, лимиты. Отдаётся `GET /api/v1/query-meta/{code}`; экран фильтрует и сортирует через JSON DSL, включая группы «любое из условий» и поля-ссылки (`QueryRef`). |
| `QueryList.withCustomFields` | там же | Дополнительные поля как поля списка. |
| `QueryListExtender` | `S/common/query/QueryListExtender.java` | Поля списка, добавляемые во время запроса (реализация — дополнительные поля). |
| `QueryListExporter` | `S/common/query/QueryListExporter.java` | Экспорт списка, если он не берётся из объявления (`EXPORT`). |

## 3. Права, меню, языки

| Точка | Где | Что даёт |
|---|---|---|
| `@RequiresPermission(form, action)` | `S/common/annotation/RequiresPermission.java` | Единственный источник существования права: при старте пары попадают в каталог (`S/md/service/MdFormCatalogSynchronizer.java`). |
| `EntityRights` | в объявлении | Названия формы и действий в матрице прав; тест не даёт выпустить пару без названия. |
| `EntityMenu` | в объявлении | Пункт бокового меню: маршрут, подпись, иконка, раздел, порядок, модуль-выключатель. Отдаётся `GET /api/v1/entities/menu` по правам зрителя. |
| Модуль-выключатель | `md_installed_modules`, `S/md/service/ModuleRegistryService.java` | Администратор включает и выключает модуль; экран охраняет `moduleActiveGuard`. |
| Переводы | `apps/server/src/main/resources/i18n/{ru,uz,en}.json` | Ключи модуля (`nav.<код>`, подписи полей). Русский — канонический каталог; другие языки администратор добавляет в редакторе языков. |

## 4. Фоновые задания, события, интеграции

| Точка | Где | Что даёт |
|---|---|---|
| `FndJobHandler` (`@Bean`) | `S/fnd/jobs/FndJobHandler.java` | Задание по расписанию: `code()` и `run(args)`; расписание — строка в `fnd_job_schedule` (пример — `V121__upl_apply_recovery_job.sql`), разовый запуск — `FndJobRunner.enqueueOnce`. Очередь выполняет `S/config/jobs/JobQueueWorker.java`; обработчик работает вне транзакции очереди (свои короткие транзакции открывает сам) и может быть повторён после сбоя — он проверяет состояние, которое меняет. |
| События Spring | например, `S/ms/task/service/MsTaskService.java` → `S/ms/notify/listener/MsTaskNotificationListener.java` | Модули общаются событиями, а не вызовами соседних сервисов. |
| Поиск | `S/search/SearchChangePublisher.java` | `changed(entityType, id)` в транзакции владельца ставит запись на переиндексацию. |
| Вебхуки | `S/kwh/service/KwhWebhookService.java` | `publishEvent(type, payload)` доставляет событие подписчикам с подписью HMAC-SHA256. |
| Провайдеры | `libs/provider-spi` (`StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider`) | Хранилище и каналы доставки; активный провайдер выбирает `S/common/provider/ProviderRegistry.java`. |

## 5. Контракт API и платформенные сервисы

Как это выглядит для клиента — [docs/api/README.md](../api/README.md);
правила для автора модуля — в
[руководстве](../guidelines/module-development-guide.md#серверные-правила).

| Точка | Где | Что даёт |
|---|---|---|
| `ApiException` + `ErrorCode` | `S/common/error/ApiException.java`, `libs/core-types/.../core/error/ErrorCode.java` | Ошибка запроса: код, ключ каталога `error.<модуль>.<имя>`, параметры; `GlobalExceptionHandler` отвечает `application/problem+json` на языке запроса ([ADR-0021](../adr/ADR-0021-error-model.md)). Ключ — в ru, uz и en (`ErrorTextsTest`). |
| `Revisioned` / `Revisions` | `S/common/web/Revisioned.java`, `S/common/web/Revisions.java` | Ответ-record с `revision()` получает `ETag` (`S/config/web/RevisionETagAdvice.java`); `Revisions.required(ifMatch)` — ревизия из `If-Match` или 428, `Revisions.conflict()` — 409 ([ADR-0024](../adr/ADR-0024-optimistic-locking.md)). |
| `Created` | `S/common/web/Created.java` | `Created.at("/api/v1/<путь>/{id}", id, body)` — 201 с `Location` ([ADR-0023](../adr/ADR-0023-uniform-rest.md)). |
| `ApiDeprecations` | `S/common/web/ApiDeprecations.java` | Единый список устаревших путей и параметров: по нему `S/config/web/DeprecatedApiFilter.java` отвечает с `Deprecation`, `Sunset`, `Link`, а описание API помечает их `deprecated` (ADR-0023). Новый псевдоним — строка в `PATHS` или `QUERY_PARAMETERS`. |
| `KeysetPage` | `libs/core-types/.../core/pagination/KeysetPage.java` | Страница коллекции: `items`, `nextCursor`, `hasMore`, `totalEstimated`, `totalExact` (план 10/10, пункт 3.5). |
| `TimePage` | `S/common/query/TimePage.java` | Страница коллекции вне реестра полей по времени и id: `TimePage.of(limit, cursor, default, max)` (422 выше максимума), `page(rows, position)`. |
| `QueryList.withEstimatedTotal()` | `S/common/query/QueryList.java` | Список реестра над таблицей без предела отдаёт оценку планировщика вместо подсчёта (`totalExact: false`, в интерфейсе «≈ N»). |
| `JsonColumns` | `S/common/json/JsonColumns.java` | Чтение и запись `jsonb` с общим `ObjectMapper`; сбой — ошибка с именем таблицы, а не пустой `{}` (план 10/10, пункт 3.11). |
| `RetentionPolicy` (`@Bean`) | `S/common/retention/RetentionPolicy.java` | Срок хранения журнальной таблицы: имя, таблица, условие с `:cutoff`, срок по умолчанию; `S/config/retention/RetentionJob.java` удаляет устаревшие строки ночью, срок меняет `smc.retention.days.<имя>` ([ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md)). |
| Имя кэша | `S/config/cache/CacheConfig.java` | Кэш объявляется константой и в `setCacheNames`; `ClusterCacheManager` рассылает его очистку всем узлам после коммита (`NOTIFY smc_cache`, ADR-0025). Кэш не из списка не создаётся. |

## 6. Интерфейс

| Точка | Где | Что даёт |
|---|---|---|
| `smt-entity-form` | `W/shared/entity/smt-entity-form.component.ts` | Форма по `form-meta`; отдельное поле заменяется шаблоном `smtEntityField`. |
| `smt-entity-card` | `W/shared/entity/smt-entity-card.component.ts` | Просмотр записи по раскладке. |
| `smt-entity-toolbar` | `W/shared/entity/smt-entity-toolbar.component.ts` | Виды, экспорт и удаление выбранных — по возможностям и правам. |
| `ui-server-table` + `registryTableConfig` | `W/shared/ui/ui-server-table.component.ts`, `W/shared/ui/registry-table-config.ts` | Таблица по `query-meta`: колонки, сортировка, фильтр, курсор. |
| UI kit | `W/shared/ui-kit` | Кнопки, диалоги, таблицы, поля (в том числе `smt-dynamic-field`, `smt-data-select`), загрузка файлов. |
| Маршрут экрана | `W/app.routes.ts` | Lazy `loadComponent` с `moduleActiveGuard` и `permissionGuard`. |
| Пункты меню администратора | `md_navigation_items` | Внутренний маршрут, внешняя ссылка или встраивание (`EMBEDDED_IFRAME`, открывается на `/embed/:code`). |

## 7. Известные пробелы

Честный список того, что ещё не является точкой расширения:

1. **Поиск не подключается декларативно.** Коллекции Typesense заданы для
   задач, проектов и пользователей; новой сущности нужен код в модуле `search`.
2. **Вебхуки без источников.** Подписки и доставка работают, но модули пока
   не вызывают `publishEvent`.
3. **Модель сущности внедрена на заметках.** Задачи, проекты и пользователи ещё
   не переведены на `EntityDefinition` и `smt-entity-*`.
4. **Встраивание внешнего приложения — только iframe** из пункта меню, без SSO
   и передачи сессии.
5. **Маршрут экрана добавляется в `app.routes.ts` вручную** — динамической
   регистрации экранов нет.
6. Таблица `md_custom_modules` (прежняя модель «Plugin SDK», V017) выключена
   в V019 и кодом не используется; хранится ради истории.

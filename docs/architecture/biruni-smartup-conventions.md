# Соглашения Biruni/Smartup в SmartupCMS

**Версия:** 2.0

**Обновлено:** 2026-09-03

**Основание:** [каноническое ТЗ](../technical-specification.md),
[ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md), текущие Java-пакеты,
Flyway-миграции и Angular-код.

Сохраняются только соглашения, наблюдаемые в текущем едином приложении.
Новые имена должны описывать предметную область, а не способ размещения или
управления установками.

## Java-пакеты и модули

Базовый пакет сервера — `com.smartup24.cms.instance`. Внутри него предметные
пакеты отражают фактические границы: `md`, `kauth`, `ms.task`, `ms.notify`,
`mf`, `audit`, `kwh`, `search`, `analytics` и `report`; общие runtime-компоненты
располагаются в `common` и `config`.

Используйте существующий модульный префикс там, где он уже является частью
контракта:

- классы HTTP-слоя оканчиваются на `Controller`, например
  `MdUserController` и `MsTaskController`;
- прикладные операции оканчиваются на `Service`, например `MdUserService`;
- доступ к данным оканчивается на `Repository`, например `MfFileRepository`;
- стабильные коды модуля размещаются в классе `*Pref`, например `MfPref`;
- события называют совершившийся факт. Вложенные records в классе-контейнере
  могут не иметь суффикса `Event`, например `MsTaskEvents.TaskAssigned`,
  `TaskStatusChanged` и `TaskCommented`; отдельный top-level тип использует
  суффикс `*Event`, например `MsNotificationCreatedEvent`.

Новый пакет должен находиться внутри функционального модуля. Доступ к
внутренним пакетам другого модуля запрещён; межмодульный вызов оформляется
явным интерфейсом или событием.

## Таблицы и столбцы

Имена таблиц и столбцов используют lowercase `snake_case`. Существующие
предметные семейства таблиц имеют префиксы `md_`, `kauth_`, `ms_`, `mf_` и
`kwh_`; журналы используют явные имена `audit_log` и `security_events`.

- `id` — суррогатный первичный ключ там, где сущности адресуются по числовому
  идентификатору.
- `code` или предметный `*_code` — стабильный машинный код; отображаемое имя
  не заменяет его.
- Аудитные поля сохраняют установленную миграциями семантику:
  `created_at`/`created_by` и, где они предусмотрены конкретной таблицей,
  `modified_at`/`modified_by` либо иной явно названный timestamp события.
- Временные значения хранятся как PostgreSQL `timestamptz` и интерпретируются
  в UTC. Конвертация в локальный часовой пояс выполняется на границе UI.

Не добавляйте универсальный столбец только ради симметрии: имя и наличие поля
должны соответствовать инварианту сущности и новой неизменяемой Flyway-миграции.

## Права

Разрешение является парой `form` + `action` и проверяется серверной аннотацией
`@RequiresPermission`. Код формы — `<область>` или
`<область>.<сущность-или-экран>` в lowercase, и область называет модуль-владельца
([ADR-0028](../adr/ADR-0028-permission-codes.md)): `md.users`, `tasks.items`
(область `tasks` — модуль `ms.task`), `mf.files`, `notes`. Действие — короткий
lowercase `snake_case` глагол, например `view`, `create`, `update`, `delete` или
`manage_tokens`. Контроллер требует формы своего модуля; чужую — только
опубликованную ему владельцем (`PermissionAreas.PUBLISHED`), это проверяет
`PermissionCodesTest`.

Константы форм размещаются в соответствующем `*Pref`, а каталог прав
синхронизируется с аннотациями в коде. Скрытие элемента в Angular не заменяет
серверную проверку права.

## Ошибки API

HTTP API возвращает единый Problem Details контракт (`ProblemDetailRecord` из
`libs/core-types`, `application/problem+json`) по
[ADR-0021](../adr/ADR-0021-error-model.md):

```json
{
  "type": "https://api.dwh.internal/errors/validation_failed",
  "title": "VALIDATION_FAILED",
  "status": 422,
  "code": "validation_failed",
  "detail": "Проверьте поля записи",
  "instance": "/api/v1/example",
  "timestamp": "2026-10-01T00:00:00Z",
  "errors": [
    { "field": "name", "code": "required", "message": "Поле обязательно" }
  ],
  "messageKey": "error.common.record_fields_invalid",
  "params": {}
}
```

Сервер бросает `ApiException` с `ErrorCode`, ключом каталога
`error.<модуль>.<имя>` и параметрами; `detail` он рендерит на языке запроса
(`Accept-Language`), а клиент показывает текст по `messageKey` и `params` из
своего каталога. Ключ есть в ru, uz и en (`ErrorTextsTest`). `type`,
`instance`, `errors`, `messageKey` и `params` могут отсутствовать там, где они
неприменимы; `title`, HTTP `status`, стабильный `code` и безопасный `detail`
сохраняют единый смысл. В ответ нельзя помещать stack trace, SQL или секреты.
Поведение API целиком — [docs/api/README.md](../api/README.md).

## Angular

Исходный корень приложения — `apps/web/src/app`. Функциональные каталоги под
`features/` называют область в kebab-case; компонент использует файл
`<feature>.component.ts`, класс `<Feature>Component`, а тест —
`<feature>.component.spec.ts`. Общие API-модели и сервисы находятся в `core`,
переиспользуемые визуальные компоненты — в `shared`.

Маршрут и название feature должны соответствовать серверному API и коду формы,
если экран защищён разрешением. Клиентская модель ошибок следует Problem Details
контракту сервера.

## Provider SPI

Публичные интерфейсы хранения, сканирования файлов, mail, SMS и messenger
находятся в `libs/provider-spi` под `com.smartup24.cms.spi`. Предметные сервисы
зависят от этих интерфейсов, а выбор и конфигурация реализации выполняются в
runtime-слое сервера. SPI не зависит от реализации или функционального модуля;
provider-specific DTO не должны протекать в предметные API.

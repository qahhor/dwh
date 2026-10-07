# Как ведёт себя API

**Назначение:** правила REST API `/api/v1` для клиента и для автора модуля в
одном месте. Нормативны ADR, на которые ссылается каждый раздел; при
расхождении прав ADR и код.

Описание операций и схем — [`openapi.json`](openapi.json) (раздел 8).

## 1. Ошибки — [ADR-0021](../adr/ADR-0021-error-model.md)

Любой 4xx и 5xx — `application/problem+json` (RFC 9457):

```json
{
  "type": "urn:smartupcms:problem:not_found",
  "title": "NOT_FOUND",
  "status": 404,
  "code": "not_found",
  "detail": "Запись не найдена",
  "instance": "/api/v1/entities/ms.notes/42",
  "timestamp": "2026-10-01T09:00:00Z",
  "messageKey": "error.common.record_not_found"
}
```

- `code` — контракт (`ErrorCode`, в нижнем регистре); по нему клиент решает,
  что делать. `status` совпадает со статусом ответа.
- `type` — `urn:smartupcms:problem:<code>`: идентификатор, а не адрес для
  запроса; клиент не разбирает его и не ветвится по нему (до 2026-10-01 —
  `https://api.dwh.internal/errors/<code>`, ADR-0021 §6).
- `messageKey` и `params` — ключ каталога i18n и значения его
  `{плейсхолдеров}`: клиент рендерит текст сам на текущем языке интерфейса.
- `detail` — тот же текст, собранный сервером на языке запроса: первый язык
  `Accept-Language`, если он активен; иначе язык системы
  (`system.default_language`); иначе русский.
- `errors[]` — ошибки полей (`field`, `code`, `message`), например при 422.
- Непредвиденная ошибка — 500 `internal_error` без подробностей.

## 2. Статусы — [ADR-0023](../adr/ADR-0023-uniform-rest.md)

| Ответ | Когда |
|---|---|
| `200` | чтение, действие, запрос |
| `201` + `Location` | создание ресурса со своим адресом |
| `202` | начатая работа (выгрузка, загрузка пакета) |
| `204` | успех без тела (удаление, сохранение без ответа) |

Один путь на операцию, параметры запроса в camelCase. Переключатель принимает
состояние (`PUT /entities/ms.notes/{id}/archived {"archived": true}`), поэтому
повтор запроса ничего не меняет.

## 3. Постраничное чтение — план 10/10, пункт 3.5

Растущая коллекция читается страницами с keyset-курсором:

```
GET /api/v1/entities/ms.notes?limit=50&cursor=<nextCursor прошлой страницы>
```

```json
{ "items": [...], "nextCursor": "…", "hasMore": true, "totalEstimated": 1234, "totalExact": true }
```

- `limit` — от 1 до максимума коллекции (у списков реестра полей по
  умолчанию 50, максимум 200); вне диапазона — **422**
  (`error.common.query_limit_invalid`, поле `limit`).
- `cursor` непрозрачен: клиент передаёт `nextCursor` как есть; чужой или
  битый — 422 (`error.common.query_cursor_invalid`).
- `totalExact: false` — `totalEstimated` не подсчёт, а оценка планировщика
  (таблица, растущая без границы, `QueryList.withEstimatedTotal()`); интерфейс
  показывает «≈ N».
- Списки реестра полей принимают также `filter` (JSON DSL), `sort` и `q`
  ([ADR-0016](../adr/ADR-0016-field-registry-query-dsl.md)).
- Целиком отдаются только ограниченные справочники; их список с причиной —
  в `CollectionsArePagedTest`.

## 4. Оптимистическая блокировка — [ADR-0024](../adr/ADR-0024-optimistic-locking.md)

- Ответ с записью несёт `revision` в теле и `ETag: "<revision>"`.
- `PUT`/`PATCH` записи передаёт ревизию, из которой сделано изменение:
  `If-Match: "<revision>"`. Сущности общего runtime (`/api/v1/entities/{code}`,
  в том числе задачи и проекты) — только `If-Match`; `expectedRevision` в теле
  осталось у одной операции — сохранения переводов языка
  (`PUT /api/v1/i18n/admin/languages/{code}/translations`).
- Без ревизии — **428** `precondition_required`; неверный формат `If-Match` —
  **422**; ревизия устарела (запись уже изменили) — **409**
  `revision_conflict`. Клиент перечитывает запись и повторяет.
- Установщики состояния (закрепить, включить) ревизию не требуют; их список —
  в `ChangesNameTheirRevisionTest`.
- Системные настройки — одна запись: `GET /settings/system` отдаёт
  `{values, revision}`, `PATCH` называет эту ревизию. Оргединицы пользователя
  и правило скоупа роли сохраняются из ревизии пользователя и роли.
  `PUT /modules/{code}` без `If-Match` только создаёт модуль.

## 5. Идемпотентность

`POST`, `PUT`, `PATCH` и `DELETE` принимают заголовок
`Idempotency-Key: <UUID>`:

- повтор с тем же ключом и тем же запросом возвращает сохранённый ответ
  (статус, тело, `Location`) с заголовком `Idempotent-Replay: true`;
- тот же ключ с другим телом — **409**
  (`error.idempotency_key_payload_mismatch`); ключ, запрос по которому ещё
  выполняется, — 409 (`error.idempotency_request_in_progress`);
- ключ не UUID — 400; тело больше 64 КБ — 413 (у `/api/v1/entities/**` —
  512 КБ, как и лимит тела записи); `multipart` и пути `/api/v1/auth/**` ключ
  не принимают (400);
- ключи хранятся 14 дней (`SMC_IDEMPOTENCY_RETENTION_DAYS`); ответ,
  содержащий секрет, не сохраняется.

## 6. Сущности: общий runtime — [ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6

Каждая сущность, объявленная с таблицей, отвечает на одних и тех же путях
(код сущности — с точкой, `ms.notes`; id — число):

| Метод и путь | Что делает | Право | Ответ |
|---|---|---|---|
| `GET /api/v1/entities/{code}` | страница списка: `q`, `filter`, `sort`, `limit`, `cursor` | `view` | 200 `KeysetPage` записей |
| `GET /api/v1/entities/{code}/{id}` | запись (архивная тоже, `archived: true`) | `view` | 200 + `ETag` |
| `POST /api/v1/entities/{code}` | создание | `create` | 201 + `Location` + `ETag` |
| `PATCH /api/v1/entities/{code}/{id}` | изменение: отсутствующее свойство не меняется, `null` очищает | `update` | 200 + `ETag`; `If-Match` обязателен |
| `DELETE /api/v1/entities/{code}/{id}` | удаление (если объявлено) | `delete` | 204; `If-Match` необязателен |
| `PUT /api/v1/entities/{code}/{id}/archived` | архив и восстановление `{"archived": true}` | `archive` (по умолчанию право `delete`) | 200 + `ETag`; `If-Match` |
| `POST /api/v1/entities/{code}/{id}/actions/{action}` | действие записи | право действия | 200 + `ETag`; `If-Match` |
| `POST /api/v1/entities/{code}/bulk` | массовое удаление и архив | право действия | 200 `BulkResult` |
| `GET /api/v1/entities/{code}/{id}/files/{fileId}` | файл поля записи | `view` | 200 поток |

- Запись — системные свойства (`id`, `revision`, `createdAt`, `createdBy`,
  `modifiedAt`, `modifiedBy`, `archived`), поля, которые зрителю видны по
  правам на поля, `attributes` (доп. поля) и `actions` — что этот зритель может
  сделать с записью.
- Неизвестная сущность, сущность без `view` и сущность выключенного модуля —
  одинаковый **404** `error.common.entity_not_found`; запись вне скоупа —
  **404** `error.common.record_not_found`, как несуществующая.
- Тело — только записываемые поля формы и `attributes`: неизвестное или
  скрытое правом свойство, системное (`id`, `revision`, `createdBy`…),
  `labels`, `actions` — **422** `unknown_field`; значение неверного типа JSON —
  **422** `invalid`; все ошибки полей, ссылок и правил — в одном 422. Ссылка —
  на запись цели, видимую автору (иначе `not_found`), и не архивную (иначе
  `archived`). Тело больше 512 КБ — **413**.
- Дробное число лучше отправлять строкой: так сохраняются все знаки.
- Описание API у каждой сущности своё: пути `/api/v1/entities/<код>…`,
  `operationId` (`listMsNotes`, `createMsNotes`, `patchMsNotes`…), схемы
  `<Код>Record`, `<Код>Create`, `<Код>Patch`, `<Код>Page`; поле, которое
  требует права, помечено `x-requires`.
- Изменение записи публикует событие: подписка на вебхук `<форма>.created`,
  `updated`, `deleted`, `archived`, `restored` или `<действие>` (например,
  `notes.updated`) получает конверт `{id, type, occurredAt, entity, recordId,
  revision, changedFields, data}`; в `data` нет полей, требующих права.

## 7. Устаревшие формы — [ADR-0023](../adr/ADR-0023-uniform-rest.md), §2.5 и §5

Сейчас устаревших форм нет: формы первого релиза (псевдонимы путей вроде
`/tasks/items`, `/rbac`, `/notify`, переключатели `POST …/toggle`,
`POST /modules`, целые списки проектов и участников, snake_case-параметры,
ключ `otp_token`) удалены до релиза, 2026-10-01; полный список — ADR-0023, §5.

До финального релиза формы не устаревают, а меняются сразу: ломающее
изменение с трейлером `Api-Breaking:` и записью в `CHANGELOG.md` (установок у
клиентов нет, `AGENTS.md`, §3). Механизм устаревания остаётся для форм, которые
устареют после финального релиза (ADR-0023, §5): такой путь или параметр
отвечает как раньше ещё один релиз и добавляет заголовки:

```
Deprecation: @<дата устаревания>
Sunset: <дата отключения>
Link: </api/v1/новый/путь>; rel="successor-version"
```

Список форм — `ApiDeprecations.CURRENT` (даты — `ApiDeprecations.DEPRECATED_SINCE`
и `ApiDeprecations.SUNSET`); в описании API у них `deprecated`, `x-sunset`,
`x-successor`. Метрика `smc_api_deprecated_calls_total{alias}` показывает, кто
ещё ими пользуется. `npm run api:audit` (веб и e2e) не пропускает вызов
устаревшей формы и вызов пути, на который не отвечает ни одна операция.

Учётные данные (план 10/10, пункт 4.7): cookie сессии — `SMC_SESSION`, токены
API выдаются и принимаются только с префиксом `smc_`. Старые имена продукта
не принимаются: переходный период отменён 2026-10-01, установок у клиентов нет.

**Ломающее изменение** (удалена операция или поле, сужен тип, удалена
устаревшая форма после `Sunset`) объявляется меткой `api-breaking` у pull
request или трейлером коммита `Api-Breaking: <что и почему>` у прямого пуша,
и в обоих случаях записью в `CHANGELOG.md`
([ADR-0022](../adr/ADR-0022-openapi-from-code.md), §2.4). Без этого CI
(`scripts/api/test-api-contract.ps1`, openapi-diff) не пропускает изменение.

## 8. Откуда `openapi.json` — [ADR-0022](../adr/ADR-0022-openapi-from-code.md)

- Описание генерирует springdoc по контроллерам и DTO (DTO — в пакете `api`
  модуля); `ApiDocsConfig` добавляет схемы входа и ответ `default`
  `application/problem+json`, `EntityOpenApiCustomizer` — пути и схемы каждой
  сущности на runtime из её объявления. Сервер отдаёт его по
  `/api/v1/openapi.json` только вошедшему пользователю (решение владельца
  продукта 2026-10-03: описание называет все сущности и поля установки);
  без входа — 401. Скрипты и проверки читают закоммиченную копию
  `docs/api/openapi.json`, а не эндпоинт.
- `docs/api/openapi.json` — копия в каноническом виде. После изменения
  контроллера или DTO:

  ```
  mvn -B test -pl apps/server -Dtest=OpenApiContractTest -Dopenapi.update=true
  cd apps/web && npm run api:types
  ```

  Первая команда перезаписывает копию, вторая — типы веба
  `src/app/core/api/openapi.d.ts`.
- Проверки: `OpenApiContractTest` (копия совпадает с кодом, каждый обработчик
  описан), `scripts/api/test-api-contract.ps1` (Spectral, свежесть типов,
  совместимость с базовой веткой; у каждой операции есть `summary` и
  `description` из `@Operation` обработчика), `npm run api:audit` в `apps/web`
  и в `e2e` (ни веб, ни тесты e2e не вызывают устаревшие формы; общая логика —
  `scripts/api/api-deprecations.mjs`).

## 9. Вебхуки — [ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6.9

- `GET /api/v1/webhooks/events` — события, которые может назвать подписка
  (`code`, `entity`, `form`, `action`, `nameKey`, `descKey`): `*` (все события),
  `<форма>.created`, `.updated`, `.deleted` каждой сущности на runtime,
  `.archived` и `.restored` архивируемой, коды её собственных действий и
  переходов процесса. Подписка на событие вне каталога — 422
  `error.webhook.event_unknown` с параметром `{event}`.
- Доставка — `POST` на адрес подписки с телом-конвертом события и
  заголовками:

  | Заголовок | Значение |
  |---|---|
  | `X-Signature-Timestamp` | метка времени подписи, секунды Unix |
  | `X-Signature-SHA256` | hex HMAC-SHA256 ключом подписки от строки `<X-Signature-Timestamp>.<сырое тело>` |
  | `X-Event-Type` | имя события (`notes.updated`) |
  | `X-Delivery-Id` | номер доставки; повтор той же доставки несёт тот же номер |

  Получатель пересчитывает подпись по сырому телу, сравнивает её за
  постоянное время и отвергает метку старше допустимого окна (например,
  5 минут): так перехваченную доставку нельзя повторить позже.

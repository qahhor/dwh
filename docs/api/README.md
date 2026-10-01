# Как ведёт себя API

**Назначение:** правила REST API `/api/v1` для клиента и для автора модуля в
одном месте. Нормативны ADR, на которые ссылается каждый раздел; при
расхождении прав ADR и код.

Описание операций и схем — [`openapi.json`](openapi.json) (раздел 7).

## 1. Ошибки — [ADR-0021](../adr/ADR-0021-error-model.md)

Любой 4xx и 5xx — `application/problem+json` (RFC 9457):

```json
{
  "type": "urn:smartupcms:problem:not_found",
  "title": "NOT_FOUND",
  "status": 404,
  "code": "not_found",
  "detail": "Заметка не найдена: 42",
  "instance": "/api/v1/notes/42",
  "timestamp": "2026-10-01T09:00:00Z",
  "messageKey": "error.note.not_found",
  "params": { "id": 42 }
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
состояние (`PUT /notes/{id}/pin {"pinned": true}`), поэтому повтор запроса
ничего не меняет.

## 3. Постраничное чтение — план 10/10, пункт 3.5

Растущая коллекция читается страницами с keyset-курсором:

```
GET /api/v1/notes?limit=50&cursor=<nextCursor прошлой страницы>
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
  `If-Match: "<revision>"` (или `expectedRevision` в теле там, где API его уже
  принимал — задачи).
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
- ключ не UUID — 400; тело больше 64 КБ — 413; `multipart` и пути
  `/api/v1/auth/**` ключ не принимают (400);
- ключи хранятся 14 дней (`SMC_IDEMPOTENCY_RETENTION_DAYS`); ответ,
  содержащий секрет, не сохраняется.

## 6. Устаревшие формы — [ADR-0023](../adr/ADR-0023-uniform-rest.md), §2.5

Старый путь или параметр отвечает как раньше ещё один релиз и добавляет
заголовки:

```
Deprecation: @1790640000
Sunset: Thu, 31 Dec 2026 00:00:00 GMT
Link: </api/v1/новый/путь>; rel="successor-version"
```

Список форм — `ApiDeprecations` (дата — `ApiDeprecations.SUNSET`); в описании
API у них `deprecated`, `x-sunset`, `x-successor`. Метрика
`smc_api_deprecated_calls_total{alias}` показывает, кто ещё ими пользуется.

Учётные данные под старым именем продукта (план 10/10, пункт 4.7) живут до
того же срока: cookie сессии `DWH_SESSION` принимается и в том же ответе
заменяется на `SMC_SESSION`; токены API с префиксом `dwh_` работают, новые
выдаются с префиксом `smc_`. В описании API схема cookie называется
`SMC_SESSION`.

**Ломающее изменение** (удалена операция или поле, сужен тип, удалена
устаревшая форма после `Sunset`) объявляется меткой `api-breaking` у pull
request или трейлером коммита `Api-Breaking: <что и почему>` у прямого пуша,
и в обоих случаях записью в `CHANGELOG.md`
([ADR-0022](../adr/ADR-0022-openapi-from-code.md), §2.4). Без этого CI
(`scripts/api/test-api-contract.ps1`, openapi-diff) не пропускает изменение.

## 7. Откуда `openapi.json` — [ADR-0022](../adr/ADR-0022-openapi-from-code.md)

- Описание генерирует springdoc по контроллерам и DTO (DTO — в пакете `api`
  модуля); `ApiDocsConfig` добавляет схемы входа и ответ `default`
  `application/problem+json`. Сервер отдаёт его по `/api/v1/openapi.json`.
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

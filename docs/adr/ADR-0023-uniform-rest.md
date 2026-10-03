# ADR-0023: Единообразный REST

**Статус:** Принято (2026-09-29); устаревшие формы удалены до релиза (2026-10-01, §5)
**Дата:** 2026-09-29
**Зависит от:** ADR-0022 (описание API из кода), ADR-0018 (асинхронные выгрузки);
план 10/10, пункт 3.4

---

## 1. Контекст

Описание API, которое с пункта 3.3 генерирует код, показало разнобой:

| Что | Как было |
|---|---|
| Пути | одна операция под двумя путями: `/tasks` и `/tasks/items`, `/iam` и `/rbac`, `/notifications` и `/notify`, `/iam/profile/sessions` и `/iam/sessions`, `/auth/password` и `/iam/users/me/password` |
| Параметры запроса | `projectId` и `project_id` в соседних списках; те же snake_case-имена в опциях выгрузок |
| Статусы | 21 создание отвечало 201, заметка — 200; 53 обработчика отвечали 204, а описание говорило 200 |
| Переключатели | `POST …/toggle` и `POST /notes/{id}/pin` меняли состояние на противоположное: повтор запроса (сеть, двойной клик) отменял первый |

## 2. Решение

1. **Один путь на операцию.** Текущие пути: `/api/v1/tasks`, `/api/v1/iam`,
   `/api/v1/notifications`, свои сессии — `/api/v1/iam/profile/sessions`,
   сессии пользователя — `/api/v1/iam/users/{userId}/…`, смена пароля —
   `/api/v1/auth/password`, объявления для читателя — `GET /api/v1/announcements/active`.
2. **Параметры в camelCase:** `projectId`, `statusId`, `tableName`, `userId`, … —
   в запросе и в опциях выгрузки.
3. **Статус говорит, что произошло.** Создание — `201 Created` и `Location` с
   путём ресурса, если у него есть свой адрес (у комментария задачи его нет —
   только 201). Начатая работа — `202 Accepted` (выгрузка, загрузка пакета,
   привязка канала до подтверждения кода). Успех без тела — `204 No Content`.
   `200` — чтение, действия и запросы. Обработчик, который отвечает 201, 202 или
   204, объявляет это `@ResponseStatus`, иначе описание API солжёт.
   Повтор создания под тем же `Idempotency-Key` возвращает тот же `Location`
   (колонка `idempotency_keys.response_location`, V133).
4. **Переключатель принимает состояние:** `PUT /notes/{id}/pin {pinned}`,
   `PUT /modules/{code}/enabled {enabled}`, `PUT /navigation/items/{id}/active {active}`.
   Регистрация модуля, которая всегда заменяла существующую, — `PUT /modules/{code}`.
5. **Старые формы живут один релиз.** *(До финального релиза не действует:
   установок у клиентов нет, формы удалены сразу — §5. Пункт описывает
   механизм `ApiDeprecations` для форм, которые устареют после финального
   релиза.)* Сервер отвечает на них как раньше и
   добавляет `Deprecation: @1790640000` (RFC 9745, 2026-09-29), `Sunset: Thu,
   31 Dec 2026 00:00:00 GMT` (RFC 8594) и, для пути, `Link: <новый путь>;
   rel="successor-version"`. snake_case-параметр доходит до обработчика под
   camelCase-именем; в опциях выгрузки — тоже, в том числе у выгрузок,
   поставленных до релиза. Счётчик `smc_api_deprecated_calls_total{alias}`
   показывает, кто ещё пользуется старой формой. Список форм — один,
   `ApiDeprecations`: по нему отвечает фильтр и строится описание API
   (`deprecated`, `x-sunset`, `x-successor`, устаревшие параметры рядом с новыми).
   Дата `Sunset` — предположение: следующий релиз после этого; её сдвигают в
   `ApiDeprecations.SUNSET`, если релиз сдвинется.

## 3. Проверки

- Spectral (`.spectral.yaml`, ошибки валят CI): сегменты путей в kebab-case,
  переменные и параметры запроса в camelCase, `POST` отвечает 201, 202 или 204
  (200 — только для действий из списка в правиле), нет текущих `…/toggle`, у
  устаревшей операции есть `x-sunset` и `x-successor`.
- `ResponseStatusDeclaredTest` — объявленный статус совпадает с тем, что
  обработчик возвращает.
- `DeprecatedApiFilterTest` — заголовки, выбор более точного псевдонима,
  переименование параметров, текущая форма не трогается (на тестовой таблице,
  пока `ApiDeprecations.CURRENT` пуста, §5); `ApiDocsDeprecationsTest` — то же
  для описания API.
- `npm run api:audit` (веб и e2e, CI) — ни один вызов `ApiService`/`HttpClient` не
  идёт по устаревшей операции, не передаёт устаревший параметр и не идёт по
  пути, на который не отвечает ни одна операция (список берётся из
  `docs/api/openapi.json`).
- openapi-diff: исправленные статусы — ломающее изменение описания; оно
  объявлено трейлером коммита `Api-Breaking:` и в `CHANGELOG.md`. Трейлер
  появился в этом же пункте: у прямого пуша в main нет метки pull request.

## 4. Последствия

- После `Sunset` старые формы удаляются (до финального релиза — сразу,
  без `Sunset`, §5): пути из `@RequestMapping`, записи из
  `ApiDeprecations`, методы `toggle*` сервисов. Удаление — ломающее изменение:
  метка `api-breaking` у pull request или трейлер `Api-Breaking:` у прямого
  пуша, и запись в `CHANGELOG.md` — то же правило, что в
  [ADR-0022](ADR-0022-openapi-from-code.md), §2.4. Перед удалением
  `smc_api_deprecated_calls_total` должен стоять на месте.
- Адрес экрана веба `/tasks?project_id=…` — не API и не менялся.
- Атомарность переключателей под конкурентной нагрузкой (`update … returning`)
  — пункт 3.6.

## 5. Устаревшие формы удалены до релиза (2026-10-01)

Владелец продукта решил (2026-10-01): установок у клиентов до финального
релиза нет, поэтому слои совместимости не нужны (`AGENTS.md`, §3). Формы из
п. 2.5 удалены сразу, не дожидаясь `Sunset`:

| Удалено | Текущая форма |
|---|---|
| `/api/v1/tasks/items/**` | `/api/v1/tasks/**` |
| `/api/v1/rbac/**` | `/api/v1/iam/**` |
| `/api/v1/notify/**` | `/api/v1/notifications/**` |
| `/api/v1/iam/sessions/**` | `/api/v1/iam/profile/sessions/**` |
| `/api/v1/iam/profile/sessions/users/{userId}/…` | `/api/v1/iam/users/{userId}/…` (`sessions`, `security`, `force-password-change`, `reset-2fa`) |
| `POST /api/v1/iam/users/me/password` | `POST /api/v1/auth/password` |
| `GET /api/v1/announcements` | `GET /api/v1/announcements/active` |
| `POST /api/v1/notes/{id}/pin` | `PUT /api/v1/notes/{id}/pin {pinned}` |
| `POST /api/v1/modules/{code}/toggle` | `PUT /api/v1/modules/{code}/enabled {enabled}` |
| `POST /api/v1/modules` (поле `code` в теле) | `PUT /api/v1/modules/{code}` (`If-Match` для замены) |
| `POST /api/v1/navigation/items/{id}/toggle` | `PUT /api/v1/navigation/items/{id}/active {active}` |
| `GET /api/v1/tasks/projects` (весь список) | `GET /api/v1/tasks/projects/page` |
| `GET /api/v1/tasks/projects/stats` | счётчики в строках `GET /api/v1/tasks/projects/page` |
| `GET /api/v1/tasks/projects/{id}/members` (весь список) | `GET /api/v1/tasks/projects/{id}/members/page` |
| snake_case-параметры запроса (`project_id`, `status_id`, `table_name`, `user_id`, … — 14 имён) | camelCase-имена |
| переименование snake_case-опций выгрузки, в том числе у сохранённых заданий | опции только в camelCase |
| ключ `otp_token` в ответе входа | `otpToken` |

- Механизм остаётся для форм, которые устареют после релиза: `ApiDeprecations`
  (таблица `CURRENT` пуста), `DeprecatedApiFilter`, отметки описания API,
  правила Spectral и `npm run api:audit` в вебе и e2e работают с пустой
  таблицей; их тесты проверяют механизм на тестовой таблице.
- `CollectionsArePagedTest` больше не знает устаревших целых списков.
- Удаление — ломающее изменение: трейлер `Api-Breaking:` и запись в
  `CHANGELOG.md` (§4). Счётчик `smc_api_deprecated_calls_total` перед
  удалением не проверялся: клиентов, которые могли бы его наращивать, нет.
- Совместимость имён конфигурации (ADR-0027, §4), кодов прав (ADR-0028, §5),
  псевдонима шага миграции (ADR-0030, §5), старого имени cookie сессии и
  префикса токенов (план 10/10, п. 4.7) удалена тем же решением 2026-10-01.

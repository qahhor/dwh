# ТЗ-04: API-спецификация ядра (вехи M1–M2)

**Версия:** 1.0
**Дата:** 2026-08-27
**Статус:** **Утверждено CEO (2026-08-27)**
**Охват:** аутентификация, профиль, пользователи, роли/права, токены — всё, что нужно вехам
M1–M2. Блоки M3+ (уведомления, задачник, CP) добавляются версиями 0.2/0.3 по мере приближения вех.
**Назначение:** прямое задание для контроллеров. Реализация обязана совпасть со спекой;
расхождение — дефект спеки или кода, решается ревью, а не молча.

---

## 1. Конвенции (обязательные, FR-API-1…5)

| Аспект | Правило |
|---|---|
| База | `/api/v1`, JSON UTF-8, время — ISO-8601 UTC (`2026-08-27T09:30:00Z`) |
| Аутентификация | Cookie-сессия + заголовок `X-CSRF-Token` на мутирующих; либо `Authorization: Bearer <api-token>` (без CSRF, FR-SEC-1) |
| Права | Каждый непубличный эндпоинт объявляет `(form, action)` — колонка в таблицах ниже |
| Ошибки | RFC 9457 `application/problem+json`: `{type, title, status, code, detail?, errors?[{field, code}]}`; `code` — машинный ключ из разд. 6 |
| Идемпотентность | Мутирующие POST принимают `Idempotency-Key` (UUID); повтор → тот же ответ, без повторного эффекта |
| Пагинация | Keyset: `?limit=50&after=<cursor>` → `{items[], next?}`; сортировка `?sort=-created_at,name` |
| Лимиты | Превышение → `429` + `Retry-After`; см. ADR-0008 разд. 2.2 |
| Фоновые операции | Долгая операция → `202 {operation_id}`; статус — `GET /operations/{id}` |

## 2. Каталог форм и действий (M1–M2)

Идентификатор формы: `<модуль>.<форма>`; действия — snake_case. Каталог генерируется из кода
(FR-PERM-1); эта таблица — контракт имён, код обязан ей следовать.

| Форма | Действия | В матрице ролей разд. 4.4.1 |
|---|---|---|
| `iam.profile` | `view, update, change_password, manage_channels, manage_tokens` | все роли |
| `iam.users` | `view, create, update, block, unblock, invite` | admin: все; manager/auditor: view |
| `iam.sessions` | `view, close` (чужие сессии/токены) | admin: все; auditor: view |
| `rbac.roles` | `view, create, update, delete, grant` | admin: все; auditor: view |
| `rbac.assignments` | `view, assign` (роли и персональные права пользователей) | admin: все; auditor: view |
| `audit.log` | `view` | admin, auditor |

## 3. Аутентификация (публичные, без формы; сценарии F-01…F-03)

| Эндпоинт | Вход | Успех | Ошибки (разд. 6) |
|---|---|---|---|
| `POST /auth/login` | `{login, password}` | `200 {step:"done"}` + cookie **или** `200 {step:"otp", otp_token}` | 401 `invalid_credentials` · 423 `login_locked` · 429 |
| `POST /auth/otp` | `{otp_token, code}` | `200 {step:"done"}` + cookie | 401 `otp_invalid` / `otp_expired` · 423 `otp_attempts_exceeded` |
| `POST /auth/otp/resend` | `{otp_token}` | `204` | 429 `otp_rate_limited` |
| `POST /auth/logout` | — (аутентиф.) | `204`, cookie погашен | — |
| `POST /auth/password-reset/request` | `{email}` | **всегда `204`** (не раскрывает существование email) | 429 |
| `POST /auth/password-reset/confirm` | `{code, new_password}` | `204` | 400 `reset_code_invalid` / `reset_code_expired` · 422 `password_policy` |
| `POST /invitations/{code}/accept` | `{password}` | `204` | 400 `invite_invalid` / `invite_expired` · 422 `password_policy` |
| `GET /auth/me` | — (аутентиф.) | `200 {user, permissions[], permissions_version, instance:{name, languages}}` — bootstrap SPA | 401 |

Правило F-01: тексты 401 не различают «нет логина» и «неверный пароль»; блокировка учётки
раскрывается только после верного пароля.

## 4. Эндпоинты по формам

### 4.1. `iam.profile` — свой профиль (F-02)

| Эндпоинт | Действие | Вход → Выход |
|---|---|---|
| `GET /profile` | `view` | → `{id, name, email, phone, language, timezone, avatar_url, channels[]}` |
| `PATCH /profile` | `update` | `{name?, language?, timezone?}` → `200 profile` |
| `PUT /profile/avatar` | `update` | multipart → `200 {avatar_url}` (FR-FILE-3 проверки) |
| `POST /profile/password` | `change_password` | `{current_password, new_password}` → `204` · 403 `current_password_invalid` · 422 `password_policy` |
| `GET /profile/sessions` | `view` | → `{items:[{id, ip, device, created_at, last_seen, current}]}` |
| `DELETE /profile/sessions/{id}` | `view` | → `204` (свою текущую — 409 `cannot_close_current`) |
| `POST /profile/channels/telegram` | `manage_channels` | → `200 {deep_link, expires_at}` (F-02) |
| `DELETE /profile/channels/telegram` | `manage_channels` | → `204` · 409 `last_2fa_channel` если 2FA обязательна |
| `GET /profile/tokens` | `manage_tokens` | → список своих API-токенов (без значений) |
| `POST /profile/tokens` | `manage_tokens` | `{name, expires_at?}` → `201 {id, token}` — **значение показывается один раз** |
| `DELETE /profile/tokens/{id}` | `manage_tokens` | → `204` |

### 4.2. `iam.users` — пользователи (F-03, F-08)

| Эндпоинт | Действие | Вход → Выход |
|---|---|---|
| `GET /users` | `view` | `?query&state&role_id&limit&after&sort` → `{items[], next}`; колонки — по правам (FR-PERM-11) |
| `POST /users` | `create` | `{name, email, login?, phone?, role_ids[]}` → `201 user` + отправка приглашения · 409 `email_taken` / `login_taken` |
| `GET /users/{id}` | `view` | → `user` + статус приглашения |
| `PATCH /users/{id}` | `update` | частичное → `200 user` |
| `POST /users/{id}/block` | `block` | → `200 {closed_sessions, revoked_tokens, open_tasks}` — атомарно (I-U1); ответ содержит «хвосты» для F-08 · 409 `last_admin` |
| `POST /users/{id}/unblock` | `unblock` | → `200 user` |
| `POST /users/{id}/invite` | `invite` | повторное приглашение (старый код аннулируется) → `204` |

### 4.3. `iam.sessions` — чужие сессии и токены (админ)

| Эндпоинт | Действие | Выход |
|---|---|---|
| `GET /users/{id}/sessions` | `view` | список сессий пользователя |
| `DELETE /users/{id}/sessions/{sid}` | `close` | `204` |
| `GET /tokens` | `view` | все API-токены экземпляра (владелец, last_used) |
| `DELETE /tokens/{id}` | `close` | `204` — отзыв (F-07 ветка) |

### 4.4. `rbac.roles` — роли и их права (F-04)

| Эндпоинт | Действие | Вход → Выход |
|---|---|---|
| `GET /roles` | `view` | → `{items:[{id, name, pcode?, state, members_count}]}` |
| `POST /roles` | `create` | `{name}` → `201 role` · 409 `name_taken` |
| `PATCH /roles/{id}` | `update` | `{name?, state?, order?}` → `200` · 409 `system_role_immutable` (pcode, I-P4) |
| `DELETE /roles/{id}` | `delete` | → `204` · 409 `system_role_immutable` / `role_in_use {members_count}` |
| `GET /roles/{id}/permissions` | `view` | → `{grants:[{form, action}]}` |
| `PUT /roles/{id}/permissions` | `grant` | `{grants[]}` — полная замена → `200` **или** `202 {operation_id}` при объёме > порога (FR-PERM-7) · 409 `auditor_mutation_denied` · 422 `unknown_form_action` |
| `GET /forms` | `view` | каталог форм/действий для матрицы прав (дерево по модулям) |

### 4.5. `rbac.assignments` — назначения (F-04)

| Эндпоинт | Действие | Вход → Выход |
|---|---|---|
| `PUT /users/{id}/roles` | `assign` | `{role_ids[]}` — полная замена → `200 {permissions_version}` · 409 `last_admin` |
| `PUT /users/{id}/permissions` | `assign` | `{grants[]}` персональные → `200 {permissions_version}` |
| `GET /users/{id}/effective-permissions` | `view` | → `{items:[{form, action, source:"role:Кладовщик"|"personal"}]}` — экран «глазами пользователя» |
| `GET /operations/{id}` | — (владелец операции) | → `{status:"running"|"done"|"failed", progress:{done, total}}` — фоновый пересчёт |

## 5. Точные результаты ТЗ-04

| Результат | Проверка |
|---|---|
| Каждый FR блоков USR/AUTH/PERM (M1–M2, приоритет M) покрыт эндпоинтом этой спеки | Трассировка при ревью ТЗ (№5 плана улучшений) |
| Каждый непубличный эндпоинт имеет `(form, action)` из каталога разд. 2, согласованного с матрицей ролей разд. 4.4.1 ТЗ-01 | Сверка таблиц (ревью) → в коде архитектурный тест FR-PERM-8 |
| Сценарии F-01…F-04, F-08 проходимы по этой спеке шаг в шаг, включая ошибочные ветки | Прогон flows по таблицам (ревью) |
| OpenAPI, сгенерированный из кода, совпадает со спекой по маршрутам и кодам ошибок | CI-сверка (M2) |

## 6. Каталог машинных кодов ошибок (M1–M2)

`invalid_credentials` · `login_locked` · `otp_invalid` · `otp_expired` · `otp_attempts_exceeded` ·
`otp_rate_limited` · `reset_code_invalid` · `reset_code_expired` · `invite_invalid` ·
`invite_expired` · `password_policy` · `current_password_invalid` · `email_taken` · `login_taken` ·
`name_taken` · `last_admin` · `last_2fa_channel` · `cannot_close_current` ·
`system_role_immutable` · `role_in_use` · `auditor_mutation_denied` · `unknown_form_action` ·
`permission_denied` (403, в `detail` — недостающая пара form/action) · `validation_failed` ·
`not_found` · `conflict` · `rate_limited` · `csrf_token_invalid`

Каждый код — ключ i18n (FR-I18N-1); фронтенд не парсит человекочитаемые тексты.

## 7. Открытые вопросы

1. `GET /auth/me` отдаёт полный список прав (может быть 2–3 тыс. пар) — либо целиком при
   bootstrap + инвалидация по `permissions_version` (предлагаю это), либо ленивые проверки.
   Зафиксировать при реализации M2 замером размера ответа.
2. Версионирование каталога ошибок: добавление кода — не ломающее изменение; удаление/переименование — ломающее (мажор API). Принять как правило.

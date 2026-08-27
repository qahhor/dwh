# ТЗ-04: API-спецификация ядра платформы (Этап 1: M1–M4, Control Plane)

**Версия:** 2.0
**Дата:** 2026-08-27
**Статус:** **Утверждено к реализации (Этап 1)**
**Основание:** ТЗ-01 v1.0, ТЗ-02 v1.0, ТЗ-03 v1.0, ADR-0001…0012
**Охват:** полная спецификация REST API и SSE-событий для всех модулей Этапа 1 (`iam`, `rbac`, `audit`, `notify`, `tasks`, `files`, `platform`, а также протокол `Instance ↔ Control Plane`).
**Назначение:** прямой контракт для Backend-контроллеров и Frontend-клиента. Реализация обязана строго соответствовать спецификации; любое расхождение — дефект кода или спеки, устраняемый через PR и ревью.

---

## 1. Конвенции и сквозные правила (FR-API-1…5, ADR-0008)

| Аспект | Правило |
|---|---|
| **Базовый путь** | `/api/v1`, кодировка JSON UTF-8, формат времени — ISO-8601 UTC (`2026-08-27T09:30:00Z`). |
| **Аутентификация (SPA)** | Cookie сессии (`HttpOnly, Secure, SameSite=Lax`) + обязательный заголовок `X-CSRF-Token` для всех мутирующих запросов (`POST, PUT, PATCH, DELETE`). |
| **Аутентификация (API)** | Заголовок `Authorization: Bearer <api-token>`. Освобождён от CSRF-проверки (FR-SEC-1), вызовы помечаются в аудите признаком `is_api=true`. |
| **Декларация прав** | Каждый непубличный эндпоинт объявляет пару `(form, action)`. Эндпоинты без аннотации `@RequiresPermission` не проходят CI (FR-PERM-8). |
| **Формат ошибок** | RFC 9457 `application/problem+json`: `{type, title, status, code, detail?, errors?[{field, code}]}`. Поле `code` — машинный ключ из разд. 8. |
| **Идемпотентность** | Все мутирующие `POST`-запросы принимают заголовок `Idempotency-Key: <UUID>` (FR-API-3). Повторный запрос с тем же ключом возвращает сохранённый ответ без повторного сайд-эффекта. |
| **Пагинация** | Keyset-пагинация для списков: `?limit=50&after=<cursor>` → `{items: [...], next: "<cursor>"}`. Сортировка: `?sort=-created_at,name`. |
| **Лимиты частоты** | Ограничение Bucket4j (ADR-0008 разд. 2.2). При превышении → `429 Too Many Requests` с заголовком `Retry-After: <sec>`. |
| **Фоновые операции** | Длительные операции (например, массовый пересчёт прав) возвращают `202 Accepted` с телом `{operation_id: "<UUID>"}`; опрос статуса — `GET /api/v1/operations/{id}`. |
| **Трассировка** | Приём заголовка `traceparent` (W3C Trace Context); сквозная проброска в логи и SQL-комментарий `/*traceparent=...*/` (FR-OBS-3). |

---

## 2. Каталог форм и действий (Каталог прав RBAC)

Идентификатор формы: `<модуль>.<форма>`; действия — `snake_case`. Каталог регистрируется из кода при старте приложения (`@RequiresPermission`, FR-PERM-1). Код обязан следовать именованию из этой таблицы.

| Форма | Действия | Описание | Матрица системных ролей |
|---|---|---|---|
| `iam.profile` | `view, update, change_password, manage_channels, manage_tokens` | Управление собственным профилем | Все роли |
| `iam.users` | `view, create, update, block, unblock, invite` | Управление пользователями экземпляра | `admin`: все; `manager, auditor`: `view` |
| `iam.sessions` | `view, close` | Просмотр и отзыв чужих сессий и токенов | `admin`: все; `auditor`: `view` |
| `rbac.roles` | `view, create, update, delete, grant` | Управление ролями и их правами | `admin`: все; `auditor`: `view` |
| `rbac.assignments`| `view, assign` | Назначение ролей и персональных прав | `admin`: все; `auditor`: `view` |
| `audit.log` | `view` | Просмотр системного аудита и security-событий | `admin, auditor`: `view` |
| `notify.inbox` | `view, read, read_all, delete` | Личные уведомления пользователя | Все роли |
| `notify.preferences` | `view, update` | Настройка каналов доставки по типам событий | Все роли |
| `notify.dead_letter` | `view, retry, discard` | Разбор сбойных очередей доставки | `admin`: все |
| `platform.announcements` | `view, read` | Глобальные и локальные объявления | Все роли |
| `platform.settings` | `view, update` | Настройки экземпляра | `admin`: все; `auditor`: `view` |
| `tasks.projects` | `view, create, update, delete, manage_members` | Проекты задач | `admin`: все; `manager`: `view, create, update, manage_members`; `user`: `view` (только свои проекты) |
| `tasks.items` | `view, create, update, change_status, assign, delete` | Задачи | `admin, manager`: все; `user`: `view, create, update, change_status` (в рамках прав проекта) |
| `tasks.comments` | `view, create, delete` | Комментарии к задачам | `admin, manager`: все; `user`: `view, create` |
| `files.storage` | `upload, download, view_metadata` | Файловое хранилище | Все роли (в рамках прав привязавшей сущности) |
| `md.custom_fields` | `view, create, update, delete` | Управление динамическими атрибутами | `admin`: все; `auditor`: `view` |
| `search.global` | `search` | Глобальный быстрый поиск | Все роли |
| `kwh.webhooks` | `view, create, update, delete, test` | Управление исходящими вебхуками | `admin`: все; `auditor`: `view` |

---

## 3. Публичные эндпоинты аутентификации и входа (F-01…F-03)

Не требуют прав RBAC. Защищены от brute-force и rate-limiting по IP.

| HTTP и Маршрут | Входной Payload | Успешный ответ | Ошибки (разд. 8) |
|---|---|---|---|
| `POST /auth/login` | `{login, password}` | `200 {step: "done"}` + сессионная cookie **или** `200 {step: "otp", otp_token: "<str>"}` | `401 invalid_credentials`<br>`423 login_locked`<br>`429 rate_limited` |
| `POST /auth/otp` | `{otp_token, code}` | `200 {step: "done"}` + сессионная cookie | `401 otp_invalid`<br>`401 otp_expired`<br>`423 otp_attempts_exceeded`<br>`429 rate_limited` |
| `POST /auth/otp/resend` | `{otp_token}` | `204 No Content` | `429 otp_rate_limited` |
| `POST /auth/logout` | — *(требует сессию)* | `204 No Content`, сессия закрыта, cookie очищена | — |
| `POST /auth/password-reset/request` | `{email}` | **Всегда `204 No Content`** *(не раскрывает наличие email в базе)* | `429 rate_limited` |
| `POST /auth/password-reset/confirm` | `{code, new_password}` | `204 No Content` *(все активные сессии пользователя закрываются)* | `400 reset_code_invalid`<br>`400 reset_code_expired`<br>`422 password_policy` |
| `POST /invitations/{code}/accept` | `{password}` | `204 No Content` *(активация аккаунта)* | `400 invite_invalid`<br>`400 invite_expired`<br>`422 password_policy` |
| `GET /auth/me` | — *(требует сессию/токен)* | `200 {user: {...}, permissions: ["form.action", ...], permissions_version: 12, instance: {code, name, languages, resource_profile}}` — Bootstrap для SPA | `401 unauthorized` |

---

## 4. Спецификация эндпоинтов по модулям

### 4.1. `iam.profile` — Профиль текущего пользователя (F-02)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /profile` | `view` | → `200 {id, name, email, phone, language, timezone, avatar_file_id, channels: [{type, address, is_verified, is_2fa_enabled}]}` | `401` |
| `PATCH /profile` | `update` | `{name?, language?, timezone?}` → `200 profile` | `422 validation_failed` |
| `POST /profile/password` | `change_password` | `{current_password, new_password}` → `204 No Content` | `403 current_password_invalid`<br>`422 password_policy` |
| `GET /profile/sessions` | `view` | → `200 {items: [{id, ip, user_agent, device_info, created_at, last_seen_at, is_current}]}` | `401` |
| `DELETE /profile/sessions/{id}` | `view` | → `204 No Content` | `404 not_found`<br>`409 cannot_close_current` |
| `POST /profile/channels/telegram` | `manage_channels` | → `200 {deep_link: "https://t.me/bot?start=<code>", expires_at: "..."}` | `429 rate_limited` |
| `DELETE /profile/channels/telegram` | `manage_channels` | → `204 No Content` | `409 last_2fa_channel` *(если 2FA обязательна)* |
| `GET /profile/tokens` | `manage_tokens` | → `200 {items: [{id, name, token_prefix, expires_at, created_at, last_used_at}]}` | `401` |
| `POST /profile/tokens` | `manage_tokens` | `{name, expires_at?}` → `201 {id, name, token: "dwh_...", expires_at}` *(значение токена отдаётся 1 раз)* | `422 validation_failed` |
| `DELETE /profile/tokens/{id}` | `manage_tokens` | → `204 No Content` | `404 not_found` |

### 4.2. `iam.users` — Пользователи экземпляра (F-03, F-08)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /users` | `view` | `?query&state&role_id&limit&after&sort` → `200 {items: [{id, name, email, phone, state, roles: [...], created_at}], next}` | `403 permission_denied` |
| `POST /users` | `create` | `{name, email, login?, phone?, role_ids: [...]}` → `201 user` *(шлёт инвайт)* | `409 email_taken`<br>`409 login_taken`<br>`422 validation_failed` |
| `GET /users/{id}` | `view` | → `200 {id, name, email, login, phone, state, manager_id, roles: [...], invite_status, created_at}` | `404 not_found` |
| `PATCH /users/{id}` | `update` | `{name?, phone?, manager_id?, language?, timezone?}` → `200 user` | `404 not_found`<br>`422 validation_failed` |
| `POST /users/{id}/block` | `block` | → `200 {closed_sessions: 2, revoked_tokens: 1, open_tasks_count: 5}` *(атомарно, I-U1)* | `404 not_found`<br>`409 last_admin` |
| `POST /users/{id}/unblock` | `unblock` | → `200 user` *(state: 'A')* | `404 not_found` |
| `POST /users/{id}/invite` | `invite` | → `204 No Content` *(повторная отправка инвайта, старый код аннулируется)* | `404 not_found`<br>`409 user_already_active` |

### 4.3. `iam.sessions` — Управление сессиями и токенами (Админ)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /users/{id}/sessions` | `view` | → `200 {items: [{id, ip, user_agent, created_at, last_seen_at}]}` | `404 not_found` |
| `DELETE /users/{id}/sessions/{sid}` | `close` | → `204 No Content` *(принудительный отзыв чужой сессии)* | `404 not_found` |
| `GET /tokens` | `view` | `?user_id&limit&after` → `200 {items: [{id, user_id, user_name, name, token_prefix, last_used_at, expires_at}], next}` | `403 permission_denied` |
| `DELETE /tokens/{id}` | `close` | → `204 No Content` *(отзыв скомпрометированного API-токена)* | `404 not_found` |

### 4.4. `rbac.roles` и `rbac.assignments` — Роли и права доступа (F-04)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /roles` | `view` | → `200 {items: [{id, name, pcode, state, order_no, members_count}]}` | `403 permission_denied` |
| `POST /roles` | `create` | `{name, order_no?}` → `201 role` | `409 name_taken`<br>`422 validation_failed` |
| `PATCH /roles/{id}` | `update` | `{name?, state?, order_no?}` → `200 role` | `404 not_found`<br>`409 system_role_immutable` (I-P4) |
| `DELETE /roles/{id}` | `delete` | → `204 No Content` | `404 not_found`<br>`409 system_role_immutable`<br>`409 role_in_use` |
| `GET /roles/{id}/permissions` | `view` | → `200 {grants: [{form, action}]}` | `404 not_found` |
| `PUT /roles/{id}/permissions` | `grant` | `{grants: [{form, action}]}` → `200 {permissions_version}` **или** `202 {operation_id}` *(если объём > 50k строк, FR-PERM-7)* | `404 not_found`<br>`409 auditor_mutation_denied`<br>`422 unknown_form_action` |
| `GET /forms` | `view` | → `200 {modules: [{name, forms: [{code, name, actions: [{code, name}]}]}]}` *(дерево прав)* | `403 permission_denied` |
| `PUT /users/{id}/roles` | `assign` | `{role_ids: [1, 2]}` → `200 {permissions_version}` | `404 not_found`<br>`409 last_admin` |
| `PUT /users/{id}/permissions` | `assign` | `{grants: [{form, action}]}` → `200 {permissions_version}` *(персональные права)* | `404 not_found`<br>`422 unknown_form_action` |
| `GET /users/{id}/effective-permissions` | `view` | → `200 {items: [{form, action, source: "role:Администратор" \| "personal"}]}` | `404 not_found` |
| `GET /operations/{id}` | — *(владелец)* | → `200 {id, type, status: "running" \| "done" \| "failed", progress: {done: 1200, total: 5000}, error?}` | `404 not_found` |

### 4.5. `audit.log` — Журнал аудита и безопасности (F-07)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /audit/changes` | `view` | `?table_name&row_pk&user_id&is_api&from&to&limit&after` → `200 {items: [{id, table_name, row_pk, event: "I"\|"U"\|"D", changed_by, user_name, is_api, changed_at, changed_columns, old_row, new_row}], next}` | `403 permission_denied` |
| `GET /audit/security-events` | `view` | `?event_type&user_id&ip&from&to&limit&after` → `200 {items: [{id, event_type, user_id, user_name, ip, user_agent, details, created_at}], next}` | `403 permission_denied` |

### 4.6. `notify.inbox` и `notify.preferences` — Оповещения (M3, F-01, F-05)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /notifications` | `view` | `?unread_only&type&limit&after` → `200 {items: [{id, type: "info"\|"success"\|"warning"\|"danger", title, body, form_link, source_code, is_read, created_at}], next}` | `401 unauthorized` |
| `GET /notifications/unread-count` | `view` | → `200 {unread_count: 5}` *(легковесный счётчик для бейджа)* | `401 unauthorized` |
| `POST /notifications/{id}/read` | `read` | → `204 No Content` | `404 not_found` |
| `POST /notifications/read-all` | `read_all` | → `204 No Content` *(отмечает прочитанными все сообщения пользователя)* | `401 unauthorized` |
| `DELETE /notifications/{id}` | `delete` | → `204 No Content` | `404 not_found` |
| `GET /profile/notification-preferences` | `view` | → `200 {items: [{event_type, channels: {in_app: true, email: true, telegram: false, sms: false}, is_mandatory}]}` | `401 unauthorized` |
| `PUT /profile/notification-preferences` | `update` | `{preferences: [{event_type, channels: {...}}]}` → `200 preferences` | `409 mandatory_notification_channel`<br>`422 validation_failed` |

### 4.7. `notify.dead_letter` — Разбор сбойных очередей доставки (M3, F-09)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /dead-letter` | `view` | `?channel&from&to&limit&after` → `200 {items: [{id, outbox_id, channel, recipient, template_code, attempts, last_error, failed_at, payload}], next}` | `403 permission_denied` |
| `POST /dead-letter/{id}/retry` | `retry` | `{recipient_override?}` → `200 {status: "queued"}` *(ручной повторный запуск доставки)* | `404 dead_letter_not_found`<br>`409 dead_letter_already_processed` |
| `DELETE /dead-letter/{id}` | `discard` | → `204 No Content` *(отклонение сбойного сообщения с фиксацией в аудите)* | `404 dead_letter_not_found` |

### 4.8. `platform.announcements` — Объявления (M3, F-06)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /announcements` | `view` | `?active_only=true` → `200 {items: [{id, title, body, banner_type: "info"\|"warning"\|"critical", published_at, is_read, requires_acknowledgement}]}` | `401 unauthorized` |
| `POST /announcements/{id}/read` | `read` | → `204 No Content` *(сохраняет серверную отметку прочтения в `announcement_reads`)* | `404 not_found` |

### 4.9. `tasks.projects`, `tasks.items`, `tasks.comments` — Задачник (M4, F-05)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /task-projects` | `view` | `?state&limit&after` → `200 {items: [{id, name, description, state, members_count, created_at}], next}` | `403 permission_denied` |
| `POST /task-projects` | `create` | `{name, description?, member_user_ids: [{user_id, access_kind: "R"\|"W"}]}` → `201 project` | `409 project_name_taken`<br>`422 validation_failed` |
| `PATCH /task-projects/{id}` | `update` | `{name?, description?, state?}` → `200 project` | `404 not_found`<br>`403 permission_denied` |
| `GET /task-statuses` | `view` | → `200 {items: [{id, pcode, name, color, order_no, is_terminal}]}` | `401 unauthorized` |
| `GET /tasks` | `view` | `?project_id&status_id&assignee_id&role_kind&is_overdue&query&limit&after&sort` → `200 {items: [{id, project_id, title, priority, status: {...}, reporter: {...}, assignee: {...}, begin_time, end_time, resolved_time, subtasks_count, comments_count, is_viewed}], next}` | `403 permission_denied` |
| `POST /tasks` | `create` | `{project_id?, parent_task_id?, title, description, priority: "low"\|"medium"\|"high"\|"critical", assignee_id, begin_time?, end_time?, file_ids?: [...]}` → `201 task` *(I-T1, I-T2, I-T5, I-T7)* | `404 project_not_found`<br>`404 assignee_not_found`<br>`422 invalid_time_range`<br>`422 nested_subtask_forbidden` |
| `GET /tasks/{id}` | `view` | → `200 {id, project: {...}, parent_task_id, title, description_markdown, status: {...}, members: [{user_id, name, involve_kind: "R"\|"E"\|"P"\|"A"\|"O", is_viewed}], begin_time, end_time, resolved_time, subtasks: [...], files: [...]}` | `404 not_found`<br>`403 permission_denied` |
| `PATCH /tasks/{id}` | `update` | `{title?, description?, priority?, end_time?}` → `200 task` | `404 not_found`<br>`403 permission_denied`<br>`422 invalid_time_range` |
| `POST /tasks/{id}/status` | `change_status` | `{status_code: "in_progress"\|"done"\|"cancelled", comment?: "..."}` → `200 task` *(I-T3, I-T4, I-T6)* | `404 not_found`<br>`403 permission_denied`<br>`409 task_closed_with_open_subtasks`<br>`422 invalid_status_transition` |
| `POST /tasks/{id}/assignee` | `assign` | `{assignee_id}` → `200 task` *(I-T1: ровно один ответственный)* | `404 not_found`<br>`404 assignee_not_found`<br>`403 permission_denied` |
| `GET /tasks/{id}/comments` | `view` | `?limit&after` → `200 {items: [{id, user: {...}, text_markdown, files: [...], created_at}], next}` | `404 not_found` |
| `POST /tasks/{id}/comments` | `create` | `{text_markdown, file_ids: [...]}` → `201 comment` | `404 not_found`<br>`422 validation_failed` |
| `DELETE /tasks/{id}/comments/{cid}`| `delete` | → `204 No Content` *(автор или админ)* | `404 not_found`<br>`403 permission_denied` |

### 4.10. `files.storage` — Файловое хранилище (M4)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `POST /files/upload` | `upload` | `multipart/form-data (file)` → `201 {id: "<UUID>", sha256: "...", name: "doc.pdf", size: 1048576, mime_type: "application/pdf", created_at: "..."}` *(SHA-256 дедупликация, проверка magic-bytes)* | `400 file_corrupted`<br>`413 file_size_exceeded`<br>`415 file_type_forbidden` |
| `GET /files/{id}` | `view_metadata`| → `200 {id, name, size, mime_type, sha256, created_at}` | `404 not_found` |
| `GET /files/{id}/download` | `download` | → `200 Binary Stream` *(заголовки `Content-Disposition: attachment; filename="..."`, `Content-Type`, `X-Content-Type-Options: nosniff`)* | `404 not_found`<br>`403 permission_denied` |

### 4.11. `platform.settings` — Настройки экземпляра (M1–M5)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /settings` | `view` | `?scope=instance\|user` → `200 {settings: {key: value, ...}}` | `403 permission_denied` |
| `PATCH /settings` | `update` | `{settings: {key: value, ...}}` → `200 settings` | `403 permission_denied`<br>`422 validation_failed` |

### 4.12. `md.custom_fields` — Динамические поля и атрибуты (FR-ATTR-1…4)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /custom-fields` | `view` | `?entity_type=USER\|TASK\|PROJECT` → `200 {items: [{id, entity_type, code, name, field_type, is_required, default_value, options_json, order_no}]}` | `403 permission_denied` |
| `POST /custom-fields` | `create` | `{entity_type, code, name, field_type, is_required, default_value?, options_json?, order_no}` → `201 custom_field` | `400 code_already_exists`<br>`422 validation_failed` |
| `PATCH /custom-fields/{id}` | `update` | `{name?, is_required?, default_value?, options_json?, order_no?}` → `200 custom_field` | `404 not_found`<br>`422 validation_failed` |
| `DELETE /custom-fields/{id}` | `delete` | → `204 No Content` | `404 not_found`<br>`409 field_in_use` |

### 4.13. `search.global` — Глобальный мгновенный поиск (Typesense / pg_trgm, FR-SEARCH-1…4)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /search` | `search` | `?q=текст&entities=task,user,project,file&limit=20` → `200 {results: [{entity_type: "task", id: "42", title: "...", snippet: "<mark>текст</mark>...", link: "/tasks/42", attributes: {...}}], total_hits: 15, query_duration_ms: 12}` | `422 empty_query`<br>`429 rate_limited` |

### 4.14. `kwh.webhooks` — Исходящие вебхуки (Smartup Core, FR-KWH-1…4)

| HTTP и Маршрут | Действие | Вход → Выход | Ошибки (разд. 8) |
|---|---|---|---|
| `GET /webhooks` | `view` | → `200 {items: [{id, name, target_url, subscribed_events, state, created_at}]}` | `403 permission_denied` |
| `POST /webhooks` | `create` | `{name, target_url, secret_token, subscribed_events}` → `201 webhook` | `400 invalid_url`<br>`422 validation_failed` |
| `PATCH /webhooks/{id}` | `update` | `{name?, target_url?, secret_token?, subscribed_events?, state?}` → `200 webhook` | `404 not_found`<br>`422 validation_failed` |
| `DELETE /webhooks/{id}` | `delete` | → `204 No Content` | `404 not_found` |
| `POST /webhooks/{id}/test` | `test` | → `200 {is_success: true, http_status: 200, duration_ms: 145, response_preview: "OK"}` | `400 webhook_target_unreachable` |
| `GET /webhooks/{id}/logs` | `view` | `?limit=50&after=<cursor>` → `200 {items: [{id, event_type, http_status, duration_ms, is_success, sent_at}]}` | `404 not_found` |

---

## 5. Протокол Real-Time событий (SSE: `/api/v1/events`)

Подключение: `GET /api/v1/events` (требует активную сессионную cookie или API-токен в query `?token=...`).

### Формат потока SSE:
```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
X-Accel-Buffering: no

event: ping
data: {"ts": "2026-08-27T10:00:00Z"}

event: notification
data: {"id": 105, "type": "info", "title": "Новая задача", "body": "Вам назначена задача #42", "form_link": "/tasks/42", "created_at": "2026-08-27T10:00:05Z"}

event: permission_revoked
data: {"permissions_version": 13, "reason": "role_updated"}

event: announcement_published
data: {"id": 12, "title": "Технические работы", "banner_type": "warning"}

event: operation_progress
data: {"operation_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "status": "running", "done": 4500, "total": 10000}
```

- **Reconnection UX:** При обрыве соединения браузерный EventSource автоматически переподключается; при восстановлении связи фронтенд выполняет лёгкий опрос `/api/v1/auth/me` для проверки `permissions_version`.

---

## 6. Межсервисный протокол: Экземпляр ↔ Control Plane (FR-CP-1…6, ADR-0004, ADR-0008)

Связь **только исходящая от экземпляра** (pull / report). Запросы к Control Plane авторизуются по mTLS и внутреннему токену экземпляра.

### 6.1. Отправка Heartbeat (`Instance → Control Plane`)
- **Эндпоинт в Control Plane:** `POST /api/v1/internal/instances/{client_code}/heartbeat`
- **Периодичность:** каждые 1–5 минут.
- **Request Payload:**
```json
{
  "client_code": "client-042",
  "app_version": "1.2.0",
  "schema_version": "12",
  "uptime_seconds": 864000,
  "resource_profile": "M",
  "metrics": {
    "active_users_last_24h": 85,
    "active_sessions_count": 112,
    "outbox_pending_count": 0,
    "dead_letter_count": 0,
    "storage_used_bytes": 10737418240,
    "oltp_db_size_bytes": 5368709120
  }
}
```
- **Response `200 OK`:** `{status: "acknowledged", server_time: "2026-08-27T10:00:00Z"}`

### 6.2. Проверка и валидация лицензии
- Лицензия представляет собой токен, подписанный закрытым ключом в **Vault Transit**.
- **Claims лицензии:**
```json
{
  "iss": "smartup-control-plane",
  "sub": "client-042",
  "kid": "vault-transit-key-2026-v1",
  "resource_profile": "M",
  "max_users": 500,
  "features": ["cms", "tasks", "dwh_core"],
  "valid_from": "2026-01-01T00:00:00Z",
  "valid_to": "2027-01-01T00:00:00Z",
  "grace_days": 14
}
```
- Экземпляр верифицирует подпись локально по набору доверенных публичных ключей `cp_public_keys`. При истечении `valid_to + grace_days` экземпляр переключается в `read-only` (все мутирующие эндпоинты возвращают `423 license_expired_read_only`).

### 6.3. Инкрементальная синхронизация объявлений (`Instance → Control Plane`)
- **Эндпоинт в Control Plane:** `GET /api/v1/internal/instances/{client_code}/announcements?since=2026-08-20T00:00:00Z`
- **Response `200 OK`:** `{items: [{id, title_i18n: {ru: "...", uz: "..."}, body_i18n: {...}, banner_type, published_at, state: "published" | "archived"}]}`

---

## 7. Каталог машинных кодов ошибок (RFC 9457)

Каждый код ошибки является уникальным ключом словаря i18n (`FR-I18N-1`).

```
Аутентификация и сессии:
├── invalid_credentials             # Неверный логин или пароль
├── login_locked                    # Временная блокировка после серии неудач
├── user_blocked                    # Учётная запись деактивирована администратором
├── otp_invalid                     # Неверный одноразовый код 2FA
├── otp_expired                     # Срок действия OTP истёк
├── otp_attempts_exceeded           # Превышено число попыток ввода OTP
├── otp_rate_limited                # Слишком частые запросы на отправку OTP
├── reset_code_invalid              # Неверный код сброса пароля
├── reset_code_expired              # Код сброса пароля истёк
├── invite_invalid                  # Ссылка-приглашение недействительна
├── invite_expired                  # Срок ссылки-приглашения истёк
├── password_policy                 # Пароль не удовлетворяет политике сложности
├── current_password_invalid        # Неверно указан текущий пароль при смене
├── cannot_close_current            # Запрет закрытия текущей сессии через эндпоинт чужих сессий
├── last_2fa_channel                # Нельзя отвязать последний канал при обязательной 2FA
└── last_admin                      # Запрет блокировки/снятия прав с последнего администратора

Пользователи и RBAC:
├── email_taken                     # Email уже зарегистрирован в экземпляре
├── login_taken                     # Логин уже занят
├── name_taken                      # Имя роли уже существует
├── user_already_active             # Пользователь уже активирован (повторный инвайт невозможен)
├── system_role_immutable           # Системная роль не может быть изменена или удалена (I-P4)
├── role_in_use                     # Роль назначена пользователям и не может быть удалена
├── auditor_mutation_denied         # Роли auditor запрещено выдавать мутирующие права
├── unknown_form_action             # Указана несуществующая пара (форма, действие)
└── permission_denied               # 403 Forbidden (detail содержит недостающее право)

Задачи и проекты:
├── project_not_found               # Проект задач не найден
├── project_name_taken              # Имя проекта уже используется
├── assignee_not_found              # Назначаемый исполнитель не найден или заблокирован
├── invalid_time_range              # begin_time позже end_time (I-T7)
├── nested_subtask_forbidden        # Запрет создания подзадачи у подзадачи (I-T5)
├── task_closed_with_open_subtasks  # Запрет закрытия задачи с открытыми подзадачами (I-T6)
└── invalid_status_transition       # Недопустимый переход между статусами задачи

Оповещения и Dead-Letter:
├── mandatory_notification_channel  # Попытка отключить обязательный security/OTP канал
├── dead_letter_not_found           # Запись в очереди сбоев не найдена
└── dead_letter_already_processed   # Запись Dead-Letter уже повторно отправлена или отклонена

Файлы и хранилище:
├── file_corrupted                  # Файл повреждён или не совпадает контрольная сумма
├── file_size_exceeded              # Превышен лимит размера файла
└── file_type_forbidden             # Тип файла запрещён политикой безопасности

Платформа и лицензии:
├── license_expired_read_only       # Лицензия истекла (экземпляр переведён в read-only)
├── license_signature_invalid       # Подпись лицензионного токена не прошла проверку
├── csrf_token_invalid              # Отсутствует или не совпадает CSRF-токен
├── rate_limited                    # 429 Превышен лимит запросов
├── validation_failed               # 422 Ошибка валидации входных данных
├── not_found                       # 404 Запрашиваемый ресурс не найден
└── conflict                        # 409 Конфликт состояния данных
```

---

## 8. Точные результаты ТЗ-04 и матрица приёмки

| Точный результат (проверяемый) | Веха | Метод проверки |
|---|---|---|
| 100% эндпоинтов всех модулей Этапа 1 специфицированы с контрактами входов, выходов и прав | M1–M4 | Сверка каталога форм с таблицами спецификации |
| Сгенерированный из кода OpenAPI-документ совпадает по путям, методам и кодам ошибок с ТЗ-04 | M2+ | Автоматический CI-тест сравнения спецификаций |
| Все пользовательские сценарии F-01…F-09 (ТЗ-03) полностью покрыты эндпоинтами спецификации | M1–M5 | Сквозной прогон flows по таблицам API |
| Запросы с `Idempotency-Key` при повторе отдают идентичный статус и тело без дублирования сайд-эффектов | M2 | Интеграционный тест с Testcontainers |

---

## 9. Правила версионирования API

1. **Обратная совместимость:** Добавление новых эндпоинтов, опциональных полей запроса или полей ответа является неломающим изменением и выпускается в рамках `/api/v1`.
2. **Ломающие изменения:** Удаление эндпоинтов, переименование полей или удаление кодов ошибок требуют выпуска новой версии `/api/v2`.
3. **Каталог ошибок:** Добавление нового машинного кода ошибки допускается в `/api/v1` при условии добавления перевода во все поддерживаемые словари (`ru`, `uz`, `en`).

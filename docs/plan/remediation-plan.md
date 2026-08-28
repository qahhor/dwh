# План ремедиации и достройки: фазы R → P → F

**Дата:** 2026-08-28
**Основание:** AUDIT-02, ТЗ-01 v1.1, принцип CEO «углублять, не расширять»
**Заменяет:** план M0 в части календаря (сам M0-контент жив как фаза P; спайк-кит `deploy/spike/` актуален)
**Исполнитель фазы R:** ассистент-архитектор (команда CEO от 2026-08-28)

## Фаза R — ремедиация кода (~2 недели)

Порядок строгий: R1 первым (все остальные правки — уже на целевых версиях).

| ID | Задача | Закрывает | Definition of Done |
|---|---|---|---|
| R1 ✅ | **Версии — ВЫПОЛНЕНО 2026-08-28:** Boot 3.4.3 → **4.1.1**; Java 23 → **25 LTS**; PG в тестах → **18-alpine**; Jackson → **3** (tools.jackson, 8 файлов; JsonProcessingException → JacksonException; jsr310 встроен); `starter-web` → `starter-webmvc` (деприкейтнут в Boot 4); Testcontainers 1.21.3. Проверено: `mvn test` зелёный (21 тест, включая Flyway на PG 18 в Docker), компиляция release 25 | D-1, D-10 | `mvn verify` зелёный на новых версиях; ни одной зависимости вне поддержки |
| R2 ✅ | **Spring Security — ВЫПОЛНЕНО 2026-08-28:** starter-security 4.1.1; KauthAuthenticationFilter внутри цепочки (SecurityContextHolder + свой thread-local); CSRF double-submit по SPA-паттерну (cookie XSRF-TOKEN + X-XSRF-TOKEN), Bearer и запросы без сессионной cookie освобождены; заголовки CSP/HSTS/XCTO/Referrer/Permissions/X-Frame=DENY; 401/403 — RFC 9457 (csrf_token_invalid); попутно NoResourceFound 500→404. SecurityConfigTest — 5 зелёных, полный набор 26 | D-2, D-3 | Тест: мутирующий запрос без CSRF-токена → 403; заголовки в каждом ответе |
| R3 ✅ | **Rate limiting — ВЫПОЛНЕНО 2026-08-28:** Bucket4j-фильтр в цепочке SS (после аутентификации, до авторизации): ip:/user:/api:-ключи, отдельный строгий лимит на дорогие пути (audit/search), 429 + Retry-After (RFC 9457 rate_limited), событие rate_limit_exceeded в security_events с анти-флудом (раз в минуту на ключ); лимиты настраиваются (dwh.rate-limit.*). RateLimitFilterTest — 3 зелёных | D-4 | Тест: превышение → 429 + запись в журнал |
| R4 ✅ | **Миграции отдельным шагом — ВЫПОЛНЕНО 2026-08-28:** flyway.enabled=false; профиль `migrate` (web-none, мигрирует и завершает процесс) — будущий Nomad batch-job; SchemaVersionGate (Flyway validate: pending/чужие/checksum → отказ старта с указанием на migrate). **Плюс закрыты C-1/C-2 AUDIT-03:** DEMO-данные и админ `admin/Admin123!` вырезаны из V002 (справочники остались); InstanceBootstrap создаёт instance_info и первого админа из конфигурации (без параметров — отказ; force_password_change=true; идемпотентен); application-dev.yml — только локально. MigrationGateAndBootstrapTest — 5 зелёных на PG 18 | D-5 | Тест: приложение с чужой версией схемы не стартует; миграция — только явным запуском |
| R5 ✅ | **CI — ВЫПОЛНЕНО 2026-08-28:** .github/workflows/ci.yml — три обязательных джоба: backend (Java 25, mvn verify c Testcontainers/ArchUnit, SBOM CycloneDX артефактом), frontend (Node 24, ng build production), security (gitleaks по истории + Trivy fs, Critical/High = красный, исключения только через .trivyignore с ревью). Неработавший .mvn/wrapper удалён (CI и разработчики — системный Maven). Required checks в настройках GitHub включает CEO | D-6 | PR с намеренной уязвимой зависимостью и с нарушением границ модулей — красный |
| R6 ✅ | **Тестовый долг — ВЫПОЛНЕНО 2026-08-28:** RbacSystemRolesIntegrationTest (7 сценариев на PG 18): FR-PERM-8 «каждый эндпоинт объявляет право» и FR-PERM-1 «каждая пара есть в каталоге» — сканом контроллеров; матрица ролей (admin 100% каталога, auditor без единой мутации, user по матрице); выдача/отзыв + рост permissions_version. Попутно закрыты дыры: mf/search-контроллеры получили декларации прав (platform.files, platform.search), audit/kwh пересажены с чужой platform.settings на свои формы (audit.log, platform.webhooks), V003 — матрица прав manager/user/auditor (ролям вообще не были выданы права). **I-U1 реализован**: блокировка через порт UserSessionInvalidator (DIP, без цикла модулей) закрывает сессии и отзывает токены в той же транзакции + UserBlockingInvariantTest. Полный набор — 41 тест | D-7 | Тесты матрицы 8.2 для блоков SEC и PERM зелёные в CI |

**Результат фазы R (проверяемый):** сборка на поддерживаемых версиях; строки SEC и MOD
матрицы 8.2 демонстрируемы; регресс A-4 (миграции при старте) устранён.

## Фаза P — платформа (~2–3 недели, бывший M0)

Спайк по готовому киту → Nomad/Consul/Vault кворум → шаблон job'а + `client-add` ≤ 1 ч →
бэкапы с restore одной командой → стек наблюдаемости (Grafana/Loki/VictoriaMetrics; в код —
JSON-логи с маскированием, метрики очередей) → секреты в Vault (закрывает D-8, D-11, D-12).
Детализация задач — прежний `M0-plan.md` (потоки B и часть A/C), актуален.

## Фаза F — достройка функционала (~3–4 недели)

SSE (D-9) · каналы Telegram/email через SPI-адаптеры (D-14) · OTP сквозной · лицензии через
Vault Transit (D-15) · объявления CP → экземпляры · кольца R0/R1/R2 · блоки v1.1 (ATTR/SEARCH/
KWH — доведение до результатов матрицы 8.2) · i18n-словари · нагрузочный прогон.

Сделано:

| Задача | Что закрыто | Дата |
|---|---|---|
| F1 | SSE `/api/v1/events` (D-9, C-8) + FR-TASK-8 | 28.08.2026 |
| F3 | Control plane: реестр, heartbeat, бэкапы, объявления (FR-CP-1, 2, 5, 6, 7); панель `apps/web-cp`; отправка heartbeat экземпляром (FR-INST-3) | 29.08.2026 |

**Приёмка Этапа 1** — по чек-листу ТЗ-01 разд. 8, ориентир — начало ноября 2026.

## Правила на время ремедиации

1. Объём заморожен («углублять, не расширять»): никаких новых FR/блоков/компонентов.
2. Каждая R-задача — отдельная ветка + PR-стиль коммит с указанием закрываемых D-* из AUDIT-02.
3. Изменение поведения относительно ТЗ — сначала строка в журнале решений, потом код.

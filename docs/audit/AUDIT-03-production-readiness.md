# AUDIT-03: Верификация готовности к production

**Дата:** 2026-08-28
**Объект:** main @ c6c83ad (после R1–R2)
**Метод:** фактическая проверка кода/конфигов/миграций, сверка с матрицей результатов ТЗ-01 разд. 8.2 и планом ремедиации

---

## 1. Production Readiness Status: **NOT READY**

Это соответствует плану, а не является сюрпризом: проект в середине фазы R
(R1–R2 из R1–R6 закрыты), фазы P (платформа) и F (достройка) не начаты.
По матрице точных результатов ТЗ-01 демонстрируемо **~7 из 17** результатов.
Целевое состояние Ready = конец фазы F + приёмочный чек-лист ТЗ-01 разд. 8.

## 2. Implemented Features (подтверждено проверкой)

| Блок | Состояние | Подтверждение |
|---|---|---|
| Версии платформы | ✅ Boot 4.1.1 / Java 25 LTS / Jackson 3 / PG 18 | pom.xml, сборка release 25, тест на postgres:18 |
| Security-каркас | ✅ Spring Security, CSRF double-submit, заголовки, RFC 9457 | SecurityConfigTest (5 сценариев) |
| Модульность | ✅ 7 модулей, префиксы Biruni, ArchUnit без циклов | ModularArchitectureTest |
| RBAC-ядро | ⚠️ Каталог форм/действий, роли, эффективные права, @RequiresPermission | код + seed; «отзыв ≤ 60 с» не измерен, фон пересчёта не проверен |
| Пользователи/аутентификация | ⚠️ Argon2id, сессии, OTP-структуры, API-токены | код + тесты; OTP-доставка — console-заглушка |
| Задачник | ⚠️ Проекты/задачи/комментарии, I-T1 в БД (unique index) | код + MsTaskServiceTest |
| Оповещения | ⚠️ Outbox SKIP LOCKED, in-app | **SSE отсутствует** (FR-NOTIF-2) |
| Файлы | ⚠️ SHA-256 дедуп, потоковая выдача | **LocalStorageProvider (диск), не Garage/S3** — FR-FILE-1 не выполнен |
| Аудит | ⚠️ Партиционированный audit_log | retention-джоб не проверен |
| Вебхуки (kwh) | ✅ Подписки, outbox, HMAC-SHA256 | KwhWebhookServiceTest |
| Идемпотентность | ✅ idempotency_keys | код + тест |
| Наименования | ✅ Таблицы `md_/kauth_/ms_/mf_/kwh_/cp_/audit_` консистентны; Java-классы в конвенции префиксов; фронт — core/features/layout/shared | сверка V001-миграций и дерева классов |
| Чистота кода | ✅ 0 console.log, 0 TODO/FIXME, 1 System.out (в тесте) | grep |

## 3. Critical Issues (блокируют запуск)

| # | Проблема | Доказательство | Закрывается |
|---|---|---|---|
| C-1 ✅ | ~~Захардкоженный админ в миграции~~ — **закрыт 2026-08-28 (R4):** InstanceBootstrap из конфигурации, force_password_change=true, дефолтов нет | V002 очищен | — |
| C-2 ✅ | ~~DEMO-клиент в миграции~~ — **закрыт 2026-08-28 (R4):** instance_info создаёт InstanceBootstrap из dwh.instance.* | V002 очищен | — |
| C-3 | **Файлы на локальном диске** (`./data/storage`), не S3/Garage — потеря при пересоздании контейнера, нет бэкапа | LocalStorageProvider | Garage-адаптер StorageProvider; фаза P |
| C-4 ✅ | ~~Миграции при старте~~ — **закрыт 2026-08-28 (R4):** flyway off, профиль migrate, SchemaVersionGate (FR-INST-2) | тест MigrationGateAndBootstrapTest | — |
| C-5 ✅ | ~~Нет rate limiting~~ — **закрыт 2026-08-28 (R3)** | RateLimitFilterTest | — |
| C-6 | **Нет CI** — ArchUnit/тесты/сканы не гейтят merge | .github отсутствует | R5 |
| C-7 | **Секреты через env с dev-фолбэками** (`DB_PASSWORD:postgres`), Vault не интегрирован | application.yml | Фаза P (Vault) |
| C-8 | **Нет SSE** (FR-NOTIF-2, FR-API-5 — M) | grep: 0 | Фаза F |
| C-9 | **Нет OpenAPI из кода** (FR-API-1 — M; заявлен в DoD CONTRIBUTING) | springdoc отсутствует в pom | Фаза F |
| C-10 | Mail/SMS — console-заглушки; Telegram-адаптер без Vault-секретов | ConsoleMailProvider и др. | Фаза F (реальные адаптеры) |
| C-11 | Нет деплой-контура (Nomad), наблюдаемости, бэкапов как процессов | deploy/ = только spike | Фаза P |

## 4. Files to Remove / Relocate (перед production)

| Файл | Проблема | Действие |
|---|---|---|
| ~~`scripts/test-api.ps1`~~ | ✅ перемещён в `scripts/dev/` (R4) | — |
| ~~`docs/plan/stage-1-completion.md`~~ | ✅ заменён указателем на актуальные статусы (R4) | — |
| `.mvn/wrapper/` (properties без mvnw-скриптов) | Неработоспособный half-wrapper | Доукомплектовать скриптами в R5 (CI) либо удалить |
| ~~`V002` (DEMO/админ)~~ | ✅ вырезано, справочники сохранены (R4) | — |
| ~~println(SEED_ADMIN_HASH)~~ | ✅ удалён (R4) | — |
| `deploy/spike/` | Не prod-конфигурация | Оставить (нужен фазе P), пометить README «не для прод» — уже помечен |

Проверено и чисто: `target/`, `node_modules/`, `dist/` — не в git; H2 — scope test;
секретов в репозитории нет (gitleaks-скан войдёт в CI в R5).

## 5. Recommendations

1. **Не менять порядок**: фаза R (R3–R6) → фаза P (Vault/Nomad/Garage/наблюдаемость/бэкапы — закрывает C-1..C-3, C-7, C-11) → фаза F (SSE, OpenAPI, реальные каналы — C-8..C-10) → приёмка по чек-листу ТЗ-01.
2. C-1/C-2 можно закрыть дёшево уже в R4 (миграции всё равно перерабатываются): справочники — в миграциях, экземплярные данные — в инициализации.
3. «Production readiness» финально подтверждается только приёмочным чек-листом ТЗ-01 разд. 8 с хронометражем (client-add ≤ 1 ч, restore ≤ 1 ч, учения по отказам) — раньше конца фазы P говорить о запуске нельзя.
4. Пентест (FR-SEC-8) — после фазы F, до первого коммерческого клиента.

# Финальный аудит production-readiness SmartupCMS

> Архивный непроверенный черновик. Сохранён в Git по запросу пользователя
> 2026-09-08; публикация не подтверждает выводы, результаты проверок или
> актуальность находок. Не использовать как требования или release evidence.

Дата: 2026-09-06. Решение: **NO-GO для production; продолжать стабилизацию и приёмку.**

Проверенный source: `6c807488e9fb4a7476abbe56eca3ca84866766fb`, локальная `main`. На момент проверки удалённая `main` — `0802ac899a54477b39d53ba22e872469bcca0611`; локальная ветка впереди на 26 commits. Аудит не является спецификацией, разрешением на публикацию или подтверждением соответствия конкретной production-установки.

Нормативная база: [текущее ТЗ](../docs/technical-specification.md), [ADR-0013: data scope](../docs/adr/ADR-0013-data-scope.md), [ADR-0014: единый открытый runtime](../docs/adr/ADR-0014-unified-open-source-runtime.md). `docs/ai-context.md` использован как навигация и handoff, не как замена source/тестам. Старые локальные аудиты не использованы как доказательство действующих дефектов.

## 1. Executive summary

1. **Два незакрытых release gate и 16 групп High.** Это не 16 независимых уязвимостей: связанные security/business/performance проявления объединены. Blocker — препятствие решению GO; High — конкретный дефект или риск, требующий закрытия либо строго ограниченного, подписанного исключения.
2. **Рабочее ядро существенно реализовано.** IAM, задачи/проекты/подзадачи, файлы, объявления, переводы и интеграционные SPI существуют. Полностью отсутствующих FR-групп не обнаружено, но отдельные обязательные ветви сломаны: password reset, отзыв credentials после смены пароля, напоминания о дедлайне, scoped analytics.
3. **Свежая локальная проверка зелёная:** Maven `364/364`, Angular `189/189`, frontend typecheck/production build, i18n audit, E2E typecheck/config/artifact-security и ряд статических release/docs contracts. Это не доказательство прохождения всех сценариев: найденные отрицательные ветви и гонки в suite отсутствуют.
4. **Последний полный browser run — `36/37`, не новый запуск данного аудита.** Все 6 Projects cases прошли; `/analytics` имеет mobile overflow `390 → 499 px`. App source эквивалентен проверенному срезу; причину конкретного DOM-overflow ещё нужно локализовать. Полный зелёный browser gate отсутствует.
5. **Основные риски безопасности — внутри установки:** доступ к пользователям и аналитике вне разрешённой орг-ветки, неполный credential revocation, доверие proxy headers, хранение одноразовых секретов в idempotency cache. Это не утверждение о cross-tenant escape: продукт по ADR-0014 — отдельная одноорганизационная установка.
6. **Надёжность требует исправлений:** возможен конкурентный цикл задач; search теряет обновления и скрывает отказ; выгрузки удерживают DB connections; deployment может пропустить backup сохранённого volume; проверка RPO использует ненадёжный timestamp.
7. **Supply chain и эксплуатационная приёмка не закрыты.** Свежий scan локальных backup/PostgreSQL images выявил 7 уникальных High CVE в общем пакете; server/web/Typesense — без High/Critical в данном scan. Проверенного опубликованного multi-arch release и target evidence для SLO, restore, alert delivery, TLS/origin и R2 нет в представленном контуре доказательств.
8. **Очистка выполнена ограниченно и безопасно:** дополнены три ignore-файла. Исходники приложения, миграции и действующая установка не изменены; файлы не удалялись, commit/push/deploy не выполнялись. Старые отчёты, Graphify и рабочие материалы сохранены; решения об архивировании перечислены отдельно.

### 1.1. Охват и границы доказательств

Проведены независимые обзоры business logic, AppSec, frontend/testing, architecture/performance и platform/DevOps; пересекающиеся High проверены и сведены по source. Использованы scoped Graphify queries, затем актуальные source/config/tests. Инвентаризированы root и вложенные директории, tracked и локальные generated artifacts. Не заявляется прочтение каждой строки каждого vendor/generated файла или полноценный внешний penetration test.

| Область | Инвентарь / проверка |
|---|---|
| Весь Git-срез | 621 tracked file до добавления этого отчёта; 456 файлов собственных implementation/config/script типов; 106 tracked Markdown |
| Backend | `apps/server/src/main`: 212 файлов, 21 893 строки; `src/test`: 68 файлов, 9 022 строки |
| Frontend | `apps/web/src`: 88 файлов, 28 558 строк; 32 spec-файла |
| Libraries | 30 файлов, 976 строк; Maven-модули и ArchUnit contracts проверены |
| E2E | `e2e/tests`: 12 файлов, 1 946 строк; 37 объявленных browser cases в 10 spec-файлах |
| Infra / automation | `deploy`: 12 tracked файлов; `scripts`: 35; `.github`: 7 |
| Документы / история | `docs`: 49 файлов; `audit`: 35 tracked; старые ADR/аудиты классифицированы, не приняты автоматически за текущие требования |
| Generated / локальное | 9 tracked Graphify-файлов; `output/`: 1 957 локальных файлов, 153 418 615 bytes; runtime dependencies не включены в оценку качества собственного кода |

Термины доказанности:

- **Факт кода/конфигурации:** конкретный исполнимый путь и нарушенный контракт прослежены. Эксплуатация на production не подразумевается.
- **Свежая проверка:** команда выполнена в этом аудите на указанном локальном source/artifact.
- **Предыдущая проверка:** сохранённый результат другого run с явно указанной эквивалентностью source.
- **Гипотеза воздействия:** путь риска найден, но степень влияния требует race/load/network experiment.
- **Не подтверждено:** необходимый target/provider/release evidence не представлен; это не утверждение, что внешний контур точно отсутствует.

### 1.2. Выполненные проверки

| Проверка | Результат и граница |
|---|---|
| Maven `verify`, Java 25.0.2 | PASS: 364 tests, 0 failures/errors/skips, 67 Surefire XML; timestamps 07:30:47–07:32:34 UTC; PostgreSQL Testcontainers и Flyway входят в run |
| Frontend `npm test` | PASS: 189 tests / 32 spec files, pinned Node 24.15.0 |
| Frontend typecheck / production build | PASS; initial JS 476.61 kB, estimated transfer 126.30 kB; не является измерением page p95 или target memory |
| `npm run i18n:audit` | PASS: 1 034 referenced keys / 1 065 RU keys; известные blind spots — M07 |
| E2E typecheck / config / artifact-security | PASS; config 3/3; secret-bearing artifact sentinels не обнаружены |
| Public-docs / repository-hygiene | PASS; 19 required public files / 106 tracked Markdown проверены; полнота текста/API не следует из этого gate |
| Unified-boundary / release-supply-chain / managed-acceptance static contracts | PASS; это проверка scripts/config, не реальная подпись, restore или внешний network probe |
| Root и web Dockerfile build checks | PASS; полноценные новые production images в этом аудите не собирались |
| Gitleaks 8.28, redacted, вся Git-история `--all` | PASS, 0 findings с действующими `.gitleaks.toml` и пятью точечными legacy allow-list fingerprints; ignored local secrets/customer data не сканировались |
| `npm audit --package-lock-only`, web | 0 High/Critical; 1 Moderate affected dev package `qs@6.15.3`, два advisory; e2e lock — 0 findings |
| Trivy 0.74, свежая DB | 5 локальных image artifacts; детали и границы — H16 |
| Remote CI/release | Последний проверенный успешный CI относится к remote SHA `0802ac8`, не local `6c80748`; `v*` tags и GitHub Releases на момент проверки не найдены |

Frontend использовал существующий `node_modules`: **свежий `npm ci` не выполнялся**. Полный browser suite, target clean install, no-egress runtime, target load/4h soak, external TLS/origin, real SMTP/Telegram/R2, production failure/restore/graceful-shutdown drills заново не выполнялись. Старые результаты не переименованы в свежие.

Ссылка на проверенный внешний CI: [GitHub Actions run 33968459665](https://github.com/qahhor/dwh/actions/runs/33968459665). Успех этого run не является приёмкой локальных 26 commits. Branch/tag protection без административного доступа не подтверждалась.

### 1.3. Требования и roadmap: выполнено / частично / отсутствует

Статус **Р**: реализация обнаружена и применимые локальные tests/contracts проходят; это не эквивалент всей production-приёмки. **Ч**: подтверждён функциональный/контрактный пробел. **В**: реализация есть, внешняя или release-приёмка не подтверждена. Отсутствующие ветви названы явно; полностью отсутствующих FR-групп нет. Действующее ТЗ и ADR имеют приоритет над историческими roadmap/audit списками.

| FR | Статус | Реализация / оставшийся пробел |
|---|---|---|
| AUTH-01 | Ч | Login/logout/cookie session есть; production `Secure`/proxy trust — H05 |
| AUTH-02 | Р | Forced password change проверяется server-side |
| AUTH-03 | Ч | Argon2id и token hashing есть; raw issued secrets сохраняются другим слоем — H06 |
| AUTH-04 | Ч | Password reset сломан; смена пароля не отзывает все credentials — H01/H02 |
| AUTH-05 | Р | CSRF и безопасный error handling присутствуют; отдельные status/error UX gaps — M02 |
| IAM-01 | Ч | Lifecycle/CRUD есть; direct-ID scope и nullable PATCH — H03/M04 |
| IAM-02 | Ч | Scope materialization/list filtering есть; user detail/mutations и Analytics не соблюдают границу — H03/H04 |
| IAM-03 | Р | Roles/assignment/system invariants реализованы и тестируются |
| IAM-04 | Р | Effective permissions/version invalidation реализованы |
| IAM-05 | Р | Authentication/action permissions enforced backend; frontend navigation — M08, row scope отдельно H03/H04 |
| WORK-01 | Ч | Tasks/projects/subtasks/status/type UI есть; concurrent cycle, deadline worker и type validation — H08/H09/M01 |
| WORK-02 | Р | Participant existence/scope validation есть; cardinality/N+1 — M03 |
| WORK-03 | Ч | Comments/attachments/permission checks есть; attachment IDs в POST response и duplicates — L02 |
| WORK-04 | Ч | Custom fields/audit есть; `select` и `user_ref` не имеют полного server validation — M01 |
| FILE-01 | Р | Action permission + row scope; direct 403/404/200 contracts |
| FILE-02 | Р | 50 MiB boundary реализован; дополнительное idempotency buffering — H06 |
| FILE-03 | Р | Hash/metadata/content validation реализованы |
| FILE-04 | Р | Quarantine-before-publish и cleanup tests проходят |
| FILE-05 | В | Fail-closed ClamAV wiring/local tests есть; target outage drill не выполнен |
| FILE-06 | В | Local/S3 SPI есть; target R2 byte-for-byte smoke отсутствует в evidence |
| FILE-07 | Р | Last-reference object deletion contract проходит |
| SEARCH-01 | Ч | PostgreSQL authority есть; index loss/startup/fallback — H10 |
| SEARCH-02 | В | Browser использует server API; target Typesense/private port isolation нужно подтвердить |
| SEARCH-03 | Р | Admin-only full search (`*.*`) — явная допустимая граница текущего ТЗ |
| COMM-01 | Р | Console defaults показывают честный unavailable/unhealthy delivery; secret logging — M14 |
| COMM-02 | В | SMTP/Telegram и SMS SPI есть; real delivery не подтверждена; built-in SMS не требуется текущим ТЗ |
| COMM-03 | Ч | Outbox/HMAC/claims/retries есть; DNS rebinding gap и non-2xx diagnostics — H07/M05 |
| COMM-04 | Ч | Announcement state machine/CAS есть; draft можно pre-read — M06 |
| COMM-05 | В | Default-off outbound wiring есть; свежего no-egress observation нет |
| ADMIN-01 | Р | Server permissions и negative tests присутствуют |
| ADMIN-02 | Р | Audit keyset/cap/redaction реализованы; полнота событий — M12 |
| ADMIN-03 | В | Provider status/selection есть; installation health не подтверждён |
| ADMIN-04 | В | Sanitized backup status/freshness model есть; actual production backup не проверен |
| I18N-01 | Р | 8 packaged languages, per-key RU fallback/coverage |
| I18N-02 | Р | Revision CAS, atomic override replacement, audit/cache invalidation; target two-session повторить на RC |
| I18N-03 | Р | Public dictionary/admin permission и narrow route contracts |
| I18N-04 | Ч | Preference/keys есть; raw Russian/English runtime copy и audit blind spots — M07 |
| I18N-05 | Р | Explicit confirmation/known-key merge/delete-after-success legacy migration |
| OSS-01 | В | Source/LICENSE/NOTICE есть; published RC build/install ещё не подтверждён |
| OSS-02 | Р | Один runtime server/web, нет обязательного Control Plane |
| OSS-03 | В | Licensing/enrollment dependency не найдена; fresh release no-egress остаётся gate |

Нефункциональная трассировка:

| NFR | Состояние |
|---|---|
| DATA-01 | Частично: derivative search не гарантирует доставку/восстановление — H10 |
| DATA-02 | Local/S3 есть; целевой managed R2 — B02 |
| DATA-03 | Forward-only migrations/schema gate есть, локальные tests зелёные; target isolated `migrate` — B01/B02 |
| DATA-04 | Encryption/checksums/object backup существуют; deploy bypass и RPO integrity — H12/H15 |
| DATA-05 | PII/retention guidance есть; утверждённое installation annex не представлено — B02 |
| SEC-01 | Частично: H01–H06, H03/H04 scope; не заменяется зелёным общим suite |
| SEC-02 | Non-root/capability/read-only/backup-role controls есть; target least-privilege и resource profile проверить |
| SEC-03 | Git scan/artifact tests PASS; idempotency secrets и console OTP logs — H06/M14 |
| SEC-04 | Edge/private-port config есть; cookie trust и origin preflight — H05/H14; external target не проверен |
| SEC-05 | Local content/EICAR/outage contracts есть; target evidence отсутствует |
| SEC-06 | Webhook policy неполна для DNS rebinding — H07 |
| SEC-07 | Verifiers/signatures/SBOM workflow есть; exact published artifacts и обязательность digest — H13/B01 |
| PERF-01 / PERF-02 | Не приняты: нет target 100-active/20-upload/4h soak с утверждёнными thresholds — B02 |
| REL-01 | Health/readiness wiring есть; target dependency failure/recovery не доказан |
| REL-02 | Fail-closed scripts частичны: сохранённый volume без container — H12 |
| REL-03 | Graceful shutdown configuration есть; fresh in-flight stop drill не выполнен |
| REL-04 | Rollback/restore tooling есть; mutable refs/RPO integrity и target drill — H13/H15/B02 |
| REL-05 | Single-host boundary честно документирована; cross-host HA не обещана и не является отсутствующим модулем |
| OBS-01 | Traceparent, internal metrics/Prometheus, scanner/S3 timers есть; сбор и target latency dashboards не доказаны |
| OBS-02 | Named on-call, маршруты alerts, SLO и actual receipt — B02/M15 |
| PORT-01 / PORT-02 | Multi-arch/reproducible Compose intent есть; exact amd64/arm64 release manifests и обе clean installs не подтверждены |

### 1.4. Приёмка релиза AC-01…AC-13

| Gate | Аудиторский статус |
|---|---|
| AC-01 | Локальный Maven PASS на `6c80748`; required CI на утверждённом release SHA ещё нужен |
| AC-02 | test/typecheck/build PASS; fresh `npm ci` и release artifact verification не выполнены |
| AC-03 | Часть static contracts PASS; полный набор release-config/backup-status/no-egress/runtime Compose в этом аудите не запускался |
| AC-04 | Flyway/Testcontainers contracts PASS; отдельный target production `migrate` + повторный запуск не доказаны |
| AC-05 | Свежей clean production install обоих platform artifacts нет |
| AC-06 | Последний полный browser suite 36/37; критические отдельные journeys не следует объявлять проваленными только из-за Analytics, но весь текущий gate не зелёный |
| AC-07 | Local tests PASS; target scanner outage/alert/storage residue evidence отсутствует |
| AC-08 | Combined restore не принят; H12/H15 и отсутствие target drill |
| AC-09 | Target R2/policy/object recovery не принят; M15 |
| AC-10 | Имеющиеся negative tests PASS; matrix расширить user direct-ID и Analytics scope — H03/H04 |
| AC-11 | Нет проверенного published RC bundle с exact digests, signatures, attestations и SBOM |
| AC-12 | Blocked: числовые SLO/RPO/RTO, privacy/retention, target domain/region, named owners и GO decision не представлены |
| AC-13 | Local catalogs/audit PASS с blind spots M07; двухсессионный browser evidence повторить на RC |

### 1.5. End-to-end, временные границы и «тихие» дефекты

| Процесс | Happy path / контроли | Не закрытый error/edge path |
|---|---|---|
| Вход, смена/восстановление пароля | Login/CSRF/force-change существуют | H01: schema/delivery reset; H02: revocation; H05: proxy/cookie; M13: чужое закрытие session |
| User/role lifecycle | Role invariants, materialized scopes, block/anonymize invalidator | H03: direct-ID row scope; M04: нельзя очистить nullable manager/avatar |
| Tasks/projects/subtasks | Transactional CRUD, task/file scope, sparse project PATCH; 6 Projects browser cases PASS ранее | H08: двухтранзакционный cycle; M01: type/custom fields; M03: limit/participant bounds |
| Comments/files | Scope, junction transaction, quarantine/ClamAV/content validation | L02: attachments response/duplicates; H06: upload buffering; target scanner/storage outage отсутствует |
| Announcements/i18n | State machine/CAS, revision conflict, fallback, safe legacy migration | M06: draft pre-read; M07: raw copy/false-green audit; target live-change replay нужен |
| Notifications/webhooks | Durable outbox claim token/stale recovery, HMAC, redirect запрет | H09: deadline CHECK failure; H07: DNS peer gap; M05: non-2xx status loss |
| Search/analytics/export | PostgreSQL source of truth, server API, analytics/report endpoints | H04 scope; H10 hidden failure/lost indexing; H11 slow-reader resource hold |
| Deploy/recovery | Migrate service, encrypted backups, health gates, verification scripts | H12 retained-volume bypass; H13 mutable/release checks; H14 wrong origin; H15 false RPO |

По tracked активным `apps/libs/e2e/scripts/deploy/docs/.github` точный поиск слов `TODO/FIXME/HACK/WIP/XXX` дал **0**. Эвристический поиск закомментированного исполняемого кода также не дал подтверждённых кандидатов. Это не доказательство отсутствия техдолга: дефекты выше не помечены TODO. Generated/vendor/history намеренно не трактуются как незавершённые продуктовые функции.

Console providers, выключенные по умолчанию исходящие интеграции, disabled legacy SSO/module metadata, admin-only full search и отсутствие Control Plane соответствуют текущей границе продукта. Их нельзя механически удалять как stubs или объявлять missing enterprise features. Для включения реальной доставки/webhook нужен owner, provider configuration, health/negative tests и gate H07/M05/M14. Реально отсутствующая ветвь — надёжная отправка password-reset challenge, а не весь notification SPI.

## 2. Блокеры релиза

### B01 — Не принят воспроизводимый release candidate

- **Описание → где:** локальный `6c80748` не совпадает с проверенным remote CI SHA; published tags/releases не обнаружены; последний полный browser suite 36/37. Источники: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `scripts/release/verify-published-release.ps1`, `e2e/tests/browser/instance/ui-release-regressions.spec.ts:46`; AC-01…AC-06/AC-10/AC-11/AC-13. Дополнительно H13/H16.
- **Риск:** невозможно связать принятый source, security/browser результаты и реально развёртываемые bytes; зелёный старый CI и подпись сами по себе не доказывают качество нового image.
- **Исправление:** после P0 зафиксировать reviewed RC SHA; на нём clean dependency install/full CI, полные browser и no-egress/negative gates; собрать все 5 images × amd64/arm64, проверить exact digests/CVE/SBOM/provenance/Cosign, установить и повторно проверить из published bundle. Публикация требует отдельного разрешения владельца; в этом аудите она не выполнялась.
- **Закрытие:** одно source/digest-linked evidence bundle, все обязательные gates PASS, 0 непринятых High, release owner подписывает GO. Удалять/ослаблять failing test ради PASS нельзя.
- **Оценка:** 2–4 инженерных дня на финальный прогон и оформление **после** исправлений; H13, H16 и продуктовые P0 в эту оценку не включены. Владелец: Release Engineer + SDET + Security.

### B02 — Не доказана готовность конкретной production-установки

- **Описание → где:** `docs/ops/production-launch-checklist.md`, `docs/ops/managed-infrastructure-acceptance.md`, раздел 7 ТЗ; отсутствует представленное заполненное installation annex и target evidence для AC-07…AC-09/AC-12, NFR-PERF/OBS/REL. Это пробел доказательств, не заявление, что внешний оператор ничего не настроил.
- **Риск:** неизвестны приемлемая потеря данных/простой/нагрузка, фактическая восстанавливаемость, получатель инцидента и ответственность за запуск. Ни размер heap, ни SLO, ни обязательные сроки retention нельзя выбрать за владельца.
- **Исправление:** утвердить workload по установкам, p95/p99/error/saturation thresholds, SLO/RPO/RTO, data/backup retention и privacy owners, domain/edge/regions, age identity custody и named on-call. На disposable target выполнить capacity/4h soak, slow-reader/near-limit upload, DB/scanner/storage/backup failures, in-flight graceful stop, actual alert receipt, encrypted combined restore, rollback и external isolation. До доверия manifest исправить H12/H14/H15/M15.
- **Закрытие:** приложены наблюдаемые метрики/инвентари/checksums/receipts, измеренные RPO/RTO, все negative cases FAIL корректным образом, назначенное лицо принимает остаточный single-host risk и GO/NO-GO.
- **Оценка:** 3–7 инженерных дней **после** получения доступа, утверждения чисел и исправления tooling; минимум одно полное 4h soak-окно плюс повтор failed drills. Согласования и инфраструктурные ожидания отдельно. Владелец: SRE/DBA + Product/Privacy/Operations owners.

## 3. Критические недоработки (High)

### H01 — Password reset обращается к несуществующей таблице; доставка отсутствует

**Доказательство:** [KauthPasswordResetRepository](../apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/repository/KauthPasswordResetRepository.java), строки 19–27, 43–51, 63–70, использует `kauth_password_resets(token_hash)`; `V001__init_schema.sql:151` создаёт `kauth_password_reset_codes(code_hash)`. В V001–V024 нет исправляющего alias/migration. `KauthAuthService:175–181` не отправляет challenge через delivery provider.

**Сценарий/риск:** неизвестный email завершается без reset insert, известный попадает в ошибочный SQL: reset не работает, различие 204/5xx может раскрыть существование аккаунта. После исправления только имени таблицы останется небезопасный шестизначный глобальный challenge без полноценной привязки/лимита попыток. Account takeover сейчас не воспроизведён и не объявляется фактом: рабочему reset мешает исходный schema defect.

**Исправление/приёмка:** закончить flow целиком: совместимая forward migration/repository, high-entropy одноразовый token, привязка к account/purpose, ограниченный TTL/attempts, atomic consume и одно активное challenge, реальная безопасная доставка, одинаковый внешний ответ. Тесты через API на real PostgreSQL: known/unknown, expiry/reuse/concurrent consume/rate limit, actual delivery fixture без логирования секрета.

**Оценка:** 3–5 инженерных дней + provider setup; Backend/Auth + Security. Зависимости: delivery owner, H02/H05/H06.

### H02 — Смена пароля не отзывает sessions и API tokens

**Доказательство:** [MdUserService](../apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java):219–232 меняет hash без invalidation; `KauthAuthService:184–200` в reset закрывает только sessions. Общий корректный `KauthUserSessionInvalidator:25–29` уже существует. Нарушение FR-AUTH-04. Reset-ветвь пока также заблокирована H01.

**Риск:** украденный credential остаётся действующим после смены пароля. **Исправление:** общий atomic invalidator для обоих flows, явный повторный login текущей сессии. **Тест:** 2 sessions + 2 API tokens, оба публичных flows, старые credentials получают 401, rollback не оставляет частичное состояние.

**Оценка:** 1–2 дня; Backend/Auth; reset regression зависит от H01.

### H03 — User detail/mutations обходят организационный data scope

**Доказательство:** [MdUserService](../apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java):123–156 применяет scope к списку, но `getUserById:117–121` использует unrestricted lookup; [MdUserController](../apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdUserController.java):53–59, 87–131 передаёт target ID в detail/update/block/unblock/delete без actor-aware row predicate.

**Риск:** роль с соответствующим action permission и ограниченным UNITS/SUBTREE/SELF может читать/менять пользователя соседней ветки по ID. Наличие permission annotation не заменяет scope. Это внутриорганизационный IDOR, не multi-tenancy defect.

**Исправление/тест:** единая actor-aware проверка до всех прямых reads/mutations; матрица ALL/SUBTREE/UNITS/SELF × actions, вне scope — 404 без mutation/audit/index side effects, внутри — успешный flow; проверить manager/role assignment targets.

**Оценка:** 3–5 дней; Backend/IAM + Security/SDET; согласовать исключения только для явно привилегированных system operations.

### H04 — Analytics раскрывает глобальные данные вне scope

**Доказательство:** [AnalyticsController](../apps/server/src/main/java/com/greenwhite/dwh/instance/analytics/controller/AnalyticsController.java):27–49 не передаёт actor, service:23–41 также; [AnalyticsRepository](../apps/server/src/main/java/com/greenwhite/dwh/instance/analytics/repository/AnalyticsRepository.java):21–168 агрегирует глобальные tasks/users/projects. ADR-0013 требует scope до aggregation.

**Риск:** scoped роль с analytics permission видит чужие названия проектов, логины/имена и агрегаты; отчёты не соответствуют организационной ответственности. **Исправление:** actor/scope predicate внутри всех четырёх SQL до GROUP/LIMIT, утвердить семантику проектов/пользователей без видимых задач. **Тест:** PostgreSQL dataset из двух sibling branches; direct API и browser limited-role; ALL/SUBTREE/UNITS/SELF для summary/trends/projects/workload.

**Оценка:** 4–7 дней; Backend + Security + SDET; зависимость — scoped aggregate contract.

### H05 — Proxy trust ломает rate limiting и Secure-cookie contract

**Доказательство:** [RateLimitFilter](../apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitFilter.java):122–129 доверяет первому X-Forwarded-For; оба nginx сохраняют клиентский префикс через `$proxy_add_x_forwarded_for`. [KauthAuthController](../apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/controller/KauthAuthController.java):132–150 определяет Secure/IP из request/forwarded headers; `apps/web/nginx.conf:45` перезаписывает X-Forwarded-Proto локальным `$scheme`. В документированном HTTPS-edge → HTTP-origin flow это `http`. [RateLimitService](../apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitService.java):23–32, 56–61 держит неограниченную map и делает полный cleanup при большой cardinality.

**Риск:** подставной IP обходит buckets/искажает security-аудит и увеличивает map; после >50k свежих ключей cleanup усиливает CPU cost. Cookie может выдаваться без Secure на предусмотренной production topology. Это source/topology вывод; живой внешний TLS experiment не выполнялся.

**Исправление/тест:** strict trusted-proxy chain и нормализованный client IP, явный Secure в production, bounded expiring limiter store; fake XFF/XFP/multi-hop/NAT cases, Set-Cookie через реальную proxy chain, memory/cardinality limit tests. Не доверять произвольным forwarded headers на direct ingress.

**Оценка:** 1–3 дня; Backend/Security + Platform; утвердить поддерживаемую edge/proxy topology.

### H06 — Глобальная идемпотентность сохраняет secrets, буферизует большие тела и имеет crash gap

**Доказательство:** [IdempotencyFilter](../apps/server/src/main/java/com/greenwhite/dwh/instance/config/idempotency/IdempotencyFilter.java):41–69 читает keyed mutating request полностью, до endpoint authorization; :97–115 кэширует 2xx–4xx responses. [IdempotencyRepository](../apps/server/src/main/java/com/greenwhite/dwh/instance/config/idempotency/IdempotencyRepository.java):67–83 сохраняет raw response JSON, включая issued API/webhook/channel/OTP secrets при таком запросе. `IdempotencyService:73–76` имеет cleanup method без scheduler. Reservation, business commit и completion — разные транзакционные границы. Angular API client не задаёт Idempotency-Key автоматически.

**Риск:** секрет, который должен показываться один раз, становится долговременной DB-копией; denied requests с уникальными keys накапливают records; near-limit multipart и cached responses масштабируют heap с concurrency. Crash после business commit до completion оставляет ambiguous PENDING и не гарантирует безопасного retry. Реальный OOM/дублирование business mutation не измерены; пути риска подтверждены статически.

**Исправление/приёмка:** явный allow-list не-secret operations, исключить auth/credential/denied/multipart endpoints; TTL, quota и size bounds; согласованная atomic operation/result или durable recovery для reservation. Client retry contract вводить только после server guarantees. Тесты: secret отсутствует в DB, denied не создаёт cache row, большие тела bounded, kill-after-commit/retry приводит к одному бизнес-результату. Обработку уже сохранённых secrets делать отдельным согласованным remediation, не удалять вслепую.

**Оценка:** 5–8 дней; Backend Platform + Security/DBA; H05 снижает amplification, но не устраняет дефект.

### H07 — Webhook DNS validation не привязана к фактическому network peer

**Доказательство:** [WebhookTargetPolicy](../apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/service/WebhookTargetPolicy.java):58–81 проверяет resolved IP и возвращает исходный URI; [KwhOutboxWorker](../apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/worker/KwhOutboxWorker.java):63–78 делает отдельное HTTP resolution. Redirects корректно отключены.

**Риск:** при контроле DNS allow-listed hostname и подходящем cache/TTL окне validated public IP и connected IP могут различаться. DNS rebinding exploit здесь не запускался; вывод — TOCTOU gap, а не подтверждённый доступ к конкретному private endpoint.

**Исправление/тест:** pin validated peer при сохранении TLS hostname verification либо controlled egress proxy с эквивалентной гарантией; deterministic DNS-flip fixture отклоняет private peer. **Допустимое временное ограничение:** webhook остаётся выключенным; включение блокируется отдельным security gate.

**Оценка:** 3–5 дней; Backend/Integrations + Security; до включения webhook обязательно.

### H08 — Параллельный parent PATCH может создать цикл задач

**Доказательство:** [MsTaskService](../apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java):252–288 — check-then-write без общей hierarchy lock; [MsTaskRepository](../apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsTaskRepository.java):233–271 — recursive UNION ALL без visited guard и отдельный cycle check.

**Сценарий/риск:** Tx1 A→B и Tx2 B→A проверяют прежнее состояние разных rows и могут оба commit; subsequent ancestors query не ограничен корректной древовидностью. Гонка статически подтверждена по транзакционным границам; новый latch experiment не выполнялся.

**Исправление/тест:** сериализация hierarchy mutations с повторным check после lock; defensive visited/depth bound в recursive read. Version одной строки недостаточна. Two-transaction test допускает один commit, второй получает conflict; legacy malformed cycle завершается bounded error. Existing data проверять read-only перед отдельным repair.

**Оценка:** 3–5 дней; Backend/Tasks + DBA/SDET.

### H09 — Deadline reminders всегда нарушают DB CHECK

**Доказательство:** [TaskDeadlineReminderWorker](../apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/worker/TaskDeadlineReminderWorker.java):63–71 передаёт type `deadline_warning`; `V001__init_schema.sql:310–319` допускает только `info/success/warning/danger`; `MsNotificationRepository:18–29` сохраняет значение напрямую. В последующих миграциях constraint не расширен. Outer catch:75–77 прекращает batch.

**Риск:** scheduled reminders не создаются, одна ошибка подавляет остальные записи. **Исправление:** допустимый `warning` либо осознанная forward schema migration; per-item isolation и error metric. **Тест:** real PostgreSQL, 2 задачи/3 получателя, повтор не дублирует, failure одного не подавляет других.

**Оценка:** 0.5–1 день; Backend/Notifications; затем оптимизация M03.

### H10 — Search скрывает отказ, теряет обновления и ненадёжно перестраивается

**Доказательство:** [TypesenseClient](../apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseClient.java):200–230 превращает collection exception в пустой результат; `SearchService:48–60` считает вызов успешным и не включает PostgreSQL fallback. [TypesenseIndexer](https://github.com/qahhor/dwh/blob/6c807488e9fb4a7476abbe56eca3ca84866766fb/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseIndexer.java):25–58 запускает async read до commit вызывающего service, без durable retry/outbox. [TypesenseSyncRunner](https://github.com/qahhor/dwh/blob/6c807488e9fb4a7476abbe56eca3ca84866766fb/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java):33–47 self-invokes `@Async`, поэтому startup фактически синхронный; :60–121 читает таблицы целиком и делает per-row HTTP. Ранний unhealthy result приводит к пропуску sync без фонового retry.

**Риск:** «ничего не найдено» вместо честной деградации, committed row отсутствует/устарел в индексе, большой dataset замедляет startup и расходует heap. Точные latency/heap thresholds не измерены.

**Исправление/тест:** сохранять индексирующее событие в business transaction, обрабатывать после commit с durable retry/reconciliation; propagate backend failure для scoped PG fallback; paginated bulk, resumable rebuild. Outage/timeout/partial collection, delayed commit, process restart и rebuild from empty Typesense должны сходиться с PostgreSQL. Сохранить действующую admin-only policy до отдельного scope-search решения.

**Оценка:** 5–10 дней; Backend/Search + SDET/SRE; предпочтительно отдельный индексный outbox, не механическое переписывание корректного webhook outbox.

### H11 — Медленная выгрузка удерживает DB connection и транзакцию

**Доказательство:** [ReportService](../apps/server/src/main/java/com/greenwhite/dwh/instance/report/service/ReportService.java):17–18, 32–157 пишет HTTP output внутри read-only JDBC transaction; нет export cap/fetch-size contract, PrintWriter может скрыть client abort. Proxy buffering выключен, большой read timeout, Hikari pool 20.

**Риск:** несколько медленных клиентов занимают общий pool; большие query results увеличивают heap/длительность transaction. Это подтверждённая связь ресурсов, но не измеренное исчерпание pool на целевой нагрузке.

**Исправление/тест:** немедленные row/time/concurrency limits, затем bounded extraction и artifact/job delivery вне долгой DB transaction; явный abort handling. Slow-reader/abort test фиксирует время освобождения connection, bounded heap и сохранение latency обычных API.

**Оценка:** 3–5 дней; Backend/Reports + SRE/DBA; SLO thresholds — B02.

### H12 — Сохранённый PostgreSQL volume без container обходит backup перед migrate

**Доказательство:** [deploy.ps1](../scripts/prod/deploy.ps1):28–43 и [deploy.sh](../scripts/prod/deploy.sh):28–40 считают отсутствие `docker compose ps -a -q postgres` признаком initial install. `docker compose down` сохраняет named volume. Документированный ручной workaround в `docs/guidelines/database-migrations.md:39–47` не делает script fail-closed.

**Риск:** миграция существующих данных без обязательной recovery point. **Исправление/тест:** определить реальный volume/data state до migrate; retained initialized volume без старого container — остановка и operator-controlled backup procedure. Отдельные tests для truly empty volume, retained volume и backup failure; оба shell варианта обязаны прекращать rollout.

**Оценка:** 1–2 дня; Platform + DBA; согласовать Compose project/volume mapping.

### H13 — Release pipeline и self-hosted deploy не обеспечивают единый immutable gate

**Доказательство:** [release.yml](../.github/workflows/release.yml):57–76 запускает ограниченный verify; build/publish не требует полного green CI на tagged SHA и scan каждого позже собранного multi-arch artifact. `test-no-default-egress.ps1` не вызывается ни одним workflow. [production Compose](../deploy/compose/docker-compose.prod.yml):3, 60, 110, 135, 174 и `.env.example:8–15` разрешают self-hosted tag fallback вместо пяти digest refs; deploy повторно делает pull.

**Риск:** подпись может удостоверять происхождение artifact, не прошедшего требуемые E2E/security gates; mutable tag после проверки выбирает другие bytes и ломает воспроизводимый rollback. Branch protection может уменьшать часть риска, но её состояние не подтверждено и scan release-time bytes не заменяет.

**Исправление/тест:** reusable full required workflow на exact RC SHA, draft→verify→publish, scan exact 5×2 platform artifacts, generated verified digest lock для **всех** production installs. Negative gates: failing E2E/secret/no-egress/CVE не публикует release; пустой/tag-only image ref отклоняется до pull; перемещение registry tag не меняет deployed bytes.

**Оценка:** 5–8 дней на объединённый workstream; Release/CI + Security; registry access, arm runner strategy, required-check policy.

### H14 — Origin-isolation preflight может проверить не фактический origin

**Доказательство:** [invoke-managed-preflight.ps1](../scripts/acceptance/invoke-managed-preflight.ps1):142–172 probe использует введённый origin IP, а :185–189 сверяет DNS name/proxied, но не соответствие content/IP.

**Риск:** неверный или устаревший недоступный IP даёт false PASS, пока реальный origin доступен напрямую. **Исправление/тест:** связать DNS A/AAAA/CNAME origin set с вводом, проверить все утверждённые адреса и fail closed при ambiguity/mismatch; tests wrong/stale IP, dual-stack/second address и реально открытый internal port.

**Оценка:** 1–2 дня; Platform/Security; уточнить разрешённые DNS topology types.

### H15 — RPO может быть ложно принят по filesystem mtime

**Доказательство:** [restore-combined.ps1](../scripts/prod/restore-combined.ps1):225–231 берёт DB capture timestamp из `LastWriteTimeUtc`, object timestamp — из manifest.

**Сценарий/риск:** старый валидный DB archive заново скачан и получает свежий mtime; при достаточно свежем object manifest и совместимом inventory вычисленный RPO может пройти, хотя DB recovery point старый. SHA-256 подтверждает bytes, не время capture.

**Исправление/тест:** authenticated/encrypted DB metadata manifest с backup/database ID и capture start/end, проверка пары DB/object, future/skew/retention policy; старый archive с новым mtime обязан FAIL RPO, tampered/future timestamp — FAIL, свежая пара — PASS.

**Оценка:** 2–3 дня; Platform + DBA; backward compatibility старых backups отдельно.

### H16 — Локальные backup/PostgreSQL artifacts не проходят свежий High-CVE gate

Свежий Trivy scan 2026-09-06 с обновлённой DB проверил существующие локальные образы, **не новые опубликованные release digests**:

| Image / local ID | High / Critical |
|---|---|
| server `e3e0627`, `64e8736f41dd8dd00ac3e318149630dd81373339d92fdedefbcfcb0837f8874c` | 0 / 0 |
| web `e3e0627`, `06edf497ab355088d131b5c38bf614d45bc61f3319420577424f494f40c48515` | 0 / 0 |
| Typesense hardened 27.1, `80561b40f5ebc98cb32bc8694fca21072c3f113675f7bfd6685d0b23c49658b8` | 0 / 0 |
| backup `dev`, `8eb3097ab094d39349f65d2a377cbf8174ddc267bd5808d6dc6c15c808964a7d` | 7 / 0 |
| PostgreSQL hardened 18 Alpine, `9cd42f17534990d311db550dac6f4839084f1827680f0cbdbcc8b8c71e55b903` | 7 / 0 |

Общий пакет `libuuid 2.42.1-r0`: CVE-2026-53612, 53613, 53614, 76642, 78408, 78409, 78410. **14 occurrences, 7 уникальных CVE**, не 14 app exploits. Alpine security DB указывает исправления util-linux в `2.42.3-r0`, для CVE-2026-78408 — `2.42.3-r1`: [Alpine v3.24 vendor secdb](https://secdb.alpinelinux.org/v3.24/main.json). [Upstream advisory для CVE-2026-53612](https://github.com/util-linux/util-linux/security/advisories/GHSA-g8wm-75wr-g2vh) описывает специальные mount/SUID/fstab условия; наличие библиотечного пакета не доказывает доступность такого пути в hardened runtime.

**Риск/решение:** текущие локальные support artifacts не проходят принятую package-severity политику без исключения. Rebuild из исправленного base, scan exact published digests обеих architectures; либо отдельный доказательный VEX с owner/expiry/conditions, если команда допускает такой процесс. Не добавлять безусловный ignore CVE. Scanner сообщал ограничение распознавания Alpine 3.24/EOL и отдельное unknown-severity advisory; нулевые High/Critical не означают отсутствие всех неизвестных/других рисков.

**Оценка:** 0.5–2 дня; Release/Security; доступность исправленного base и H13.

## 4. Средние/низкие улучшения

### 4.1. Medium

Оценки — инженерные дни, без независимого суммирования пересекающихся работ. P1 по умолчанию; явные security/acceptance подпункты поднимаются в P0 в разделе 5.

| ID | Где / факт → риск | Исправление и закрывающий тест | Оценка / роль |
|---|---|---|---|
| M01 | `tasks.component.ts:3464,3638`, `MsTaskService:581–589`: task type — свободный JSON code, used type удаляется; `MdCustomFieldService:99–131` не проверяет select/user_ref. Возможны orphan/invalid business values | Server code/option/reference/scope validation; used-type delete → 409; unknown value → 422; existing-data report до strict enforcement. FK — возможное дальнейшее решение, не самоцель ТЗ | 3–5, BE/FE/DBA |
| M02 | `api.service.ts:75–97` игнорирует canonical `errors[]`, хотя model его знает; `ApiException` status выбирается через ErrorCode и не соответствует имени factory. Потерян field-level feedback, возможны inconsistent 400/404/409 | Один typed Problem Details adapter, stable error/status matrix, one-toast/inline field errors; test `errors`, legacy format и precheck/race equivalence | 2–4, BE/FE |
| M03 | `MsTaskController:35–48`, `MdUserController:32–43`, `MsNotificationController:26–34`, list services: unrestricted limit; task participants per-ID queries/writes; reminder dedupe N+1. -1/0/MAX_INT → invalid pagination/500, огромные reads | Cap 1..approved max, overflow-safe fetch limit, array cardinality и bulk scope validation; negative limits, query counts и EXPLAIN на representative data | 3–5, BE/DBA |
| M04 | `MdUserRepository:258–287` COALESCE PATCH не различает absent/null; UI предлагает снять manager/avatar | Presence-aware PATCH по примеру Tasks; omitted сохраняет, null очищает, invalid reference отклоняется | 1–2, BE/FE |
| M05 | `KwhOutboxWorker:69–111`: retrieve бросает до status assignment; remote 429/503 записываются как 0 | Сохранить actual status через exchange/status handler; explicit retry matrix 408/429/5xx vs permanent 4xx; fixture 400/429/503/204 + HMAC/retry/dead-letter assertions | 1–2, BE/Integrations |
| M06 | `MsAnnouncementRepository:38–69`: read list только published, markAsRead допускает draft ID; после publish объявление уже прочитано | Atomic INSERT SELECT с state predicate; draft/archive/nonexistent не создают read, published идемпотентен; race archive/read определён | 0.5–1, BE |
| M07 | `localization-audit.mjs:9–17,41–52`, `ci.yml:55–60`: скан только component/Cyrillic, audit не запускается CI; raw copy в auth/api services и Settings/Analytics/Profile | Все runtime sources/templates, reviewed technical allow-list, negative fixtures русского service/английского UI literal, обязательный CI audit; second-language auth/error E2E | 2–4, FE/SDET |
| M08 | `app.routes.ts:17–74`, `auth.service.ts:44–70`, `settings.component.ts:124–297,837–870`: скрытая nav не означает route guard, login всегда ведёт Tasks, view-only Settings имеет Save | Declarative permissions/доступный landing/403 state, read-only controls и method guards; limited-role direct URL и no-PATCH tests. Не утверждается backend auth bypass | 2–4, FE/SDET |
| M09 | `analytics.component.ts:551–622,734–740,819–853`: прежний mobile overflow 390/499; 4 requests частично мутируют signals до Promise.all failure, loading отображается как 0 | Сначала измерить widest descendant, затем shrink/wrap; per-panel LoadState либо atomic snapshot, retry/stale/empty distinction; 320/390 px и 4 failure/out-of-order cases | 0.5–1.5 layout + 2–4 state, FE/QA |
| M10 | `settings.component.ts:43–124`, `app-shell.component.ts:27–46,124–137,688–698`: tabs без roving/Arrow keys, drawer без полного Escape/focus/ARIA contract; axe/mobile coverage узкий | Keyboard interaction tests, focus trap/restore и tablist semantics; route/state cases отдельно, чтобы Analytics failure не скрывал Users/Profile. AT impact вручную ещё не проверен | 2–4, FE/A11y/SDET |
| M11 | `pom.xml`, `angular.json`, `ci.yml:14–60,148–163`, Playwright reporter: нет доказательного JaCoCo/LCOV/diff coverage и always-upload success test reports | Sanitized JUnit/Surefire/Vitest + JaCoCo/LCOV с SHA/retention; critical branch/diff gates. Не приписывать проекту выдуманный процент coverage | 2–4, SDET/CI |
| M12 | `KauthApiTokenService:13–55`, `KauthSessionService:10–48`: issue/revoke token и close session не оставляют заявленные audit events; AuditCoverageTest исключает эти операции | Metadata-only audit events с actor/target/outcome/trace, без credential; tests successful/denied/self/admin paths | 1–2, BE/Security |
| M13 | `KauthSessionController:35–39`, `KauthSessionService:35–38`, repository:75–82: close по sequential ID не ограничен owner | Owner predicate в UPDATE и controlled admin path; A не может закрыть B session, self close успешен. Влияние — availability/session termination, не чтение secret | 0.5–1, BE/Security |
| M14 | `ConsoleMailProvider`, `ConsoleSmsProvider`, `ConsoleMessengerProvider`, `KauthOtpSender:70–80`: full recipient/subject/body содержат OTP/verification codes | Metadata-only default logs; явно изолированный dev delivery sink при необходимости; sentinel code/PII не появляется в captured logs | 0.5–1.5, BE/Security |
| M15 | `invoke-managed-preflight.ps1:234–244,296–309`: alert 2xx ≠ on-call receipt; lifecycle проверяет ID/enabled, recovery bucket — existence | Correlated delivered/acknowledged drill ID; сверка action/prefix/days, private/CORS/retention/access boundaries обоих buckets; broken downstream и wrong policy → FAIL | 2–5, SRE/Cloud/Privacy |
| M16 | `docker-compose.prod.yml` без memory/CPU/PID limits, `Dockerfile:58–64` JVM percentage; single-host noisy-neighbor risk | Профилировать и задать service limits/heap/headroom/disk alerts; capacity/soak с scanner/search/backup pressure. Числа не выбирать произвольно | 2–4 + soak, SRE/DBA |
| M17 | `OpenApiController:14–146`: ручные 7 paths/8 operations при 123 HTTP mapping annotations в source, неполные bodies/DTO/security/errors, JWT wording для opaque Bearer, localhost server | Полный generated/curated OpenAPI с actual request/response/error examples, multipart/keyset/auth и coverage/diff tests; link из docs index. 123 annotations — не точное число уникальных resolved API operations | 3–5, BE/Technical writer |
| M18 | `CODE_STYLE.md:17,72–80,128–135`: stale classpath/ТЗ/stylelint-eslint claims; AFTER_COMMIT пример смешан с atomic outbox; active-docs gate не охватывает эти противоречия | Обновить по фактическому build/current ТЗ; разделить transactional outbox и after-commit side effects; executable command/doc contract | 1–2, Tech Lead |
| M19 | Controllers возвращают nested repository records, ArchUnit simpleName rule пропускает их; `MsTaskService` имеет 11 collaborators и совмещает commands/catalog/participants/search coordination | Явные public DTO и package/API boundary tests, постепенное извлечение catalog/commands. Maven/ArchUnit PASS; доказанных циклов модулей нет, rewrite не требуется | 5–10 итеративно, Backend Lead |

### 4.2. Low

| ID | Находка / решение | Оценка |
|---|---|---|
| L01 | `ms_tasks.parent_task_id` без индекса для subtask query (`MsTaskRepository:212–226`). Сначала representative EXPLAIN; добавить migration index, если план подтверждает benefit | 0.5–1 день, DBA |
| L02 | `MsTaskCommentRepository:19–58`: POST с attachment IDs возвращает пустой список; duplicate IDs приводят к rollback. Вернуть фактические refs, определить dedupe/reject contract, real-DB tests | 0.5–1, BE |
| L03 | Self token/session list DTO сериализует внутренние hashes. Убрать storage fields из public DTO; сам по себе 256-bit hash не является доказанным пригодным credential | 0.5, BE |
| L04 | `SystemInfoServiceTest:23–58` и `KwhOutboxWorkerSecurityTest:61–99` используют тесные wall-clock timeouts. Это риск flakiness, не наблюдаемая частота failures. Использовать controllable synchronization/time и проверить stressed runner | 0.5–1, SDET |
| L05 | `scripts/security/scan-runtime-images.ps1:1–5` default Trivy tag не pinned digest. Зафиксировать scanner digest, сохранять DB freshness, reviewed upgrades | 0.5–1, Security/CI |
| L06 | `.codex/hooks.json` / `.claude/settings.json` содержат персональные absolute tool paths. Переносимые checked prerequisites + локальный override; не удалять рабочие hooks без owner review | 0.5–1, Repo maintainer |

Дополнительно dependency hygiene: web dev dependency `qs@6.15.3` имеет Moderate advisory, исправлено в 6.16.0: [GHSA-4mjr-xmp4-gh2g](https://github.com/advisories/GHSA-4mjr-xmp4-gh2g), [GHSA-x5fp-wj9c-mxmx](https://github.com/advisories/GHSA-x5fp-wj9c-mxmx). Обновить допустимую dependency chain/lock и повторить `npm ci`, test/build/audit; не применять `npm audit fix --force` автоматически. Это build/dev exposure, не автоматически runtime nginx vulnerability.

README, env examples, migration/rollback/incident/backup/restore guides и acceptance samples существуют; docs gate зелёный. Проблема не в полном отсутствии документации, а в полноте API-контракта, отдельных stale active instructions и недоказанности target runbooks. Seed/migration fixtures пригодны для bootstrap/tests, но не заменяют versioned representative dataset для scope/race/load/restore приёмки. Private production samples в Git добавлять нельзя.

## 5. План действий

`D0` — день согласования плана и назначения команды. Сроки ниже — ориентиры, не обещание даты релиза: доступность людей/провайдеров неизвестна. Оценки карточек — трудозатраты, а не календарные сроки; пересекающиеся работы не суммируются механически. Product owner может менять UX-приоритеты, но отсутствие AC-12 и неустранённый security boundary нельзя скрывать общим PASS.

| Приоритет / пакет | Владелец | Зависимости | Ориентир / условие завершения |
|---|---|---|---|
| P0-A: установить release freeze/RC policy, назначить owners, собрать annex inputs B02 | Release owner + Product/SRE/Privacy | Доступ к installation и business decisions | D0–D2: владельцы и missing-input list; запуск не разрешён до подписанного annex |
| P0-B: H01/H02/H03/H04/H05, M13/M14 и security events M12 | Backend/Auth/IAM + Security/SDET | Provider для reset; scope/proxy decisions | Начать D1; первая интеграция D5–D10 при 2 BE + QA, затем полная negative matrix |
| P0-C: H06/H08/H09/H10/H11 | Backend Platform/Tasks/Search + DBA/SDET | Atomicity/retry contracts, representative dataset | Параллельно D1; целевой exit D10–D20 при выделенных инженерах; crash/race/slow-reader tests обязательны |
| P0-D: H12/H13/H14/H15/H16, M15 | Release/Platform + Security/DBA | Registry/Cloudflare/target access, backup compatibility | D1–D10 при 1–2 Platform инженерах; exact-artifact и negative acceptance gates |
| P0-E: M09 mobile, M07 i18n gate; регрессии новых scope/auth fixes | Frontend + SDET | Backend contracts/fixtures | D1–D7 для UI/gate fixes; затем full browser на RC, а не только affected spec |
| P0-F: H07 и M05 при включении webhook | Integrations + Security | Validated-peer architecture | До первого включения; при default-off можно перенести реализацию, сохранив явный запрет и owner |
| P0-G: B01/B02 final acceptance | Release owner + SRE + DBA + SDET/Security | P0 fixes, approved thresholds/owners, disposable target | 3–7 дней после готового RC/tooling, включая 4h soak; target evidence и подписанный GO/NO-GO |
| P1: M01–M06, M08, M09 state model, M10–M11, M16–M18, Moderate dev dependency | Feature leads + FE/BE/SDET/SRE/Docs | P0 contracts и ресурсные профили | Следующий 1–2 спринта; M15/M16 и policy-related части обязательны раньше, если нужны для B02 |
| P2: M19, L01–L06, документальное архивирование/портируемые hooks | Tech Lead + Repo maintainer | Стабилизированные contracts, usage/EXPLAIN/owner review | 2–4 последующих спринта; небольшими PR, без rewrite и истории миграций |

При ориентировочной команде **2–3 Backend, 1 Frontend, 1 Platform/SRE и 1 QA/SDET** разумно зарезервировать **4–8 недель до повторного go/no-go**, с перекрытием workstreams и запасом на integration/target retries. Это плановая вилка, не статистический прогноз. Без такой команды или при задержке access/annex дату нужно пересчитать; обещание production «через пару дней» текущими доказательствами не поддерживается.

Каждый исправляющий PR: failing regression → минимальное исправление → локальные checks → peer/security review по риску → immutable CI evidence. Менять source/deploy в этом аудите не поручалось, поэтому перечисленные application/infra fixes **не реализованы**. Ошибки корректируются forward migrations; не менять применённые V001–V024 и не переписывать Git history.

Критерий GO: все P0 закрыты, либо узкое отключённое optional capability с owner/expiry; все применимые AC имеют SHA/digest-linked evidence; no unaccepted High; owner принял single-host availability boundary. Для критических auth/data-scope/recovery integrity defects одного документального «accepted risk» без реального ограничения экспозиции недостаточно.

## 6. Список находок по очистке репозитория

### 6.1. Сделано в этом аудите

- [.gitignore](../.gitignore): добавлены build/out/coverage/.cache, editor backup/swap suffixes, root `output/` и `.superpowers/`, DB dumps и encrypted/compressed SQL archives. Уже существовавшие target/node_modules/dist/IDE/OS/env/log/tmp/worktree исключения сохранены.
- [.dockerignore](../.dockerignore): исключены локальные audit/output/Graphify/agent/design материалы, nested secrets/backups/caches/build/temp, dumps, editor/OS junk. Root Dockerfile использует explicit COPY `pom.xml/libs/apps/server`: до исправления это был риск build-context exposure, **не доказанная утечка в final image**.
- [apps/web/.dockerignore](../apps/web/.dockerignore): дополнены env/secrets/backups/dumps, caches/build/temp/logs/editor/OS exclusions для web context.
- Проверены ignore sentinels, Dockerfile build checks и whitespace diff. `output/` (~146.3 MiB), в том числе используемые Node/Maven tools, остался на диске и теперь не предлагается к добавлению в Git.
- **Удалено 0 файлов.** `git rm`, purge, history rewrite, commit/push/deploy не выполнялись. Pre-existing dirty Graphify и девять untracked audit/patch drafts сохранены. Приложение на пользовательском порту 4200 не изменялось.

### 6.2. Что обновить, архивировать, удалить или оставить

| Объект | Решение | Почему / условие |
|---|---|---|
| Tracked build/dist/out/target/coverage/cache/tmp/OS/log/bak/old/swap junk | Удаление из index не требуется | На исходном Git-срезе стандартных tracked junk candidates не найдено; новые ignore rules предотвращают будущие additions |
| `output/`, local tools/test logs, `.superpowers/` | Игнорировать; локальная очистка по необходимости | Это воспроизводимые tools/evidence/scratch, не исходники. Не удалены: часть инструментов используется для проверки |
| `audit/00-master-improvement-plan-2026-09-03.md`, ранние audit/domain reports | Маркировать историческим SHA/датой и архивировать после review ссылок | Содержат старые counts 206/68/9 и уже исправленные findings; нельзя представлять как current release state. Audit-history имеет ценность, удалять wholesale нельзя |
| Девять untracked drafts: `architecture/cto/devops/performance/security/widgets-2026-09-05`, `widgets-2026-09-06`, `fixes/W-P01.patch`, `fixes/W-P11.patch` | Не публиковать автоматически; owner review, затем archive/delete по решению владельца | Пользовательские незавершённые материалы; не использованы как текущие требования/доказательства. Не blanket-ignore весь `audit/`, где есть tracked документы |
| `design-qa.md` с временными `C:/Temp` и `e2e/test-results` references | Историческая маркировка + durable sanitized evidence bundle | Временная ссылка не гарантирует доступность/связь с release SHA; не удалять сам historical evidence без решения |
| `CODE_STYLE.md` | Обновить, не архивировать действующий стандарт | M18: реальные build/lint/transaction rules должны совпадать с source/current ADR |
| `CONTRIBUTING.md` и правило Graphify | Обновить противоречие | Общий запрет commit graph output конфликтует с AGENTS policy, допускающей чистую regeneration intended source. Сначала согласовать одну политику |
| Tracked `graphify-out/*` | Сохранить knowledge baseline; перед будущим commit regenerate из clean intended source | Не автоматически generated junk: проект явно использует граф. Текущие unrelated dirty graph files не коммитить. Code не менялся, поэтому graph update в этом аудите не требовался |
| `.superdesign/resume.json` и init snapshots | Кандидат archive/ignore только после design-workflow owner review | Отличать local generator state от используемых design inputs; canonical design-system documents сохранить |
| ADR-0004/ADR-0007 и superseded решения | Сохранить как историю с superseded links | Уже обозначенное supersession — не основание уничтожать архитектурную трассировку |
| Flyway V001–V024, disabled legacy SSO/module metadata | Сохранить | Применённые migrations immutable; historical dummy disabled seed не равен действующему credential. `SsoProviderRepository` имеет consumer, не удалять как «orphan» |
| `ru.packaged-russian.ts`, localization bundles, lockfiles, LICENSE/NOTICE | Сохранить | Runtime fallback, reproducible dependency resolution и legal distribution inputs; generated происхождение не делает их мусором |
| `.codex/hooks.json`, `.claude/settings.json` | Обновить portability/local override | L06; работающие shared hooks не удалять вслепую |
| `.vscode/` | Оставить ignore; при необходимости shared extensions исправить parent negation | Текущее исключение `!.vscode/extensions.json` не разблокирует файл внутри целиком ignored parent; shared team config — отдельное решение |
| `.env`, private keys, dumps, customer exports | Никогда не добавлять; игнорирование + secret/artifact gate | Whole-history redacted scan не нашёл secrets с текущим allow-list. По именам tracked private key/dump/archive candidates не обнаружены; только безопасные env examples. Local ignored secrets не читались/не публиковались |

Пять точечных historical Gitleaks suppressions сохранять проверяемыми, не расширять до широкого отключения secret scan. Общее наличие dummy word `secret` в исторической disabled seed migration не оправдывает переписывание Flyway. Если будет обнаружен реальный опубликованный credential, сначала revoke/rotate и incident assessment, затем отдельный согласованный history remediation.

### 6.3. Проверяемость и материалы

Рабочие redacted scanner outputs и независимые заметки текущего аудита находятся локально в `C:/Temp/smartupcms-final-audit-20260906/`; они не включены в Git автоматически. Итоговые существенные факты, identifiers, counts, source links и ограничения перенесены в этот отчёт. Raw outputs нужны для повторной проверки, но не являются приложенным public release evidence bundle.

Последний browser evidence находится в `C:/Temp/smartupcms-projects-interaction-qa-20260906/accepted-final-browser-results` и `analytics-baseline-results`; он относится к предыдущему run и явно отделён от свежих unit/integration checks. Перед релизом заменить временные ссылки на approved sanitized, access-controlled, immutable evidence с SHA/digest metadata и утверждённой retention.

Практический следующий шаг: согласовать владельцев P0 и разрешить отдельный цикл исправлений. Повторный финальный аудит должен проверять закрывающие regressions каждого finding и фактический installation/release bundle, а не только увеличение числа зелёных тестов.

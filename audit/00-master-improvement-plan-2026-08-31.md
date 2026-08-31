# Release readiness audit and minimal improvement plan — 2026-08-31

> **Remediation update — 2026-09-01.** Ниже сохранён исходный audit snapshot. Application P0 и локальные release gates реализованы; актуальный статус и доказательства: [`fixes/00-implementation-tracker.md`](fixes/00-implementation-tracker.md) и [`evidence/verification-2026-08-31.md`](evidence/verification-2026-08-31.md). Текущий вывод: **GA — нет; ограниченный pilot — условно** после immutable commit, push и зелёного remote CI. Незакрытые внешние блокеры: automatic rollback, file/object backup, registry provenance, внешний TLS, SLO/observability.

Объект: текущий workspace `D:\Claude\dwh`, ветка `main`, base commit `f65431a`. Это не чистый RC: до аудита в дереве было более 160 изменённых/удалённых/untracked путей. Профильные доказательства: [architecture](architecture-2026-08-31.md), [quality](code-quality-2026-08-31.md), [documentation](documentation-2026-08-31.md), [testing](testing-2026-08-31.md), [security](security-2026-08-31.md), [UI/UX](widgets-2026-08-31.md), [DevOps](devops-2026-08-31.md), [performance](performance-2026-08-31.md), [verification](evidence/verification-2026-08-31.md).

## 1. Executive summary

1. **Готовность к релизу: НЕТ** — ни для GA, ни для pilot в текущем workspace.
2. Есть unauthenticated SSO bypass: публичный endpoint выдаёт локальную сессию на основе клиентского `email`.
3. Публичный moderation callback способен утвердить модуль и изменить permissions/admin role.
4. Markdown renderer допускает stored XSS через опасные URL schemes.
5. Global search обходит entity permissions/data scope и раскрывает user PII.
6. Production fleet не поднимет UI корректно: proxy идёт на порт 80, контейнеры слушают 8080; 443 опубликован без TLS listener.
7. Deploy scripts могут объявить успех без readiness/rollback, а backup failure проглатывается.
8. Release gates красные: backend ArchUnit fail, web-instance 12/53 unit failures; frontend CI unit tests не запускает.
9. Backup/restore не покрывает CP+files; подтверждённого restore drill, SLO/alerts/CD/promotion trail нет.
10. После закрытия P0 ограниченный single-node pilot возможен условно, только с письменно принятыми RPO/RTO и feature flags для незрелых функций.

### Карта системы

GreenWhite — database-per-client платформа с двумя runtime-контурами: Angular web-instance → Spring Boot instance API → PostgreSQL/Typesense/files и Angular web-cp → Spring Boot Control Plane → CP PostgreSQL. Instance отправляет heartbeat в CP; внешние интеграции — SMTP, Telegram, webhook и storage SPI. Платёжные интеграции и message broker не найдены. Модули Maven перечислены в `pom.xml:35-40`, назначение — в `README.md:1-5,21`, topology/data flows — `docs/ops/architecture-overview.md:9-62`, UI routes — `apps/web-instance/src/app/app.routes.ts:5-73` и `apps/web-cp/src/app/app.routes.ts:4-23`.

Локально миграции запускаются отдельными compose jobs, затем сервисы (`README.md:35-70`). CI выполняет Maven verify, два Angular build, clean-compose Playwright и security scans (`.github/workflows/ci.yml:16-166`). CD workflow не найден.

## 2. Release blockers (P0)

| ID | Блокер / риск | Доказательство | Конкретное минимальное действие | Критерий закрытия | Effort |
|---|---|---|---|---|---|
| P0-01 | Текущий workspace не является проверяемым RC | >160 dirty paths; ArchUnit 1 failure; web-instance 12 failures: `audit/evidence/verification-2026-08-31.md` | Зафиксировать scope/commit; исправить controller→repo и frontend regressions; прогнать clean gates | Clean checkout одного SHA: backend verify, 2 frontend unit/build, E2E/config/security — green | M |
| P0-02 | SSO authentication bypass | `SecurityConfig.java:29-38`; `OAuth2AuthController.java:28-55`; `OAuth2AuthService.java:71-118`; `V017__custom_modules_and_sso.sql:47-52` | В release branch выключить endpoint/providers/UI; полноценный OIDC вынести за gate | Anonymous forged email/code получает 404/403 или feature disabled; negative test в CI | S |
| P0-03 | Unsigned public module approval / RBAC mutation | `SecurityConfig.java:34`; `WebMvcConfig.java:21-27`; `MdCustomModuleController.java:78-82`; `MdCustomModuleService.java:84-141` | Выключить custom-module moderation feature/callback до аутентифицированного протокола | Anonymous/replayed callback не меняет status/forms/permissions; integration test | S |
| P0-04 | Stored XSS в markdown | `ui-markdown-view.component.ts:137-142`; editor `:461-466`; task usages `tasks.component.ts:526,602` | URL allowlist, убрать unsafe bypass, добавить UI CSP | `javascript:`, `data:`, encoded variants не создают executable link; tests + CSP header | M |
| P0-05 | Search authorization/data-scope bypass | `V003__rbac_role_matrix.sql:65-81`; `SearchController.java:22-29`; `SearchService.java:58-120`; `TypesenseClient.java:88-96,181-190` | До scoped implementation отключить чувствительные types неадминам; затем entity permission + scope filter | User без `iam.users.view` не видит users/PII; data-scope matrix test | M |
| P0-06 | Production UI/TLS topology неработоспособна | `nginx.prod.conf:66-80`; web nginx `:3-4`; fleet compose `:43-47,113-120,202-208` | Исправить upstream 8080; определить TLS termination; production-compose smoke | Clean host: HTTP→HTTPS policy, `/`, `/cp/`, оба API/health отвечают ожидаемо | M |
| P0-07 | Deploy/backup false-success, нет rollback | `deploy.sh:23-46`; `deploy.ps1:23-38`; `backup.sh:7-24` | Backup failure fail-closed; exact health allowlist + HTTP/schema smoke; rollback previous digest | Injected unhealthy/missing service завершает deploy nonzero и восстанавливает previous; verified backup ID записан | M |
| P0-08 | CI пропускает frontend unit failures и production topology | `.github/workflows/ci.yml:56-66,99-141` | Добавить обе unit suites/typecheck и production-compose smoke; сделать required checks | PR с намеренно падающим unit/proxy test не mergeable | S |
| P0-09 | Security artifact текущего RC не подтверждён | локально gitleaks/trivy/full backend suite не выполнены; CI config `:143-166` | Запустить security job/SBOM на clean RC, сохранить results и triage | 0 unwaived critical/high runtime findings, 0 secrets; waiver имеет owner/expiry | S |

## 3. Risk register

Шкала: вероятность и влияние — Low/Medium/High/Critical. Остаточный риск принимается только именованным владельцем.

| Риск | Вероятность | Влияние | Mitigation | Остаточный риск / owner |
|---|---|---|---|---|
| Account takeover через SSO | High | Critical | Disable now; later verified OIDC+PKCE/state/nonce/claims | Low после tests; Security owner не назначен |
| Privilege escalation через module callback | High | Critical | Disable now; later mTLS/HMAC+replay protection+state machine | Low; CP/Instance owner не назначен |
| Stored XSS | High | Critical | Sanitize schemes, no unsafe bypass, CSP, regression tests | Low; Frontend/Security owner не назначен |
| Cross-scope PII disclosure через search | High | High | Entity permission + DB/Typesense scope filter + IDOR tests | Low/Medium; IAM owner не назначен |
| Production outage/502/TLS failure | High | Critical | Fix ports/TLS contract; test actual fleet compose | Low; Platform owner не назначен |
| Deploy declares success on broken version | High | Critical | Fail-closed health, immutable digest, automatic rollback test | Medium; Deployment owner не назначен |
| Data loss after failed release/host | Medium | Critical | Full backup set, encryption/offsite, automated restore drill | Medium until WAL/object durability; Business must accept RPO |
| Webhook SSRF/secret disclosure | Medium | High | Secret redaction/rotation, URL/IP policy, timeout/no redirect | Low/Medium; Integration owner not assigned |
| Duplicate side effects under concurrency | Medium | High | Atomic idempotency/outbox claims; one-replica invariant meanwhile | Low under enforced single replica |
| Performance collapse with data growth | Medium | High | Representative dataset, EXPLAIN/load baseline, search/index decision | Unknown until workload/SLO supplied |
| Supply-chain drift | Medium | High | SHA/digest pinning, SBOM/provenance, dependency bot | Low/Medium |
| Operational blind spot | High | High | JSON masked logs, golden signals, alerts/runbooks, agreed SLO | Medium until production telemetry exists |
| Documentation-driven operator error | High | Medium | Supersede stale audit/docs; executable runbook checks | Low |

## 4. План работ на 2–4 недели

Scope rule: только блокеры, regression protection и release contour; SSO/custom-modules можно отключить вместо расширения реализации.

### Неделя 1 — стабилизация и безопасность

- День 1: объявить один RC SHA, заморозить feature scope, назначить owners P0; убрать dirty/unreviewed изменения из RC обычным review-процессом, без потери работы.
- День 1–2: disable SSO и custom-module callback/providers/UI; добавить negative integration tests.
- День 2–3: исправить markdown sanitizer/CSP и search authorization/data scope; security regression matrix.
- День 3: закрыть ArchUnit dependency violation и 12 frontend test failures.
- День 4: включить frontend unit/typecheck в CI; выполнить Maven verify, npm prod audit, gitleaks, Trivy, SBOM на clean RC.
- День 5: исправить production UI ports/TLS contract; первый clean production-compose smoke.

Результат недели: все P0 application/security gates green, опасные незрелые функции выключены, production stack отвечает end-to-end.

### Неделя 2 — тесты, наблюдаемость и релизный контур

- Сделать deploy fail-closed: exact service health, API/schema smoke, immutable image digest, recorded previous version, rollback injection test.
- Полный backup set: instance DB + CP DB + file/object manifest; checksum, encryption/offsite policy; automated restore drill и измеренные RPO/RTO.
- Добавить 6 critical E2E/contract flows: auth/session, RBAC/IDOR/search, task+idempotency, files, audit, CP heartbeat/module rejection.
- Ввести JSON log masking, correlation propagation, 4 golden signals и 5 actionable alerts с owner/runbook.
- Сверить README/runbooks/OpenAPI с фактическим RC; пометить старые readiness claims superseded.

Результат недели: deploy/rollback/restore проверены на чистом окружении; эксплуатация видит и диагностирует отказ.

### Неделя 3 — надёжность и performance baseline (если нужен внешний pilot)

- Атомарный claim для idempotency и обоих outbox; до этого enforce `replicas=1`.
- Representative dataset + EXPLAIN для global search/analytics/top queries; webhook timeouts/SSRF hardening.
- Зафиксировать pilot SLO/SLI, capacity envelope и alert thresholds по измерениям.
- Провести release rehearsal из registry digest с оператором и заполненным rollback/restore evidence.

### Неделя 4 — только при SLA/GA

- Закрыть принятые P1 security/supply-chain/PII gaps: action SHA, image digest/provenance, retention/erasure matrix.
- Повторный threat-model review и go/no-go с Product, Security, Operations.
- Не реализовывать HA/WAL/мультиязычность/новую дизайн-систему, если это не требуется согласованным tier/SLO.

## 5. Definition of Done для релиза

- [ ] Есть один immutable RC SHA и image digests; build provenance/SBOM привязаны к ним.
- [ ] `mvn -B verify` — 0 failures; оба Angular `npm test -- --run` и production builds — 0 failures.
- [ ] E2E/config/artifact-security — green на clean environment; critical flow matrix покрыта и не содержит skip.
- [ ] SSO и module callback либо безопасно реализованы по threat model, либо недоступны во всех release layers (API/config/UI/seed).
- [ ] Markdown dangerous-scheme tests и CSP check green; search IDOR/data-scope tests green.
- [ ] Gitleaks: 0 secrets; Trivy/runtime dependency scan: 0 unwaived Critical/High; waiver имеет owner, reason, expiry.
- [ ] Production-like compose из release artifacts проходит `/`, `/cp/`, API, health, migrations и TLS policy.
- [ ] Injected failed deployment завершён nonzero и автоматически возвращает previous immutable digest.
- [ ] Backup содержит обе DB и file/object manifest; checksum/encryption проверены; isolated restore drill прошёл, измеренные RPO/RTO ≤ письменно согласованных.
- [ ] Metrics/logs/alerts доступны; каждый P0 alert имеет owner и проверенный runbook; PII/secret masking test green.
- [ ] Документация запуска, deploy, rollback, restore, known limitations и API соответствует RC; broken internal links = 0.
- [ ] Product/Engineering/Security/Operations подписали go/no-go и явно приняли single-node/retention/SLO residual risks.

## 6. Вопросы и нехватка данных

1. Какой exact commit/набор dirty changes является кандидатом в релиз? Текущий workspace не позволяет это установить.
2. Какой релизный tier: internal, ограниченный pilot или GA? Сколько клиентов/пользователей, объём DB/files, peak RPS и допустимые latency/error rate?
3. Какие договорные SLO/RPO/RTO? Документы одновременно указывают daily backup/RPO 24h и Standard RPO ≤15 min.
4. Где реально завершается TLS, кто владеет DNS/cert rotation, registry, production hosts и secret manager?
5. Кто владельцы Security, IAM/search, CP protocol, deployment, backups и on-call? В репозитории назначения не найдены.
6. Должны ли SSO, custom modules и analytics входить в этот релиз? Без подтверждения безопасный выбор — feature disabled.
7. Какие требования по PII: юрисдикция, legal basis, retention, export/delete, backup erasure и audit retention?
8. Где хранятся file blobs в production: local volume, S3/Garage или другое? Какой backup/durability contract?
9. Есть ли branch protection/environment approvals и доступ к результатам последнего CI security job? Локально их состояние проверить нельзя.
10. Есть ли production-like dataset и разрешённый isolated E2E/load environment? Текущую общую dev-БД аудит намеренно не менял.
11. Кто и когда выполнял последний restore/rollback drill? Evidence artifact не найден.
12. Какой API считается внешним контрактом? Текущий manual OpenAPI покрывает только часть controllers.

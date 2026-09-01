# Fleet Foundation Control Plane contract — verification evidence

Дата проверки: 2026-09-01. Реализация проверена на commit `cf752f4a58464e94574138029d9bc145dfa3dce7` в ветке `codex/fleet-foundation-cp-contract`. Этот SHA включает код и regression fix; текущий evidence-коммит меняет только документацию.

## Решение по срезу

- **Локальный Fleet Foundation contract/security slice: PASS.** Backend, оба frontend, clean-slate Compose, live API smoke, E2E, production config и runtime-image security gate зелёные.
- **Общий release status: In review.** Remote GitHub CI ещё должен подтвердить отправленный immutable SHA. Workflow запускается на `push` только для `main` либо на `pull_request` (`.github/workflows/ci.yml:5-9`).
- Проверен только первый Control Plane foundation slice. Это не подтверждение готовности Hetzner/Cloudflare production-инфраструктуры или полного автоматического rollout.

## Воспроизводимые проверки

| Gate | Команда / среда | Результат |
|---|---|---|
| Полный backend | `maven:3.9.11-eclipse-temurin-25`, `mvn -B verify`, Docker socket и `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` | **PASS**, exit 0; 56 test reports, **288/288**: core-types 2, platform-common 3, instance 160, control-plane 123; failures/errors/skipped = 0. PostgreSQL Testcontainers 18.6 реально запущен. |
| web-instance | `node:24.15.0-alpine`, `npm ci && npm test && npm run typecheck && npm run build` | **PASS**, 23 files, **55/55**, typecheck/build green, npm audit 0. |
| web-cp | Та же pinned Node-команда | **PASS**, 3 files, **8/8**, typecheck/build green, npm audit 0. |
| Production config | `scripts/prod/test-release-config.ps1` | **PASS**, exit 0: NGINX config valid; Bash syntax valid после host-neutral CRLF normalization. `*.sh` закреплены как LF в `.gitattributes`. |
| Clean-slate deployment | Изолированный Compose project `dwhfleetverify`: `down -v --remove-orphans`, fresh `build --pull`, затем отдельные `migrate` и `migrate-cp`, `up -d --wait` | **PASS**. Instance schema V001→V018; CP schema V001→V006; db, db-cp, app, control-plane, web, web-cp healthy, Typesense running. Обе UI и оба management health endpoint вернули HTTP 200. Первичный `up` до migration jobs ожидаемо заблокирован schema gate, что подтверждает fail-closed порядок deploy. |
| Live Control Plane API | `scripts/dev/test-cp-api.ps1` с синтетическими локальными credentials | **PASS**, **11/11**: login/session, registration, one-time enrollment, heartbeat, tenant isolation, backup report и empty desired state. Secret/token значения скрипт не печатает. |
| Runtime images | `scripts/security/scan-runtime-images.ps1`, Trivy 0.74.0, свежие vuln/java DB, `HIGH,CRITICAL --ignore-unfixed` | **PASS**, **0 HIGH/CRITICAL** в 7/7: instance, control-plane, web, web-cp, hardened PostgreSQL, Typesense и NGINX proxy. |
| Browser E2E | `scripts/dev/test-e2e.ps1` против clean-slate stack | Первая попытка: 7/8 и найден устаревший backup heading contract. Исправлено в `cf752f4`; targeted 2/2 и полный повтор **8/8 PASS**. Config tests 3/3, TypeScript, credential-artifact redaction PASS. |
| Graph integrity | `graphify update .`; `graphify query "Control Plane instance credential release desired state deployment history"` | Rebuild и query успешны: 4 893 узла, 12 382 связи; запрос нашёл credential/release/target/deployment implementation и tests. CLI 0.9.51 сообщил mismatch с project skill 0.9.13, поэтому несвязанные labels/cache rewrites не включены в commit; `last_query_stamp` удалён. |
| Git hygiene | `git diff --check`; explicit staging only | **PASS**; generated Graphify cache не staged. |

## Schema и fail-closed security paths

| Сценарий | Ожидаемое и фактическое поведение | Доказательство |
|---|---|---|
| Empty CP schema | V001→V006 применяются целиком | `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/FlywayControlPlaneScriptIntegrityTest.java:10`; full Maven gate и clean Compose migration. |
| Upgrade legacy CP | V001→V005 fixture обновляется до V006; legacy credential переносится в hashed credential table | `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/db/CpFleetFoundationMigrationTest.java:10`; full Maven gate. |
| Enrollment replay | Повтор consumed token → HTTP 401; runtime credential остаётся только в памяти smoke-процесса | `scripts/dev/test-cp-api.ps1`; live checks 5–6; concurrency test `CpInstanceCredentialRepositoryIntegrationTest.java:87`. |
| Revoked credential | Backup POST → HTTP 401 `instance_credential_invalid`; write count = 0 | `CpBackupOwnershipIntegrationTest.java:146`; full Maven gate. |
| Cross-client backup body | Подмена client/instance → HTTP 400; accepted report связывается только с authenticated principal | `CpBackupOwnershipIntegrationTest.java:73`; live check 10. |
| Oversized body | 16 385 bytes → HTTP 413 `instance_payload_too_large`; exact 16 KiB accepted | `CpInstanceRequestGuardFilterTest.java:101-130`. |
| Unknown-length/chunked body | Не обходит лимит; 16 385 bytes → HTTP 413 | `CpInstanceRequestGuardFilterTest.java:150`. |
| Heartbeat rate | Третий heartbeat/minute → HTTP 429, `Retry-After: 60`, `instance_rate_limited` | `CpInstanceRequestGuardFilterTest.java:130`. |
| Telemetry allowlist | PII, arbitrary maps, negative counters и unknown nested field → HTTP 400 и 0 inserts | `CpHeartbeatContractIntegrationTest.java:193`. |
| Revoked release assignment | Assignment → `release_not_assignable`; target rows не создаются | `CpTargetServiceIntegrationTest.java:164`. |
| Conflicting deployment replay | Только byte-exact replay idempotent; иной status/sequence → `deployment_event_conflict`; event count не меняется | `CpDeploymentRepositoryIntegrationTest.java:68`. |

## Известный оставшийся scope

- Нет подтверждённого remote CI результата для этого SHA до push/PR; локальные результаты не заменяют required checks.
- Release catalog принимает только уже verified digest metadata, но registry verification/signature/provenance adapter и build promotion pipeline в этот slice не входят.
- Desired state и deployment history реализуют безопасный declarative contract; фактический instance rollout agent, публичный `VERIFIED` transition и enforcement `minimumAgentVersion` ещё не реализованы.
- Hetzner IaC, отдельные CI runners, production secrets, внешние DNS/TLS/Cloudflare policies и network segmentation не найдены в этом slice и не проверялись.
- Cloudflare R2/S3 для управляемой инфраструктуры, customer-selected object storage adapter, file/object backup, automatic previous-digest rollback и DR остаются отдельными release работами.
- Production SLO/SLI, alert ownership, centralized telemetry и representative load test не подтверждены.

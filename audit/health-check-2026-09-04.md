# SmartupCMS health check — 2026-09-04

**Метод:** focused repository health audit

**Maturity:** Growing / pre-release

**Release readiness:** **Нет** — локальные исправления CI подтверждены, но текущий
remote `main` ещё красный, а installation-specific acceptance evidence отсутствует.

Этот snapshot заменяет `health-check-2026-09-03.md` как текущую оценку. Он не
изменяет требования: нормативными остаются `docs/technical-specification.md` и
действующие ADR.

## Подтверждённая карта системы

- Единый open-source runtime: Angular SPA (`apps/web`) и Java/Spring Boot
  модульный монолит (`apps/server`) с общими библиотеками в `libs/`.
- PostgreSQL 18 — authoritative store; Typesense 27.1 — производный поисковый
  индекс; файлы хранятся в `local_disk` или S3-compatible provider.
- Поддерживаемая production-топология — Docker Compose с отдельными `migrate`,
  ClamAV и зашифрованным backup. Единственный опубликованный origin — `web`.
- Smartup-managed профиль целится в Cloudflare edge и Cloudflare R2;
  self-hosted оператор выбирает совместимый edge и storage.

Evidence: `README.md`, `pom.xml`, `docker-compose.yml`,
`deploy/compose/docker-compose.prod.yml`, `docs/adr/ADR-0014-unified-open-source-runtime.md`,
`docs/ops/architecture-overview.md`.

## Реестр рисков — ровно 10

| ID | Наблюдение | Риск | Доказательство | Минимальная рекомендация | Effort | Priority |
|---|---|---|---|---|---|---|
| R-01 | Последний remote CI для `main`/`1b24d3f` завершился ошибкой в backup-status и Trivy. Локально причины исправлены, но изменения ещё не прошли remote CI. | Нет immutable зелёного baseline; релиз нельзя воспроизводимо аттестовать. | `.github/workflows/ci.yml`; `pom.xml`; `scripts/prod/test-backup-status.ps1`; [GitHub run 33833803385](https://github.com/qahhor/dwh/actions/runs/33833803385) | Отправить узкий patch, дождаться полного green run на точном SHA и сохранить URL/SHA в release evidence. | S | P0 |
| R-02 | Числовые p95/p99/error/saturation thresholds и распределение нагрузки по установкам не утверждены. | Невозможно доказать пригодность для заявленных 500 registered/100 active users и 50 ГБ upload/month. | `docs/technical-specification.md:178`; `docs/technical-specification.md:179`; `docs/ops/production-launch-checklist.md:98` | Утвердить SLO и профиль данных, затем выполнить reproducible load + soak на release image. | M | P0 |
| R-03 | В репозитории нет заполненного installation annex с controller/processor, legal basis, retention и DSAR/delete/hold owners. | Непроверяемое обращение с PII и невозможность принять production юридически и операционно. | `docs/technical-specification.md:158`; `docs/technical-specification.md:220`; `docs/security/threat-model.md:55` | Заполнить и подписать один annex на установку; выполнить delete/export verification по DB, Typesense, objects и применимой backup policy. | M | P0 |
| R-04 | Compose экспортирует health/metrics, но внешние collection, dashboards, alert transport и named on-call не подтверждены. | Сбой или исчерпание ресурсов может остаться незамеченным до обращения пользователя. | `docs/technical-specification.md:196`; `docs/ops/architecture-overview.md:105`; `docs/ops/production-launch-checklist.md:80` | Подключить внешний сбор, определить alerts/on-call и доказать доставку тестового alert. | M | P0 |
| R-05 | Backup/restore tooling и checksum-контракты есть, но evidence изолированного совместного восстановления DB и object bytes отсутствует. | Backup может оказаться формально успешным, но непригодным для полного восстановления. | `docs/technical-specification.md:216`; `docs/ops/production-launch-checklist.md:40`; `docs/ops/production-launch-checklist.md:43` | Провести disposable DB+objects restore drill, сверить checksums/репрезентативные записи и зафиксировать фактические RPO/RTO. | M | P0 |
| R-06 | Целевая стратегия Hetzner + Cloudflare edge + R2 описана, но domain/region/WAF/origin-lock/R2 lifecycle и smoke evidence конкретной установки не найдены. | Production boundary и storage semantics остаются непроверенными на целевой инфраструктуре. | `docs/technical-specification.md:169`; `docs/technical-specification.md:217`; `docs/ops/deployment-guide.md:150` | Выполнить installation acceptance: внешний port scan, origin lock, upload/download/delete R2, backup copy и rollback smoke. | M | P0 |
| R-07 | Ключевые Angular components остаются слишком крупными: tasks — 3136 строк, users — 1579, roles — 1354. | Высокая стоимость изменений и риск UI-регрессий в критических потоках. | `apps/web/src/app/features/tasks/tasks.component.ts`; `apps/web/src/app/features/iam/users/users.component.ts`; `apps/web/src/app/features/iam/roles/roles.component.ts` | Итеративно выделять presentational blocks и facade, начиная с tasks; routes/API не менять, каждый шаг закрывать focused tests и Playwright. | L | P1 |
| R-08 | Unit/integration/E2E gates существуют, но JaCoCo/LCOV baseline и changed-lines threshold не настроены. | Количество тестов может расти без защиты новых критических ветвей auth/RBAC/files/migrations. | `pom.xml` — JaCoCo не найден; `.github/workflows/ci.yml` — coverage gate не найден; `apps/web/package-lock.json` содержит optional coverage engines, но CI их не запускает. | Сначала публиковать baseline без блокировки, затем включить diff coverage для критических packages. | M | P1 |
| R-09 | Remote CI предупреждает о принудительном переходе Node 20 actions на Node 24. Дополнительно `npm ci` сообщил об одной moderate issue; production-only `npm audit` не завершился в окно проверки, поэтому package/scope не подтверждены. | Будущее изменение runner может сломать CI, а неклассифицированный moderate finding останется без owner/SLA. | `.github/workflows/ci.yml:18`; `.github/workflows/ci.yml:20`; `.github/workflows/release.yml:44`; `apps/web/package-lock.json`; remote run annotations 33833803385 | В отдельном dependency PR обновить actions на официальные Node 24-compatible releases и воспроизводимо классифицировать npm finding; сохранить full-SHA pins и release contract. | S | P2 |
| R-10 | Rate limits и SSE registry находятся в памяти процесса; single-host limit документирован, но machine-readable запрета `server > 1 replica` не найдено. | Случайное масштабирование даст разные rate-limit buckets и неполную realtime-доставку. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitService.java:18`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/sse/MsSseRegistry.java:20`; `docs/ops/architecture-overview.md:106` | Явно зафиксировать one-server invariant в deployment validation/system info; distributed state проектировать только после утверждения HA/SLO. | M | P1 |

## Что уже улучшено локально

- Embedded Tomcat выровнен на 11.0.25 вместо уязвимой 11.0.24; Maven dependency
  tree и полный `mvn -B verify` подтверждают единый набор `core/el/websocket`.
- Trivy больше не сканирует Maven source tree без разрешённых версий: backend
  проверяется по CycloneDX SBOM, web application/build и E2E toolchain —
  отдельно; обе npm-проверки включают dev dependencies.
- Backup-status contract использует Docker volume и тот же UID/GID 10001, что
  production images; Linux CI больше не зависит от root-owned host bind file.

## Проверки текущего локального patch

- Maven reactor: **214 tests**, 0 failures/errors/skipped, `BUILD SUCCESS`.
- Trivy 0.70.0: backend SBOM, web с dev dependencies и E2E dev toolchain —
  **0 HIGH/CRITICAL**.
- Web на Node 24.15.0: **26 files / 68 tests**, typecheck и production build.
- E2E contracts: **3/3**, typecheck и artifact-security.
- 7/7 architecture/docs/release/production contracts — green.
- `actionlint` 1.7.7 — green.

Одна moderate issue из сводки `npm ci` остаётся неклассифицированной: повторный
`npm audit --omit=dev` не завершился в отведённое окно. Trivy с включёнными web
dev dependencies при этом не обнаружил HIGH/CRITICAL; это не доказывает
отсутствие moderate finding и оставляет его в R-09.

Это локальное evidence рабочего дерева от 2026-09-04. Оно не заменяет remote CI
на immutable commit и не закрывает R-01.

## Приоритет следующего шага

1. Закрыть R-01 одним узким commit и получить полностью зелёный remote CI.
2. До production закрыть R-02…R-06 installation-specific evidence.
3. Параллельно, без расширения продукта, начать R-07/R-08; R-09/R-10 не должны
   блокировать текущую локальную стабилизацию, но обязаны иметь владельца.

Карточки реализации: `audit/fixes/R-01.md` … `audit/fixes/R-10.md`.

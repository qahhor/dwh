# Release implementation tracker

Source of truth: [каноническое ТЗ](../../docs/technical-specification.md) и
[ADR-0014](../../docs/adr/ADR-0014-unified-open-source-runtime.md). Статус
обновлён 2026-09-03 для единого open-source SmartupCMS. Control Plane/Fleet
Foundation удалены из
поддерживаемого runtime и остаются только историческим evidence. `Verified`
разрешён только для immutable pushed SHA с зелёным remote CI и clean deployment;
поэтому локально реализованные пункты остаются `In review`.

| ID | Карточка | Priority | Effort | Owner | Status | Текущее evidence / остаток |
|---|---|---|---|---|---|---|
| P0-01/P0-08 | [T-01 release gates](T-01-release-gates.md) | P0 | M | TBD | In review | Pushed SHA `df84019`: последний полный локальный прогон backend 206/206, frontend 68/68 и clean E2E 9/9 green; remote run текущего SHA не подтверждён |
| P0-02 | [S-01 SSO](S-01-sso-disable-or-verify.md) | P0 | S/M | TBD | In review | Публичный exchange удалён, providers выключены V018; полноценный OIDC не входит в release scope |
| P0-03 | [S-02 module callback](S-02-module-callback-hardening.md) | P0 | S/M | TBD | In review | Публичный moderation callback удалён; mutation остаётся только в authenticated CP flow, service audit tests green |
| P0-04 | [S-03 markdown XSS](S-03-markdown-xss.md) | P0 | M | TBD | In review | Unsafe bypass удалён, URL sanitizer и strict CSP добавлены; unit/build/E2E green |
| P0-05 | [S-04 search authorization](S-04-search-authorization.md) | P0 | M | TBD | In review | Global search fail-closed для non-wildcard admin до реализации data-scope filtering |
| P0-06 | [D-01 production topology](D-01-production-topology.md) | P0 | M | TBD | In review | Unified web/server/PostgreSQL/Typesense/backup topology; release-config и V018→V019 production rehearsal green; внешний Hetzner/Cloudflare TLS не подтверждён |
| P0-07 | [D-02 deploy recovery](D-02-deploy-recovery.md) | P0 | M | TBD | In progress | Encrypted V018 backup before V019 and HTTP 200 green; target restore, automatic previous-digest rollback и file/object recovery отсутствуют |
| P0-09 | Security artifact | P0 | S | TBD | In progress | Текущий Trivy filesystem scan не завершился; Gitleaks требует узкой классификации legacy test fixture; нужен green remote artifact/signature от stable release tag |
| P0-11 | File ingress contract | P0 | S | TBD | Not started | 50 MB задан только в service; в активных Spring multipart и web Nginx limits нет; нужны 49 MB success и >50 MB 413 через production origin |
| P0-12 | File pipeline isolation | P0 | M | TBD | Not started | Storage/AV/copy выполняются внутри `@Transactional`; требуется короткая DB finalization и concurrency/pool test |
| P0-13 | Production file scanner | P0 | S/M | TBD | Not started | `DWH_FILE_SCANNER_REQUIRED` default=false; release должен fail-closed или отключать upload, EICAR/outage/cleanup tests обязательны |
| P0-14 | Task/file data scope | P0 conditional | M | TBD | Not started | Object-level read policy не зафиксирована; Product/Security decision и cross-role direct-API suite обязательны |
| P0-15 | Target acceptance | P0 | M | TBD | Not started | Hetzner/Cloudflare/R2, alerts, 100-user load/soak и combined DB+objects restore не подтверждены |
| CP-FF-01 | Historical Fleet Foundation evidence (удалено в `c6a1181`, доступно только в предшествующей истории Git) | — | — | — | Superseded | Control Plane/Fleet runtime удалён; historical evidence не является частью текущего release contour |
| P1 | [S-05 webhook hardening](S-05-webhook-hardening.md) | P1 | M | TBD | In review | Fail-closed, exact allow-list, SSRF ranges, redirect/timeout/redaction/secret-once; focused and full backend tests green; target egress firewall остаётся installation control |
| P1 | [W-01 a11y consistency](W-01-a11y-consistency.md) | P1 | M | TBD | In review | Семантика/labels/focus/states исправлены; axe critical/serious, keyboard и narrow viewport включены; clean browser E2E 9/9 |
| P1 | [P-01 performance baseline](P-01-performance-baseline.md) | P1 | M | TBD | Not started | Нет representative dataset и согласованного SLO |
| P1 | [A-01 docs source of truth](A-01-documentation-source-of-truth.md) | P1 | M | TBD | In progress | Run/deploy/backup/restore/CI исправлены; полный broken-link/API contract audit не закрыт |
| P1-HYGIENE | Repository hygiene | P1 | S | TBD | In review | [Design](../../docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md), [plan](../../docs/superpowers/plans/2026-09-03-repository-cleanup-and-documentation-refresh.md), [ТЗ](../../docs/technical-specification.md), [ADR-0014](../../docs/adr/ADR-0014-unified-open-source-runtime.md), [public-docs contract](../../scripts/docs/test-public-docs.ps1), [repository-hygiene contract](../../scripts/docs/test-repository-hygiene.ps1) и [health report](../health-check-2026-09-03.md) фиксируют единый runtime; Task 7 local verification на `178e26a` прошла, но immutable pushed SHA и green remote CI ещё не подтверждены |

Status values: `Not started` → `In progress` → `In review` → `Verified`.

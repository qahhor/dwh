# Release implementation tracker

Source of truth до завершения нового evidence bundle:
`audit/00-master-improvement-plan-2026-08-31.md`. Статус обновлён 2026-09-02 для
единого open-source SmartupCMS. Control Plane/Fleet Foundation удалены из
поддерживаемого runtime и остаются только историческим evidence. `Verified`
разрешён только для immutable pushed SHA с зелёным remote CI и clean deployment;
поэтому локально реализованные пункты остаются `In review`.

| ID | Карточка | Priority | Effort | Owner | Status | Текущее evidence / остаток |
|---|---|---|---|---|---|---|
| P0-01/P0-08 | [T-01 release gates](T-01-release-gates.md) | P0 | M | TBD | In review | Локально: backend 194/194, frontend 68/68, typecheck/build, Compose/docs/actionlint green; clean E2E и remote required checks выполняются после push |
| P0-02 | [S-01 SSO](S-01-sso-disable-or-verify.md) | P0 | S/M | TBD | In review | Публичный exchange удалён, providers выключены V018; полноценный OIDC не входит в release scope |
| P0-03 | [S-02 module callback](S-02-module-callback-hardening.md) | P0 | S/M | TBD | In review | Публичный moderation callback удалён; mutation остаётся только в authenticated CP flow, service audit tests green |
| P0-04 | [S-03 markdown XSS](S-03-markdown-xss.md) | P0 | M | TBD | In review | Unsafe bypass удалён, URL sanitizer и strict CSP добавлены; unit/build/E2E green |
| P0-05 | [S-04 search authorization](S-04-search-authorization.md) | P0 | M | TBD | In review | Global search fail-closed для non-wildcard admin до реализации data-scope filtering |
| P0-06 | [D-01 production topology](D-01-production-topology.md) | P0 | M | TBD | In review | Unified web/server/PostgreSQL/Typesense/backup topology; release-config и V018→V019 production rehearsal green; внешний Hetzner/Cloudflare TLS не подтверждён |
| P0-07 | [D-02 deploy recovery](D-02-deploy-recovery.md) | P0 | M | TBD | In progress | Encrypted V018 backup before V019 and HTTP 200 green; target restore, automatic previous-digest rollback и file/object recovery отсутствуют |
| P0-09 | Security artifact | P0 | S | TBD | In review | Gitleaks working tree 0; Trivy dependencies и 5 runtime images 0 HIGH/CRITICAL; нужен remote artifact/signature от release tag |
| CP-FF-01 | [Historical Fleet Foundation evidence](../evidence/fleet-foundation-cp-contract-2026-09-01.md) | — | — | — | Superseded | Control Plane/Fleet runtime удалён; evidence не является частью текущего release contour |
| P1 | [S-05 webhook hardening](S-05-webhook-hardening.md) | P1 | M | TBD | In review | Fail-closed, exact allow-list, SSRF ranges, redirect/timeout/redaction/secret-once; focused and full backend tests green; target egress firewall остаётся installation control |
| P1 | [W-01 a11y consistency](W-01-a11y-consistency.md) | P1 | M | TBD | In review | Семантика/labels/focus/states исправлены; axe critical/serious, keyboard и narrow viewport добавлены в browser suite; clean E2E после push |
| P1 | [P-01 performance baseline](P-01-performance-baseline.md) | P1 | M | TBD | Not started | Нет representative dataset и согласованного SLO |
| P1 | [A-01 docs source of truth](A-01-documentation-source-of-truth.md) | P1 | M | TBD | In progress | Run/deploy/backup/restore/CI исправлены; полный broken-link/API contract audit не закрыт |

Status values: `Not started` → `In progress` → `In review` → `Verified`.

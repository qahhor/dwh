# Release implementation tracker

Source of truth: `audit/00-master-improvement-plan-2026-08-31.md`. Статус обновлён 2026-09-01 после локальной реализации и clean-slate verification. `Verified` по-прежнему разрешён только для immutable RC с зелёным remote CI; поэтому реализованные локально пункты остаются `In review`.

| ID | Карточка | Priority | Effort | Owner | Status | Текущее evidence / остаток |
|---|---|---|---|---|---|---|
| P0-01/P0-08 | [T-01 release gates](T-01-release-gates.md) | P0 | M | TBD | In review | Локально: backend 163/163, frontend 60/60, build/typecheck, E2E 8/8; нужен pushed SHA и remote required checks |
| P0-02 | [S-01 SSO](S-01-sso-disable-or-verify.md) | P0 | S/M | TBD | In review | Публичный exchange удалён, providers выключены V018; полноценный OIDC не входит в release scope |
| P0-03 | [S-02 module callback](S-02-module-callback-hardening.md) | P0 | S/M | TBD | In review | Публичный moderation callback удалён; mutation остаётся только в authenticated CP flow, service audit tests green |
| P0-04 | [S-03 markdown XSS](S-03-markdown-xss.md) | P0 | M | TBD | In review | Unsafe bypass удалён, URL sanitizer и strict CSP добавлены; unit/build/E2E green |
| P0-05 | [S-04 search authorization](S-04-search-authorization.md) | P0 | M | TBD | In review | Global search fail-closed для non-wildcard admin до реализации data-scope filtering |
| P0-06 | [D-01 production topology](D-01-production-topology.md) | P0 | M | TBD | In review | Clean fleet 7/7 healthy; proxy non-root, `nginx -t` и `/healthz=200`; внешний TLS host не подтверждён |
| P0-07 | [D-02 deploy recovery](D-02-deploy-recovery.md) | P0 | M | TBD | In progress | Backup обеих DB, SHA, fail-closed test и restore drill green; автоматический rollback previous digest и file/object backup отсутствуют |
| P0-09 | Security artifact | P0 | S | TBD | In review | Gitleaks 139 commits: 0; семь runtime images Trivy: 0 HIGH/CRITICAL; SBOM задан в CI, нужен artifact от pushed SHA |
| P1 | [S-05 webhook hardening](S-05-webhook-hardening.md) | P1 | M | TBD | Not started | SSRF/timeout/secret test matrix не выполнена |
| P1 | [W-01 a11y consistency](W-01-a11y-consistency.md) | P1 | M | TBD | In review | Семантика/labels/focus/states исправлены, unit+E2E green; axe gate не добавлен |
| P1 | [P-01 performance baseline](P-01-performance-baseline.md) | P1 | M | TBD | Not started | Нет representative dataset и согласованного SLO |
| P1 | [A-01 docs source of truth](A-01-documentation-source-of-truth.md) | P1 | M | TBD | In progress | Run/deploy/backup/restore/CI исправлены; полный broken-link/API contract audit не закрыт |

Status values: `Not started` → `In progress` → `In review` → `Verified`.

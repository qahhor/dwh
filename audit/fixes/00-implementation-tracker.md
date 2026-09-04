# Release implementation tracker

Source of truth: [каноническое ТЗ](../../docs/technical-specification.md) и
[ADR-0014](../../docs/adr/ADR-0014-unified-open-source-runtime.md). Статус
обновлён 2026-09-05 для единого open-source SmartupCMS. Control Plane/Fleet
Foundation удалены из
поддерживаемого runtime и остаются только историческим evidence. `Verified`
разрешён только для immutable pushed SHA с зелёным remote CI и clean deployment;
поэтому локально реализованные пункты остаются `In review`.

| ID | Карточка | Priority | Effort | Owner | Status | Текущее evidence / остаток |
|---|---|---|---|---|---|---|
| P0-01/P0-08 | [T-01 release gates](T-01-release-gates.md) | P0 | M | TBD | In progress | `main`/`1b24d3f`: remote frontend/backend green, но run `33833803385` red в release-config/security. Локальный patch: Maven 214/214, Trivy backend/web/E2E 0 HIGH/CRITICAL, 7/7 contract gates и actionlint green; нужен полный remote green на новом SHA |
| P0-02 | [S-01 SSO](S-01-sso-disable-or-verify.md) | P0 | S/M | TBD | In review | Публичный exchange удалён, providers выключены V018; полноценный OIDC не входит в release scope |
| P0-03 | [S-02 module callback](S-02-module-callback-hardening.md) | P0 | S/M | TBD | In review | Публичный moderation callback удалён вместе с retired Control Plane runtime; server-side mutation/audit tests входят в Maven green suite |
| P0-04 | [S-03 markdown XSS](S-03-markdown-xss.md) | P0 | M | TBD | In review | Unsafe bypass удалён, URL sanitizer и strict CSP добавлены; unit/build/E2E green |
| P0-05 | [S-04 search authorization](S-04-search-authorization.md) | P0 | M | TBD | In review | Global search fail-closed для non-wildcard admin до реализации data-scope filtering |
| P0-06 | [D-01 production topology](D-01-production-topology.md) | P0 | M | TBD | In review | Unified web/server/PostgreSQL/Typesense/backup topology; release-config и V018→V019 production rehearsal green; внешний Hetzner/Cloudflare TLS не подтверждён |
| P0-07 | [D-02 deploy recovery](D-02-deploy-recovery.md) | P0 | M | TBD | In progress | Encrypted V018 backup before V019 and HTTP 200 green; target restore, automatic previous-digest rollback и file/object recovery отсутствуют |
| P0-09 | Security artifact | P0 | S | TBD | In progress | Tomcat 11.0.25 и resolved-SBOM Trivy patch локально дают 0 HIGH/CRITICAL; нужен green remote security job и signed artifact от stable release tag |
| P0-11 | File ingress contract | P0 | S | TBD | In progress | Spring 50/51 MiB, NGINX 51m, stable 413 handler и config tests реализованы; требуется clean browser evidence для success boundary и >50 MiB через production origin |
| P0-12 | File pipeline isolation | P0 | M | TBD | In review | Quarantine/scan/copy выполняются вне короткой `MfFileMetadataService.publish` transaction; boundary/concurrency tests входят в Maven green suite, remote full CI ещё red |
| P0-13 | Production file scanner | P0 | S/M | TBD | In review | Production Compose требует активный ClamAV и ждёт его health; startup/EICAR/quarantine cleanup tests green локально, target outage smoke остаётся installation evidence |
| P0-14 | Task/file data scope | P0 conditional | M | TBD | In review | ADR-0013 фиксирует `ALL/SUBTREE/UNITS/SELF`; task/comment/file list, direct reads и mutations используют SQL row-scope, роль пересчитывает effective scope в той же транзакции; PostgreSQL policy/direct-API tests и clean Docker/browser 24/24 green локально. Remote run `33909868657` подтвердил все jobs кроме browser E2E и выявил общий anonymous/i18n NAT bucket; regression patch локально green, нужны immutable push и полностью green remote CI |
| P0-15 | Target acceptance | P0 | M | TBD | In review | Реализованы non-secret managed template, external/host fail-closed preflight, private R2 policy/round-trip, digest deployment overrides, 100-user/20-upload/4h k6 profiles, scanner/database/backup drills, scanner/R2 latency metrics, encrypted object backup, isolated combined restore и published-release verifier. Реальный Hetzner/Cloudflare/R2/alert/load/negative-restore evidence отсутствует и остаётся `UNVERIFIED` |
| CP-FF-01 | Historical Fleet Foundation evidence (удалено в `c6a1181`, доступно только в предшествующей истории Git) | — | — | — | Superseded | Control Plane/Fleet runtime удалён; historical evidence не является частью текущего release contour |
| P1 | [S-05 webhook hardening](S-05-webhook-hardening.md) | P1 | M | TBD | In review | Fail-closed, exact allow-list, SSRF ranges, redirect/timeout/redaction/secret-once; focused and full backend tests green; target egress firewall остаётся installation control |
| P1 | [W-01 a11y consistency](W-01-a11y-consistency.md) | P1 | M | TBD | In review | Семантика/labels/focus/states исправлены; axe critical/serious, keyboard и narrow viewport включены; clean browser E2E 9/9 |
| P1 | [P-01 performance baseline](P-01-performance-baseline.md) | P1 | M | TBD | Not started | Нет representative dataset и согласованного SLO |
| P1 | [A-01 docs source of truth](A-01-documentation-source-of-truth.md) | P1 | M | TBD | In progress | Run/deploy/backup/restore/CI исправлены; полный broken-link/API contract audit не закрыт |
| P1-HYGIENE | Repository hygiene | P1 | S | TBD | In review | [Design](../../docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md), [plan](../../docs/superpowers/plans/2026-09-03-repository-cleanup-and-documentation-refresh.md), [ТЗ](../../docs/technical-specification.md), [ADR-0014](../../docs/adr/ADR-0014-unified-open-source-runtime.md), [public-docs contract](../../scripts/docs/test-public-docs.ps1), [repository-hygiene contract](../../scripts/docs/test-repository-hygiene.ps1) и `audit/health-check-2026-09-04.md` фиксируют единый runtime; локальные contracts green, но immutable pushed SHA и green remote CI ещё не подтверждены |

Status values: `Not started` → `In progress` → `In review` → `Verified`.
